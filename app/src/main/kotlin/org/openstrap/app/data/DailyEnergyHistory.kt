package org.openstrap.app.data

import kotlinx.coroutines.flow.first
import org.openstrap.app.data.db.DailyEnergyRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.presentation.Weeks
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps [DailyEnergyRow] for every day with data, so week, month and year views read a row rather than run the model.
 * The day shown in the app writes its own row each time [RecoveryEngine.compute] works it out ([HealthRepository]);
 * this fills the rest after a sync: days without a row (the first run backfills them all), the last [RECENT_DAYS] (a
 * sync can still bring their minutes or a workout), and every day when the profile has changed. Phone workouts from
 * before version 20 get their kcal first, so their days count it, and after a profile change every phone workout's
 * kcal is worked out again first, so a day never fits its minutes to a total from the old profile.
 */
class DailyEnergyHistory(private val dao: HealthDao) {

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
            .filter { d -> d <= today && (profileChanged || d > today.minusDays(RECENT_DAYS.toLong()) || DayWindow.of(d, zone).from !in have) }
        if (days.isEmpty()) return 0

        // Inputs the whole model would take a day to give for each past day: resting HR as the workouts take it, max HR
        // as of today, VO2max once a week. They only move the HR-assisted minutes; opening a day rewrites its row exactly.
        val engine = RecoveryEngine(dao)
        val maxHr = engine.maxHeartRate(profile, today, zone)
        val vo2 = HashMap<LocalDate, Float?>()
        val firstDay = Weeks.firstDay()
        var written = 0
        for (d in days) {
            val v = vo2.getOrPut(Weeks.start(d, firstDay)) { runCatching { engine.vo2max(profile, d, zone, firstDay) }.getOrNull() }
            if (write(engine, d, profile, maxHr, v, zone, now)) written++
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
        val engine = RecoveryEngine(dao)
        val first = Instant.ofEpochSecond(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochSecond(maxOf(from, to)).atZone(zone).toLocalDate()
        val maxHr = engine.maxHeartRate(profile, Instant.ofEpochSecond(now).atZone(zone).toLocalDate(), zone)
        var d = first
        while (d <= last) {
            write(engine, d, profile, maxHr, runCatching { engine.vo2max(profile, d, zone) }.getOrNull(), zone, now)
            d = d.plusDays(1)
        }
    }

    /** Works out [d] on the resting HR of the days before it and writes its row; false when the model can't. */
    private suspend fun write(engine: RecoveryEngine, d: LocalDate, profile: UserProfile, maxHr: Float, vo2max: Float?, zone: ZoneId, now: Long): Boolean {
        val from = DayWindow.of(d, zone).from
        val restingHr = Recovery.daytimeRestingHr(
            dao.restingHrOnce(from - Recovery.RHR_DAYS * 86_400L, from).associate { it.day to it.bpm.toFloat() }, emptyMap(), from,
        ) ?: DEFAULT_RESTING_HR
        val e = runCatching { engine.energy(d, profile, restingHr, maxHr, vo2max, zone, now) }.getOrNull() ?: return false
        dao.insertDailyEnergy(listOf(row(from, e, now)))
        return true
    }

    companion object {
        /** Days back from today rewritten after every sync: the strap's history reaches about a week. */
        const val RECENT_DAYS = 7
        private const val DEFAULT_RESTING_HR = 60f

        /** [e] as the row for the day starting at [day]. */
        fun row(day: Long, e: EnergyExpenditure.Day, now: Long) = DailyEnergyRow(
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
