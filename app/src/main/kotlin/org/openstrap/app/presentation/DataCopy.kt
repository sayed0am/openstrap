package org.openstrap.app.presentation

import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.Vo2max
import org.openstrap.app.data.settings.Goals
import java.time.ZoneId
import java.util.Locale

/*
 * Text the data package used to write itself (audit finding A10). Data now hands over values only, and the screens
 * ask here for the words.
 */

/** A recovery contributor's line under its name in the score detail: "52 ms, +4% vs usual", "7 h 40 asleep". */
object RecoveryCopy {
    fun detail(d: Recovery.Detail, locale: Locale = Locale.getDefault()): String = when (d) {
        is Recovery.Detail.Hrv -> "${whole(d.ms, locale)} ms, ${Signed.of(d.percentVsUsual, locale)}% vs usual"
        is Recovery.Detail.RestingHr -> "${whole(d.bpm, locale)} bpm, ${Signed.of(d.vsUsual, 0, locale)} vs usual"
        is Recovery.Detail.Asleep -> "${minutes(d.minutes, locale)} asleep"
        is Recovery.Detail.SleepDebt -> if (d.minutes == 0) "No sleep debt" else "${minutes(d.minutes, locale)} of sleep debt"
        is Recovery.Detail.LowestHeartRate -> "Lowest heart rate ${minutes(d.minutesBeforeWaking, locale)} before waking"
        is Recovery.Detail.Temperature -> "${Signed.of(d.vsUsual, 1, locale)} °C vs usual"
        is Recovery.Detail.Breathing -> "${Signed.of(d.vsUsual, 1, locale)} /min vs usual"
        is Recovery.Detail.BloodOxygen ->
            "${String.format(locale, "%.1f", d.percent)} %, ${Signed.of(d.vsUsual, 1, locale)} vs usual"
        is Recovery.Detail.Load -> "Load ${whole(d.yesterday, locale)}, usual ${whole(d.usual, locale)}"
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
    ): String = when (p) {
        SleepEdits.Problem.WakeBeforeSleep -> "Waking up has to come after falling asleep."
        is SleepEdits.Problem.TooShort -> if (p.nap) "A nap needs at least 5 minutes." else "Sleep needs at least an hour."
        is SleepEdits.Problem.TooLong -> if (p.nap) "A nap can be 4 hours at most." else "Sleep can be 16 hours at most."
        SleepEdits.Problem.Future -> "That's later than now."
        SleepEdits.Problem.TooFarFromStrap -> "Keep it within 3 hours of what the strap saw."
        is SleepEdits.Problem.RunsInto ->
            "That runs into your ${if (p.nap) "nap" else "sleep"} at ${TimeOfDay.format(p.start, zone, use24Hour, locale)}."
    }
}

/** The VO2max estimate's words: why there is none, and its FRIEND band. */
object Vo2maxCopy {
    fun reason(w: Vo2max.Withheld): String = when (w) {
        Vo2max.Withheld.Age -> "Needs an age between 20 and 79."
        Vo2max.Withheld.Sex -> "Needs your sex: the equation differs for women and men."
        Vo2max.Withheld.Body -> "Needs a weight and height entered in the last 90 days."
        Vo2max.Withheld.Activity -> "Answer the activity question (asked every 6 months)."
        Vo2max.Withheld.Medication ->
            "Not shown while taking beta-blockers or during pregnancy: resting heart rate doesn't reflect fitness then."
        Vo2max.Withheld.RestingHr -> "Needs at least 5 nights of resting heart rate in the last 2 weeks."
    }

    fun band(b: Vo2max.Friend.Band): String = when (b) {
        Vo2max.Friend.Band.Below30 -> "Below the 30th percentile"
        Vo2max.Friend.Band.P30to50 -> "30th to 50th percentile"
        Vo2max.Friend.Band.P50to70 -> "50th to 70th percentile"
        Vo2max.Friend.Band.P70to90 -> "70th to 90th percentile"
        Vo2max.Friend.Band.Top10 -> "Top 10 %"
    }

    /** The estimate from your profile as a row's value: "40 to 45". */
    fun estimate(low: Int, high: Int, locale: Locale = Locale.getDefault()): String = Ranges.numbers(low, high, locale)

    /**
     * The estimate from your profile as a detail's hero: the low end in dots and the rest beside them, "40" and
     * "to 45 ml/kg/min", since the dots draw digits only and a range never takes a dash.
     */
    fun estimateHero(low: Int, high: Int): Pair<String, String> = dotFormat("%d", low) to "to ${Units.count(high)} ml/kg/min"
}

/** The goals sheet's words. */
object GoalCopy {
    /** "8,000 steps, 300 active kcal, 8 h sleep, 10 active hours", the numbers in the phone's language. */
    fun summary(g: Goals, locale: Locale = Locale.getDefault()): String =
        "${Units.count(g.steps.toLong(), locale)} steps, ${Units.count(g.calories.toLong(), locale)} active kcal, " +
            "${Units.duration(g.sleepSeconds, locale)} sleep, ${String.format(locale, "%d", g.activeHours)} active hours"

    /** Under the calorie field when a typed value is outside [range]: "Pick a number from 200 to 3,000". */
    fun outOfRange(range: IntRange, locale: Locale = Locale.getDefault()): String =
        "Pick a number from ${Units.count(range.first.toLong(), locale)} to ${Units.count(range.last.toLong(), locale)}"
}
