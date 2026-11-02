package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.health.CalorieRecords
import java.time.LocalDate
import java.time.ZoneId

/** Day bounds and the hours in them on the days the clocks change (D8, E11), and one way of counting a night. */
class DayBoundariesTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    /** 25 October 2026: the clocks go back at 03:00, so the day has 25 hours. */
    private val autumn = LocalDate.of(2026, 10, 25)

    /** 29 March 2026: the clocks go forward at 02:00, so the day has 23 hours. */
    private val spring = LocalDate.of(2026, 3, 29)

    private fun at(d: LocalDate, hour: Int, minute: Int = 0) = d.atTime(hour, minute).atZone(berlin).toEpochSecond()

    @Test fun `hourly steps go by the clock on a 25 hour day`() {
        val start = Days.start(autumn, berlin)
        // 22:30 is the 24th hour after midnight that day: by hours since midnight it fell off the end.
        val minutes = listOf(ActivityMinute(at(autumn, 22, 30), 100, 0, 0, null), ActivityMinute(at(autumn, 9, 0), 40, 0, 0, null))
        val hourly = Summaries.hourlySteps(minutes, start, berlin)
        assertEquals(100f, hourly[22], 0f)
        assertEquals(40f, hourly[9], 0f)
        assertEquals(0f, hourly[23], 0f)
    }

    @Test fun `hourly steps go by the clock on a 23 hour day`() {
        val start = Days.start(spring, berlin)
        val hourly = Summaries.hourlySteps(listOf(ActivityMinute(at(spring, 12, 0), 70, 0, 0, null)), start, berlin)
        assertEquals(70f, hourly[12], 0f)
        // The next day's first minute isn't this day's.
        val next = Summaries.hourlySteps(listOf(ActivityMinute(Days.end(spring, berlin), 5, 0, 0, null)), start, berlin)
        assertEquals(0f, next.sum(), 0f)
    }

    @Test fun `a night window runs from the evening before by the clock`() {
        val w = DayWindow.nightEndingOn(autumn.plusDays(1), berlin)
        assertEquals(at(autumn, 18), w.from)
        assertEquals(at(autumn.plusDays(1), 18), w.to)
        val s = DayWindow.nightEndingOn(spring, berlin)
        assertEquals(at(spring.minusDays(1), 18), s.from)
        assertEquals(23 * 3600L, s.to - s.from)
    }

    @Test fun `a day window and the calorie day are the local midnights`() {
        val w = DayWindow.of(autumn, berlin)
        assertEquals(25 * 3600L - 1, w.to - w.from)
        assertEquals(Days.end(autumn, berlin), CalorieRecords.dayEnd(CalorieRecords.dayStart(at(autumn, 12), berlin), berlin))
    }

    @Test fun `the sleep screens count a night as recovery does`() {
        val day = LocalDate.of(2026, 10, 17)
        // No stages: time in bed less awake, as recovery counts it.
        val night = SleepSession(start = at(day, 0) - 3600, end = at(day, 7), awakeMinutes = 30)
        assertEquals(RecoveryEngine.asleepMinutes(night) * 60L, SleepHistory.asleepSeconds(night))
        assertEquals(450 * 60L, SleepHistory.asleepSeconds(night))
        // A sleep ending in the evening belongs to the day it ended on, not the next.
        val evening = SleepSession(start = at(day, 13), end = at(day, 19))
        assertEquals(day, SleepHistory.nightOf(evening, berlin))
        assertEquals(Days.date(RecoveryEngine.morningOf(evening, berlin), berlin), SleepHistory.nightOf(evening, berlin))
        // The strap's own day wins, as in recovery.
        val assigned = evening.copy(day = Days.start(day.plusDays(1), berlin))
        assertEquals(mapOf(day.plusDays(1) to assigned), SleepHistory.mainSleeps(listOf(assigned), berlin))
    }
}
