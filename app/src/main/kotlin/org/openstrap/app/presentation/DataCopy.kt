package org.openstrap.app.presentation

import org.openstrap.app.R
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.Vo2max
import org.openstrap.app.data.settings.Goals
import org.openstrap.app.i18n.Strings
import java.time.ZoneId
import java.util.Locale

/*
 * Text the data package used to write itself (audit finding A10). Data now hands over values only, and the screens
 * ask here for the words. The text is in res/values/strings_core_data.xml.
 */

/** A recovery contributor's line under its name in the score detail: "52 ms, +4% vs usual", "7 h 40 asleep". */
object RecoveryCopy {
    fun detail(d: Recovery.Detail, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String = when (d) {
        is Recovery.Detail.Hrv ->
            strings.format(R.string.core_data_recovery_hrv, whole(d.ms, locale), Signed.of(d.percentVsUsual, locale))
        is Recovery.Detail.RestingHr ->
            strings.format(R.string.core_data_recovery_resting_hr, whole(d.bpm, locale), Signed.of(d.vsUsual, 0, locale))
        is Recovery.Detail.Asleep -> strings.format(R.string.core_data_recovery_asleep, minutes(d.minutes, locale))
        is Recovery.Detail.SleepDebt -> if (d.minutes == 0) strings.get(R.string.core_data_recovery_no_debt)
        else strings.format(R.string.core_data_recovery_debt, minutes(d.minutes, locale))
        is Recovery.Detail.LowestHeartRate ->
            strings.format(R.string.core_data_recovery_lowest_hr, minutes(d.minutesBeforeWaking, locale))
        is Recovery.Detail.Temperature -> strings.format(R.string.core_data_recovery_temperature, Signed.of(d.vsUsual, 1, locale))
        is Recovery.Detail.Breathing -> strings.format(R.string.core_data_recovery_breathing, Signed.of(d.vsUsual, 1, locale))
        is Recovery.Detail.BloodOxygen -> strings.format(
            R.string.core_data_recovery_blood_oxygen,
            String.format(locale, "%.1f", d.percent),
            Signed.of(d.vsUsual, 1, locale),
        )
        is Recovery.Detail.Load -> strings.format(R.string.core_data_recovery_load, whole(d.yesterday, locale), whole(d.usual, locale))
    }

    private fun whole(n: Int, locale: Locale) = String.format(locale, "%d", n)

    private fun minutes(m: Int, locale: Locale) = Units.duration(m * Units.MINUTE, locale)
}

/** Why a sleep edit can't be saved ([SleepEdits.check]), as the edit sheet says it. */
object SleepEditCopy {
    fun text(
        p: SleepEdits.Problem,
        zone: ZoneId,
        use24Hour: Boolean = ClockSetting.use24Hour,
        locale: Locale = Locale.getDefault(),
        strings: Strings = Strings.app,
    ): String = when (p) {
        SleepEdits.Problem.WakeBeforeSleep -> strings.get(R.string.core_data_edit_wake_before_sleep)
        is SleepEdits.Problem.TooShort ->
            strings.get(if (p.nap) R.string.core_data_edit_nap_too_short else R.string.core_data_edit_sleep_too_short)
        is SleepEdits.Problem.TooLong ->
            strings.get(if (p.nap) R.string.core_data_edit_nap_too_long else R.string.core_data_edit_sleep_too_long)
        SleepEdits.Problem.Future -> strings.get(R.string.core_data_edit_future)
        SleepEdits.Problem.TooFarFromStrap -> strings.get(R.string.core_data_edit_too_far)
        is SleepEdits.Problem.RunsInto -> strings.format(
            if (p.nap) R.string.core_data_edit_runs_into_nap else R.string.core_data_edit_runs_into_sleep,
            TimeOfDay.format(p.start, zone, use24Hour, locale),
        )
    }
}

/** The VO2max estimate's words: why there is none, and its FRIEND band. */
object Vo2maxCopy {
    fun reason(w: Vo2max.Withheld, strings: Strings = Strings.app): String = strings.get(
        when (w) {
            Vo2max.Withheld.Age -> R.string.core_data_vo2_needs_age
            Vo2max.Withheld.Sex -> R.string.core_data_vo2_needs_sex
            Vo2max.Withheld.Body -> R.string.core_data_vo2_needs_body
            Vo2max.Withheld.Activity -> R.string.core_data_vo2_needs_activity
            Vo2max.Withheld.Medication -> R.string.core_data_vo2_medication
            Vo2max.Withheld.RestingHr -> R.string.core_data_vo2_needs_resting_hr
        },
    )

    fun band(b: Vo2max.Friend.Band, strings: Strings = Strings.app): String = when (b) {
        Vo2max.Friend.Band.Below30 -> strings.get(R.string.core_data_vo2_band_below_30)
        Vo2max.Friend.Band.P30to50 -> strings.get(R.string.core_data_vo2_band_30_50)
        Vo2max.Friend.Band.P50to70 -> strings.get(R.string.core_data_vo2_band_50_70)
        Vo2max.Friend.Band.P70to90 -> strings.get(R.string.core_data_vo2_band_70_90)
        Vo2max.Friend.Band.Top10 -> strings.format(R.string.core_data_vo2_band_top_10, TOP_BAND_PERCENT)
    }

    /** The top band's share of people, as its name says it. */
    private const val TOP_BAND_PERCENT = 10

    /** The estimate from your profile as a row's value: "40 to 45". */
    fun estimate(low: Int, high: Int, locale: Locale = Locale.getDefault()): String = Ranges.numbers(low, high, locale)

    /**
     * The estimate from your profile as a detail's hero: the low end in dots and the rest beside them, "40" and
     * "to 45 ml/kg/min", since the dots draw digits only and a range never takes a dash.
     */
    fun estimateHero(low: Int, high: Int, strings: Strings = Strings.app): Pair<String, String> =
        dotFormat("%d", low) to strings.format(R.string.core_data_vo2_estimate_rest, Units.count(high))
}

/** The goals sheet's words. */
object GoalCopy {
    /** "8,000 steps, 300 active kcal, 8 h sleep, 10 active hours", the numbers in the phone's language. */
    fun summary(g: Goals, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String = strings.format(
        R.string.core_data_goal_summary,
        Units.count(g.steps.toLong(), locale),
        Units.count(g.calories.toLong(), locale),
        Units.duration(g.sleepSeconds, locale),
        String.format(locale, "%d", g.activeHours),
    )

    /** Under the calorie field when a typed value is outside [range]: "Pick a number from 200 to 3,000". */
    fun outOfRange(range: IntRange, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        strings.format(
            R.string.core_data_goal_out_of_range,
            Units.count(range.first.toLong(), locale),
            Units.count(range.last.toLong(), locale),
        )
}
