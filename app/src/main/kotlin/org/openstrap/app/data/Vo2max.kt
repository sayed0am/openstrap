package org.openstrap.app.data

import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * A non-exercise VO2max estimate (docs/algorithms/evidence-review.md, "VO2max and fitness age"): Jurca et al. 2005
 * needs only sex, age, BMI, resting HR and one activity question. Its SEE is 1.45-1.97 METs, with no bias in an
 * independent older-adult sample (Mailey et al. 2010). Shown as a band, never a decimal, with a FRIEND 2022 percentile
 * band and an optional fitness age. No biological age.
 */
object Vo2max {

    /*
     * Jurca et al. 2005, Am J Prev Med 29(3):185-193, NASA model (Table 5) and the worksheet built from it (Figure 1),
     * checked against the full text: METs = 2.77·sex (female 0, male 1) − 0.10·age − 0.17·BMI − 0.03·RHR + SR-PA score
     * + 18.07, SEE 1.45 METs. The SR-PA scores are the NASA model's dummy weights, not 0-4.
     */
    const val SEX_COEF = 2.77f
    const val AGE_COEF = 0.10f
    const val BMI_COEF = 0.17f
    const val RHR_COEF = 0.03f
    const val INTERCEPT = 18.07f
    /** Score for activity levels 1-5 (index 0-4), Jurca 2005 Figure 1. */
    val SRPA_SCORES = floatArrayOf(0f, 0.32f, 1.06f, 1.76f, 3.03f)
    /** The NASA model's standard error of estimate, METs (≈ 5 ml/kg/min). */
    const val SEE_METS = 1.45f
    const val ML_PER_MET = 3.5f

    /** The worksheet's question, Jurca 2005 Figure 1 (shortened): the user's usual pattern of physical activity. */
    val ACTIVITY_LEVELS = listOf(
        tr(R.string.vo2max_inactive_or_little_activity),
        tr(R.string.vo2max_light_activity_at_least),
        tr(R.string.vo2max_aerobic_exercise_minutes_a),
        tr(R.string.vo2max_aerobic_exercise_hours_a),
        tr(R.string.vo2max_aerobic_exercise_over_hours),
    )

    /** Withholding rules. */
    const val MIN_RHR_NIGHTS = 5
    const val RHR_LOOKBACK_DAYS = 14
    const val RHR_NIGHTS_USED = 7
    const val BODY_MAX_AGE_DAYS = 90
    const val ACTIVITY_MAX_AGE_DAYS = 180
    const val MIN_AGE = 20
    const val MAX_AGE = 79

    /** Jurca's estimate in METs. */
    fun jurcaMets(male: Boolean, age: Int, bmi: Float, restingHr: Float, activityLevel: Int): Float =
        SEX_COEF * (if (male) 1 else 0) - AGE_COEF * age - BMI_COEF * bmi - RHR_COEF * restingHr +
            SRPA_SCORES[activityLevel.coerceIn(0, SRPA_SCORES.size - 1)] + INTERCEPT

    data class Estimate(
        /** ml/kg/min, rounded: never shown as a decimal. */
        val value: Int,
        /** ±1 SEE. */
        val low: Int,
        val high: Int,
        /** The resting HR used: median of the last 7 nightly values. */
        val restingHr: Float,
        /** FRIEND 2022 percentile band for the user's age and sex (see [Friend]). */
        val band: Friend.Band? = null,
        /** Fitness age as a range (see [Friend.fitnessAge]); withheld with the estimate. */
        val fitnessAge: IntRange? = null,
        val sex: UserProfile.Sex? = null,
    )

    /** Why there is no estimate, in the order checked. */
    enum class Withheld(val reason: String) {
        Age(tr(R.string.vo2max_needs_an_age_between)),
        Sex(tr(R.string.vo2max_needs_your_sex_the)),
        Body(tr(R.string.vo2max_needs_a_weight_and)),
        Activity(tr(R.string.vo2max_answer_the_activity_question)),
        Medication(tr(R.string.common_not_shown_while_taking)),
        RestingHr(tr(R.string.vo2max_needs_at_least_nights)),
    }

    /**
     * The estimate for [day] (local midnight, epoch seconds), from [nightlyRhr] (morning → the night's resting HR) and
     * the [profile]. Sleeping RHR runs a few bpm below a seated one; at −0.03 METs per bpm that is small next to the SEE,
     * so it's documented, not corrected.
     */
    fun estimate(profile: UserProfile, age: Int?, nightlyRhr: Map<Long, Float>, day: Long): Pair<Estimate?, Withheld?> {
        val dayS = 86_400L
        if (age == null || age !in MIN_AGE..MAX_AGE) return null to Withheld.Age
        val sex = profile.sex ?: return null to Withheld.Sex
        val w = profile.weightKg
        val h = profile.heightCm
        val measured = profile.bodyMeasuredAt
        if (w == null || h == null || measured == null || day - measured > BODY_MAX_AGE_DAYS * dayS) return null to Withheld.Body
        val level = profile.activityLevel
        val answered = profile.activityAnsweredAt
        if (level == null || answered == null || day - answered > ACTIVITY_MAX_AGE_DAYS * dayS) return null to Withheld.Activity
        if (profile.betaBlocker || profile.pregnant) return null to Withheld.Medication
        val recent = nightlyRhr.filterKeys { it <= day && day - it < RHR_LOOKBACK_DAYS * dayS }.toSortedMap().values.toList()
        if (recent.size < MIN_RHR_NIGHTS) return null to Withheld.RestingHr
        val rhr = Recovery.percentile(recent.takeLast(RHR_NIGHTS_USED), 0.5f)!!
        val bmi = w / ((h / 100f) * (h / 100f))
        val mets = jurcaMets(sex == UserProfile.Sex.Male, age, bmi, rhr, level)
        val ml = mets * ML_PER_MET
        val see = SEE_METS * ML_PER_MET
        return Estimate(
            Math.round(ml), Math.round(ml - see), Math.round(ml + see), rhr,
            band = Friend.band(ml, sex, age), fitnessAge = Friend.fitnessAge(ml, sex), sex = sex,
        ) to null
    }

    /**
     * FRIEND 2022 reference standards: Kaminsky et al., "Updated Reference Standards for Cardiorespiratory Fitness
     * Measured with Cardiopulmonary Exercise Testing", Mayo Clin Proc 2022;97(2):285-293, doi:10.1016/j.mayocp.2021.08.020.
     * Apparently healthy US adults, treadmill CPX, RER ≥ 1.0. Jurca's equation was validated against treadmill VO2max, so
     * the treadmill rows are the ones used.
     *
     * The paper reports only the 10th, 20th … 90th percentiles, so the bands use the nearest reported cuts,
     * < 30 / 30-50 / 50-70 / 70-90 / ≥ 90, in place of the evidence review's 25 / 75 / 97.7, which would need
     * interpolation or a distribution assumption.
     */
    object Friend {
        enum class Band(val label: String) {
            Below30(tr(R.string.vo2max_below_the_th_percentile)),
            P30to50(tr(R.string.vo2max_th_th_percentile)),
            P50to70(tr(R.string.vo2max_th_th_percentile_2)),
            P70to90(tr(R.string.vo2max_th_th_percentile_3)),
            Top10(tr(R.string.vo2max_top)),
        }

        /** The percentiles in each row, ascending. */
        val PERCENTILES = intArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90)

        /*
         * Kaminsky et al. 2022, Table 3 ("CRF Percentiles for the FRIEND Cohort by Age Group for Treadmill and Cycle
         * Ergometer CPX ... Using an Inclusion Criterion of RER ≥ 1.0"), treadmill section, copied from the published
         * table (Mayo Clin Proc full text, open access). ml/kg/min; each row is the 10th … 90th percentile.
         */
        val MEN: Map<IntRange, FloatArray> = linkedMapOf(
            20..29 to floatArrayOf(28.6f, 35.2f, 40.0f, 43.6f, 46.5f, 49.0f, 51.9f, 54.5f, 58.6f),
            30..39 to floatArrayOf(24.9f, 29.8f, 33.5f, 37.0f, 39.7f, 43.4f, 46.4f, 50.0f, 55.5f),
            40..49 to floatArrayOf(22.1f, 26.7f, 29.7f, 32.4f, 35.3f, 37.9f, 40.9f, 45.2f, 50.8f),
            50..59 to floatArrayOf(18.6f, 22.2f, 24.5f, 26.9f, 29.2f, 31.8f, 34.3f, 38.3f, 43.4f),
            60..69 to floatArrayOf(15.8f, 18.5f, 20.7f, 22.8f, 24.6f, 26.5f, 28.7f, 32.0f, 37.1f),
            70..79 to floatArrayOf(13.6f, 15.9f, 17.3f, 19.1f, 20.6f, 22.2f, 23.8f, 25.9f, 29.4f),
            80..89 to floatArrayOf(12.9f, 14.8f, 16.1f, 16.6f, 17.6f, 18.4f, 20.0f, 21.4f, 22.8f),
        )
        val WOMEN: Map<IntRange, FloatArray> = linkedMapOf(
            20..29 to floatArrayOf(22.5f, 27.2f, 30.8f, 34.0f, 36.6f, 39.0f, 41.8f, 44.8f, 49.0f),
            30..39 to floatArrayOf(18.6f, 21.9f, 24.2f, 26.4f, 28.3f, 31.0f, 33.6f, 37.0f, 42.1f),
            40..49 to floatArrayOf(17.2f, 19.7f, 21.8f, 23.9f, 25.7f, 27.7f, 30.0f, 33.0f, 37.8f),
            50..59 to floatArrayOf(16.5f, 18.5f, 20.1f, 21.5f, 22.9f, 24.6f, 26.3f, 28.4f, 32.4f),
            60..69 to floatArrayOf(13.4f, 15.4f, 17.0f, 18.3f, 19.6f, 20.9f, 22.4f, 24.3f, 27.3f),
            70..79 to floatArrayOf(12.3f, 14.0f, 15.2f, 16.2f, 17.2f, 18.3f, 19.6f, 20.8f, 22.8f),
            80..89 to floatArrayOf(11.4f, 12.6f, 13.7f, 14.7f, 15.4f, 16.0f, 17.3f, 18.4f, 20.8f),
        )

        private const val P30 = 2
        private const val P50 = 4
        private const val P70 = 6
        private const val P90 = 8

        private fun table(sex: UserProfile.Sex) = if (sex == UserProfile.Sex.Male) MEN else WOMEN

        /** The band for [vo2] (ml/kg/min) against the row for the user's age decade; null outside 20-89. */
        fun band(vo2: Float, sex: UserProfile.Sex, age: Int): Band? {
            val row = table(sex).entries.firstOrNull { age in it.key }?.value ?: return null
            return when {
                vo2 < row[P30] -> Band.Below30
                vo2 < row[P50] -> Band.P30to50
                vo2 < row[P70] -> Band.P50to70
                vo2 < row[P90] -> Band.P70to90
                else -> Band.Top10
            }
        }

        /**
         * Fitness age: the age at which the sex-specific 50th percentile equals [vo2]. The medians are placed at each
         * decade's midpoint (25, 35 … 85) and joined linearly; they fall with every decade in Table 3. Shown as a range
         * of ±[FITNESS_AGE_SPREAD] years, clamped to 20-80.
         */
        fun fitnessAge(vo2: Float, sex: UserProfile.Sex): IntRange {
            val pts = table(sex).entries.map { (it.key.first + it.key.last + 1) / 2f to it.value[P50] }
            val age = when {
                vo2 >= pts.first().second -> pts.first().first
                vo2 <= pts.last().second -> pts.last().first
                else -> pts.zipWithNext().first { (a, b) -> vo2 <= a.second && vo2 >= b.second }.let { (a, b) ->
                    a.first + (a.second - vo2) / (a.second - b.second) * (b.first - a.first)
                }
            }
            val centre = Math.round(age).coerceIn(FITNESS_AGE_MIN, FITNESS_AGE_MAX)
            return (centre - FITNESS_AGE_SPREAD).coerceAtLeast(FITNESS_AGE_MIN)..(centre + FITNESS_AGE_SPREAD).coerceAtMost(FITNESS_AGE_MAX)
        }

        /** "typical of a woman aged about 35–55". */
        fun fitnessAgeText(range: IntRange, sex: UserProfile.Sex): String =
            tr(R.string.vo2max_typical_of_a_aged, if (sex == UserProfile.Sex.Male) tr(R.string.vo2max_typical_of_a_aged__man) else tr(R.string.vo2max_typical_of_a_aged__woman), range.first, range.last)

        const val FITNESS_AGE_SPREAD = 10
        const val FITNESS_AGE_MIN = 20
        const val FITNESS_AGE_MAX = 80
    }
}
