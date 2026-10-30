package org.openstrap.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openstrap.app.data.db.ActivityExtendedMinute
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.DailyAggregate
import org.openstrap.app.data.db.DailyMaxHr
import org.openstrap.app.data.db.DailyPai
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.data.db.HrvSdnnSample
import org.openstrap.app.data.db.OsaEventRow
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.SleepSpo2Sample
import org.openstrap.app.data.db.BreathRate
import org.openstrap.app.data.db.CheckInRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.data.db.SkinTemperature
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.Spo2Sample
import org.openstrap.app.data.db.StressSample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A [from, to] window in epoch seconds covering one local calendar day. */
data class DayWindow(val from: Long, val to: Long) {
    companion object {
        fun of(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayWindow {
            val from = date.atStartOfDay(zone).toEpochSecond()
            val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
            return DayWindow(from, to)
        }

        /** Sleep that *ended* on [date]: from 18:00 the previous evening until 18:00 on [date]. */
        fun nightEndingOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayWindow {
            val to = date.atTime(18, 0).atZone(zone).toEpochSecond()
            return DayWindow(to - 24 * 3600, to)
        }
    }
}

class HealthRepository(private val dao: HealthDao) {
    private val recoveryEngine = RecoveryEngine(dao)
    private val healthspanEngine = HealthspanEngine(dao)
    /** The one reader of sleep for everything shown ([SleepSource]). */
    val sleep = SleepSource(dao)

    /**
     * Readiness and charge for [date]; heavy, so call it off the main thread. Also logs the night's own inputs next to
     * the strap's ([NightMetricsRow]) for the calibration review, and keeps the day's energy ([DailyEnergyHistory]).
     */
    suspend fun recovery(date: LocalDate, profile: org.openstrap.app.data.settings.UserProfile): RecoveryResult {
        val r = recoveryEngine.compute(date, profile)
        r.tonight?.let { logNight(it) }
        r.energy?.let { dao.insertDailyEnergy(listOf(DailyEnergyHistory.row(DayWindow.of(date).from, it, System.currentTimeMillis() / 1000))) }
        return r
    }

    /** When you usually go to sleep as of [date] ([RecoveryResult.usualBedtime]), without working out Recovery. */
    suspend fun usualBedtime(date: LocalDate): java.time.LocalTime? = recoveryEngine.usualBedtime(date)

    /** The weekly Healthspan estimate shown on [date] (made the Sunday on or before it), or why it's withheld; heavy. */
    suspend fun healthspan(date: LocalDate, profile: org.openstrap.app.data.settings.UserProfile): Healthspan.Outcome =
        healthspanEngine.compute(date, profile)

    private suspend fun logNight(n: Recovery.Night) {
        val strap = dao.strapReadinessOnce(n.day, n.day).firstOrNull()
        dao.insertNightMetrics(listOf(NightMetrics.row(n, strap, System.currentTimeMillis() / 1000)))
    }

    /** The morning check-in for [date], 1-5, or null before the user answered. */
    fun checkIn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<CheckInRow?> = dao.checkIn(DayWindow.of(date, zone).from)

    /** Saves the check-in for [date]; null clears it. */
    suspend fun saveCheckIn(date: LocalDate, feeling: Int?, zone: ZoneId = ZoneId.systemDefault()) {
        val day = DayWindow.of(date, zone).from
        if (feeling == null) dao.deleteCheckIn(day)
        else dao.insertCheckIns(listOf(CheckInRow(day, feeling.coerceIn(CheckIn.MIN, CheckIn.MAX), System.currentTimeMillis() / 1000)))
    }


    /**
     * Emits whenever new heart-rate, stress or sleep data lands, a strap workout comes or goes, or a nap's times are
     * changed, so derived scores can refresh.
     */
    fun changes(): Flow<Unit> = kotlinx.coroutines.flow.combine(
        dao.latestHeartRateTime(), dao.latestStressTime(), dao.latestSleepEnd(), dao.workoutCountFlow().distinctUntilChanged(),
        dao.sleepEdits().distinctUntilChanged(),
    ) { _, _, _, _, _ -> }

    /** Local days that have any heart rate, steps or sleep, for the calendar. */
    fun daysWithData(zone: ZoneId = ZoneId.systemDefault()): Flow<Set<LocalDate>> = dao.hoursWithData().map { hours ->
        hours.mapTo(HashSet()) { Instant.ofEpochSecond(it * 3600).atZone(zone).toLocalDate() }
    }

    fun heartRate(w: DayWindow): Flow<List<HeartRateSample>> = dao.heartRate(w.from, w.to)
    fun activity(w: DayWindow): Flow<List<ActivityMinute>> = dao.activity(w.from, w.to)
    /** Sleep and naps as shown, ending in [w]: with the user's times, deleted ones left out ([SleepSource]). */
    fun sleepSessions(w: DayWindow): Flow<List<SleepSession>> = sleep.sessions(w.from, w.to)
    /** Each strap session ending in [w] with its edit, deleted ones too, for the screens that change them. */
    fun sleepResolved(w: DayWindow): Flow<List<SleepEdits.Resolved>> = sleep.resolved(w.from, w.to)
    /** [r]'s stages as shown. */
    fun sleepStages(r: SleepEdits.Resolved): Flow<List<SleepStage>> = sleep.stages(r)
    /** The strap's own stages for its session [strap], unclipped. */
    fun strapStages(strap: SleepSession): Flow<List<SleepStage>> = sleep.strapStages(strap)
    fun sleepEdits(): Flow<List<org.openstrap.app.data.db.SleepEdit>> = dao.sleepEdits()

    /** Keeps [start] to [end] as the strap's session [strap]'s times; its own times again drop the edit. */
    suspend fun setSleepTimes(strap: SleepSession, start: Long, end: Long) {
        val current = SleepEdits.resolve(listOf(strap), dao.sleepEditsOnce()).single().edit
        val e = SleepEdits.edit(strap, start, end, current, System.currentTimeMillis() / 1000)
        if (e == null) dao.deleteSleepEdit(current?.strapStart ?: strap.start) else dao.upsertSleepEdit(e)
    }
    fun spo2(w: DayWindow): Flow<List<Spo2Sample>> = dao.spo2(w.from, w.to)
    /**
     * The day's stress readings that count as daily stress: those outside the exclusion around workouts
     * ([Recovery.stressExcluded]), so the chart shows a gap there and averages and highs leave them out. The raw
     * readings stay in the database and the export.
     */
    fun stress(w: DayWindow): Flow<List<StressSample>> =
        combine(dao.stress(w.from, w.to), stressExcluded(w.from, w.to)) { s, excluded -> s.filter { Recovery.stressCounts(it.timestamp, excluded) } }

    /** The stress exclusion for workouts touching [from]..[to]; one that ended up to 20 minutes before [from] reaches in. */
    private fun stressExcluded(from: Long, to: Long): Flow<List<LongRange>> {
        val since = from - Recovery.STRESS_AFTER_WORKOUT_SECONDS
        return combine(dao.appWorkoutsIn(since, to), dao.workoutsIn(since, to)) { phone, strap ->
            Recovery.stressExcluded(Recovery.workoutRanges(phone, strap, System.currentTimeMillis() / 1000))
        }
    }
    fun hrv(w: DayWindow): Flow<List<HrvSample>> = dao.hrv(w.from, w.to)
    fun restingHr(from: Long, to: Long): Flow<List<RestingHeartRate>> = dao.restingHr(from, to)
    fun skinTemperature(w: DayWindow): Flow<List<SkinTemperature>> = dao.skinTemperature(w.from, w.to)
    fun breathRate(w: DayWindow): Flow<List<BreathRate>> = dao.breathRate(w.from, w.to)
    fun latestPai(before: Long): Flow<PaiSample?> = dao.latestPai(before)

    fun sleepSpo2(w: DayWindow): Flow<List<SleepSpo2Sample>> = dao.sleepSpo2(w.from, w.to)
    fun hrvSdnn(w: DayWindow): Flow<List<HrvSdnnSample>> = dao.hrvSdnn(w.from, w.to)
    fun osaEvents(w: DayWindow): Flow<List<OsaEventRow>> = dao.osaEvents(w.from, w.to)
    fun activityExtended(w: DayWindow): Flow<List<ActivityExtendedMinute>> = dao.activityExtended(w.from, w.to)
    fun healthAlerts(w: DayWindow): Flow<List<HealthAlertRow>> = dao.healthAlerts(w.from, w.to)
    fun dailyMaxHr(w: DayWindow): Flow<DailyMaxHr?> = dao.dailyMaxHr(w.from, w.to).map { it.lastOrNull() }

    /** The strap's sleep-apnea summary for the night ending on [date]: its belongTime + 2 h falls on that date. */
    fun osaResult(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<OsaResultRow?> {
        val d = DayWindow.of(date, zone)
        return dao.osaResults(d.from - 2 * 3600, d.to - 2 * 3600).map { it.lastOrNull() }
    }

    /** One value per local day from [from] to [to], for week, month and year views. Days without data are absent. */
    fun daily(metric: DailyMetric, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<List<DayStat>> {
        val a = DayWindow.of(from, zone).from
        val b = DayWindow.of(to, zone).to
        // The strap stores one resting HR per day already, keyed by local midnight.
        if (metric == DailyMetric.RestingHr) return dao.restingHr(a, b).map { rows ->
            rows.map { r ->
                val bpm = r.bpm.toFloat()
                DayStat(Instant.ofEpochSecond(r.day).atZone(zone).toLocalDate(), bpm, bpm, bpm, 1)
            }
        }
        // Stress leaves out readings in and just after workouts, so it's aggregated here rather than in SQL.
        if (metric == DailyMetric.Stress) return stress(DayWindow(a, b)).map { s ->
            s.groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }.toSortedMap().map { (d, v) ->
                val values = v.map { it.value.toFloat() }
                DayStat(d, values.average().toFloat(), values.min(), values.max(), values.size)
            }
        }
        val rows = when (metric) {
            DailyMetric.RestingHr, DailyMetric.Stress -> error("handled above")
            DailyMetric.HeartRate -> dao.dailyHeartRate(a, b)
            DailyMetric.Steps -> dao.dailySteps(a, b)
            DailyMetric.Hrv -> dao.dailyHrv(a, b)
            DailyMetric.HrvSdnn -> dao.dailyHrvSdnn(a, b)
            DailyMetric.Spo2 -> dao.dailySpo2(a, b)
            DailyMetric.SleepSpo2 -> dao.dailySleepSpo2(a, b)
            DailyMetric.SkinTemperature -> dao.dailySkinTemperature(a, b)
            DailyMetric.Breathing -> dao.dailyBreathRate(a, b)
        }
        return rows.map { list -> list.mapNotNull(DayStat::of) }
    }

    /**
     * Each local day's steps hour by hour, from [from] to [to], keyed by date; days without a step record are absent.
     * Read once, off the main thread, for your usual steps by this time of day.
     */
    suspend fun hourlySteps(from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, List<Float>> =
        dao.hourlyStepsOnce(DayWindow.of(from, zone).from, DayWindow.of(to, zone).to).groupBy { it.day }.mapNotNull { (day, hours) ->
            DayStat.parseDay(day)?.let { d ->
                val buckets = FloatArray(24)
                hours.forEach { if (it.hour in 0..23) buckets[it.hour] += it.steps.toFloat() }
                d to buckets.toList()
            }
        }.toMap()

    /** Each day's PAI (earned that day, and the 7-day total), keyed by local date. */
    fun dailyPai(from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<Map<LocalDate, DailyPai>> =
        dao.dailyPai(DayWindow.of(from, zone).from, DayWindow.of(to, zone).to).map { rows ->
            rows.mapNotNull { r -> DayStat.parseDay(r.day)?.let { it to r } }.toMap()
        }

    /** Each day's stored energy from [from] to [to] ([DailyEnergyHistory]), by local day, for Calories' ranges. */
    fun dailyEnergy(from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<Map<LocalDate, org.openstrap.app.data.db.DailyEnergyRow>> =
        dao.dailyEnergy(DayWindow.of(from, zone).from, DayWindow.of(to, zone).from).map { rows ->
            rows.associateBy { Instant.ofEpochSecond(it.day).atZone(zone).toLocalDate() }
        }

    /**
     * The calorie goal the last [org.openstrap.app.data.settings.CalorieGoal.DAYS] suggest: from the active kcal of
     * their full days, today left out ([org.openstrap.app.data.settings.CalorieGoal.suggested]). Null under a week of them.
     */
    suspend fun suggestedCalorieGoal(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Int? {
        val days = org.openstrap.app.data.settings.CalorieGoal.DAYS.toLong()
        val rows = dao.dailyEnergyOnce(DayWindow.of(today.minusDays(days), zone).from, DayWindow.of(today.minusDays(1), zone).from)
        return org.openstrap.app.data.settings.CalorieGoal.suggested(rows.filter { !it.partial }.map { it.activeKcal })
    }
}

/** Sampled metrics that have a per-day series. */
enum class DailyMetric { HeartRate, RestingHr, Steps, Hrv, HrvSdnn, Spo2, SleepSpo2, Stress, SkinTemperature, Breathing }

/** One local day of a metric: the average (the total, for steps), lowest and highest sample, and how many samples. */
data class DayStat(val date: LocalDate, val value: Float, val min: Float, val max: Float, val count: Int) {
    companion object {
        fun parseDay(day: String): LocalDate? = runCatching { LocalDate.parse(day) }.getOrNull()

        fun of(row: DailyAggregate): DayStat? =
            parseDay(row.day)?.let { DayStat(it, row.value.toFloat(), row.min.toFloat(), row.max.toFloat(), row.count) }
    }
}
