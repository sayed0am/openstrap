package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.protocol.services.StepTotals
import java.time.LocalDate

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

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 1, 15)
    }
}
