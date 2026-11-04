package org.openstrap.app.ui.dashboard

import org.openstrap.app.R
import org.openstrap.app.data.EnergyExpenditure
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.i18n.Strings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.TimeOfDay
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.Range

/*
 * The words and sums of Calories (calories spec 4.4 to 4.6): the Activity row, the detail's day and its ranges, where
 * the kcal came from, and a workout's place in its day. Active kcal leads everywhere, the total under it. Pure, so
 * every rule is tested without a screen. The text is in res/values/strings_day_calories.xml.
 */
internal object CaloriesCopy {

    /* ----------------------------------------------- Row ------------------------------------------------ */

    /** The Activity row's line: "Active, of 450. Total 1,468", or "Active, goal met. Total 2,140". */
    fun rowLine(active: Int, goal: Int, total: Int, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        if (active >= goal) {
            strings.format(R.string.day_calories_row_line_met, Units.count(total, locale))
        } else {
            strings.format(R.string.day_calories_row_line, Units.count(goal, locale), Units.count(total, locale))
        }

    /** How far toward the goal, 0..1, for the row's ring and the detail's bar. */
    fun share(active: Int, goal: Int): Float = if (goal <= 0) 0f else (active / goal.toFloat()).coerceIn(0f, 1f)

    /* ----------------------------------------------- Day ------------------------------------------------ */

    /** The headline's label on Day. */
    fun dayLabel(today: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (today) R.string.day_calories_day_label_today else R.string.day_calories_day_label)

    /** Under the goal bar: "102 to your goal of 450", or "Goal of 450 met". */
    fun toGoal(active: Int, goal: Int, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        if (active >= goal) {
            strings.format(R.string.day_calories_to_goal_met, Units.count(goal, locale))
        } else {
            strings.format(R.string.day_calories_to_goal, Units.count(goal - active, locale), Units.count(goal, locale))
        }

    /** The busiest hour, for the hourly card's note: "Peak 07:00, 228". Null while every hour is under 1 kcal. */
    fun peak(hourly: List<Float>, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String? {
        val h = hourly.indices.maxByOrNull { hourly[it] } ?: return null
        val v = Math.round(hourly[h])
        return if (v < 1) null else strings.format(R.string.day_calories_peak, hourClock(h, locale), Units.count(v.toLong(), locale))
    }

    /** An hour's kcal in the headline while it's picked: "active kcal, 07:00 to 08:00". */
    fun hourLabel(hour: Int, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        strings.format(R.string.day_calories_hour_label, Ranges.of(hourClock(hour, locale), hourClock(hour + 1, locale)))

    /** The start of [hour] on the phone's clock: "07:00", or "7:00 AM". */
    private fun hourClock(hour: Int, locale: Locale) = TimeOfDay.minutes(hour * MINUTES_PER_HOUR, locale = locale)

    private const val MINUTES_PER_HOUR = 60

    /** One row of "Where it came from": a workout (opening it, by [workoutStart]), walking or everyday moving. */
    data class Source(val name: String, val line: String, val kcal: Int, val workoutStart: Long? = null, val sportType: Int? = null)

    /**
     * Where [day]'s active kcal came from: a row for each workout started on [date] (oldest first, a strap workout
     * left out where a phone one covers the same time, as the day counts the phone's minutes), then walking and
     * everyday moving from the day itself. A workout's kcal is its own ([Workouts.activeKcal] with [bmrPerMinute]).
     */
    fun sources(
        workouts: List<WorkoutAnalysis>, date: LocalDate, day: EnergyExpenditure.Day, bmrPerMinute: Float?,
        zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): List<Source> {
        val onDay = workouts.filter { Instant.ofEpochSecond(it.shownStart).atZone(zone).toLocalDate() == date }
        val phone = onDay.filter { it.fromPhone }
        val shown = onDay.filter { w -> w.fromPhone || phone.none { p -> w.workout.start < p.end && p.shownStart < w.workout.end } }
            .sortedBy { it.shownStart }
        val rows = shown.mapNotNull { w ->
            val kcal = Workouts.activeKcal(w, bmrPerMinute) ?: return@mapNotNull null
            val at = TimeOfDay.format(w.shownStart, zone, locale = locale)
            val line = strings.format(R.string.day_calories_source_workout_line, at, minutes(w.activeSeconds))
            Source(w.sportName, line, kcal, w.workout.start, w.workout.sportType)
        }
        return rows + listOf(
            Source(
                strings.get(R.string.day_calories_source_walking), walkingLine(day.walkingMinutes, strings),
                Math.round(day.walkingKcal),
            ),
            Source(
                strings.get(R.string.day_calories_source_everyday), strings.get(R.string.day_calories_source_everyday_line),
                Math.round(day.everydayKcal.coerceAtLeast(0f)),
            ),
        )
    }

    /** A workout's length on its row, to the nearest minute: "42 min", "1 h 05". */
    fun minutes(seconds: Long): String = Units.duration(seconds + HALF_MINUTE_S)

    private const val HALF_MINUTE_S = 30L

    /** The walking row's line, from its minutes at a walking cadence outside workouts. */
    fun walkingLine(minutes: Int, strings: Strings = Strings.app): String =
        if (minutes == 0) {
            strings.get(R.string.day_calories_walking_none)
        } else {
            strings.plural(R.plurals.day_calories_walking, minutes, minutes)
        }

    /* ---------------------------------------------- Ranges ---------------------------------------------- */

    /** One stored day, as the ranges read it ([org.openstrap.app.data.db.DailyCaloriesRow]). */
    data class DayKcal(val date: LocalDate, val active: Float, val total: Float, val workout: Float, val partial: Boolean)

    /** A range's numbers: per day averages, the goal days, the active total and the change against the range before. */
    data class RangeSummary(
        /** The goal's days: the full days, and today or a barely worn day only once its goal is met. */
        val days: Int,
        /** Active a day, over the full days: a day under way or barely worn would drag it down. */
        val activeADay: Int?,
        val goalMet: Int,
        val activeTotal: Int,
        val totalADay: Int?,
        /** Active a day against the range before, when that range has days of its own. */
        val vsBefore: Int?,
        /** The share of the active kcal from workouts, 0..100. */
        val workoutShare: Int?,
        val busiest: DayKcal?,
    )

    /**
     * The days from [start] to [end] of [rows], against the same number of days before [start]. The averages read only
     * full days, so a day [inProgress] (today, so far) or one barely worn never drags them down. What is counted up
     * (the total, the busiest day, the workouts' share) takes every day, today too, so it agrees with the bars. Today
     * or a barely worn day joins the goal days once it has met the goal, never to count against it.
     */
    fun summary(rows: Map<LocalDate, DayKcal>, start: LocalDate, end: LocalDate, goal: Int, inProgress: LocalDate?): RangeSummary {
        fun inRange(from: LocalDate, to: LocalDate) = rows.values.filter { it.date in from..to }
        fun full(days: List<DayKcal>) = days.filter { it.date != inProgress && !it.partial }
        fun aDay(days: List<DayKcal>, of: (DayKcal) -> Float) = if (days.isEmpty()) null else Math.round(days.sumOf { of(it).toDouble() } / days.size).toInt()
        val all = inRange(start, end)
        val length = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
        val now = full(all)
        val before = full(inRange(start.minusDays(length), start.minusDays(1)))
        val goalDays = all.filter { it in now || Math.round(it.active) >= goal }
        val active = all.sumOf { it.active.toDouble() }
        val workout = all.sumOf { it.workout.toDouble() }
        val activeADay = aDay(now) { it.active }
        val beforeADay = aDay(before) { it.active }
        return RangeSummary(
            days = goalDays.size,
            activeADay = activeADay,
            goalMet = goalDays.count { Math.round(it.active) >= goal },
            activeTotal = Math.round(active).toInt(),
            totalADay = aDay(now) { it.total },
            vsBefore = if (activeADay != null && beforeADay != null) activeADay - beforeADay else null,
            workoutShare = if (active > 0) Math.round(workout / active * 100).toInt() else null,
            busiest = all.maxByOrNull { it.active },
        )
    }

    /** The headline's label on a range: "active kcal a day, last 7 days". */
    fun rangeLabel(range: Range, strings: Strings = Strings.app): String = when (range) {
        Range.Year -> strings.get(R.string.day_calories_range_label_year)
        else -> strings.plural(R.plurals.day_calories_range_label, range.days, range.days)
    }

    /** The headline's word: "Goal met on 4 of 7 days". Null without days. */
    fun goalDays(s: RangeSummary, strings: Strings = Strings.app): String? =
        if (s.days == 0) {
            null
        } else {
            strings.format(R.string.day_calories_goal_days, s.goalMet, strings.plural(R.plurals.day_calories_days, s.days, s.days))
        }

    /** The change's figure: "+42", "−18" with a true minus, "0". */
    fun signed(n: Int): String = when {
        n > 0 -> "+$n"
        n < 0 -> "−${abs(n)}"
        else -> "0"
    }

    /** The change's name: "vs the 7 days before", "vs the year before". */
    fun vsLabel(range: Range, strings: Strings = Strings.app): String =
        if (range == Range.Year) {
            strings.get(R.string.day_calories_vs_year)
        } else {
            strings.plural(R.plurals.day_calories_vs_days, range.days, range.days)
        }

    /**
     * The range's sentence: how much of it workouts made, else its busiest day. Null without days.
     */
    fun rangeSentence(s: RangeSummary, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String? {
        val busiest = s.busiest ?: return null
        val share = s.workoutShare
        return if (share != null && share >= 1) strings.format(R.string.day_calories_range_workouts, share)
        else strings.format(
            R.string.day_calories_range_busiest, DayWords.short(busiest.date, locale),
            Units.count(Math.round(busiest.active).toLong(), locale),
        )
    }

    /* ------------------------------------------- A workout's day ---------------------------------------- */

    /** The day word in "of today's 702": "today's", "yesterday's", "that day's". */
    fun dayWord(date: LocalDate, today: LocalDate, strings: Strings = Strings.app): String = strings.get(
        when (date) {
            today -> R.string.day_calories_day_word_today
            today.minusDays(1) -> R.string.day_calories_day_word_yesterday
            else -> R.string.day_calories_day_word_that_day
        },
    )

    /**
     * The workout page's In your day card: "This run is 486 of today's 702 active kcal. The day counts it exactly as
     * shown here." A strap workout's minutes come from the strap's own count, so it says that instead. [sport] is the
     * sport as it reads mid-sentence ([org.openstrap.app.ui.workout.WorkoutDetailCopy.sport]). Null when the day's
     * figure isn't there yet, or is behind the workout's (its row not yet written again).
     */
    fun inYourDay(
        sport: String, workout: Int, dayActive: Int?, dayWord: String, fromPhone: Boolean, locale: Locale = Locale.getDefault(),
        strings: Strings = Strings.app,
    ): String? {
        if (dayActive == null || dayActive < workout || workout <= 0) return null
        val id = if (fromPhone) R.string.day_calories_in_your_day_phone else R.string.day_calories_in_your_day_strap
        return strings.format(id, sport, Units.count(workout, locale), dayWord, Units.count(dayActive, locale))
    }
}
