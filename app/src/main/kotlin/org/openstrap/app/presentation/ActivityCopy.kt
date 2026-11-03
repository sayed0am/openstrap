package org.openstrap.app.presentation

import org.openstrap.app.data.Activity
import org.openstrap.app.data.UsualSteps
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/*
 * The words of the Activity tab's details (spec 5.3 and 6.1): steps against your usual by now, Heart points, active
 * minutes, Load, VO2 max, time to recover, calories and distance. Built only from numbers the app already has, like
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
        UsualSteps.Pace.More -> if (today) "More than usual by now" else "More than your usual day"
        UsualSteps.Pace.About -> if (today) "About usual by now" else "About your usual day"
        UsualSteps.Pace.Fewer -> if (today) "Fewer than usual by now" else "Fewer than your usual day"
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
        if (steps >= goal) "Goal met" else "${steps * 100 / goal.coerceAtLeast(1)}% of your goal"

    /** Today's Activity card under the steps: the goal share, then your usual by now when there is one. */
    fun activityLine(steps: Int, goal: Int, pace: UsualSteps.Pace?, today: Boolean): String {
        val share = goalShare(steps, goal)
        return if (pace == null) share else "$share, ${pace(pace, today).replaceFirstChar { it.lowercase() }}"
    }

    /** An hour's steps in the headline while it's tapped: "steps, 09:00 to 10:00". */
    fun hourLabel(hour: Int, locale: Locale = Locale.getDefault()): String {
        fun clock(h: Int) = TimeOfDay.minutes(h * MINUTES_PER_HOUR, locale = locale)
        return "steps, ${clock(hour)} to ${clock(hour + 1)}"
    }

    private const val MINUTES_PER_HOUR = 60

    /* -------------------------------------------- Heart points ------------------------------------------ */

    /** The status word beside the 7-day total; null under the aim, where the sentence says how far to go. */
    fun heartPointsWord(total: Int): String? = if (total >= HEART_POINTS_TARGET) "At your aim" else null

    /** The week's sentence, under the total: how far to the aim, or that it's met. */
    fun heartPointsWeek(total: Int): String = when {
        total >= HEART_POINTS_TARGET -> "Keeping it at $HEART_POINTS_TARGET or more week after week is what counts."
        else -> "${HEART_POINTS_TARGET - total} to go for $HEART_POINTS_TARGET."
    }

    /** What drops off tomorrow: the oldest day's points. Null when that day earned none. */
    fun dropsOff(oldest: LocalDate, points: Int, locale: Locale = Locale.getDefault()): String? {
        if (points <= 0) return null
        val day = oldest.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        return "Tomorrow $day's $points ${if (points == 1) "point drops" else "points drop"} off."
    }

    /** A day's points in the headline: "points earned, Tue 20 Oct". */
    fun earnedLabel(dayName: String): String = "points earned, $dayName"

    /** The month's sentence from each day's 7-day total: how often it was at the aim. Null without any. */
    fun heartPointsMonth(totals: List<Int?>): String? {
        val known = totals.filterNotNull()
        if (known.isEmpty()) return null
        val at = known.count { it >= HEART_POINTS_TARGET }
        return when (at) {
            0 -> "Under $HEART_POINTS_TARGET every day in the last 30, between ${known.min()} and ${known.max()}."
            known.size -> "At $HEART_POINTS_TARGET or more every day with a total in the last 30."
            else -> "At $HEART_POINTS_TARGET or more on $at of ${known.size} days in the last 30."
        }
    }

    /* ------------------------------------------- Active minutes ----------------------------------------- */

    /** The status word beside the week's active minutes; null under the goal. */
    fun activeWord(week: Int): String? = if (week >= Activity.MVPA_WEEKLY_TARGET) "Goal met" else null

    /** The week's sentence, under the week's minutes: how far to the 150, or by how much it's past. */
    fun activeWeek(week: Int): String {
        val target = Activity.MVPA_WEEKLY_TARGET
        return when {
            week == target -> "Right at the $target to aim for."
            week > target -> "${week - target} past the $target to aim for."
            else -> "${target - week} to go for $target."
        }
    }

    /** A day's sentence: its moderate and vigorous minutes. */
    fun activeDay(m: Activity.Mvpa, today: Boolean): String {
        val lead = if (today) "So far today" else "That day"
        return when {
            m.moderateMinutes == 0 && m.vigorousMinutes == 0 -> if (today) "No active minutes so far today." else "No active minutes that day."
            m.vigorousMinutes == 0 -> "$lead, ${minutes(m.moderateMinutes)} of brisk movement."
            m.moderateMinutes == 0 -> "$lead, ${minutes(m.vigorousMinutes)} of hard effort."
            else -> "$lead, ${minutes(m.moderateMinutes)} brisk and ${minutes(m.vigorousMinutes)} hard."
        }
    }

    private fun minutes(n: Int) = if (n == 1) "1 minute" else "$n minutes"

    /* ------------------------------------------------ Load ---------------------------------------------- */

    /** Load's scale in its info sheet, low to high, in the words beside it. */
    val LOAD_SCALE = listOf(LoadBand.Below, LoadBand.Usual, LoadBand.Above).map { it.text }

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
    fun usualBandLabel(band: ClosedFloatingPointRange<Float>): String = "Your usual, ${Math.round(band.start)} to ${Math.round(band.endInclusive)}"

    /**
     * The day's sentence: how it sits among your recent days ([percentile], the share of the last 90 days' loads at or
     * below it), or before there are enough days, that your usual is being learned.
     */
    fun loadDay(percentile: Int?, today: Boolean): String {
        val lead = if (today) "So far today" else "That day"
        return when {
            percentile == null -> "Your usual shows after a week of wear."
            percentile >= 50 -> "$lead, as hard as or harder than $percentile% of your days in the last 3 months."
            else -> "$lead, easier than ${100 - percentile}% of your days in the last 3 months."
        }
    }

    /** The week's balance in a sentence, from its word ([TabCopy.loadBalance]). */
    fun balance(word: String?): String? = when (word) {
        "Building" -> "Your last 7 days are well above your usual, so you're building."
        "Easing off" -> "Your last 7 days are well below your usual, so you're easing off."
        "Steady" -> "Your last 7 days are close to your usual, a steady stretch."
        else -> null
    }

    /** A week's or month's sentence from daily Loads against your usual band: how many days sat above it. */
    fun loadSpan(levels: List<DetailCopy.Level?>, range: Range): String? {
        val known = levels.filterNotNull()
        if (known.isEmpty()) return null
        val above = known.count { it == DetailCopy.Level.Above }
        val over = if (range == Range.Month) "in the last 30 days" else "in the last 7 days"
        return when (above) {
            0 -> "No day above your usual $over."
            1 -> "One day above your usual $over."
            else -> "$above days above your usual $over."
        }
    }

    /** The strap's own weekly load and the range it calls productive, as a row's status. */
    fun strapRange(low: Int, high: Int): String = "The strap's productive range is $low to $high"

    /* --------------------------------------------- VO2 max ---------------------------------------------- */

    /** The sentence: where the number comes from. */
    fun vo2(fromWorkout: Boolean): String =
        if (fromWorkout) "From your latest workout the strap could read it from." else "Estimated from your profile and resting heart rate, updated weekly."

    /* ------------------------------------------ Time to recover ----------------------------------------- */

    /**
     * When the workout time to recover counts from ended, by [DayWords]: "today at 18:30", "yesterday at 07:10",
     * "Tuesday at 18:30", "Tue 13 Oct at 18:30".
     */
    fun ended(date: LocalDate, clock: String, today: LocalDate, locale: Locale = Locale.getDefault()): String =
        "${DayWords.inSentence(date, today, locale)} at $clock"

    /** The sentence under the hours to go: which workout they count from. */
    fun recover(sport: String, ended: String): String = "Counted from your ${sport.lowercase()}, which ended $ended."

    /* ---------------------------------------------- Calories -------------------------------------------- */

    /** The sentence under the day's kcal: what your body uses at rest, before any movement. */
    fun caloriesAtRest(atRest: Int): String = "Your body uses about ${Units.count(atRest.toLong())} kcal a day at rest, before any movement."

    /* ---------------------------------------------- Distance -------------------------------------------- */

    /** The distance row's line and the detail's label: where the number comes from. */
    fun distanceSource(fromSteps: Boolean): String = if (fromSteps) "km, from your steps" else "km, from the strap"

    /** The sentence under the kilometres: the steps they come from. */
    fun distance(steps: Int, today: Boolean): String =
        "Over ${Units.count(steps.toLong())} steps ${if (today) "so far today" else "that day"}."
}
