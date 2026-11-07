package org.openstrap.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.device.SyncPolicy.Trigger

class SyncPolicyTest {
    private val now = 1_794_054_000L
    private fun due(trigger: Trigger, minutesAgo: Long?, failed: Boolean = false) =
        SyncPolicy.due(trigger, now, minutesAgo?.let { now - it * 60 }, failed)

    @Test
    fun firstSyncAlwaysRuns() {
        Trigger.entries.forEach { assertTrue(due(it, null)) }
    }

    @Test
    fun connectAndTimerWaitSixHours() {
        listOf(Trigger.CONNECT, Trigger.TIMER).forEach {
            assertFalse(due(it, 20))
            assertFalse(due(it, 5 * 60 + 59))
            assertTrue(due(it, 6 * 60))
        }
    }

    @Test
    fun appInFrontWaitsFiveMinutes() {
        assertFalse(due(Trigger.APP_IN_FRONT, 4))
        assertTrue(due(Trigger.APP_IN_FRONT, 5))
    }

    @Test
    fun aFailedSyncIsRetried() {
        Trigger.entries.forEach { assertTrue(due(it, 1, failed = true)) }
    }
}
