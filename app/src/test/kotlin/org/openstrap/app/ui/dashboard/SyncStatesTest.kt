package org.openstrap.app.ui.dashboard

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.SyncRecord
import org.openstrap.app.device.SyncState
import org.openstrap.app.presentation.TimeOfDay

/** Sync and strap states (spec 4.3): when syncing has stopped, the Up to marker, the stuck hero and the debug previews. */
class SyncStatesTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 24, hour, minute).toEpochSecond(ZoneOffset.UTC)
    private val connected = ConnectionState.Connected(80, null)

    @Test fun `clock times follow the 24 or 12 hour setting`() {
        assertEquals("09:40", TimeOfDay.format(at(9, 40), zone, use24Hour = true))
        assertEquals("21:05", TimeOfDay.format(at(21, 5), zone, use24Hour = true))
        assertEquals("9:40 AM", TimeOfDay.format(at(9, 40), zone, use24Hour = false, locale = Locale.US))
        assertEquals("9:05 PM", TimeOfDay.format(at(21, 5), zone, use24Hour = false, locale = Locale.US))
        assertEquals("12:00 PM", TimeOfDay.format(at(12), zone, use24Hour = false, locale = Locale.US))
    }

    @Test fun `syncing has stopped when the last sync failed or the strap is away`() {
        assertFalse(syncStopped(true, connected, SyncState.Idle))
        assertFalse(syncStopped(true, connected, SyncState.Done(at(9))))
        assertFalse(syncStopped(true, connected, SyncState.Running("Syncing")))
        assertTrue(syncStopped(true, connected, SyncState.Failed("timeout")))
        assertTrue(syncStopped(true, ConnectionState.Disconnected, SyncState.Idle))
        assertTrue(syncStopped(true, ConnectionState.Connecting, SyncState.Done(at(9))))
        assertFalse(syncStopped(false, ConnectionState.NotPaired, SyncState.Failed("timeout")))
        assertFalse(syncStopped(true, ConnectionState.NotPaired, SyncState.Idle))
    }

    @Test fun `a card is marked only once its newest reading is stale and syncing has stopped`() {
        val newest = at(9, 40)
        assertEquals("Up to 09:40", upToMarker(newest, newest + STALE_AFTER_S, stopped = true, isToday = true, zone = zone))
        assertNull(upToMarker(newest, newest + STALE_AFTER_S - 1, stopped = true, isToday = true, zone = zone))
        assertNull(upToMarker(newest, newest + 5 * 3600, stopped = false, isToday = true, zone = zone))
        assertNull(upToMarker(newest, newest + 5 * 3600, stopped = true, isToday = false, zone = zone))
        // Nothing synced today: no time to give, the card says what it waits for instead.
        assertNull(upToMarker(null, newest, stopped = true, isToday = true, zone = zone))
        assertEquals("Up to 09:40", upToMarker(newest, newest, true, true, zone, staleAfter = 0))
    }

    @Test fun `a failing sync takes the hero after exactly 3 hours`() {
        val since = at(13)
        assertFalse(syncStuck(true, hasLastNight = true, failingSince = since, now = since + STUCK_AFTER_S - 1, zone = zone))
        assertTrue(syncStuck(true, hasLastNight = true, failingSince = since, now = since + STUCK_AFTER_S, zone = zone))
        assertFalse(syncStuck(false, hasLastNight = true, failingSince = since, now = since + STUCK_AFTER_S, zone = zone))
    }

    @Test fun `in the morning without last night a failing sync takes the hero at once`() {
        assertTrue(syncStuck(true, hasLastNight = false, failingSince = at(7, 40), now = at(7, 42), zone = zone))
        assertTrue(syncStuck(true, hasLastNight = false, failingSince = at(4), now = at(4), zone = zone))
        assertTrue(syncStuck(true, hasLastNight = false, failingSince = at(11, 59), now = at(11, 59), zone = zone))
        assertFalse(syncStuck(true, hasLastNight = false, failingSince = at(12), now = at(12), zone = zone))
        assertFalse(syncStuck(true, hasLastNight = false, failingSince = at(3, 59), now = at(3, 59), zone = zone))
        assertFalse(syncStuck(true, hasLastNight = true, failingSince = at(7, 40), now = at(7, 42), zone = zone))
    }

    @Test fun `no failure, or no sync ever, is never stuck`() {
        assertFalse(syncStuck(true, hasLastNight = false, failingSince = null, now = at(7), zone = zone))
        assertNull(lastTried(SyncRecord(), zone))
    }

    @Test fun `the stuck hero says what is on the strap`() {
        assertEquals("Last night is still on the strap.", stuckCopy(false, "09:40").sentence)
        assertEquals("Your data since 09:40 is still on the strap.", stuckCopy(true, "09:40").sentence)
        assertEquals("Your data is still on the strap.", stuckCopy(true, null).sentence)
        assertEquals("Last tried 07:42", lastTried(SyncRecord(lastAttempt = at(7, 42)), zone, use24Hour = true))
    }

    @Test fun `state copy has no interpunct, semicolon or em dash`() {
        val texts = listOf(stuckCopy(false, "09:40"), stuckCopy(true, "09:40"), stuckCopy(true, null))
            .flatMap { listOf(it.title, it.sentence, it.line) } + listOfNotNull(upToMarker(at(9), at(12), true, true, zone))
        texts.forEach { t -> listOf("·", ";", "—").forEach { assertFalse("$t has $it", it in t) } }
    }

    @Test fun `debug previews change only what is shown`() {
        val now = at(10)
        val real = StrapView(true, connected, SyncState.Done(now - 60), SyncRecord(lastSuccess = now - 60), now - 10)
        assertEquals(real, real.previewed(null, now))
        assertEquals(real, real.previewed(StrapPreview.RecoveryFail, now))
        val stale = real.previewed(StrapPreview.Stale, now)
        assertEquals("Not synced", strapChip(stale.paired, stale.connection, stale.sync, stale.lastSeen, now).text)
        assertFalse(syncStuck(true, true, stale.record.failingSince, now, zone))
        val stuck = real.previewed(StrapPreview.Stuck, now)
        assertTrue(syncStuck(true, true, stuck.record.failingSince, now, zone))
        val away = real.previewed(StrapPreview.Disconnected, now)
        assertEquals("18 min ago", strapChip(away.paired, away.connection, away.sync, away.lastSeen, now).text)
        val charging = real.previewed(StrapPreview.Charging, now)
        assertEquals("Charging 80%", strapChip(charging.paired, charging.connection, charging.sync, charging.lastSeen, now).text)
        val none = real.previewed(StrapPreview.NotPaired, now)
        assertEquals("Not paired", strapChip(none.paired, none.connection, none.sync, none.lastSeen, now).text)
        // A strap that isn't connected still previews a battery.
        val offline = real.copy(connection = ConnectionState.Disconnected).previewed(StrapPreview.Charging, now)
        assertEquals("Charging 64%", strapChip(offline.paired, offline.connection, offline.sync, offline.lastSeen, now).text)
    }

    @Test fun `previews are named by the extra`() {
        assertEquals(StrapPreview.RecoveryFail, StrapPreview.of("recoveryfail"))
        assertEquals(StrapPreview.NotPaired, StrapPreview.of("NotPaired"))
        assertNull(StrapPreview.of("auto"))
        assertEquals(0L, StrapPreview.Stale.staleAfter)
        assertEquals(STALE_AFTER_S, StrapPreview.Disconnected.staleAfter)
    }
}
