package org.openstrap.app.data

import org.openstrap.app.data.db.HabitAgeWeekRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.UserProfile
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Loads six months of daily inputs for [Healthspan] and runs it. The estimate is weekly: as of local midnight of the
 * latest Sunday, over the [Healthspan.WINDOW_DAYS] before it, so it holds still for the week. Heavy; call off the main
 * thread. The last result is kept until a sync adds data to its window.
 */
class HealthspanEngine(private val dao: HealthDao) {
    /** Sleep as shown, with the user's edits ([SleepEdits]). */
    private val sleepSource = SleepSource(dao)
    private data class Key(val asOf: Long, val profile: UserProfile, val fingerprint: Long)
    private var cached: Pair<Key, Healthspan.Outcome>? = null

    /**
     * The Sunday on or before [date]: the day the estimate it shows was made. Sunday whatever the locale, not
     * [Weeks], because the weekly schedule follows WHOOP's.
     */
    fun sundayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    suspend fun compute(date: LocalDate, profile: UserProfile, zone: ZoneId = ZoneId.systemDefault()): Healthspan.Outcome {
        val sunday = sundayOf(date)
        val asOf = DayWindow.of(sunday, zone).from
        val from = DayWindow.of(sunday.minusDays(Healthspan.WINDOW_DAYS.toLong()), zone).from
        val key = Key(asOf, profile, dao.dataFingerprint(from, asOf - 1))
        cached?.let { (k, v) -> if (k == key) return v }
        val age = profile.age(sunday)
        val d = daily(sunday, from, asOf, zone)
        val (result, withheld) = Healthspan.estimate(profile, age, d)
        val missing = listOfNotNull("birth year".takeIf { profile.birthYear == null }, "sex".takeIf { profile.sex == null })
        // This week's result is kept as shown, so next week can compare with it. A past week is never written again:
        // looking back at a day must not replace what that Sunday showed.
        result?.takeIf { sunday == sundayOf(LocalDate.now(zone)) }?.let {
            dao.insertHabitAgeWeek(HabitAgeWeekRow(sunday, it.estimate, it.low, it.high, System.currentTimeMillis() / 1000))
        }
        val weeks = dao.habitAgeWeeksOnce(sunday.minusDays(8), sunday.minusDays(1)).map { Days.start(it.asOf, zone) to it.estimate }
        val outcome = Healthspan.Outcome(
            result, withheld, age, missing, d.firstDay?.let { firstEstimate(it, zone) }?.takeIf { withheld == Healthspan.Withheld.Learning },
            lastWeek = Healthspan.lastWeek(weeks, asOf),
        )
        cached = key to outcome
        return outcome
    }

    /** Local midnight of the first Sunday with [Healthspan.EARLY_DAYS] of data after [firstDay], as [Healthspan.estimate] counts them. */
    private fun firstEstimate(firstDay: Long, zone: ZoneId): Long {
        val t = firstDay + (Healthspan.EARLY_DAYS * 86_400L - 43_200L)
        var d = java.time.Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
        if (DayWindow.of(d, zone).from < t) d = d.plusDays(1)
        return DayWindow.of(d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), zone).from
    }

    /**
     * Each of [nights]' heart rate by the minute, by its start: read a week of nights at a time and sliced, rather than
     * two reads a night, while no read holds more than a week of minutes.
     */
    private suspend fun nightHeartRates(nights: Collection<org.openstrap.app.data.db.SleepSession>): Map<Long, List<Pair<Long, Int>>> {
        val out = HashMap<Long, List<Pair<Long, Int>>>()
        val sorted = nights.sortedBy { it.start }
        var i = 0
        while (i < sorted.size) {
            val first = sorted[i].start
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1].end - first <= HR_READ_SPAN_S) j++
            val group = sorted.subList(i, j + 1)
            val to = group.maxOf { it.end }
            val hr = dao.heartRateOnce(first, to)
            val activity = dao.activityOnce(first, to)
            for (s in group) {
                val night = hr.within(s.start, s.end) { it.timestamp }
                val series = Summaries.heartRateSeries(night, activity.within(s.start, s.end) { it.timestamp })
                out[s.start] = Summaries.bucketAverage(series, 60)
            }
            i = j + 1
        }
        return out
    }

    /** Every input over [from, asOf), keyed as [Healthspan.Daily] wants. */
    private suspend fun daily(sunday: LocalDate, from: Long, asOf: Long, zone: ZoneId): Healthspan.Daily {
        val to = asOf - 1
        val worn = dao.wornMinutesOnce(from, to).toLongArray().also { it.sort() }
        fun wornAt(t: Long) = worn.binarySearch(t / 60) >= 0

        // Local midnights of the window's days, oldest first, and the worn minutes in each.
        val days = (Healthspan.WINDOW_DAYS downTo 1).map { sunday.minusDays(it.toLong()) }
        val starts = days.map { DayWindow.of(it, zone).from }
        val wornPerDay = IntArray(days.size)
        run {
            var d = 0
            for (m in worn) {
                val t = m * 60
                while (d + 1 < starts.size && t >= starts[d + 1]) d++
                if (t >= starts[d]) wornPerDay[d]++
            }
        }

        // Steps: the sum of synced minutes, on days worn long enough.
        val stepsByDate = dao.dailyStepsOnce(from, to).mapNotNull { r -> DayStat.parseDay(r.day)?.let { it to r.value.toInt() } }.toMap()
        val steps = days.indices.mapNotNull { i ->
            stepsByDate[days[i]]?.takeIf { wornPerDay[i] >= Healthspan.STEP_DAY_WORN_MINUTES }?.let { starts[i] to it }
        }.toMap()

        // Main sleeps: the longest non-nap session of each day it ended on, as Recovery picks them.
        val resolved = sleepSource.resolvedOnce(from, to)
        val sessions = sleepSource.shown(resolved)
        val mains = RecoveryEngine.mainsByMorning(sessions, zone).filterKeys { it in from until asOf }
        val asleep = mains.mapValues { RecoveryEngine.asleepMinutes(it.value) }

        // Resting HR: the logged lowest 30-minute mean, else worked out from the night's heart rate the same way.
        val logged = DayKey.datesIn(from, to, zone).let { dao.nightMetricsOnce(it.start, it.endInclusive) }
            .associateBy { Days.start(it.day, zone) }
        val valid = mains.filterValues { s -> s.end - s.start >= Recovery.MIN_VALID_SLEEP_MINUTES * 60L }
        val nightHr = nightHeartRates(valid.filterKeys { logged[it]?.rhrLowest30 == null }.values)
        val restingHr = valid.mapNotNull { (morning, s) ->
            val row = logged[morning]
            val rhr = if (row?.rhrLowest30 != null) row.rhrLowest30.takeIf { row.hrMinutes >= Recovery.MIN_HR_MINUTES } else {
                val hr = nightHr[s.start].orEmpty().filter { it.second in Recovery.HR_MIN_BPM..Recovery.HR_MAX_BPM }
                if (hr.size < Recovery.MIN_HR_MINUTES) null else Recovery.lowestAverage(hr, 30, Recovery.RHR_WINDOW_MIN_MINUTES)?.second
            }
            rhr?.let { morning to it }
        }.toMap()

        // Sleep regularity: one SRI per week, each over 8 noon-to-noon grid days ending at a Saturday noon.
        // Each session's stages from its resolved edit, which is at hand, so finding its edit again isn't a read per night.
        val stages = resolved.filter { it.shown != null }
            .flatMap { r -> sleepSource.stagesOnce(r).map { Triple(it.start, it.end, it.stage) } }
        val weeks = (Healthspan.WINDOW_DAYS - 1) / 7
        val weeklySri = (0 until weeks).mapNotNull { k ->
            val end = sunday.minusDays(7L * k + 1)
            val grids = (SleepRegularity.SRI_DAYS + 1 downTo 1).map { back ->
                val noon = end.minusDays(back.toLong()).atTime(SleepRegularity.DAY_START_HOUR, 0).atZone(zone).toEpochSecond()
                SleepRegularity.grid(noon, stages.filter { it.second > noon && it.first < noon + 86_400 }, ::wornAt)
            }
            SleepRegularity.sri(grids).first?.let { DayWindow.of(end, zone).from to it }
        }.toMap()

        // Workouts: strength minutes, and the strap's own VO2max (the workout's, else its sport effect's).
        val workouts = dao.workoutsOnce(from, to).filter { it.start in from until asOf }
        val effects = dao.sportEffectsOnce(from, to + 12 * 3600)
        val strength = workouts.filter { it.sportType in Healthspan.STRENGTH_SPORTS }
            .associate { w -> w.start to ((w.exerciseSeconds ?: (w.end - w.start).toInt()) / 60f) }
        val vo2 = workouts.mapNotNull { w ->
            val effect = Workouts.analyse(w, emptyList(), effects, 0, HeartZones.STANDARD).effect
            (w.vo2max?.toFloat() ?: effect?.vo2max)?.takeIf { it > 0f }?.let { w.start to it }
        }.toMap()

        return Healthspan.Daily(
            asOf = asOf, restingHr = restingHr, steps = steps, weeklySri = weeklySri, asleepMinutes = asleep,
            strength = strength, vo2max = vo2, firstDay = worn.firstOrNull()?.let { it * 60 } ?: mains.keys.minOrNull(),
        )
    }

    private companion object {
        /** The most a heart rate read for the nights spans: a week. */
        const val HR_READ_SPAN_S = 7 * 86_400L
    }
}
