package org.openstrap.app.device

import org.openstrap.app.data.WorkoutEffectEngine
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * After a history sync, fills the minutes each recent phone workout missed (a disconnect, a killed app) from what the
 * strap recorded meanwhile ([WorkoutRecorder.missingMinutes], [WorkoutRecorder.fillFromHistory]), then refreshes the
 * workout's averages and effect ([org.openstrap.app.data.WorkoutEffectEngine]). Running it again changes nothing once a
 * minute is filled. [profile] gives the max HR the effect is worked out with.
 */
class WorkoutGapFill(private val dao: HealthDao, private val profile: suspend () -> UserProfile = { UserProfile() }) {

    /** A line for the log when anything was filled, else null. */
    suspend fun run(now: Long = System.currentTimeMillis() / 1000): String? {
        var filled = 0
        for (w in dao.appWorkoutsOnce(now - LOOKBACK_S, now)) filled += fill(w, now)
        return if (filled > 0) tr(R.string.workout_gap_fill_workout_gap_fill_minutes, filled) else null
    }

    /** Fills [w]'s missing minutes from history; the number filled. Also builds a workout saved after the fact. */
    suspend fun fill(w: org.openstrap.app.data.db.AppWorkout, now: Long = System.currentTimeMillis() / 1000): Int {
        // The whole recorded span, so a trim or an early end can be undone with its minutes filled.
        val end = w.recordedEnd ?: w.end ?: w.pausedAt ?: now
        val hr = dao.appWorkoutHeartRateOnce(w.start)
        val missing = WorkoutRecorder.missingMinutes(w.start, end, w.pauseRanges, hr.map { it.timestamp })
        if (missing.isEmpty()) return 0
        val from = missing.first()
        val to = missing.last() + 59
        val perSecond = dao.heartRateOnce(from, to)
            .filter { it.source == HeartRateSample.SOURCE_HISTORY && it.timestamp % 60 == 0L }
            .associate { it.timestamp to it.bpm }
        val minutes = dao.activityOnce(from, to).mapNotNull { m -> m.heartRate?.let { m.timestamp to it } }.toMap()
        val rows = WorkoutRecorder.fillFromHistory(w.start, missing, perSecond, minutes)
        if (rows.isEmpty()) return 0
        dao.insertAppWorkoutHeartRateIfMissing(rows)
        // The recording workout gets its figures when it ends.
        if (w.end != null) {
            val all = dao.appWorkoutHeartRateOnce(w.start)
            val stats = WorkoutRecorder.withStats(w, all)
            dao.upsertAppWorkout(runCatching { WorkoutEffectEngine(dao).withEffect(stats, all, profile()) }.getOrDefault(stats))
        }
        return rows.size
    }

    private companion object {
        /** The strap's history reaches back about this far; older workouts can't gain anything. */
        const val LOOKBACK_S = 7 * 86_400L
    }
}
