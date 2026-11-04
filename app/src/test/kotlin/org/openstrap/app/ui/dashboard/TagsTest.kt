package org.openstrap.app.ui.dashboard

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.presentation.Metric
import org.openstrap.app.testing.FileStrings

/** The news tags (spec 2.5, Tags, and 8.2). */
class TagsTest {
    private val strings = FileStrings()
    private fun steps(value: Int, best: Int? = null, month: Int? = null, streak: Int = 0, goal: Int = 8000) =
        goalTag(GoalMetric.Steps, GoalFacts(value, goal, best, month, streak), strings = strings, locale = Locale.US)?.text

    @Test fun `below the goal there is no tag`() {
        assertNull(steps(7999))
        assertNull(goalTag(GoalMetric.Steps, GoalFacts(100, 0), strings = strings))
    }

    @Test fun `the ladder from Goal met up`() {
        assertEquals("Goal met", steps(8000))
        assertEquals("Goal met", steps(9999))
        assertEquals("3 days in a row", steps(9000, streak = 3))
        assertEquals("+2,000 past goal", steps(10000))
        assertEquals("+2,000 past goal", steps(10000, streak = 5))
        assertEquals("Double goal", steps(16000))
        assertEquals("Best this month", steps(16000, month = 15000))
        assertEquals("Personal best", steps(16000, best = 15500, month = 15000))
    }

    @Test fun `a best only counts when it is beaten`() {
        assertEquals("Goal met", steps(8000, best = 8000, month = 8000))
        assertEquals("Personal best", steps(8001, best = 8000))
    }

    @Test fun `sleep stops at Goal met`() {
        val rung = goalRung(GoalMetric.Sleep, GoalFacts(1000, 450, best = 500, monthBest = 400, streak = 9))
        assertEquals(GoalRung.GoalMet, rung)
    }

    @Test fun `calories and active hours stop at Goal met on a day to ease off`() {
        for (m in listOf(GoalMetric.Calories, GoalMetric.ActiveHours)) {
            assertEquals(GoalRung.GoalMet, goalRung(m, GoalFacts(1000, 400), easeOff = true))
            assertEquals(GoalRung.DoubleGoal, goalRung(m, GoalFacts(1000, 400), easeOff = false))
        }
        assertEquals(GoalRung.DoubleGoal, goalRung(GoalMetric.Steps, GoalFacts(1000, 400), easeOff = true))
    }

    @Test fun `off words`() {
        fun t(w: OffWords, s: OffStatus) = offTag(w, s, strings)?.text
        assertEquals("Above normal", t(OffWords.Normal, OffStatus.Above))
        assertEquals("Below normal", t(OffWords.Normal, OffStatus.Below))
        assertEquals("Below normal", t(OffWords.LowOnly, OffStatus.Below))
        assertNull(t(OffWords.LowOnly, OffStatus.Above))
        assertEquals("Above usual", t(OffWords.Usual, OffStatus.Above))
        assertEquals("Short", t(OffWords.Length, OffStatus.Below))
        assertEquals("Long", t(OffWords.Length, OffStatus.Above))
        assertEquals("Later", t(OffWords.Timing, OffStatus.Above))
        assertEquals("Younger", t(OffWords.Age, OffStatus.Below))
        assertEquals("More than usual", t(OffWords.MoreOnly, OffStatus.Above))
        assertNull(t(OffWords.MoreOnly, OffStatus.Below))
        assertEquals("Down", t(OffWords.Trend, OffStatus.Below))
        for (w in OffWords.entries) assertNull(t(w, OffStatus.Usual))
    }

    @Test fun `the rim lights only when the off tag is news`() {
        assertTrue(rimLit(OffWords.Normal, OffStatus.Below))
        assertFalse(rimLit(OffWords.LowOnly, OffStatus.Above))
        assertFalse(rimLit(OffWords.Usual, OffStatus.Usual))
    }

    @Test fun `one tag per number, the goal first`() {
        val goal = NewsTag("Goal met", goal = true)
        val off = NewsTag("Above usual", goal = false)
        assertEquals(goal, newsTag(goal, off))
        assertEquals(off, newsTag(null, off))
        assertNull(newsTag(null, null))
    }

    @Test fun `blood oxygen only says below`() {
        assertNull(bodyTag(Metric.Spo2, 1, strings))
        assertEquals("Below normal", bodyTag(Metric.Spo2, -1, strings)?.text)
        assertEquals("Above normal", bodyTag(Metric.Hrv, 1, strings)?.text)
        assertNull(bodyTag(Metric.Hrv, null, strings))
    }
}
