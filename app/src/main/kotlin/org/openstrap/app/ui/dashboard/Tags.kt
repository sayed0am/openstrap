package org.openstrap.app.ui.dashboard

import androidx.compose.runtime.Immutable
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.Units

/*
 * News tags (spec 2.5, Tags): a filled pill next to a number that says what's new about it. A goal tag when a goal is
 * met, climbing a ladder past it, or an off tag when the value is off your normal. One per number, the goal first.
 * A usual value carries none, so a calm day shows no tags. Plain functions, so the rules are tested without a screen.
 */

/** A value against your normal or usual (spec 2.1). Off either way lights the rim. */
enum class OffStatus { Usual, Above, Below;

    val off: Boolean get() = this != Usual

    companion object {
        /** From `History.status`'s -1, 0 or 1. */
        fun of(sign: Int): OffStatus = when { sign > 0 -> Above; sign < 0 -> Below; else -> Usual }
    }
}

/** A tag's words and whether it's about a goal (true) or a value off your normal. */
@Immutable
data class NewsTag(val text: String, val goal: Boolean)

/** The goal ladder, highest first (spec 2.5, Goal tags). */
enum class GoalRung { PersonalBest, BestThisMonth, DoubleGoal, PastGoal, InARow, GoalMet }

/** What a goal tag is about: the cap differs (spec 2.5). */
enum class GoalMetric { Steps, Calories, ActiveHours, Workouts, Sleep }

/**
 * A goal's day: the [value] against the [goal], the [best] day ever before today and the best of the 30 days before
 * today ([monthBest]), null when not known, and the [streak] of days running the goal is met, today included.
 */
data class GoalFacts(val value: Int, val goal: Int, val best: Int? = null, val monthBest: Int? = null, val streak: Int = 0)

/** At 125% of the goal or more, the tag says how far past it. */
private const val PAST_GOAL_SHARE = 1.25

/** The streak that earns a tag. */
const val STREAK_DAYS = 3

/**
 * The highest rung of the goal ladder that [facts] reach, or null below the goal. Sleep stops at Goal met: sleeping
 * far past the goal isn't an achievement. Calories and active hours stop there too on a day [easeOff] (Load above
 * your usual or Recovery below it), so a tag never cheers on what another card says to ease off.
 */
fun goalRung(metric: GoalMetric, facts: GoalFacts, easeOff: Boolean = false): GoalRung? {
    val (value, goal) = facts
    if (goal <= 0 || value < goal) return null
    if (metric == GoalMetric.Sleep) return GoalRung.GoalMet
    if (easeOff && (metric == GoalMetric.Calories || metric == GoalMetric.ActiveHours)) return GoalRung.GoalMet
    return when {
        facts.best != null && facts.best > 0 && value > facts.best -> GoalRung.PersonalBest
        facts.monthBest != null && facts.monthBest > 0 && value > facts.monthBest -> GoalRung.BestThisMonth
        value >= goal * 2 -> GoalRung.DoubleGoal
        value >= goal * PAST_GOAL_SHARE -> GoalRung.PastGoal
        facts.streak >= STREAK_DAYS -> GoalRung.InARow
        else -> GoalRung.GoalMet
    }
}

/**
 * [goalRung] in words. [past] formats the amount past the goal in the metric's unit (a plain count by default, so
 * "+2,140 past goal").
 */
fun goalTag(
    metric: GoalMetric, facts: GoalFacts, easeOff: Boolean = false, strings: Strings = Strings.app,
    locale: Locale = Locale.getDefault(), past: (Int) -> String = { Units.count(it, locale) },
): NewsTag? = goalRung(metric, facts, easeOff)?.let { rung ->
    val text = when (rung) {
        GoalRung.PersonalBest -> strings.get(R.string.glow_tag_personal_best)
        GoalRung.BestThisMonth -> strings.get(R.string.glow_tag_best_month)
        GoalRung.DoubleGoal -> strings.get(R.string.glow_tag_double_goal)
        GoalRung.PastGoal -> strings.format(R.string.glow_tag_past_goal, past(facts.value - facts.goal))
        GoalRung.InARow -> strings.plural(R.plurals.glow_tag_in_a_row, facts.streak, facts.streak)
        GoalRung.GoalMet -> strings.get(R.string.glow_tag_goal_met)
    }
    NewsTag(text, goal = true)
}

/** Which words a metric's off tag uses (spec 2.5, Off tags). */
enum class OffWords {
    /** Resting heart rate, HRV, heart rate, breathing rate, skin temperature: Above or Below normal. */
    Normal,

    /** Blood oxygen: only Below normal is news. */
    LowOnly,

    /** Recovery, Stress, Load: Above or Below usual. */
    Usual,

    /** Last night: Long or Short. */
    Length,

    /** Sleep regularity: Later or Earlier. */
    Timing,

    /** Habit Age against your own age: Older or Younger. */
    Age,

    /** Breathing disturbances: More than usual, and fewer isn't news. */
    MoreOnly,

    /** VO2 max as a trend: Up or Down. */
    Trend,
}

/** The off tag for a value [status] against your normal, in [words], or null when usual or not news. */
fun offTag(words: OffWords, status: OffStatus, strings: Strings = Strings.app): NewsTag? {
    val above = status == OffStatus.Above
    val id = when {
        !status.off -> return null
        words == OffWords.LowOnly -> if (above) return null else R.string.glow_tag_below_normal
        words == OffWords.MoreOnly -> if (above) R.string.glow_tag_more_than_usual else return null
        else -> when (words) {
            OffWords.Normal -> if (above) R.string.glow_tag_above_normal else R.string.glow_tag_below_normal
            OffWords.Usual -> if (above) R.string.glow_tag_above_usual else R.string.glow_tag_below_usual
            OffWords.Length -> if (above) R.string.glow_tag_long else R.string.glow_tag_short
            OffWords.Timing -> if (above) R.string.glow_tag_later else R.string.glow_tag_earlier
            OffWords.Age -> if (above) R.string.glow_tag_older else R.string.glow_tag_younger
            OffWords.Trend -> if (above) R.string.glow_tag_up else R.string.glow_tag_down
            OffWords.LowOnly, OffWords.MoreOnly -> error("handled above")
        }
    }
    return NewsTag(strings.get(id), goal = false)
}

/** Whether an off value lights the rim: only when its off tag is news (high blood oxygen isn't). */
fun rimLit(words: OffWords, status: OffStatus): Boolean = offTag(words, status, NoStrings) != null

/** One tag per number: the goal wins over off. */
fun newsTag(goal: NewsTag?, off: NewsTag?): NewsTag? = goal ?: off

/** Strings that are never read, for [rimLit], which only asks whether a tag exists. */
private object NoStrings : Strings {
    override fun get(id: Int) = ""
    override fun format(id: Int, vararg args: Any) = ""
    override fun plural(id: Int, count: Int, vararg args: Any) = ""
}

/** A body signal's off words: blood oxygen only says Below, the rest Above or Below normal. */
fun bodyWords(metric: org.openstrap.app.presentation.Metric): OffWords =
    if (metric == org.openstrap.app.presentation.Metric.Spo2) OffWords.LowOnly else OffWords.Normal

/** A body signal's off tag from `History.status`'s sign, null when usual or not known. */
fun bodyTag(metric: org.openstrap.app.presentation.Metric, sign: Int?, strings: Strings = Strings.app): NewsTag? =
    offTag(bodyWords(metric), OffStatus.of(sign ?: 0), strings)
