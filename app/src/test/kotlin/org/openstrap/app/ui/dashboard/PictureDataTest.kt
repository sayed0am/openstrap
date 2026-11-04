package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.presentation.ChartPoint
import java.time.DayOfWeek
import java.time.LocalDate

/** The stress strip, Steps' goal facts and the week of steps (picture cards plan, Data audit). */
class PictureDataTest {
    private val date = LocalDate.of(2026, 11, 4) // A Wednesday.

    @Test fun `the stress strip is the day hour by hour`() {
        val start = 1_000_000L
        val end = start + 24 * 3600 - 1
        val samples = listOf(
            ChartPoint(start, 20f), ChartPoint(start + 1800, 40f), // Hour 0: mean 30.
            ChartPoint(start + 5 * 3600 + 60, 90f), // Hour 5.
            ChartPoint(end, 150f), // The last second, held to the top.
            ChartPoint(start - 1, 50f), ChartPoint(end + 1, 50f), // Outside the day.
        )
        val strip = stressStrip(samples, start, end)
        assertEquals(24, strip.size)
        assertEquals(0.3f, strip[0]!!, 0.0001f)
        assertNull(strip[1])
        assertEquals(0.9f, strip[5]!!, 0.0001f)
        assertEquals(1f, strip[23]!!, 0.0001f)
        assertEquals(3, strip.count { it != null })
        // Half hours, and a clock change's 25-hour day.
        assertEquals(48, stressStrip(samples, start, end, 1800).size)
        assertEquals(25, stressStrip(emptyList(), start, start + 25 * 3600 - 1).size)
        assertEquals(emptyList<Float?>(), stressStrip(samples, end, start))
    }

    private fun days(vararg steps: Pair<Int, Int>) = steps.associate { (back, n) -> date.minusDays(back.toLong()) to n }

    @Test fun `Steps' goal facts`() {
        val history = days(1 to 9000, 2 to 8500, 3 to 7000, 10 to 15000, 40 to 20000, 0 to 1)
        val f = stepsGoalFacts(date, 9500, 8000, history, bestBefore = 18000)
        assertEquals(9500, f.value)
        assertEquals(8000, f.goal)
        // Day 40 is outside the month, and today's own entry is never its own best.
        assertEquals(15000, f.monthBest)
        assertEquals(18000, f.best)
        assertEquals(3, f.streak)
        // A stored best that lags is raised to the month's.
        assertEquals(15000, stepsGoalFacts(date, 9500, 8000, history, bestBefore = 12000).best)
        assertEquals(15000, stepsGoalFacts(date, 9500, 8000, history, bestBefore = null).best)
        // Below the goal today, no streak.
        assertEquals(0, stepsGoalFacts(date, 7999, 8000, history, null).streak)
        assertEquals(1, stepsGoalFacts(date, 8000, 8000, emptyMap(), null).streak)
        val none = stepsGoalFacts(date, 100, 8000, emptyMap(), null)
        assertNull(none.best)
        assertNull(none.monthBest)
        assertEquals("3 days in a row", goalTag(GoalMetric.Steps, f, strings = org.openstrap.app.testing.FileStrings())?.text)
    }

    @Test fun `the week runs Monday to Sunday, days to come empty`() {
        val w = stepsWeek(date, 4000, 8000, days(1 to 9000, 2 to 7000, 5 to 12000))
        assertEquals(8000, w.goal)
        assertEquals((0..6).map { LocalDate.of(2026, 11, 2).plusDays(it.toLong()) }, w.days.map { it.first })
        assertEquals(listOf(7000, 9000, 4000, null, null, null, null), w.days.map { it.second })
        // A past day picked: its later days are there.
        val past = stepsWeek(date, 4000, 8000, days(-1 to 3000), today = date.plusDays(2))
        assertEquals(listOf(0, 0, 4000, 3000, 0, null, null), past.days.map { it.second })
        val sunday = stepsWeek(date, 4000, 8000, emptyMap(), firstDay = DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), sunday.days.first().first)
    }

    @Test fun `the activity history hands its own day's facts only`() {
        val h = ActivityHistory(date = date, steps = days(1 to 9000), stepsBest = 10000)
        assertEquals(GoalFacts(9500, 8000, 10000, 9000, 2), h.stepsFacts(date, 9500, 8000))
        assertNull(h.stepsFacts(date.minusDays(1), 9500, 8000))
        assertEquals(9000, h.stepsWeek(date, 9500, 8000)!!.days[1].second)
        assertNull(h.stepsWeek(date.plusDays(1), 9500, 8000))
    }
}
