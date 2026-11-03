package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.db.DayKeyMerge
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Stored days as dates (item 17): the key, old keys read back as dates, and which row a date keeps. */
class DayKeyTest {
    private val zones = listOf("Asia/Dubai", "Europe/London", "America/New_York", "Pacific/Auckland", "Asia/Kathmandu", "America/Santiago")
        .map(ZoneId::of)

    @Test fun `a key is the date's UTC midnight, the same in every zone`() {
        val d = LocalDate.of(2026, 11, 3)
        assertEquals(Instant.parse("2026-11-03T00:00:00Z").epochSecond, DayKey.of(d))
        assertEquals(d, DayKey.date(DayKey.of(d)))
        assertEquals(LocalDate.of(1969, 12, 31), DayKey.date(-1))
    }

    @Test fun `an old key comes back as its own date in the zone it was written in, every day of 2026`() {
        for (zone in zones) {
            var d = LocalDate.of(2026, 1, 1)
            while (d.year == 2026) {
                assertEquals("$d in $zone", d, DayKey.fromLegacy(Days.start(d, zone), zone))
                d = d.plusDays(1)
            }
        }
    }

    @Test fun `a skipped midnight's first moment is that day`() {
        // Santiago springs forward at midnight on 6 September 2026: the day starts at 01:00.
        val santiago = ZoneId.of("America/Santiago")
        val d = LocalDate.of(2026, 9, 6)
        assertEquals(java.time.LocalTime.of(1, 0), Instant.ofEpochSecond(Days.start(d, santiago)).atZone(santiago).toLocalTime())
        assertEquals(d, DayKey.fromLegacy(Days.start(d, santiago), santiago))
    }

    @Test fun `an old key from another zone rounds to the nearest midnight, a day off at 12 hours or more`() {
        val d = LocalDate.of(2026, 1, 15)
        // 9 hours: Dubai's midnight is 15:00 the day before in New York, which rounds up.
        assertEquals(d, DayKey.fromLegacy(Days.start(d, ZoneId.of("Asia/Dubai")), ZoneId.of("America/New_York")))
        // And New York's midnight is 09:00 in Dubai, which rounds down.
        assertEquals(d, DayKey.fromLegacy(Days.start(d, ZoneId.of("America/New_York")), ZoneId.of("Asia/Dubai")))
        // 13 hours: Auckland's midnight is 11:00 the day before in London.
        assertEquals(d.minusDays(1), DayKey.fromLegacy(Days.start(d, ZoneId.of("Pacific/Auckland")), ZoneId.of("Europe/London")))
        // Exactly 12 hours: noon rounds up, to the right date.
        assertEquals(d, DayKey.fromLegacy(Days.start(d, ZoneId.of("+12:00")), ZoneId.of("UTC")))
    }

    @Test fun `the dates in a span of moments are those whose midnight is in it`() {
        val zone = ZoneId.of("Asia/Kathmandu")
        val d = LocalDate.of(2026, 10, 30)
        val midnight = Days.start(d, zone)
        assertEquals(d..d.plusDays(2), DayKey.datesIn(midnight, Days.start(d.plusDays(2), zone), zone))
        // From just after a midnight, that day's midnight isn't in it.
        assertEquals(d.plusDays(1)..d.plusDays(2), DayKey.datesIn(midnight + 1, Days.start(d.plusDays(2), zone) + 3600, zone))
        val none = DayKey.datesIn(midnight + 1, midnight + 3600, zone)
        assertTrue(none.isEmpty())
    }

    @Test fun `a date keeps the row its table's rule picks, whatever order they come in`() {
        val c = listOf(DayKeyMerge.Candidate(100, 5), DayKeyMerge.Candidate(200, 9), DayKeyMerge.Candidate(300, 9))
        assertEquals(300L, DayKeyMerge.winner(DayKeyMerge.Rule.NEWEST, c, 0))
        assertEquals(300L, DayKeyMerge.winner(DayKeyMerge.Rule.NEWEST, c.reversed(), 0))
        assertEquals(300L, DayKeyMerge.winner(DayKeyMerge.Rule.HIGHEST, c, 0))
        assertEquals(100L, DayKeyMerge.winner(DayKeyMerge.Rule.HIGHEST, listOf(DayKeyMerge.Candidate(100, 180), DayKeyMerge.Candidate(200, 170)), 0))
        assertEquals(200L, DayKeyMerge.winner(DayKeyMerge.Rule.NEAREST_MIDNIGHT, c, 190))
        // Equally near: the larger.
        assertEquals(300L, DayKeyMerge.winner(DayKeyMerge.Rule.NEAREST_MIDNIGHT, c, 250))
        assertEquals(300L, DayKeyMerge.unionKeep(listOf(300, 100)))
    }
}
