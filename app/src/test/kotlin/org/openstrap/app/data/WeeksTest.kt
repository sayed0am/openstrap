package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.openstrap.app.presentation.localeParameters
import org.openstrap.app.testing.LocaleRule
import org.openstrap.app.testing.ZoneRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** A week starts on the phone's locale's first day, in each test language, over the clock change Sundays. */
@RunWith(Parameterized::class)
class WeeksTest(private val locale: Locale) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun locales() = localeParameters()

        private val DST_START = LocalDate.of(2026, 3, 29)
        private val DST_END = LocalDate.of(2026, 10, 25)
    }

    @get:Rule val localeRule = LocaleRule(locale)
    @get:Rule val zoneRule = ZoneRule(ZoneId.of("Europe/Berlin"))

    @Test
    fun firstDayIsTheLocales() {
        assertEquals(WeekFields.of(locale).firstDayOfWeek, Weeks.firstDay())
        assertEquals(Weeks.firstDay(locale), Weeks.firstDay())
        when (locale.toLanguageTag()) {
            "de-DE" -> assertEquals(DayOfWeek.MONDAY, Weeks.firstDay())
            "en-US" -> assertEquals(DayOfWeek.SUNDAY, Weeks.firstDay())
        }
    }

    @Test
    fun theWeekHoldsTheDay() {
        for (date in listOf(DST_START, DST_END, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))) {
            val start = Weeks.start(date)
            assertEquals(Weeks.firstDay(), start.dayOfWeek)
            assertTrue(!start.isAfter(date) && date.isBefore(start.plusDays(7)))
            assertEquals(start.plusDays(6), Weeks.end(date))
            val days = Weeks.days(date)
            assertEquals(7, days.size)
            assertEquals((0L..6L).map(start::plusDays), days)
            assertTrue(date in days)
        }
    }

    @Test
    fun clockChangeSundays() {
        // Both are Sundays: the first day of a US week, the last of a German one.
        assertEquals(DST_START, Weeks.start(DST_START, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 3, 23), Weeks.start(DST_START, DayOfWeek.MONDAY))
        assertEquals(DST_END, Weeks.end(DST_END, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 10, 31), Weeks.end(DST_END, DayOfWeek.SUNDAY))
        if (locale.toLanguageTag() == "en-US") assertEquals(DST_START, Weeks.start(DST_START))
        if (locale.toLanguageTag() == "de-DE") assertEquals(LocalDate.of(2026, 3, 23), Weeks.start(DST_START))
    }

    @Test
    fun matchesTheRewardsWeek() {
        for (first in DayOfWeek.entries) {
            for (offset in 0L..13L) {
                val date = DST_START.plusDays(offset)
                assertEquals(Rewards.weekStart(date, first), Weeks.start(date, first))
            }
        }
    }
}
