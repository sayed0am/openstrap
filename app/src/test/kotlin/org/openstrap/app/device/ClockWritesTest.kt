package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.device.ClockWrites.Companion.DATE_CHANGED
import org.openstrap.app.device.ClockWrites.Companion.TIMEZONE_CHANGED
import org.openstrap.app.device.ClockWrites.Companion.TIME_SET

class ClockWritesTest {
    private val now = 1_794_054_000_000L

    @Test
    fun pingsOneThreeFiveWriteTheClock() {
        val c = ClockWrites()
        assertEquals(listOf(true, false, true, false, true, false), (1..6).map { c.onPing() })
    }

    @Test
    fun timeAndDateBroadcastsAreSpacedBy60s() {
        val c = ClockWrites()
        assertTrue(c.onBroadcast(TIME_SET, now, 0))
        assertFalse(c.onBroadcast(DATE_CHANGED, now + 60_000, 0))
        assertTrue(c.onBroadcast(DATE_CHANGED, now + 60_001, 0))
        // A clock set back counts by distance too.
        assertTrue(c.onBroadcast(TIME_SET, now - 120_000, 0))
    }

    @Test
    fun zoneBroadcastOnlyWhenTheRawOffsetMoves() {
        val c = ClockWrites()
        val dubai = 4 * 3_600_000
        assertTrue(c.onBroadcast(TIMEZONE_CHANGED, now, dubai))
        assertFalse(c.onBroadcast(TIMEZONE_CHANGED, now, dubai))
        assertTrue(c.onBroadcast(TIMEZONE_CHANGED, now, 0))
        assertFalse(c.onBroadcast("other", now, 0))
    }
}
