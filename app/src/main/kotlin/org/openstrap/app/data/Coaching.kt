package org.openstrap.app.data

import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * What a workout aims for, chosen on the ready screen: a zone or a range of zones (0..4, inclusive), a time, or active
 * kcal. Stored on the workout as [code] ("z:1-2", "t:2700", "k:300").
 */
sealed interface WorkoutTarget {
    val code: String

    data class Zones(val low: Int, val high: Int) : WorkoutTarget {
        override val code get() = "z:$low-$high"
        val label: String get() = if (low == high) tr(R.string.coaching_zone, low + 1) else tr(R.string.coaching_zone_2, low + 1, high + 1)
    }

    data class Time(val seconds: Long) : WorkoutTarget {
        override val code get() = "t:$seconds"
        val label: String get() = tr(R.string.missed_duration_min, seconds / 60)
    }

    /** Active kcal ([org.openstrap.app.data.WorkoutRecorder.Kcal.active]), so only offered with a profile to work it out. */
    data class Kcal(val kcal: Int) : WorkoutTarget {
        override val code get() = "k:$kcal"
        val label: String get() = "$kcal kcal"
    }

    companion object {
        fun parse(code: String?): WorkoutTarget? {
            code ?: return null
            val v = code.substringAfter(':')
            return when (code.substringBefore(':')) {
                "z" -> v.split('-').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { (a, b) -> Zones(a, b) }
                "t" -> v.toLongOrNull()?.let(::Time)
                "k" -> v.toIntOrNull()?.takeIf { it > 0 }?.let(::Kcal)
                else -> null
            }
        }

        /** The choices the ready screen offers, after "None". */
        val presets: List<WorkoutTarget> = listOf(
            Zones(1, 1), Zones(1, 2), Zones(2, 2), Zones(3, 3), Time(30 * 60), Time(45 * 60), Time(60 * 60), Kcal(300), Kcal(500), Kcal(800),
        )

        /** [presets] as offered: kcal ones only when kcal can be worked out ([kcal], a profile with age, weight and height). */
        fun offered(kcal: Boolean): List<WorkoutTarget> = if (kcal) presets else presets.filter { it !is Kcal }
    }
}

/** A cue the strap's motor plays; each has its own pattern so it can be told apart without looking. */
enum class Cue(val pattern: List<Int>) {
    /** Above the target zone: three quick pulses, "ease off". */
    AboveTarget(listOf(120, 120, 120, 120, 120)),
    /** Below it: one long pulse, "push". */
    BelowTarget(listOf(700)),
    /** Back in the target: one short pulse. */
    BackInTarget(listOf(200)),
    /** Halfway through a time target: two short pulses. */
    Halfway(listOf(150, 150, 150)),
    /** Time or kcal target reached: long, short, long. */
    GoalReached(listOf(500, 200, 200, 200, 500)),
    /** Strength: rest is over, start the next set. Two medium pulses. */
    RestOver(listOf(400, 150, 400)),
}

/**
 * Turns a workout's live zones and time into cues, debounced so arm-worn optical heart rate's spikes don't buzz:
 * leaving or re-entering the target must last [HOLD_S] seconds, and zone cues are at least [MIN_GAP_S] apart
 * (Zepp's alerts use 15 s and 40 s). Pure and stateful: feed it every reading in order, and the live kcal to [kcal].
 * [reachedAt] is when a kcal target was already reached (a workout picked up again), so it never buzzes twice.
 */
class Coach(private val target: WorkoutTarget?, reachedAt: Long? = null) {
    /** The side of the target last settled on: above 1, in 0, below −1; null before the first. */
    private var settled: Int? = null
    private var pendingSide = 0
    private var pendingSince = 0L
    private var lastZoneCue = Long.MIN_VALUE / 2
    private var halfwayDone = false
    private var goalDone = reachedAt != null

    /** When the kcal target was reached, epoch seconds; null until then. */
    var reachedAt: Long? = reachedAt
        private set

    /** A reading at [t] in live zone [zone] ([WorkoutRecorder.liveZone]), [activeSeconds] into the workout. */
    fun feed(t: Long, zone: Int?, activeSeconds: Long): Cue? = when (target) {
        is WorkoutTarget.Time -> when {
            !goalDone && activeSeconds >= target.seconds -> { goalDone = true; halfwayDone = true; Cue.GoalReached }
            !halfwayDone && activeSeconds * 2 >= target.seconds -> { halfwayDone = true; Cue.Halfway }
            else -> null
        }
        is WorkoutTarget.Zones -> zone?.let { zoneCue(t, it, target) }
        is WorkoutTarget.Kcal, null -> null
    }

    /**
     * The live active kcal at [t] ([WorkoutRecorder.liveKcal], which never goes down): the goal cue the first time it
     * reaches a kcal target, once only. Fed nothing while paused, the figure holds there anyway.
     */
    fun kcal(t: Long, activeKcal: Float): Cue? {
        if (target !is WorkoutTarget.Kcal || goalDone || activeKcal < target.kcal) return null
        goalDone = true
        reachedAt = t
        return Cue.GoalReached
    }

    private fun zoneCue(t: Long, zone: Int, target: WorkoutTarget.Zones): Cue? {
        val side = when { zone > target.high -> 1; zone < target.low -> -1; else -> 0 }
        if (side != pendingSide) { pendingSide = side; pendingSince = t; return null }
        if (t - pendingSince < HOLD_S) return null
        // The first settled side only says where the workout begins: warming up below the target isn't a drift.
        val was = settled ?: run { settled = side; return null }
        if (side == was || t - lastZoneCue < MIN_GAP_S) return null
        settled = side
        lastZoneCue = t
        return when (side) { 0 -> Cue.BackInTarget; 1 -> Cue.AboveTarget; else -> Cue.BelowTarget }
    }

    companion object {
        const val HOLD_S = 15L
        const val MIN_GAP_S = 40L
    }
}
