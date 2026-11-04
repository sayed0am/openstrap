package org.openstrap.app.presentation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openstrap.app.testing.LocaleRule
import org.openstrap.app.testing.assertCleanCopy
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Every range says "to", never a dash (user, 2026-11-03 and 2026-11-04), in the phone's clock and digits. */
class RangesTest {
    @get:Rule val locale = LocaleRule(Locale.US)

    @After fun clock() { ClockSetting.use24Hour = true }

    private fun at(zone: ZoneId, h: Int, m: Int, day: Int = 22) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toEpochSecond()

    @Test fun `two written ends join with to`() {
        assertEquals("Mon to Sun", Ranges.of("Mon", "Sun"))
        assertEquals("3 Nov to 9 Nov", Ranges.of("3 Nov", "9 Nov"))
    }

    @Test fun `a time range follows the phone's 12 or 24 hour clock`() {
        val zone = ZoneOffset.UTC
        val start = at(zone, 23, 5)
        val end = at(zone, 8, 16, day = 23)
        ClockSetting.use24Hour = true
        assertEquals("23:05 to 08:16", Ranges.times(start, end, zone))
        ClockSetting.use24Hour = false
        assertEquals("11:05 PM to 8:16 AM", Ranges.times(start, end, zone))
        assertCleanCopy(Ranges.times(start, end, zone))
    }

    @Test fun `a time range is the zone's wall clock, a non whole hour offset included`() {
        // Kathmandu is UTC+5:45: 17:13 UTC is 22:58 there.
        val utc = ZoneOffset.UTC
        val start = at(utc, 17, 13)
        val end = at(utc, 1, 11, day = 23)
        assertEquals("22:58 to 06:56", Ranges.times(start, end, ZoneId.of("Asia/Kathmandu"), use24Hour = true))
    }

    @Test fun `a number range writes the phone's digits and thousands marks`() {
        assertEquals("20 to 60", Ranges.numbers(20, 60))
        assertEquals("8,000 to 10,000", Ranges.numbers(8000, 10_000))
        assertEquals("8.000 to 10.000", Ranges.numbers(8000, 10_000, Locale.GERMANY))
    }
}
