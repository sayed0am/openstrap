package org.openstrap.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Steps widget's throttle: at most one update every 15 minutes, and the last change never lost. */
class UpdateThrottleTest {
    private val every = 15 * 60_000L

    @Test fun `the first request runs now`() {
        assertEquals(0L, UpdateThrottle(every).request(1_000))
    }

    @Test fun `a request inside the gap waits for its end, and others join it`() {
        val t = UpdateThrottle(every)
        t.request(0)
        assertEquals(every - 60_000, t.request(60_000))
        assertNull(t.request(120_000))
        assertNull(t.request(every - 1))
        t.ran(every)
        // The waiting one ran: the next gap counts from it.
        assertEquals(every - 1, t.request(every + 1))
    }

    @Test fun `a request after the gap runs now`() {
        val t = UpdateThrottle(every)
        t.request(0)
        assertEquals(0L, t.request(every))
        assertEquals(0L, t.request(3 * every))
    }
}
