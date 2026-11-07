package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    private val now = 1_794_054_000_000L

    @Test
    fun everyTwelfthPingSyncs() {
        val p = SyncPolicy()
        val due = (1..36).filter { p.backgroundSyncDue() }
        assertEquals(listOf(12, 24, 36), due)
        assertEquals(36, p.wakeUps)
    }

    @Test
    fun successGapIsMoreThan120Seconds() {
        assertTrue(SyncPolicy.successGapPassed(now, null))
        assertFalse(SyncPolicy.successGapPassed(now, now - 120_000))
        assertTrue(SyncPolicy.successGapPassed(now, now - 120_001))
    }

    @Test
    fun noMockWithoutAPreviousStart() {
        val p = SyncPolicy()
        assertFalse(p.mock(null, now))
        assertFalse(p.mock(0L, now))
    }

    @Test
    fun mockWithin60sExceptEachThirdCheck() {
        val p = SyncPolicy()
        // Zepp's counter: 0 mock, 1 mock, 2 mock, 3 real, 4 mock, 5 mock, 6 real.
        val results = (0 until 7).map { p.mock(now - 10_000, now) }
        assertEquals(listOf(true, true, true, false, true, true, false), results)
    }

    @Test
    fun checksOutsideTheWindowStillCount() {
        val p = SyncPolicy()
        assertFalse(p.mock(now - 61_000, now))
        assertFalse(p.mock(now - 61_000, now))
        assertTrue(p.mock(now - 30_000, now))
        // The 4th check (counter 3) runs for real even inside the window.
        assertFalse(p.mock(now - 30_000, now))
    }

    @Test
    fun mockWindowEdges() {
        assertTrue(SyncPolicy().mock(now - 60_000, now))
        assertFalse(SyncPolicy().mock(now - 60_001, now))
        assertFalse(SyncPolicy().mock(now, now))
    }

    @Test
    fun backgroundDelayIsOneTo59Seconds() {
        val r = kotlin.random.Random(7)
        repeat(1_000) { assertTrue(SyncPolicy.backgroundDelayMs(r) in 1_000L..59_000L) }
    }
}
