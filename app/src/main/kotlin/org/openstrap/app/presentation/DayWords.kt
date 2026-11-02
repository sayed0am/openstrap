package org.openstrap.app.presentation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * When a day was, in words (decision 6 in [Units]): "Today", "Yesterday", the weekday for the rest of the last 7 days
 * ("Tuesday"), then the date ("Tue 21 Oct"), with the year when it's from an earlier one ("Tue 21 Oct 2025"). The
 * words are the app's English, the weekday and month names the phone's language.
 */
object DayWords {
    const val TODAY = "Today"
    const val YESTERDAY = "Yesterday"

    /** A day this many days back or fewer, past yesterday, goes by its weekday. A week back would repeat today's. */
    private const val WEEKDAY_WITHIN_DAYS = 6L

    private const val SHORT = "EEE d MMM"
    private const val SHORT_WITH_YEAR = "EEE d MMM yyyy"

    /**
     * [date] seen from [today], for a heading: "Today", "Yesterday", "Tuesday", "Tue 21 Oct", "Tue 21 Oct 2025". A
     * day after [today] has no word for it, so it takes its date.
     */
    fun relative(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days == 0L -> TODAY
            days == 1L -> YESTERDAY
            days in 2..WEEKDAY_WITHIN_DAYS -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            date.year == today.year -> date.format(Formatters.of(SHORT, locale))
            else -> date.format(Formatters.of(SHORT_WITH_YEAR, locale))
        }
    }

    /** [relative] after other words: "today" and "yesterday" in lowercase, a weekday or date as it is. */
    fun inSentence(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String =
        when (val word = relative(date, today, locale)) {
            TODAY, YESTERDAY -> word.lowercase(Locale.ROOT)
            else -> word
        }

    /**
     * A moment joined with its time, after other words: "10:42" today, "yesterday 10:42", "Tuesday 10:42",
     * "Tue 21 Oct 10:42". [now] and [epoch] in epoch seconds.
     */
    fun at(
        epoch: Long, now: Long, zone: ZoneId, use24Hour: Boolean = ClockSetting.use24Hour, locale: Locale = Locale.getDefault(),
    ): String {
        val clock = TimeOfDay.format(epoch, zone, use24Hour, locale)
        val date = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate()
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        return if (date == today) clock else "${inSentence(date, today, locale)} $clock"
    }

    /**
     * When something last happened, after other words: "today at 10:42", "yesterday at 10:42", or further back the
     * date alone, "on Tue 21 Oct". For a backup or an update check, where an older time no longer matters.
     */
    fun whenDone(
        epoch: Long, now: Long, zone: ZoneId, use24Hour: Boolean = ClockSetting.use24Hour, locale: Locale = Locale.getDefault(),
    ): String {
        val date = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate()
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val clock = TimeOfDay.format(epoch, zone, use24Hour, locale)
        return when (date) {
            today -> "today at $clock"
            today.minusDays(1) -> "yesterday at $clock"
            else -> "on ${short(date, locale)}"
        }
    }

    /** A day for a chart or a short label, always dated: "Tue 21 Oct". */
    fun short(date: LocalDate, locale: Locale = Locale.getDefault()): String = date.format(Formatters.of(SHORT, locale))
}
