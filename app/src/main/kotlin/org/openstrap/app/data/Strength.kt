package org.openstrap.app.data

/**
 * Strength training as sets and rest rather than distance, worked out in pure Kotlin like [Recovery]. Rest ends at
 * the chosen time or earlier once heart rate drops below a chosen level, whichever comes first (Hevy's rest timer is
 * time only). Arm-worn optical heart rate is least reliable while lifting, so the heart-rate rule is a convenience:
 * the time limit always applies.
 */
object Strength {
    /** Sports counted in sets rather than distance. */
    val SPORTS = setOf(24, 50, 52, 222, 226)

    /** How rest works for a workout, chosen on the ready screen; stored as [code] ("r:90:110"). */
    data class Rest(val seconds: Long, val belowBpm: Int?) {
        val code: String get() = "r:$seconds:${belowBpm ?: 0}"

        companion object {
            val DEFAULT = Rest(90, null)
            fun parse(code: String?): Rest? {
                val parts = code?.split(':')?.takeIf { it.size == 3 && it[0] == "r" } ?: return null
                val s = parts[1].toLongOrNull() ?: return null
                return Rest(s, parts[2].toIntOrNull()?.takeIf { it > 0 })
            }
            val TIMES = listOf(60L, 90L, 120L, 180L)
            val LEVELS = listOf(null, 100, 110, 120)
            /** Ready's rest stepper: 15 s at a time, from 30 s to 5 min. */
            const val STEP_S = 15L
            val RANGE_S = 30L..300L
        }
    }

    /** Ready's sets stepper; below its first is open, no plan. */
    val SETS = 1..10

    /** One step of Ready's sets stepper from [sets] by [by]: below 1 is open (null), and open steps up to 1. */
    fun stepSets(sets: Int?, by: Int): Int? = when {
        sets == null -> if (by > 0) SETS.first else null
        sets + by < SETS.first -> null
        else -> (sets + by).coerceAtMost(SETS.last)
    }

    /** The exercise list's built in lifts (workouts spec 11: a short list plus typed names), after your own. */
    val COMMON_LIFTS = listOf(
        "Bench press", "Squat", "Deadlift", "Overhead press", "Barbell row", "Pull up", "Lat pulldown", "Romanian deadlift",
        "Hip thrust", "Leg press", "Lunge", "Dumbbell curl", "Triceps extension", "Push up", "Dips", "Plank",
    )

    /** The exercise list: yours, last used first ([used]), then the built in lifts you haven't used. */
    fun exerciseList(used: List<String>): List<String> = used + COMMON_LIFTS.filter { c -> used.none { it.equals(c, ignoreCase = true) } }

    /** Without anything to fill the log card from: 10 reps and 20 kg. */
    const val DEFAULT_REPS = 10
    const val DEFAULT_KG = 20f

    /** The log card's reps and kg steppers. */
    val REPS = 1..100
    const val KG_STEP = 2.5f
    const val KG_MAX = 500f

    fun stepReps(reps: Int, by: Int): Int = (reps + by).coerceIn(REPS)
    fun stepKg(kg: Float, by: Int): Float = (kg + by * KG_STEP).coerceIn(0f, KG_MAX)

    /**
     * What the log card opens on for [exercise] (workouts spec 2.4): the last set of it logged in this workout ([sets]),
     * else what was last logged for it ([lastReps], [lastKg] from the exercise list), else [DEFAULT_REPS] and
     * [DEFAULT_KG].
     */
    fun prefill(exercise: String?, sets: List<org.openstrap.app.data.db.AppWorkoutSet>, lastReps: Int?, lastKg: Float?): Pair<Int, Float> {
        val here = sets.lastOrNull { it.reps != null && it.exercise.equals(exercise, ignoreCase = true) }
        return (here?.reps ?: lastReps ?: DEFAULT_REPS) to (here?.weightKg ?: lastKg ?: DEFAULT_KG)
    }

    /** Kilograms lifted: reps times weight over the sets logged with both; null when none was. */
    fun kgLifted(sets: List<org.openstrap.app.data.db.AppWorkoutSet>): Float? {
        val logged = sets.filter { it.reps != null && it.weightKg != null }
        return if (logged.isEmpty()) null else logged.sumOf { it.reps!! * it.weightKg!!.toDouble() }.toFloat()
    }

    /** The highest heart rate of [readings] (time, bpm) from [from] to [to]; null without any. */
    fun peak(readings: List<Pair<Long, Int>>, from: Long, to: Long): Int? = readings.filter { it.first in from..to }.maxOfOrNull { it.second }

    /** "60", or "62.5" kg. */
    fun kgText(kg: Float): String = if (kg % 1f == 0f) kg.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", kg)

    /** One step of Ready's rest stepper. */
    fun stepRest(seconds: Long, by: Int): Long = (seconds + by * Rest.STEP_S).coerceIn(Rest.RANGE_S)

    /**
     * Whether a rest that began at [restStart] (and may have been extended to [restUntil]) is over at [now]: its time
     * is up, or heart rate has stayed below the rule's level for [HR_HOLD_S] seconds after [MIN_REST_S] of rest.
     * [hr] is (time, bpm), ascending.
     */
    fun restOver(rest: Rest, restStart: Long, restUntil: Long, hr: List<Pair<Long, Int>>, now: Long): Boolean {
        if (now >= restUntil) return true
        val below = rest.belowBpm ?: return false
        if (now - restStart < MIN_REST_S) return false
        val recent = hr.filter { it.first in (now - HR_HOLD_S)..now }
        return recent.size >= HR_HOLD_S / 2 && recent.all { it.second < below }
    }

    /** Heart rate must hold below the level this long, so one low reading doesn't end rest. */
    const val HR_HOLD_S = 5L
    /** Rest never ends on heart rate this early: the drop after a set takes a moment to show. */
    const val MIN_REST_S = 20L
    /** "+30 s". */
    const val EXTEND_S = 30L
}
