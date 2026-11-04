// The numbers are the model's published coefficients and curve shapes, each explained in the KDoc beside it and in
// docs/algorithms/recovery.md.
@file:Suppress("MagicNumber")

package org.openstrap.app.data

import org.openstrap.app.data.Recovery.ChargeDay
import org.openstrap.app.data.Recovery.ChargeParams
import org.openstrap.app.data.Recovery.ChargePoint
import org.openstrap.app.data.Recovery.Minute
import org.openstrap.app.data.Recovery.MinuteRanges
import org.openstrap.app.data.Recovery.State
import org.openstrap.app.data.Recovery.StressThreshold
import org.openstrap.app.data.Recovery.WorkoutPhase
import org.openstrap.app.data.db.SleepStage

// Charge: the minute-by-minute simulation, the minutes it runs on, and the personal stress threshold and the
// stress exclusion around workouts. The types and constants are in Recovery.kt.

/**
 * A personal stress threshold. [calm] are the strap's stress readings taken awake, still and at resting heart
 * rate over the last [STRESS_BASELINE_DAYS] days, as (epoch seconds, value); [dayOf] maps a time to its day.
 * The calm level is their median, and stress counts from [STRESS_MARGIN] above it, kept within
 * [STRESS_FROM_MIN]..[STRESS_FROM_MAX]. A calm level of 50 gives 60, where Zepp's "medium" band starts.
 * Zepp computes its own threshold on the phone and stores it on the strap; this is our equivalent.
 */
fun Recovery.stressThreshold(calm: List<Pair<Long, Int>>, dayOf: (Long) -> Long): StressThreshold {
    val days = calm.map { dayOf(it.first) }.distinct().size
    if (days < MIN_STRESS_DAYS || calm.size < MIN_STRESS_READINGS) return StressThreshold(STRESS_FROM, null, days)
    // Never null here: there are at least MIN_STRESS_READINGS.
    val median = Stats.median(calm.map { it.second }) ?: 0f
    val value = Math.round(median + STRESS_MARGIN).coerceIn(STRESS_FROM_MIN, STRESS_FROM_MAX)
    return StressThreshold(value, Math.round(median), days)
}

/**
 * The strap's stress readings that make up the calm level for [stressThreshold]: awake ([asleep] false), still
 * (fewer than [CALM_STEPS] steps that minute), heart rate below [ACTIVITY_HRR] of reserve, and outside the stress
 * exclusion around workouts ([excluded], from [stressExcluded]). [hr] and [steps] are keyed by minute start.
 */
fun Recovery.calmStress(
    readings: List<Pair<Long, Int>>,
    hr: Map<Long, Int>,
    steps: Map<Long, Int>,
    restingHr: Float,
    maxHr: Float,
    excluded: List<LongRange>,
    asleep: (Long) -> Boolean,
): List<Pair<Long, Int>> = readings.filter { (t, _) ->
    val minute = t / 60 * 60
    val bpm = hr[minute]
    !asleep(t) && (steps[minute] ?: 0) < CALM_STEPS && bpm != null && hrr(bpm, restingHr, maxHr) < ACTIVITY_HRR &&
        !inWorkout(minute, excluded)
}

/**
 * The stress exclusion: the strap's stress score comes from heart-rate variability, which exercise suppresses
 * whatever the mind is doing, so a reading during a workout or while the heart is still settling after one says
 * nothing about stress. The window is each of [workouts]' active parts (phone and strap, as
 * [workoutRanges] gives them) plus [STRESS_AFTER_WORKOUT_SECONDS] after each part ends. A pause therefore stays
 * excluded for its first 20 minutes too, being the same settling-down. After a hard workout [tails] (from
 * [stressTails]) carry it on for an hour or more. Readings in it are kept and exported, but
 * Charge, the calm level and the day's stress figures and charts leave them out (see [stressCounts]).
 */
fun Recovery.stressExcluded(workouts: List<LongRange>, tails: List<LongRange> = emptyList()): List<LongRange> =
    workouts.flatMap { listOf(it, it.last + 1..it.last + STRESS_AFTER_WORKOUT_SECONDS) } + tails

/** Whether a stress reading at [t] counts as daily stress: not inside [excluded] (from [stressExcluded]). */
fun Recovery.stressCounts(t: Long, excluded: List<LongRange>): Boolean = !inWorkout(t / 60 * 60, excluded)

private val STAGE_WEIGHT = mapOf(
    SleepStage.DEEP to 1.0f,
    SleepStage.LIGHT to 0.8f,
    SleepStage.REM to 0.65f,
    SleepStage.AWAKE to 0.1f,
)

/**
 * Runs the charge model over [minutes] (one per minute, ascending; gaps are filled as no-data),
 * starting from [start], and reports the part inside [window].
 *
 * Not-worn time ([notWorn]) follows Zepp (native 03 §1, §4.7): Charge drifts down at [NO_WEAR_DRIFT_HIGH] /
 * [NO_WEAR_DRIFT_LOW] until [NO_WEAR_LIMIT_MINUTES] not-worn minutes have built up since the last wake, then it is
 * invalid (no points). A not-worn stretch over the usual sleep time is invalid from its start, so a night off the
 * wrist is a gap, not a sleepless night, and Charge restarts when the strap is back on from the median of the last
 * [WAKE_HISTORY] wake values ([DEFAULT_WAKE] without any), flagged [ChargeDay.strapOffOvernight]. Any other
 * invalid stretch stays a gap until the next recorded sleep, which starts from [RESTART_BEFORE_SLEEP].
 */
// One pass over the minutes, whose state (charge, debt, runs, the not-worn count) every branch reads and writes, so
// splitting it would hand that state around instead of reading in order.
@Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "ComplexCondition")
fun Recovery.charge(
    minutes: List<Minute>,
    params: ChargeParams,
    window: LongRange,
    start: Float = 50f,
    sampleSeconds: Long = 300,
    mainSleepEnd: Long? = null,
    /** Report nothing until a sleep has been seen (see [ChargeDay.anchored]); tests switch this off. */
    requireAnchor: Boolean = true,
): ChargeDay {
    var c = start
    var epoc = 0f
    var lastHr: Int? = null
    val points = mutableListOf<ChargePoint>()
    var recharged = 0f
    var exercise = 0f
    var stress = 0f
    var living = 0f
    var atWake: Float? = null
    var latest: Float? = null
    var hrvFactor = hrvFactor(params.hrvScore)
    val hrvScores = params.sleepHrvScore.sortedBy { it.first }
    var hi = 0
    val scoredNightRest = params.nightRestingHr ?: params.restingHr
    var nightRest = scoredNightRest
    val references = params.sleepRestingHr.sortedBy { it.first }
    var ri = 0
    var anchored = false
    var stressRun = 0
    val signals = params.bodySignals.sortedBy { it.first }
    var si = 0
    var signal = BodySignals.State.Normal
    val off = notWorn(minutes)
    val sustained = sustainedEffort(minutes, params)
    val (stretchOf, overnight) = notWornStretches(minutes, off, params.usualSleep)
    val wakeTimes = params.mainSleepEnds.sorted()
    var wi = 0
    val wakes = ArrayList<Float>()
    var wakesSeen = 0
    var notWornCount = 0
    var invalid = false
    var invalidOvernight = false
    var offOvernight = false
    var estimatedWake: Float? = null

    for ((i, m) in minutes.withIndex()) {
        // A wake: record it (all but the simulation's first, which still carries the starting guess) and start a new
        // wake-to-wake count of not-worn minutes.
        while (wi < wakeTimes.size && wakeTimes[wi] <= m.t) {
            if (!invalid) {
                if (wakesSeen > 0) wakes += c
                wakesSeen++
            }
            notWornCount = 0
            wi++
        }
        if (off[i]) {
            notWornCount++
            if (!invalid && (overnight[stretchOf[i]] || notWornCount > NO_WEAR_LIMIT_MINUTES)) {
                invalid = true
                invalidOvernight = overnight[stretchOf[i]]
            }
            // Stress either side of a not-worn stretch doesn't add up to the gate.
            stressRun = 0
            if (!invalid) {
                // Zepp's no-wear drift; accounted as living, like the rest it stands in for.
                val before = c
                c = (c + if (c >= 50f) NO_WEAR_DRIFT_HIGH else NO_WEAR_DRIFT_LOW).coerceIn(0f, 100f)
                if (m.t in window) {
                    living += before - c
                    if (m.t % sampleSeconds == 0L || points.isEmpty()) points += ChargePoint(m.t, c, State.NoData)
                }
            }
            continue
        }
        if (invalid) {
            when {
                m.sleepStage != null -> {
                    invalid = false
                    c = RESTART_BEFORE_SLEEP
                }

                // Back on after a night off: restart from the usual wake. Before any sleep (the first days of
                // wearing) there is nothing to restart from, so Charge still waits for the first night.
                m.hr != null && invalidOvernight && anchored -> {
                    invalid = false
                    c = wakes.takeLast(WAKE_HISTORY).median() ?: DEFAULT_WAKE
                    anchored = true
                    if (m.t in window) {
                        offOvernight = true
                        estimatedWake = c
                    }
                }

                // Still invalid: a short dropout inside it, or worn again after a long daytime stretch.
                else -> {
                    stressRun = 0
                    continue
                }
            }
            notWornCount = 0
            epoc = 0f
        }
        // Consecutive awake minutes at or above the threshold: stress drains only from the third. A minute in the
        // stress exclusion around a workout breaks the run, as its reading doesn't count.
        stressRun = if (m.sleepStage == null && m.workout == null && m.stress != null && m.stress >= params.stressFrom) stressRun + 1 else 0
        val hr = m.hr
        val x = hr?.let { hrr(it, params.restingHr, params.maxHr) }
        while (si < signals.size && signals[si].first <= m.t) signal = signals[si++].second
        while (ri < references.size && references[ri].first <= m.t) nightRest = references[ri++].second ?: scoredNightRest
        while (hi < hrvScores.size && hrvScores[hi].first <= m.t) hrvFactor = hrvFactor(hrvScores[hi++].second)
        // Heart rate counts as effort outside workouts only while moving, or in a sustained run (see [ACTIVITY_HRR]).
        val effort = m.workout != null || moving(m.steps) || sustained[i]
        val state: State
        var delta: Float
        when {
            m.sleepStage != null -> {
                anchored = true
                state = State.Sleep
                val relax = hr?.let { (1.2f - 0.04f * (it - nightRest)).coerceIn(0.5f, 1.2f) } ?: 1f
                delta = SLEEP_K * (STAGE_WEIGHT[m.sleepStage] ?: 0.8f) * relax * hrvFactor * (100 - c)
            }

            // A short heart-rate dropout (under [NO_WEAR_GAP_MINUTES], or not yet synced): the value holds.
            hr == null || x == null -> {
                state = State.NoData
                delta = 0f
            }

            x >= EXERCISE_HRR && effort -> {
                state = State.Exercise
                val t = trimpMinute(x, params.female)
                epoc += t
                delta = -BASE_DRAIN - EXERCISE_DRAIN_PER_TRIMP * t
            }

            // After exercise the heart rate stays up while the body recovers; that isn't stress.
            epoc > EPOC_THRESHOLD && (lastHr == null || hr <= lastHr + 2) -> {
                state = State.ExerciseRecovery
                delta = -BASE_DRAIN
            }

            (x >= ACTIVITY_HRR && effort) || m.steps >= ACTIVE_STEPS -> {
                state = State.Activity
                delta = -BASE_DRAIN - EXERCISE_DRAIN_PER_TRIMP * trimpMinute(x, params.female)
            }

            // Inside the stress exclusion ([stressExcluded]) the strap's stress score is ignored, so a minute that
            // reaches here is never Stress or Relax. See [WORKOUT_REST_HRR] for the state it takes instead.
            m.workout == WorkoutPhase.Active && x >= WORKOUT_REST_HRR -> {
                state = State.Activity
                delta = -BASE_DRAIN - EXERCISE_DRAIN_PER_TRIMP * trimpMinute(x, params.female)
            }

            m.workout == WorkoutPhase.After && x >= WORKOUT_REST_HRR -> {
                state = State.ExerciseRecovery
                delta = -BASE_DRAIN
            }

            m.workout != null -> {
                state = State.Rest
                delta = -BASE_DRAIN
            }

            m.stress != null && m.stress >= params.stressFrom && stressRun >= STRESS_GATE_MINUTES -> {
                state = State.Stress
                delta = -BASE_DRAIN - STRESS_DRAIN * (m.stress - params.stressFrom)
            }

            m.stress != null && m.stress < RELAX_BELOW && m.steps < CALM_STEPS && x < ACTIVITY_HRR -> {
                state = State.Relax
                // Calm, still minutes hold, with a slight recovery and no drain ([RELAX_K]).
                delta = RELAX_K * (100 - c)
            }

            else -> {
                state = State.Rest
                delta = -REST_DRAIN * c
            }
        }
        // The share of a drain that is the base drain, for the split below; scaling keeps it.
        val baseShare = if (delta < 0) BASE_DRAIN / -delta else 0f
        if (signal == BodySignals.State.Alert) delta *= if (delta > 0) ALERT_RECHARGE else ALERT_DRAIN
        // EPOC-like debt decays with a half-life of about 20 minutes once exercise stops.
        if (state != State.Exercise) epoc *= EPOC_DECAY
        if (hr != null) lastHr = hr

        val before = c
        c = (c + delta).coerceIn(0f, 100f)
        val applied = c - before
        if (m.t in window) {
            if (applied > 0) {
                recharged += applied
            } else {
                when (state) {
                    State.Exercise, State.Activity -> {
                        exercise += -applied * (1 - baseShare)
                        living += -applied * baseShare
                    }

                    State.ExerciseRecovery, State.Rest, State.NoData, State.Relax, State.Sleep -> living += -applied

                    State.Stress -> {
                        stress += -applied * (1 - baseShare)
                        living += -applied * baseShare
                    }
                }
            }
            if (m.t % sampleSeconds == 0L || points.isEmpty()) points += ChargePoint(m.t, c, state)
            if (state != State.NoData) latest = c
        }
        if (mainSleepEnd != null && atWake == null && m.t >= mainSleepEnd) atWake = c
    }
    val values = points.map { it.value }
    if (!anchored && requireAnchor) return ChargeDay(emptyList(), null, null, 0f, 0f, 0f, 0f, null, null, anchored = false)
    return ChargeDay(
        points, latest, atWake ?: estimatedWake, recharged, exercise, stress, living, values.maxOrNull(), values.minOrNull(), anchored,
        strapOffOvernight = offOvernight,
    )
}

/**
 * The not-worn stretch each of [minutes] belongs to (an index, -1 when worn), and for each stretch whether it covers
 * at least [USUAL_SLEEP_COVER] of one of [usualSleep]. A stretch still open at the end of the data never does.
 */
private fun Recovery.notWornStretches(
    minutes: List<Minute>,
    off: BooleanArray,
    usualSleep: List<LongRange>,
): Pair<IntArray, List<Boolean>> {
    val stretchOf = IntArray(minutes.size) { -1 }
    val overnight = ArrayList<Boolean>()
    var i = 0
    while (i < minutes.size) {
        if (!off[i]) {
            i++
            continue
        }
        var j = i
        while (j < minutes.size && off[j]) j++
        val from = minutes[i].t
        val to = minutes[j - 1].t + 60
        val closed = j < minutes.size
        overnight += closed && usualSleep.any { u ->
            val len = u.last - u.first
            len > 0 && (minOf(to, u.last) - maxOf(from, u.first)) >= USUAL_SLEEP_COVER * len
        }
        for (k in i until j) stretchOf[k] = overnight.size - 1
        i = j
    }
    return stretchOf to overnight
}

/**
 * Which of [minutes] are not worn: no heart rate, not asleep, and then either recorded off-body by the strap's live
 * wear state ([Minute.worn] false), marked [Minute.offWrist], or inside a gap of at least [NO_WEAR_GAP_MINUTES]
 * that heart rate or sleep later closes (an open gap at the end is unsynced data). A recorded on-body state doesn't
 * override the other two: it may be one read at connect that the strap never updated, so only off-body is trusted.
 */
fun Recovery.notWorn(minutes: List<Minute>): BooleanArray {
    val out = BooleanArray(minutes.size)
    var i = 0
    while (i < minutes.size) {
        if (minutes[i].hr != null || minutes[i].sleepStage != null) {
            i++
            continue
        }
        var j = i
        while (j < minutes.size && minutes[j].hr == null && minutes[j].sleepStage == null) j++
        val gap = j < minutes.size && j - i >= NO_WEAR_GAP_MINUTES
        for (k in i until j) out[k] = minutes[k].worn == false || gap || minutes[k].offWrist
        i = j
    }
    return out
}

/**
 * Minutes in a run of at least [SUSTAINED_EFFORT_MINUTES] consecutive awake minutes outside the stress exclusion at
 * or above [EXERCISE_HRR]: effort even without steps.
 */
fun Recovery.sustainedEffort(minutes: List<Minute>, params: ChargeParams): BooleanArray =
    sustainedRuns(minutes.map { it.t }) { k ->
        val m = minutes[k]
        m.sleepStage == null && m.workout == null && m.hr != null && hrr(m.hr, params.restingHr, params.maxHr) >= EXERCISE_HRR
    }

private fun List<Float>.median(): Float? = Stats.median(this)

/** How well a sleep recharges for its night's HRV contributor [score]: 1 at a usual night (80) or without one. */
private fun hrvFactor(score: Int?): Float = score?.let { (0.75f + 0.25f * it / 80f).coerceIn(0.6f, 1.1f) } ?: 1f

private const val EPOC_THRESHOLD = 5f

private const val ACTIVE_STEPS = 60

/**
 * Builds one [Minute] per minute over [from, to): heart rate at that minute, the latest stress
 * value within the last [stressHoldSeconds], the sleep stage covering the minute, and where it sits against
 * [workouts].
 */
@Suppress("LongParameterList") // Callers name the optional inputs, and the signature is the one they already use.
fun Recovery.minutes(
    from: Long,
    to: Long,
    hr: List<Pair<Long, Int>>,
    steps: Map<Long, Int>,
    stress: List<Pair<Long, Int>>,
    stages: List<Triple<Long, Long, Int>>,
    stressHoldSeconds: Long = 10 * 60,
    /** Stored workouts' active parts ([workoutRanges]); each minute gets its [WorkoutPhase] from these. */
    workouts: List<LongRange> = emptyList(),
    /** Minute starts the strap marked off the wrist ([offWrist]). */
    offWrist: Set<Long> = emptySet(),
    /** Recorded wear changes (time, worn or null for unknown), each holding until the next ([Minute.worn]). */
    wear: List<Pair<Long, Boolean?>> = emptyList(),
    /**
     * Hard workouts' longer stress exclusion ([stressTails]). Only the stress readings in it are dropped: the minutes
     * keep no [WorkoutPhase], so heart rate there is effort only while moving, as anywhere outside a workout.
     */
    stressTails: List<LongRange> = emptyList(),
): List<Minute> {
    val excluded = stressExcluded(workouts, stressTails)
    val active = MinuteRanges(workouts)
    val after = MinuteRanges(stressExcluded(workouts))
    val hrByMinute = hr.associate { (it.first / 60 * 60) to it.second }
    // A reading taken in the exclusion must not be held into the minutes after it either.
    val stressSorted = stress.filter { stressCounts(it.first, excluded) }.sortedBy { it.first }
    val stageSorted = stages.sortedBy { it.first }
    var si = 0
    var lastStress: Pair<Long, Int>? = null
    var gi = 0
    val wearSorted = wear.sortedBy { it.first }
    var wi = 0
    var worn: Boolean? = null
    val out = ArrayList<Minute>(((to - from) / 60).toInt().coerceAtLeast(0))
    var t = from / 60 * 60
    while (t < to) {
        while (si < stressSorted.size && stressSorted[si].first <= t) {
            lastStress = stressSorted[si]
            si++
        }
        while (gi < stageSorted.size && stageSorted[gi].second <= t) gi++
        val stage = stageSorted.getOrNull(gi)?.takeIf { t >= it.first && t < it.second }?.third
        val s = lastStress?.takeIf { t - it.first <= stressHoldSeconds }?.second
        val phase = when {
            active(t) -> WorkoutPhase.Active
            after(t) -> WorkoutPhase.After
            else -> null
        }
        // The state at the minute's middle, so a change in its first half counts for it.
        while (wi < wearSorted.size && wearSorted[wi].first <= t + 30) {
            worn = wearSorted[wi].second
            wi++
        }
        out += Minute(t, hrByMinute[t], steps[t] ?: 0, s, stage, phase, t in offWrist, worn)
        t += 60
    }
    return out
}
