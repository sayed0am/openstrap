package org.openstrap.app.device

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openstrap.app.data.WorkoutEffectEngine
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.settings.UserProfile

/**
 * After a history sync, fills the minutes each recent phone workout missed (a disconnect, a killed app) from what the
 * strap recorded meanwhile ([WorkoutRecorder.missingMinutes], [WorkoutRecorder.fillFromHistory]), then refreshes the
 * workout's averages and effect ([org.openstrap.app.data.WorkoutEffectEngine]). Running it again changes nothing once a
 * minute is filled. [profile] gives the max HR the effect is worked out with.
 *
 * Each workout is filled under [lock], the one [WorkoutSession] and its [SavedWorkoutEditor] change workouts under,
 * and read again inside it, so a trim, an end or a discard made since the list was read stands.
 */
class WorkoutGapFill(
    private val dao: HealthDao,
    private val lock: Mutex = WorkoutSession.LOCK,
    private val profile: suspend () -> UserProfile = { UserProfile() },
) {

    /** A line for the log when anything was filled, else null. */
    suspend fun run(now: Long = System.currentTimeMillis() / 1000): String? {
        var filled = 0
        for (listed in dao.appWorkoutsOnce(now - LOOKBACK_S, now)) {
            // Gone since the list was read (discarded): nothing to fill.
            filled += lock.withLock { dao.appWorkoutOnce(listed.start)?.let { fill(it) } ?: 0 }
        }
        return if (filled > 0) "workout gap fill: $filled minutes from the strap's history" else null
    }

    /**
     * Fills [w]'s missing minutes from history and returns how many. Also builds a workout saved after the fact. The
     * caller holds [lock] and passes the row as stored now.
     */
    suspend fun fill(w: org.openstrap.app.data.db.AppWorkout): Int {
        val hr = dao.appWorkoutHeartRateOnce(w.start)
        // The whole recorded span, so a trim or an early end can be undone with its minutes filled. One still recording
        // only up to its last reading: the hours after the app was killed may be the night, and a workout left that
        // long is filled and ended by [LeftRecording] instead.
        val end = w.end?.let { w.recordedEnd ?: it } ?: hr.maxOfOrNull { it.timestamp } ?: return 0
        val missing = WorkoutRecorder.missingMinutes(w.start, end, w.pauseRanges, hr.map { it.timestamp })
        if (missing.isEmpty()) return 0
        val rows = fromHistory(w.start, missing)
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

    /**
     * Rows for the [missing] minutes of the workout [workoutStart] from the strap's history
     * ([WorkoutRecorder.fillFromHistory]), for the minutes it has a heart rate for. Nothing is written.
     */
    suspend fun fromHistory(workoutStart: Long, missing: List<Long>): List<org.openstrap.app.data.db.AppWorkoutHeartRate> {
        if (missing.isEmpty()) return emptyList()
        val from = missing.first()
        val to = missing.last() + 59
        val perSecond = dao.heartRateOnce(from, to)
            .filter { it.source == HeartRateSample.SOURCE_HISTORY && it.timestamp % 60 == 0L }
            .associate { it.timestamp to it.bpm }
        val minutes = dao.activityOnce(from, to).mapNotNull { m -> m.heartRate?.let { m.timestamp to it } }.toMap()
        return WorkoutRecorder.fillFromHistory(workoutStart, missing, perSecond, minutes)
    }

    private companion object {
        /** The strap's history reaches back about this far; older workouts can't gain anything. */
        const val LOOKBACK_S = 7 * 86_400L
    }
}
