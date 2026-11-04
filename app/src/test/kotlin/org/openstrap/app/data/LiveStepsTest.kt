package org.openstrap.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.protocol.services.StepTotals
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class LiveStepsTest {
    @Test
    fun plausibleTotalIsUsedDirectly() {
        assertEquals(3000, LiveSteps().today(DAY, 2456, StepTotals(3000, 2200, 120)))
        assertEquals(2456, LiveSteps().today(DAY, 2456, StepTotals(100, 70, 5)))
    }

    @Test
    fun offsetCounterAddsOnlyStepsSinceTheLastSync() {
        // Captured on firmware 3.25: 11,368 steps with 1,836 m while history held 2,456.
        val live = LiveSteps()
        assertEquals(2456, live.today(DAY, 2456, StepTotals(11368, 1836, 151)))
        assertEquals(2479, live.today(DAY, 2456, StepTotals(11391, 1853, 152)))
        // A sync brings those steps into history: re-anchor rather than count them twice.
        assertEquals(2480, live.today(DAY, 2480, StepTotals(11392, 1854, 152)))
        assertEquals(2490, live.today(DAY, 2480, StepTotals(11402, 1861, 152)))
    }

    @Test
    fun counterGoingBackwardsReanchors() {
        val live = LiveSteps()
        live.today(DAY, 2456, StepTotals(11368, 1836, 151))
        assertEquals(2456, live.today(DAY, 2456, StepTotals(9000, 10, 1)))
        assertEquals(2460, live.today(DAY, 2456, StepTotals(9004, 13, 1)))
    }

    @Test
    fun noLiveReadingFallsBackToHistory() {
        assertEquals(2456, LiveSteps().today(DAY, 2456, null))
    }

    @Test
    fun aNewDayStartsANewAnchor() {
        // One counter serves Today and the widgets for every day: yesterday's anchor must not carry over, even when
        // both days' history reads the same and the strap's counter didn't go back at midnight.
        val live = LiveSteps()
        live.today(DAY, 0, StepTotals(20000, 10, 1))
        assertEquals(100, live.today(DAY, 0, StepTotals(20100, 20, 1)))
        assertEquals(0, live.today(DAY.plusDays(1), 0, StepTotals(20150, 25, 1)))
        assertEquals(30, live.today(DAY.plusDays(1), 0, StepTotals(20180, 30, 1)))
    }

    @Test
    fun yesterdaysReadingCountsForNothingAfterMidnight() {
        // Data integrity item 10: the strap went away at 23:00 with 9,000 steps, and its reading stays as it was.
        val live = LiveSteps()
        val evening = StepTotals(9000, 6300, 350)
        live.read(evening, DAY)
        assertEquals(9000, live.today(DAY, 8800, evening))
        assertEquals(0, live.today(DAY.plusDays(1), 0, evening))
        assertEquals(120, live.today(DAY.plusDays(1), 120, evening))
        // The strap is back and sends today's total.
        val morning = StepTotals(400, 280, 15)
        live.read(morning, DAY.plusDays(1))
        assertEquals(400, live.today(DAY.plusDays(1), 120, morning))
    }

    @Test
    fun aReadingNotSeenYetIsTodays() {
        // It has only just come, before the follower kept its day.
        val live = LiveSteps()
        live.read(StepTotals(9000, 6300, 350), DAY)
        assertEquals(400, live.today(DAY.plusDays(1), 120, StepTotals(400, 280, 15)))
    }

    @Test
    fun followKeepsTheDayEachReadingCameOn() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val readings = MutableStateFlow<StepTotals?>(null)
        var date = DAY
        val live = LiveSteps().also { it.follow(readings, scope) { date } }
        val evening = StepTotals(9000, 6300, 350)
        readings.value = evening
        date = DAY.plusDays(1)
        assertEquals(50, live.today(date, 50, readings.value))
        readings.value = StepTotals(400, 280, 15)
        assertEquals(400, live.today(date, 50, readings.value))
        scope.cancel()
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 1, 15)
    }
}
