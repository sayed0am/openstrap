package org.openstrap.app.ui.glow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.db.SleepSession

/** The rules of the one trim bar for workouts, sleep and naps. */
class TrimTest {
    private val start = 1_792_000_020L // on a whole minute
    private val night = SleepSession(start, start + 8 * 3600 + 37)
    private val now = night.end + 6 * 3600

    @Test fun `a step of one second leaves a time as it is, the workout's`() {
        assertEquals(start + 29, Trim.snap(start + 29, 1))
    }

    @Test fun `a sleep's handle moves to the nearest whole minute`() {
        assertEquals(start, Trim.snap(start + 29, 60))
        assertEquals(start + 60, Trim.snap(start + 31, 60))
    }

    @Test fun `a handle back on the strap's own minute keeps its second`() {
        assertEquals(night.end, Trim.snap(night.end - 20, 60, listOf(night.start, night.end)))
        assertEquals(night.end - 37 + 60, Trim.snap(night.end + 25, 60, listOf(night.start, night.end)))
    }

    @Test fun `a sleep's bar reaches two hours either side of what the strap detected`() {
        assertEquals(night.start - 7200..night.end + 7200, Trim.sleepReach(night, night.start, night.end, now))
        assertTrue(Trim.SLEEP_REACH_SECONDS <= SleepEdits.MAX_SHIFT_SECONDS)
    }

    @Test fun `the bar takes in times the user gave before, and stops at now`() {
        assertEquals(night.start - 3 * 3600..night.end + 7200, Trim.sleepReach(night, night.start - 3 * 3600, night.end, now))
        assertEquals(night.start - 7200..night.end + 600, Trim.sleepReach(night, night.start, night.end, night.end + 600))
    }

    @Test fun `the bar stops at the sleep or nap before and after it`() {
        val before = SleepSession(night.start - 5400, night.start - 3600, nap = true)
        val after = SleepSession(night.end + 1800, night.end + 3000, nap = true)
        val elsewhere = SleepSession(night.end + 9 * 3600, night.end + 10 * 3600, nap = true)
        assertEquals(before.end..after.start, Trim.sleepReach(night, night.start, night.end, now, listOf(after, elsewhere, before)))
    }

    @Test fun `a dragged handle stays inside the reach and a minute from the other`() {
        val reach = night.start - 7200..night.end + 7200
        assertEquals(reach.first to night.end, Trim.drag(reach.first - 9999, true, night.start, night.end, reach, 60, emptyList()))
        assertEquals(night.end - 60 to night.end, Trim.drag(night.end + 60, true, night.start, night.end, reach, 1, emptyList()))
        assertEquals(night.start to night.start + 60, Trim.drag(night.start - 600, false, night.start, night.end, reach, 1, emptyList()))
    }

    @Test fun `kept is bright, the rest of the recording faded, the context past it dimmer still`() {
        val core = night.start..night.end
        assertEquals(Trim.Shade.Kept, Trim.shade(true, night.start - 60, core))
        assertEquals(Trim.Shade.LeftOut, Trim.shade(false, night.start + 60, core))
        assertEquals(Trim.Shade.Context, Trim.shade(false, night.start - 60, core))
        assertTrue(Trim.Shade.Context.alpha < Trim.Shade.LeftOut.alpha)
    }
}
