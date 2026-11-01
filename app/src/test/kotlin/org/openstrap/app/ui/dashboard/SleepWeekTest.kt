package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import java.time.LocalDate
import java.time.ZoneOffset

class SleepWeekTest {
    private val zone = ZoneOffset.UTC
    private val date = LocalDate.of(2026, 10, 17)

    /** Seven nights, 23:30 to 07:00. */
    private val state = DayUiState(
        date = date,
        nights = (6L downTo 0L).map { back ->
            val midnight = date.minusDays(back).atStartOfDay(zone).toEpochSecond()
            SleepNight(date.minusDays(back), SleepSession(start = midnight - 30 * 60, end = midnight + 7 * 3600))
        },
    )

    @Test fun `regularity shown for the week is the SRI`() {
        // A regular week on the SRI grid, the same as RecoveryEngine builds it.
        val grids = (0..7).map { k ->
            val noon = k * 86_400L
            SleepRegularity.grid(noon, listOf(Triple(noon + 690 * 60L, noon + 1140 * 60L, SleepStage.LIGHT))) { true }
        }
        val result = SleepRegularity.result(grids, emptyList())
        val w = sleepWeek(state, 8 * 3600L, result, zone)!!
        assertEquals(result.sri, w.sri)
        assertEquals(100f, w.sri!!, 1e-4f)
        assertEquals(SleepRegularity.Band.VeryRegular, w.band)
        // The method is in the info sheet now, with how much wear the week compares.
        assertTrue(InfoCopy.regularityNotes(result).single().startsWith("This week compares"))
        // Usual times still come from the nights.
        assertEquals("23:30", minutesClock(w.usual!!.bedtime))
        assertEquals("07:00", minutesClock(w.usual.wake))
    }

    @Test fun `withheld SRI shows no score and says why`() {
        val w = sleepWeek(state, 8 * 3600L, SleepRegularity.Result(null, 3360, 12f), zone)!!
        assertNull(w.sri)
        assertNull(w.band)
        assertTrue(InfoCopy.regularityNotes(w.regularity).first().contains("56 h of the 120 h it needs"))
    }

    @Test fun `while the SRI loads there is neither score nor note`() {
        val w = sleepWeek(state, 8 * 3600L, null, zone)!!
        assertNull(w.sri)
        assertEquals(emptyList<String>(), InfoCopy.regularityNotes(w.regularity))
    }
}
