package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.History
import org.openstrap.app.presentation.DetailCopy.Level
import java.time.LocalDate
import java.util.Locale
import org.openstrap.app.presentation.DaySource
import org.openstrap.app.presentation.DetailCopy
import org.openstrap.app.presentation.Metric
import org.openstrap.app.presentation.Range
import org.openstrap.app.presentation.daySource
import org.openstrap.app.presentation.TodayCopy

/** A detail screen's words and plain decisions (spec 6.1). */
class DetailCopyTest {
    private val normal = History.Normal(mean = 51f, low = 44f, high = 58f, days = 30)

    @Test fun `the status word says where a value sits against your normal`() {
        assertEquals("Above your normal", DetailCopy.statusWord(1))
        assertEquals("Usual", DetailCopy.statusWord(0))
        assertEquals("Below your normal", DetailCopy.statusWord(-1))
        assertNull(DetailCopy.statusWord(null))
    }

    @Test fun `points inside your normal stay dim, outside it they're lit`() {
        assertEquals(
            listOf(Level.Inside, Level.Above, null, Level.Below, Level.Inside, Level.Inside),
            DetailCopy.levels(listOf(50f, 60f, null, 40f, 44f, 58f), normal),
        )
        // Without a normal nothing is off it.
        assertEquals(listOf(Level.Inside, null), DetailCopy.levels(listOf(90f, null), null))
    }

    @Test fun `the band is labelled with your normal`() {
        assertEquals("Your normal, 44 to 58", DetailCopy.bandLabel(normal) { Math.round(it).toString() })
        assertEquals("Learning your normal, 4 of 7 nights", DetailCopy.learningNormal(4, nightly = true))
        assertEquals("Learning your normal, 7 of 7 days", DetailCopy.learningNormal(12, nightly = false))
    }

    @Test fun `the latest run off your normal counts back over missing days`() {
        assertEquals(Level.Below to 3, DetailCopy.latestRun(listOf(Level.Inside, Level.Below, null, Level.Below, Level.Below)))
        assertNull(DetailCopy.latestRun(listOf(Level.Below, Level.Inside)))
        assertNull(DetailCopy.latestRun(listOf(null, null)))
        assertEquals(Level.Above to 1, DetailCopy.latestRun(listOf(Level.Below, Level.Above)))
    }

    @Test fun `the day's sentence talks about where its number comes from`() {
        assertEquals("So far today, within your normal.", DetailCopy.day(0, DaySource.SoFar, today = true, days = 30))
        assertEquals("Last night, below your normal.", DetailCopy.day(-1, DaySource.Night, today = true, days = 30))
        assertEquals("Today's average so far, above your normal.", DetailCopy.day(1, DaySource.Average, today = true, days = 30))
        assertEquals("That day, above your normal.", DetailCopy.day(1, DaySource.SoFar, today = false, days = 30))
        assertEquals("That night, within your normal.", DetailCopy.day(0, DaySource.Night, today = false, days = 30))
        assertEquals("That day's average, within your normal.", DetailCopy.day(0, DaySource.Average, today = false, days = 30))
        assertEquals("Learning your normal, 3 of 7 days.", DetailCopy.day(null, DaySource.SoFar, today = true, days = 3))
        assertEquals("Between 52 and 148 bpm so far today.", DetailCopy.heartDay(52, 148, today = true))
    }

    @Test fun `each metric's day is worded by its source`() {
        // Resting heart rate and breathing rate come from the night, never "so far today".
        assertEquals("Last night, within your normal.", DetailCopy.day(0, daySource(Metric.RestingHr), today = true, days = 30))
        assertEquals("Last night, within your normal.", DetailCopy.day(0, daySource(Metric.Breathing), today = true, days = 30))
        // The rest are the day's readings, averaged or added up.
        listOf(Metric.Hrv, Metric.Spo2, Metric.SkinTemperature, Metric.Stress).forEach { assertEquals(it.name, DaySource.Average, daySource(it)) }
        listOf(Metric.Steps, Metric.HeartRate).forEach { assertEquals(it.name, DaySource.SoFar, daySource(it)) }
    }

    @Test fun `with no value the hero says the state, never a lone dash`() {
        val learning = org.openstrap.app.data.Recovery.Readiness(
            score = null, contributors = emptyList(), baselineNights = 6, strainAlert = false, alertSignals = emptyList(), capped = false,
        )
        assertEquals("Learning" to "6 of 7 nights", DetailCopy.recoveryState(learning, today = true))
        assertEquals("Waiting" to "For last night's sleep", DetailCopy.recoveryState(null, today = true))
        assertEquals("For last night", DetailCopy.noValue(Metric.RestingHr, Range.Day, today = true))
        assertEquals("In the last 7 days", DetailCopy.noValue(Metric.Hrv, Range.Week, today = true))
    }

    @Test fun `a week leads with a run off your normal, otherwise counts the days inside`() {
        val run = listOf(Level.Inside, Level.Inside, Level.Inside, Level.Inside, Level.Below, Level.Below, Level.Below)
        assertEquals("Below your normal for three nights.", DetailCopy.span(Range.Week, run, nightly = true, normalKnown = true, days = 30))
        val mixed = listOf(Level.Inside, Level.Above, Level.Inside, Level.Inside, Level.Inside, Level.Inside, Level.Inside)
        assertEquals("Within your normal on 6 of 7 days.", DetailCopy.span(Range.Week, mixed, nightly = false, normalKnown = true, days = 30))
        assertEquals("Within your normal on every day.", DetailCopy.span(Range.Week, List(7) { Level.Inside }, nightly = false, normalKnown = true, days = 30))
        assertEquals(
            "Within your normal on every day with data in the last 30 days.",
            DetailCopy.span(Range.Month, List(20) { Level.Inside } + List(10) { null }, nightly = false, normalKnown = true, days = 20),
        )
        // One day off isn't a run.
        assertEquals("Within your normal on 6 of 7 days.", DetailCopy.span(Range.Week, mixed.reversed(), nightly = false, normalKnown = true, days = 30))
        assertEquals("Learning your normal, 5 of 7 days.", DetailCopy.span(Range.Week, mixed, nightly = false, normalKnown = false, days = 5))
        assertNull(DetailCopy.span(Range.Week, List(7) { null }, nightly = false, normalKnown = true, days = 0))
    }

    @Test fun `the year and a score's week in a sentence`() {
        assertEquals("Over the last 12 months, weeks averaged from 48 to 61 ms.", DetailCopy.year("48", "61", "ms"))
        assertEquals("Over the last 12 months, weeks averaged from 20 to 41.", DetailCopy.year("20", "41", ""))
        assertEquals("Averaged 74 over the last 7 days, from 61 to 84.", DetailCopy.scoreWeek(listOf(61, null, 84, 77)))
        assertEquals("One score in the last 7 days so far.", DetailCopy.scoreWeek(listOf(null, 70)))
        assertNull(DetailCopy.scoreWeek(listOf(null, null)))
    }

    @Test fun `Charge on a past day says where it started and ended`() {
        assertEquals("Started the day at 84 and ended it at 30.", DetailCopy.charge(30, 84, 60, anchored = true, today = false, hour = 9))
        assertEquals(TodayCopy.charge(60, 30, true, 9), DetailCopy.charge(60, 84, 30, anchored = true, today = true, hour = 9))
    }

    @Test fun `the label under the value names what it is for the range`() {
        assertEquals("bpm now", DetailCopy.valueLabel(Metric.HeartRate, Range.Day, live = true))
        assertEquals("ms, day average", DetailCopy.valueLabel(Metric.Hrv, Range.Day))
        assertEquals("Day average", DetailCopy.valueLabel(Metric.Stress, Range.Day))
        assertEquals("steps a day, last 7 days", DetailCopy.valueLabel(Metric.Steps, Range.Week))
        assertEquals("ms, average of the last 30 days", DetailCopy.valueLabel(Metric.Hrv, Range.Month))
    }

    @Test fun `the strip ends today while the day shown is in the last week`() {
        val today = LocalDate.of(2026, 10, 22)
        val strip = DetailCopy.strip(LocalDate.of(2026, 10, 19), today, setOf(LocalDate.of(2026, 10, 20)), Locale.UK)
        assertEquals(7, strip.size)
        assertEquals(today, strip.last().date)
        assertEquals(listOf("Fri", "Sat", "Sun", "Mon", "Tue", "Wed", "Thu"), strip.map { it.label })
        assertTrue(strip.single { it.selected }.date == LocalDate.of(2026, 10, 19))
        assertEquals(listOf(LocalDate.of(2026, 10, 20)), strip.filter { it.hasData }.map { it.date })
        // An older day ends its own strip.
        assertEquals(LocalDate.of(2026, 9, 1), DetailCopy.strip(LocalDate.of(2026, 9, 1), today, emptySet()).last().date)
    }

    @Test fun `no detail sentence uses an interpunct, semicolon or em dash`() {
        val lines = listOfNotNull(
            DetailCopy.day(0, DaySource.Night, true, 30), DetailCopy.heartDay(1, 2, false), DetailCopy.year("1", "2", "ms"),
            DetailCopy.span(Range.Month, listOf(Level.Above, Level.Above), false, true, 30), DetailCopy.scoreWeek(listOf(1, 2)),
            DetailCopy.charge(1, 2, 1, true, false, 3), DetailCopy.learningNormal(1, true), DetailCopy.bandLabel(normal) { it.toString() },
        ) + Metric.entries.flatMap { m -> Range.entries.map { DetailCopy.valueLabel(m, it) } }
        lines.forEach { assertTrue(it, it.none { c -> c == '·' || c == ';' || c == '—' }) }
    }
}
