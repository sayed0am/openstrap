package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.protocol.history.ActivitySample
import org.openstrap.protocol.history.HistoryDataType
import org.openstrap.protocol.history.SpotHeartRate
import org.openstrap.protocol.history.TodayActivityExtended

/** Which synced records are stored, and how far the cursor may move, around the present minute. */
class HistoryKeepTest {
    /** 18:42:15 on a day in 2026, so the current minute is 18:42. */
    private val minute = 1_793_630_520L
    private val now = minute + 15

    private fun activity(at: Long, steps: Int) = ActivitySample(at, 0, 0, steps, 80, null)

    private fun extended(at: Long, steps: Int) = TodayActivityExtended(at, 1, 0, 0, 0, 0, 0, 0, 0, steps, 0, 0)

    /** An extended activity slot the strap hasn't written: every byte 0xFF. */
    private fun blankExtended(at: Long) = TodayActivityExtended(at, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFFFF)

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

        val header1912 = 1_793_632_320L

        assertEquals(header1912, HistorySync.nextCursor(HistoryDataType.ACTIVITY, header1912, emptyList(), blankFrom1912, emptyList(), at))
    }

    @Test fun `blank activity slots older than the wait are a real gap and the cursor moves past them`() {
        val gapEnds = now - 3600

        assertEquals(gapEnds, HistorySync.nextCursor(HistoryDataType.ACTIVITY, gapEnds - 600, emptyList(), gapEnds, emptyList(), now))
    }

    @Test fun `after stored minutes the cursor sits right after the last one`() {
        val kept = listOf(activity(minute - 120, 5), activity(minute - 60, 7))

        assertEquals(minute, HistorySync.nextCursor(HistoryDataType.ACTIVITY, minute - 120, kept, minute + 3600, kept, now))
    }

    @Test fun `records from before the strap's clock was set are dropped for every type`() {
        assertEquals(emptyList<ActivitySample>(), HistorySync.keepable(HistoryDataType.ACTIVITY, listOf(activity(60, 10)), now))
    }

    @Test fun `recent blank minutes before written ones hold the cursor at the first blank`() {
        // The strap sent 18:38 and 18:39 blank (not written yet), then 18:40 and 18:41. The parser skips the blanks,
        // so the chunk's records start two minutes after its header. Moving past 18:41 lost 18:38 and 18:39 for good.
        val header = minute - 240
        val written = listOf(activity(minute - 120, 30), activity(minute - 60, 40))

        assertEquals(header, HistorySync.nextCursor(HistoryDataType.ACTIVITY, header, written, minute, written, now))
    }

    @Test fun `old blank minutes before written ones are a real gap`() {
        val header = minute - 3600
        val written = listOf(activity(minute - 3000, 30), activity(minute - 2940, 40))

        assertEquals(minute - 2880, HistorySync.nextCursor(HistoryDataType.ACTIVITY, header, written, minute - 2880, written, now))
    }

    @Test fun `a blank run reaching into the wait holds the cursor at its first recent minute`() {
        // Blank from 18:22, written from 18:40, at 18:42:15: 18:22 to 18:32 are more than 10 minutes old and real
        // gaps, 18:33 on may still be written.
        val header = minute - 20 * 60
        val written = listOf(activity(minute - 120, 30))

        assertEquals(minute - 540, HistorySync.nextCursor(HistoryDataType.ACTIVITY, header, written, minute - 60, written, now))
    }

    @Test fun `extended activity drops blank slots and the minute in progress`() {
        val chunk = listOf(extended(minute - 120, 5), blankExtended(minute - 60), extended(minute, 9))

        assertEquals(listOf(minute - 120), HistorySync.keepable(HistoryDataType.TODAY_ACTIVITY_EXTENDED, chunk, now).map { it.timestamp })
    }

    @Test fun `a recent blank extended minute holds the cursor so it is fetched once written`() {
        val chunk = listOf(extended(minute - 180, 5), blankExtended(minute - 120), extended(minute - 60, 9))
        val kept = HistorySync.keepable(HistoryDataType.TODAY_ACTIVITY_EXTENDED, chunk, now)

        assertEquals(minute - 120, HistorySync.nextCursor(HistoryDataType.TODAY_ACTIVITY_EXTENDED, minute - 180, chunk, minute, kept, now))
    }

    @Test fun `the extended activity cursor stops at the minute in progress`() {
        val chunk = listOf(extended(minute - 60, 5), extended(minute, 9))
        val kept = HistorySync.keepable(HistoryDataType.TODAY_ACTIVITY_EXTENDED, chunk, now)

        assertEquals(minute, HistorySync.nextCursor(HistoryDataType.TODAY_ACTIVITY_EXTENDED, minute - 60, chunk, minute + 60, kept, now))
    }
}
