package org.openstrap.app.presentation

import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.Vo2max
import org.openstrap.app.data.settings.Goals
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/*
 * Text the data package used to write itself (audit finding A10), moved here by task 3E. The data side keeps a thin
 * function that calls these, for the ui callers that still read its text. Written in its own file so the coordinator
 * can fold it into the rest of presentation/.
 */

/** A change against your usual, with its sign: "+3", "−3", or "±0" when it rounds to nothing, never "-0". */
object Signed {
    fun of(v: Int, locale: Locale = Locale.getDefault()): String {
        val text = String.format(locale, "%d", abs(v))
        return when {
            v > 0 -> "+$text"
            v < 0 -> "−$text"
            else -> "±$text"
        }
    }

    /** [v] to [decimals] places with its sign: "+0.4", "−3", "±0.0". */
    fun of(v: Float, decimals: Int, locale: Locale = Locale.getDefault()): String {
        val text = String.format(locale, "%.${decimals}f", abs(v))
        // Decided on the rounded value, not the text, so a comma decimal mark can't make "0,0" read as a change.
        val rounded = (abs(v) * TEN.pow(decimals)).roundToLong()
        return when {
            rounded == 0L -> "±$text"
            v > 0 -> "+$text"
            else -> "−$text"
        }
    }

    private const val TEN = 10f
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
