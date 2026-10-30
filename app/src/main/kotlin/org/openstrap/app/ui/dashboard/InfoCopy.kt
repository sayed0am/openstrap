package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.Activity
import org.openstrap.app.data.History
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/*
 * What every detail's info sheet says (spec 6.2): a bold one-line meaning, the scale its value is read on, and three
 * folded rows. Every method name, formula and caveat that used to sit on a card or a detail lives here, one tap from
 * the number, so the cards can lead with what it means.
 */

/** An info sheet's words: the [meaning] in bold, the [scale] from low to high, and the three folded rows. */
data class Info(
    val name: String,
    val meaning: String,
    val scale: List<String>,
    val how: String,
    val moves: String,
    val off: String,
)

object InfoCopy {
    /** The scale of every value read against your normal, low to high. */
    val NORMAL_SCALE = listOf(tr(R.string.common_below_your_normal), tr(R.string.common_usual), tr(R.string.common_above_your_normal))

    /** Recovery's bands, low to high, in the words beside the score ([TodayCopy.recoveryWord]). */
    val RECOVERY_SCALE = listOf(tr(R.string.common_take_it_easy), tr(R.string.common_moderate), tr(R.string.common_ready), tr(R.string.common_ready_for_more))

    /** Charge's bands, low to high. */
    val CHARGE_SCALE = listOf(tr(R.string.info_copy_running_low), tr(R.string.info_copy_some_left), tr(R.string.common_good), tr(R.string.info_copy_full))

    /** The Sleep score's bands, low to high, in the words beside it ([TabCopy.sleepScoreWord]). */
    val SLEEP_SCALE = listOf(tr(R.string.common_poor), tr(R.string.common_fair), tr(R.string.common_good), tr(R.string.common_great))

    /** Sleep regularity's bands, low to high ([SleepRegularity.Band]). */
    val REGULARITY_SCALE = SleepRegularity.Band.entries.map { it.label }

    /** Which bar of [SLEEP_SCALE] a Sleep score is in. */
    fun sleepBar(score: Int): Int = when {
        score >= 90 -> 3
        score >= 80 -> 2
        score >= 60 -> 1
        else -> 0
    }

    /** Which bar of [REGULARITY_SCALE] a band is. */
    fun regularityBar(band: SleepRegularity.Band): Int = band.ordinal

    /** Which bar of [NORMAL_SCALE] a status against your normal is in; null without a normal. */
    fun normalBar(status: Int?): Int? = status?.let { it.coerceIn(-1, 1) + 1 }

    /** Which bar of [RECOVERY_SCALE] a score is in. */
    fun recoveryBar(score: Int): Int = when {
        score >= 85 -> 3
        score >= 70 -> 2
        score >= Recovery.FAIR_FROM -> 1
        else -> 0
    }

    /** Which bar of [CHARGE_SCALE] a Charge is in, by quarters. */
    fun chargeBar(charge: Int): Int = (charge / 25).coerceIn(0, 3)

    /**
     * The line under the scale: "You are here. Your normal is 44 to 58 ms, from 30 days." Before there's a normal, how
     * far along it is.
     */
    fun normalLine(normal: History.Normal?, days: Int, format: (Float) -> String, unit: String, nightly: Boolean): String {
        val word = tr(if (nightly) R.string.missed_unit_night else R.string.missed_unit_day) + trRaw(R.string.missed_plural)
        if (normal == null) return tr(R.string.info_copy_your_normal_shows_after, History.MIN_NORMAL_DAYS, word, days.coerceAtMost(History.MIN_NORMAL_DAYS))
        val u = if (unit.isEmpty()) "" else if (unit.startsWith("%") || unit.startsWith("°")) unit else " $unit"
        return tr(R.string.info_copy_you_are_here_your, format(normal.low), format(normal.high), u, normal.days, word)
    }

    /** The line under Recovery's and Charge's scales. */
    val HERE: String get() = tr(R.string.info_copy_you_are_here)

    fun of(metric: Metric): Info = when (metric) {
        Metric.HeartRate -> Info(
            tr(R.string.common_heart_rate),
            tr(R.string.info_copy_how_fast_your_heart),
            NORMAL_SCALE,
            how = tr(R.string.info_copy_the_strap_reads_your),
            moves = tr(R.string.info_copy_movement_stress_caffeine_and),
            off = tr(R.string.info_copy_a_loose_strap_cold),
        )
        Metric.RestingHr -> Info(
            tr(R.string.common_resting_heart_rate_2),
            tr(R.string.info_copy_your_heart_rate_at),
            NORMAL_SCALE,
            how = tr(R.string.info_copy_the_strap_works_it),
            moves = tr(R.string.info_copy_it_rises_after_hard),
            off = tr(R.string.info_copy_a_night_with_the),
        )
        Metric.Hrv -> Info(
            tr(R.string.common_hrv),
            tr(R.string.info_copy_heart_rate_variability_the),
            NORMAL_SCALE,
            how = tr(R.string.info_copy_the_strap_measures_the),
            moves = tr(R.string.info_copy_good_sleep_and_rest),
            off = tr(R.string.info_copy_moving_during_a_reading),
        )
        Metric.Spo2 -> Info(
            tr(R.string.common_blood_oxygen),
            tr(R.string.info_copy_how_much_of_your),
            NORMAL_SCALE,
            how = tr(R.string.info_copy_the_strap_shines_red),
            moves = tr(R.string.info_copy_altitude_and_pauses_in),
            off = tr(R.string.info_copy_a_loose_strap_cold_2),
        )
        Metric.Stress -> Info(
            tr(R.string.common_stress),
            tr(R.string.info_copy_the_strap_s_stress),
            NORMAL_SCALE,
            how = tr(R.string.info_copy_the_strap_works_it_2),
            moves = tr(R.string.info_copy_pressure_caffeine_and_too),
            off = tr(R.string.info_copy_excitement_heat_and_an),
        )
        Metric.SkinTemperature -> Info(
            tr(R.string.common_skin_temperature),
            tr(R.string.info_copy_the_temperature_of_your),
            NORMAL_SCALE,
            how = tr(R.string.info_copy_the_strap_measures_it),
            moves = tr(R.string.info_copy_it_moves_with_alcohol),
            off = tr(R.string.info_copy_a_warm_bed_a),
        )
        Metric.Breathing -> Info(
            tr(R.string.common_breathing_rate),
            tr(R.string.info_copy_how_many_breaths_you),
            NORMAL_SCALE,
            how = tr(R.string.info_copy_the_strap_measures_it_2),
            moves = tr(R.string.info_copy_it_can_rise_above),
            off = tr(R.string.info_copy_a_restless_night_or),
        )
        Metric.Steps -> Info(
            tr(R.string.common_steps),
            tr(R.string.info_copy_steps_counted_by_the),
            NORMAL_SCALE,
            how = tr(R.string.info_copy_the_strap_counts_them),
            moves = tr(R.string.info_copy_short_walks_spread_across),
            off = tr(R.string.info_copy_pushing_a_pram_or),
        )
    }

    fun of(score: Score): Info = when (score) {
        Score.Recovery -> Info(
            tr(R.string.common_recovery),
            tr(R.string.info_copy_how_ready_your_body),
            RECOVERY_SCALE,
            how = tr(R.string.info_copy_each_morning_openstrap_compares),
            moves = tr(R.string.info_copy_good_sleep_easy_days),
            off = tr(R.string.info_copy_it_needs_good_nights, Recovery.SCORED_NIGHTS, Recovery.SETTLED_NIGHTS),
        )
        Score.Charge -> Info(
            tr(R.string.common_energy),
            tr(R.string.info_copy_your_energy_right_now),
            CHARGE_SCALE,
            how = tr(R.string.info_copy_think_of_it_as),
            moves = tr(R.string.info_copy_a_long_calm_night),
            off = tr(R.string.info_copy_energy_starts_after_your),
        )
        Score.Sleep -> Info(
            tr(R.string.common_sleep_score),
            tr(R.string.info_copy_how_well_you_slept),
            SLEEP_SCALE,
            how = tr(R.string.info_copy_the_score_is_the),
            moves = tr(R.string.info_copy_regular_bedtimes_a_dark),
            off = tr(R.string.info_copy_lying_still_awake_can),
        )
        Score.Regularity -> Info(
            tr(R.string.common_sleep_regularity),
            tr(R.string.info_copy_how_alike_your_sleep),
            REGULARITY_SCALE,
            how = tr(R.string.info_copy_openstrap_works_out_the, SleepRegularity.MIN_VALID_PAIRS / 60),
            moves = tr(R.string.info_copy_going_to_bed_and),
            off = tr(R.string.info_copy_minutes_with_the_strap),
        )
        Score.Naps -> Info(
            tr(R.string.common_naps),
            tr(R.string.info_copy_sleep_in_the_day),
            emptyList(),
            how = tr(R.string.info_copy_the_strap_tells_a),
            moves = tr(R.string.info_copy_a_short_nap_early),
            off = tr(R.string.info_copy_lying_still_awake_can_2),
        )
        // The Activity tab's (spec 5.3). The method notes that sat on the old Activity panels (Paluch, Mifflin-St Jeor,
        // Jurca, FRIEND, acute:chronic, the strap's weekly EPOC range, training effect) live here now.
        Score.HeartPoints -> Info(
            tr(R.string.common_heart_points),
            tr(R.string.info_copy_points_for_the_minutes, ActivityCopy.HEART_POINTS_TARGET),
            emptyList(),
            how = tr(R.string.info_copy_heart_points_are_the),
            moves = tr(R.string.info_copy_anything_that_gets_you, ActivityCopy.HEART_POINTS_TARGET),
            off = tr(R.string.info_copy_the_total_updates_when),
        )
        Score.ActiveMinutes -> Info(
            tr(R.string.common_active_minutes),
            tr(R.string.info_copy_minutes_of_brisk_movement, Activity.MVPA_WEEKLY_TARGET),
            emptyList(),
            how = tr(R.string.info_copy_a_minute_at_steps, Activity.MODERATE_CADENCE, Activity.VIGOROUS_CADENCE, Activity.CADENCE_RUN_MINUTES, Activity.HR_RUN_MINUTES, (Activity.HR_MODERATE_HRR * 100).toInt(), (Activity.HR_VIGOROUS_HRR * 100).toInt()),
            moves = tr(R.string.info_copy_brisk_walks_of_a),
            off = tr(R.string.info_copy_no_study_has_checked),
        )
        Score.Load -> Info(
            tr(R.string.common_load),
            tr(R.string.info_copy_how_hard_your_heart),
            ActivityCopy.LOAD_SCALE,
            how = tr(R.string.info_copy_each_minute_counts_by),
            moves = tr(R.string.info_copy_workouts_add_the_most),
            off = tr(R.string.info_copy_days_the_strap_was),
        )
        Score.Vo2Max -> Info(
            tr(R.string.common_vo_max),
            tr(R.string.info_copy_the_most_oxygen_your),
            emptyList(),
            how = tr(R.string.info_copy_it_s_measured_in),
            moves = tr(R.string.info_copy_regular_hard_effort_raises),
            off = tr(R.string.info_copy_the_estimate_from_your),
        )
        Score.Recover -> Info(
            tr(R.string.common_time_to_recover),
            tr(R.string.info_copy_how_long_your_body),
            emptyList(),
            how = tr(R.string.info_copy_it_s_the_strap),
            moves = tr(R.string.info_copy_longer_and_harder_workouts),
            off = tr(R.string.info_copy_it_knows_only_the),
        )
        Score.Energy -> Info(
            tr(R.string.common_calories),
            tr(R.string.info_copy_active_kcal_are_what),
            emptyList(),
            how = tr(R.string.info_copy_your_bodys_use_at),
            moves = tr(R.string.info_copy_moving_more_raises_it),
            off = tr(R.string.info_copy_food_digestion_isnt_included),
        )
        Score.Distance -> Info(
            tr(R.string.common_distance),
            tr(R.string.info_copy_how_far_you_walked),
            emptyList(),
            how = tr(R.string.info_copy_each_minute_s_steps, (Activity.WALK_STEP_FRACTION * 100).roundToIntString(), (Activity.RUN_STEP_FRACTION * 100).roundToIntString()),
            moves = tr(R.string.info_copy_more_steps_and_faster),
            off = tr(R.string.info_copy_step_length_varies_from),
        )
    }

    private fun Float.roundToIntString(): String = if (this % 1f == 0f) toInt().toString() else "%.1f".format(this)

    /** What's particular about today's active minutes: how many came from heart rate. */
    fun activeNotes(fromHeartRate: Int, today: Boolean): List<String> = listOfNotNull(
        tr(R.string.info_copy_of_minutes_came_from, fromHeartRate, if (today) tr(R.string.info_copy_of_minutes_came_from__today_s) else tr(R.string.info_copy_of_minutes_came_from__that_day_s)).takeIf { fromHeartRate > 0 },
    )

    /** What's particular about today's Load: the heart rates it's read between, and the contested ratio. */
    fun loadNotes(restingHr: Float, maxHr: Float, maxTested: Boolean, acwr: Float?): List<String> = listOfNotNull(
        tr(R.string.info_copy_today_it_s_read, restingHr.toInt(), maxHr.toInt()) +
            if (maxTested) tr(R.string.info_copy_from_your_test) else ".",
        acwr?.let {
            trRaw(R.string.info_copy_your_last_days_against).format(it) +
                tr(R.string.info_copy_interest_only_since_the)
        },
    )

    /** What's particular about the VO2 max estimate: the resting heart rate used, fitness age, or why it's withheld. */
    fun vo2Notes(estimate: org.openstrap.app.data.Vo2max.Estimate?, withheld: org.openstrap.app.data.Vo2max.Withheld?): List<String> = buildList {
        estimate?.let { e ->
            add(tr(R.string.info_copy_this_week_s_estimate, e.restingHr.toInt()))
            e.fitnessAge?.let { a ->
                e.sex?.let { add(tr(R.string.info_copy_that_s_typical_of, if (it == org.openstrap.app.data.settings.UserProfile.Sex.Male) tr(R.string.info_copy_that_s_typical_of__man) else tr(R.string.info_copy_that_s_typical_of__woman), a.first, a.last)) }
            }
        }
        withheld?.let { add(tr(R.string.info_copy_no_estimate_from_your, it.reason)) }
    }

    /** What's particular about the day's energy: part of the day worn, an unusual total, minutes from heart rate. */
    fun energyNotes(e: org.openstrap.app.data.EnergyExpenditure.Day?): List<String> = buildList {
        e ?: return@buildList
        if (e.partial) add(tr(R.string.info_copy_it_covers_of_wear, TodayCopy.duration(e.minutesWithData * 60L)))
        if (e.palImplausible) add(tr(R.string.info_copy_that_s_an_unusual))
        if (e.hrAssistedMinutes > 0) add(tr(R.string.info_copy_minutes_came_from_heart, e.hrAssistedMinutes))
    }

    /** Spec 6.2's sheet for a night vital opened from the Sleep tab: the metric's, saying where the night's numbers come from. */
    fun ofNight(metric: Metric): Info = of(metric).let { it.copy(how = it.how + " " + nightHow(metric)) }

    private fun nightHow(metric: Metric): String = when (metric) {
        Metric.HeartRate -> tr(R.string.info_copy_opened_from_the_night)
        Metric.Spo2 -> tr(R.string.info_copy_opened_from_the_night_2)
        Metric.Breathing -> tr(R.string.info_copy_opened_from_the_night_3)
        else -> tr(R.string.info_copy_opened_from_the_night_4)
    }

    /** The breathing disturbances sheet: an early estimate, with no scale, since its only words would be apnea bands. */
    val BREATHING_DISTURBANCES = Info(
        tr(R.string.common_breathing_disturbances),
        tr(R.string.info_copy_dips_in_blood_oxygen),
        emptyList(),
        how = tr(R.string.info_copy_the_strap_counts_each, Recovery.SCORED_NIGHTS),
        moves = tr(R.string.info_copy_alcohol_a_blocked_nose),
        off = tr(R.string.info_copy_the_strap_s_units, org.openstrap.app.data.BodySignalsCopy.DISCLAIMER),
    )

    /** Habit Age's bands, against your age: [habitAgeBar] says which one you're in. */
    val HABIT_AGE_SCALE = listOf(tr(R.string.info_copy_younger_than_you), tr(R.string.info_copy_your_age), tr(R.string.info_copy_older_than_you))

    /** Which bar of [HABIT_AGE_SCALE] an estimate [years] younger than your age is in. */
    fun habitAgeBar(years: Int): Int = when {
        years > 0 -> 0
        years < 0 -> 2
        else -> 1
    }

    /** Habit Age's sheet, behind the (i) on its card (spec 5.4): the method, the pace of aging and what moves it. */
    val HABIT_AGE = Info(
        tr(R.string.common_habit_age),
        tr(R.string.info_copy_how_old_your_habits),
        HABIT_AGE_SCALE,
        how = tr(R.string.info_copy_each_habit_is_scored),
        moves = tr(R.string.info_copy_your_habits_over_months),
        off = tr(R.string.info_copy_it_updates_once_a),
    )

    /** The week story's sheet: what each day's numbers are and how far back the comparisons reach. */
    val WEEK = Info(
        tr(R.string.common_your_week),
        tr(R.string.info_copy_last_monday_to_sunday),
        emptyList(),
        how = tr(R.string.info_copy_steps_are_each_day),
        moves = tr(R.string.info_copy_every_walk_workout_and),
        off = tr(R.string.info_copy_steps_and_workouts_are),
    )

    /** Under the breathing disturbances sheet's title: the night against your usual, or how far the usual has got. */
    fun breathingLine(b: Recovery.BreathingDisturbances, today: Boolean): String {
        val night = trRaw(R.string.info_copy_f_an_hour)
            .format(tr(if (today) R.string.info_copy_f_an_hour__last_night else R.string.info_copy_f_an_hour__that_night), b.perHour)
        return when (val usual = b.usual) {
            null -> tr(R.string.info_copy_learning_your_usual_of, night, b.nights.coerceAtMost(Recovery.SCORED_NIGHTS), Recovery.SCORED_NIGHTS)
            else -> trRaw(R.string.info_copy_your_usual_is_f).format(night, usual)
        }
    }

    /** What's particular about this week's regularity: how much wear it compares, or what it still needs. */
    fun regularityNotes(reg: SleepRegularity.Result?): List<String> = buildList {
        reg ?: return@buildList
        val hours = reg.validPairs / 60
        if (reg.sri != null) add(tr(R.string.info_copy_this_week_compares_h, hours))
        else {
            add(tr(R.string.info_copy_not_enough_wear_for, hours, SleepRegularity.MIN_VALID_PAIRS / 60))
            reg.midpointSdMinutes?.let { add(tr(R.string.info_copy_meanwhile_the_middle_of, TodayCopy.duration(Math.round(it) * 60L))) }
        }
    }

    /** What's particular about the night's Sleep score: the sleep need the guidance measures against. */
    fun sleepNotes(needMinutes: Int): List<String> = listOf(tr(R.string.info_copy_your_sleep_need_here, TodayCopy.duration(needMinutes * 60L)))

    /**
     * What's particular about today's Recovery, for its info sheet: the strap's own score beside ours, and anything that
     * made tonight's reading less sure. Each is a sentence; none when there's nothing to say.
     */
    fun recoveryNotes(ready: Recovery.Readiness?, strapScore: Int?): List<String> = buildList {
        if (strapScore != null) add(
            tr(R.string.info_copy_the_strap_s_own, strapScore),
        )
        ready ?: return@buildList
        if (ready.capped) add(tr(R.string.info_copy_today_one_body_signal))
        ready.fallbackHrv?.let { add(tr(R.string.info_copy_last_night_s_own, it.toInt())) }
        ready.fallbackRestingHr?.let { add(tr(R.string.info_copy_last_night_s_own_2, it.toInt())) }
        if (ready.hrvWeekThin) add(tr(R.string.info_copy_the_week_s_hrv, ready.hrvWeekNights))
    }

    /** What's particular about today's Charge: an estimated start after a night with the strap off. */
    fun chargeNotes(c: Recovery.ChargeDay?): List<String> = listOfNotNull(
        tr(R.string.info_copy_the_strap_was_off).takeIf { c?.strapOffOvernight == true },
    )
}
