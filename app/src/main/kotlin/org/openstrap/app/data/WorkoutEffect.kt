package org.openstrap.app.data

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * What a phone workout did to you, worked out in pure Kotlin like [Recovery]: EPOC, aerobic training effect, recovery
 * time and, for outdoor runs with GPS, VO2max. The strap gives these for its own workouts (GDSP type 59); Zepp works
 * them out for app workouts with neural networks we can't reuse (native 04 §1), so OpenStrap uses open methods in the
 * same 5-second epochs and on the same scales. See docs/algorithms/calculations.md §13.
 *
 * Anaerobic training effect is left out: Zepp's is a network on EPOC increments (native 04 §4.2), and no published
 * HR-only model holds up for short efforts, where heart rate lags the work by tens of seconds.
 */
object WorkoutEffect {
    /**
     * Raised when [epoc], recovery or [vo2max] changes, so saved workouts are worked out again once
     * (SyncCoordinator.updateEffectModel). 5: VO2max over %VO2R ([Recovery.vo2Reserve]).
     */
    const val MODEL = 5

    /** Zepp works in 5-sample epochs of 1 Hz data, each read as its median HR (native 04 §3.1). */
    const val EPOCH_S = 5L
    /** Heart rate outside this is the sensor, not a heartbeat (native 04 §3 step 2). */
    const val MIN_BPM = 40
    const val MAX_BPM = 220
    /** An epoch with no valid reading keeps the last one this many times, then counts as missing (native 04 §4.1). */
    const val HOLD_EPOCHS = 2

    /** One epoch: its start, median heart rate, and whether the workout was paused then. */
    data class Epoch(val t: Long, val bpm: Int, val paused: Boolean = false)

    /**
     * [hr] (time, bpm) as epochs from [from] to [end]: the median of each epoch's valid readings, held for
     * [HOLD_EPOCHS] when one has none, and missing after that, so a dropout neither adds nor clears load.
     */
    fun epochs(hr: List<Pair<Long, Int>>, from: Long, end: Long, pauses: List<LongRange> = emptyList()): List<Epoch> {
        val byEpoch = hr.filter { it.first in from until end && it.second in MIN_BPM..MAX_BPM }
            .groupBy({ (it.first - from) / EPOCH_S }, { it.second })
        val out = mutableListOf<Epoch>()
        var last: Int? = null
        var held = 0
        for (k in 0 until (end - from + EPOCH_S - 1) / EPOCH_S) {
            val t = from + k * EPOCH_S
            val values = byEpoch[k]
            val bpm = if (values != null) {
                held = 0
                Stats.upperMiddle(values)?.also { last = it }
            } else if (last != null && held < HOLD_EPOCHS) {
                held++
                last
            } else null
            if (bpm != null) out += Epoch(t, bpm, pauses.any { t in it })
        }
        return out
    }

    /* ------------------------------------------------ EPOC ------------------------------------------------ */

    /**
     * EPOC added a minute at intensity i: [EPOC_RATE]·(e^([EPOC_STEEPNESS]·i) − 1) ml/kg. The level is fitted to the
     * strap's own EPOC on three of its workouts over the same heart rate (37 min at 117 bpm gave 16.3, 9 min at 119
     * gave 6), all easy; the steepness keeps harder work where it was aimed before, so 30 minutes at 60 % HRR land
     * near aerobic TE 2.5, a 60-minute tempo at 85 % at TE 5, and 6 × 3-minute intervals near TE 3.7, on Zepp's
     * class-50 scale (WorkoutEffectTest). A fallback for when the strap kept no record of its own ([WorkoutEffectEngine]).
     */
    const val EPOC_RATE = 0.077
    const val EPOC_STEEPNESS = 4.5

    /**
     * EPOC (ml/kg) over [epochs], the quantity training effect is read from. As Zepp's (native 04 §4.2), it only adds:
     * each epoch puts in a share that grows exponentially with intensity (EPOC grows exponentially with intensity and
     * linearly with duration, Børsheim & Bahr 2003), intensity being the heart-rate reserve (%HRR ≈ %VO2R, Swain &
     * Leutholtz 1997), and nothing is taken off during the workout. An earlier version let EPOC clear as it went and
     * took its peak, which held a long easy walk to a third of what the strap gave. Coefficients are OpenStrap's own
     * fit, not Zepp's network.
     */
    fun epoc(epochs: List<Epoch>, restingHr: Float, maxHr: Float): Float {
        val dt = EPOCH_S / 60.0
        return epochs.sumOf { ep ->
            val i = Recovery.hrr(ep.bpm, restingHr, maxHr).toDouble().coerceIn(0.0, 1.0)
            EPOC_RATE * (exp(EPOC_STEEPNESS * i) - 1) * dt
        }.toFloat()
    }

    /* ------------------------------------------- Training effect ------------------------------------------- */

    /** Zepp's activity classes and the 28-day load (sum of workout EPOC) each runs up to (native 04 §4.5). */
    private val CLASS_LOADS = listOf(450f to 40, 570f to 50, 720f to 60, 870f to 70, 1020f to 75, 1320f to 80, 1570f to 85, 1920f to 90, 2450f to 95)

    /** Activity class 40 to 100 from the last 28 days' workout EPOC, 50 with none, as Zepp's app does (native 04 §4.5). */
    fun activityClass(load28: Float): Int =
        if (load28 <= 0f) 50 else CLASS_LOADS.firstOrNull { load28 <= it.first }?.second ?: 100

    /** EPOC where aerobic TE reaches 1, 2, 3, 4 and 5, by activity class: Zepp's table (native 04 §4.4). */
    private val TE_EPOC = mapOf(
        40 to floatArrayOf(6.5f, 12.8f, 40.9f, 96.4f, 156.7f),
        50 to floatArrayOf(7.9f, 15.7f, 49.7f, 119.8f, 199.2f),
        60 to floatArrayOf(9.4f, 18.6f, 58.8f, 141.3f, 242.1f),
        70 to floatArrayOf(10.8f, 21.5f, 67.5f, 163.9f, 292.4f),
        75 to floatArrayOf(11.5f, 23.0f, 71.9f, 177.1f, 316.2f),
        80 to floatArrayOf(12.3f, 24.5f, 76.2f, 189.7f, 343.3f),
        85 to floatArrayOf(13.0f, 25.8f, 80.9f, 201.8f, 384.5f),
        90 to floatArrayOf(13.8f, 27.5f, 85.3f, 215.3f, 418.9f),
        95 to floatArrayOf(14.6f, 28.9f, 89.6f, 230.6f, 429.5f),
        100 to floatArrayOf(15.4f, 30.6f, 94.2f, 245.3f, 445.9f),
    )

    /** Zepp gives no TE for a minute or less of epochs, or under 1 ml/kg (native 04 §4.4). */
    const val MIN_TE_EPOCHS = 12

    /**
     * Aerobic training effect 0 to 5 from peak [epoc]: straight lines between the class's breakpoints (Zepp's own
     * segments, which don't quite meet, differ from them by up to 0.2), floored to a tenth and capped at 5 as Zepp does (native 04 §4.4). A
     * fitter class needs more EPOC for the same effect.
     */
    fun aerobicTe(epoc: Float, epochCount: Int, activityClass: Int): Float {
        if (epochCount < MIN_TE_EPOCHS || epoc < 1f) return 0f
        val row = TE_EPOC[activityClass] ?: TE_EPOC.getValue(40)
        var lo = 0f
        for ((k, hi) in row.withIndex()) {
            if (epoc < hi) return floor(10 * (k + (epoc - lo) / (hi - lo))) / 10
            lo = hi
        }
        return 5f
    }

    /* ------------------------------------------- Recovery time ------------------------------------------- */

    /**
     * Hours to recover by aerobic TE, joined by straight lines. Zepp's own rule is switched off for app workouts
     * (native 04 §6.2), so this keeps its shape, rising with load to 72 h at TE 5, anchored low on the strap's own:
     * 11 h for a walk at aerobic TE 2.1. A design choice beyond that one point.
     */
    private val RECOVERY_HOURS = listOf(0f to 0f, 1f to 5f, 2f to 10f, 3f to 20f, 4f to 40f, 5f to 72f)
    /** Never longer than Zepp's TE-5 value (native 04 §6.2); its 96 h needs an anaerobic TE we don't make. */
    const val MAX_RECOVERY_H = 72f
    /** At most this much longer when fatigue (ATL) runs ahead of fitness (CTL). */
    const val FATIGUE_EXTRA = 0.25f
    /** Fitness (EPOC per day) below this is treated as this, so a first week's tiny CTL doesn't swing the factor. */
    const val MIN_CTL = 5f

    /**
     * Recovery time in minutes: hours by [aerobicTe], up to [FATIGUE_EXTRA] longer as [trend]'s fatigue exceeds its
     * fitness (both from daily workout EPOC, [Recovery.fitnessFatigue]), never less than [carriedMinutes] left over from
     * earlier workouts (Zepp's max(new, old − elapsed), native 04 §6.2), and at most [MAX_RECOVERY_H]. It only ever
     * lengthens for fatigue, never shortens for fitness: fitness already lowers TE through the activity class.
     */
    fun recoveryMinutes(aerobicTe: Float, trend: Recovery.LoadTrend?, carriedMinutes: Int = 0): Int {
        val te = aerobicTe.coerceIn(0f, 5f)
        val (a, b) = RECOVERY_HOURS.zipWithNext().first { (_, hi) -> te <= hi.first }
        var hours = a.second + (b.second - a.second) * (te - a.first) / (b.first - a.first)
        if (trend != null) hours *= 1 + FATIGUE_EXTRA * ((trend.atl - trend.ctl) / maxOf(trend.ctl, MIN_CTL)).coerceIn(0f, 1f)
        return maxOf(Math.round(minOf(hours, MAX_RECOVERY_H) * 60), carriedMinutes.coerceAtMost((MAX_RECOVERY_H * 60).toInt()))
    }

    /* ----------------------------------------------- VO2max ----------------------------------------------- */

    /** Outdoor run: the only sport with GPS speed on the level that the ACSM running equation fits. */
    const val OUTDOOR_RUN = 1
    /** A qualifying epoch, as Zepp's: at least jogging speed and half of max HR (native 04 §5.2). */
    const val MIN_SPEED_MS = 1.5
    /** Zepp waits for 120 qualifying epochs, about 10 minutes of running (native 04 §5.2). */
    const val MIN_QUALIFYING_EPOCHS = 120
    /** No window starts in the first two minutes, Zepp's own wait before scoring (native 04 §5.2). */
    const val WARMUP_S = 120L
    /** A window: speed over all of it, heart rate over its second half, once HR has caught up with the pace. */
    const val WINDOW_S = 120L
    const val WINDOW_STEP_S = 30L
    /** Steady: the four 30-second speeds within this SD (Zepp's 0.3 m/s, native 04 §5.2) and HR within this SD. */
    const val MAX_SPEED_SD = 0.3
    const val MAX_HR_SD = 4.0
    /** The heart-rate reserve a window must be run at. Below half the reserve the extrapolation magnifies noise. */
    const val MIN_HRR = 0.5f
    const val MAX_HRR = 0.95f
    /** A window climbing or falling more than this is skipped: GPS height can't grade-adjust (Zepp's dead band, native 04 §5.4). */
    const val MAX_GRADE = 0.02
    /** Enough steady windows to take a median of, and how far apart their middle half may be before it's noise. */
    const val MIN_WINDOWS = 5
    /**
     * Of the windows run at a qualifying pace, at least this share must be steady: on a run whose heart rate jumps
     * about, the few windows that happen to look steady are luck, not data.
     */
    const val MIN_STEADY_SHARE = 0.3
    const val MAX_IQR = 5.0
    /** Outside this a value is an artefact (Zepp drops < 20 and > 97.5, native 04 §5.4). */
    const val MIN_VO2MAX = 20.0
    const val MAX_VO2MAX = 90.0

    /**
     * VO2max (ml/kg/min) from an outdoor run's steady stretches: the ACSM running equation on the level, VO2 = 3.5 +
     * 0.2·v (v in m/min; ACSM's Guidelines, 11th ed.), at an oxygen-uptake reserve r gives VO2max = 3.5 + (VO2 − 3.5)/r,
     * with r from the heart-rate reserve by [Recovery.vo2Reserve]. Gated as Zepp gates its own (native 04 §5.2): outdoor runs only, ten
     * minutes of qualifying epochs, steady windows only, after a warm-up; then the median of the windows. Null with
     * too little or too noisy data. Speed is from [Route.track], which already folds in the Doppler speed.
     */
    fun vo2max(sportType: Int, epochs: List<Epoch>, route: List<Route.Point>, pauses: List<LongRange>, restingHr: Float, maxHr: Float): Float? {
        if (sportType != OUTDOOR_RUN || epochs.isEmpty()) return null
        val track = Route.track(route)
        if (track.size < 2) return null
        val dist = cumulative(track, pauses)
        fun distAt(t: Long): Double? {
            if (t < track.first().t || t > track.last().t) return null
            val i = track.indexOfLast { it.t <= t }
            if (i == track.lastIndex) return dist[i]
            val a = track[i]
            val b = track[i + 1]
            return dist[i] + (dist[i + 1] - dist[i]) * (t - a.t) / (b.t - a.t).coerceAtLeast(1)
        }
        fun speed(from: Long, to: Long): Double? {
            val a = distAt(from) ?: return null
            val b = distAt(to) ?: return null
            return (b - a) / (to - from)
        }

        val qualifying = epochs.count { e -> !e.paused && e.bpm * 2 >= maxHr && (speed(e.t, e.t + EPOCH_S) ?: 0.0) >= MIN_SPEED_MS }
        if (qualifying < MIN_QUALIFYING_EPOCHS) return null

        val byT = epochs.associateBy { it.t }
        val first = epochs.first().t
        val heights = track.filter { it.altitude != null }
        val estimates = mutableListOf<Double>()
        var running = 0
        var s = first + WARMUP_S
        while (s + WINDOW_S <= epochs.last().t + EPOCH_S) {
            window(s, byT, heights, ::speed, restingHr, maxHr)?.let { running++; if (!it.isNaN()) estimates += it }
            s += WINDOW_STEP_S
        }
        if (estimates.size < MIN_WINDOWS || estimates.size < MIN_STEADY_SHARE * running) return null
        val sorted = estimates.sorted()
        val iqr = quantile(sorted, 0.75) - quantile(sorted, 0.25)
        val median = quantile(sorted, 0.5)
        return if (iqr > MAX_IQR || median !in MIN_VO2MAX..MAX_VO2MAX) null else median.toFloat()
    }

    /**
     * One window's VO2max; NaN when it was run at a qualifying pace but isn't steady, level or in the reserve range;
     * null when it isn't wholly recorded, is paused, or is slower than [MIN_SPEED_MS].
     */
    private fun window(
        s: Long, byT: Map<Long, Epoch>, heights: List<Route.Point>, speed: (Long, Long) -> Double?, restingHr: Float, maxHr: Float,
    ): Double? {
        val eps = (0 until WINDOW_S / EPOCH_S).map { byT[s + it * EPOCH_S] ?: return null }
        if (eps.any { it.paused }) return null
        val quarters = (0 until 4).map { q -> speed(s + q * 30, s + (q + 1) * 30) ?: return null }
        val v = quarters.average()
        if (v < MIN_SPEED_MS) return null
        if (sd(quarters) > MAX_SPEED_SD) return Double.NaN
        val late = eps.drop(eps.size / 2).map { it.bpm.toDouble() }
        if (sd(late) > MAX_HR_SD) return Double.NaN
        val i = Recovery.hrr(Math.round(late.average()).toInt(), restingHr, maxHr)
        if (i < MIN_HRR || i > MAX_HRR) return Double.NaN
        // Level ground only: the mean height of the first and last 10 s, over the distance between.
        val start = heights.filter { it.t in s until s + 10 }.mapNotNull { it.altitude }
        val end = heights.filter { it.t in s + WINDOW_S - 10 until s + WINDOW_S }.mapNotNull { it.altitude }
        if (start.isNotEmpty() && end.isNotEmpty() && kotlin.math.abs(end.average() - start.average()) > MAX_GRADE * v * WINDOW_S) return Double.NaN
        val vo2 = 3.5 + 0.2 * v * 60
        return 3.5 + (vo2 - 3.5) / Recovery.vo2Reserve(i.toDouble())
    }

    /** Metres along [track] up to each point, adding nothing across a pause. */
    private fun cumulative(track: List<Route.Point>, pauses: List<LongRange>): DoubleArray {
        val out = DoubleArray(track.size)
        for (k in 1 until track.size) {
            val a = track[k - 1]
            val b = track[k]
            out[k] = out[k - 1] + if (pauses.any { it.first < b.t && it.last > a.t }) 0.0 else Route.meters(a, b)
        }
        return out
    }

    private fun sd(x: List<Double>): Double {
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / x.size)
    }

    private fun quantile(sorted: List<Double>, p: Double): Double = Stats.quantileOfSorted(sorted, p)

    /* ---------------------------------------------- Together ---------------------------------------------- */

    /** A workout's figures. [epoc] is null without heart rate; [vo2max] null outside a valid run. */
    /** [recoveryMinutes] counts what earlier workouts left; [ownRecoveryMinutes] is this workout's alone. */
    data class Result(val epoc: Float?, val aerobicTe: Float?, val recoveryMinutes: Int?, val vo2max: Float?, val ownRecoveryMinutes: Int? = null)

    /**
     * Everything for one workout from [from] to [end]. [load28] is the 28 days' workout EPOC before it, [trend] the
     * fitness and fatigue from daily workout EPOC, [carriedMinutes] recovery still owed from earlier workouts.
     */
    fun compute(
        sportType: Int, hr: List<Pair<Long, Int>>, from: Long, end: Long, pauses: List<LongRange>, route: List<Route.Point>,
        restingHr: Float, maxHr: Float, load28: Float, trend: Recovery.LoadTrend?, carriedMinutes: Int = 0,
    ): Result {
        val eps = epochs(hr, from, end, pauses)
        if (eps.isEmpty()) return Result(null, null, null, null)
        val e = epoc(eps, restingHr, maxHr)
        val te = aerobicTe(e, eps.size, activityClass(load28))
        return Result(
            e, te, recoveryMinutes(te, trend, carriedMinutes), vo2max(sportType, eps, route, pauses, restingHr, maxHr),
            ownRecoveryMinutes = recoveryMinutes(te, trend),
        )
    }
}
