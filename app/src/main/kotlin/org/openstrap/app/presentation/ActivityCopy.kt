package org.openstrap.app.presentation

import org.openstrap.app.R
import org.openstrap.app.data.Activity
import org.openstrap.app.data.UsualSteps
import org.openstrap.app.i18n.Strings
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/*
 * The words of the Activity tab's details (spec 5.3 and 6.1): steps against your usual by now, Heart points, active
 * minutes, Load, VO2 max, time to recover, calories and distance. Built only from numbers the app already has, like
 * [DetailCopy], so every rule is tested without a screen. The method behind each number is in [InfoCopy]. The text is
 * in res/values/strings_core_activity.xml.
 */
internal object ActivityCopy {
    /** Heart points' weekly aim: 100 or more over the last seven days. */
    const val HEART_POINTS_TARGET = 100

    /* ----------------------------------------------- Steps ---------------------------------------------- */

    /**
     * Steps against your usual by now (spec 2.7, compare by pace): "About usual by now". A past day is read whole,
     * against your usual day.
     */
    fun pace(p: UsualSteps.Pace, today: Boolean, strings: Strings = Strings.app): String = strings.get(
        when (p) {
            UsualSteps.Pace.More -> if (today) R.string.core_activity_pace_more_now else R.string.core_activity_pace_more_day
            UsualSteps.Pace.About -> if (today) R.string.core_activity_pace_about_now else R.string.core_activity_pace_about_day
            UsualSteps.Pace.Fewer -> if (today) R.string.core_activity_pace_fewer_now else R.string.core_activity_pace_fewer_day
        },
    )

    /**
     * Today's steps against your [usual] running totals by the hour ([UsualSteps.byHour]): by [hour]:[minute] today,
     * by the end of a past day. Null without a usual, or while your usual by now is too small to compare with.
     */
    fun paceOf(steps: Int, usual: List<Float>?, today: Boolean, hour: Int, minute: Int): UsualSteps.Pace? {
        val u = usual?.takeIf { it.size == 24 } ?: return null
        return UsualSteps.pace(steps, if (today) UsualSteps.at(u, hour, minute) else u[23])
    }

    /** How far toward the goal: "Goal met" or "62% of your goal". */
    fun goalShare(steps: Int, goal: Int, strings: Strings = Strings.app): String =
        if (steps >= goal) strings.get(R.string.core_activity_goal_met)
        else strings.format(R.string.core_activity_goal_share, steps * 100 / goal.coerceAtLeast(1))

    /**
     * Today's Activity card under the steps: the goal share, then your usual by now when there is one. One string per
     * pace, so a translator writes the lower case second half rather than the code lowering [pace]'s first letter.
     */
    fun activityLine(steps: Int, goal: Int, pace: UsualSteps.Pace?, today: Boolean, strings: Strings = Strings.app): String {
        val share = goalShare(steps, goal, strings)
        val id = when (pace) {
            null -> return share
            UsualSteps.Pace.More -> if (today) R.string.core_activity_line_more_now else R.string.core_activity_line_more_day
            UsualSteps.Pace.About -> if (today) R.string.core_activity_line_about_now else R.string.core_activity_line_about_day
            UsualSteps.Pace.Fewer -> if (today) R.string.core_activity_line_fewer_now else R.string.core_activity_line_fewer_day
        }
        return strings.format(id, share)
    }

    /** An hour's steps in the headline while it's tapped: "steps, 09:00 to 10:00". */
    fun hourLabel(hour: Int, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String {
        fun clock(h: Int) = TimeOfDay.minutes(h * MINUTES_PER_HOUR, locale = locale)
        return strings.format(R.string.core_activity_hour_label, clock(hour), clock(hour + 1))
    }

    private const val MINUTES_PER_HOUR = 60

    /* -------------------------------------------- Heart points ------------------------------------------ */

    /** The status word beside the 7-day total; null under the aim, where the sentence says how far to go. */
    fun heartPointsWord(total: Int, strings: Strings = Strings.app): String? =
        if (total >= HEART_POINTS_TARGET) strings.get(R.string.core_activity_points_at_aim) else null

    /** The week's sentence, under the total: how far to the aim, or that it's met. */
    fun heartPointsWeek(total: Int, strings: Strings = Strings.app): String = when {
        total >= HEART_POINTS_TARGET -> strings.format(R.string.core_activity_points_week_met, HEART_POINTS_TARGET)
        else -> strings.format(R.string.core_activity_points_week_to_go, HEART_POINTS_TARGET - total, HEART_POINTS_TARGET)
    }

    /** What drops off tomorrow: the oldest day's points. Null when that day earned none. */
    fun dropsOff(oldest: LocalDate, points: Int, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String? {
        if (points <= 0) return null
        val day = oldest.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        return strings.plural(R.plurals.core_activity_points_drop_off, points, points, day)
    }

    /** A day's points in the headline: "points earned, Tue 20 Oct". */
    fun earnedLabel(dayName: String, strings: Strings = Strings.app): String = strings.format(R.string.core_activity_points_earned, dayName)

    /** The month's sentence from each day's 7-day total: how often it was at the aim. Null without any. */
    fun heartPointsMonth(totals: List<Int?>, strings: Strings = Strings.app): String? {
        val known = totals.filterNotNull()
        if (known.isEmpty()) return null
        val at = known.count { it >= HEART_POINTS_TARGET }
        return when (at) {
            0 -> strings.format(R.string.core_activity_points_month_under, HEART_POINTS_TARGET, known.min(), known.max())
            known.size -> strings.format(R.string.core_activity_points_month_all, HEART_POINTS_TARGET)
            else -> strings.format(R.string.core_activity_points_month_some, HEART_POINTS_TARGET, at, known.size)
        }
    }

    /* ------------------------------------------- Active minutes ----------------------------------------- */

    /** The status word beside the week's active minutes; null under the goal. */
    fun activeWord(week: Int, strings: Strings = Strings.app): String? =
        if (week >= Activity.MVPA_WEEKLY_TARGET) strings.get(R.string.core_activity_active_goal_met) else null

    /** The week's sentence, under the week's minutes: how far to the 150, or by how much it's past. */
    fun activeWeek(week: Int, strings: Strings = Strings.app): String {
        val target = Activity.MVPA_WEEKLY_TARGET
        return when {
            week == target -> strings.format(R.string.core_activity_active_week_at, target)
            week > target -> strings.format(R.string.core_activity_active_week_past, week - target, target)
            else -> strings.format(R.string.core_activity_active_week_to_go, target - week, target)
        }
    }

    /** A day's sentence: its moderate and vigorous minutes. */
    fun activeDay(m: Activity.Mvpa, today: Boolean, strings: Strings = Strings.app): String {
        fun minutes(n: Int) = strings.plural(R.plurals.core_activity_minutes, n, n)
        return when {
            m.moderateMinutes == 0 && m.vigorousMinutes == 0 ->
                strings.get(if (today) R.string.core_activity_active_none_today else R.string.core_activity_active_none_day)
            m.vigorousMinutes == 0 -> strings.format(
                if (today) R.string.core_activity_active_brisk_today else R.string.core_activity_active_brisk_day,
                minutes(m.moderateMinutes),
            )
            m.moderateMinutes == 0 -> strings.format(
                if (today) R.string.core_activity_active_hard_today else R.string.core_activity_active_hard_day,
                minutes(m.vigorousMinutes),
            )
            else -> strings.format(
                if (today) R.string.core_activity_active_both_today else R.string.core_activity_active_both_day,
                minutes(m.moderateMinutes),
                minutes(m.vigorousMinutes),
            )
        }
    }

    /* ------------------------------------------------ Load ---------------------------------------------- */

    /** Load's scale in its info sheet, low to high, in the words beside it. A getter, so it follows the app's language. */
    val LOAD_SCALE: List<String> get() = listOf(LoadBand.Below, LoadBand.Usual, LoadBand.Above).map { it.text }

    /** Which bar of [LOAD_SCALE] a Load is in against your usual for a whole day, by [LoadBands]. Null without a usual. */
    fun loadBar(load: Float, usual: Float?): Int? = when (LoadBands.of(load, usual)) {
        null -> null
        LoadBand.Below -> 0
        LoadBand.Usual, LoadBand.SoFar -> 1
        LoadBand.Above -> 2
    }

    /** Your usual as a band on Load's chart: within [LoadBands.BAND] of it, as Today reads a usual Load. */
    fun usualBand(usual: Float?): ClosedFloatingPointRange<Float>? = LoadBands.usualRange(usual)

    /** The band's label: "Your usual, 30 to 50". */
    fun usualBandLabel(band: ClosedFloatingPointRange<Float>, strings: Strings = Strings.app): String =
        strings.format(R.string.core_activity_usual_band, Math.round(band.start), Math.round(band.endInclusive))

    /**
     * The day's sentence: how it sits among your recent days ([percentile], the share of the last 90 days' loads at or
     * below it), or before there are enough days, that your usual is being learned.
     */
    fun loadDay(percentile: Int?, today: Boolean, strings: Strings = Strings.app): String = when {
        percentile == null -> strings.get(R.string.core_activity_load_learning)
        percentile >= 50 -> strings.format(
            if (today) R.string.core_activity_load_harder_today else R.string.core_activity_load_harder_day,
            percentile,
        )
        else -> strings.format(
            if (today) R.string.core_activity_load_easier_today else R.string.core_activity_load_easier_day,
            100 - percentile,
        )
    }

    /**
     * The week's balance in a sentence, from its word ([TabCopy.loadBalance]). The word is matched against the same
     * strings, not against English, so it matches in every language.
     */
    fun balance(word: String?, strings: Strings = Strings.app): String? = when (word) {
        null -> null
        strings.get(R.string.core_tab_balance_building) -> strings.get(R.string.core_activity_balance_building)
        strings.get(R.string.core_tab_balance_easing) -> strings.get(R.string.core_activity_balance_easing)
        strings.get(R.string.core_tab_balance_steady) -> strings.get(R.string.core_activity_balance_steady)
        else -> null
    }

    /** A week's or month's sentence from daily Loads against your usual band: how many days sat above it. */
    fun loadSpan(levels: List<DetailCopy.Level?>, range: Range, strings: Strings = Strings.app): String? {
        val known = levels.filterNotNull()
        if (known.isEmpty()) return null
        val above = known.count { it == DetailCopy.Level.Above }
        val month = range == Range.Month
        return when (above) {
            0 -> strings.get(if (month) R.string.core_activity_span_none_month else R.string.core_activity_span_none_week)
            1 -> strings.get(if (month) R.string.core_activity_span_one_month else R.string.core_activity_span_one_week)
            else -> strings.plural(
                if (month) R.plurals.core_activity_span_days_month else R.plurals.core_activity_span_days_week,
                above,
                above,
            )
        }
    }

    /** The strap's own weekly load and the range it calls productive, as a row's status. */
    fun strapRange(low: Int, high: Int, strings: Strings = Strings.app): String =
        strings.format(R.string.core_activity_strap_range, low, high)

    /* --------------------------------------------- VO2 max ---------------------------------------------- */

    /** The sentence: where the number comes from. */
    fun vo2(fromWorkout: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (fromWorkout) R.string.core_activity_vo2_workout else R.string.core_activity_vo2_profile)

    /* ------------------------------------------ Time to recover ----------------------------------------- */

    /**
     * When the workout time to recover counts from ended, by [DayWords]: "today at 18:30", "yesterday at 07:10",
     * "Tuesday at 18:30", "Tue 13 Oct at 18:30".
     */
    fun ended(
        date: LocalDate, clock: String, today: LocalDate, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): String =
        strings.format(R.string.core_activity_ended, DayWords.inSentence(date, today, locale), clock)

    /** The sentence under the hours to go: which workout they count from. */
    fun recover(sport: String, ended: String, strings: Strings = Strings.app): String =
        strings.format(R.string.core_activity_recover, sport.lowercase(), ended)

    /* ---------------------------------------------- Calories -------------------------------------------- */

    /** The sentence under the day's kcal: what your body uses at rest, before any movement. */
    fun caloriesAtRest(atRest: Int, strings: Strings = Strings.app): String =
        strings.format(R.string.core_activity_calories_at_rest, Units.count(atRest.toLong()))

    /* ---------------------------------------------- Distance -------------------------------------------- */

    /** The distance row's line and the detail's label: where the number comes from. */
    fun distanceSource(fromSteps: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (fromSteps) R.string.core_activity_distance_from_steps else R.string.core_activity_distance_from_strap)

    /** The sentence under the kilometres: the steps they come from. */
    fun distance(steps: Int, today: Boolean, strings: Strings = Strings.app): String = strings.format(
        if (today) R.string.core_activity_distance_steps_today else R.string.core_activity_distance_steps_day,
        Units.count(steps.toLong()),
    )
}
