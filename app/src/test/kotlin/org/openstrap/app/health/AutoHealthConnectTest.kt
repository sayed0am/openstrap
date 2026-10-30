package org.openstrap.app.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.settings.HealthConnectAuto
import org.openstrap.app.health.AutoHealthConnect.GAP_MS
import org.openstrap.app.health.AutoHealthConnect.MAX_S
import org.openstrap.app.health.AutoHealthConnect.QUIET_MS
import org.openstrap.app.health.AutoHealthConnect.RECENT_S

/** Auto sync to Health Connect: what range a sync writes, how long it waits, a workout's range and when it stops. */
class AutoHealthConnectTest {
    private val now = 1_800_000_000L

    @Test fun `the first write covers the last two days`() {
        assertEquals(now - RECENT_S to now, AutoHealthConnect.range(now, null))
    }

    @Test fun `a recent write covers the two days before it, so nothing between writes is missed`() {
        assertEquals(now - 600 - RECENT_S to now, AutoHealthConnect.range(now, now - 600))
    }

    @Test fun `days away reach back to two days before the last write`() {
        val last = now - 5 * 86_400L
        assertEquals(last - RECENT_S to now, AutoHealthConnect.range(now, last))
    }

    @Test fun `a long time away is capped at 30 days`() {
        assertEquals(now - MAX_S to now, AutoHealthConnect.range(now, now - 90 * 86_400L))
    }

    @Test fun `a last write in the future, from a clock change, covers the last two days`() {
        assertEquals(now - RECENT_S to now, AutoHealthConnect.range(now, now + 3600))
    }

    @Test fun `with no write yet a sync waits the quiet minute`() {
        assertEquals(QUIET_MS, AutoHealthConnect.delayMs(now * 1000, null))
    }

    @Test fun `back to back syncs wait for the gap after the last write`() {
        val nowMs = now * 1000
        assertEquals(GAP_MS - 2 * 60_000L, AutoHealthConnect.delayMs(nowMs, nowMs - 2 * 60_000L))
    }

    @Test fun `once the gap has passed a sync waits only the quiet minute`() {
        val nowMs = now * 1000
        assertEquals(QUIET_MS, AutoHealthConnect.delayMs(nowMs, nowMs - GAP_MS - 1))
        // Close to the gap's end, the quiet minute still wins.
        assertEquals(QUIET_MS, AutoHealthConnect.delayMs(nowMs, nowMs - GAP_MS + 10_000L))
    }

    @Test fun `a workout is written over whole hours, not past now`() {
        val hour = now / 3600 * 3600
        // 10:20 to 10:50, saved at 10:51: from 10:00 up to now.
        assertEquals(hour to hour + 51 * 60, AutoHealthConnect.workoutRange(hour + 20 * 60, hour + 50 * 60, hour + 51 * 60))
        // Trimmed later in the day: to the end of the hour it ended in.
        assertEquals(hour to hour + 3599, AutoHealthConnect.workoutRange(hour + 20 * 60, hour + 50 * 60, hour + 5 * 3600))
        // Across an hour: both hours whole.
        assertEquals(hour to hour + 2 * 3600 - 1, AutoHealthConnect.workoutRange(hour + 40 * 60, hour + 3600 + 30 * 60, hour + 6 * 3600))
    }

    @Test fun `auto sync stops when Health Connect goes or its permissions are taken back`() {
        assertEquals(HealthConnectAuto.Stopped.UNAVAILABLE, AutoHealthConnect.stopReason(available = false, granted = false))
        assertEquals(HealthConnectAuto.Stopped.PERMISSIONS, AutoHealthConnect.stopReason(available = true, granted = false))
        assertNull(AutoHealthConnect.stopReason(available = true, granted = true))
    }

    @Test fun `an edited sleep is written over every time it can be shown at, not past now`() {
        val start = 1_000_000L
        assertEquals(start - 3 * 3600 to start + 19 * 3600, AutoHealthConnect.sleepRange(start, start + 30 * 3600))
        assertEquals(start - 3 * 3600 to start + 9 * 3600, AutoHealthConnect.sleepRange(start, start + 9 * 3600))
    }
}
