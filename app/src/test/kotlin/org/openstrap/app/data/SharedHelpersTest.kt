package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.openstrap.app.testing.ZoneRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/** Stats, Days, HeartRate and SportTypes, the shared helpers the engines move onto in wave 3. */
class SharedHelpersTest {
    // A zone the tests' own zones differ from, so nothing leans on the default.
    @get:Rule val zoneRule = ZoneRule(ZoneId.of("Asia/Kathmandu"))

    private val berlin = ZoneId.of("Europe/Berlin")

    /* ----------------------------------------------- Stats ----------------------------------------------- */

    @Test
    fun median() {
        assertNull(Stats.median(emptyList<Float>()))
        assertEquals(3f, Stats.median(listOf(5f, 1f, 3f))!!, 0f)
        assertEquals(2.5f, Stats.median(listOf(4f, 1f, 3f, 2f))!!, 0f)
        assertEquals(7f, Stats.median(listOf(7f))!!, 0f)
        assertEquals(2.5f, Stats.median(listOf(1, 2, 3, 4))!!, 0f)
        assertNull(Stats.median(emptyList<Int>()))
    }

    @Test
    fun medianMatchesTheCopiesItReplaces() {
        val r = Random(7)
        repeat(200) {
            val values = List(1 + r.nextInt(30)) { r.nextFloat() * 100 }
            val s = values.sorted()
            val n = s.size
            // UsualSteps and Goals.
            val usual = if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
            assertEquals(usual, Stats.median(values)!!, 1e-4f)
        }
    }

    @Test
    fun upperMiddle() {
        assertNull(Stats.upperMiddle(emptyList()))
        assertEquals(3, Stats.upperMiddle(listOf(4, 1, 3, 2)))
        assertEquals(2, Stats.upperMiddle(listOf(3, 1, 2)))
    }

    @Test
    fun quantile() {
        assertNull(Stats.quantile(emptyList(), 0.5f))
        assertEquals(1f, Stats.quantile(listOf(3f, 1f, 2f), 0f)!!, 0f)
        assertEquals(3f, Stats.quantile(listOf(3f, 1f, 2f), 1f)!!, 0f)
        assertEquals(1.5f, Stats.quantile(listOf(3f, 1f, 2f), 0.25f)!!, 1e-6f)
        assertEquals(3f, Stats.quantile(listOf(3f, 1f, 2f), 2f)!!, 0f)
        assertEquals(1f, Stats.quantile(listOf(3f, 1f, 2f), -1f)!!, 0f)
        assertEquals(10.0, Stats.quantileOfSorted(listOf(10.0), 0.9), 0.0)
        assertThrows(IllegalArgumentException::class.java) { Stats.quantileOfSorted(emptyList(), 0.5) }
    }

    @Test
    fun quantileMatchesRecoverysPercentile() {
        val r = Random(11)
        repeat(200) {
            val values = List(1 + r.nextInt(30)) { r.nextFloat() * 100 }
            val p = r.nextFloat()
            assertEquals(Recovery.percentile(values, p)!!, Stats.quantile(values, p)!!, 1e-3f)
        }
    }

    /* ----------------------------------------------- Days ------------------------------------------------ */

    @Test
    fun anOrdinaryDayIs24Hours() {
        val d = LocalDate.of(2026, 6, 10)
        assertEquals(24 * 3600L, Days.end(d, berlin) - Days.start(d, berlin))
        assertEquals(Instant.parse("2026-06-09T22:00:00Z").epochSecond, Days.start(d, berlin))
    }

    @Test
    fun theDayTheClocksGoForwardIs23Hours() {
        val d = LocalDate.of(2026, 3, 29)
        val start = Days.start(d, berlin)
        assertEquals(Instant.parse("2026-03-28T23:00:00Z").epochSecond, start)
        assertEquals(23 * 3600L, Days.end(d, berlin) - start)
        // Any moment in the day gives the same bounds, its last second too.
        for (t in listOf(start, start + 2 * 3600, Days.end(d, berlin) - 1)) {
            assertEquals(start, Days.start(t, berlin))
            assertEquals(Days.end(d, berlin), Days.end(t, berlin))
            assertEquals(d, Days.date(t, berlin))
        }
        assertEquals(d.plusDays(1), Days.date(Days.end(d, berlin), berlin))
    }

    @Test
    fun theDayTheClocksGoBackIs25Hours() {
        val d = LocalDate.of(2026, 10, 25)
        val start = Days.start(d, berlin)
        assertEquals(Instant.parse("2026-10-24T22:00:00Z").epochSecond, start)
        assertEquals(25 * 3600L, Days.end(d, berlin) - start)
        assertEquals(start, Days.start(Instant.parse("2026-10-25T01:30:00Z").epochSecond, berlin))
    }

    @Test
    fun aDayWhoseMidnightIsSkippedStartsAtItsFirstMoment() {
        // Chile moves its clocks at midnight: on the first Sunday of September 2026, 00:00 becomes 01:00.
        val santiago = ZoneId.of("America/Santiago")
        val d = LocalDate.of(2026, 9, 6)
        if (santiago.rules.getTransition(LocalDateTime.of(d, LocalTime.MIDNIGHT)) == null) return
        assertEquals(LocalTime.of(1, 0), Instant.ofEpochSecond(Days.start(d, santiago)).atZone(santiago).toLocalTime())
        assertEquals(23 * 3600L, Days.end(d, santiago) - Days.start(d, santiago))
    }

    @Test
    fun daysMatchDayWindow() {
        for (d in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 1, 1))) {
            val w = DayWindow.of(d, berlin)
            assertEquals(w.from, Days.start(d, berlin))
            assertEquals(w.to + 1, Days.end(d, berlin))
        }
    }

    /* --------------------------------------------- HeartRate --------------------------------------------- */

    @Test
    fun validHeartRateIs30To230() {
        assertEquals(30..230, HeartRate.VALID)
        assertEquals("BETWEEN 30 AND 230", HeartRate.SQL_VALID)
        assertFalse(HeartRate.isValid(29))
        assertTrue(HeartRate.isValid(30))
        assertTrue(HeartRate.isValid(230))
        assertFalse(HeartRate.isValid(231))
    }

    /* --------------------------------------------- SportTypes -------------------------------------------- */

    @Test
    fun sportSetsAreTheOnesTheyReplace() {
        assertEquals(setOf(1, 2, 11), SportTypes.RUNS)
        assertEquals(setOf(24, 50, 52, 222, 226), SportTypes.STRENGTH)
        assertEquals(setOf(3, 15, 40, 215, 224, 225), SportTypes.WALKS)
        assertTrue(SportTypes.HIKES.all { it in SportTypes.WALKS })
    }

    @Test
    fun everySportHasAName() {
        val all = SportTypes.ON_FOOT + SportTypes.RIDES + SportTypes.SWIMS + SportTypes.STRENGTH + SportTypes.ROWING_MACHINE
        for (id in all) assertTrue("no name for $id", Workouts.sportName(id) != "Workout")
    }
}
