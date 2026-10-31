package org.openstrap.app.widget

import org.openstrap.app.data.Summaries
import org.openstrap.app.ui.dashboard.ActivityCopy
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.glow.DotSize
import org.openstrap.app.ui.glow.draw.WeekBarsLook
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil

/** How a day of this week stands against the goal, which colours its bar on the Large. */
enum class StepsDay {
    /** At or past the goal: Steps' lime. */
    Met,

    /** Under it: white at 30%. */
    Missed,

    /** Today, met or not yet: white. */
    Today,

    /** Later this week: a 4 dp stub at 12%. */
    ToCome,

    /** A day gone with no steps recorded (the strap not worn): no bar, and it doesn't count. */
    NoRecord,
}

/** A day's bar in the Large's week: its letter, its steps (null without any) and how it stands. */
data class StepsWeekDay(val date: LocalDate, val letter: String, val steps: Int?, val state: StepsDay)

/**
 * What the Steps widget shows (widgets spec 3.3), the same model at every size: the Small shows the title, [stepsText]
 * with [word], the goal bar and [share]; the Wide adds [goalNote], [toGo] and the day's [line]; the Large adds the
 * [week] and [weekLine]. There's no waiting state: before any steps today it shows 0 against the goal, as Today's
 * Steps card does.
 */
data class StepsWidgetModel(
    /** Today, which a tap opens the Steps detail on. */
    val date: LocalDate,
    val title: String,
    val goal: Int,
    /** "Goal 10,000". */
    val goalNote: String,
    val steps: Int,
    /** The number in dots, "8,432", and the word under or beside it. */
    val stepsText: String,
    val word: String,
    /** "1,568 to go", or "Goal met" past the goal. */
    val toGo: String,
    /** The Small's line under the bar, as Today's card says it: "84% of your goal", "Goal met". */
    val share: String,
    /** The goal bar, 0 to 1: full past the goal. */
    val progress: Float,
    /** Today's running total at the end of each hour so far, the Steps card's line ([Summaries.runningSteps]). */
    val line: List<Float?>,
    /** The line's value axis tops out here, as the card's: the goal, or the day when it's past it. */
    val lineMax: Float,
    /** Monday to Sunday. */
    val week: List<StepsWeekDay>,
    /** "Goal met 2 of 6 days". */
    val weekLine: String,
) {
    val goalMet: Boolean get() = steps >= goal

    /** The line shows once there's a step to draw, as on Today's card. */
    val showLine: Boolean get() = steps > 0 && line.size >= 2

    /** What TalkBack reads for the dotted number: "8,432 steps today, 84% of your goal". */
    val description: String get() = "$stepsText steps today, ${share.replaceFirstChar { it.lowercase() }}"

    companion object {
        const val TITLE = "Steps"
        const val TODAY = "today"
        const val GOAL_MET = "Goal met"
        const val THIS_WEEK = "This week"

        /**
         * The widget for [today] at [hour]: [steps] so far (the live total, as Today's card shows it), the day's
         * [hourly] steps from the synced minutes for the line, the [goal] from settings, and [past] days' steps this
         * week (days without a record absent).
         */
        fun from(
            today: LocalDate, steps: Int, hourly: List<Float>, hour: Int, goal: Int, past: Map<LocalDate, Int>,
            locale: Locale = Locale.getDefault(),
        ): StepsWidgetModel {
            val line = Summaries.runningSteps(hourly, hour)
            val week = week(today, steps, goal, past, locale)
            val counted = week.filter { it.state != StepsDay.ToCome && it.state != StepsDay.NoRecord }
            val met = counted.count { (it.steps ?: 0) >= goal }
            return StepsWidgetModel(
                date = today,
                title = TITLE,
                goal = goal,
                goalNote = "Goal ${format(goal)}",
                steps = steps,
                stepsText = format(steps),
                word = TODAY,
                toGo = toGo(steps, goal),
                share = ActivityCopy.goalShare(steps, goal),
                progress = if (goal <= 0) 1f else (steps / goal.toFloat()).coerceIn(0f, 1f),
                line = line,
                // The goal at the top, so the line fills toward it. Past the goal the day's total is the top.
                lineMax = maxOf(goal.toFloat(), steps.toFloat(), line.filterNotNull().maxOrNull() ?: 0f),
                week = week,
                weekLine = weekLine(met, counted.size),
            )
        }

        /** "1,568 to go", or "Goal met" from the goal on. */
        fun toGo(steps: Int, goal: Int): String = if (steps >= goal) GOAL_MET else "${format(goal - steps)} to go"

        /** "Goal met 2 of 6 days", "Goal met 0 of 1 day". */
        fun weekLine(met: Int, of: Int): String = "Goal met $met of $of ${if (of == 1) "day" else "days"}"

        /** Monday to Sunday of [today]'s week, each day as it stands against [goal]. */
        fun week(today: LocalDate, steps: Int, goal: Int, past: Map<LocalDate, Int>, locale: Locale): List<StepsWeekDay> {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return (0L until 7L).map { monday.plusDays(it) }.map { d ->
                val letter = d.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
                when {
                    d > today -> StepsWeekDay(d, letter, null, StepsDay.ToCome)
                    d == today -> StepsWeekDay(d, letter, steps, StepsDay.Today)
                    else -> past[d]?.let { s -> StepsWeekDay(d, letter, s, if (s >= goal) StepsDay.Met else StepsDay.Missed) }
                        ?: StepsWeekDay(d, letter, null, StepsDay.NoRecord)
                }
            }
        }

        /**
         * Each day's bar as a share of the week's top, the goal or the most steps in a day if that's more, so a met day
         * stands taller than a missed one. Null where there's no bar to draw (days to come get a stub instead).
         */
        fun barValues(week: List<StepsWeekDay>, goal: Int): List<Float?> {
            val top = maxOf(goal, week.maxOf { it.steps ?: 0 }).coerceAtLeast(1).toFloat()
            return week.map { d -> d.steps?.takeIf { d.state != StepsDay.ToCome }?.let { it / top } }
        }

        private fun format(n: Int) = Metric.Steps.format(n.toFloat())
    }
}

/**
 * The sums that fit the Steps widget into its smallest cell at any system font size (spec 2.1): what each size keeps
 * when it can't hold everything, how small the dots go, how tall the line can be. Widths and heights in dp, worked
 * out at the bucket's content size ([WidgetFrame.contentWidth]); text with [WidgetText]'s rough sums.
 */
object StepsLayout {
    /** The dots on a Small, and on a Wide or Large (spec 3). */
    val SMALL_DOT = DotSize.Edge.dot.value
    val WIDE_DOT = DotSize.Second.dot.value

    /** Small: between the number and "today", and either side of the goal bar. */
    const val SMALL_WORD_GAP_DP = 4f
    const val SMALL_BAR_GAP_DP = 6f

    /** Wide: between the number and "today", and before what's to go on the right. */
    const val WORD_GAP_DP = 8f
    const val SIDE_GAP_DP = 12f

    /** The goal bar's height: the Small's, and the Wide's and Large's when a large font drops their line. */
    const val BAR_DP = 4f

    /**
     * The line at its tallest and shortest worth drawing. It is the Wide's and Large's only progress: its top is the
     * goal, so it fills its height as the day reaches it. It takes the mockup's line, gap and goal bar together.
     */
    const val LINE_DP = 52f
    const val MIN_LINE_DP = 20f

    /** The least room between the rows that share out the spare height. */
    const val MIN_GAP_DP = 4f

    /** Between "This week" and how many days met the goal, on their one line. */
    const val WEEK_GAP_DP = 12f

    /** Between that line and the bars under it. */
    const val WEEK_LINE_GAP_DP = 6f

    /** [text]'s dots at [dot] dp, wide and high. */
    fun dotsWidth(text: String, dot: Float): Float = WidgetDots.width(text, dot)
    fun dotsHeight(dot: Float): Float = WidgetDots.height(dot)

    /** The dot that fits [text] into [room] dp, at most [dot]. */
    fun fitDot(text: String, room: Float, dot: Float): Float = WidgetDots.fit(text, room, dot)

    /**
     * What the Small keeps: the dot it draws at, whether "today" and the share line fit, its [top] row and how its
     * three blocks (the top row, the number with "today", the bar with the share) share the height.
     */
    data class Small(
        val dot: Float, val word: Boolean, val share: Boolean,
        val top: TopRow = TopRow(null, false, 0f), val spread: Spread = Spread(0f, 0),
    )


    /**
     * The Small in [width] by [height] dp: the top row (the title, with the strap [status] button when it shows), the
     * number, the bar always, then the share line and "today" while they fit, the share first as it says how far. The
     * share only on one line. The blocks share the spare height, the dots growing from 3 dp until no gap is over
     * [Spread.TARGET_GAP_DP]; at least [SMALL_BAR_GAP_DP] between them all.
     */
    fun small(stepsText: String, share: String, width: Float, height: Float, fontScale: Float, status: StatusLine? = null): Small {
        val top = TopRow.of(StepsWidgetModel.TITLE, null, status, width, fontScale, withWords = false)
        val shareFits = WidgetText.width(share, WidgetText.SECONDARY_SP, fontScale) <= width
        fun at(dot: Float, word: Boolean, withShare: Boolean): Small {
            val d = fitDot(stepsText, width, dot)
            val number = dotsHeight(d) + (if (word) SMALL_WORD_GAP_DP + WidgetText.line(WidgetText.BODY_SP, fontScale) else 0f)
            val foot = BAR_DP + (if (withShare) SMALL_BAR_GAP_DP + WidgetText.line(WidgetText.SECONDARY_SP, fontScale) else 0f)
            return Small(d, word, withShare, top, Spread.of(top.height, number, foot))
        }
        val gap = SMALL_BAR_GAP_DP / 2
        for ((withShare, word) in listOf(true to true, true to false, false to true, false to false)) {
            if (withShare && !shareFits) continue
            val base = at(SMALL_DOT, word, withShare)
            if (!base.spread.fits(height, gap)) continue
            return WidgetHero.ladder(WidgetSizes.Small, height).map { at(it, word, withShare) }.firstOrNull { it.spread.fits(height, gap) } ?: base
        }
        return at(SMALL_DOT, word = false, withShare = false)
    }

    /** The Wide's and Large's number row: the dot it draws at and whether "today" fits beside it. */
    data class NumberRow(val dot: Float, val word: Boolean) {
        fun height(fontScale: Float): Float =
            maxOf(dotsHeight(dot), WidgetText.line(WidgetText.BODY_SP, fontScale), WidgetText.line(WidgetText.SECONDARY_SP, fontScale))
    }

    /**
     * The number with "today" and [toGo] on the right, in [width] dp: at 3.6 dp dots when all fit, the dots smaller
     * down to the Small's 3 dp to keep "today", then without "today" at up to 3.6 dp.
     */
    fun numberRow(stepsText: String, toGo: String, width: Float, fontScale: Float, maxDot: Float = WIDE_DOT): NumberRow {
        val right = SIDE_GAP_DP + WidgetText.width(toGo, WidgetText.SECONDARY_SP, fontScale)
        val word = WORD_GAP_DP + WidgetText.width(StepsWidgetModel.TODAY, WidgetText.BODY_SP, fontScale)
        val withWord = fitDot(stepsText, width - right - word, maxDot)
        if (withWord >= SMALL_DOT) return NumberRow(withWord, word = true)
        return NumberRow(fitDot(stepsText, width - right, maxDot), word = false)
    }

    /**
     * How tall the Wide's line can be in [height] dp under the title and [row]: up to [LINE_DP], null when less than
     * [MIN_LINE_DP] is left, so a large font drops the line before anything clips.
     */
    fun wideLine(height: Float, row: NumberRow, fontScale: Float): Float? =
        lineIn(height - WidgetText.line(WidgetText.TITLE_SP, fontScale) - row.height(fontScale) - 2 * MIN_GAP_DP)

    /** The Large's week: the bars' column width, whether "This week" fits beside the count, and the height. */
    data class Week(val column: Float, val title: Boolean, val height: Float)

    /**
     * This week in [width] dp: one line, "This week" on the left and [weekLine] on the right, never wrapping (a
     * large font drops "This week" first), then the bars across the width, columns up to [WeekBarsLook.COLUMN_DP].
     */
    fun week(weekLine: String, width: Float, fontScale: Float, days: Int = 7): Week {
        val sp = WidgetText.SECONDARY_SP
        val title = WidgetText.width(StepsWidgetModel.THIS_WEEK, sp, fontScale) + WEEK_GAP_DP + WidgetText.width(weekLine, sp, fontScale) <= width
        val column = WeekBarsLook.column(days, width)
        val bars = WeekBarsLook.HEIGHT_DP + 4f + WidgetText.line(WidgetText.CAPTION_SP, fontScale)
        return Week(column, title, WidgetText.line(sp, fontScale) + WEEK_LINE_GAP_DP + bars)
    }

    /** How tall the Large's line can be in [height] dp, as [wideLine] with the [week] row under it too. */
    fun largeLine(height: Float, row: NumberRow, week: Week, fontScale: Float): Float? =
        lineIn(height - WidgetText.line(WidgetText.TITLE_SP, fontScale) - row.height(fontScale) - week.height - 3 * MIN_GAP_DP)

    private fun lineIn(room: Float): Float? = room.coerceAtMost(LINE_DP).takeIf { it >= MIN_LINE_DP }

    /** As far as the line grows on a tall cell before the gaps do. */
    const val MAX_LINE_DP = 72f

    /**
     * The Wide or Large laid out: the [top] row, the number [row], the line's height ([line], null for the goal bar
     * instead), the Large's [week], and how the blocks share the height ([spread]).
     */
    data class Fit(val top: TopRow, val row: NumberRow, val line: Float?, val week: Week?, val spread: Spread)

    /**
     * The Wide in [width] by [height] dp: the top row (the title with the goal, or the strap [status] line in the
     * title's place), the number row, then the day's line as tall as fits up to [LINE_DP], growing to [MAX_LINE_DP]
     * on a tall cell so no gap passes [Spread.TARGET_GAP_DP]; the goal bar when the line doesn't fit.
     */
    fun wide(m: StepsWidgetModel, width: Float, height: Float, fontScale: Float, status: StatusLine? = null): Fit =
        fit(m, width, height, fontScale, status, large = false)

    /** The Large: as the Wide with this week under the line. */
    fun large(m: StepsWidgetModel, width: Float, height: Float, fontScale: Float, status: StatusLine? = null): Fit =
        fit(m, width, height, fontScale, status, large = true)

    private fun fit(m: StepsWidgetModel, width: Float, height: Float, fontScale: Float, status: StatusLine?, large: Boolean): Fit {
        val top = TopRow.of(m.title, m.goalNote, status, width, fontScale, withWords = true)
        val week = if (large) week(m.weekLine, width, fontScale, m.week.size) else null
        val inner = if (large) 3 else 2
        // The number at the size every widget's takes here, smaller while the rest doesn't fit with the line's least.
        val rows = WidgetHero.ladder(if (large) WidgetSizes.Large else WidgetSizes.Wide, height).map { numberRow(m.stepsText, m.toGo, width, fontScale, it) }
        val row = rows.firstOrNull { r ->
            height - top.height - r.height(fontScale) - (week?.height ?: 0f) - inner * MIN_GAP_DP >= (if (m.showLine) MIN_LINE_DP else BAR_DP)
        } ?: rows.last()
        val fixed = top.height + row.height(fontScale) + (week?.height ?: 0f)
        val room = height - fixed - inner * MIN_GAP_DP
        val line = if (!m.showLine) null else lineIn(room)?.let { l ->
            // A tall cell grows the line until the gaps come down to the target, never past the room.
            val wanted = height - fixed - (inner + 1) * Spread.TARGET_GAP_DP
            maxOf(l, wanted.coerceAtMost(MAX_LINE_DP)).coerceAtMost(room)
        }
        val spread = Spread.of(top.height, row.height(fontScale), line ?: BAR_DP, week?.height)
        return Fit(top, row, line, week, spread)
    }
}
