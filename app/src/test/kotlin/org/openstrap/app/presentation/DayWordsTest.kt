package org.openstrap.app.presentation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.openstrap.app.testing.LocaleRule
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.assertCleanCopy
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.TextStyle
import java.util.Locale

/** One rule for day words, in each test language, seen from the days the clocks change. */
@RunWith(Parameterized::class)
class DayWordsTest(private val locale: Locale) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun locales() = localeParameters()

        /** Both Sundays, the days the clocks change in Berlin. */
        private val DST_START = LocalDate.of(2026, 3, 29)
        private val DST_END = LocalDate.of(2026, 10, 25)
    }

    @get:Rule val localeRule = LocaleRule(locale)
    @get:Rule val zoneRule = ZoneRule(ZoneId.of("Asia/Kathmandu"))

    private val produced = mutableListOf<String>()
    private fun String.kept() = also(produced::add)

    @After
    fun cleanCopy() = assertCleanCopy(produced)

    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    /** The date in [locale]'s words and its own digits, Arabic-Indic under `ar`. */
    private fun dated(d: LocalDate, pattern: String = "EEE d MMM") =
        d.format(DateTimeFormatter.ofPattern(pattern, locale).withDecimalStyle(DecimalStyle.of(locale)))
    private fun n(ascii: String) = localNumber(ascii, locale)

    @Test
    fun todayYesterdayThenTheWeekdayThenTheDate() {
        for (today in listOf(DST_START, DST_END)) {
            assertEquals("Today", DayWords.relative(today, today).kept())
            assertEquals("Yesterday", DayWords.relative(today.minusDays(1), today).kept())
            for (back in 2L..6L) assertEquals(weekday(today.minusDays(back)), DayWords.relative(today.minusDays(back), today).kept())
            assertEquals(dated(today.minusDays(7)), DayWords.relative(today.minusDays(7), today).kept())
            assertEquals(dated(today.minusDays(40)), DayWords.relative(today.minusDays(40), today).kept())
        }
    }

    @Test
    fun anEarlierYearAddsTheYear() {
        val d = LocalDate.of(2025, 10, 21)
        assertEquals(dated(d, "EEE d MMM yyyy"), DayWords.relative(d, DST_START).kept())
        // Across new year, still within the week: the weekday, no year.
        assertEquals(weekday(LocalDate.of(2025, 12, 30)), DayWords.relative(LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 2)))
    }

    @Test
    fun aDayAheadTakesItsDate() {
        assertEquals(dated(DST_START.plusDays(1)), DayWords.relative(DST_START.plusDays(1), DST_START))
    }

    @Test
    fun inEnglishItReadsAsDecided() {
        if (locale.language != "en") return
        assertEquals("Friday", DayWords.relative(LocalDate.of(2026, 3, 27), DST_START))
        assertEquals("Sun 22 Mar", DayWords.relative(LocalDate.of(2026, 3, 22), DST_START))
        assertEquals("Tue 21 Oct 2025", DayWords.relative(LocalDate.of(2025, 10, 21), DST_START))
        assertEquals("Tue 20 Oct", DayWords.short(LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun lowercaseAfterOtherWords() {
        assertEquals("today", DayWords.inSentence(DST_END, DST_END))
        assertEquals("yesterday", DayWords.inSentence(DST_END.minusDays(1), DST_END))
        assertEquals(weekday(DST_END.minusDays(3)), DayWords.inSentence(DST_END.minusDays(3), DST_END))
        assertEquals(dated(DST_END.minusDays(9)), DayWords.inSentence(DST_END.minusDays(9), DST_END))
    }

    @Test
    fun shortIsAlwaysDated() {
        assertEquals(dated(DST_START), DayWords.short(DST_START).kept())
        assertEquals(dated(LocalDate.of(2025, 10, 21)), DayWords.short(LocalDate.of(2025, 10, 21)))
    }

    private fun epoch(utc: String) = Instant.parse(utc).epochSecond
    private fun amPm(time: LocalTime) = DateTimeFormatter.ofPattern("a", locale).format(time)

    @Test
    fun withATimeOnTheDayTheClocksGoForward() {
        val now = epoch("2026-03-29T12:00:00Z")
        // 00:30 on the 29th in Berlin, an hour before the change: today.
        assertEquals(n("00:30"), DayWords.at(epoch("2026-03-28T23:30:00Z"), now, BERLIN, use24Hour = true).kept())
        // 23:59 on the 28th.
        assertEquals(n("yesterday 23:59"), DayWords.at(epoch("2026-03-28T22:59:00Z"), now, BERLIN, use24Hour = true).kept())
        assertEquals(n("yesterday 11:59 ") + amPm(LocalTime.of(23, 59)), DayWords.at(epoch("2026-03-28T22:59:00Z"), now, BERLIN, use24Hour = false).kept())
        assertEquals(
            weekday(LocalDate.of(2026, 3, 26)) + n(" 10:42"),
            DayWords.at(epoch("2026-03-26T09:42:00Z"), now, BERLIN, use24Hour = true).kept(),
        )
        assertEquals(dated(LocalDate.of(2026, 3, 2)) + n(" 10:42"), DayWords.at(epoch("2026-03-02T09:42:00Z"), now, BERLIN, use24Hour = true).kept())
    }

    @Test
    fun withATimeOnTheDayTheClocksGoBack() {
        val now = epoch("2026-10-25T23:30:00Z") // 00:30 on the 26th in Berlin, after a 25 hour day
        assertEquals(n("yesterday 00:30"), DayWords.at(epoch("2026-10-24T22:30:00Z"), now, BERLIN, use24Hour = true).kept())
        assertEquals(n("yesterday 02:30"), DayWords.at(epoch("2026-10-25T01:30:00Z"), now, BERLIN, use24Hour = true))
        assertEquals(n("00:10"), DayWords.at(epoch("2026-10-25T23:10:00Z"), now, BERLIN, use24Hour = true))
        assertEquals(n("12:10 ") + amPm(LocalTime.of(0, 10)), DayWords.at(epoch("2026-10-25T23:10:00Z"), now, BERLIN, use24Hour = false).kept())
    }

    @Test
    fun theDayFollowsTheZoneGivenNotThePhones() {
        // 23:30 UTC on the 28th is already the 29th in Berlin, and the 29th in Kathmandu too, but not in New York.
        val now = epoch("2026-03-29T12:00:00Z")
        val t = epoch("2026-03-28T23:30:00Z")
        assertEquals(n("yesterday 19:30"), DayWords.at(t, now, ZoneId.of("America/New_York"), use24Hour = true))
    }

    @Test
    fun datesAndClocksUseTheLanguagesDigits() {
        val d = LocalDate.of(2026, 10, 21)
        val short = DayWords.short(d).kept()
        if (locale.language == "ar") assertTrue("no Arabic-Indic digits in $short", short.contains('\u0662') && short.none { it in '0'..'9' })
        if (locale.language != "ar") assertTrue(short.contains("21"))
        val now = epoch("2026-03-29T12:00:00Z")
        val at = DayWords.at(epoch("2026-03-29T09:42:00Z"), now, BERLIN, use24Hour = true)
        assertEquals(n("11:42"), at)
    }

    @Test
    fun whenDoneSaysTodayYesterdayOrTheDate() {
        val now = epoch("2026-03-29T12:00:00Z")
        assertEquals("today at " + n("11:42"), DayWords.whenDone(epoch("2026-03-29T09:42:00Z"), now, BERLIN, use24Hour = true).kept())
        assertEquals("yesterday at " + n("23:59"), DayWords.whenDone(epoch("2026-03-28T22:59:00Z"), now, BERLIN, use24Hour = true).kept())
        assertEquals("on " + dated(LocalDate.of(2026, 3, 20)), DayWords.whenDone(epoch("2026-03-20T09:42:00Z"), now, BERLIN, use24Hour = true).kept())
    }

    @Test
    fun weekdaysAreTheLanguagesOwn() {
        if (locale.language == "de") assertEquals("Freitag", DayWords.relative(LocalDate.of(2026, 3, 27), DST_START))
        assertEquals(DayOfWeek.FRIDAY.getDisplayName(TextStyle.FULL, locale), DayWords.relative(LocalDate.of(2026, 3, 27), DST_START))
    }
}
