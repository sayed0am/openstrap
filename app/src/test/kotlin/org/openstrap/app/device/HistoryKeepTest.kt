package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.protocol.history.ActivitySample
import org.openstrap.protocol.history.HistoryDataType
import org.openstrap.protocol.history.SpotHeartRate

/** Which synced records are stored, and how far the cursor may move, around the present minute. */
class HistoryKeepTest {
    /** 18:42:15 on a day in 2026, so the current minute is 18:42. */
    private val minute = 1_793_630_520L
    private val now = minute + 15

    private fun activity(at: Long, steps: Int) = ActivitySample(at, 0, 0, steps, 80, null)

    @Test fun `activity keeps only minutes that have ended`() {
        // On the strap: 18:41 done, 18:42 still counting, then stale slots for 18:43 and 18:44.
        val chunk = listOf(activity(minute - 60, 58), activity(minute, 49), activity(minute + 60, 12), activity(minute + 120, 0))

        assertEquals(listOf(minute - 60), HistorySync.keepable(HistoryDataType.ACTIVITY, chunk, now).map { it.timestamp })
    }

    @Test fun `the activity cursor stops at the current minute so it is fetched once it ends`() {
        assertEquals(minute, HistorySync.latestCursor(HistoryDataType.ACTIVITY, now))
    }

    @Test fun `other types still allow a strap clock a little ahead`() {
        val readings = listOf(SpotHeartRate(now + 60, 0, 70), SpotHeartRate(now + 600, 0, 70))

        assertEquals(listOf(now + 60), HistorySync.keepable(HistoryDataType.HEART_RATE_MANUAL, readings, now).map { it.timestamp })
        assertEquals(now, HistorySync.latestCursor(HistoryDataType.HEART_RATE_MANUAL, now))
    }

    @Test fun `blank recent activity slots leave the cursor so the minute is fetched once the strap writes it`() {
        // At 19:13:34 the strap sent 19:12 blank: it hadn't written that minute yet. The parser skips blank slots
        // and points past the whole chunk.
        val at = 1_793_632_414L
        val blankFrom1912 = 1_793_632_320L + 59 * 60

        assertEquals(null, HistorySync.nextCursor(HistoryDataType.ACTIVITY, blankFrom1912, emptyList(), at))
    }

    @Test fun `blank activity slots older than the wait are a real gap and the cursor moves past them`() {
        val gapEnds = now - 3600

        assertEquals(gapEnds, HistorySync.nextCursor(HistoryDataType.ACTIVITY, gapEnds, emptyList(), now))
    }

    @Test fun `after stored minutes the cursor sits right after the last one`() {
        val kept = listOf(activity(minute - 120, 5), activity(minute - 60, 7))

        assertEquals(minute, HistorySync.nextCursor(HistoryDataType.ACTIVITY, minute + 3600, kept, now))
    }

    @Test fun `records from before the strap's clock was set are dropped for every type`() {
        assertEquals(emptyList<ActivitySample>(), HistorySync.keepable(HistoryDataType.ACTIVITY, listOf(activity(60, 10)), now))
    }
}
