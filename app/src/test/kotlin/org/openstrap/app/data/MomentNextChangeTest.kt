package org.openstrap.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MomentResolver.nextChange]: every boundary of widgets spec 7 and v2 4.1, overlaps and past midnight, and that it
 * agrees with [MomentResolver.resolve]: the moment just before is the one now, at it another, and a minute by minute
 * scan finds no change earlier.
 */
class MomentNextChangeTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 23) // a Friday

    private fun at(h: Int, m: Int, date: LocalDate = today, zone: ZoneId = utc): Instant = date.atTime(h, m).atZone(zone).toInstant()
    private fun epoch(h: Int, m: Int, date: LocalDate = today): Long = at(h, m, date).epochSecond

    /** Three Fridays before [today] at [h]:[m]: enough for a weekday usual. */
    private fun fridays(h: Int, m: Int) = (1..3).map { epoch(h, m, today.minusWeeks(it.toLong())) }

    private data class In(
        val day: LocalDate? = null,
        val recording: Boolean = false,
        val lastWorkoutEnd: Long? = null,
        val sleepEnd: Long? = null,
        val bedtime: LocalTime? = LocalTime.of(23, 0),
        val starts: List<Long> = emptyList(),
        val zone: ZoneId = ZoneOffset.UTC,
    )

    private fun next(now: Instant, i: In = In()): Instant? = MomentResolver.nextChange(
        now, i.zone, i.day ?: now.atZone(i.zone).toLocalDate(), i.recording, i.lastWorkoutEnd, i.sleepEnd, i.bedtime, i.starts,
    )

    /** The moment at [t] read as [MomentResolver.nextChange] reads it: past midnight the new day, without today's sleep. */
    private fun momentAt(t: Instant, day: LocalDate, i: In): Moment {
        val date = t.atZone(i.zone).toLocalDate()
        val later = date > day
        return MomentResolver.resolve(t, i.zone, if (later) date else day, i.recording, i.lastWorkoutEnd, if (later) null else i.sleepEnd, i.bedtime, i.starts)
    }

    /**
     * Checks [next] at [now] against [MomentResolver.resolve] and a minute by minute scan, and returns it with the
     * moment it changes to.
     */
    private fun checked(now: Instant, i: In = In()): Pair<Instant, Moment>? {
        val day = i.day ?: now.atZone(i.zone).toLocalDate()
        val current = momentAt(now, day, i)
        val r = next(now, i)
        val scan = generateSequence(now.plusSeconds(60)) { it.plusSeconds(60) }
            .takeWhile { it <= now.plusSeconds(MomentResolver.NEXT_CHANGE_HORIZON_SECONDS) }
            .firstOrNull { momentAt(it, day, i) != current }
        if (r == null) {
            assertNull("no change from $now, but the scan found one at $scan", scan)
            return null
        }
        assertEquals("just before $r", current, momentAt(r.minusSeconds(1), day, i))
        assertNotEquals("at $r", current, momentAt(r, day, i))
        assertTrue("the scan from $now found a change at $scan, before $r", scan != null && r <= scan && scan.epochSecond - r.epochSecond < 60)
        return r to momentAt(r, day, i)
    }

    // Each boundary

    @Test fun `after ends two hours after the workout`() {
        assertEquals(at(14, 0) to Moment.Midday, checked(at(12, 30), In(lastWorkoutEnd = epoch(12, 0))))
    }

    @Test fun `the workout window starts and ends 45 minutes either side`() {
        val i = In(starts = fridays(16, 0))
        assertEquals(at(15, 15) to Moment.WorkoutWindow, checked(at(14, 0), i))
        assertEquals(at(16, 45) to Moment.Midday, checked(at(15, 15), i))
    }

    @Test fun `waking ends three hours after the main sleep`() {
        assertEquals(at(9, 30) to Moment.Midday, checked(at(7, 0), In(sleepEnd = epoch(6, 30))))
    }

    @Test fun `without a main sleep the morning starts at 05_00 and ends at 08_00`() {
        assertEquals(at(5, 0) to Moment.Waking, checked(at(4, 0)))
        assertEquals(at(8, 0) to Moment.Midday, checked(at(5, 0)))
    }

    @Test fun `evening and night start before bedtime`() {
        assertEquals(at(18, 0) to Moment.Evening, checked(at(12, 0), In(sleepEnd = epoch(7, 0))))
        assertEquals(at(22, 15) to Moment.Night, checked(at(20, 0), In(sleepEnd = epoch(7, 0))))
        assertEquals(at(18, 0) to Moment.Evening, checked(at(12, 0), In(bedtime = null)))
        assertEquals(at(17, 30) to Moment.Evening, checked(at(12, 0), In(sleepEnd = epoch(7, 0), bedtime = LocalTime.of(20, 30))))
    }

    // Past midnight

    @Test fun `night runs on past midnight to 04_00`() {
        assertEquals(at(4, 0, today.plusDays(1)) to Moment.Midday, checked(at(22, 15), In(sleepEnd = epoch(7, 0))))
    }

    @Test fun `after midnight the morning is the new day's, not today's sleep`() {
        // Up at 22:00 after a day sleep: waking would run to 01:00, but at midnight that sleep is yesterday's.
        assertEquals(at(0, 0, today.plusDays(1)) to Moment.Night, checked(at(22, 30), In(sleepEnd = epoch(22, 0))))
    }

    @Test fun `after a late workout crosses midnight into night`() {
        assertEquals(at(1, 30, today.plusDays(1)) to Moment.Night, checked(at(23, 40), In(lastWorkoutEnd = epoch(23, 30))))
    }

    @Test fun `a bedtime after midnight starts night after midnight`() {
        assertEquals(at(0, 45, today.plusDays(1)) to Moment.Night, checked(at(23, 0), In(sleepEnd = epoch(7, 0), bedtime = LocalTime.of(1, 30))))
    }

    @Test fun `the workout window crosses midnight`() {
        val starts = listOf(19, 20, 21).map { epoch(23, 50, LocalDate.of(2026, 10, it)) }
        assertEquals(at(0, 35, today.plusDays(1)) to Moment.Night, checked(at(23, 30), In(sleepEnd = epoch(7, 0), starts = starts)))
    }

    // Overlaps

    @Test fun `after over the window ends at after's end, then the window carries on`() {
        val i = In(lastWorkoutEnd = epoch(15, 0), starts = fridays(16, 0))
        assertEquals(at(17, 0) to Moment.Midday, checked(at(15, 30), i))
        val j = In(lastWorkoutEnd = epoch(14, 30), starts = fridays(16, 0))
        assertEquals(at(16, 30) to Moment.WorkoutWindow, checked(at(15, 30), j))
    }

    @Test fun `waking over evening ends at waking's end, into night`() {
        assertEquals(at(23, 0) to Moment.Night, checked(at(21, 0), In(sleepEnd = epoch(20, 0))))
    }

    @Test fun `a midnight with nothing changing is not a change`() {
        val r = checked(at(23, 0), In(sleepEnd = epoch(7, 0)))
        assertEquals(at(4, 0, today.plusDays(1)), r?.first)
    }

    @Test fun `a workout leaving the usual time's look-back ends the window`() {
        // Three workouts in 28 days, the oldest started 28 days ago at 17:40: once it's out there's no usual time.
        val oldest = epoch(17, 40, today.minusDays(28))
        val starts = listOf(oldest, epoch(18, 0, today.minusDays(10)), epoch(18, 0, today.minusDays(5)))
        assertEquals(Instant.ofEpochSecond(oldest + 28 * 86_400L + 1) to Moment.Midday, checked(at(17, 30), In(sleepEnd = epoch(7, 0), starts = starts)))
    }

    // None

    @Test fun `a recording has no known end`() {
        assertNull(next(at(17, 0), In(recording = true, lastWorkoutEnd = epoch(12, 0), starts = fridays(18, 0))))
    }

    @Test fun `a past day is always waking`() {
        assertNull(next(at(17, 0), In(day = today.minusDays(1))))
    }

    // The property, swept

    @Test fun `it agrees with resolve from every point of a day`() {
        val scenarios = listOf(
            In(),
            In(sleepEnd = epoch(6, 40)),
            In(sleepEnd = epoch(6, 40), lastWorkoutEnd = epoch(18, 50), starts = fridays(18, 0)),
            In(bedtime = null, starts = listOf(19, 20, 21).map { epoch(23, 50, LocalDate.of(2026, 10, it)) }),
            In(sleepEnd = epoch(11, 0), bedtime = LocalTime.of(2, 0), lastWorkoutEnd = epoch(13, 7)),
        )
        for (i in scenarios) {
            var now = at(0, 0)
            while (now < at(0, 0, today.plusDays(1))) { checked(now, i); now = now.plusSeconds(37 * 60) }
        }
    }

    @Test fun `it agrees with resolve across the clocks going back and forward`() {
        val london = ZoneId.of("Europe/London")
        // 2026-10-25 passes 01:00 to 02:00 twice, 2026-03-29 skips it. The window's end (01:15) and night's start
        // (01:45) sit inside it: going back, the window and evening come round again.
        listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 3, 28)).forEach { eve ->
            val starts = (1..3).map { eve.minusDays(it.toLong()).atTime(0, 30).atZone(london).toEpochSecond() }
            val i = In(bedtime = LocalTime.of(2, 30), starts = starts, zone = london)
            var now = eve.atTime(12, 0).atZone(london).toInstant()
            val end = now.plusSeconds(86_400)
            while (now < end) { checked(now, i); now = now.plusSeconds(23 * 60) }
        }
    }
}
