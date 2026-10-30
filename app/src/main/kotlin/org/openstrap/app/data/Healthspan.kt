package org.openstrap.app.data

import org.openstrap.app.data.settings.UserProfile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * Healthspan age: how old your habits look, from data OpenStrap already has (docs/algorithms/healthspan.md). Each habit
 * is scored in years against the typical person of your age and sex, the fitness terms share a limit, the total is
 * shrunk by [S] and capped at ±[CAP] years. A wellness estimate, never a medical measurement; shown as a whole-year range.
 */
object Healthspan {
    /** PhenoAge's Gompertz age slope: ln hazard ratio → years. */
    const val G = 0.09165f
    /** Shrinkage, because the terms weren't adjusted for each other (design choice). */
    const val S = 0.6f
    /** Cap on the shown offset, and the fitness group's shared limit, years. */
    const val CAP = 5f
    const val FITNESS_LIMIT = 5f
    /** The shown range is at least this far either side of the estimate (an early estimate: [EARLY_SPREAD]). */
    const val MIN_SPREAD = 2f
    const val EARLY_SPREAD = 4f

    const val WINDOW_DAYS = 180
    const val VO2_WINDOW_DAYS = 90
    const val PACE_DAYS = 30
    const val EARLY_DAYS = 30
    const val MIN_AGE = 20
    const val MAX_AGE = 79

    /** Withholding thresholds over [WINDOW_DAYS] (§6); an early estimate scales them to the days available. */
    const val MIN_RHR_NIGHTS = 90
    const val MIN_STEP_DAYS = 90
    const val MIN_WEEKLY_SRIS = 8
    const val MIN_SLEEPS = 90
    /** A step day counts with at least this many minutes worn. */
    const val STEP_DAY_WORN_MINUTES = 10 * 60
    /** VO2max needs this many workout estimates in [VO2_WINDOW_DAYS]. */
    const val MIN_VO2_WORKOUTS = 3
    /** The strap's sport types for strength work ([Workouts.sportName]): 52 Strength training, 50 Core training. */
    val STRENGTH_SPORTS = setOf(50, 52)

    // ------------------------------------------------------------------------------------------------
    // The six terms (§3). Each returns the central value and its band, in years, before shrinkage.
    // ------------------------------------------------------------------------------------------------

    /** A term's value and band, already clamped to the term's limits. */
    data class Value(val years: Float, val low: Float, val high: Float) {
        companion object {
            val ZERO = Value(0f, 0f, 0f)
            /** [c] and the band [a]..[b] (either order), each clamped to [lo]..[hi]. */
            fun of(c: Float, a: Float, b: Float, lo: Float, hi: Float) =
                Value(c.coerceIn(lo, hi), min(a, b).coerceIn(lo, hi), max(a, b).coerceIn(lo, hi))
            /** ±50 % of the term after its limits, clamped back to them (steps, SRI, short sleep). */
            fun halfBand(raw: Float, lo: Float, hi: Float): Value {
                val c = raw.coerceIn(lo, hi)
                return of(c, c * 0.5f, c * 1.5f, lo, hi)
            }
        }
    }

    /** Linear between (x, y) points sorted by x, flat outside them. */
    internal fun interp(points: List<Pair<Float, Float>>, x: Float): Float {
        if (x <= points.first().first) return points.first().second
        if (x >= points.last().first) return points.last().second
        val (a, b) = points.zipWithNext().first { (a, b) -> x >= a.first && x <= b.first }
        return a.second + (x - a.first) / (b.first - a.first) * (b.second - a.second)
    }

    private fun years(hr: Float) = ln(hr) / G

    // ---- 3.1 Resting HR ----

    /** Office HR runs about 10 bpm above the night's (Böhm 2020); the sleeping value is raised by this before scoring. */
    const val SEATED_OFFSET_BPM = 10f
    const val RHR_FLOOR_BPM = 45f
    const val RHR_LIMIT = 4f
    /** Years per bpm: ln(1.09)/10/G, and both ends of Zhang 2016's CI (RR 1.07-1.12 per 10 bpm). */
    val RHR_SLOPE = ln(1.09f) / 10f / G
    val RHR_SLOPE_LOW = ln(1.07f) / 10f / G
    val RHR_SLOPE_HIGH = ln(1.12f) / 10f / G

    /** NHANES seated pulse P50 (Ostchega 2011) at the band midpoints 30, 50, 70; joined linearly, flat outside. */
    private val RHR_REF_MEN = listOf(30f to 69f, 50f to 68f, 70f to 67f)
    private val RHR_REF_WOMEN = listOf(30f to 74f, 50f to 71f, 70f to 70f)

    fun rhrReference(age: Float, sex: UserProfile.Sex): Float =
        interp(if (sex == UserProfile.Sex.Male) RHR_REF_MEN else RHR_REF_WOMEN, age)

    fun rhrTerm(sleepingRhr: Float, age: Float, sex: UserProfile.Sex): Value {
        val d = max(sleepingRhr + SEATED_OFFSET_BPM, RHR_FLOOR_BPM) - rhrReference(age, sex)
        return Value.of(RHR_SLOPE * d, RHR_SLOPE_LOW * d, RHR_SLOPE_HIGH * d, -RHR_LIMIT, RHR_LIMIT)
    }

    // ---- 3.2 Steps ----

    /** Paluch 2022 appendix Figure S2c, HR vs 3,500 steps, read from the figure (±0.02); as years via ln HR / G. */
    private val STEPS_UNDER_60 = listOf(3000f to 1.12f, 4000f to 0.89f, 5000f to 0.71f, 6000f to 0.56f, 7000f to 0.46f, 8000f to 0.40f, 9000f to 0.38f, 10000f to 0.38f)
        .map { it.first to years(it.second) }
    private val STEPS_60_PLUS = listOf(3000f to 1.12f, 4000f to 0.89f, 5000f to 0.71f, 6000f to 0.61f, 7000f to 0.56f, 8000f to 0.54f, 9000f to 0.52f, 10000f to 0.50f, 12000f to 0.47f, 14000f to 0.44f, 16000f to 0.41f)
        .map { it.first to years(it.second) }
    /** Paluch 2022 median of the cohort medians. */
    const val STEPS_REF_UNDER_60 = 7803f
    const val STEPS_REF_60_PLUS = 5649f
    const val STEPS_LIMIT_YOUNG = 1.5f
    const val STEPS_LIMIT_OLD = 2f
    /** The two curves are blended from 55 to 65 so the term doesn't jump at 60. */
    const val STEPS_BLEND_FROM = 55f
    const val STEPS_BLEND_YEARS = 10f

    private fun stepsBlend(age: Float) = ((age - STEPS_BLEND_FROM) / STEPS_BLEND_YEARS).coerceIn(0f, 1f)

    /** The typical daily steps the term is scored against, blended the same way. */
    fun stepsReference(age: Float): Float = stepsBlend(age).let { w -> (1 - w) * STEPS_REF_UNDER_60 + w * STEPS_REF_60_PLUS }

    fun stepsTerm(steps: Float, age: Float): Value {
        val young = (interp(STEPS_UNDER_60, steps) - interp(STEPS_UNDER_60, STEPS_REF_UNDER_60)).coerceIn(-STEPS_LIMIT_YOUNG, STEPS_LIMIT_YOUNG)
        val old = (interp(STEPS_60_PLUS, steps) - interp(STEPS_60_PLUS, STEPS_REF_60_PLUS)).coerceIn(-STEPS_LIMIT_OLD, STEPS_LIMIT_OLD)
        val w = stepsBlend(age)
        val limit = (1 - w) * STEPS_LIMIT_YOUNG + w * STEPS_LIMIT_OLD
        return Value.halfBand((1 - w) * young + w * old, -limit, limit)
    }

    // ---- 3.3 Sleep regularity ----

    /** Windred 2024 Table S2.4 (SRI + duration quintiles), re-based to Q3, at the quintile midpoints. */
    private val SRI_POINTS = listOf(68.2f to 2.71f, 75.1f to 0.68f, 80.9f to 0f, 85.2f to -0.43f, 89.4f to -0.57f)
    /** Windred 2024 UK Biobank median. */
    const val SRI_REFERENCE = 81f
    const val SRI_LIMIT_LOW = -0.57f
    const val SRI_LIMIT_HIGH = 2.71f

    fun sriTerm(sri: Float): Value = Value.halfBand(interp(SRI_POINTS, sri), SRI_LIMIT_LOW, SRI_LIMIT_HIGH)

    // ---- 3.4 Short sleep ----

    /** Yuan 2024 Supplementary Fig. 26, re-based to 6-6.9 h, at band midpoints (minutes asleep). Penalty only. */
    private val SLEEP_POINTS = listOf(270f to 9.17f, 330f to 2.12f, 390f to 0f)
    const val SLEEP_LIMIT = 4f
    /** Below this, time asleep starts adding years. */
    const val SLEEP_COUNTS_BELOW_MINUTES = 390

    fun sleepTerm(asleepMinutes: Float): Value = Value.halfBand(interp(SLEEP_POINTS, asleepMinutes), 0f, SLEEP_LIMIT)

    // ---- 3.5 Strength training ----

    /**
     * Momma 2022 Figure 4 (all-cause), RR by minutes a week, read from the figure (±0.01); 40 min is from the text.
     * 0 at 0 min and from 140 min. Credit only.
     */
    private val STRENGTH_RR = listOf(
        0f to 1f, 10f to 0.94f, 20f to 0.89f, 30f to 0.85f, 40f to 0.83f, 50f to 0.83f, 60f to 0.84f, 70f to 0.845f,
        80f to 0.85f, 90f to 0.865f, 100f to 0.89f, 110f to 0.92f, 120f to 0.95f, 130f to 0.97f, 140f to 1f,
    )
    /**
     * The curve's 95 % CI (the dashed lines), digitized from the same panel's pixels (±0.005): lower and upper RR at
     * each 10 min. The upper line is above 1.00 from about 118 min; it's capped at 1 before scoring.
     */
    private val STRENGTH_CI_LOW = listOf(
        0f to 1f, 10f to 0.926f, 20f to 0.868f, 30f to 0.820f, 40f to 0.794f, 50f to 0.799f, 60f to 0.805f, 70f to 0.810f,
        80f to 0.816f, 90f to 0.829f, 100f to 0.856f, 110f to 0.879f, 120f to 0.902f, 130f to 0.926f, 140f to 0.950f,
    )
    private val STRENGTH_CI_HIGH = listOf(
        0f to 1f, 10f to 0.953f, 20f to 0.914f, 30f to 0.883f, 40f to 0.864f, 50f to 0.870f, 60f to 0.875f, 70f to 0.881f,
        80f to 0.886f, 90f to 0.900f, 100f to 0.927f, 110f to 0.958f, 120f to 0.994f, 130f to 1.028f, 140f to 1.062f,
    )
    const val STRENGTH_LIMIT = -2.03f
    const val STRENGTH_MAX_MINUTES = 140f
    /** The coaching target: where Momma's curve is lowest. */
    const val STRENGTH_TARGET_MINUTES = 40

    fun strengthTerm(minutesPerWeek: Float): Value {
        if (minutesPerWeek <= 0f || minutesPerWeek >= STRENGTH_MAX_MINUTES) return Value.ZERO
        val rr = interp(STRENGTH_RR, minutesPerWeek)
        val lo = interp(STRENGTH_CI_LOW, minutesPerWeek)
        val hi = interp(STRENGTH_CI_HIGH, minutesPerWeek)
        return Value.of(years(rr), years(lo), years(min(hi, 1f)), STRENGTH_LIMIT, 0f)
    }

    // ---- 3.6 VO2max ----

    /** Half weight: wearable estimates agree with the lab only to about ±10 ml/kg/min. */
    const val VO2_WEIGHT = 0.5f
    const val VO2_LIMIT = 2f
    /** Years per MET: Kodama 2009, RR 0.87 (0.84-0.90) per MET. */
    val VO2_PER_MET = ln(0.87f) / G
    val VO2_PER_MET_LOW = ln(0.84f) / G
    val VO2_PER_MET_HIGH = ln(0.90f) / G

    /** FRIEND P50 for [sex], decade medians placed at 25, 35 … 85 and joined linearly, as [Vo2max.Friend.fitnessAge] does. */
    fun vo2Reference(age: Float, sex: UserProfile.Sex): Float {
        val table = if (sex == UserProfile.Sex.Male) Vo2max.Friend.MEN else Vo2max.Friend.WOMEN
        return interp(table.entries.map { (it.key.first + it.key.last + 1) / 2f to it.value[4] }, age)
    }

    fun vo2Term(vo2: Float, age: Float, sex: UserProfile.Sex): Value {
        val mets = (vo2 - vo2Reference(age, sex)) / Vo2max.ML_PER_MET
        return Value.of(VO2_WEIGHT * VO2_PER_MET * mets, VO2_WEIGHT * VO2_PER_MET_LOW * mets, VO2_WEIGHT * VO2_PER_MET_HIGH * mets, -VO2_LIMIT, VO2_LIMIT)
    }

    // ------------------------------------------------------------------------------------------------
    // Combining them (§1, §3.7, §5)
    // ------------------------------------------------------------------------------------------------

    enum class Habit(val label: String, val fitness: Boolean) {
        Strength(tr(R.string.common_strength_training), false),
        Vo2max(tr(R.string.healthspan_fitness_vo_max), true),
        Steps(tr(R.string.common_steps), true),
        Regularity(tr(R.string.common_sleep_regularity), false),
        RestingHr(tr(R.string.common_resting_heart_rate_2), true),
        TimeAsleep(tr(R.string.common_time_asleep), false),
    }

    /** One window's averaged inputs; null where there isn't enough data for that habit, which scores as typical. */
    data class Habits(
        val sleepingRhr: Float? = null,
        val steps: Float? = null,
        val sri: Float? = null,
        val asleepMinutes: Float? = null,
        val strengthMinutesPerWeek: Float = 0f,
        val vo2max: Float? = null,
    )

    /** Every habit's term before shrinkage, and the fitness group after its shared limit. */
    data class Terms(val each: Map<Habit, Value>, val fitness: Value, val fitnessCapped: Boolean) {
        /** Σ of the fitness group and the other terms, before shrinkage. */
        val sum: Value get() {
            val rest = each.filterKeys { !it.fitness }.values
            return Value(fitness.years + rest.sumOf { it.years.toDouble() }.toFloat(), fitness.low + rest.sumOf { it.low.toDouble() }.toFloat(), fitness.high + rest.sumOf { it.high.toDouble() }.toFloat())
        }
        /** The shown offset from age, years: shrunk and capped. */
        val offset: Float get() = (S * sum.years).coerceIn(-CAP, CAP)
    }

    fun terms(h: Habits, age: Float, sex: UserProfile.Sex): Terms {
        val each = linkedMapOf<Habit, Value>()
        h.sleepingRhr?.let { each[Habit.RestingHr] = rhrTerm(it, age, sex) }
        h.steps?.let { each[Habit.Steps] = stepsTerm(it, age) }
        h.vo2max?.let { each[Habit.Vo2max] = vo2Term(it, age, sex) }
        h.sri?.let { each[Habit.Regularity] = sriTerm(it) }
        h.asleepMinutes?.let { each[Habit.TimeAsleep] = sleepTerm(it) }
        each[Habit.Strength] = strengthTerm(h.strengthMinutesPerWeek)
        val fit = each.filterKeys { it.fitness }.values
        val raw = fit.sumOf { it.years.toDouble() }.toFloat()
        val fitness = Value(
            raw.coerceIn(-FITNESS_LIMIT, FITNESS_LIMIT),
            fit.sumOf { it.low.toDouble() }.toFloat().coerceIn(-FITNESS_LIMIT, FITNESS_LIMIT),
            fit.sumOf { it.high.toDouble() }.toFloat().coerceIn(-FITNESS_LIMIT, FITNESS_LIMIT),
        )
        return Terms(each, fitness, abs(raw) > FITNESS_LIMIT)
    }

    /** The estimate in years and the whole-year range shown, widened to at least ±[spread] around it. */
    fun range(age: Int, t: Terms, spread: Float): Triple<Float, Int, Int> {
        val estimate = age + t.offset
        val s = t.sum
        val low = min(age + (S * s.low).coerceIn(-CAP, CAP), estimate - spread)
        val high = max(age + (S * s.high).coerceIn(-CAP, CAP), estimate + spread)
        return Triple(estimate, low.roundToInt(), high.roundToInt())
    }

    // ------------------------------------------------------------------------------------------------
    // Pace of aging (§4)
    // ------------------------------------------------------------------------------------------------

    const val PACE_FLOOR_YEARS = 0.25f
    /** WHOOP's five-month projection. */
    const val PACE_PROJECTION_YEARS = 5f / 12f
    const val PACE_MIN_VALID_DAYS = 21
    const val PACE_MIN_WEEKLY_SRIS = 2
    const val PACE_MIN_BLOCKS = 3

    /**
     * Pace from the last 30 days' offset, the 180-day one, and the offsets of the earlier non-overlapping 30-day blocks
     * (for the noise gate); null when fewer than [PACE_MIN_BLOCKS] earlier blocks. Rounded to the nearest 0.5x.
     */
    fun pace(recent: Float, whole: Float, earlierBlocks: List<Float>): Float? {
        if (earlierBlocks.size < PACE_MIN_BLOCKS) return null
        val mean = earlierBlocks.average()
        val sd = sqrt(earlierBlocks.sumOf { (it - mean) * (it - mean) } / (earlierBlocks.size - 1)).toFloat()
        val delta = recent - whole
        if (abs(delta) < max(2 * sd, PACE_FLOOR_YEARS)) return 1f
        val p = (1 + delta / PACE_PROJECTION_YEARS).coerceIn(-1f, 3f)
        return (p * 2).roundToInt() / 2f
    }

    // ------------------------------------------------------------------------------------------------
    // From daily data to the weekly result (§2, §6)
    // ------------------------------------------------------------------------------------------------

    /**
     * Daily data over the [WINDOW_DAYS] before [asOf], keyed by local midnight (epoch seconds). Only valid values belong
     * here: nights with a usable resting HR, step days worn ≥ [STEP_DAY_WORN_MINUTES], weekly SRIs that weren't withheld
     * (keyed by the week's last morning), main sleeps, and per-workout VO2max estimates from the strap.
     */
    data class Daily(
        val asOf: Long,
        val restingHr: Map<Long, Float> = emptyMap(),
        val steps: Map<Long, Int> = emptyMap(),
        val weeklySri: Map<Long, Float> = emptyMap(),
        val asleepMinutes: Map<Long, Int> = emptyMap(),
        /** Strength sessions: start → minutes. */
        val strength: Map<Long, Float> = emptyMap(),
        val vo2max: Map<Long, Float> = emptyMap(),
        /** The first day with any data, for an early estimate. */
        val firstDay: Long? = null,
    )

    /**
     * Why there is no estimate, in the order checked. [profile]: the user can fix it in their profile. [learning]: there
     * isn't enough data yet, so the card starts at the user's own age.
     */
    enum class Withheld(val reason: String, val profile: Boolean = false, val learning: Boolean = false) {
        NoAge(tr(R.string.healthspan_add_your_birth_year), profile = true),
        Age(tr(R.string.healthspan_habit_age_is_for)),
        Sex(tr(R.string.healthspan_add_your_sex_in), profile = true),
        Medication(tr(R.string.common_not_shown_while_taking)),
        Learning(tr(R.string.healthspan_needs_days_of_wear), learning = true),
        RestingHr(tr(R.string.healthspan_needs_more_nights_of), learning = true),
        Steps(tr(R.string.healthspan_needs_more_days_worn), learning = true),
        Regularity(tr(R.string.healthspan_needs_more_weeks_of), learning = true),
        Sleep(tr(R.string.healthspan_needs_more_nights_of_2), learning = true),
    }

    /**
     * What the card and screen show: the estimate, or why it's withheld together with what the starting state needs:
     * the user's [age] (the estimate starts there), what's [missing] from the profile, and the Sunday a first estimate
     * is due ([firstEstimate], local midnight) while it's learning.
     */
    data class Outcome(
        val result: Result?,
        val withheld: Withheld?,
        val age: Int? = null,
        val missing: List<String> = emptyList(),
        val firstEstimate: Long? = null,
        /** Last week's estimate as it was shown ([lastWeek]), for "1 year younger than last week"; null until there's one. */
        val lastWeek: Float? = null,
    )

    /**
     * Last week's estimate for the Sunday [asOf]: the latest of the stored [weeks] (Sunday to estimate) from the 8 days
     * before it, so a clock change between the two Sundays doesn't lose it. Null without one.
     */
    fun lastWeek(weeks: List<Pair<Long, Float>>, asOf: Long): Float? =
        weeks.filter { it.first in asOf - 8 * DAY until asOf }.maxByOrNull { it.first }?.second

    /** One habit as the screen shows it: its shown years (after shrinkage) and the numbers behind it. */
    data class Term(val habit: Habit, val years: Float, val value: Float?, val reference: Float?)

    data class Result(
        val age: Int,
        val estimate: Float,
        val low: Int,
        val high: Int,
        /** Withheld until there's enough history; 1 is in step with the calendar. */
        val pace: Float?,
        /** Shown years per habit, largest effect first. */
        val terms: List<Term>,
        val fitnessCapped: Boolean,
        /** Less than [WINDOW_DAYS] of history: shown with at least ±[EARLY_SPREAD] y. */
        val early: Boolean,
        /** Local midnight of the Sunday this estimate is for. */
        val asOf: Long,
        val sex: UserProfile.Sex,
    ) {
        /** The habit taking off the most years, else the one adding the most; null when all are typical. */
        val lever: Term? get() = terms.filter { it.years < -0.05f }.minByOrNull { it.years } ?: terms.filter { it.years > 0.05f }.maxByOrNull { it.years }
    }

    private const val DAY = 86_400L

    /** Averages one window's daily data into [Habits]; [days] is the window length for the strength rate. */
    internal fun habits(d: Daily, from: Long, to: Long, days: Int, vo2: Float?): Habits {
        fun <V> Map<Long, V>.inWindow() = filterKeys { it in from until to }.values
        return Habits(
            sleepingRhr = Recovery.percentile(d.restingHr.inWindow().toList(), 0.5f),
            steps = d.steps.inWindow().takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            sri = d.weeklySri.inWindow().takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            asleepMinutes = d.asleepMinutes.inWindow().takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            strengthMinutesPerWeek = d.strength.inWindow().sum() / (days / 7f),
            vo2max = vo2,
        )
    }

    /** The strap's VO2max over the [VO2_WINDOW_DAYS] before [asOf]: median of ≥ [MIN_VO2_WORKOUTS] workouts. */
    internal fun vo2(d: Daily): Float? =
        d.vo2max.filterKeys { it in d.asOf - VO2_WINDOW_DAYS * DAY until d.asOf }.values.toList()
            .takeIf { it.size >= MIN_VO2_WORKOUTS }?.let { Recovery.percentile(it, 0.5f) }

    /**
     * The weekly estimate as of [Daily.asOf] (local midnight of a Sunday) for [profile] at [age]. Uses the full window
     * once there are [WINDOW_DAYS] of history; from [EARLY_DAYS] it gives an early estimate on the days there are, with
     * the thresholds scaled to them.
     */
    fun estimate(profile: UserProfile, age: Int?, d: Daily): Pair<Result?, Withheld?> {
        if (age == null) return null to Withheld.NoAge
        if (age !in MIN_AGE..MAX_AGE) return null to Withheld.Age
        val sex = profile.sex ?: return null to Withheld.Sex
        if (profile.betaBlocker || profile.pregnant) return null to Withheld.Medication
        val windowFrom = d.asOf - WINDOW_DAYS * DAY
        val span = d.firstDay?.let { ((d.asOf - max(it, windowFrom) + DAY / 2) / DAY).toInt() } ?: 0
        if (span < EARLY_DAYS) return null to Withheld.Learning
        val early = span < WINDOW_DAYS
        val days = min(span, WINDOW_DAYS)
        val from = d.asOf - days * DAY
        fun need(full: Int) = ceil(full * days / WINDOW_DAYS.toFloat()).toInt()
        fun count(m: Map<Long, *>) = m.keys.count { it in from until d.asOf }
        if (count(d.restingHr) < need(MIN_RHR_NIGHTS)) return null to Withheld.RestingHr
        if (count(d.steps) < need(MIN_STEP_DAYS)) return null to Withheld.Steps
        if (count(d.weeklySri) < max(1, need(MIN_WEEKLY_SRIS))) return null to Withheld.Regularity
        if (count(d.asleepMinutes) < need(MIN_SLEEPS)) return null to Withheld.Sleep

        val vo2 = vo2(d)
        val ageF = age.toFloat()
        val h = habits(d, from, d.asOf, days, vo2)
        val t = terms(h, ageF, sex)
        val (estimate, low, high) = range(age, t, if (early) EARLY_SPREAD else MIN_SPREAD)

        // Pace: the last 30 days against the whole window, gated by how much earlier 30-day blocks varied. VO2max is
        // held at the window's value in every block, so a block without 3 workouts doesn't read as a change.
        fun blockOffset(k: Int): Float? {
            val to = d.asOf - k * PACE_DAYS * DAY
            val f = to - PACE_DAYS * DAY
            if (f < from) return null
            if (d.steps.keys.count { it in f until to } < PACE_MIN_VALID_DAYS) return null
            if (d.weeklySri.keys.count { it in f until to } < PACE_MIN_WEEKLY_SRIS) return null
            return terms(habits(d, f, to, PACE_DAYS, vo2), ageF, sex).offset
        }
        val recent = blockOffset(0)
        val pace = recent?.let { r -> pace(r, t.offset, (1 until WINDOW_DAYS / PACE_DAYS).mapNotNull(::blockOffset)) }

        // Shown years per habit: shrunk, and the fitness terms scaled together when their shared limit binds.
        val fitRaw = t.each.filterKeys { it.fitness }.values.sumOf { it.years.toDouble() }.toFloat()
        val fitScale = if (t.fitnessCapped && fitRaw != 0f) t.fitness.years / fitRaw else 1f
        val values = mapOf(
            Habit.RestingHr to h.sleepingRhr, Habit.Steps to h.steps, Habit.Regularity to h.sri,
            Habit.TimeAsleep to h.asleepMinutes, Habit.Strength to h.strengthMinutesPerWeek, Habit.Vo2max to vo2,
        )
        val refs = mapOf(
            Habit.RestingHr to rhrReference(ageF, sex) - SEATED_OFFSET_BPM, Habit.Steps to stepsReference(ageF),
            Habit.Regularity to SRI_REFERENCE, Habit.TimeAsleep to SLEEP_COUNTS_BELOW_MINUTES.toFloat(), Habit.Strength to 0f,
            Habit.Vo2max to vo2Reference(ageF, sex),
        )
        val shown = Habit.entries.map { habit ->
            val c = t.each[habit]?.years ?: 0f
            Term(habit, S * c * (if (habit.fitness) fitScale else 1f), values[habit], refs[habit])
        }.sortedBy { if (it.years == 0f) Float.MAX_VALUE else -abs(it.years) }
        return Result(age, estimate, low, high, pace, shown, t.fitnessCapped, early, d.asOf, sex) to null
    }
}
