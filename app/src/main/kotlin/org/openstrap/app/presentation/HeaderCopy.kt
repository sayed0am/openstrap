package org.openstrap.app.presentation

import java.time.LocalDate
import java.util.Locale
import org.openstrap.app.data.Moment

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
): String =
    if (date < today) dayTitle(date, today, locale) else when (moment) {
        Moment.During -> sportType?.let(::sportDoing) ?: "Workout"
        Moment.After -> "After your ${sportType?.let(::sportNoun) ?: "workout"}"
        Moment.WorkoutWindow -> "Workout time"
        Moment.Waking -> "Waking up"
        Moment.Night -> "Night"
        Moment.Evening -> "Evening"
        Moment.Midday -> "Midday"
    }

/** A sport as a short noun ("run", "ride"), for "After your run"; null where none reads well, so it says "workout". */
// Sport ids as the strap numbers them, read here only, so naming each would add nothing.
@Suppress("MagicNumber")
internal fun sportNoun(sportType: Int): String? = when (sportType) {
    1, 2, 11 -> "run"
    3, 40, 215 -> "walk"
    4, 8, 143 -> "ride"
    6, 7 -> "swim"
    15, 224 -> "hike"
    23 -> "row"
    else -> null
}

/** A sport as what you're doing ("Running"), for the header during it; its name where no verb reads well. */
internal fun sportDoing(sportType: Int): String = when (sportNoun(sportType)) {
    "run" -> "Running"
    "walk" -> "Walking"
    "ride" -> "Cycling"
    "swim" -> "Swimming"
    "hike" -> "Hiking"
    "row" -> "Rowing"
    else -> org.openstrap.app.data.Workouts.sportName(sportType)
}
