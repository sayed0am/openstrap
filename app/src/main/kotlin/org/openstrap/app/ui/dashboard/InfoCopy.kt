package org.openstrap.app.ui.dashboard

import androidx.annotation.StringRes
import org.openstrap.app.R
import org.openstrap.app.data.Activity
import org.openstrap.app.data.History
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.RecoveryBands
import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.SleepBands
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.Vo2maxCopy
import org.openstrap.app.presentation.ActivityCopy
import org.openstrap.app.presentation.Metric

/*
 * What every detail's info sheet says (spec 6.2): a bold one-line meaning, the scale its value is read on, and three
 * folded rows. Every method name, formula and caveat that used to sit on a card or a detail lives here, one tap from
 * the number, so the cards can lead with what it means. The text is in res/values/strings_day_info.xml.
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
    val NORMAL_SCALE: List<String> get() = normalScale()

    /** Recovery's bands, low to high, in the words beside the score ([TodayCopy.recoveryWord]). */
    val RECOVERY_SCALE: List<String> get() = RecoveryBands.WORDS

    /** Charge's bands, low to high. */
    val CHARGE_SCALE: List<String> get() = chargeWords()

    /** The Sleep score's bands, low to high, in the words beside it ([TabCopy.sleepScoreWord]). */
    val SLEEP_SCALE: List<String> get() = SleepBands.WORDS

    /** Sleep regularity's bands, low to high ([SleepRegularity.Band]). */
    val REGULARITY_SCALE: List<String> get() = SleepRegularity.Band.entries.map { it.label }

    private fun normalScale(strings: Strings = Strings.app) = listOf(
        strings.get(R.string.day_info_scale_normal_below),
        strings.get(R.string.day_info_scale_normal_usual),
        strings.get(R.string.day_info_scale_normal_above),
    )

    /** Charge's bands, low to high. */
    fun chargeWords(strings: Strings = Strings.app): List<String> = listOf(
        strings.get(R.string.day_info_scale_charge_low),
        strings.get(R.string.day_info_scale_charge_some),
        strings.get(R.string.day_info_scale_charge_good),
        strings.get(R.string.day_info_scale_charge_full),
    )

    /** Which bar of [SLEEP_SCALE] a Sleep score is in. */
    fun sleepBar(score: Int): Int = SleepBands.bar(score)

    /** Which bar of [REGULARITY_SCALE] a band is. */
    fun regularityBar(band: SleepRegularity.Band): Int = band.ordinal

    /** Which bar of [NORMAL_SCALE] a status against your normal is in; null without a normal. */
    fun normalBar(status: Int?): Int? = status?.let { it.coerceIn(-1, 1) + 1 }

    /** Which bar of [RECOVERY_SCALE] a score is in. */
    fun recoveryBar(score: Int): Int = RecoveryBands.bar(score)

    /** Which bar of [CHARGE_SCALE] a Charge is in, by quarters. */
    fun chargeBar(charge: Int): Int = (charge / 25).coerceIn(0, 3)

    /**
     * The line under the scale: "You are here. Your normal is 44 to 58 ms, from 30 days." Before there's a normal, how
     * far along it is.
     */
    fun normalLine(
        normal: History.Normal?, days: Int, format: (Float) -> String, unit: String, nightly: Boolean, strings: Strings = Strings.app,
    ): String {
        if (normal == null) {
            val waiting = if (nightly) R.string.day_info_normal_waiting_nights else R.string.day_info_normal_waiting_days
            return strings.format(waiting, History.MIN_NORMAL_DAYS, days.coerceAtMost(History.MIN_NORMAL_DAYS))
        }
        val u = if (unit.isEmpty()) "" else if (unit.startsWith("%") || unit.startsWith("°")) unit else " $unit"
        val range = Ranges.of(format(normal.low), format(normal.high)) + u
        val line = if (nightly) R.plurals.day_info_normal_line_nights else R.plurals.day_info_normal_line_days
        return strings.plural(line, normal.days, normal.days, range)
    }

    /** The line under Recovery's and Charge's scales. */
    val HERE: String get() = Strings.app.get(R.string.day_info_here)

    /** A sheet from its strings, the scale as given. */
    private fun info(
        strings: Strings, @StringRes name: Int, @StringRes meaning: Int, scale: List<String>,
        @StringRes how: Int, @StringRes moves: Int, @StringRes off: Int,
    ) = Info(strings.get(name), strings.get(meaning), scale, how = strings.get(how), moves = strings.get(moves), off = strings.get(off))

    fun of(metric: Metric, strings: Strings = Strings.app): Info = when (metric) {
        Metric.HeartRate -> info(
            strings, R.string.day_info_heart_rate_name, R.string.day_info_heart_rate_meaning, normalScale(strings),
            R.string.day_info_heart_rate_how, R.string.day_info_heart_rate_moves, R.string.day_info_heart_rate_off,
        )
        Metric.RestingHr -> info(
            strings, R.string.day_info_resting_hr_name, R.string.day_info_resting_hr_meaning, normalScale(strings),
            R.string.day_info_resting_hr_how, R.string.day_info_resting_hr_moves, R.string.day_info_resting_hr_off,
        )
        Metric.Hrv -> info(
            strings, R.string.day_info_hrv_name, R.string.day_info_hrv_meaning, normalScale(strings),
            R.string.day_info_hrv_how, R.string.day_info_hrv_moves, R.string.day_info_hrv_off,
        )
        Metric.Spo2 -> Info(
            strings.get(R.string.day_info_spo2_name), strings.format(R.string.day_info_spo2_meaning), normalScale(strings),
            how = strings.get(R.string.day_info_spo2_how), moves = strings.get(R.string.day_info_spo2_moves),
            off = strings.get(R.string.day_info_spo2_off),
        )
        Metric.Stress -> info(
            strings, R.string.day_info_stress_name, R.string.day_info_stress_meaning, normalScale(strings),
            R.string.day_info_stress_how, R.string.day_info_stress_moves, R.string.day_info_stress_off,
        )
        Metric.SkinTemperature -> info(
            strings, R.string.day_info_skin_temp_name, R.string.day_info_skin_temp_meaning, normalScale(strings),
            R.string.day_info_skin_temp_how, R.string.day_info_skin_temp_moves, R.string.day_info_skin_temp_off,
        )
        Metric.Breathing -> info(
            strings, R.string.day_info_breathing_name, R.string.day_info_breathing_meaning, normalScale(strings),
            R.string.day_info_breathing_how, R.string.day_info_breathing_moves, R.string.day_info_breathing_off,
        )
        Metric.Steps -> info(
            strings, R.string.day_info_steps_name, R.string.day_info_steps_meaning, normalScale(strings),
            R.string.day_info_steps_how, R.string.day_info_steps_moves, R.string.day_info_steps_off,
        )
    }

    fun of(score: Score, strings: Strings = Strings.app): Info = when (score) {
        Score.Recovery -> Info(
            score.title(strings), strings.get(R.string.day_info_recovery_meaning), RECOVERY_SCALE,
            how = strings.get(R.string.day_info_recovery_how),
            moves = strings.get(R.string.day_info_recovery_moves),
            off = strings.format(R.string.day_info_recovery_off, Recovery.SCORED_NIGHTS, Recovery.SETTLED_NIGHTS),
        )
        Score.Energy -> info(
            strings, score.titleRes, R.string.day_info_energy_meaning, chargeWords(strings),
            R.string.day_info_energy_how, R.string.day_info_energy_moves, R.string.day_info_energy_off,
        )
        Score.Sleep -> info(
            strings, score.titleRes, R.string.day_info_sleep_meaning, SLEEP_SCALE,
            R.string.day_info_sleep_how, R.string.day_info_sleep_moves, R.string.day_info_sleep_off,
        )
        Score.Regularity -> Info(
            score.title(strings), strings.get(R.string.day_info_regularity_meaning), REGULARITY_SCALE,
            how = strings.format(R.string.day_info_regularity_how, SleepRegularity.MIN_VALID_PAIRS / 60),
            moves = strings.get(R.string.day_info_regularity_moves),
            off = strings.get(R.string.day_info_regularity_off),
        )
        Score.Naps -> info(
            strings, score.titleRes, R.string.day_info_naps_meaning, emptyList(),
            R.string.day_info_naps_how, R.string.day_info_naps_moves, R.string.day_info_naps_off,
        )
        // The Activity tab's (spec 5.3). The method notes that sat on the old Activity panels (Paluch, Mifflin-St Jeor,
        // Jurca, FRIEND, acute:chronic, the strap's weekly EPOC range, training effect) live here now.
        Score.HeartPoints -> Info(
            score.title(strings), strings.format(R.string.day_info_heart_points_meaning, ActivityCopy.HEART_POINTS_TARGET), emptyList(),
            how = strings.get(R.string.day_info_heart_points_how),
            moves = strings.format(R.string.day_info_heart_points_moves, ActivityCopy.HEART_POINTS_TARGET),
            off = strings.get(R.string.day_info_heart_points_off),
        )
        Score.ActiveMinutes -> Info(
            score.title(strings), strings.format(R.string.day_info_active_minutes_meaning, Activity.MVPA_WEEKLY_TARGET), emptyList(),
            how = strings.format(
                R.string.day_info_active_minutes_how, Activity.MODERATE_CADENCE, Activity.VIGOROUS_CADENCE,
                Activity.CADENCE_RUN_MINUTES, Activity.HR_RUN_MINUTES, (Activity.HR_MODERATE_HRR * PERCENT).toInt(),
                (Activity.HR_VIGOROUS_HRR * PERCENT).toInt(),
            ),
            moves = strings.get(R.string.day_info_active_minutes_moves),
            off = strings.get(R.string.day_info_active_minutes_off),
        )
        Score.Load -> Info(
            score.title(strings), strings.get(R.string.day_info_load_meaning), ActivityCopy.LOAD_SCALE,
            how = strings.format(R.string.day_info_load_how),
            moves = strings.get(R.string.day_info_load_moves),
            off = strings.get(R.string.day_info_load_off),
        )
        Score.Vo2Max -> info(
            strings, score.titleRes, R.string.day_info_vo2max_meaning, emptyList(),
            R.string.day_info_vo2max_how, R.string.day_info_vo2max_moves, R.string.day_info_vo2max_off,
        )
        Score.Recover -> info(
            strings, score.titleRes, R.string.day_info_recover_meaning, emptyList(),
            R.string.day_info_recover_how, R.string.day_info_recover_moves, R.string.day_info_recover_off,
        )
        Score.Calories -> info(
            strings, score.titleRes, R.string.day_info_calories_meaning, emptyList(),
            R.string.day_info_calories_how, R.string.day_info_calories_moves, R.string.day_info_calories_off,
        )
        Score.Distance -> Info(
            score.title(strings), strings.get(R.string.day_info_distance_meaning), emptyList(),
            how = strings.format(
                R.string.day_info_distance_how, (Activity.WALK_STEP_FRACTION * PERCENT).roundToIntString(),
                (Activity.RUN_STEP_FRACTION * PERCENT).roundToIntString(),
            ),
            moves = strings.get(R.string.day_info_distance_moves),
            off = strings.get(R.string.day_info_distance_off),
        )
    }

    private const val PERCENT = 100

    private fun Float.roundToIntString(): String = if (this % 1f == 0f) toInt().toString() else "%.1f".format(this)

    /** What's particular about today's active minutes: how many came from heart rate. */
    fun activeNotes(fromHeartRate: Int, today: Boolean, strings: Strings = Strings.app): List<String> = listOfNotNull(
        strings.format(if (today) R.string.day_info_active_note_today else R.string.day_info_active_note_that_day, fromHeartRate)
            .takeIf { fromHeartRate > 0 },
    )

    /** What's particular about today's Load: the heart rates it's read between, and the contested ratio. */
    fun loadNotes(restingHr: Float, maxHr: Float, maxTested: Boolean, acwr: Float?, strings: Strings = Strings.app): List<String> =
        listOfNotNull(
            strings.format(
                if (maxTested) R.string.day_info_load_note_between_tested else R.string.day_info_load_note_between,
                restingHr.toInt(), maxHr.toInt(),
            ),
            acwr?.let { strings.format(R.string.day_info_load_note_acwr, it) },
        )

    /** What's particular about the VO2 max estimate: the resting heart rate used, fitness age, or why it's withheld. */
    fun vo2Notes(
        estimate: org.openstrap.app.data.Vo2max.Estimate?,
        withheld: org.openstrap.app.data.Vo2max.Withheld?,
        strings: Strings = Strings.app,
    ): List<String> = buildList {
        estimate?.let { e ->
            add(strings.format(R.string.day_info_vo2_note_resting, e.restingHr.toInt()))
            e.fitnessAge?.let { a ->
                e.sex?.let {
                    val man = it == org.openstrap.app.data.settings.UserProfile.Sex.Male
                    val id = if (man) R.string.day_info_vo2_note_age_man else R.string.day_info_vo2_note_age_woman
                    add(strings.format(id, a.first, a.last))
                }
            }
        }
        withheld?.let { add(strings.format(R.string.day_info_vo2_note_withheld, Vo2maxCopy.reason(it))) }
    }

    /** What's particular about the day's calories: part of the day worn, an unusual total, minutes from heart rate. */
    fun caloriesNotes(e: org.openstrap.app.data.EnergyExpenditure.Day?, strings: Strings = Strings.app): List<String> = buildList {
        e ?: return@buildList
        if (e.partial) add(strings.format(R.string.day_info_calories_note_partial, Units.duration(e.minutesWithData * 60L)))
        if (e.palImplausible) add(strings.get(R.string.day_info_calories_note_unusual))
        if (e.hrAssistedMinutes > 0) {
            add(strings.plural(R.plurals.day_info_calories_note_heart_rate, e.hrAssistedMinutes, e.hrAssistedMinutes))
        }
    }

    /** Spec 6.2's sheet for a night vital opened from the Sleep tab: the metric's, saying where the night's numbers come from. */
    fun ofNight(metric: Metric, strings: Strings = Strings.app): Info =
        of(metric, strings).let { it.copy(how = it.how + " " + nightHow(metric, strings)) }

    private fun nightHow(metric: Metric, strings: Strings): String = strings.get(
        when (metric) {
            Metric.HeartRate -> R.string.day_info_night_heart_rate
            Metric.Spo2 -> R.string.day_info_night_spo2
            Metric.Breathing -> R.string.day_info_night_breathing
            else -> R.string.day_info_night_other
        },
    )

    /** The breathing disturbances sheet: an early estimate, with no scale, since its only words would be apnea bands. */
    val BREATHING_DISTURBANCES: Info get() = breathingDisturbances()

    /** [BREATHING_DISTURBANCES] in [strings]. */
    fun breathingDisturbances(strings: Strings = Strings.app): Info = Info(
        strings.get(R.string.day_info_disturbances_name),
        strings.get(R.string.day_info_disturbances_meaning),
        emptyList(),
        how = strings.format(R.string.day_info_disturbances_how, Recovery.SCORED_NIGHTS),
        moves = strings.get(R.string.day_info_disturbances_moves),
        off = strings.format(R.string.day_info_disturbances_off, org.openstrap.app.data.BodySignalsCopy.DISCLAIMER),
    )

    /** Habit Age's bands, against your age: [habitAgeBar] says which one you're in. */
    val HABIT_AGE_SCALE: List<String> get() = habitAgeScale()

    private fun habitAgeScale(strings: Strings = Strings.app) = listOf(
        strings.get(R.string.day_info_scale_habit_younger),
        strings.get(R.string.day_info_scale_habit_same),
        strings.get(R.string.day_info_scale_habit_older),
    )

    /** Which bar of [HABIT_AGE_SCALE] an estimate [years] younger than your age is in. */
    fun habitAgeBar(years: Int): Int = when {
        years > 0 -> 0
        years < 0 -> 2
        else -> 1
    }

    /** Habit Age's sheet, behind the (i) on its card (spec 5.4): the method, the pace of aging and what moves it. */
    val HABIT_AGE: Info get() = habitAge()

    /** [HABIT_AGE] in [strings]. */
    fun habitAge(strings: Strings = Strings.app): Info = info(
        strings, R.string.day_info_habit_age_name, R.string.day_info_habit_age_meaning, habitAgeScale(strings),
        R.string.day_info_habit_age_how, R.string.day_info_habit_age_moves, R.string.day_info_habit_age_off,
    )

    /** The week story's sheet: what each day's numbers are and how far back the comparisons reach. */
    val WEEK: Info get() = week()

    /** [WEEK] in [strings]. */
    fun week(strings: Strings = Strings.app): Info = info(
        strings, R.string.day_info_week_name, R.string.day_info_week_meaning, emptyList(),
        R.string.day_info_week_how, R.string.day_info_week_moves, R.string.day_info_week_off,
    )

    /** Under the breathing disturbances sheet's title: the night against your usual, or how far the usual has got. */
    fun breathingLine(b: Recovery.BreathingDisturbances, today: Boolean, strings: Strings = Strings.app): String {
        val nightId = if (today) R.string.day_info_disturbances_last_night else R.string.day_info_disturbances_that_night
        val night = strings.format(nightId, b.perHour)
        val then = when (val usual = b.usual) {
            null -> strings.format(
                R.string.day_info_disturbances_learning, b.nights.coerceAtMost(Recovery.SCORED_NIGHTS), Recovery.SCORED_NIGHTS,
            )
            else -> strings.format(R.string.day_info_disturbances_usual, usual)
        }
        return "$night $then"
    }

    /** What's particular about this week's regularity: how much wear it compares, or what it still needs. */
    fun regularityNotes(reg: SleepRegularity.Result?, strings: Strings = Strings.app): List<String> = buildList {
        reg ?: return@buildList
        val hours = reg.validPairs / 60
        if (reg.sri != null) add(strings.format(R.string.day_info_regularity_note_compares, hours))
        else {
            add(strings.format(R.string.day_info_regularity_note_needs, hours, SleepRegularity.MIN_VALID_PAIRS / 60))
            reg.midpointSdMinutes?.let {
                add(strings.format(R.string.day_info_regularity_note_midpoint, Units.duration(Math.round(it) * 60L)))
            }
        }
    }

    /** What's particular about the night's Sleep score: the sleep need the guidance measures against. */
    fun sleepNotes(needMinutes: Int, strings: Strings = Strings.app): List<String> =
        listOf(strings.format(R.string.day_info_sleep_note_need, Units.duration(needMinutes * 60L)))

    /**
     * What's particular about today's Recovery, for its info sheet: the strap's own score beside ours, and anything that
     * made tonight's reading less sure. Each is a sentence; none when there's nothing to say.
     */
    fun recoveryNotes(ready: Recovery.Readiness?, strapScore: Int?, strings: Strings = Strings.app): List<String> = buildList {
        if (strapScore != null) add(strings.format(R.string.day_info_recovery_note_strap, strapScore))
        ready ?: return@buildList
        if (ready.capped) add(strings.get(R.string.day_info_recovery_note_capped))
        ready.fallbackHrv?.let { add(strings.format(R.string.day_info_recovery_note_hrv, it.toInt())) }
        ready.fallbackRestingHr?.let { add(strings.format(R.string.day_info_recovery_note_resting_hr, it.toInt())) }
        if (ready.hrvWeekThin) add(strings.format(R.string.day_info_recovery_note_week_thin, ready.hrvWeekNights))
    }

    /** What's particular about today's Charge: an estimated start after a night with the strap off. */
    fun chargeNotes(c: Recovery.ChargeDay?, strings: Strings = Strings.app): List<String> = listOfNotNull(
        strings.get(R.string.day_info_charge_note_estimated).takeIf { c?.strapOffOvernight == true },
    )
}
