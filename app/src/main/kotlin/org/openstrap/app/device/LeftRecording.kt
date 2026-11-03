package org.openstrap.app.device

import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.HealthDao

/**
 * A workout the app was killed during, found with no reading for [WorkoutSession.LEFT_RECORDING_S] when the app came
 * back (decisions 2026-11-03). The strap most likely stayed on and kept measuring, so the workout records on while a
 * sync brings the strap's history for the time the phone missed, and then ends where activity stopped: the strap's own
 * record of it when it has one, else where the live end prompt would have asked. Never at the moment the app came back.
 * With nothing from the strap, it ends at the phone's last reading, as before. Pure apart from [load].
 */
internal object LeftRecording {

    /** The workout [start], the phone's last reading before the app was killed [leftAt], and when it came back. */
    data class Left(val start: Long, val leftAt: Long, val reopenedAt: Long)

    sealed interface Verdict {
        /** Ends at [at]. [why] is for the log. */
        data class End(val at: Long, val why: String) : Verdict

        /** Still under way when the app came back: it records on, and the live end prompt takes over. */
        data object CarryOn : Verdict

        /** The strap's history doesn't reach the time the app came back yet: the next sync may bring it. */
        data object Wait : Verdict
    }

    /**
     * What was seen up to [Left.reopenedAt]. [hr] is the workout's readings: the phone's a second each, the strap's
     * history a row a minute ([AppWorkoutHeartRate.fromHistory]), [filled] being the history rows not stored yet.
     * [steps] is the strap's steps each minute, by minute start. [historyTo] is where the strap's synced history ends,
     * null when it has nothing after [Left.leftAt]. [twinEnd] ends the strap's own record of the workout, one that was
     * under way at [Left.leftAt].
     */
    data class Seen(
        val hr: List<AppWorkoutHeartRate>,
        val filled: List<AppWorkoutHeartRate>,
        val steps: Map<Long, Int>,
        val historyTo: Long?,
        val twinEnd: Long?,
        val restingHr: Int,
        val maxHr: Int,
    )

    /** The strap's history counts as reaching the reopen this close to it: it sends the last minutes in batches. */
    const val CAUGHT_UP_S = 15 * 60L

    /**
     * Where [left] ends, from what was [seen]. At the strap's own end, when it kept a record of the workout. At the
     * last reading, when the strap measured nothing for [WorkoutSession.LEFT_RECORDING_S] (taken off). At the last
     * active moment, once the wearer had been calm and still for [WorkoutRecorder.STOPPED_S] after it, by the live end
     * prompt's rule. Active up to the reopen, it carries on. Until the strap's history reaches the reopen it waits,
     * unless [final]: then what came decides, and with nothing from the strap it ends at the phone's last reading.
     */
    // Each return is one rule, in the order they apply.
    @Suppress("ReturnCount")
    fun verdict(left: Left, seen: Seen, final: Boolean): Verdict {
        seen.twinEnd?.takeIf { it > left.leftAt && it <= left.reopenedAt }?.let { return Verdict.End(it, "the strap's own workout ended") }
        if (seen.historyTo == null && final) return Verdict.End(left.leftAt, "nothing from the strap")
        val horizon = (seen.historyTo ?: left.leftAt).coerceIn(left.leftAt, left.reopenedAt)
        val caughtUp = horizon >= left.reopenedAt - CAUGHT_UP_S
        if (!caughtUp && !final) return Verdict.Wait
        val lastSeen = seen.hr.filter { WorkoutRecorder.isValid(it.bpm) }.maxOfOrNull(::lastSecond)?.coerceAtMost(horizon) ?: left.leftAt
        if (horizon - lastSeen >= WorkoutSession.LEFT_RECORDING_S) return Verdict.End(lastSeen, "no heart rate for 2 hours")
        val active = lastActive(seen, horizon) ?: return Verdict.End(left.leftAt, "no activity")
        return when {
            horizon - active >= WorkoutRecorder.STOPPED_S -> Verdict.End(active, "activity stopped")
            caughtUp -> Verdict.CarryOn
            else -> Verdict.End(lastSeen, "the strap's history ends there")
        }
    }

    /**
     * The last moment up to [horizon] of a heart rate above [WorkoutRecorder.CALM_HRR] of the reserve, or of a minute
     * with [WorkoutRecorder.MOVING_STEPS] steps: the live prompt's rule ([WorkoutRecorder.lastActive]) over minutes.
     */
    private fun lastActive(seen: Seen, horizon: Long): Long? {
        val calm = seen.restingHr + WorkoutRecorder.CALM_HRR * (seen.maxHr - seen.restingHr)
        val byHr = seen.hr.filter { WorkoutRecorder.isValid(it.bpm) && it.bpm > calm }.maxOfOrNull(::lastSecond)
        val bySteps = seen.steps.filter { (m, n) -> m < horizon && n >= WorkoutRecorder.MOVING_STEPS }.keys.maxOrNull()?.plus(MINUTE_S - 1)
        return listOfNotNull(byHr, bySteps).maxOrNull()?.coerceAtMost(horizon)
    }

    /** The last second a reading stands for: a history row stands for its whole minute. */
    private fun lastSecond(r: AppWorkoutHeartRate) = if (r.fromHistory) r.timestamp + MINUTE_S - 1 else r.timestamp

    /**
     * What [w], restored as [left], has seen by [now]: its stored readings up to the reopen, the strap's history for
     * the minutes it missed since ([WorkoutGapFill.fromHistory]), the strap's steps each minute, and the strap's own
     * record of it. Under [WorkoutSession.LOCK].
     */
    // The workout, what the app knew, and the figures the live prompt reads.
    @Suppress("LongParameterList")
    suspend fun load(dao: HealthDao, w: AppWorkout, left: Left, restingHr: Int, maxHr: Int, now: Long): Seen {
        val stored = dao.appWorkoutHeartRateOnce(w.start).filter { it.timestamp <= left.reopenedAt }
        val historyTo = dao.latestActivity(now)?.takeIf { it >= left.leftAt }?.plus(MINUTE_S)
        val horizon = (historyTo ?: left.leftAt).coerceIn(left.leftAt, left.reopenedAt)
        val missing = WorkoutRecorder.missingMinutes(left.leftAt, horizon, w.pauseRanges, stored.map { it.timestamp })
        val filled = WorkoutGapFill(dao).fromHistory(w.start, missing)
        val steps = dao.activityOnce(w.start, horizon).filter { it.steps in 0..MAX_STEPS }
            .associate { it.timestamp / MINUTE_S * MINUTE_S to it.steps }
        val twinEnd = dao.workoutsOnce(left.leftAt, left.reopenedAt)
            .filter { it.start <= left.leftAt && it.end > left.leftAt && Workouts.sameWorkout(it.start..it.end, w.start..left.reopenedAt) }
            .maxOfOrNull { it.end }
        return Seen(stored + filled, filled, steps, historyTo, twinEnd, restingHr, maxHr)
    }

    private const val MINUTE_S = 60L
    /** More steps than this in a minute is the strap's 0xFF filler, not walking. */
    private const val MAX_STEPS = 250
}
