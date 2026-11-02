package org.openstrap.app.presentation

import org.openstrap.app.data.History
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/*
 * The words and the plain decisions of a detail screen (spec 6.1): the status word beside the value, the label under
 * it for each range, the seven-day strip, where each chart point sits against your normal, and the one time-aware
 * sentence. Built only from numbers the app already has, like [TabCopy], so every rule is tested without a screen.
 */
internal object DetailCopy {
    /** The status word beside a value against your normal (spec 7.2); null without a normal to compare with. */
    fun statusWord(status: Int?): String? = when (status) {
        null -> null
        1 -> "Above your normal"
        -1 -> "Below your normal"
        else -> "Usual"
    }

    /** Where a chart's point sits against your normal: inside it stays dim, above or below it gets the full colour. */
    enum class Level { Inside, Above, Below }

    /** Each value's [Level] against [normal]; null for a missing value. Without a normal every point is [Level.Inside]. */
    fun levels(values: List<Float?>, normal: History.Normal?): List<Level?> = values.map { v ->
        when {
            v == null -> null
            normal == null -> Level.Inside
            v > normal.high -> Level.Above
            v < normal.low -> Level.Below
            else -> Level.Inside
        }
    }

    /** The band's label on the chart: "Your normal, 44 to 58". */
    fun bandLabel(normal: History.Normal, format: (Float) -> String): String = "Your normal, ${format(normal.low)} to ${format(normal.high)}"

    /** Before there's a normal: "Learning your normal, 4 of 7 days" (spec 2.7). */
    fun learningNormal(days: Int, nightly: Boolean): String =
        "Learning your normal, ${days.coerceAtMost(History.MIN_NORMAL_DAYS)} of ${History.MIN_NORMAL_DAYS} ${if (nightly) "nights" else "days"}"

    /**
     * The latest run of values off your normal on one side, counting back from the last value and skipping days with
     * none: its side and its length. Null when the last value is inside your normal, or there is none.
     */
    fun latestRun(levels: List<Level?>): Pair<Level, Int>? {
        val known = levels.filterNotNull()
        val side = known.lastOrNull()?.takeIf { it != Level.Inside } ?: return null
        return side to known.takeLastWhile { it == side }.size
    }

    /** Small counts in words, as a sentence says them: "three nights", "12 days". */
    fun count(n: Int, unit: String): String {
        val word = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine").getOrNull(n) ?: n.toString()
        return "$word $unit${if (n == 1) "" else "s"}"
    }

    /** "days" or "nights", singular. */
    private fun dayWord(nightly: Boolean) = if (nightly) "night" else "day"

    /**
     * The label under the value: what the number is for the range. "bpm now", "ms, day average", "steps, average of
     * the last 7 days". [live] is heart rate read live this minute.
     */
    fun valueLabel(metric: Metric, range: Range, live: Boolean = false, today: Boolean = true): String {
        val unit = metric.unit
        fun with(text: String) = if (unit.isEmpty()) text.replaceFirstChar { it.uppercase() } else "$unit, $text"
        return when (range) {
            Range.Day -> when (metric) {
                Metric.HeartRate -> if (live) "bpm now" else "bpm, latest"
                Metric.RestingHr -> "bpm resting"
                Metric.Steps -> if (today) "steps so far today" else "steps"
                Metric.Breathing -> "br/min during sleep"
                else -> with("day average")
            }
            else -> {
                val span = when (range) { Range.Week -> "last 7 days"; Range.Month -> "last 30 days"; else -> "last 12 months" }
                if (metric == Metric.Steps) "steps a day, $span" else with("average of the $span")
            }
        }
    }

    /**
     * The day's sentence for a value against your normal, in the words of where the number comes from ([source]):
     * "Last night, within your normal." for a value from the night, "Today's average so far, ..." for the average of
     * the day's readings, "So far today, ..." for a running total, and "That night", "That day's average" or "That
     * day" for a past one. While your normal is still being learned it says how far along it is ([days] with data).
     */
    fun day(status: Int?, source: DaySource, today: Boolean, days: Int): String {
        val word = when (status) {
            null -> return learningNormal(days, source == DaySource.Night) + "."
            1 -> "above your normal"
            -1 -> "below your normal"
            else -> "within your normal"
        }
        val prefix = when (source) {
            DaySource.Night -> if (today) "Last night" else "That night"
            DaySource.Average -> if (today) "Today's average so far" else "That day's average"
            DaySource.SoFar -> if (today) "So far today" else "That day"
        }
        return "$prefix, $word."
    }

    /** Under the state word when there's no value: why, for the range. */
    fun noValue(metric: Metric, range: Range, today: Boolean): String = when (range) {
        Range.Day -> when {
            metric == Metric.Breathing -> "Measured during sleep"
            daySource(metric) == DaySource.Night -> if (today) "For last night" else "For that night"
            else -> if (today) "So far today" else "For that day"
        }
        Range.Week -> "In the last 7 days"
        Range.Month -> "In the last 30 days"
        Range.Year -> "In the last 12 months"
    }

    /** Heart rate's day sentence: its spread, since your normal is a daily average and not a reading. */
    fun heartDay(low: Int, high: Int, today: Boolean): String = "Between $low and $high bpm ${if (today) "so far today" else "that day"}."

    /**
     * A week's or a month's sentence from the daily [levels] against your normal: a run off it first ("Below your
     * normal for three nights."), otherwise how many days sat inside it. Null with no values at all.
     */
    fun span(range: Range, levels: List<Level?>, nightly: Boolean, normalKnown: Boolean, days: Int): String? {
        val known = levels.filterNotNull()
        if (known.isEmpty()) return null
        if (!normalKnown) return learningNormal(days, nightly) + "."
        val unit = dayWord(nightly)
        latestRun(levels)?.takeIf { it.second >= 2 }?.let { (side, n) ->
            return "${if (side == Level.Above) "Above" else "Below"} your normal for ${count(n, unit)}."
        }
        val inside = known.count { it == Level.Inside }
        val over = if (range == Range.Month) " in the last 30 days" else ""
        return when (inside) {
            known.size -> "Within your normal on every ${unit}${if (known.size < range.days) " with data" else ""}$over."
            else -> "Within your normal on $inside of ${known.size} ${unit}s$over."
        }
    }

    /** The year's sentence, from its weekly averages: "Over the last 12 months, weeks averaged from 48 to 61 ms." */
    fun year(lowest: String, highest: String, unit: String): String =
        "Over the last 12 months, weeks averaged from $lowest to $highest${if (unit.isEmpty()) "" else " $unit"}."

    /** A score's last 7 days: "Averaged 74 over the last 7 days, from 61 to 84." Null without any. */
    fun scoreWeek(values: List<Int?>): String? {
        val known = values.filterNotNull()
        return when (known.size) {
            0 -> null
            1 -> "One score in the last 7 days so far."
            else -> "Averaged ${Math.round(known.average())} over the last 7 days, from ${known.min()} to ${known.max()}."
        }
    }

    /**
     * Charge's sentence for the day shown: today what the day took and what's left ([TodayCopy.charge]), a past day
     * where it started and ended.
     */
    fun charge(now: Int?, atWake: Int?, drained: Int?, anchored: Boolean, today: Boolean, hour: Int): String = when {
        today || now == null || !anchored -> TodayCopy.charge(now, drained, anchored, hour)
        atWake != null -> "Started the day at $atWake and ended it at $now."
        else -> "Ended the day at $now."
    }

    /**
     * Recovery's state when there's no score, as the hero's word and the line under it: still learning ("Learning",
     * "6 of 7 nights"), a night without the heart data a score needs, or waiting for the night.
     */
    fun recoveryState(ready: org.openstrap.app.data.Recovery.Readiness?, today: Boolean): Pair<String, String> = when {
        ready == null -> "Waiting" to if (today) "For last night's sleep" else "No sleep recorded that night"
        ready.phase == org.openstrap.app.data.Recovery.Phase.Withheld ->
            "Learning" to "${ready.baselineNights} of ${org.openstrap.app.data.Recovery.SCORED_NIGHTS} nights"
        ready.withheld -> "No score" to "Not enough heart data that night"
        else -> "No score" to "Scored each morning from the night"
    }

    /** A day of the seven-day strip under the value: its short name, whether it has data, and whether it's shown. */
    data class StripDay(val date: LocalDate, val label: String, val hasData: Boolean, val selected: Boolean)

    /**
     * The seven days under a detail's value. It ends today while the day shown is in the last week, so tapping back
     * through the week keeps the strip still; an older day ends its own strip.
     */
    fun strip(selected: LocalDate, today: LocalDate, withData: Set<LocalDate>, locale: Locale = Locale.getDefault()): List<StripDay> {
        val end = if (!selected.isAfter(today) && selected >= today.minusDays(6)) today else selected
        return (6 downTo 0).map { end.minusDays(it.toLong()) }.map { d ->
            StripDay(d, d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), d in withData, d == selected)
        }
    }
}
