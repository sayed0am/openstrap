package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.History
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/*
 * The words and the plain decisions of a detail screen (spec 6.1): the status word beside the value, the label under
 * it for each range, the seven-day strip, where each chart point sits against your normal, and the one time-aware
 * sentence. Built only from numbers the app already has, like [TabCopy], so every rule is tested without a screen.
 */
internal object DetailCopy {
    /** The status word beside a value against your normal (spec 7.2); null without a normal to compare with. */
    fun statusWord(status: Int?): String? = when (status) {
        null -> null
        1 -> tr(R.string.common_above_your_normal)
        -1 -> tr(R.string.common_below_your_normal)
        else -> tr(R.string.common_usual)
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
    fun bandLabel(normal: History.Normal, format: (Float) -> String): String = tr(R.string.detail_copy_your_normal_to, format(normal.low), format(normal.high))

    /** Before there's a normal: "Learning your normal, 4 of 7 days" (spec 2.7). */
    fun learningNormal(days: Int, nightly: Boolean): String =
        tr(R.string.detail_copy_learning_your_normal_of, days.coerceAtMost(History.MIN_NORMAL_DAYS), History.MIN_NORMAL_DAYS, if (nightly) tr(R.string.detail_copy_learning_your_normal_of__nights) else tr(R.string.detail_copy_learning_your_normal_of__days))

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
        val word = trRaw(R.string.missed_count_words).split(',').getOrNull(n) ?: n.toString()
        return tr(R.string.missed_count, word, unit + if (n == 1) "" else trRaw(R.string.missed_plural))
    }

    /** "days" or "nights", singular. */
    private fun dayWord(nightly: Boolean) = tr(if (nightly) R.string.missed_unit_night else R.string.missed_unit_day)

    /**
     * The label under the value: what the number is for the range. "bpm now", "ms, day average", "steps, average of
     * the last 7 days". [live] is heart rate read live this minute.
     */
    fun valueLabel(metric: Metric, range: Range, live: Boolean = false, today: Boolean = true): String {
        val unit = metric.unit
        fun with(text: String) = if (unit.isEmpty()) text.replaceFirstChar { it.uppercase() } else "$unit, $text"
        return when (range) {
            Range.Day -> when (metric) {
                Metric.HeartRate -> if (live) tr(R.string.common_bpm_now) else tr(R.string.detail_copy_bpm_latest)
                Metric.RestingHr -> tr(R.string.detail_copy_bpm_resting)
                Metric.Steps -> if (today) tr(R.string.detail_copy_steps_so_far_today) else tr(R.string.missed_unit_steps)
                Metric.Breathing -> tr(R.string.detail_copy_br_min_during_sleep)
                else -> with(tr(R.string.detail_copy_day_average))
            }
            else -> {
                val span = when (range) { Range.Week -> tr(R.string.detail_copy_last_days); Range.Month -> tr(R.string.detail_copy_last_days_2); else -> tr(R.string.detail_copy_last_months) }
                if (metric == Metric.Steps) tr(R.string.detail_copy_steps_a_day, span) else with(tr(R.string.detail_copy_average_of_the, span))
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
            1 -> tr(R.string.detail_copy_above_your_normal)
            -1 -> tr(R.string.detail_copy_below_your_normal)
            else -> tr(R.string.detail_copy_within_your_normal)
        }
        val prefix = when (source) {
            DaySource.Night -> if (today) tr(R.string.common_last_night_2) else tr(R.string.detail_copy_that_night)
            DaySource.Average -> if (today) tr(R.string.detail_copy_today_s_average_so) else tr(R.string.detail_copy_that_day_s_average)
            DaySource.SoFar -> if (today) tr(R.string.common_so_far_today) else tr(R.string.common_that_day)
        }
        return "$prefix, $word."
    }

    /** Under the state word when there's no value: why, for the range. */
    fun noValue(metric: Metric, range: Range, today: Boolean): String = when (range) {
        Range.Day -> when {
            metric == Metric.Breathing -> tr(R.string.detail_copy_measured_during_sleep)
            daySource(metric) == DaySource.Night -> if (today) tr(R.string.detail_copy_for_last_night) else tr(R.string.detail_copy_for_that_night)
            else -> if (today) tr(R.string.common_so_far_today) else tr(R.string.detail_copy_for_that_day)
        }
        Range.Week -> tr(R.string.common_in_the_last_days)
        Range.Month -> tr(R.string.common_in_the_last_days_2)
        Range.Year -> tr(R.string.detail_copy_in_the_last_months)
    }

    /** Heart rate's day sentence: its spread, since your normal is a daily average and not a reading. */
    fun heartDay(low: Int, high: Int, today: Boolean): String = tr(R.string.detail_copy_between_and_bpm, low, high, if (today) tr(R.string.detail_copy_between_and_bpm__so_far_today) else tr(R.string.detail_copy_between_and_bpm__that_day))

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
            return tr(R.string.detail_copy_your_normal_for, if (side == Level.Above) tr(R.string.detail_copy_your_normal_for__above) else tr(R.string.detail_copy_your_normal_for__below), count(n, unit))
        }
        val inside = known.count { it == Level.Inside }
        val over = if (range == Range.Month) tr(R.string.detail_copy_in_the_last_days) else ""
        return when (inside) {
            known.size -> tr(R.string.detail_copy_within_your_normal_on, unit, if (known.size < range.days) tr(R.string.detail_copy_within_your_normal_on__with_data) else "", over)
            else -> tr(R.string.detail_copy_within_your_normal_on_2, inside, known.size, unit, over)
        }
    }

    /** The year's sentence, from its weekly averages: "Over the last 12 months, weeks averaged from 48 to 61 ms." */
    fun year(lowest: String, highest: String, unit: String): String =
        tr(R.string.detail_copy_over_the_last_months, lowest, highest, if (unit.isEmpty()) "" else " $unit")

    /** A score's last 7 days: "Averaged 74 over the last 7 days, from 61 to 84." Null without any. */
    fun scoreWeek(values: List<Int?>): String? {
        val known = values.filterNotNull()
        return when (known.size) {
            0 -> null
            1 -> tr(R.string.detail_copy_one_score_in_the)
            else -> tr(R.string.detail_copy_averaged_over_the_last, Math.round(known.average()), known.min(), known.max())
        }
    }

    /**
     * Charge's sentence for the day shown: today what the day took and what's left ([TodayCopy.charge]), a past day
     * where it started and ended.
     */
    fun charge(now: Int?, atWake: Int?, drained: Int?, anchored: Boolean, today: Boolean, hour: Int): String = when {
        today || now == null || !anchored -> TodayCopy.charge(now, drained, anchored, hour)
        atWake != null -> tr(R.string.detail_copy_started_the_day_at, atWake, now)
        else -> tr(R.string.detail_copy_ended_the_day_at, now)
    }

    /**
     * Recovery's state when there's no score, as the hero's word and the line under it: still learning ("Learning",
     * "6 of 7 nights"), a night without the heart data a score needs, or waiting for the night.
     */
    fun recoveryState(ready: org.openstrap.app.data.Recovery.Readiness?, today: Boolean): Pair<String, String> = when {
        ready == null -> tr(R.string.common_waiting) to if (today) tr(R.string.detail_copy_for_last_night_s) else tr(R.string.detail_copy_no_sleep_recorded_that)
        ready.phase == org.openstrap.app.data.Recovery.Phase.Withheld ->
            tr(R.string.common_learning) to tr(R.string.detail_copy_of_nights, ready.baselineNights, org.openstrap.app.data.Recovery.SCORED_NIGHTS)
        ready.withheld -> tr(R.string.common_no_score) to tr(R.string.detail_copy_not_enough_heart_data)
        else -> tr(R.string.common_no_score) to tr(R.string.detail_copy_scored_each_morning_from)
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
