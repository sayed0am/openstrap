package org.openstrap.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every moment's first and last minute, and every overlap in the priority list (spec 4.1). */
class MomentResolverTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 23) // a Friday

    private fun at(h: Int, m: Int, date: LocalDate = today): Instant = date.atTime(h, m).atZone(zone).toInstant()
    private fun epoch(h: Int, m: Int, date: LocalDate = today): Long = at(h, m, date).epochSecond

    /** Three Fridays before [today] at [h]:[m]: enough for a weekday usual. */
    private fun fridays(h: Int, m: Int) = (1..3).map { epoch(h, m, today.minusWeeks(it.toLong())) }

    private fun moment(
        now: Instant,
        day: LocalDate = now.atZone(zone).toLocalDate(),
        recording: Boolean = false,
        lastWorkoutEnd: Long? = null,
        sleepEnd: Long? = null,
        bedtime: LocalTime? = LocalTime.of(23, 0),
        starts: List<Long> = emptyList(),
    ) = MomentResolver.resolve(now, zone, day, recording, lastWorkoutEnd, sleepEnd, bedtime, starts)

    // During

    @Test fun `a recording is during, over everything else`() {
        assertEquals(Moment.During, moment(at(7, 0), recording = true, lastWorkoutEnd = epoch(6, 30), sleepEnd = epoch(6, 0), starts = fridays(7, 0)))
        assertEquals(Moment.During, moment(at(23, 30), recording = true))
    }

    // After

    @Test fun `after runs from the workout's end for two hours`() {
        val end = epoch(12, 0)
        assertEquals(Moment.Midday, moment(at(11, 59), lastWorkoutEnd = end))
        assertEquals(Moment.After, moment(at(12, 0), lastWorkoutEnd = end))
        assertEquals(Moment.After, moment(at(13, 59), lastWorkoutEnd = end))
        assertEquals(Moment.Midday, moment(at(14, 0), lastWorkoutEnd = end))
    }

    @Test fun `after wins over the workout window, waking, night and evening`() {
        assertEquals(Moment.After, moment(at(18, 30), lastWorkoutEnd = epoch(18, 0), starts = fridays(18, 0)))
        assertEquals(Moment.After, moment(at(7, 0), lastWorkoutEnd = epoch(6, 50), sleepEnd = epoch(6, 0)))
        assertEquals(Moment.After, moment(at(22, 30), lastWorkoutEnd = epoch(22, 0)))
        assertEquals(Moment.After, moment(at(20, 30), lastWorkoutEnd = epoch(20, 0)))
    }

    @Test fun `after crosses midnight`() {
        assertEquals(Moment.After, moment(at(0, 30, today.plusDays(1)), lastWorkoutEnd = epoch(23, 30)))
    }

    // Workout window

    @Test fun `the workout window runs 45 minutes either side of your usual time`() {
        val starts = fridays(16, 0)
        assertEquals(Moment.Midday, moment(at(15, 14), starts = starts))
        assertEquals(Moment.WorkoutWindow, moment(at(15, 15), starts = starts))
        assertEquals(Moment.WorkoutWindow, moment(at(16, 44), starts = starts))
        assertEquals(Moment.Midday, moment(at(16, 45), starts = starts))
    }

    @Test fun `the workout window wins over waking, night and evening`() {
        assertEquals(Moment.WorkoutWindow, moment(at(7, 0), sleepEnd = epoch(6, 30), starts = fridays(7, 0)))
        assertEquals(Moment.WorkoutWindow, moment(at(22, 30), starts = fridays(22, 30)))
        assertEquals(Moment.WorkoutWindow, moment(at(20, 30), starts = fridays(20, 30)))
    }

    @Test fun `the workout window crosses midnight`() {
        // Three late sessions on other weekdays: the all-days usual is 23:50, so 00:20 on Saturday is in its window.
        val starts = listOf(19, 20, 21).map { epoch(23, 50, LocalDate.of(2026, 10, it)) }
        assertEquals(Moment.WorkoutWindow, moment(at(0, 20, today.plusDays(1)), starts = starts))
        assertEquals(Moment.Night, moment(at(0, 35, today.plusDays(1)), starts = starts))
    }

    @Test fun `no usual workout time means no workout window`() {
        assertEquals(Moment.Midday, moment(at(16, 0), starts = fridays(16, 0).take(2)))
    }

    // Waking

    @Test fun `waking runs three hours from the end of the main sleep`() {
        val wake = epoch(6, 30)
        assertEquals(Moment.Midday, moment(at(6, 29), sleepEnd = wake))
        assertEquals(Moment.Waking, moment(at(6, 30), sleepEnd = wake))
        assertEquals(Moment.Waking, moment(at(9, 29), sleepEnd = wake))
        assertEquals(Moment.Midday, moment(at(9, 30), sleepEnd = wake))
    }

    @Test fun `without a main sleep waking runs from 05_00 to 08_00`() {
        assertEquals(Moment.Night, moment(at(3, 59)))
        assertEquals(Moment.Midday, moment(at(4, 59)))
        assertEquals(Moment.Waking, moment(at(5, 0)))
        assertEquals(Moment.Waking, moment(at(7, 59)))
        assertEquals(Moment.Midday, moment(at(8, 0)))
    }

    @Test fun `waking wins over night and evening`() {
        // Up at 21:00 after a day sleep: still waking when night and evening would have started.
        assertEquals(Moment.Waking, moment(at(22, 30), sleepEnd = epoch(21, 0)))
        assertEquals(Moment.Waking, moment(at(20, 30), sleepEnd = epoch(20, 0)))
        // Woken early: waking from 03:00, before night ends.
        assertEquals(Moment.Waking, moment(at(3, 0), sleepEnd = epoch(3, 0)))
    }

    @Test fun `waking lasts three hours from the end of sleep`() {
        val wake = epoch(6, 30)
        assertEquals(false, MomentResolver.wakingOver(epoch(9, 29), wake))
        assertEquals(true, MomentResolver.wakingOver(epoch(9, 30), wake))
    }

    // Night

    @Test fun `night runs from 45 minutes before bedtime to 04_00`() {
        assertEquals(Moment.Evening, moment(at(22, 14)))
        assertEquals(Moment.Night, moment(at(22, 15)))
        assertEquals(Moment.Night, moment(at(23, 59)))
    }

    @Test fun `night crosses midnight and ends at 04_00`() {
        val saturday = today.plusDays(1)
        assertEquals(Moment.Night, moment(at(0, 0, saturday)))
        assertEquals(Moment.Night, moment(at(3, 59, saturday)))
        assertEquals(Moment.Midday, moment(at(4, 0, saturday)))
    }

    @Test fun `a bedtime after midnight starts night after midnight`() {
        val late = LocalTime.of(0, 30)
        assertEquals(Moment.Evening, moment(at(23, 44), bedtime = late))
        assertEquals(Moment.Night, moment(at(23, 45), bedtime = late))
        val later = LocalTime.of(1, 30)
        assertEquals(Moment.Evening, moment(at(0, 44, today.plusDays(1)), bedtime = later))
        assertEquals(Moment.Night, moment(at(0, 45, today.plusDays(1)), bedtime = later))
    }

    @Test fun `night wins over evening`() {
        // Evening would run to 23:00 here; night takes it from 22:15.
        assertEquals(Moment.Night, moment(at(22, 59)))
    }

    // Evening

    @Test fun `evening runs from three hours before bedtime until night`() {
        val early = LocalTime.of(20, 30)
        assertEquals(Moment.Midday, moment(at(17, 29), bedtime = early))
        assertEquals(Moment.Evening, moment(at(17, 30), bedtime = early))
        assertEquals(Moment.Evening, moment(at(19, 44), bedtime = early))
        assertEquals(Moment.Night, moment(at(19, 45), bedtime = early))
    }

    @Test fun `evening starts at 18_00 at the latest, however late bedtime is`() {
        // Three hours before 23:00 would be 20:00, so 18:45 showed Midday.
        assertEquals(Moment.Midday, moment(at(17, 59)))
        assertEquals(Moment.Evening, moment(at(18, 0)))
        assertEquals(Moment.Evening, moment(at(18, 45)))
        assertEquals(Moment.Evening, moment(at(22, 14)))
        assertEquals(Moment.Midday, moment(at(17, 59), bedtime = LocalTime.of(1, 30)))
        assertEquals(Moment.Evening, moment(at(18, 0), bedtime = LocalTime.of(1, 30)))
    }

    @Test fun `evening starts at 18_00 when bedtime is unknown`() {
        assertEquals(Moment.Midday, moment(at(17, 59), bedtime = null))
        assertEquals(Moment.Evening, moment(at(18, 0), bedtime = null))
        assertEquals(Moment.Evening, moment(at(21, 14), bedtime = null))
        assertEquals(Moment.Night, moment(at(21, 15), bedtime = null))
    }

    @Test fun `a bedtime in the afternoon starts evening no earlier than noon`() {
        assertEquals(Moment.Evening, moment(at(12, 0), bedtime = LocalTime.of(14, 0)))
        assertEquals(Moment.Night, moment(at(13, 15), bedtime = LocalTime.of(14, 0)))
    }

    // Midday and past days

    @Test fun `midday is everything else`() {
        assertEquals(Moment.Midday, moment(at(12, 0)))
        assertEquals(Moment.Midday, moment(at(15, 0), sleepEnd = epoch(7, 0)))
    }

    @Test fun `past days are always waking`() {
        val yesterday = today.minusDays(1)
        assertEquals(Moment.Waking, moment(at(23, 30), day = yesterday))
        assertEquals(Moment.Waking, moment(at(12, 0), day = yesterday, recording = true, lastWorkoutEnd = epoch(11, 0), starts = fridays(12, 0)))
        assertEquals(Moment.Waking, moment(at(19, 0), day = today.minusDays(30)))
    }

    // Usual workout time

    private fun usual(starts: List<Long>, now: Instant = at(12, 0)) = MomentResolver.usualWorkoutTime(starts, now, zone)

    @Test fun `usual workout time is the median start on the same weekday`() {
        // Fridays at 06:50, 07:00 and 07:30, every other day at 18:00.
        val fridays = listOf(epoch(6, 50, today.minusWeeks(1)), epoch(7, 0, today.minusWeeks(2)), epoch(7, 30, today.minusWeeks(3)))
        val others = (1..6).map { epoch(18, 0, today.minusDays(it.toLong())) }
        assertEquals(LocalTime.of(7, 0), usual(fridays + others))
    }

    @Test fun `with fewer than three on the weekday all days count`() {
        val starts = listOf(
            epoch(7, 0, today.minusWeeks(1)), epoch(7, 0, today.minusWeeks(2)), // two Fridays
            epoch(12, 0, today.minusDays(4)), epoch(18, 0, today.minusDays(3)), epoch(18, 0, today.minusDays(2)),
        )
        assertEquals(LocalTime.of(12, 0), usual(starts))
    }

    @Test fun `an even count takes the middle two's mean`() {
        val starts = listOf(epoch(7, 0, today.minusDays(1)), epoch(8, 0, today.minusDays(2)), epoch(9, 0, today.minusDays(3)), epoch(10, 0, today.minusDays(4)))
        assertEquals(LocalTime.of(8, 30), usual(starts))
    }

    @Test fun `fewer than three workouts give no usual time`() {
        assertNull(usual(emptyList()))
        assertNull(usual(listOf(epoch(7, 0, today.minusDays(1)), epoch(7, 0, today.minusDays(2)))))
    }

    @Test fun `only the last 28 days count`() {
        val old = (29..31).map { epoch(7, 0, today.minusDays(it.toLong())) }
        assertNull(usual(old))
        val edge = (26..28).map { epoch(13, 0, today.minusDays(it.toLong())) }
        // At 12:00 today, 28 days back reaches 12:00 that day, so its 13:00 workout is in.
        assertEquals(LocalTime.of(13, 0), usual(edge))
        // Workouts later than now (a clock change, a bad record) don't count either.
        assertNull(usual(listOf(epoch(13, 0), epoch(14, 0), epoch(15, 0))))
    }
}
