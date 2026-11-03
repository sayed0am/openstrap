package org.openstrap.app.data

import org.openstrap.app.data.db.SleepStage
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * OpenStrap's own recovery model: a morning **Readiness** score and a minute-by-minute **Charge** (body
 * battery). Pure Kotlin, no Android types, so every rule is unit-tested. docs/algorithms/recovery.md
 * explains each rule and the research behind it; the constants below are referenced there by name.
 *
 * This object holds the model's inputs, results and constants, which callers name as `Recovery.X`. The rules
 * themselves are extensions on it, one file per part: [readiness] in ReadinessModel.kt, [charge] in ChargeModel.kt,
 * [dailyLoad] in LoadModel.kt and [maxHeartRate] in MaxHrModel.kt.
 *
 * Zepp's BioCharge runs in a closed native library and on the strap, so nothing here is derived from it.
 */
object Recovery {

    // ------------------------------------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------------------------------------

    /** One main sleep, reduced to the nightly values readiness needs. Null where the strap had no data. */
    data class Night(
        /** Local midnight of the day the sleep belongs to (the morning it ended). */
        val day: Long,
        val start: Long,
        val end: Long,
        val asleepMinutes: Int,
        val awakeMinutes: Int? = null,
        val deepMinutes: Int? = null,
        val remMinutes: Int? = null,
        /** The strap's own sleep score, when it computed one. */
        val strapScore: Int? = null,
        /** Mean RMSSD during the sleep, ms. */
        val hrv: Float? = null,
        /** Lowest 30-minute average heart rate during the sleep, bpm (`rhr_n`). */
        val restingHr: Float? = null,
        /** Mean of the per-minute heart rate during the sleep, bpm (`rhr_mean_sleep`). */
        val rhrMeanSleep: Float? = null,
        /** 10th percentile of the per-minute heart rate during the sleep, bpm (`rhr_p10`). */
        val rhrP10: Float? = null,
        /** RMSSD samples behind [hrv], after gating to [HRV_MIN_MS]..[HRV_MAX_MS]. */
        val hrvSamples: Int = 0,
        /** Minutes of heart rate in [HR_MIN_BPM]..[HR_MAX_BPM] during the sleep. */
        val hrMinutes: Int = 0,
        /** When the lowest 10-minute heart rate happened (epoch seconds). */
        val nadirTime: Long? = null,
        val skinTemperature: Float? = null,
        val breathRate: Float? = null,
        /** Wake after sleep onset: awake minutes between the first and the last sleep stage; null without stages. */
        val wasoMinutes: Int? = null,
        /** Minutes asleep in naps on this day, counted toward total sleep for the debt. */
        val napMinutes: Int = 0,
        /** Mean blood oxygen during the sleep, %. */
        val spo2: Float? = null,
        /** Oxygen-desaturation events per hour asleep, from the strap's nightly sleep-apnea summary. */
        val odi: Float? = null,
        /** The strap's own nightly HRV (ms) and resting HR (bpm) for this night, when it reported them. */
        val strapHrv: Float? = null,
        val strapRestingHr: Float? = null,
        /**
         * The strap's own day for this night's session ([SleepHistory.strapDay]), which its nightly rows are keyed by;
         * null when another, longer night from the same strap day has them.
         */
        val strapDay: LocalDate? = null,
    ) {
        /**
         * The validity rule for OpenStrap's own HRV and resting HR: a main sleep of at least 3 h, ≥ [MIN_HRV_SAMPLES]
         * gated RMSSD samples and ≥ [MIN_HR_MINUTES] minutes of gated heart rate. Invalid nights never enter a baseline.
         */
        val valid: Boolean
            get() = end - start >= MIN_VALID_SLEEP_MINUTES * 60L && hrvSamples >= MIN_HRV_SAMPLES && hrMinutes >= MIN_HR_MINUTES

        /** `hrv_n` = ln(mean gated RMSSD) on a valid night, else null. */
        val hrvLn: Float? get() = hrv?.takeIf { valid && it > 0 }?.let { ln(it) }
        val validRestingHr: Float? get() = restingHr?.takeIf { valid }
    }

    /** One minute of the day as the charge model sees it. */
    data class Minute(
        val t: Long,
        val hr: Int?,
        val steps: Int,
        /** The strap's stress value (0-100) if one was measured within the last few minutes. */
        val stress: Int?,
        /** A [SleepStage] while asleep (including AWAKE inside a sleep), null outside sleep. */
        val sleepStage: Int?,
        /** Inside a stored workout's active time, or within [STRESS_AFTER_WORKOUT_SECONDS] after one; null otherwise. */
        val workout: WorkoutPhase? = null,
        /** The strap marked this minute as not worn ([offWrist]); a minute with heart rate is worn whatever this says. */
        val offWrist: Boolean = false,
        /**
         * Whether the strap said it was worn at this minute, from its live wear pushes (true on body, false off-body);
         * null where no recorded state covers the minute (e.g. while disconnected). See [notWorn].
         */
        val worn: Boolean? = null,
    )

    /**
     * Whether the activity record's mode byte ([org.openstrap.app.data.db.ActivityMinute.kind]) says the strap was off
     * the wrist: low nibble 3 or 6, or 0x7E, the values Zepp's BioCharge reads as no wear (native 03 §2.2). On a real
     * database 974 of 989 kind-3 minutes and all 46 kind-6 minutes had no heart rate.
     */
    fun offWrist(kind: Int): Boolean = kind == 0x7E || (kind and 0x0F).let { it == 3 || it == 6 }

    /** Where a minute sits against the stored workouts, for the stress exclusion ([stressExcluded]). */
    enum class WorkoutPhase { Active, After }

    // ------------------------------------------------------------------------------------------------
    // Baselines
    // ------------------------------------------------------------------------------------------------

    /**
     * A robust personal baseline: [centre] is the median and [scale] is 1.4826·MAD, floored, so one bad stretch
     * (illness, alcohol, an artefact night) can't drag "normal" with it the way a mean and SD do.
     */
    data class Baseline(val centre: Float, val scale: Float, val n: Int) {
        fun z(x: Float) = (x - centre) / scale
    }

    /** Baseline from [values] with its scale floored at [floor]; null with fewer than [minN] values. */
    fun baseline(values: List<Float>, floor: Float, minN: Int = SCORED_NIGHTS): Baseline? {
        if (values.size < minN || values.isEmpty()) return null
        val centre = percentile(values, 0.5f)!!
        val mad = percentile(values.map { abs(it - centre) }, 0.5f)!!
        return Baseline(centre, max(MAD_SCALE * mad, floor), values.size)
    }

    /** 1.4826·MAD estimates the SD for normally distributed data. */
    const val MAD_SCALE = 1.4826f

    /**
     * Where Charge starts counting the strap's stress score as stress. [calmLevel] is null (and [value] is
     * [STRESS_FROM]) until [days] reaches [MIN_STRESS_DAYS].
     */
    data class StressThreshold(val value: Int, val calmLevel: Int?, val days: Int)

    /** A minute with fewer steps than this counts as still for the calm stress level. */
    const val CALM_STEPS = 10

    /**
     * How long after a workout stress readings stay excluded: 20 minutes, the ≈20-minute half-life of Charge's
     * post-exercise debt ([EPOC_DECAY], 0.966 a minute). By then half the debt is gone and the heart rate is close
     * to where it settles.
     */
    const val STRESS_AFTER_WORKOUT_SECONDS = 20 * 60L

    const val STRESS_BASELINE_DAYS = 14
    const val MIN_STRESS_DAYS = 3
    const val MIN_STRESS_READINGS = 60
    const val STRESS_MARGIN = 10
    const val STRESS_FROM_MIN = 50
    const val STRESS_FROM_MAX = 70

    /**
     * Phases by valid nights in the baseline (they replace MIN_BASELINE_NIGHTS = 4 and CALIBRATED_NIGHTS = 14):
     * below [SCORED_NIGHTS] HRV and resting HR are withheld and the strap's estimate is shown instead; up to
     * [SETTLED_NIGHTS] the score is labelled "learning"; from [CALIBRATED_NIGHTS] it's labelled "calibrated".
     */
    const val SCORED_NIGHTS = 7
    const val SETTLED_NIGHTS = 14
    const val CALIBRATED_NIGHTS = 28
    /** Valid nights of history a baseline looks back over (published windows run 28-60). */
    const val BASELINE_NIGHTS = 60

    /** The max heart rates a tested value may be: anything outside is taken as a typo and left out. */
    val TESTED_MAX_RANGE = 120..230

    enum class Phase { Withheld, Learning, Scored, Calibrated }

    fun phase(validNights: Int): Phase = when {
        validNights < SCORED_NIGHTS -> Phase.Withheld
        validNights < SETTLED_NIGHTS -> Phase.Learning
        validNights < CALIBRATED_NIGHTS -> Phase.Scored
        else -> Phase.Calibrated
    }

    /** The validity rule's thresholds (see [Night.valid]). */
    const val MIN_VALID_SLEEP_MINUTES = 3 * 60
    const val MIN_HRV_SAMPLES = 6
    const val MIN_HR_MINUTES = 120
    /** The lowest 30-minute window needs this many minutes of heart rate in it. */
    const val RHR_WINDOW_MIN_MINUTES = 20

    // ------------------------------------------------------------------------------------------------
    // Readiness (the rules are in ReadinessModel.kt)
    // ------------------------------------------------------------------------------------------------

    /**
     * Readiness contributors and their weights (a design choice, evidence-review.md): autonomic 52 %, sleep 28 %, other
     * body signals 13 %, load 4 %, renormalised over the contributors present. Breathing disturbances (a stable trait,
     * so only a fixed offset inside Readiness) moved to the Breathing card ([breathingDisturbances]); training load
     * balance (ACWR) moved to the Load screen ([acwr]).
     */
    enum class Factor(val label: String, val weight: Float, val physiological: Boolean = false) {
        HrvBalance("HRV balance", 0.30f, physiological = true),
        RestingHr("Resting heart rate", 0.22f, physiological = true),
        Sleep("Last night's sleep", 0.16f),
        SleepBalance("Sleep balance", 0.12f),
        Temperature("Body temperature", 0.06f, physiological = true),
        Breathing("Breathing rate", 0.04f, physiological = true),
        PreviousDayLoad("Previous day's load", 0.04f),
        BloodOxygen("Blood oxygen", 0.03f, physiological = true),
        RecoveryIndex("Recovery index", 0.03f),
    }

    data class Contributor(val factor: Factor, val score: Int, val detail: Detail)

    /** The numbers behind a contributor's line. Presentation writes the line (`RecoveryCopy.detail`). */
    sealed interface Detail {
        /** Tonight's HRV and how far it sits from your usual, in percent. */
        data class Hrv(val ms: Int, val percentVsUsual: Int) : Detail

        /** Tonight's resting heart rate and its change against your usual. */
        data class RestingHr(val bpm: Int, val vsUsual: Float) : Detail

        data class Asleep(val minutes: Int) : Detail

        /** Sleep debt over the last 14 nights, 0 when there is none worth naming. */
        data class SleepDebt(val minutes: Int) : Detail

        /** How long before waking the night's lowest heart rate came. */
        data class LowestHeartRate(val minutesBeforeWaking: Int) : Detail

        /** Skin temperature against your usual, in °C. */
        data class Temperature(val vsUsual: Float) : Detail

        /** Breaths a minute against your usual. */
        data class Breathing(val vsUsual: Float) : Detail

        data class BloodOxygen(val percent: Float, val vsUsual: Float) : Detail

        data class Load(val yesterday: Int, val usual: Int) : Detail
    }

    /** Where the 7-night HRV sits against the normal range, centre ± [HRV_RANGE_SCALE]·scale (the trials' band). */
    enum class HrvRange { Below, Within, Above }

    data class Readiness(
        /** 0-100, or null when there's no sleep, or no HRV or resting HR to score ([withheld]). */
        val score: Int?,
        val contributors: List<Contributor>,
        /** Valid nights in the HRV and resting HR baselines (the larger of the two); sets [phase]. */
        val baselineNights: Int,
        /** Body signals off baseline in the Alert state ([BodySignals]); worth taking it easy. */
        val strainAlert: Boolean,
        /** The signals that moved, in plain words, for the alert. */
        val alertSignals: List<String>,
        /** True when the weighted score was capped by one very low physiological contributor. */
        val capped: Boolean,
        val phase: Phase = phase(baselineNights),
        /**
         * There was a night but neither an HRV nor a resting HR contributor, so no score: sleep alone isn't readiness
         * (on 2026-10-16 it gave 52 against the strap's 91). The sleep contributor and the strap's estimate show instead.
         */
        val withheld: Boolean = false,
        val hrvRange: HrvRange? = null,
        /** Valid nights behind the 7-night HRV; "thin" below [HRV_WEEK_FULL_NIGHTS]. */
        val hrvWeekNights: Int = 0,
        /** The strap's values, shown (and used by alerts) because tonight's own ones failed the validity rule. */
        val fallbackHrv: Float? = null,
        val fallbackRestingHr: Float? = null,
        /** Data-quality notes: own and strap values disagree by more than [HRV_QA_FRACTION] or [RHR_QA_BPM]. */
        val qualityFlags: List<String> = emptyList(),
        /** Tonight in the body-signal state machine: Normal, Watch (in-app) or Alert (push, capped). */
        val bodySignals: BodySignals.Day? = null,
        /**
         * The same contributors combined by [softMinimum] instead of the weighted mean and red-flag cap, with the Alert
         * cap still applied. For the backtest comparison only ([SOFT_MIN_FUSION]); the app never shows it.
         */
        val softMinScore: Int? = null,
    ) {
        val calibrating get() = phase == Phase.Withheld || phase == Phase.Learning
        val hrvWeekThin get() = hrvWeekNights in HRV_WEEK_MIN_NIGHTS until HRV_WEEK_FULL_NIGHTS
        /** Show the strap's own score and parts as "Strap's estimate": before our baselines exist, or when withheld. */
        val showStrapEstimate get() = phase == Phase.Withheld || withheld
    }

    /** Smallest worthwhile change, as a fraction of the baseline scale (Buchheit; Plews uses 0.5 for HRV). */
    const val SWC_SD = 0.5f

    /** Floors so a very steady baseline doesn't turn tiny changes into huge z-scores (RHR's sits above device error). */
    const val HRV_LN_SD_FLOOR = 0.05f
    const val RHR_SD_FLOOR = 2f
    const val BREATH_SD_FLOOR = 0.5f
    const val TEMP_SD_FLOOR = 0.15f
    const val SPO2_SD_FLOOR = 0.5f

    /** Tonight's and the 7-night HRV in the score input; nightly wearable HRV error is larger than the SWC. */
    const val HRV_NIGHT_WEIGHT = 0.4f
    const val HRV_WEEK_WEIGHT = 0.6f
    /** The 7-night mean needs this many valid nights, and is "thin" below the second. */
    const val HRV_WEEK_MIN_NIGHTS = 3
    const val HRV_WEEK_FULL_NIGHTS = 5
    const val HRV_RANGE_SCALE = 0.5f
    /** Own and strap values further apart than this raise a data-quality flag. */
    const val HRV_QA_FRACTION = 0.15f
    const val RHR_QA_BPM = 3f

    /**
     * [Readiness.qualityFlags] for [tonight]: its own HRV and resting HR against the strap's, where both exist. Kept
     * here with the other words the model writes, which the copy punctuation check reads.
     */
    internal fun qualityFlags(tonight: Night): List<String> = buildList {
        val rhr = tonight.validRestingHr
        val own = tonight.hrv?.takeIf { tonight.valid }
        val strap = tonight.strapHrv
        if (own != null && strap != null && strap > 0 && abs(own - strap) / strap > HRV_QA_FRACTION) {
            add("HRV: ours ${own.toInt()} ms, the strap's ${strap.toInt()} ms")
        }
        val strapRhr = tonight.strapRestingHr
        if (rhr != null && strapRhr != null && abs(rhr - strapRhr) > RHR_QA_BPM) add("Resting HR: ours ${rhr.toInt()} bpm, the strap's ${strapRhr.toInt()} bpm")
    }
    /** Default sleep need: 480 min for ages 18-64, [SLEEP_NEED_65_MINUTES] from 65 (NSF 7-9 h and 7-8 h; AASM ≥ 7 h). */
    const val SLEEP_NEED_MINUTES = 8 * 60
    const val SLEEP_NEED_65_MINUTES = 450
    /** A user's own need can be set within this range (individual optimal sleep ran 7.3-9.3 h, Kitamura 2016). */
    const val SLEEP_NEED_MIN = 420
    const val SLEEP_NEED_MAX = 540

    /**
     * Off: the app scores with the weighted mean and the red-flag cap, and [Readiness.softMinScore] is computed beside
     * it for the backtest. On: the soft minimum becomes the score. Decided at the calibration review
     * (readiness-calibration.md, "Soft-minimum fusion").
     */
    const val SOFT_MIN_FUSION = false

    /** Sleep debt halves over 7 nights: λ = 0.5^(1/7) ≈ 0.906, so a 14-night build-up still counts. */
    const val SLEEP_DEBT_HALF_LIFE_NIGHTS = 7.0
    val SLEEP_DEBT_DECAY = 0.5.pow(1 / SLEEP_DEBT_HALF_LIFE_NIGHTS).toFloat()
    /** A long night repays half its surplus, at most an hour. */
    const val SLEEP_DEBT_CREDIT_FRACTION = 0.5f
    const val SLEEP_DEBT_MAX_CREDIT_MINUTES = 60f
    const val SLEEP_DEBT_NIGHTS = 14
    const val SLEEP_DEBT_MIN_NIGHTS = 7

    internal const val DAY = 86_400L
    /** Local midnights a DST change apart are an hour off a whole number of days. */
    internal const val DST_SLACK = 2 * 3600L
    /** Readiness points lost per hour of average nightly shortfall (1 h short ≈ 75, 2 h ≈ 50). */
    const val SLEEP_DEBT_POINTS_PER_HOUR = 25

    const val RED_FLAG = 40
    const val RED_FLAG_HEADROOM = 30
    /** Where OpenStrap's "Take it steady" (Fair) band starts, which `Narrative.ratingFor` uses. */
    const val FAIR_FROM = 55
    /** One below the Fair band, so an Alert day always rates in the lowest band ("Take it easy"). */
    const val ALERT_CAP = FAIR_FROM - 1
    internal const val MIN_CHRONIC_LOAD = 20f
    /** Readiness points lost per % of nightly SpO2 below baseline, beyond a 1 % dead band (−2 % ≈ 75, −3 % ≈ 50). */
    const val SPO2_POINTS_PER_PERCENT = 25
    const val SPO2_DEAD_BAND = 1f

    /** The Breathing card: tonight's oxygen-desaturation events per hour against the user's own nights. */
    data class BreathingDisturbances(val perHour: Float, val usual: Float?, val nights: Int)

    const val ACWR_ACUTE_DAYS = 7
    const val ACWR_CHRONIC_DAYS = 28
    const val ACWR_MIN_CHRONIC_DAYS = 14

    const val SLEEP_DURATION_WEIGHT = 0.7f
    const val SLEEP_CONTINUITY_WEIGHT = 0.3f
    /** WASO up to this is free; continuity then falls to 0 over the next [WASO_SPAN_MINUTES]. */
    const val WASO_FREE_MINUTES = 30f
    const val WASO_SPAN_MINUTES = 90f

    // ------------------------------------------------------------------------------------------------
    // Training load (the rules are in LoadModel.kt, and max heart rate's in MaxHrModel.kt)
    // ------------------------------------------------------------------------------------------------

    /** A day's load, TRIMP, split as Zepp's Exertion splits it: workout minutes and everyday minutes. */
    data class DailyLoad(val workout: Float, val everyday: Float) {
        val total: Float get() = workout + everyday
    }

    /**
     * [inWorkout] for many minutes against the same [ranges]: sorted by start once, then a binary search per minute,
     * so a 90-day load doesn't scan every workout for every minute. Same answer as [inWorkout].
     */
    class MinuteRanges(ranges: List<LongRange>) {
        private val sorted = ranges.sortedBy { it.first }
        private val firsts = LongArray(sorted.size) { sorted[it].first }
        /** The latest end among the first k + 1 ranges by start. */
        private val reach = LongArray(sorted.size).also { a ->
            var m = Long.MIN_VALUE
            for (k in sorted.indices) { m = max(m, sorted[k].last); a[k] = m }
        }

        operator fun invoke(t: Long): Boolean {
            // Ranges starting before the minute ends are sorted[0 until lo]; one of them must end at or after t.
            var lo = 0
            var hi = sorted.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (firsts[mid] < t + 60) lo = mid + 1 else hi = mid }
            return lo > 0 && reach[lo - 1] >= t
        }
    }

    /**
     * Sustained walking: a minute with at least [LOAD_WALK_STEPS] steps counts from this reserve fraction rather than
     * [LOAD_MIN_HRR]. On real data, sustained walking at about 100 steps a minute sat at 24-34 % of reserve, so the
     * 35 % line alone threw away whole walks; at 60+ steps a minute the movement is purposeful, not a few steps
     * around a desk. Full TRIMP: the curve already gives these minutes little (about 0.26 a minute at 25 %).
     */
    const val LOAD_WALK_HRR = 0.20f
    /** Steps in a minute that make it a walk: the bottom of the cadence MET scale OpenStrap already uses (60 → 2 MET). */
    const val LOAD_WALK_STEPS = 60
    /** Above this a minute's step count is a sentinel or an error (the strap's u8 tops out at 255). */
    const val MAX_MINUTE_STEPS = 250

    const val LOAD_RUN_MINUTES = 3
    const val LOAD_RUN_WINDOW = 5

    const val MAX_HR_RAISE = 15f
    /** Two separate days must reach within this of each other before a peak counts. */
    const val MAX_HR_AGREE_BPM = 3

    const val ARTEFACT_JUMP_BPM = 10

    const val RHR_DAYS = 14
    const val RHR_OFFSET_BPM = 3f

    /**
     * Outside a workout, a moving minute below this reserve fraction is everyday living rather than training (a walk
     * aside, [LOAD_WALK_HRR]). Zepp's Exertion uses 35 % (native 07 §3.1); a lower line lets a raised heart rate
     * during a few steps around a desk count as load.
     */
    const val LOAD_MIN_HRR = 0.35f

    const val HARD_DAY_RATIO = 1.5f

    /** Fitness (CTL), fatigue (ATL) and form for one day of [fitnessFatigue]. */
    data class LoadTrend(val ctl: Float, val atl: Float, val form: Float)

    /** The standard 42/7-day convention; personal time constants are not fitted (poorly identifiable, Imbach 2025). */
    const val CTL_DAYS = 42
    const val ATL_DAYS = 7

    const val STRAIN_MAX = 21f
    const val STRAIN_SCALE = 180f

    // ------------------------------------------------------------------------------------------------
    // Charge (the rules are in ChargeModel.kt, the stress threshold and exclusion included)
    // ------------------------------------------------------------------------------------------------

    /** What the body was doing in a minute, Firstbeat-style. */
    enum class State { Sleep, Relax, Rest, Stress, Activity, Exercise, ExerciseRecovery, NoData }

    data class ChargeParams(
        /** Daytime resting heart rate (the baseline), bpm. */
        val restingHr: Float,
        val maxHr: Float,
        /**
         * Lowest 30-minute average of the scored day's night, for the sleep relaxation term of a sleep without its own
         * ([sleepRestingHr]).
         */
        val nightRestingHr: Float? = null,
        /** Tonight's HRV contributor (0-100); scales how well sleep recharges. */
        val hrvScore: Int? = null,
        val female: Boolean = false,
        /** Stress score from which a still minute counts as stress (see [stressThreshold]). */
        val stressFrom: Int = STRESS_FROM,
        /**
         * The body-signal state of each main sleep ([BodySignals]), as (sleep start, state), any order. A state applies
         * from its sleep's start until the next sleep's start, as Zepp applies its morning alarm from the sleep-start
         * minute (native 03 §8.2). Only [BodySignals.State.Alert] changes Charge ([ALERT_RECHARGE], [ALERT_DRAIN]).
         */
        val bodySignals: List<Pair<Long, BodySignals.State>> = emptyList(),
        /**
         * The end of each recorded main sleep in the simulation. Charge there is a wake value: the restart value after
         * a long not-worn stretch is the median of the last [WAKE_HISTORY] of them ([NO_WEAR_LIMIT_MINUTES]), and the
         * not-worn count restarts at each, as Zepp's wake-to-wake window does.
         */
        val mainSleepEnds: List<Long> = emptyList(),
        /**
         * The user's usual main sleep on each night of the simulation, as absolute time ranges. A not-worn stretch that
         * covers at least [USUAL_SLEEP_COVER] of one is the strap off overnight ([ChargeDay.strapOffOvernight]).
         */
        val usualSleep: List<LongRange> = emptyList(),
        /**
         * Each main sleep's own night resting heart rate for the sleep relaxation term, as (sleep start, bpm), any order:
         * a sleep minute takes the latest that started at or before it, so a nap takes the night's before it. Without
         * one, or before the first, [nightRestingHr] stands in.
         */
        val sleepRestingHr: List<Pair<Long, Float?>> = emptyList(),
    )

    data class ChargePoint(val t: Long, val value: Float, val state: State)

    data class ChargeDay(
        /** Every [sampleSeconds] inside the requested window. */
        val points: List<ChargePoint>,
        val latest: Float?,
        /** Charge when the main sleep ended. */
        val atWake: Float?,
        val recharged: Float,
        /** Drained by movement: exercise and everyday activity above resting. */
        val drainedExercise: Float,
        val drainedStress: Float,
        val drainedLiving: Float,
        val highest: Float?,
        val lowest: Float?,
        /**
         * True once the simulation has seen a sleep: charge is relative to how full a night left you, so
         * without one the number would only reflect the arbitrary starting guess.
         */
        val anchored: Boolean,
        /**
         * True when the strap was off over the usual sleep time before this day: that stretch has no Charge (a gap in
         * [points]) and the day restarted from the median of recent wake values, so the numbers are an estimate.
         * [atWake] is then that restart value.
         */
        val strapOffOvernight: Boolean = false,
    )

    /** Sleep recharge rate per minute, as a fraction of the remaining headroom (≈30 → 92 over 7.5 h). */
    const val SLEEP_K = 0.0060f
    /**
     * Drain while awake and doing something, per minute (≈2.4 per hour): under every movement and stress minute, the
     * stress exclusion around a workout, and exercise recovery. Still, calm minutes don't pay it ([REST_DRAIN],
     * Relax).
     */
    const val BASE_DRAIN = 0.04f
    /**
     * Drain of a still, awake Rest minute outside workouts, per minute and per point of charge: 0.04/min at 80 (as much
     * as [BASE_DRAIN]), 0.025 at 50, 0.01 at 20, so a desk day still runs Charge down but ever more slowly. Zepp has
     * no awake drain on its physical pool; a seated day falls through its mental pool, whose drain also eases below 50
     * (native 03 §4.4). Its 8 hours at a desk at stress 35 take the total from 85 to 64 (§11); this takes 85 to 67.
     * Relax (low stress, still) has no drain at all, as Zepp's stress under 24 drains neither pool.
     */
    const val REST_DRAIN = 0.0005f
    /**
     * Recharge of a Relax minute (awake, still, stress under [RELAX_BELOW]), per minute, as a fraction of the headroom:
     * 85 → 86 over 8 hours, 60 → 62.8. Zepp's 8 hours at a desk at stress 20 from 85 end at 86 (native 03 §11): its
     * mental pool gains a little and its physical pool, at rest between 80 and 100, barely moves (§4.3, §4.4). A
     * calm day holds; sleep (SLEEP_K·weight, about 30 times this) is what fills Charge.
     */
    const val RELAX_K = 0.00015f
    /**
     * Not-worn drift per minute: the mean of Zepp's default no-wear slopes for its two pools (native 03 §4.7), physical
     * −0.04596 with mental −0.0505 at or above 50 and −0.02386 below: about −2.9 an hour above 50, −2.1 below.
     * Zepp learns per-user slopes from rest minutes; OpenStrap keeps the defaults.
     *
     * TODO: learn the user's own drift rate from their history, as Zepp does (native 03 §4.7): each day, measure the
     * mean per-minute change of Charge over quiet, awake, worn minutes; once about 30 days and enough minutes have
     * built up, replace these defaults with the minute-weighted mean of those days, keeping the defaults until then.
     */
    const val NO_WEAR_DRIFT_HIGH = -0.0482f
    const val NO_WEAR_DRIFT_LOW = -0.0349f
    /**
     * Not-worn minutes since the last wake after which Charge is invalid (no points), as in Zepp's 384 minutes, 6.4 h
     * (native 03 §3 step 12). A not-worn stretch over the usual sleep time is invalid from its start, however long.
     */
    const val NO_WEAR_LIMIT_MINUTES = 384
    /**
     * A stretch without heart rate (and outside sleep) counts as not worn once it runs this long: Zepp holds the last
     * heart rate for 541 s (native 03 §4.3), so a shorter gap is a dropout and Charge holds. Minutes the strap marks
     * off-wrist ([offWrist]) count at once. A gap still open at the end of the data (not synced yet) holds too.
     */
    const val NO_WEAR_GAP_MINUTES = 10
    /** Share of a usual sleep range ([ChargeParams.usualSleep]) a not-worn stretch must cover to be "off overnight". */
    const val USUAL_SLEEP_COVER = 0.5f
    /** Wake values the restart median uses, and the value without any (Zepp's start rule, native 03 §12). */
    const val WAKE_HISTORY = 7
    const val DEFAULT_WAKE = 80f
    /** Charge at the next recorded sleep after a long daytime not-worn stretch: the simulation's own neutral start. */
    const val RESTART_BEFORE_SLEEP = 50f
    /**
     * A run of awake minutes outside workouts at or above [EXERCISE_HRR] without steps counts as Exercise for its
     * whole length once it lasts this long: cycling, rowing or a stepper that wasn't recorded. 30 % of reserve is
     * about 40 bpm over resting for a 130-bpm reserve; caffeine adds a few bpm and a stressful moment pushes heart
     * rate up for a minute or two, so neither holds that for 10 minutes seated, while an unrecorded ride does.
     * Shorter runs stay Stress or Rest.
     */
    const val SUSTAINED_EFFORT_MINUTES = 10

    /** Charge drained per TRIMP unit of exercise (≈25 for an hour at 60% of reserve). */
    const val EXERCISE_DRAIN_PER_TRIMP = 0.32f
    /** Extra drain per stress point above the threshold, per minute (80 over 60 drains 0.14/min). */
    const val STRESS_DRAIN = 0.007f
    /**
     * Zepp's bands for the strap's score: relaxed 1 to 39, normal 40 to 59, medium 60 to 79, high 80 to 100. Only medium
     * and up count as stress; from 40, most of a seated day (Zepp's "normal") drained as stress. The default
     * until [stressThreshold] has enough of the user's own readings.
     */
    const val STRESS_FROM = 60
    /** Stress drains only after this many consecutive minutes at or above the threshold; a blip is rest. */
    const val STRESS_GATE_MINUTES = 3
    const val RELAX_BELOW = 25
    /**
     * A minute in the stress exclusion around a workout ([stressExcluded]) that the intensity states above haven't
     * claimed (heart rate under [ACTIVITY_HRR], few steps, no post-exercise debt: a strength workout between sets,
     * yoga, the cool-down) never becomes Stress or Relax, since its stress reading reflects the exercise. It becomes:
     * - **Activity** inside the workout's active time, draining by its TRIMP as any light activity does: it is part
     *   of a recorded workout, and daily load counts every workout minute too ([dailyLoad]);
     * - **ExerciseRecovery** in the 20 minutes after, the same base drain as the debt-driven recovery above;
     * - **Rest** in either when heart rate is below this fraction of reserve (about 12 bpm over resting for a
     *   120-bpm reserve), where it is back at sitting level and there is nothing to attribute to the workout.
     * Activity at this intensity drains little more than Rest (TRIMP ≈ 0.08 a minute at 10 %), so the choice mostly
     * shows in the state strip.
     */
    const val WORKOUT_REST_HRR = 0.10f
    /**
     * Firstbeat's intensity bands, as heart-rate reserve (≈ %VO2 reserve, Swain & Leutholtz 1997). Outside the stress
     * exclusion around a workout, a minute reaches Activity or Exercise by heart rate only while moving ([moving]),
     * or as part of a sustained run at [EXERCISE_HRR] ([SUSTAINED_EFFORT_MINUTES]):
     * a raised heart rate sitting still (heat, caffeine, a fever, sitting stress) isn't effort, as in Zepp, whose
     * physical pool drains only with movement (native 03 §4.3), and in [dailyLoad]. Such a minute can't Relax either:
     * it is Stress if the stress score says so, else Rest.
     */
    const val ACTIVITY_HRR = 0.20f
    const val EXERCISE_HRR = 0.30f

    /**
     * In [BodySignals.State.Alert], recharge (sleep and Relax) is multiplied by [ALERT_RECHARGE] and every drain by
     * [ALERT_DRAIN]: the ends of Zepp's health-alarm multipliers (native 03 §4.2). Zepp ramps them with how far over
     * its one-day threshold the night is; OpenStrap's Alert already needs persistence or two signal families, so it
     * takes the full effect. Watch doesn't change Charge: it is one signal for one night, shown in the app only and
     * leaving Readiness uncapped, and Zepp's multipliers just under its threshold are within 5 % of 1.
     */
    const val ALERT_RECHARGE = 0.5f
    const val ALERT_DRAIN = 1.5f

    /** Charge's EPOC-like debt, and a workout's EPOC at rest ([WorkoutEffect]), are multiplied by this each minute (half-life ≈ 20 min). */
    const val EPOC_DECAY = 0.966f

    /**
     * Gates for a nightly sample: RMSSD outside 5-200 ms or heart rate outside [HeartRate.VALID] is treated as
     * artefact.
     */
    const val HRV_MIN_MS = 5
    const val HRV_MAX_MS = 200
    const val HR_MIN_BPM = HeartRate.MIN_BPM
    const val HR_MAX_BPM = HeartRate.MAX_BPM

    /** The [p]-th percentile (0-1) of [values] by linear interpolation; null when empty. */
    fun percentile(values: List<Float>, p: Float): Float? = Stats.quantile(values, p)

    /** Lowest rolling-[windowMinutes] average in [series], with the time at its centre. */
    fun lowestAverage(series: List<Pair<Long, Int>>, windowMinutes: Int, minCount: Int = windowMinutes / 2): Pair<Long, Float>? {
        if (series.size < minCount) return null
        var best: Pair<Long, Float>? = null
        var lo = 0
        var sum = 0L
        for (hi in series.indices) {
            sum += series[hi].second
            while (series[hi].first - series[lo].first >= windowMinutes * 60L) { sum -= series[lo].second; lo++ }
            val n = hi - lo + 1
            if (n >= minCount) {
                val avg = sum.toFloat() / n
                if (best == null || avg < best.second) best = ((series[lo].first + series[hi].first) / 2) to avg
            }
        }
        return best
    }
}
