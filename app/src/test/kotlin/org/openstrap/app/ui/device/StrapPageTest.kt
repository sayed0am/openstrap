package org.openstrap.app.ui.device

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.settings.Goals
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.SyncState
import java.util.Locale

/** The Strap page's state line and the You page's values. */
class StrapPageTest {
    private val now = 1_800_000_000L
    private val connected = ConnectionState.Connected(86, "3.25")

    @Test fun `state line says what the strap is doing and when it was heard from`() {
        assertEquals("Connected. Synced 2 min ago", strapStateLine(connected, SyncState.Done(now - 120), null, now))
        assertEquals("Connected. Synced just now", strapStateLine(connected, SyncState.Done(now - 5), null, now))
        assertEquals("Connected. Syncing", strapStateLine(connected, SyncState.Running("Heart rate"), null, now))
        assertEquals("Connected. Not synced", strapStateLine(connected, SyncState.Failed("timeout"), null, now))
        assertEquals("Connected", strapStateLine(connected, SyncState.Idle, null, now))
        assertEquals("Charging. Wear data pauses until it's back on.", strapStateLine(connected.copy(charging = true), SyncState.Idle, null, now))
        assertEquals("Last seen 18 min ago", strapStateLine(ConnectionState.Disconnected, SyncState.Idle, now - 18 * 60, now))
        assertEquals("Not connected", strapStateLine(ConnectionState.Disconnected, SyncState.Idle, null, now))
        assertEquals("Connecting", strapStateLine(ConnectionState.Connecting, SyncState.Idle, null, now))
    }

    @Test fun `You page values`() {
        val goals = Goals(steps = 8000, sleepMinutes = 450, activeHours = 8, calories = 450)
        assertEquals("8,000 steps, 450 active kcal, 8 active hours", goalsLine(goals, Locale.UK))
        assertEquals("8.000 steps, 450 active kcal, 8 active hours", goalsLine(goals, Locale.GERMANY))
        // The sleep goal and need read as every other length of time does.
        assertEquals("7 h 30. Your sleep need is 8 h", sleepGoalLine(goals, 480))
        assertEquals("7 h 05. Your sleep need is 8 h", sleepGoalLine(goals.copy(sleepMinutes = 425), 480))
    }
}
