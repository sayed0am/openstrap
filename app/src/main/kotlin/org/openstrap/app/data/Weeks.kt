package org.openstrap.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * What "a week" is everywhere in the app (decision 7 in presentation's `Units`): it starts on the phone's locale's
 * first day, Monday on most phones outside the Americas and Sunday in the US. Rewards, history, recovery, Today, the
 * charts and the widgets all ask here, so "this week" is the same week on every screen. It lives in data because the
 * data package groups by week too.
 */
object Weeks {
    const val DAYS = 7

    /** The first day of the week in [locale]. */
    fun firstDay(locale: Locale = Locale.getDefault()): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

    /** The first day of [date]'s week: [date] itself when it's [firstDay]. */
    fun start(date: LocalDate, firstDay: DayOfWeek = firstDay()): LocalDate = date.with(TemporalAdjusters.previousOrSame(firstDay))

    /** The last day of [date]'s week. */
    fun end(date: LocalDate, firstDay: DayOfWeek = firstDay()): LocalDate = start(date, firstDay).plusDays(DAYS - 1L)

    /** The seven days of [date]'s week, first to last. */
    fun days(date: LocalDate, firstDay: DayOfWeek = firstDay()): List<LocalDate> =
        start(date, firstDay).let { s -> List(DAYS) { s.plusDays(it.toLong()) } }
}
