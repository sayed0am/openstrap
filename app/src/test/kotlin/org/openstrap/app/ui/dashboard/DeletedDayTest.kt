package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.db.SleepEdit
import org.openstrap.app.data.db.SleepSession
import java.time.LocalDate
import java.time.ZoneId

/** Deleted sleep names an entry by its times as shown (n-days follow-up 11), the user's when they moved it. */
class DeletedDayTest {
    private val zone = ZoneId.of("Asia/Kathmandu")
    private val day = LocalDate.of(2026, 1, 15)

    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toEpochSecond()

    @Test fun `a moved night is named by the evening before the day it was shown on`() {
        val strap = SleepSession(at(day.minusDays(1), 23), at(day, 7))
        // As the strap had it: the night of the 14th.
        assertEquals(day.minusDays(1), SleepCopy.deletedDay(SleepEdits.Resolved(strap, SleepEdit(strap.start, false, deleted = true)), zone))
        // Moved to end after midnight on the 16th, then deleted: it showed as the 16th's night, so the night of the 15th.
        val moved = SleepEdit(strap.start, false, start = at(day, 17), end = at(day.plusDays(1), 1), deleted = true)
        assertEquals(day, SleepCopy.deletedDay(SleepEdits.Resolved(strap, moved), zone))
    }

    @Test fun `a moved nap is named by the day it was shown on`() {
        val nap = SleepSession(at(day, 13), at(day, 14), nap = true)
        val moved = SleepEdit(nap.start, true, start = at(day.plusDays(1), 13), end = at(day.plusDays(1), 14), deleted = true)
        assertEquals(day.plusDays(1), SleepCopy.deletedDay(SleepEdits.Resolved(nap, moved), zone))
        assertEquals(day, SleepCopy.deletedDay(SleepEdits.Resolved(nap, null), zone))
    }
}
