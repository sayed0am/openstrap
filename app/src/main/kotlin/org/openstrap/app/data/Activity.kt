package org.openstrap.app.data


/**
 * Steps, distance and moderate-to-vigorous activity (docs/algorithms/evidence-review.md, "Energy and activity").
 * The cadence thresholds are established (CADENCE-Adults); everything about a chest- or arm-worn strap's step count
 * is not: no step or cadence validation exists for one. Pure Kotlin, like [Recovery].
 */
object Activity {

    /** One minute as activity sees it. [hrr] is heart-rate reserve (null without HR); [asleep] and [inWorkout] flags. */
    data class Minute(val t: Long, val steps: Int, val hrr: Float?, val asleep: Boolean = false, val inWorkout: Boolean = false)

    /** Moderate from 100 steps/min at every age, vigorous from 130 (Tudor-Locke). */
    const val MODERATE_CADENCE = 100
    const val VIGOROUS_CADENCE = 130
    /** A cadence minute counts only inside a run of this many consecutive qualifying minutes (design choice). */
    const val CADENCE_RUN_MINUTES = 2
    /**
     * HR-MVPA: awake, ≥ 5 consecutive minutes at ≥ 40 % of reserve (vigorous ≥ 60 %), and few steps or in a workout.
     * WHO 2020 dropped the 10-minute bout rule, and shorter bouts carry the same benefit (Jakicic 2019). Five minutes
     * still keeps a stairwell or a coffee spike out (evidence-review.md, "Activity").
     */
    const val HR_MODERATE_HRR = 0.40f
    const val HR_VIGOROUS_HRR = 0.60f
    const val HR_RUN_MINUTES = 5
    /** WHO 2020: 150 min of moderate a week, a vigorous minute counting double. */
    const val MVPA_WEEKLY_TARGET = 150

    data class Mvpa(
        val moderateMinutes: Int,
        val vigorousMinutes: Int,
        /** Minutes of the totals that came from heart rate, not cadence ("HR-assisted"). */
        val fromHeartRate: Int,
    ) {
        /** Moderate + 2·vigorous, against [MVPA_WEEKLY_TARGET] a week. */
        val equivalent get() = moderateMinutes + 2 * vigorousMinutes
        operator fun plus(o: Mvpa) = Mvpa(moderateMinutes + o.moderateMinutes, vigorousMinutes + o.vigorousMinutes, fromHeartRate + o.fromHeartRate)
    }

    /**
     * Moderate and vigorous minutes in [minutes] (ascending, one per minute; gaps break runs). Cadence first: a minute
     * at ≥ [MODERATE_CADENCE] steps counts inside a run of ≥ [CADENCE_RUN_MINUTES], vigorous at ≥ [VIGOROUS_CADENCE].
     * Then heart rate, for effort without steps (cycling, rowing): awake runs of ≥ [HR_RUN_MINUTES] minutes at
     * ≥ [HR_MODERATE_HRR] of reserve with < 100 steps or inside a workout. No minute is counted twice.
     */
    fun mvpa(minutes: List<Minute>): Mvpa {
        val n = minutes.size
        val counted = BooleanArray(n)
        var moderate = 0
        var vigorous = 0
        var fromHr = 0
        fun runs(qualifies: (Int) -> Boolean, minLength: Int, each: (Int) -> Unit) {
            var i = 0
            while (i < n) {
                if (!qualifies(i)) { i++; continue }
                var j = i
                while (j + 1 < n && qualifies(j + 1) && minutes[j + 1].t - minutes[j].t == 60L) j++
                if (j - i + 1 >= minLength) for (k in i..j) each(k)
                i = j + 1
            }
        }
        runs({ !minutes[it].asleep && minutes[it].steps >= MODERATE_CADENCE }, CADENCE_RUN_MINUTES) { k ->
            counted[k] = true
            if (minutes[k].steps >= VIGOROUS_CADENCE) vigorous++ else moderate++
        }
        runs({ k ->
            val m = minutes[k]
            !counted[k] && !m.asleep && (m.hrr ?: 0f) >= HR_MODERATE_HRR && (m.steps < MODERATE_CADENCE || m.inWorkout)
        }, HR_RUN_MINUTES) { k ->
            counted[k] = true
            fromHr++
            if (minutes[k].hrr!! >= HR_VIGOROUS_HRR) vigorous++ else moderate++
        }
        return Mvpa(moderate, vigorous, fromHr)
    }

    /**
     * Step length in metres for a minute at [cadence] steps/min and height [heightCm]: 0.414·H below 120 spm, rising
     * linearly to 0.45·H at 130 and above. An estimate; distance is always shown with "≈".
     */
    fun stepLength(cadence: Int, heightCm: Int): Float {
        val h = heightCm / 100f
        val f = when {
            cadence < 120 -> WALK_STEP_FRACTION
            cadence >= 130 -> RUN_STEP_FRACTION
            else -> WALK_STEP_FRACTION + (RUN_STEP_FRACTION - WALK_STEP_FRACTION) * (cadence - 120) / 10f
        }
        return f * h
    }

    const val WALK_STEP_FRACTION = 0.414f
    const val RUN_STEP_FRACTION = 0.45f

    /** Distance in metres for [minutes]; null without a height. */
    fun distanceMeters(minutes: List<Minute>, heightCm: Int?): Float? {
        if (heightCm == null || heightCm <= 0) return null
        return minutes.sumOf { (it.steps * stepLength(it.steps, heightCm)).toDouble() }.toFloat()
    }

    /**
     * A daily step goal framed as "where most of the benefit is reached": mortality plateaued at 6,000-8,000 steps from
     * age 60 and 8,000-10,000 below (Paluch et al. 2022).
     */
    fun defaultStepGoal(age: Int?): Int = if (age != null && age >= 60) 7_000 else 8_000

    data class Day(
        val steps: Int,
        val mvpa: Mvpa,
        /** MVPA-equivalent minutes over the 7 days ending today. */
        val weekEquivalent: Int,
        val distanceMeters: Float?,
        val stepGoal: Int,
        /** Each day's MVPA, worked out the same way, for the [RecoveryEngine.ACTIVITY_DAYS] ending on the day. */
        val byDay: Map<java.time.LocalDate, Mvpa> = emptyMap(),
    )
}
