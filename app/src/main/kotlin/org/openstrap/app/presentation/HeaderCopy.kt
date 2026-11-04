package org.openstrap.app.presentation

import androidx.annotation.StringRes
import java.time.LocalDate
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.data.Moment
import org.openstrap.app.i18n.Strings

/**
 * The header's title for [date] on Today, by [DayWords]: "Today", "Yesterday", the weekday within the week ("Tuesday"),
 * then the date ("Tue 21 Oct").
 */
fun dayTitle(date: LocalDate, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String =
    DayWords.relative(date, today, locale)

/**
 * The header's title on Today (spec 3.1): the [moment] for today, [dayTitle] for a past day, which keeps its name.
 * During a workout it's the sport ("Running"), after one it names it when it can ("After your ride"): [sportType] is
 * the one recording, or the last one done.
 */
fun todayTitle(
    moment: Moment, date: LocalDate, sportType: Int? = null, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault(),
    strings: Strings = Strings.app,
): String =
    if (date < today) dayTitle(date, today, locale) else when (moment) {
        Moment.During -> sportType?.let { sportDoing(it, strings) } ?: strings.get(R.string.core_header_during_workout)
        Moment.After -> sportType?.let { sportNoun(it, strings) }?.let { strings.format(R.string.core_header_after_sport, it) }
            ?: strings.get(R.string.core_header_after_workout)
        Moment.WorkoutWindow -> strings.get(R.string.core_header_workout_window)
        Moment.Waking -> strings.get(R.string.core_header_waking)
        Moment.Night -> strings.get(R.string.core_header_night)
        Moment.Evening -> strings.get(R.string.core_header_evening)
        Moment.Midday -> strings.get(R.string.core_header_midday)
    }

/**
 * The sports with a short noun, each with its noun ("run"), what you're doing in it ("Running"), the workout window's
 * sentence ("You usually run around now.") and the evening list's item ("a run").
 */
private enum class ShortSport(
    @StringRes val noun: Int, @StringRes val doing: Int, @StringRes val usually: Int, @StringRes val one: Int,
) {
    Run(
        R.string.core_header_sport_run, R.string.core_header_doing_run, R.string.core_today_window_usually_run,
        R.string.core_today_evening_one_run,
    ),
    Walk(
        R.string.core_header_sport_walk, R.string.core_header_doing_walk, R.string.core_today_window_usually_walk,
        R.string.core_today_evening_one_walk,
    ),
    Ride(
        R.string.core_header_sport_ride, R.string.core_header_doing_ride, R.string.core_today_window_usually_ride,
        R.string.core_today_evening_one_ride,
    ),
    Swim(
        R.string.core_header_sport_swim, R.string.core_header_doing_swim, R.string.core_today_window_usually_swim,
        R.string.core_today_evening_one_swim,
    ),
    Hike(
        R.string.core_header_sport_hike, R.string.core_header_doing_hike, R.string.core_today_window_usually_hike,
        R.string.core_today_evening_one_hike,
    ),
    Row(
        R.string.core_header_sport_row, R.string.core_header_doing_row, R.string.core_today_window_usually_row,
        R.string.core_today_evening_one_row,
    ),
}

// Sport ids as the strap numbers them, read here only, so naming each would add nothing.
@Suppress("MagicNumber")
private fun shortSport(sportType: Int): ShortSport? = when (sportType) {
    1, 2, 11 -> ShortSport.Run
    3, 40, 215 -> ShortSport.Walk
    4, 8, 143 -> ShortSport.Ride
    6, 7 -> ShortSport.Swim
    15, 224 -> ShortSport.Hike
    23 -> ShortSport.Row
    else -> null
}

/** A sport as a short noun ("run", "ride"), for "After your run", or null where none reads well, so it says "workout". */
internal fun sportNoun(sportType: Int, strings: Strings = Strings.app): String? = shortSport(sportType)?.let { strings.get(it.noun) }

/** The workout window's sentence for [sportType]: "You usually run around now.", or "work out" for any other sport or none. */
internal fun sportUsually(sportType: Int?, strings: Strings = Strings.app): String =
    strings.get(sportType?.let(::shortSport)?.usually ?: R.string.core_today_window_usually_workout)

/** One workout of [sportType] as an item of the evening list: "a run", or "a workout" for any other sport. */
internal fun sportOne(sportType: Int, strings: Strings = Strings.app): String =
    strings.get(shortSport(sportType)?.one ?: R.string.core_today_evening_one_workout)

/** A sport as what you're doing ("Running"), for the header during it; its name where no verb reads well. */
internal fun sportDoing(sportType: Int, strings: Strings = Strings.app): String =
    shortSport(sportType)?.let { strings.get(it.doing) } ?: org.openstrap.app.data.Workouts.sportName(sportType)
