package org.openstrap.app.presentation

import org.openstrap.app.R
import org.openstrap.app.data.History
import org.openstrap.app.i18n.Strings
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/*
 * The words and the plain decisions of a detail screen (spec 6.1): the status word beside the value, the label under
 * it for each range, the seven-day strip, where each chart point sits against your normal, and the one time-aware
 * sentence. Built only from numbers the app already has, like [TabCopy], so every rule is tested without a screen. The
 * text is in res/values/strings_core_detail.xml.
 */
internal object DetailCopy {
    /** The status word beside a value against your normal (spec 7.2); null without a normal to compare with. */
    fun statusWord(status: Int?, strings: Strings = Strings.app): String? = when (status) {
        null -> null
        1 -> strings.get(R.string.core_detail_status_above)
        -1 -> strings.get(R.string.core_detail_status_below)
        else -> strings.get(R.string.core_detail_status_usual)
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
    fun bandLabel(normal: History.Normal, format: (Float) -> String): String =
        // Strings.app read here rather than taken: the format comes last so callers can pass it as a trailing lambda.
        Strings.app.format(R.string.core_detail_band_label, format(normal.low), format(normal.high))

    /** Before there's a normal: "Learning your normal, 4 of 7 days" (spec 2.7). */
    fun learningNormal(days: Int, nightly: Boolean, strings: Strings = Strings.app): String = strings.format(
        if (nightly) R.string.core_detail_learning_nights else R.string.core_detail_learning_days,
        days.coerceAtMost(History.MIN_NORMAL_DAYS),
        History.MIN_NORMAL_DAYS,
    )

    /** [learningNormal] as a sentence under a chart, with its full stop. */
    private fun learningSentence(days: Int, nightly: Boolean, strings: Strings): String = strings.format(
        if (nightly) R.string.core_detail_learning_nights_sentence else R.string.core_detail_learning_days_sentence,
        days.coerceAtMost(History.MIN_NORMAL_DAYS),
        History.MIN_NORMAL_DAYS,
    )

    /**
     * The latest run of values off your normal on one side, counting back from the last value and skipping days with
     * none: its side and its length. Null when the last value is inside your normal, or there is none.
     */
    fun latestRun(levels: List<Level?>): Pair<Level, Int>? {
        val known = levels.filterNotNull()
        val side = known.lastOrNull()?.takeIf { it != Level.Inside } ?: return null
        return side to known.takeLastWhile { it == side }.size
    }

    /**
     * Small counts in words, as a sentence says them: "three naps", "12 workouts". The [unit] is English glued on with
     * an "s", so it is kept only for the callers in ui that still pass one. New text uses a plural with [numberWord].
     */
    fun count(n: Int, unit: String, strings: Strings = Strings.app): String = "${numberWord(n, strings)} $unit${if (n == 1) "" else "s"}"

    /** A count as a sentence says it: a word up to nine ("no", "three"), digits from 10. */
    fun numberWord(n: Int, strings: Strings = Strings.app): String = NUMBER_WORDS.getOrNull(n)?.let(strings::get) ?: n.toString()

    private val NUMBER_WORDS = listOf(
        R.string.core_detail_number_0, R.string.core_detail_number_1, R.string.core_detail_number_2, R.string.core_detail_number_3,
        R.string.core_detail_number_4, R.string.core_detail_number_5, R.string.core_detail_number_6, R.string.core_detail_number_7,
        R.string.core_detail_number_8, R.string.core_detail_number_9,
    )

    /** "three nights" or "12 days": [n] in words, with the unit a [span] counts in. */
    private fun counted(n: Int, nightly: Boolean, strings: Strings): String =
        strings.plural(if (nightly) R.plurals.core_detail_nights else R.plurals.core_detail_days, n, numberWord(n, strings))

    /**
     * The label under the value: what the number is for the range. "bpm now", "ms, day average", "steps, average of
     * the last 7 days". [live] is heart rate read live this minute.
     */
    fun valueLabel(metric: Metric, range: Range, live: Boolean = false, today: Boolean = true, strings: Strings = Strings.app): String {
        val unit = metric.unit
        // One string with the unit and one without, so a translator writes the capital rather than the code adding it.
        fun with(bare: Int, withUnit: Int) = if (unit.isEmpty()) strings.get(bare) else strings.format(withUnit, unit)
        return when (range) {
            Range.Day -> when (metric) {
                Metric.HeartRate -> strings.get(if (live) R.string.core_detail_label_bpm_now else R.string.core_detail_label_bpm_latest)
                Metric.RestingHr -> strings.get(R.string.core_detail_label_bpm_resting)
                Metric.Steps -> strings.get(if (today) R.string.core_detail_label_steps_today else R.string.core_detail_label_steps)
                Metric.Breathing -> strings.get(R.string.core_detail_label_breathing)
                else -> with(R.string.core_detail_label_day_average, R.string.core_detail_label_unit_day_average)
            }
            Range.Week -> if (metric == Metric.Steps) strings.get(R.string.core_detail_label_steps_week)
            else with(R.string.core_detail_label_average_week, R.string.core_detail_label_unit_average_week)
            Range.Month -> if (metric == Metric.Steps) strings.get(R.string.core_detail_label_steps_month)
            else with(R.string.core_detail_label_average_month, R.string.core_detail_label_unit_average_month)
            Range.Year -> if (metric == Metric.Steps) strings.get(R.string.core_detail_label_steps_year)
            else with(R.string.core_detail_label_average_year, R.string.core_detail_label_unit_average_year)
        }
    }

    /**
     * The day's sentence for a value against your normal, in the words of where the number comes from ([source]):
     * "Last night, within your normal." for a value from the night, "Today's average so far, ..." for the average of
     * the day's readings, "So far today, ..." for a running total, and "That night", "That day's average" or "That
     * day" for a past one. While your normal is still being learned it says how far along it is ([days] with data).
     */
    fun day(status: Int?, source: DaySource, today: Boolean, days: Int, strings: Strings = Strings.app): String {
        if (status == null) return learningSentence(days, source == DaySource.Night, strings)
        // Each source and status is its own sentence, so a translator can order and inflect it.
        val ids = when (source) {
            DaySource.Night -> if (today) DAY_NIGHT_TODAY else DAY_NIGHT_PAST
            DaySource.Average -> if (today) DAY_AVERAGE_TODAY else DAY_AVERAGE_PAST
            DaySource.SoFar -> if (today) DAY_SO_FAR_TODAY else DAY_SO_FAR_PAST
        }
        return strings.get(
            when (status) {
                1 -> ids.above
                -1 -> ids.below
                else -> ids.within
            },
        )
    }

    /** The day sentence for one source, by where the value sits. */
    private class DayIds(val above: Int, val below: Int, val within: Int)

    private val DAY_NIGHT_TODAY = DayIds(
        R.string.core_detail_day_night_today_above, R.string.core_detail_day_night_today_below, R.string.core_detail_day_night_today_within,
    )
    private val DAY_NIGHT_PAST = DayIds(
        R.string.core_detail_day_night_past_above, R.string.core_detail_day_night_past_below, R.string.core_detail_day_night_past_within,
    )
    private val DAY_AVERAGE_TODAY = DayIds(
        R.string.core_detail_day_average_today_above, R.string.core_detail_day_average_today_below,
        R.string.core_detail_day_average_today_within,
    )
    private val DAY_AVERAGE_PAST = DayIds(
        R.string.core_detail_day_average_past_above, R.string.core_detail_day_average_past_below,
        R.string.core_detail_day_average_past_within,
    )
    private val DAY_SO_FAR_TODAY = DayIds(
        R.string.core_detail_day_so_far_today_above, R.string.core_detail_day_so_far_today_below,
        R.string.core_detail_day_so_far_today_within,
    )
    private val DAY_SO_FAR_PAST = DayIds(
        R.string.core_detail_day_so_far_past_above, R.string.core_detail_day_so_far_past_below,
        R.string.core_detail_day_so_far_past_within,
    )

    /** Under the state word when there's no value: why, for the range. */
    fun noValue(metric: Metric, range: Range, today: Boolean, strings: Strings = Strings.app): String = strings.get(
        when (range) {
            Range.Day -> when {
                metric == Metric.Breathing -> R.string.core_detail_none_during_sleep
                daySource(metric) == DaySource.Night ->
                    if (today) R.string.core_detail_none_last_night else R.string.core_detail_none_that_night
                else -> if (today) R.string.core_detail_none_so_far_today else R.string.core_detail_none_that_day
            }
            Range.Week -> R.string.core_detail_none_week
            Range.Month -> R.string.core_detail_none_month
            Range.Year -> R.string.core_detail_none_year
        },
    )

    /** Heart rate's day sentence: its spread, since your normal is a daily average and not a reading. */
    fun heartDay(low: Int, high: Int, today: Boolean, strings: Strings = Strings.app): String =
        strings.format(if (today) R.string.core_detail_heart_day_today else R.string.core_detail_heart_day_past, low, high)

    /**
     * A week's or a month's sentence from the daily [levels] against your normal: a run off it first ("Below your
     * normal for three nights."), otherwise how many days sat inside it. Null with no values at all.
     */
    fun span(
        range: Range, levels: List<Level?>, nightly: Boolean, normalKnown: Boolean, days: Int, strings: Strings = Strings.app,
    ): String? {
        val known = levels.filterNotNull()
        if (known.isEmpty()) return null
        if (!normalKnown) return learningSentence(days, nightly, strings)
        latestRun(levels)?.takeIf { it.second >= 2 }?.let { (side, n) ->
            val id = if (side == Level.Above) R.string.core_detail_run_above else R.string.core_detail_run_below
            return strings.format(id, counted(n, nightly, strings))
        }
        val inside = known.count { it == Level.Inside }
        val month = range == Range.Month
        val ids = if (nightly) SPAN_NIGHTS else SPAN_DAYS
        if (inside != known.size) return strings.format(if (month) ids.insideMonth else ids.inside, inside, known.size)
        val withData = known.size < range.days
        return strings.get(
            when {
                withData && month -> ids.everyWithDataMonth
                withData -> ids.everyWithData
                month -> ids.everyMonth
                else -> ids.every
            },
        )
    }

    /** A span's sentences for nights or for days, each its own string so a translator can inflect it. */
    private class SpanIds(
        val every: Int, val everyWithData: Int, val everyMonth: Int, val everyWithDataMonth: Int, val inside: Int, val insideMonth: Int,
    )

    private val SPAN_NIGHTS = SpanIds(
        R.string.core_detail_every_night, R.string.core_detail_every_night_with_data, R.string.core_detail_every_night_month,
        R.string.core_detail_every_night_with_data_month, R.string.core_detail_inside_nights, R.string.core_detail_inside_nights_month,
    )
    private val SPAN_DAYS = SpanIds(
        R.string.core_detail_every_day, R.string.core_detail_every_day_with_data, R.string.core_detail_every_day_month,
        R.string.core_detail_every_day_with_data_month, R.string.core_detail_inside_days, R.string.core_detail_inside_days_month,
    )

    /** The year's sentence, from its weekly averages: "Over the last 12 months, weeks averaged from 48 to 61 ms." */
    fun year(lowest: String, highest: String, unit: String, strings: Strings = Strings.app): String =
        if (unit.isEmpty()) strings.format(R.string.core_detail_year, lowest, highest)
        else strings.format(R.string.core_detail_year_unit, lowest, highest, unit)

    /** A score's last 7 days: "Averaged 74 over the last 7 days, from 61 to 84." Null without any. */
    fun scoreWeek(values: List<Int?>, strings: Strings = Strings.app): String? {
        val known = values.filterNotNull()
        return when (known.size) {
            0 -> null
            1 -> strings.get(R.string.core_detail_score_week_one)
            else -> strings.format(R.string.core_detail_score_week, Math.round(known.average()), known.min(), known.max())
        }
    }

    /**
     * Charge's sentence for the day shown: today what the day took and what's left ([TodayCopy.charge]), a past day
     * where it started and ended.
     */
    fun charge(
        now: Int?, atWake: Int?, drained: Int?, anchored: Boolean, today: Boolean, hour: Int, strings: Strings = Strings.app,
    ): String = when {
        today || now == null || !anchored -> TodayCopy.charge(now, drained, anchored, hour)
        atWake != null -> strings.format(R.string.core_detail_charge_started, atWake, now)
        else -> strings.format(R.string.core_detail_charge_ended, now)
    }

    /**
     * Recovery's state when there's no score, as the hero's word and the line under it: still learning ("Learning",
     * "6 of 7 nights"), a night without the heart data a score needs, or waiting for the night.
     */
    fun recoveryState(
        ready: org.openstrap.app.data.Recovery.Readiness?, today: Boolean, strings: Strings = Strings.app,
    ): Pair<String, String> = when {
        ready == null -> strings.get(R.string.core_detail_recovery_waiting) to
            strings.get(if (today) R.string.core_detail_recovery_for_last_night else R.string.core_detail_recovery_no_sleep)
        ready.phase == org.openstrap.app.data.Recovery.Phase.Withheld -> strings.get(R.string.core_detail_recovery_learning) to
            strings.format(R.string.core_detail_recovery_nights, ready.baselineNights, org.openstrap.app.data.Recovery.SCORED_NIGHTS)
        ready.withheld -> strings.get(R.string.core_detail_recovery_no_score) to strings.get(R.string.core_detail_recovery_no_heart_data)
        else -> strings.get(R.string.core_detail_recovery_no_score) to strings.get(R.string.core_detail_recovery_scored_mornings)
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
