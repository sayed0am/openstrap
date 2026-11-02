package org.openstrap.app.ui.dashboard

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.EnergyExpenditure
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.WakingLead
import org.openstrap.app.presentation.wakingLead
import org.openstrap.app.presentation.AFTER_FIRST_NIGHT
import org.openstrap.app.presentation.TabCopy
import org.openstrap.app.presentation.TodayCopy

/** Data states (spec 4.3): the pairing steps, who leads with no sleep, the first score once, and the partial day rule. */
class DataStatesTest {
    private val day = LocalDate.of(2026, 10, 24)
    private val start = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    private val end = start + 86_399

    @Test fun `before a strap is paired the step follows the scan`() {
        assertEquals(PairStep.Ready, pairStep(false, scanning = false, found = false, ConnectionState.NotPaired))
        assertEquals(PairStep.Searching, pairStep(false, scanning = true, found = false, ConnectionState.NotPaired))
        assertEquals(PairStep.Found, pairStep(false, scanning = true, found = true, ConnectionState.NotPaired))
        // A strap found stays found when the scan stops, until it's paired.
        assertEquals(PairStep.Found, pairStep(false, scanning = false, found = true, ConnectionState.NotPaired))
    }

    @Test fun `once paired the step follows the connection`() {
        assertEquals(PairStep.Connecting, pairStep(true, false, false, ConnectionState.Connecting))
        assertEquals(PairStep.Connecting, pairStep(true, false, false, ConnectionState.Authenticating))
        assertEquals(PairStep.Connecting, pairStep(true, false, false, ConnectionState.Disconnected))
        assertEquals(PairStep.Connected, pairStep(true, false, false, ConnectionState.Connected(80, null)))
        assertEquals(PairStep.Failed, pairStep(true, false, false, ConnectionState.Failed("timeout", keyRejected = false)))
        assertEquals(PairStep.KeyRejected, pairStep(true, false, false, ConnectionState.Failed("auth", keyRejected = true)))
    }

    @Test fun `every pairing step has its own sentence, in plain copy`() {
        val all = PairStep.entries.map(::pairSentence)
        assertEquals(all.size, all.toSet().size)
        assertEquals("Put the strap on its charger, close to this phone.", pairSentence(PairStep.Ready))
        assertTrue(pairSentence(PairStep.Connected).startsWith("Connected."))
    }

    @Test fun `with no sleep last night Charge leads the morning, today only`() {
        assertEquals(WakingLead.Charge, wakingLead(firstNights = false, asleepSeconds = null, isToday = true))
        assertEquals(WakingLead.Recovery, wakingLead(firstNights = false, asleepSeconds = 7 * 3600L, isToday = true))
        // Before the first night Recovery says when it starts; a past day says there was no sleep that night.
        assertEquals(WakingLead.Recovery, wakingLead(firstNights = true, asleepSeconds = null, isToday = true))
        assertEquals(WakingLead.Recovery, wakingLead(firstNights = false, asleepSeconds = null, isToday = false))
    }

    @Test fun `the first score shows all its day and is remembered`() {
        val first = firstScore(64, day, isToday = true, learning = true, earlier = null, seen = null)
        assertTrue(first.show)
        assertEquals(day, first.remember)
        // Opened again that day it still shows, and nothing more is written.
        assertEquals(FirstScore(true), firstScore(64, day, isToday = true, learning = true, earlier = null, seen = day))
        // The next day, never again.
        assertEquals(FirstScore(false), firstScore(70, day.plusDays(1), isToday = true, learning = true, earlier = day, seen = day))
    }

    @Test fun `a score that isn't the first never shows the marker`() {
        val week = listOf(day.minusDays(3) to 70, day.minusDays(2) to null, day.minusDays(1) to 72, day to 75)
        assertEquals(day.minusDays(3), earliestScore(week, day))
        assertEquals(FirstScore(false, remember = day.minusDays(3)), firstScore(75, day, true, true, earliestScore(week, day), null))
        // Past the first scored phase with nothing else in the week: from before the marker was kept.
        assertEquals(FirstScore(false, remember = BEFORE_FIRST_SCORE), firstScore(75, day, true, learning = false, earlier = null, seen = null))
        // No score, or a past day with nothing stored: nothing decided, nothing written.
        assertEquals(FirstScore(false), firstScore(null, day, true, true, null, null))
        assertEquals(FirstScore(false), firstScore(64, day, isToday = false, learning = true, earlier = null, seen = null))
        assertNull(earliestScore(listOf(day to 75), day))
    }

    @Test fun `a day is partial below 20 h worn, the energy estimate's full day`() {
        assertEquals(EnergyExpenditure.FULL_DAY_MINUTES / 1440f, PARTIAL_WEAR_SHARE)
        assertFalse(wear(EnergyExpenditure.FULL_DAY_MINUTES, emptyList(), start, end, isToday = false, newest = null)!!.partial)
        assertTrue(wear(EnergyExpenditure.FULL_DAY_MINUTES - 1, emptyList(), start, end, isToday = false, newest = null)!!.partial)
        assertEquals(1440, wear(600, emptyList(), start, end, false, null)!!.ofMinutes)
    }

    @Test fun `today counts the share of the day so far, up to the newest reading`() {
        val noon = start + 12 * 3600
        // 10 h worn of the 12 h so far is exactly the 20 of 24 share: not partial. A minute less is.
        assertFalse(wear(600, emptyList(), start, end, isToday = true, newest = noon)!!.partial)
        assertTrue(wear(599, emptyList(), start, end, isToday = true, newest = noon)!!.partial)
        assertEquals(720, wear(600, emptyList(), start, end, isToday = true, newest = noon)!!.ofMinutes)
    }

    @Test fun `without the energy estimate wear comes from the heart rate buckets`() {
        // Every 5 minutes of the first 10 hours: 600 minutes worn, out of the 600 so far.
        val buckets = (0 until 120).map { start + it * 300L }
        val w = wear(null, buckets, start, end, isToday = true, newest = buckets.last() + 300)!!
        assertEquals(Wear(600, 600), w)
        assertFalse(w.partial)
        // Half of them missing: partial.
        assertTrue(wear(null, buckets.filterIndexed { i, _ -> i % 2 == 0 }, start, end, true, buckets.last() + 300)!!.partial)
        // No wear at all is an empty day waiting for data, not a partial one.
        assertNull(wear(null, emptyList(), start, end, true, null))
        assertNull(wear(0, buckets, start, end, false, null))
    }

    @Test fun `a day's wear reads its heart rate`() {
        val state = DayUiState(date = day, dayStart = start, dayEnd = end, heartRate = (0 until 60).map { ChartPoint(start + it * 300L, 60f) })
        assertEquals(Wear(300, 300), state.wear(null, isToday = true))
        assertTrue(state.wear(null, isToday = false)!!.partial)
    }

    @Test fun `the partial day sheet says how much wear the figures cover`() {
        assertEquals("The strap recorded 6 h 30 of the 12 h so far today.", partialLine(Wear(390, 720), isToday = true))
        assertEquals("The strap recorded 14 h of the day.", partialLine(Wear(840, 1440), isToday = false))
    }

    @Test fun `data previews change only what is shown`() {
        val state = DayUiState(date = day, dayStart = start, dayEnd = end)
        assertEquals(state, state.previewed(null))
        assertEquals(state, state.previewed(DataPreview.Partial))
        assertNull(null.previewed(null, 600))
        assertEquals(Wear(330, 600), null.previewed(DataPreview.Partial, 600))
        assertTrue(Wear(1400, 1440).previewed(DataPreview.Partial, 1440)!!.partial)
        assertEquals(Wear(1400, 1440), Wear(1400, 1440).previewed(DataPreview.NoSleep, 1440))
        assertEquals(DataPreview.PairConnecting, DataPreview.of("pair_connecting"))
        assertEquals(PairStep.Connecting, DataPreview.PairConnecting.pair)
        assertTrue(DataPreview.PairSearch.unpaired)
        assertFalse(DataPreview.PairConnected.unpaired)
        assertNull(DataPreview.of("auto"))
    }

    @Test fun `state copy has no interpunct, semicolon or em dash`() {
        val texts = PairStep.entries.map(::pairSentence) + listOf(
            AFTER_FIRST_NIGHT, FIRST_SCORE, PARTIAL_DAY, PARTIAL_DAY_INFO.meaning, partialLine(Wear(390, 720), true),
            partialLine(Wear(390, 1440), false), TodayCopy.NO_SLEEP, TodayCopy.RECOVERY_FIRST_NIGHT, TabCopy.NO_SLEEP_SCORE,
            TabCopy.FIRST_NIGHT_SLEEP,
        ) + PARTIAL_DAY_ROWS.flatMap { listOf(it.first, it.second) }
        texts.forEach { t -> listOf("·", ";", "—").forEach { assertFalse("$t has $it", it in t) } }
    }
}
