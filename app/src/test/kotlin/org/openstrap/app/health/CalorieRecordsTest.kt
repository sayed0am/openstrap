package org.openstrap.app.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.db.DailyCaloriesRow
import java.time.LocalDate
import java.time.ZoneId
import org.openstrap.app.data.Days

class CalorieRecordsTest {
    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun midnight(d: LocalDate, zone: ZoneId) = d.atStartOfDay(zone).toEpochSecond()

    /** The row for the day starting at the local midnight [day] in [zone]. */
    private fun row(day: Long, hourly: List<Float>, total: Float = 2200f, partial: Boolean = false, updated: Long = day + 90_000, zone: ZoneId = utc) =
        DailyCaloriesRow(Days.date(day, zone), updated, total, hourly.sum(), 1650f, if (partial) 600 else 1440, partial, 100f, 150f, hourly.joinToString(","))

    private val day = midnight(LocalDate.of(2026, 10, 20), utc)
    private val later = day + 10 * 86_400

    @Test fun `each hour with active kcal is its own record, on the hour, with its kcal`() {
        val hourly = List(24) { h -> if (h in 7..9) 40f + h else 0f }
        val spans = CalorieRecords.activeHours(listOf(row(day, hourly)), utc, later)
        assertEquals(listOf(7, 8, 9).map { day + it * 3600L }, spans.map { it.start })
        assertTrue(spans.all { it.end - it.start == 3600L })
        assertEquals(listOf(47.0, 48.0, 49.0), spans.map { it.kcal })
        assertEquals(hourly.sum().toDouble(), spans.sumOf { it.kcal }, 1e-3)
    }

    @Test fun `ids are stable and the version is when the day was worked out`() {
        val hourly = List(24) { 10f }
        val a = CalorieRecords.activeHours(listOf(row(day, hourly, updated = 1000)), utc, later)
        val b = CalorieRecords.activeHours(listOf(row(day, hourly.map { it + 5 }, updated = 2000)), utc, later)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals("active:${day + 3600}", a[1].id)
        assertEquals(setOf(1000L), a.map { it.version }.toSet())
        assertEquals(setOf(2000L), b.map { it.version }.toSet())
        assertEquals("total:$day", CalorieRecords.dayTotals(listOf(row(day, hourly)), utc, later).single().id)
        // Every hour of a day its own id, and none shared with the next day's.
        val two = CalorieRecords.activeHours(listOf(row(day, hourly), row(day + 86_400, hourly)), utc, later)
        assertEquals(48, two.map { it.id }.toSet().size)
    }

    @Test fun `the hour under way ends now, and hours still to come write nothing`() {
        val now = day + 14 * 3600 + 1200
        val spans = CalorieRecords.activeHours(listOf(row(day, List(24) { 20f })), utc, now)
        assertEquals(15, spans.size)
        assertEquals(now, spans.last().end)
        assertTrue(spans.all { it.end <= now })
    }

    @Test fun `a partial day writes its hours but never a total`() {
        val hourly = List(24) { h -> if (h in 10..13) 30f else 0f }
        val partial = row(day, hourly, partial = true)
        assertEquals(4, CalorieRecords.activeHours(listOf(partial), utc, later).size)
        assertEquals(emptyList<CalorieRecords.Span>(), CalorieRecords.dayTotals(listOf(partial), utc, later))
    }

    @Test fun `a total covers the local day, only once it's over`() {
        val full = row(day, List(24) { 20f }, total = 2345f)
        val t = CalorieRecords.dayTotals(listOf(full), utc, later).single()
        assertEquals(day, t.start)
        assertEquals(day + 86_400, t.end)
        assertEquals(2345.0, t.kcal, 1e-3)
        // The day in progress, full or not, waits.
        assertEquals(emptyList<CalorieRecords.Span>(), CalorieRecords.dayTotals(listOf(full), utc, day + 86_399))
    }

    @Test fun `days with a clock change keep their local hours and length`() {
        // Berlin falls back on 25 October 2026: a 25 hour day.
        val d = midnight(LocalDate.of(2026, 10, 25), berlin)
        val spans = CalorieRecords.activeHours(listOf(row(d, List(24) { 10f }, zone = berlin)), berlin, d + 30 * 86_400)
        assertEquals(24, spans.size)
        assertEquals(d + 4 * 3600, spans[3].start) // 03:00 local is four hours after midnight
        assertEquals(25 * 3600L, CalorieRecords.dayEnd(d, berlin) - d)
        assertEquals(25 * 3600L, CalorieRecords.dayTotals(listOf(row(d, List(24) { 10f }, zone = berlin)), berlin, d + 30 * 86_400).single().let { it.end - it.start })
        // And springs forward on 29 March: hour 2 doesn't exist, and it holds nothing.
        val s = midnight(LocalDate.of(2026, 3, 29), berlin)
        val hourly = List(24) { h -> if (h == 2) 0f else 10f }
        val spring = CalorieRecords.activeHours(listOf(row(s, hourly, zone = berlin)), berlin, s + 30 * 86_400)
        assertEquals(23, spring.size)
        assertEquals(spring.size, spring.map { it.start }.toSet().size)
    }

    @Test fun `the days a range is in run midnight to midnight`() {
        assertEquals(day to day + 2 * 86_400, CalorieRecords.days(day + 3600, day + 86_400 + 60, utc))
        assertEquals(day to day + 86_400, CalorieRecords.days(day + 3600, day + 7200, utc))
    }
}
