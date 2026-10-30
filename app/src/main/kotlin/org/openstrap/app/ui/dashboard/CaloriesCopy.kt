package org.openstrap.app.ui.dashboard

import org.openstrap.app.R
import org.openstrap.app.data.EnergyExpenditure
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.tr
import org.openstrap.app.trRaw
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/*
 * The words and sums of Calories (calories spec 4.4 to 4.6): the Activity row, the detail's day and its ranges, where
 * the kcal came from, and a workout's place in its day. Active kcal leads everywhere, the total under it. Pure, so
 * every rule is tested without a screen.
 */
internal object CaloriesCopy {

    /* ----------------------------------------------- Row ------------------------------------------------ */

    /** The Activity row's line: "Active, of 450. Total 1,468", or "Active, goal met. Total 2,140". */
    fun rowLine(active: Int, goal: Int, total: Int, locale: Locale = Locale.getDefault()): String =
        (if (active >= goal) tr(R.string.calories_copy_active_goal_met) else trRaw(R.string.calories_copy_active_of).format(locale, goal)) +
            trRaw(R.string.calories_copy_total).format(locale, total)

    /** How far toward the goal, 0..1, for the row's ring and the detail's bar. */
    fun share(active: Int, goal: Int): Float = if (goal <= 0) 0f else (active / goal.toFloat()).coerceIn(0f, 1f)

    /* ----------------------------------------------- Day ------------------------------------------------ */

    /** The headline's label on Day. */
    fun dayLabel(today: Boolean): String = if (today) tr(R.string.calories_copy_active_kcal_so_far) else tr(R.string.calories_copy_active_kcal)

    /** Under the goal bar: "102 to your goal of 450", or "Goal of 450 met". */
    fun toGoal(active: Int, goal: Int, locale: Locale = Locale.getDefault()): String =
        if (active >= goal) trRaw(R.string.calories_copy_goal_of_met).format(locale, goal) else trRaw(R.string.calories_copy_to_your_goal_of).format(locale, goal - active, goal)

    /** The busiest hour, for the hourly card's note: "Peak 07:00, 228". Null while every hour is under 1 kcal. */
    fun peak(hourly: List<Float>, locale: Locale = Locale.getDefault()): String? {
        val h = hourly.indices.maxByOrNull { hourly[it] } ?: return null
        val v = Math.round(hourly[h])
        return if (v < 1) null else trRaw(R.string.calories_copy_peak_00).format(locale, h, v)
    }

    /** An hour's kcal in the headline while it's picked: "active kcal, 07:00 to 08:00". */
    fun hourLabel(hour: Int): String = trRaw(R.string.calories_copy_active_kcal_00_to).format(hour, (hour + 1) % 24)

    /** One row of "Where it came from": a workout (opening it, by [workoutStart]), walking or everyday moving. */
    data class Source(val name: String, val line: String, val kcal: Int, val workoutStart: Long? = null, val sportType: Int? = null)

    /**
     * Where [day]'s active kcal came from: a row for each workout started on [date] (oldest first, a strap workout
     * left out where a phone one covers the same time, as the day counts the phone's minutes), then walking and
     * everyday moving from the day itself. A workout's kcal is its own ([Workouts.activeKcal] with [bmrPerMinute]).
     */
    fun sources(
        workouts: List<WorkoutAnalysis>, date: LocalDate, day: EnergyExpenditure.Day, bmrPerMinute: Float?,
        zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(),
    ): List<Source> {
        val clock = DateTimeFormatter.ofPattern("HH:mm", locale)
        val onDay = workouts.filter { Instant.ofEpochSecond(it.shownStart).atZone(zone).toLocalDate() == date }
        val phone = onDay.filter { it.fromPhone }
        val shown = onDay.filter { w -> w.fromPhone || phone.none { p -> w.workout.start < p.end && p.shownStart < w.workout.end } }
            .sortedBy { it.shownStart }
        val rows = shown.mapNotNull { w ->
            val kcal = Workouts.activeKcal(w, bmrPerMinute) ?: return@mapNotNull null
            val at = Instant.ofEpochSecond(w.shownStart).atZone(zone).format(clock)
            Source(w.sportName, "$at, ${minutes(w.activeSeconds)}", kcal, w.workout.start, w.workout.sportType)
        }
        return rows + listOf(
            Source(tr(R.string.frame_walking), walkingLine(day.walkingMinutes), Math.round(day.walkingKcal)),
            Source(tr(R.string.calories_copy_everyday_moving), tr(R.string.calories_copy_standing_chores_and_getting), Math.round(day.everydayKcal.coerceAtLeast(0f))),
        )
    }

    /** A workout's length on its row: "42 min", "1 h 05". */
    fun minutes(seconds: Long): String {
        val m = (seconds + 30) / 60
        return if (m < 60) "$m min" else "%d h %02d".format(m / 60, m % 60)
    }

    /** The walking row's line, from its minutes at a walking cadence outside workouts. */
    fun walkingLine(minutes: Int): String = when (minutes) {
        0 -> tr(R.string.calories_copy_no_walking_outside_workouts)
        1 -> tr(R.string.calories_copy_1_min_walking_or)
        else -> tr(R.string.calories_copy_min_walking_or_running, minutes)
    }

    /* ---------------------------------------------- Ranges ---------------------------------------------- */

    /** One stored day, as the ranges read it ([org.openstrap.app.data.db.DailyEnergyRow]). */
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
    fun rangeLabel(range: Range): String = tr(R.string.calories_copy_active_kcal_a_day) + when (range) {
        Range.Year -> tr(R.string.detail_copy_last_months)
        else -> tr(R.string.calories_copy_last_days, range.days)
    }

    /** The headline's word: "Goal met on 4 of 7 days". Null without days. */
    fun goalDays(s: RangeSummary): String? {
        if (s.days == 0) return null
        val unit = if (s.days == 1) tr(R.string.missed_unit_day) else tr(R.string.detail_copy_learning_your_normal_of__days)
        return tr(R.string.calories_copy_goal_met_on_of, s.goalMet, s.days, unit)
    }

    /** The change's figure: "+42", "−18" with a true minus, "0". */
    fun signed(n: Int): String = when {
        n > 0 -> "+$n"
        n < 0 -> "−${abs(n)}"
        else -> "0"
    }

    /** The change's name: "vs the 7 days before", "vs the year before". */
    fun vsLabel(range: Range): String =
        if (range == Range.Year) tr(R.string.calories_copy_vs_the_year_before) else tr(R.string.calories_copy_vs_the_days_before, range.days)

    /**
     * The range's sentence: how much of it workouts made, else its busiest day. Null without days.
     */
    fun rangeSentence(s: RangeSummary, locale: Locale = Locale.getDefault()): String? {
        val busiest = s.busiest ?: return null
        val share = s.workoutShare
        val day = busiest.date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
        return if (share != null && share >= 1) tr(R.string.calories_copy_workouts_made_of_your, share)
        else trRaw(R.string.calories_copy_your_busiest_day_was).format(locale, day, Math.round(busiest.active))
    }

    /* ------------------------------------------- A workout's day ---------------------------------------- */

    /** The day word in "of today's 702": "today's", "yesterday's", "that day's". */
    fun dayWord(date: LocalDate, today: LocalDate): String = when (date) {
        today -> tr(R.string.info_copy_of_minutes_came_from__today_s)
        today.minusDays(1) -> tr(R.string.calories_copy_yesterday_s)
        else -> tr(R.string.info_copy_of_minutes_came_from__that_day_s)
    }

    /**
     * The workout page's In your day card: "This run is 486 of today's 702 active kcal. The day counts it exactly as
     * shown here." A strap workout's minutes come from the strap's own count, so it says that instead. [sport] is the
     * sport as it reads mid-sentence ([org.openstrap.app.ui.workout.WorkoutDetailCopy.sport]). Null when the day's
     * figure isn't there yet, or is behind the workout's (its row not yet written again).
     */
    fun inYourDay(sport: String, workout: Int, dayActive: Int?, dayWord: String, fromPhone: Boolean, locale: Locale = Locale.getDefault()): String? {
        if (dayActive == null || dayActive < workout || workout <= 0) return null
        val head = trRaw(R.string.calories_copy_this_is_of_active).format(locale, sport, workout, dayWord, dayActive)
        return head + if (fromPhone) tr(R.string.calories_copy_the_day_counts_it) else tr(R.string.calories_copy_the_day_counts_it_2)
    }
}
