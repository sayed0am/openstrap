package org.openstrap.app.widget

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widgets spec 5: each state, the order they win in, the 15% and 3 hour edges, and the words. */
class StrapStatusTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 10, 23).atTime(12, 0).atZone(zone).toEpochSecond()
    private val fresh = now - 600
    private val old = now - 4 * 3600

    @Test fun `all well shows nothing`() {
        assertNull(strapStatus(paired = true, battery = 80, charging = false, lastSync = fresh, now = now))
    }

    @Test fun `each state`() {
        assertEquals(StrapStatus.NotPaired, strapStatus(false, 80, false, fresh, now))
        assertEquals(StrapStatus.LowBattery(12), strapStatus(true, 12, false, fresh, now))
        assertEquals(StrapStatus.StaleSync(old), strapStatus(true, 80, false, old, now))
        assertEquals(StrapStatus.Syncing, strapStatus(true, 80, false, old, now, syncing = true))
    }

    @Test fun `not paired wins over a low battery, which wins over an old sync`() {
        assertEquals(StrapStatus.NotPaired, strapStatus(false, 5, false, old, now, syncing = true))
        assertEquals(StrapStatus.LowBattery(5), strapStatus(true, 5, false, old, now))
        assertEquals(StrapStatus.LowBattery(5), strapStatus(true, 5, false, old, now, syncing = true))
    }

    @Test fun `the battery line holds at 15 percent and under, not charging`() {
        assertEquals(StrapStatus.LowBattery(15), strapStatus(true, 15, false, fresh, now))
        assertNull(strapStatus(true, 16, false, fresh, now))
        assertNull(strapStatus(true, 15, true, fresh, now))
        assertNull(strapStatus(true, null, false, fresh, now))
    }

    @Test fun `the sync line holds past 3 hours, not at them`() {
        assertNull(strapStatus(true, 80, false, now - 3 * 3600, now))
        assertEquals(StrapStatus.StaleSync(now - 3 * 3600 - 1), strapStatus(true, 80, false, now - 3 * 3600 - 1, now))
        assertNull(strapStatus(true, 80, false, null, now))
    }

    @Test fun `a sync while all is well shows nothing`() {
        assertNull(strapStatus(true, 80, false, fresh, now, syncing = true))
    }

    @Test fun `the words`() {
        fun line(s: StrapStatus) = strapStatusLine(s, now, zone, Locale.UK)
        assertEquals("Pair your strap", line(StrapStatus.NotPaired))
        assertEquals("Strap at 12%", line(StrapStatus.LowBattery(12)))
        assertEquals("Syncing…", line(StrapStatus.Syncing))
        assertEquals("Synced 09:10", line(StrapStatus.StaleSync(now - 2 * 3600 - 50 * 60)))
        assertEquals("Synced yesterday", line(StrapStatus.StaleSync(now - 86_400)))
        assertEquals("Synced Tuesday", line(StrapStatus.StaleSync(now - 3 * 86_400)))
        assertEquals("Synced Tue 13 Oct", line(StrapStatus.StaleSync(now - 10 * 86_400)))
        // A week back would repeat today's weekday, so it takes the date.
        assertEquals("Synced Fri 16 Oct", line(StrapStatus.StaleSync(now - 7 * 86_400)))
    }

    @Test fun `the widgets hear of pairing, the low edge and charging, not every percent`() {
        assertFalse(strapStateChanged(true, 79, false, true, 80, false))
        assertFalse(strapStateChanged(true, 14, false, true, 15, false))
        assertTrue(strapStateChanged(true, 15, false, true, 16, false))
        assertTrue(strapStateChanged(true, 16, false, true, 15, false))
        assertTrue(strapStateChanged(true, 80, true, true, 80, false))
        assertTrue(strapStateChanged(false, 80, false, true, 80, false))
        assertTrue(strapStateChanged(true, 10, false, true, null, false))
    }
}
