package org.openstrap.app.ui.dashboard

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/*
 * The week story (spec 4.2), opened from Your week on Today: last Monday to Sunday day by day, from what the app keeps.
 * Steps a day, active minutes, the workouts done and the night's sleep after each day. It compares with the week
 * before only where that week is kept: steps (stored per day) and workouts. Active minutes are worked out for the
 * 14 days ending on the day shown and nights load 14 back, so neither reaches the week before. Pure, like [TodayCopy].
 */
internal object WeekStory {
    /**
     * One day of the week: its steps, active minutes, the workouts started that day by name, and the night that
     * started that evening (it belongs to the morning after). Null where the app has nothing for it.
     */
    data class Day(val date: LocalDate, val steps: Int?, val activeMinutes: Int?, val workouts: List<String>, val asleepSeconds: Long?)

    /** The week in a few numbers: steps a day over the days with steps, active minutes in all, workouts, sleep a night. */
    data class Totals(val stepsADay: Int?, val activeMinutes: Int?, val workouts: Int, val asleepANight: Long?)

    /**
     * The days of [week], Monday first. [steps] and [active] are keyed by day, [workouts] by the day they started,
     * [asleepByMorning] by the morning a night ended on, so each day gets the night after it.
     */
    fun days(
        week: ClosedRange<LocalDate>, steps: Map<LocalDate, Int>, active: Map<LocalDate, Int>, workouts: Map<LocalDate, List<String>>,
        asleepByMorning: Map<LocalDate, Long>,
    ): List<Day> = generateSequence(week.start) { it.plusDays(1) }.takeWhile { it <= week.endInclusive }.map { d ->
        Day(d, steps[d]?.takeIf { it > 0 }, active[d], workouts[d].orEmpty(), asleepByMorning[d.plusDays(1)])
    }.toList()

    fun totals(days: List<Day>): Totals = Totals(
        stepsADay = days.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()?.let(Math::round)?.toInt(),
        activeMinutes = days.mapNotNull { it.activeMinutes }.takeIf { it.isNotEmpty() }?.sum(),
        workouts = days.sumOf { it.workouts.size },
        asleepANight = days.mapNotNull { it.asleepSeconds }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
    )

    /** A day's row under its steps: "42 active minutes, run, 7 h 40 asleep". Null when the day has nothing else. */
    fun dayLine(d: Day): String? = listOfNotNull(
        d.activeMinutes?.takeIf { it > 0 }?.let { if (it == 1) tr(R.string.week_story_active_minute) else tr(R.string.week_story_active_minutes, it) },
        d.workouts.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.lowercase() },
        d.asleepSeconds?.let { tr(R.string.missed_asleep, TodayCopy.duration(it)) },
    ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { null }

    /** "Busiest on Thursday, 12,400 steps." Null with fewer than two days of steps, where there's nothing to pick. */
    fun busiest(days: List<Day>, locale: Locale = Locale.getDefault()): String? {
        val withSteps = days.filter { it.steps != null }
        if (withSteps.size < 2) return null
        val top = withSteps.maxBy { it.steps!! }
        return tr(R.string.week_story_busiest_on_steps, top.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale), "%,d".format(locale, top.steps))
    }

    /** Workouts against the week before: "Three workouts, one more than the week before." Null without a week before. */
    fun workoutsChange(now: Int, before: Int?): String? {
        before ?: return null
        val n = DetailCopy.count(now, tr(R.string.missed_unit_workout)).replaceFirstChar { it.uppercase() }
        return when {
            now == 0 && before == 0 -> tr(R.string.week_story_no_workouts_nor_the)
            now == before -> tr(R.string.week_story_as_many_as_the, n)
            now > before -> tr(R.string.week_story_than_the_week_before, n, DetailCopy.count(now - before, tr(R.string.week_story_than_the_week_before__more)).removeSuffix(trRaw(R.string.missed_plural)))
            else -> tr(R.string.week_story_than_the_week_before_2, n, DetailCopy.count(before - now, tr(R.string.week_story_than_the_week_before_2__fewer)).removeSuffix(trRaw(R.string.missed_plural)))
        }
    }

    /**
     * The sentence under the chart: the busiest day, then the steps and the workouts against the week before where
     * that week is kept. [stepsChange] is [TodayCopy.stepsChange]'s ("Steps up 12%").
     */
    fun sentence(days: List<Day>, stepsChange: String?, workoutsBefore: Int?, locale: Locale = Locale.getDefault()): String? {
        val parts = listOfNotNull(
            busiest(days, locale),
            stepsChange?.let { tr(R.string.common_on_the_week_before, it) },
            workoutsChange(days.sumOf { it.workouts.size }, workoutsBefore),
        )
        return parts.joinToString(" ").ifEmpty { null }
    }

    /** The headline's label: "steps a day, 12 to 18 Oct". */
    fun label(week: ClosedRange<LocalDate>, locale: Locale = Locale.getDefault()): String {
        val start = week.start
        val end = week.endInclusive
        val month = { d: LocalDate -> d.month.getDisplayName(TextStyle.SHORT, locale) }
        val from = if (start.month == end.month) "${start.dayOfMonth}" else "${start.dayOfMonth} ${month(start)}"
        return tr(R.string.week_story_steps_a_day_to, from, end.dayOfMonth, month(end))
    }

    /** One row of Day by day: a day, or a run of days with nothing recorded folded into one ("Monday to Thursday"). */
    data class Row(val title: String, val steps: Int?, val line: String?)

    /** [days] as rows, so a week that started late doesn't list its empty days one by one. */
    fun rows(days: List<Day>, locale: Locale = Locale.getDefault()): List<Row> {
        fun name(d: Day) = d.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val rows = mutableListOf<Row>()
        var i = 0
        while (i < days.size) {
            val d = days[i]
            val line = dayLine(d)
            if (d.steps != null || line != null) { rows += Row(name(d), d.steps, line); i++; continue }
            var j = i
            while (j + 1 < days.size && days[j + 1].steps == null && dayLine(days[j + 1]) == null) j++
            rows += Row(if (j == i) name(d) else tr(R.string.missed_day_range, name(d), name(days[j])), null, tr(R.string.week_story_nothing_recorded))
            i = j + 1
        }
        return rows
    }
}
