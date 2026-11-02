package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openstrap.app.data.db.BadgeRow
import org.openstrap.app.presentation.Weeks
import org.openstrap.app.testing.LocaleRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * Rewards and history agree on the week (D7) in a Sunday-first locale, and the rows stored by week (week_result,
 * and a badge's week through [Rewards.goalAt]) still line up when read back.
 */
class WeekStartTest {
    @get:Rule val locale = LocaleRule(Locale.US)

    private val zone = ZoneOffset.UTC

    /** Wednesday 14 Oct 2026: its US week starts on Sunday the 11th, its German one on Monday the 12th. */
    private val wednesday = LocalDate.of(2026, 10, 14)
    private val sunday = LocalDate.of(2026, 10, 11)

    private fun at(d: LocalDate, hour: Int) = d.atTime(hour, 0).toEpochSecond(zone)

    private fun done(d: LocalDate) = Rewards.Done(start = at(d, 8), end = at(d, 9), sport = SportTypes.OUTDOOR_RUN, activeSeconds = 3600)

    @Test fun `every week starts on the locale's first day`() {
        assertEquals(DayOfWeek.SUNDAY, Weeks.firstDay())
        assertEquals(sunday, Rewards.weekStart(wednesday, Weeks.firstDay()))
        val stats = listOf(DayStat(sunday, 10f, 10f, 10f, 1), DayStat(wednesday, 30f, 30f, 30f, 1))
        assertEquals(listOf(sunday), History.weekly(stats).map { it.date })
    }

    @Test fun `stored weeks and a badge's week line up in a Sunday-first locale`() {
        val firstDay = Weeks.firstDay()
        // Sunday and Wednesday are one US week, so two workouts count in it.
        val workouts = listOf(done(sunday), done(wednesday))
        val weeks = Rewards.weeks(workouts, emptyList(), goal = 2, today = wednesday, firstDay = firstDay, zone = zone)
        assertEquals(listOf(sunday.toEpochDay()), weeks.map { it.weekStart })
        assertEquals(2, weeks.single().count)
        // Read back as kept rows, the same weeks come out, and the week keeps its stored goal.
        val kept = weeks.map { it.copy(goal = 3) }
        val again = Rewards.weeks(workouts, kept, goal = 2, today = wednesday.plusDays(7), firstDay = firstDay, zone = zone)
        assertEquals(3, again.first { it.weekStart == sunday.toEpochDay() }.goal)
        // A badge earned on the Wednesday finds that week's goal.
        val badge = BadgeRow("first_workout", earnedAt = at(wednesday, 9), workoutStart = at(wednesday, 8))
        assertEquals(3, Rewards.goalAt(badge, kept, firstDay, zone))
    }

    @Test fun `a Monday-first locale keeps Monday weeks`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals(LocalDate.of(2026, 10, 12), Rewards.weekStart(wednesday, Weeks.firstDay()))
        val stats = listOf(DayStat(sunday, 10f, 10f, 10f, 1), DayStat(wednesday, 30f, 30f, 30f, 1))
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12)), History.weekly(stats).map { it.date })
    }
}
