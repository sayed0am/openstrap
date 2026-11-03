package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.device.LeftRecording.Verdict

/** Where a workout left recording while the app was killed ends, by what the phone and the strap saw (decisions 2026-11-03). */
class LeftRecordingTest {
    private val t0 = 1_800_000_000L / 60 * 60
    /** Killed ten minutes in, back five hours later. */
    private val left = LeftRecording.Left(t0, t0 + 600, t0 + 5 * 3600)

    /** The phone's readings each second until the kill, at 120. */
    private val phone = (1L..600L).map { AppWorkoutHeartRate(t0, t0 + it, 120) }

    /** The strap's minutes from [from] until [to] at [bpm]. */
    private fun minutes(from: Long, to: Long, bpm: Int) = (from until to step 60).map { AppWorkoutHeartRate(t0, it, bpm, fromHistory = true) }

    private fun steps(from: Long, to: Long, n: Int) = (from until to step 60).associateWith { n }

    private fun seen(hr: List<AppWorkoutHeartRate>, steps: Map<Long, Int> = emptyMap(), historyTo: Long? = left.reopenedAt, twinEnd: Long? = null) =
        LeftRecording.Seen(phone + hr, hr, steps, historyTo, twinEnd, restingHr = 60, maxHr = 190)

    @Test fun `it waits until the strap's history reaches the reopen`() {
        val partial = seen(minutes(left.leftAt, t0 + 3600, 130), historyTo = t0 + 3600)
        assertEquals(Verdict.Wait, LeftRecording.verdict(left, partial, final = false))
        // Out of time, what came decides: still active where the history ends, so it ends there.
        assertEquals(t0 + 3600 - 1, (LeftRecording.verdict(left, partial, final = true) as Verdict.End).at)
    }

    @Test fun `history a few minutes short of the reopen is enough`() {
        val hr = minutes(left.leftAt, left.reopenedAt - 600, 130)
        assertEquals(Verdict.CarryOn, LeftRecording.verdict(left, seen(hr, historyTo = left.reopenedAt - 600), final = false))
    }

    @Test fun `it ends where activity stopped, by heart rate or by steps`() {
        val byHr = minutes(left.leftAt, t0 + 3 * 3600, 130) + minutes(t0 + 3 * 3600, left.reopenedAt, 65)
        assertEquals(t0 + 3 * 3600 - 1, (LeftRecording.verdict(left, seen(byHr), final = false) as Verdict.End).at)
        // A calm heart rate, but walking for another half hour.
        val walking = steps(t0 + 3 * 3600, t0 + 3 * 3600 + 1800, 90)
        assertEquals(t0 + 3 * 3600 + 1800 - 1, (LeftRecording.verdict(left, seen(byHr, walking), final = false) as Verdict.End).at)
    }

    @Test fun `active until the reopen it records on`() {
        val hr = minutes(left.leftAt, left.reopenedAt - 300, 130) + minutes(left.reopenedAt - 300, left.reopenedAt, 70)
        assertEquals(Verdict.CarryOn, LeftRecording.verdict(left, seen(hr), final = false))
    }

    @Test fun `the strap's own record of it decides its end`() {
        val hr = minutes(left.leftAt, left.reopenedAt, 130)
        assertEquals(Verdict.End(t0 + 7200, "the strap's own workout ended"), LeftRecording.verdict(left, seen(hr, twinEnd = t0 + 7200), final = false))
        // One that ended before the phone's last reading says nothing of the time after.
        assertEquals(Verdict.CarryOn, LeftRecording.verdict(left, seen(hr, twinEnd = t0 + 300), final = false))
    }

    @Test fun `two hours with no heart rate is a strap taken off`() {
        val hr = minutes(left.leftAt, t0 + 3600, 130)
        assertEquals(t0 + 3600 - 1, (LeftRecording.verdict(left, seen(hr), final = false) as Verdict.End).at)
        assertEquals(left.leftAt, (LeftRecording.verdict(left, seen(emptyList()), final = false) as Verdict.End).at)
    }

    @Test fun `with nothing from the strap it ends at the phone's last reading`() {
        assertEquals(Verdict.Wait, LeftRecording.verdict(left, seen(emptyList(), historyTo = null), final = false))
        assertEquals(left.leftAt, (LeftRecording.verdict(left, seen(emptyList(), historyTo = null), final = true) as Verdict.End).at)
    }

    @Test fun `a workout that was never active ends at the phone's last reading`() {
        val calm = LeftRecording.Seen(
            phone.map { it.copy(bpm = 70) } + minutes(left.leftAt, left.reopenedAt, 70), emptyList(), emptyMap(), left.reopenedAt, null, 60, 190,
        )
        assertEquals(left.leftAt, (LeftRecording.verdict(left, calm, final = false) as Verdict.End).at)
    }
}
