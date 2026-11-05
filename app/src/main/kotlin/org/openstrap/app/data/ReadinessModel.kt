// The numbers are the model's published coefficients and curve shapes, each explained in the KDoc beside it and in
// docs/algorithms/recovery.md.
// One part of the model per file: these are the readiness rules, which callers reach as Recovery.x.
@file:Suppress("MagicNumber", "TooManyFunctions")

package org.openstrap.app.data

import org.openstrap.app.data.Recovery.Baseline
import org.openstrap.app.data.Recovery.BreathingDisturbances
import org.openstrap.app.data.Recovery.Contributor
import org.openstrap.app.data.Recovery.Detail
import org.openstrap.app.data.Recovery.Factor
import org.openstrap.app.data.Recovery.HrvRange
import org.openstrap.app.data.Recovery.Night
import org.openstrap.app.data.Recovery.Phase
import org.openstrap.app.data.Recovery.Readiness
import org.openstrap.app.data.db.SleepStage
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

// Readiness: the morning score, one function per contributor, and the sleep score and debt it uses. The types and
// constants are in Recovery.kt, which docs/algorithms/recovery.md names them by.

/**
 * Maps a z-score (deviation from baseline in standard deviations, positive = better) to 0-100.
 * A normal night (z = 0) scores 80, one SD better ~93, one SD worse ~55, two SD worse ~27.
 */
fun Recovery.scoreFromZ(z: Float): Int = Math.round(100.0 / (1.0 + exp(-(Z_SLOPE * z + Z_OFFSET)))).toInt().coerceIn(0, 100)
private const val Z_SLOPE = 1.19
private val Z_OFFSET = ln(4.0)

/** The dead zone at the smallest worthwhile change: z_eff = sign(z)·max(0, |z| − [SWC_SD]). Smaller changes are noise. */
fun Recovery.deadZone(z: Float): Float = sign(z) * max(0f, abs(z) - SWC_SD)

/** Sleep need in minutes: the user's [override] when it's in range, else by [age]. */
fun Recovery.sleepNeed(age: Int?, override: Int? = null): Int = when {
    override != null && override in SLEEP_NEED_MIN..SLEEP_NEED_MAX -> override
    age != null && age >= 65 -> SLEEP_NEED_65_MINUTES
    else -> SLEEP_NEED_MINUTES
}

/**
 * Readiness for [tonight] against [history] (earlier nights, any order, tonight excluded) and
 * [dailyLoad] (TRIMP per day, oldest first, ending with yesterday). [causes] gives the likely causes noted for a
 * morning, to annotate a body-signal alert.
 */
fun Recovery.readiness(
    tonight: Night?,
    history: List<Night>,
    dailyLoad: List<Float> = emptyList(),
    causes: (Long) -> List<String> = { emptyList() },
    sleepNeed: Int = SLEEP_NEED_MINUTES,
): Readiness {
    if (tonight == null) {
        val valid = history.filter { it.valid }.takeLast(BASELINE_NIGHTS)
        val nights = max(valid.count { it.hrvLn != null }, valid.count { it.restingHr != null })
        return Readiness(null, emptyList(), nights, false, emptyList(), false)
    }
    val earlier = history.filter { it.day < tonight.day }.sortedBy { it.day }
    // Body signals first: nights in Watch or Alert stay out of every baseline, so a bad stretch can't become normal.
    val states = BodySignals.evaluate(earlier + tonight, causes)
    val flagged = states.filter { it.state != BodySignals.State.Normal }.mapTo(HashSet()) { it.day }
    val body = states.last()
    val usable = earlier.filter { it.day !in flagged }
    // Baselines use the prior BASELINE_NIGHTS valid nights; invalid nights never enter them.
    val validPast = usable.filter { it.valid }.takeLast(BASELINE_NIGHTS)
    val past = usable.takeLast(BASELINE_NIGHTS)

    val lnHistory = validPast.mapNotNull { it.hrvLn }
    val rhrHistory = validPast.mapNotNull { it.restingHr }
    val nights = max(lnHistory.size, rhrHistory.size)
    val phase = phase(nights)
    val scored = phase != Phase.Withheld

    val hrvBase = baseline(lnHistory, HRV_LN_SD_FLOOR)
    val week = (earlier + tonight).filter { tonight.day - it.day <= 6 * DAY + DST_SLACK }.mapNotNull { it.hrvLn }
    val hrv7 = week.takeIf { it.size >= HRV_WEEK_MIN_NIGHTS }?.average()?.toFloat()
    // One contributor per factor, in this order (the weighted mean adds them up in it). HRV and resting HR wait for
    // their baselines ([Phase.Withheld]).
    val out = listOfNotNull(
        if (scored) hrvBalance(tonight, hrvBase, hrv7) else null,
        if (scored) restingHeartRate(tonight, baseline(rhrHistory, RHR_SD_FLOOR)) else null,
        // Last night's sleep: the strap's own score when there is one, else ours.
        Contributor(Factor.Sleep, tonight.strapScore ?: sleepScore(tonight, sleepNeed), Detail.Asleep(tonight.asleepMinutes)),
        sleepBalance(earlier + tonight, tonight, sleepNeed),
        recoveryIndex(tonight),
        temperature(tonight, past),
        breathing(tonight, past),
        bloodOxygen(tonight, past),
        previousDayLoad(dailyLoad),
    )

    // The strap's own values: a fallback for display when ours failed the validity rule, and a quality check.
    val fallbackHrv = tonight.strapHrv?.takeIf { tonight.hrvLn == null }
    val fallbackRhr = tonight.strapRestingHr?.takeIf { tonight.validRestingHr == null }
    val quality = qualityFlags(tonight)

    val alert = body.state == BodySignals.State.Alert
    val extras = { score: Int?, capped: Boolean, withheld: Boolean, softMin: Int? ->
        Readiness(
            score, out, nights, alert, if (body.state == BodySignals.State.Normal) emptyList() else body.signals, capped, phase,
            withheld, hrvRangeOf(hrvBase, hrv7), week.size, fallbackHrv, fallbackRhr, quality, body, softMin,
        )
    }
    val soft = softScore(out, alert)
    return when {
        // Withhold without an autonomic contributor: sleep alone isn't readiness.
        out.none { it.factor == Factor.HrvBalance || it.factor == Factor.RestingHr } -> extras(null, false, true, null)

        SOFT_MIN_FUSION -> extras(soft, alert && soft == ALERT_CAP, false, soft)

        else -> weightedScore(out, alert).let { (score, capped) -> extras(score, capped, false, soft) }
    }
}

/** Where the 7-night HRV mean [hrv7] sits against the normal range of [base]; null without either. */
private fun Recovery.hrvRangeOf(base: Baseline?, hrv7: Float?): HrvRange? {
    if (base == null || hrv7 == null) return null
    return when {
        hrv7 < base.centre - HRV_RANGE_SCALE * base.scale -> HrvRange.Below
        hrv7 > base.centre + HRV_RANGE_SCALE * base.scale -> HrvRange.Above
        else -> HrvRange.Within
    }
}

/** HRV balance: 0.4·z(tonight) + 0.6·z(7-night mean of hrv_n) against the robust baseline, through the dead zone. */
private fun Recovery.hrvBalance(tonight: Night, base: Baseline?, hrv7: Float?): Contributor? {
    val ln = tonight.hrvLn
    // hrvLn is only there when hrv is.
    val hrv = tonight.hrv
    if (base == null || ln == null || hrv == null) return null
    val zNight = base.z(ln)
    val z = if (hrv7 != null) HRV_NIGHT_WEIGHT * zNight + HRV_WEEK_WEIGHT * base.z(hrv7) else zNight
    val pct = ((exp(ln - base.centre.toDouble()) - 1) * 100).toInt()
    return Contributor(Factor.HrvBalance, scoreFromZ(deadZone(z)), Detail.Hrv(hrv.toInt(), pct))
}

/** Resting heart rate: the night's lowest 30-minute average against baseline; lower is better. */
private fun Recovery.restingHeartRate(tonight: Night, base: Baseline?): Contributor? {
    val rhr = tonight.validRestingHr
    if (base == null || rhr == null) return null
    val delta = rhr - base.centre
    return Contributor(Factor.RestingHr, scoreFromZ(deadZone(-base.z(rhr))), Detail.RestingHr(rhr.toInt(), delta))
}

/** Sleep balance: debt built over the last 14 nights, against need (naps count). */
private fun Recovery.sleepBalance(nights: List<Night>, tonight: Night, sleepNeed: Int): Contributor? =
    sleepDebtMinutes(nights, tonight.day, sleepNeed)?.let { debt ->
        val scored = (debt - SLEEP_DEBT_FREE_MINUTES) / (SLEEP_DEBT_ZERO_MINUTES - SLEEP_DEBT_FREE_MINUTES)
        val score = (100 * (1 - scored)).toInt().coerceIn(0, 100)
        Contributor(Factor.SleepBalance, score, Detail.SleepDebt(if (debt < SLEEP_DEBT_NAMED_FROM) 0 else debt.toInt()))
    }

/** Recovery index: the night's lowest heart rate should come in its first half (Oura). Shown, but weighs nothing. */
private fun recoveryIndex(tonight: Night): Contributor? {
    if (tonight.nadirTime == null || tonight.end <= tonight.start) return null
    val p = (tonight.nadirTime - tonight.start).toFloat() / (tonight.end - tonight.start)
    val score = if (p <= 0.5f) 100 else (100 - 120 * (p - 0.5f)).toInt().coerceIn(40, 100)
    val after = ((tonight.end - tonight.nadirTime) / 60).toInt()
    return Contributor(Factor.RecoveryIndex, score, Detail.LowestHeartRate(after))
}

/** Body temperature: distance from the usual nightly skin temperature, either direction, through the dead zone. */
private fun Recovery.temperature(tonight: Night, past: List<Night>): Contributor? {
    val tempBase = baseline(past.mapNotNull { it.skinTemperature }, TEMP_SD_FLOOR)
    val t = tonight.skinTemperature
    if (t == null || tempBase == null) return null
    val dev = t - tempBase.centre
    return Contributor(Factor.Temperature, scoreFromZ(-abs(deadZone(tempBase.z(t)))), Detail.Temperature(dev))
}

/** Breathing rate: very stable night to night; a change either way beyond the dead zone counts. */
private fun Recovery.breathing(tonight: Night, past: List<Night>): Contributor? {
    val breathBase = baseline(past.mapNotNull { it.breathRate }, BREATH_SD_FLOOR)
    val b = tonight.breathRate
    if (b == null || breathBase == null) return null
    val dev = b - breathBase.centre
    return Contributor(Factor.Breathing, scoreFromZ(-abs(deadZone(breathBase.z(b)))), Detail.Breathing(dev))
}

/** Blood oxygen: only a fall below the usual nightly level counts; a higher reading is no better. */
private fun Recovery.bloodOxygen(tonight: Night, past: List<Night>): Contributor? {
    val spo2Base = baseline(past.mapNotNull { it.spo2 }, SPO2_SD_FLOOR)
    val o = tonight.spo2
    if (o == null || spo2Base == null) return null
    val dev = o - spo2Base.centre
    val score = (100 - SPO2_POINTS_PER_PERCENT * (-dev - SPO2_DEAD_BAND).coerceAtLeast(0f)).toInt().coerceIn(0, 100)
    return Contributor(Factor.BloodOxygen, score, Detail.BloodOxygen(o, dev))
}

/** Training load: yesterday against fitness (CTL, 42-day) up to the day before. */
private fun Recovery.previousDayLoad(dailyLoad: List<Float>): Contributor? {
    if (dailyLoad.size < 7) return null
    val chronic = fitnessFatigue(dailyLoad.dropLast(1)).last().ctl
    val yesterday = dailyLoad.last()
    val ratio = yesterday / max(chronic, MIN_CHRONIC_LOAD)
    val score = if (ratio <= HARD_DAY_RATIO) 100 else (100 - 25 * (ratio - HARD_DAY_RATIO)).toInt().coerceIn(30, 100)
    return Contributor(Factor.PreviousDayLoad, score, Detail.Load(yesterday.toInt(), chronic.toInt()))
}

/**
 * The weighted mean of [out] (0-100) and whether a cap lowered it: one very poor body signal ([RED_FLAG]) caps it
 * at [RED_FLAG_HEADROOM] above that signal, so good sleep and a quiet day can't average it away, and an [alert]
 * caps it at [ALERT_CAP].
 */
private fun Recovery.weightedScore(out: List<Contributor>, alert: Boolean): Pair<Int, Boolean> {
    val totalWeight = out.sumOf { it.factor.weight.toDouble() }
    var score = (out.sumOf { it.factor.weight.toDouble() * it.score } / totalWeight).toInt()
    val worst = out.filter { it.factor.physiological }.minOfOrNull { it.score }
    var capped = false
    if (worst != null && worst < RED_FLAG && score > worst + RED_FLAG_HEADROOM) {
        score = worst + RED_FLAG_HEADROOM
        capped = true
    }
    if (alert && score > ALERT_CAP) {
        score = ALERT_CAP
        capped = true
    }
    return score.coerceIn(0, 100) to capped
}

/** [softMinimum] of [out] (0-100): it replaces the weighted mean and the red-flag cap, and the Alert cap stays. */
private fun Recovery.softScore(out: List<Contributor>, alert: Boolean): Int {
    var softMin = softMinimum(out.map { it.factor.weight to it.score }).toInt()
    if (alert) softMin = min(softMin, ALERT_CAP)
    return softMin.coerceIn(0, 100)
}

/**
 * Zepp's soft minimum (analysis native/06 §4.8) with our importance weights: each part counts
 * w = baseWeight · e^(−(s/100)²), and the result is Σ w·s / Σ w. A low part weighs up to e ≈ 2.7× more than a
 * perfect one, so one poor signal pulls the score down smoothly instead of through a hard cap. [parts] are
 * (base weight, 0-100 score); 0 when empty.
 */
fun Recovery.softMinimum(parts: List<Pair<Float, Int>>): Double {
    var num = 0.0
    var den = 0.0
    for ((base, s) in parts) {
        val w = base * exp(-(s / 100.0).pow(2))
        num += w * s
        den += w
    }
    return if (den > 0) num / den else 0.0
}

/**
 * Sleep debt in minutes at the morning [day], from the last [SLEEP_DEBT_NIGHTS] nights of [nights] (oldest to
 * newest; missing nights skipped; naps count toward total sleep). Each night d = need − TST: a short night adds d
 * on top of the decayed debt, a long one repays its surplus, at most 2 h. Null with fewer than [SLEEP_DEBT_MIN_NIGHTS].
 * Deficits build near-linearly over 14 days without people noticing (Van Dongen 2003); about 1 h of debt took 4
 * nights to clear (Kitamura 2016).
 */
fun Recovery.sleepDebtMinutes(nights: List<Night>, day: Long, need: Int = SLEEP_NEED_MINUTES): Float? {
    val window = nights.filter { it.day <= day && day - it.day < SLEEP_DEBT_NIGHTS * DAY - DST_SLACK }.sortedBy { it.day }
    if (window.size < SLEEP_DEBT_MIN_NIGHTS) return null
    var debt = 0f
    for (n in window) {
        val d = need - (n.asleepMinutes + n.napMinutes).toFloat()
        debt = if (d >= 0) {
            debt * SLEEP_DEBT_DECAY + d
        } else {
            max(0f, debt * SLEEP_DEBT_DECAY - min(SLEEP_DEBT_CREDIT_FRACTION * -d, SLEEP_DEBT_MAX_CREDIT_MINUTES))
        }
    }
    return debt
}

/** Minutes of sleep debt under this read as none: a few minutes short isn't debt worth naming. */
private const val SLEEP_DEBT_NAMED_FROM = 3f

/**
 * Events per hour for [tonight], with the median of earlier nights once there are [SCORED_NIGHTS] of them. No
 * normal / mild / moderate / severe words: those are apnea severity bands (evidence-review.md, "Wording").
 */
fun Recovery.breathingDisturbances(tonight: Night?, history: List<Night>): BreathingDisturbances? {
    val odi = tonight?.odi ?: return null
    val past = history.filter { it.day < tonight.day }.sortedBy { it.day }.takeLast(BASELINE_NIGHTS).mapNotNull { it.odi }
    return BreathingDisturbances(odi, past.takeIf { it.size >= SCORED_NIGHTS }?.let { percentile(it, 0.5f) }, past.size)
}

/**
 * Our sleep score when the strap didn't give one: 0.7·duration + 0.3·continuity. No stage target: four-stage
 * agreement across six devices was kappa 0.21-0.53 (Schyvens et al. 2025), so stages are shown, not scored. And
 * low wake specificity inflates wearable efficiency, so continuity uses wake after sleep onset, not time in bed.
 * Without stages (no WASO), duration alone.
 */
fun Recovery.sleepScore(n: Night, need: Int = SLEEP_NEED_MINUTES): Int {
    val duration = durationScore(n.asleepMinutes, need)
    val continuity = n.wasoMinutes?.let { continuityScore(it) } ?: return Math.round(duration)
    return Math.round(SLEEP_DURATION_WEIGHT * duration + SLEEP_CONTINUITY_WEIGHT * continuity).coerceIn(0, 100)
}

/** 100·min(1, TST/need)²: 7 of 8 h ≈ 77, 6 h ≈ 56. */
fun Recovery.durationScore(asleepMinutes: Int, need: Int = SLEEP_NEED_MINUTES): Float =
    100f * (asleepMinutes.toFloat() / need).coerceIn(0f, 1f).pow(2)

/** clamp(100 − (WASO − 30)·(100/90), 0, 100). */
fun Recovery.continuityScore(wasoMinutes: Int): Float =
    (100f - (wasoMinutes - WASO_FREE_MINUTES) * (100f / WASO_SPAN_MINUTES)).coerceIn(0f, 100f)

/**
 * Wake after sleep onset from [stages] (start, end, stage): awake minutes between the first sleep stage's start
 * and the last one's end. Null without any sleep stage.
 */
fun Recovery.wasoMinutes(stages: List<Triple<Long, Long, Int>>): Int? {
    val sleep = stages.filter { it.third != SleepStage.AWAKE }
    if (sleep.isEmpty()) return null
    val onset = sleep.minOf { it.first }
    val wake = sleep.maxOf { it.second }
    val seconds = stages.filter { it.third == SleepStage.AWAKE }
        .sumOf { (max(it.first, onset) until min(it.second, wake)).let { r -> max(0L, r.last - r.first + 1) } }
    return (seconds / 60).toInt()
}
