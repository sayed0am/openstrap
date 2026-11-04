// The numbers are the model's published coefficients and curve shapes, each explained in the KDoc beside it and in
// docs/algorithms/recovery.md.
// One part of the model per file: these are the load rules, which callers reach as Recovery.x.
@file:Suppress("MagicNumber", "TooManyFunctions")

package org.openstrap.app.data

import org.openstrap.app.data.Recovery.DailyLoad
import org.openstrap.app.data.Recovery.LoadTrend
import org.openstrap.app.data.Recovery.MinuteRanges
import kotlin.math.exp
import kotlin.math.max

// Training load: heart-rate reserve, TRIMP, a day's load split into workout and everyday minutes, the workouts'
// own time and heart rate, and fitness, fatigue and strain over days. The types and constants are in Recovery.kt.

/**
 * A phone workout's active parts: [activeRanges] from its shown start to its end (now while recording), less its
 * pauses, the one under way included.
 */
fun Recovery.activeRanges(w: org.openstrap.app.data.db.AppWorkout, now: Long): List<LongRange> {
    val end = w.end ?: now
    return activeRanges(w.from, end, w.pauseRanges + listOfNotNull(w.pausedAt?.let { it..end }))
}

/** Every stored workout's active time, phone ([activeRanges]) and strap (start until end), as [inWorkout] takes it. */
fun Recovery.workoutRanges(
    phone: List<org.openstrap.app.data.db.AppWorkout>,
    strap: List<org.openstrap.app.data.db.Workout>,
    now: Long,
): List<LongRange> = (phone.flatMap { activeRanges(it, now) } + strap.map { it.start until it.end }).filter { !it.isEmpty() }

/**
 * The longer stress exclusion after hard workouts: from each finished phone or strap workout's end, the time its
 * aerobic training effect asks for ([STRESS_AFTER_HARD_WORKOUT_SECONDS] from TE [HARD_WORKOUT_TE],
 * [STRESS_AFTER_VERY_HARD_WORKOUT_SECONDS] from [VERY_HARD_WORKOUT_TE]). Lighter workouts and ones without a training
 * effect add nothing; [stressExcluded] already covers their 20 minutes.
 */
fun Recovery.stressTails(
    phone: List<org.openstrap.app.data.db.AppWorkout>,
    strap: List<org.openstrap.app.data.db.Workout>,
): List<LongRange> {
    fun tail(end: Long, te: Float?): LongRange? = when {
        te == null || te < HARD_WORKOUT_TE -> null
        te < VERY_HARD_WORKOUT_TE -> end..end + STRESS_AFTER_HARD_WORKOUT_SECONDS
        else -> end..end + STRESS_AFTER_VERY_HARD_WORKOUT_SECONDS
    }
    return phone.mapNotNull { w -> w.end?.let { tail(it, w.aerobicTrainingEffect) } } +
        strap.mapNotNull { w -> tail(w.end, w.aerobicTrainingEffect) }
}

/**
 * Acute:chronic workload ratio for the Load screen only, labelled contested: it is out of Readiness because the
 * evidence is against scoring with it (evidence-review.md, "Load screen"). Null before 21 days of load: the 7-day
 * acute week plus [ACWR_MIN_CHRONIC_DAYS] of chronic load before it.
 */
fun Recovery.acwr(daily: List<Float>): Float? {
    // Uncoupled: the chronic EWMA stops where the acute week begins, so this week's load isn't in both.
    if (daily.size < ACWR_ACUTE_DAYS + ACWR_MIN_CHRONIC_DAYS) return null
    val acute = ewma(daily, ACWR_ACUTE_DAYS)
    val chronic = ewma(daily.dropLast(ACWR_ACUTE_DAYS), ACWR_CHRONIC_DAYS)
    return acute / max(chronic, MIN_CHRONIC_LOAD)
}

/** Exponentially weighted mean of [daily] with λ = 2/([days] + 1). */
fun Recovery.ewma(daily: List<Float>, days: Int): Float {
    val l = 2f / (days + 1)
    var v = daily.first()
    for (x in daily.drop(1)) v = l * x + (1 - l) * v
    return v
}

/** Heart-rate reserve fraction: 0 at resting, 1 at maximum. */
fun Recovery.hrr(hr: Int, rest: Float, maxHr: Float): Float = ((hr - rest) / (maxHr - rest)).coerceIn(0f, 1.2f)

/**
 * Banister TRIMP for one minute at [hrr]: 0.64·e^(1.92·x) for men, 0.86·e^(1.67·x) for women (the
 * lactate-curve weightings). Unknown sex uses the men's curve, the common default.
 */
fun Recovery.trimpMinute(hrr: Float, female: Boolean = false): Float = when {
    hrr <= 0f -> 0f
    female -> hrr * 0.86f * exp(1.67f * hrr)
    else -> hrr * 0.64f * exp(1.92f * hrr)
}

/**
 * Daily load: Banister TRIMP per minute, in two parts. No HR-load method is validated on free-living minutes;
 * Banister is the least-bad choice, and the gate below is what keeps everyday heart rate out of it.
 *
 * - **Workout:** every minute that overlaps one of [workouts] (phone or strap, as time ranges), whatever its
 *   intensity and even if marked asleep. Overlapping workouts share minutes, so each minute counts once.
 * - **Everyday:** any other awake minute counts only when the strap counted steps that minute ([steps]) and
 *   [everydayMinute] passes it: at or above [LOAD_MIN_HRR] of reserve, or at or above [LOAD_WALK_HRR] with at least
 *   [LOAD_WALK_STEPS] steps. At least [LOAD_RUN_MINUTES] of the last [LOAD_RUN_WINDOW] minutes (itself included)
 *   must pass too, so an isolated PPG spike during a few steps doesn't count. Raised HR while sitting (coffee,
 *   stress, heat, a PPG artefact) is not training load. Without steps, a minute counts only inside a sustained run
 *   ([sustainedRuns]), the rule Charge drains by too: cycling or rowing that wasn't recorded. All of such a run
 *   counts, its first minutes too.
 *
 * Workout minutes count at any intensity, unlike Zepp, which applies its 35 % line to every minute (native 07
 * §3.3): a recorded workout's easy stretches (warm-up, recoveries between intervals, rests between sets) are part
 * of the session, and dropping them would understate it, while the low TRIMP of an easy minute keeps them small.
 * Everyday minutes follow Zepp's Exertion: movement and 35 % of reserve, with the day split into workout and
 * everyday activity. The walking exception is OpenStrap's own (see [LOAD_WALK_HRR]). [hr] is one value per minute
 * (epoch seconds, the minute's start); the engine replaces workout minutes with the workout's own HR.
 */
fun Recovery.dailyLoad(
    hr: List<Pair<Long, Int>>,
    rest: Float,
    maxHr: Float,
    female: Boolean = false,
    workouts: List<LongRange> = emptyList(),
    asleep: (Long) -> Boolean = { false },
    steps: (Long) -> Int?,
): DailyLoad {
    var workout = 0.0
    // Each everyday minute: its start, its reserve fraction and whether a sustained run carries it.
    val everyday = ArrayList<Triple<Long, Float, Boolean>>()
    val inWorkout = MinuteRanges(workouts)
    val sorted = hr.sortedBy { it.first }
    // Charge's stress exclusion: a workout and the minutes after it, where a raised heart rate is the workout's.
    val excluded = MinuteRanges(stressExcluded(workouts))
    val sustained = sustainedRuns(sorted.map { it.first }) { k ->
        val (t, bpm) = sorted[k]
        !asleep(t) && !excluded(t) && hrr(bpm, rest, maxHr) >= EXERCISE_HRR
    }
    for ((k, minute) in sorted.withIndex()) {
        val (t, bpm) = minute
        val x = hrr(bpm, rest, maxHr)
        when {
            inWorkout(t) -> workout += trimpMinute(x, female)
            !asleep(t) && (sustained[k] || everydayMinute(x, steps(t))) -> everyday += Triple(t, x, sustained[k])
        }
    }
    var lo = 0
    var total = 0.0
    for ((k, m) in everyday.withIndex()) {
        while (everyday[lo].first <= m.first - LOAD_RUN_WINDOW * 60L) lo++
        // A sustained run is already longer than the window, so all of it counts, as all of it drains Charge.
        if (m.third || k - lo + 1 >= LOAD_RUN_MINUTES) total += trimpMinute(m.second, female)
    }
    return DailyLoad(workout.toFloat(), total.toFloat())
}

/**
 * [minutes] (one HR a minute) with each workout minute taken from the workout's own recording, as Zepp does: the
 * mean of a workout's samples in that minute, only those inside its time range (so not the strap's recovery
 * recording after it), the higher one where two workouts overlap. A workout minute with no sample of its own
 * keeps the all-day value. [workouts] is (time range, samples as epoch second to bpm).
 */
fun Recovery.withWorkoutHeartRate(
    minutes: List<Pair<Long, Int>>,
    workouts: List<Pair<LongRange, List<Pair<Long, Int>>>>,
): List<Pair<Long, Int>> = withWorkoutMinutes(minutes, workouts.map { (range, samples) -> workoutMinutes(range, samples) })

/**
 * One workout's own heart rate per minute (minute start to bpm), for [withWorkoutMinutes]: the rounded mean of its
 * valid [samples] inside [range]. Reduced as each workout is read, so months of per-second samples aren't held.
 */
fun Recovery.workoutMinutes(range: LongRange, samples: List<Pair<Long, Int>>): Map<Long, Int> =
    samples.filter { it.first in range && it.second in HR_MIN_BPM..HR_MAX_BPM }
        .groupBy { it.first / 60 * 60 }
        .mapValues { (_, v) -> Math.round(v.map { it.second }.average()).toInt() }

/** [withWorkoutHeartRate] on workouts already reduced by [workoutMinutes]. */
@Suppress("ReturnCount") // Each early return hands the minutes back unchanged.
fun Recovery.withWorkoutMinutes(minutes: List<Pair<Long, Int>>, workouts: List<Map<Long, Int>>): List<Pair<Long, Int>> {
    if (workouts.isEmpty()) return minutes
    val own = HashMap<Long, Int>()
    for (w in workouts) for ((m, bpm) in w) own[m] = max(own[m] ?: 0, bpm)
    if (own.isEmpty()) return minutes
    return (minutes.toMap() + own).toList().sortedBy { it.first }
}

/**
 * The active parts of a workout from [from] until [end] (exclusive), leaving out [pauses] (inclusive second ranges,
 * as [org.openstrap.app.data.db.AppWorkout.pauseRanges] stores them), so paused time isn't workout load.
 */
fun Recovery.activeRanges(from: Long, end: Long, pauses: List<LongRange>): List<LongRange> {
    var parts = listOf(from until end)
    for (p in pauses) {
        parts = parts.flatMap { r ->
            if (p.last < r.first || p.first > r.last) {
                listOf(r)
            } else {
                listOf(r.first until p.first, p.last + 1..r.last).filter { !it.isEmpty() }
            }
        }
    }
    return parts
}

/** True when the minute starting at [t] overlaps any of [workouts] (inclusive second ranges). */
fun Recovery.inWorkout(t: Long, workouts: List<LongRange>): Boolean = workouts.any { t <= it.last && t + 60 > it.first }

/**
 * The movement signal for everyday load: the strap counted at least one step that minute (activity type 1,
 * `steps` in 1..[MAX_MINUTE_STEPS]). Checked against a real database before choosing it: the record's activity
 * `mode` (`kind`) was 0 ("silent") for every awake minute, never walking or running, so it says nothing; and its
 * `intensity` byte was above Zepp's "> 5" in 79 % of minutes without a step (typing, gesturing at a desk), so
 * Zepp's gate on it would let most of a seated day through, and any higher cut-off would be a guess. A step is
 * unambiguous. Non-stepping exercise (cycling, rowing) outside a recorded workout counts only as a sustained run
 * ([sustainedRuns]), and weights between sets rarely make one, so record a workout for those.
 */
fun Recovery.moving(steps: Int?): Boolean = steps != null && steps in 1..MAX_MINUTE_STEPS

/**
 * Movement without steps, one rule for Charge ([sustainedEffort]) and daily load ([dailyLoad]): the minutes, at the
 * minute starts [times] (ascending), in a run of at least [SUSTAINED_EFFORT_MINUTES] back to back that are each
 * [hard] (awake, outside a workout and the minutes after it, at or above [EXERCISE_HRR] of reserve).
 */
fun Recovery.sustainedRuns(times: List<Long>, hard: (Int) -> Boolean): BooleanArray {
    val out = BooleanArray(times.size)
    var i = 0
    while (i < times.size) {
        if (!hard(i)) {
            i++
            continue
        }
        var j = i + 1
        while (j < times.size && hard(j) && times[j] - times[j - 1] <= 60) j++
        if (j - i >= SUSTAINED_EFFORT_MINUTES) for (k in i until j) out[k] = true
        i = j
    }
    return out
}

/**
 * Whether a day goes into the load history: worn at least [LOAD_MIN_WORN_MINUTES] (heart rate minutes [worn]), or
 * worn less but with a recorded workout's load in it ([load]). A short day with a workout counts what it measured,
 * as the workout is most of its load, rather than drop the workout from fitness and fatigue. A short day without one
 * says too little and holds.
 */
fun Recovery.loadDayCounts(worn: Int, load: DailyLoad): Boolean = worn >= LOAD_MIN_WORN_MINUTES || load.workout > 0f

/** Whether an awake minute outside workouts, at reserve fraction [x] with [steps], can add everyday load. */
fun Recovery.everydayMinute(x: Float, steps: Int?): Boolean =
    steps != null && moving(steps) && (x >= LOAD_MIN_HRR || (x >= LOAD_WALK_HRR && steps >= LOAD_WALK_STEPS))

/**
 * Daytime resting HR for heart-rate reserve: Karvonen and Swain define resting HR as awake rest, so a nightly
 * minimum would inflate HRR for every waking minute. The median of the strap's daily resting HR over the last
 * [RHR_DAYS] days ([daily], day → bpm); else the median nightly lowest ([nightly]) of those days plus an offset:
 * the median of (daily − nightly lowest) over the latest [RHR_DAYS] days that have both, else [RHR_OFFSET_BPM].
 */
@Suppress("ReturnCount") // The returns are the rule's fallbacks, in order.
fun Recovery.daytimeRestingHr(daily: Map<Long, Float>, nightly: Map<Long, Float>, day: Long): Float? {
    val recent = { m: Map<Long, Float> -> m.filterKeys { it <= day && day - it < RHR_DAYS * DAY - DST_SLACK }.values.toList() }
    percentile(recent(daily), 0.5f)?.let { return it }
    val lowest = percentile(recent(nightly), 0.5f) ?: return null
    val pairs = daily.keys.filter { it in nightly }.sortedDescending().take(RHR_DAYS).map { daily.getValue(it) - nightly.getValue(it) }
    return lowest + (percentile(pairs, 0.5f) ?: RHR_OFFSET_BPM)
}

/**
 * A day whose TRIMP is more than [HARD_DAY_RATIO]× the chronic load (CTL to the day before, [fitnessFatigue]): annotated
 * as a likely cause of an alert. Readiness's previous-day contributor starts taking points off at the same ratio.
 */
fun Recovery.hardDay(load: Float, chronic: Float): Boolean = load > HARD_DAY_RATIO * max(chronic, MIN_CHRONIC_LOAD)

/**
 * CTL_t = CTL_{t−1} + (TRIMP_t − CTL_{t−1})·(1 − e^(−1/42)), ATL likewise with 7, Form_t = CTL_{t−1} − ATL_{t−1}, over
 * [daily] (oldest first; only worn days, so an unworn day holds rather than decays). Seeded with the mean of the
 * first week, so a short history doesn't start at zero.
 */
fun Recovery.fitnessFatigue(daily: List<Float>): List<LoadTrend> {
    if (daily.isEmpty()) return listOf(LoadTrend(0f, 0f, 0f))
    val kc = 1 - exp(-1f / CTL_DAYS)
    val ka = 1 - exp(-1f / ATL_DAYS)
    var ctl = daily.take(7).average().toFloat()
    var atl = ctl
    return daily.map { x ->
        val form = ctl - atl
        ctl += (x - ctl) * kc
        atl += (x - atl) * ka
        LoadTrend(ctl, atl, form)
    }
}

/** Strain 0-21 from a day's TRIMP: 21·(1 − e^(−TRIMP/180)). A fixed curve, so today's 12 compares with last year's. */
fun Recovery.strain(trimp: Float): Float = STRAIN_MAX * (1 - exp(-trimp.coerceAtLeast(0f) / STRAIN_SCALE))

/** "vs your usual": the share (0-100) of the last 90 days' loads ([history]) at or below [today]; null under 7 days. */
fun Recovery.loadPercentile(today: Float, history: List<Float>): Int? =
    if (history.size < 7) null else Math.round(100f * history.count { it <= today } / history.size)

