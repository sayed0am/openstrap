package org.openstrap.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import java.time.LocalDate

/** The morning notification's Recovery number (data integrity item 23.3): what Today shows, the strap's too. */
class NotificationRecoveryTest {
    private fun result(score: Int?, strapScore: Int?, baselineNights: Int = 30) = RecoveryResult(
        LocalDate.of(2026, 1, 15), Recovery.Readiness(score, emptyList(), baselineNights, false, emptyList(), false),
        Recovery.ChargeDay(emptyList(), null, null, 0f, 0f, 0f, 0f, null, null, anchored = true), emptyList(),
        0f, null, 52f, 190f, true, Recovery.StressThreshold(60, null, 0), strapScore = strapScore,
    )

    @Test fun `the strap's estimate shows while OpenStrap's own score is withheld, as on Today`() {
        // Three nights in: no baseline yet, so ours is withheld and the strap's stands in.
        val learning = result(null, 84, baselineNights = 3)
        assertEquals(84, NotificationCopy.recovery(learning))
        assertEquals(TodayCopy.recoveryShown(learning.readiness, learning.strapScore, "").value, NotificationCopy.recovery(learning))
        // Our own score wins whenever there is one.
        assertEquals(70, NotificationCopy.recovery(result(70, 84)))
        // Neither: no number.
        assertNull(NotificationCopy.recovery(result(null, null, baselineNights = 3)))
    }
}
