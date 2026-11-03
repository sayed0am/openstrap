package org.openstrap.app.data

import kotlinx.coroutines.flow.first
import org.openstrap.app.data.db.DailyEnergyRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.restingHrIn
import org.openstrap.app.data.settings.UserProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps [DailyEnergyRow] for every day with data, so week, month and year views read a row rather than run the model.
 * The day shown in the app writes its own row each time its Recovery is worked out ([HealthRepository]). This fills
 * the rest after a sync: days without a row (the first run backfills them all), the last [RECENT_DAYS] (a sync can
 * still bring their minutes or a workout), and every day when the profile has changed. Phone workouts from before
 * version 20 get their kcal first, so their days count it, and after a profile change every phone workout's kcal is
 * worked out again first, so a day never fits its minutes to a total from the old profile.
 *
 * Both writers work a day out with [energy], so a day's page and its bar in the week show the same kcal (data
 * integrity item 18).
 */
class DailyEnergyHistory(private val dao: HealthDao) {
    private val engine = RecoveryEngine(dao)

    /** The days written. */
    suspend fun update(
        profile: UserProfile, profileChanged: Boolean, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis() / 1000,
    ): Int {
        if (EnergyExpenditure.bmrPerMinute(profile, profile.age()) == null) return 0
        val effects = WorkoutEffectEngine(dao)
        // A new profile moves every phone workout's kcal, and the days fit their minutes to it, so those go first.
        for (w in dao.appWorkoutsAll()) if (w.end != null && (w.totalKcal == null || profileChanged)) {
            runCatching { effects.backfillKcal(w, profile, zone, again = profileChanged) }
        }

        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val have = dao.dailyEnergyDaysOnce().toHashSet()
        val days = dao.hoursWithData().first().mapTo(sortedSetOf()) { Instant.ofEpochSecond(it * 3600).atZone(zone).toLocalDate() }
            .filter { d -> d <= today && (profileChanged || d > today.minusDays(RECENT_DAYS.toLong()) || d !in have) }
        if (days.isEmpty()) return 0

        // Max HR is today's for every day, so it's worked out once, and VO2max once a week ([energy]).
        val maxHr = engine.maxHeartRate(profile, today, zone)
        val vo2 = HashMap<LocalDate, Float?>()
        val firstDay = Weeks.firstDay()
        var written = 0
        for (d in days) {
            val v = vo2.getOrPut(Weeks.start(d, firstDay)) { runCatching { engine.vo2max(profile, d, zone, firstDay) }.getOrNull() }
            if (write(d, profile, maxHr, v, zone, now)) written++
        }
        return written
    }

    /**
     * Writes the rows of the days from [from] to [to] (epoch seconds) again, for a phone workout edited, trimmed or
     * deleted there: its kcal moved, and the day's must follow without waiting for a sync. The day shown rewrites its
     * own anyway.
     */
    suspend fun updateDays(from: Long, to: Long, profile: UserProfile, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis() / 1000) {
        if (EnergyExpenditure.bmrPerMinute(profile, profile.age()) == null) return
        val first = Instant.ofEpochSecond(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochSecond(maxOf(from, to)).atZone(zone).toLocalDate()
        val maxHr = engine.maxHeartRate(profile, Instant.ofEpochSecond(now).atZone(zone).toLocalDate(), zone)
        var d = first
        while (d <= last) {
            write(d, profile, maxHr, runCatching { engine.vo2max(profile, d, zone) }.getOrNull(), zone, now)
            d = d.plusDays(1)
        }
    }

    /** Works out [d] ([energy]) and writes its row. False when the model can't. */
    private suspend fun write(d: LocalDate, profile: UserProfile, maxHr: Float, vo2max: Float?, zone: ZoneId, now: Long): Boolean {
        val e = runCatching { energy(d, profile, maxHr, vo2max, zone, now) }.getOrNull() ?: return false
        dao.insertDailyEnergy(listOf(row(d, e, now)))
        return true
    }

    /**
     * [d]'s energy, the one rule its row and its page both follow: its own minutes ([RecoveryEngine.energy]) on the
     * strap's daytime resting HR of the [Recovery.RHR_DAYS] up to it, [maxHr] as of today
     * ([RecoveryEngine.maxHeartRate]) and [vo2max] as of its week ([RecoveryEngine.vo2max]). Max HR is today's for
     * every day, so a sync that rewrites a week of rows reads 90 days of heart rate once, not once a day.
     */
    suspend fun energy(
        d: LocalDate, profile: UserProfile, maxHr: Float, vo2max: Float?,
        zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis() / 1000,
    ): EnergyExpenditure.Day? {
        val from = DayWindow.of(d, zone).from
        val restingHr = Recovery.daytimeRestingHr(
            dao.restingHrIn(from - Recovery.RHR_DAYS * 86_400L, from, zone).associate { Days.start(it.day, zone) to it.bpm.toFloat() },
            emptyMap(), from,
        ) ?: DEFAULT_RESTING_HR
        return engine.energy(d, profile, restingHr, maxHr, vo2max, zone, now)
    }

    companion object {
        /** Days back from today rewritten after every sync: the strap's history reaches about a week. */
        const val RECENT_DAYS = 7
        private const val DEFAULT_RESTING_HR = 60f

        /** [e] as the row for [day]. */
        fun row(day: LocalDate, e: EnergyExpenditure.Day, now: Long) = DailyEnergyRow(
            day = day,
            updatedAt = now,
            totalKcal = e.totalKcal,
            activeKcal = e.activeKcal,
            bmrKcal = e.bmrKcal,
            minutesWithData = e.minutesWithData,
            partial = e.partial,
            workoutKcal = e.workoutKcal,
            walkingKcal = e.walkingKcal,
            hourly = e.hourly.total.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) },
        )

        /**
         * What in the profile moves a day's energy: weight, height, age, sex and a tested max HR. When it differs from
         * the one the rows were written with, every row is written again. Built from a string, whose hash is the same
         * in every process, as it is stored and compared after the app restarts. An enum's own hash is not.
         */
        fun profileKey(p: UserProfile): Long = "${p.weightKg}|${p.heightCm}|${p.age()}|${p.sex?.name}|${p.maxHeartRate}".hashCode().toLong()
    }
}
