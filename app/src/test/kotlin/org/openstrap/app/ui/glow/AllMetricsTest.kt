package org.openstrap.app.ui.glow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.openstrap.app.testing.LocaleRule
import org.openstrap.app.ui.components.ChartPoint
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.Metric

/** The All metrics index is a map of what exists: every metric detail once, and the screens without one. */
class AllMetricsTest {
    // The values are checked as an English phone writes them: a German one groups steps with a dot.
    @get:Rule val locale = LocaleRule()

    private val empty = DayUiState()

    @Test fun `every metric detail appears exactly once`() {
        val details = metricIndex(empty, null, null, emptyList()).flatMap { it.rows }.map { it.target }
        for (m in Metric.entries) assertEquals(m.name, 1, details.count { it == IndexTarget.Open(Detail.OfMetric(m)) })
    }

    @Test fun `the screens without a metric detail are there too`() {
        val details = metricIndex(empty, null, null, emptyList()).flatMap { it.rows }.map { it.target }.toSet()
        assertTrue(details.containsAll(listOf(IndexTarget.Open(Detail.Healthspan), IndexTarget.Open(Detail.AllWorkouts))))
    }

    @Test fun `grouped by section`() {
        assertEquals(
            listOf("Recovery", "Sleep", "Heart", "Body signals", "Activity"),
            metricIndex(empty, null, null, emptyList()).map { it.title },
        )
    }

    @Test fun `a day without a value says so instead of a number`() {
        val hrv = metricIndex(empty, null, null, emptyList()).flatMap { it.rows }.first { it.target == IndexTarget.Open(Detail.OfMetric(Metric.Hrv)) }
        assertEquals(null, hrv.value)
        assertEquals(NO_VALUE, hrv.status)
    }

    @Test fun `values are the day's, in the metric's format`() {
        val day = empty.copy(
            steps = 13860, latestHeartRate = 58,
            spo2 = listOf(ChartPoint(0, 96f), ChartPoint(60, 98f)),
            skinTemperature = listOf(ChartPoint(0, 36.4f), ChartPoint(60, 36.6f)),
        )
        val rows = metricIndex(day, null, null, emptyList()).flatMap { it.rows }.associateBy { it.target }
        assertEquals("13,860", rows.getValue(IndexTarget.Open(Detail.OfMetric(Metric.Steps))).value)
        assertEquals("58", rows.getValue(IndexTarget.Open(Detail.OfMetric(Metric.HeartRate))).value)
        assertEquals("97%", rows.getValue(IndexTarget.Open(Detail.OfMetric(Metric.Spo2))).value)
        assertEquals("36.5", rows.getValue(IndexTarget.Open(Detail.OfMetric(Metric.SkinTemperature))).value)
    }

    private fun readiness(score: Int?, nights: Int, withheld: Boolean = false) =
        org.openstrap.app.data.Recovery.Readiness(score, emptyList(), nights, false, emptyList(), false, withheld = withheld)

    @Test fun `Recovery while learning says how far along it is`() {
        val n = org.openstrap.app.data.Recovery.SCORED_NIGHTS
        assertEquals("Learning, 2 of $n nights", recoveryStatus(readiness(null, 2, withheld = true)))
        assertEquals("Score for the day", recoveryStatus(readiness(80, 60)))
        assertEquals(null, recoveryStatus(null))
    }

    @Test fun `Charge is the latest today and the end of day before, workouts the last 7 days`() {
        val today = java.time.LocalDate.of(2026, 10, 23)
        fun status(date: java.time.LocalDate, name: String) =
            metricIndex(empty.copy(date = date), null, null, emptyList(), today).flatMap { it.rows }.first { it.name == name }
        // Without a value the row says so; the status words are checked through a day that has none of either.
        assertEquals(NO_VALUE, status(today, "Energy").status)
        assertEquals("Last 7 days", status(today, "Workouts").status)
    }

    @Test fun `rows whose home is a tab go to it, the rest open their detail`() {
        val targets = metricIndex(empty, null, null, emptyList()).flatMap { it.rows }.associate { it.name to it.target }
        assertEquals(IndexTarget.Home(HomeTab.Recovery), targets["Recovery"])
        assertEquals(IndexTarget.Home(HomeTab.Recovery, charge = true), targets["Energy"])
        assertEquals(IndexTarget.Home(HomeTab.Sleep), targets["Sleep"])
        assertEquals(IndexTarget.Open(Detail.Healthspan), targets["Habit Age"])
        assertEquals(IndexTarget.Open(Detail.AllWorkouts), targets["Workouts"])
        for (name in listOf("Heart rate", "Resting heart rate", "HRV", "Blood oxygen", "Skin temperature", "Breathing rate", "Stress", "Steps")) {
            assertTrue(name, targets[name] is IndexTarget.Open)
        }
        // Nothing in the index opens a copy of another tab's screen.
        assertTrue(targets.values.none { it == IndexTarget.Open(Detail.Recovery) || it == IndexTarget.Open(Detail.Sleep) })
    }

    @Test fun `Charge is cut to a whole number like the Charge card`() {
        assertEquals(57, chargeShown(57.6f))
        assertEquals(57, chargeShown(57.0f))
        assertEquals(0, chargeShown(0.9f))
    }

    @Test fun `Habit Age shows what its page shows`() {
        val learning = org.openstrap.app.data.Healthspan.Outcome(
            null, org.openstrap.app.data.Healthspan.Withheld.Learning, age = 30,
        )
        assertEquals("30" to "Starting point", habitAgeRow(learning))
        assertEquals(null to "Not worked out yet", habitAgeRow(null))
        val row = metricIndex(empty, null, learning, emptyList()).flatMap { it.rows }.first { it.name == "Habit Age" }
        assertEquals("30", row.value)
        assertEquals("Starting point", row.status)
    }

    @Test fun `no row text breaks the copy rules`() {
        metricIndex(empty, null, null, emptyList()).flatMap { it.rows }.forEach { r ->
            for (s in listOf(r.name, r.status)) assertTrue(s, s.none { it == '·' || it == ';' || it == '—' })
        }
    }
}
