package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.Activity
import org.openstrap.app.data.UsualSteps
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/*
 * The words of the Activity tab's details (spec 5.3 and 6.1): steps against your usual by now, Heart points, active
 * minutes, Load, VO2 max, time to recover, energy and distance. Built only from numbers the app already has, like
 * [DetailCopy], so every rule is tested without a screen. The method behind each number is in [InfoCopy].
 */
internal object ActivityCopy {
    /** Heart points' weekly aim: 100 or more over the last seven days. */
    const val HEART_POINTS_TARGET = 100

    /* ----------------------------------------------- Steps ---------------------------------------------- */

    /**
     * Steps against your usual by now (spec 2.7, compare by pace): "About usual by now". A past day is read whole,
     * against your usual day.
     */
    fun pace(p: UsualSteps.Pace, today: Boolean): String = when (p) {
        UsualSteps.Pace.More -> if (today) tr(R.string.activity_copy_more_than_usual_by) else tr(R.string.activity_copy_more_than_your_usual)
        UsualSteps.Pace.About -> if (today) tr(R.string.activity_copy_about_usual_by_now) else tr(R.string.activity_copy_about_your_usual_day)
        UsualSteps.Pace.Fewer -> if (today) tr(R.string.activity_copy_fewer_than_usual_by) else tr(R.string.activity_copy_fewer_than_your_usual)
    }

    /**
     * Today's steps against your [usual] running totals by the hour ([UsualSteps.byHour]): by [hour]:[minute] today,
     * by the end of a past day. Null without a usual, or while your usual by now is too small to compare with.
     */
    fun paceOf(steps: Int, usual: List<Float>?, today: Boolean, hour: Int, minute: Int): UsualSteps.Pace? {
        val u = usual?.takeIf { it.size == 24 } ?: return null
        return UsualSteps.pace(steps, if (today) UsualSteps.at(u, hour, minute) else u[23])
    }

    /** How far toward the goal: "Goal met" or "62% of your goal". */
    fun goalShare(steps: Int, goal: Int): String =
        if (steps >= goal) tr(R.string.common_goal_met) else tr(R.string.activity_copy_of_your_goal, steps * 100 / goal.coerceAtLeast(1))

    /** Today's Activity card under the steps: the goal share, then your usual by now when there is one. */
    fun activityLine(steps: Int, goal: Int, pace: UsualSteps.Pace?, today: Boolean): String {
        val share = goalShare(steps, goal)
        return if (pace == null) share else "$share, ${pace(pace, today).replaceFirstChar { it.lowercase() }}"
    }

    /** An hour's steps in the headline while it's tapped: "steps, 09:00 to 10:00". */
    fun hourLabel(hour: Int): String = trRaw(R.string.common_steps_d_to_d).format(hour, (hour + 1) % 24)

    /* -------------------------------------------- Heart points ------------------------------------------ */

    /** The status word beside the 7-day total; null under the aim, where the sentence says how far to go. */
    fun heartPointsWord(total: Int): String? = if (total >= HEART_POINTS_TARGET) tr(R.string.activity_copy_at_your_aim) else null

    /** The week's sentence, under the total: how far to the aim, or that it's met. */
    fun heartPointsWeek(total: Int): String = when {
        total >= HEART_POINTS_TARGET -> tr(R.string.activity_copy_keeping_it_at_or, HEART_POINTS_TARGET)
        else -> tr(R.string.activity_copy_to_go_for, HEART_POINTS_TARGET - total, HEART_POINTS_TARGET)
    }

    /** What drops off tomorrow: the oldest day's points. Null when that day earned none. */
    fun dropsOff(oldest: LocalDate, points: Int, locale: Locale = Locale.getDefault()): String? {
        if (points <= 0) return null
        val day = oldest.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        return tr(R.string.activity_copy_tomorrow_s_off, day, points, if (points == 1) tr(R.string.activity_copy_tomorrow_s_off__point_drops) else tr(R.string.activity_copy_tomorrow_s_off__points_drop))
    }

    /** A day's points in the headline: "points earned, Tue 20 Oct". */
    fun earnedLabel(dayName: String): String = tr(R.string.activity_copy_points_earned, dayName)

    /** The month's sentence from each day's 7-day total: how often it was at the aim. Null without any. */
    fun heartPointsMonth(totals: List<Int?>): String? {
        val known = totals.filterNotNull()
        if (known.isEmpty()) return null
        val at = known.count { it >= HEART_POINTS_TARGET }
        return when (at) {
            0 -> tr(R.string.activity_copy_under_every_day_in, HEART_POINTS_TARGET, known.min(), known.max())
            known.size -> tr(R.string.activity_copy_at_or_more_every, HEART_POINTS_TARGET)
            else -> tr(R.string.activity_copy_at_or_more_on, HEART_POINTS_TARGET, at, known.size)
        }
    }

    /* ------------------------------------------- Active minutes ----------------------------------------- */

    /** The status word beside the week's active minutes; null under the goal. */
    fun activeWord(week: Int): String? = if (week >= Activity.MVPA_WEEKLY_TARGET) tr(R.string.common_goal_met) else null

    /** The week's sentence, under the week's minutes: how far to the 150, or by how much it's past. */
    fun activeWeek(week: Int): String {
        val target = Activity.MVPA_WEEKLY_TARGET
        return when {
            week == target -> tr(R.string.activity_copy_right_at_the_to, target)
            week > target -> tr(R.string.activity_copy_past_the_to_aim, week - target, target)
            else -> tr(R.string.activity_copy_to_go_for_2, target - week, target)
        }
    }

    /** A day's sentence: its moderate and vigorous minutes. */
    fun activeDay(m: Activity.Mvpa, today: Boolean): String {
        val lead = if (today) tr(R.string.common_so_far_today) else tr(R.string.common_that_day)
        return when {
            m.moderateMinutes == 0 && m.vigorousMinutes == 0 -> if (today) tr(R.string.activity_copy_no_active_minutes_so) else tr(R.string.activity_copy_no_active_minutes_that)
            m.vigorousMinutes == 0 -> tr(R.string.activity_copy_of_brisk_movement, lead, minutes(m.moderateMinutes))
            m.moderateMinutes == 0 -> tr(R.string.activity_copy_of_hard_effort, lead, minutes(m.vigorousMinutes))
            else -> tr(R.string.activity_copy_brisk_and_hard, lead, minutes(m.moderateMinutes), minutes(m.vigorousMinutes))
        }
    }

    private fun minutes(n: Int) = if (n == 1) tr(R.string.activity_copy_minute) else tr(R.string.missed_n_minutes, n)

    /* ------------------------------------------------ Load ---------------------------------------------- */

    /** Load's scale in its info sheet, low to high, in the words beside it. */
    val LOAD_SCALE = listOf(tr(R.string.common_below_your_usual), tr(R.string.common_usual), tr(R.string.common_above_your_usual))

    /** Which bar of [LOAD_SCALE] a Load is in against your usual for a whole day; null without a usual. */
    fun loadBar(load: Float, usual: Float?): Int? {
        if (usual == null || usual <= 0f) return null
        return when {
            load > usual * (1 + TodayCopy.LOAD_BAND) -> 2
            load < usual * (1 - TodayCopy.LOAD_BAND) -> 0
            else -> 1
        }
    }

    /** Your usual as a band on Load's chart: within [TodayCopy.LOAD_BAND] of it, as Today reads a usual Load. */
    fun usualBand(usual: Float?): ClosedFloatingPointRange<Float>? =
        usual?.takeIf { it > 0f }?.let { it * (1 - TodayCopy.LOAD_BAND)..it * (1 + TodayCopy.LOAD_BAND) }

    /** The band's label: "Your usual, 30 to 50". */
    fun usualBandLabel(band: ClosedFloatingPointRange<Float>): String = tr(R.string.activity_copy_your_usual_to, Math.round(band.start), Math.round(band.endInclusive))

    /**
     * The day's sentence: how it sits among your recent days ([percentile], the share of the last 90 days' loads at or
     * below it), or before there are enough days, that your usual is being learned.
     */
    fun loadDay(percentile: Int?, today: Boolean): String {
        val lead = if (today) tr(R.string.common_so_far_today) else tr(R.string.common_that_day)
        return when {
            percentile == null -> tr(R.string.activity_copy_your_usual_shows_after)
            percentile >= 50 -> tr(R.string.activity_copy_as_hard_as_or, lead, percentile)
            else -> tr(R.string.activity_copy_easier_than_of_your, lead, 100 - percentile)
        }
    }

    /** The week's balance in a sentence, from its word ([TabCopy.loadBalance]). */
    fun balance(word: String?): String? = when (word) {
        tr(R.string.tab_copy_building) -> tr(R.string.activity_copy_your_last_days_are)
        tr(R.string.tab_copy_easing_off) -> tr(R.string.activity_copy_your_last_days_are_2)
        tr(R.string.common_steady) -> tr(R.string.activity_copy_your_last_days_are_3)
        else -> null
    }

    /** A week's or month's sentence from daily Loads against your usual band: how many days sat above it. */
    fun loadSpan(levels: List<DetailCopy.Level?>, range: Range): String? {
        val known = levels.filterNotNull()
        if (known.isEmpty()) return null
        val above = known.count { it == DetailCopy.Level.Above }
        val over = if (range == Range.Month) tr(R.string.activity_copy_in_the_last_days) else tr(R.string.activity_copy_in_the_last_days_2)
        return when (above) {
            0 -> tr(R.string.activity_copy_no_day_above_your, over)
            1 -> tr(R.string.activity_copy_one_day_above_your, over)
            else -> tr(R.string.activity_copy_days_above_your_usual, above, over)
        }
    }

    /** The strap's own weekly load and the range it calls productive, as a row's status. */
    fun strapRange(low: Int, high: Int): String = tr(R.string.activity_copy_the_strap_s_productive, low, high)

    /* --------------------------------------------- VO2 max ---------------------------------------------- */

    /** The sentence: where the number comes from. */
    fun vo2(fromWorkout: Boolean): String =
        if (fromWorkout) tr(R.string.activity_copy_from_your_latest_workout) else tr(R.string.activity_copy_estimated_from_your_profile)

    /* ------------------------------------------ Time to recover ----------------------------------------- */

    /** When the workout time to recover counts from ended: "today at 18:30", "yesterday at 07:10", "Tue 20 Oct at 18:30". */
    fun ended(date: LocalDate, clock: String, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (date) {
        today -> tr(R.string.activity_copy_today_at, clock)
        today.minusDays(1) -> tr(R.string.activity_copy_yesterday_at, clock)
        else -> tr(R.string.missed_value_at_time, date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", locale)), clock)
    }

    /** The sentence under the hours to go: which workout they count from. */
    fun recover(sport: String, ended: String): String = tr(R.string.activity_copy_counted_from_your_which, sport.lowercase(), ended)

    /* ----------------------------------------------- Energy --------------------------------------------- */

    /** The sentence under the day's kcal: what your body uses at rest, before any movement. */
    fun energy(atRest: Int): String = tr(R.string.activity_copy_your_body_uses_about, "%,d".format(atRest))

    /* ---------------------------------------------- Distance -------------------------------------------- */

    /** The distance row's line and the detail's label: where the number comes from. */
    fun distanceSource(fromSteps: Boolean): String = if (fromSteps) tr(R.string.activity_copy_km_from_your_steps) else tr(R.string.activity_copy_km_from_the_strap)

    /** The sentence under the kilometres: the steps they come from. */
    fun distance(steps: Int, today: Boolean): String = tr(R.string.activity_copy_over_steps, "%,d".format(steps), if (today) tr(R.string.activity_copy_over_steps__so_far_today) else tr(R.string.activity_copy_over_steps__that_day))
}
