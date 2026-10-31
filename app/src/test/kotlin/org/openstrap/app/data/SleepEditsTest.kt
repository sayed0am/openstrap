package org.openstrap.app.data

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.db.SleepEdit
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage

class SleepEditsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(h: Int, m: Int, day: Int = 30) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toEpochSecond()
    private val nap = SleepSession(at(14, 10), at(14, 48), nap = true)
    private val main = SleepSession(at(23, 20, day = 29), at(7, 21), score = 81, deepMinutes = 90, lightMinutes = 240, remMinutes = 100, awakeMinutes = 30)
    private val now = at(18, 0)

    private fun moved(s: SleepSession, start: Long, end: Long) = SleepEdit(s.start, s.nap, start, end, editedAt = now)
    private fun deleted(s: SleepSession) = SleepEdit(s.start, s.nap, deleted = true, editedAt = now)

    @Test fun `a moved nap takes the user's times, anything else stays`() {
        val out = SleepEdits.apply(listOf(main, nap), listOf(moved(nap, at(14, 2), at(14, 55))))
        assertSame(main, out[0])
        assertEquals(at(14, 2), out[1].start)
        assertEquals(at(14, 55), out[1].end)
    }

    @Test fun `a moved main sleep takes the user's times`() {
        val out = SleepEdits.apply(listOf(main, nap), listOf(moved(main, at(23, 55, day = 29), at(7, 21))))
        assertEquals(at(23, 55, day = 29), out[0].start)
        assertSame(nap, out[1])
        // The strap's totals are for its own span, so they go until the stages are clipped.
        assertNull(out[0].deepMinutes)
    }

    @Test fun `a deleted session is left out, and restoring it brings back exactly what was there`() {
        val gone = deleted(main)
        assertEquals(listOf(nap), SleepEdits.apply(listOf(main, nap), listOf(gone)))
        assertNull(SleepEdits.restore(gone, now))
        assertEquals(listOf(main, nap), SleepEdits.apply(listOf(main, nap), emptyList()))
    }

    @Test fun `moved then deleted, then restored, keeps the user's times`() {
        val m = moved(nap, at(14, 2), at(14, 55))
        val d = SleepEdits.delete(nap, m, now + 60)
        assertTrue(d.deleted)
        assertEquals(at(14, 2), d.start)
        assertEquals(emptyList<SleepSession>(), SleepEdits.apply(listOf(nap), listOf(d)))
        val r = SleepEdits.restore(d, now + 120)!!
        assertFalse(r.deleted)
        assertEquals(at(14, 2) to at(14, 55), SleepEdits.apply(listOf(nap), listOf(r)).single().let { it.start to it.end })
    }

    @Test fun `an edit never crosses kinds`() {
        // A main sleep starting where a nap's edit is keyed is never moved or removed by it.
        val other = SleepSession(nap.start, at(20, 0))
        assertSame(other, SleepEdits.apply(listOf(other), listOf(deleted(nap))).single())
        val nearby = SleepSession(nap.start + 300, at(20, 0))
        assertSame(nearby, SleepEdits.apply(listOf(nearby), listOf(deleted(nap))).single())
    }

    @Test fun `a resync that moves the strap's start a little keeps the edit, a bigger move doesn't`() {
        val e = moved(nap, at(14, 2), at(14, 55))
        val shifted = nap.copy(start = nap.start + 4 * 60)
        assertEquals(at(14, 2), SleepEdits.apply(listOf(shifted), listOf(e)).single().start)
        val far = nap.copy(start = nap.start + 16 * 60)
        assertSame(far, SleepEdits.apply(listOf(far), listOf(e)).single())
        // The exact start wins over a nearer fuzzy one, and an edit goes to one session only.
        val exact = SleepEdits.resolve(listOf(shifted, nap), listOf(e))
        assertNull(exact[0].edit)
        assertEquals(e, exact[1].edit)
    }

    @Test fun `stages are trimmed to the session's times, and a moved session's totals come from them`() {
        val stages = listOf(
            SleepStage(at(14, 10), at(14, 20), nap.start, SleepStage.LIGHT),
            SleepStage(at(14, 20), at(14, 40), nap.start, SleepStage.DEEP),
            SleepStage(at(14, 40), at(14, 48), nap.start, SleepStage.LIGHT),
        )
        val out = SleepEdits.clip(stages, at(14, 15), at(14, 40))
        assertEquals(listOf(at(14, 15) to at(14, 20), at(14, 20) to at(14, 40)), out.map { it.start to it.end })
        val shown = SleepEdits.moved(nap, at(14, 15), at(14, 40))
        val restaged = SleepEdits.restage(shown, nap, stages)
        assertEquals(20, restaged.deepMinutes)
        assertEquals(5, restaged.lightMinutes)
    }

    @Test fun `a move under 15 minutes in all keeps the strap's score, a bigger one works it out again`() {
        assertEquals(81, SleepEdits.moved(main, main.start + 5 * 60, main.end - 9 * 60).score)
        val big = SleepEdits.moved(main, main.start + 35 * 60, main.end)
        assertNotNull(big.score)
        assertTrue(big.score!! < 81)
        // A nap has no score of its own.
        assertNull(SleepEdits.moved(nap.copy(score = 50), nap.start - 30 * 60, nap.end).score)
    }

    @Test fun `35 minutes cut from the start never raises the strap's score`() {
        // Last night as the strap scored it, 83, then the start moved from 23:20 to 23:55.
        val night = SleepSession(at(23, 20, day = 29), at(7, 21), score = 83, deepMinutes = 95, lightMinutes = 296, remMinutes = 60, awakeMinutes = 30)
        fun stage(h1: Int, m1: Int, h2: Int, m2: Int, st: Int) =
            SleepStage(at(h1, m1, day = if (h1 >= 12) 29 else 30), at(h2, m2, day = if (h2 >= 12) 29 else 30), night.start, st)
        val stages = listOf(
            stage(23, 20, 23, 30, SleepStage.AWAKE), stage(23, 30, 23, 55, SleepStage.LIGHT),
            stage(23, 55, 1, 30, SleepStage.DEEP), stage(1, 30, 1, 40, SleepStage.AWAKE),
            stage(1, 40, 4, 0, SleepStage.LIGHT), stage(4, 0, 5, 0, SleepStage.REM),
            stage(5, 0, 5, 20, SleepStage.AWAKE), stage(5, 20, 7, 21, SleepStage.LIGHT),
        )
        val start = at(23, 55, day = 29)
        val shown = SleepEdits.restage(SleepEdits.moved(night, start, night.end), night, stages)
        assertTrue("scored ${shown.score}", shown.score!! <= 83)
        assertTrue("scored ${SleepEdits.moved(night, start, night.end).score}", SleepEdits.moved(night, start, night.end).score!! <= 83)
        // Moving it back to the strap's own times gives the strap's score again.
        assertEquals(83, SleepEdits.restage(SleepEdits.moved(night, night.start, night.end), night, stages).score)
    }

    @Test fun `a big move shifts the strap's score by the change in OpenStrap's own, never past the direction of the move`() {
        assertEquals(70, SleepEdits.anchored(83, 72, 85, lengthChange = -2100))
        // Shorter but the clipped stages read better: held at the strap's.
        assertEquals(83, SleepEdits.anchored(83, 90, 85, lengthChange = -2100))
        // Longer but reading worse: held at the strap's too.
        assertEquals(83, SleepEdits.anchored(83, 80, 85, lengthChange = 1800))
        assertEquals(100, SleepEdits.anchored(95, 100, 80, lengthChange = 1800))
        assertEquals(0, SleepEdits.anchored(10, 20, 60, lengthChange = -3600))
    }

    @Test fun `times that can't be saved are refused, by kind`() {
        assertNull(SleepEdits.problem(nap, at(14, 0), at(15, 0), now))
        assertEquals("Waking up has to come after falling asleep.", SleepEdits.problem(nap, at(14, 30), at(14, 20), now))
        assertEquals("A nap needs at least 5 minutes.", SleepEdits.problem(nap, at(14, 30), at(14, 33), now))
        assertEquals("A nap can be 4 hours at most.", SleepEdits.problem(nap, at(11, 0), at(15, 30), now))
        assertEquals("That's later than now.", SleepEdits.problem(nap, at(16, 0), at(18, 30), now))
        assertEquals("Keep it within 3 hours of what the strap saw.", SleepEdits.problem(nap, at(10, 30), at(11, 0), now))
        assertNull(SleepEdits.problem(main, at(23, 55, day = 29), at(7, 21), now))
        assertEquals("Sleep needs at least an hour.", SleepEdits.problem(main, at(6, 30), at(7, 21), now))
        assertEquals("Sleep can be 16 hours at most.", SleepEdits.problem(main.copy(end = at(10, 0)), at(20, 50, day = 29), at(12, 55), at(20, 0)))
    }

    @Test fun `times that run into another sleep or nap are refused`() {
        val earlier = SleepSession(at(13, 0), at(14, 5), nap = true)
        assertEquals("That runs into your nap at 13:00.", SleepEdits.problem(nap, at(14, 0), at(14, 48), now, listOf(earlier, main), zone))
        val evening = SleepSession(at(22, 0, day = 29), at(23, 0, day = 29), nap = true)
        assertEquals("That runs into your nap at 22:00.", SleepEdits.problem(main, at(22, 50, day = 29), at(7, 21), now, listOf(evening), zone))
        assertEquals("That runs into your sleep at 23:20.", SleepEdits.problem(evening, at(22, 0, day = 29), at(23, 30, day = 29), now, listOf(main), zone))
        // Touching isn't running into.
        assertNull(SleepEdits.problem(nap, at(14, 5), at(14, 48), now, listOf(earlier), zone))
    }

    @Test fun `the strap's own times again drop the edit, unless it's deleted`() {
        assertNull(SleepEdits.edit(nap, nap.start, nap.end, null, now))
        assertNull(SleepEdits.edit(nap, nap.start, nap.end, moved(nap, at(14, 5), nap.end), now))
        assertEquals(SleepEdit(nap.start, true, at(14, 5), nap.end, editedAt = now), SleepEdits.edit(nap, at(14, 5), nap.end, null, now))
        val d = SleepEdits.edit(nap, nap.start, nap.end, deleted(nap), now)!!
        assertTrue(d.deleted)
        assertFalse(d.moved)
        // A fuzzy-matched edit keeps its own key.
        val old = moved(nap, at(14, 2), at(14, 55)).copy(strapStart = nap.start - 120)
        assertEquals(nap.start - 120, SleepEdits.edit(nap, at(14, 0), at(14, 55), old, now)!!.strapStart)
    }

    @Test fun `a delete is kept 7 days, then leaves a marker`() {
        val d = deleted(main).copy(start = at(23, 55, day = 29), end = at(7, 21))
        assertFalse(SleepEdits.expired(d, now + 6 * 86_400L))
        assertTrue(SleepEdits.expired(d, now + 7 * 86_400L))
        assertEquals(7, SleepEdits.daysLeft(d, now + 60))
        assertEquals(6, SleepEdits.daysLeft(d, now + 86_400L + 60))
        assertEquals(1, SleepEdits.daysLeft(d, now + 7 * 86_400L - 60))
        val m = SleepEdits.marker(d)
        assertTrue(m.deleted)
        assertNull(m.start)
        // The marker still drops a resynced session, even one a few minutes off.
        assertEquals(emptyList<SleepSession>(), SleepEdits.apply(listOf(main.copy(start = main.start + 180)), listOf(m)))
        assertFalse(SleepEdits.expired(moved(main, 1, 2), now + 30 * 86_400L))
    }

    @Test fun `a nap whose heart rate stayed as in the hour before, with no deep sleep, may not be a nap`() {
        fun readings(before: Float, during: Float) =
            (0 until 12).map { nap.start - 3600 + it * 300L to before } + (0 until 7).map { nap.start + it * 300L to during }
        assertEquals(72, SleepEdits.stillHeartRate(readings(74f, 72f), nap.start, nap.end, deepSeconds = 0))
        // A real drop into sleep, or any deep sleep, isn't flagged.
        assertNull(SleepEdits.stillHeartRate(readings(74f, 62f), nap.start, nap.end, deepSeconds = 0))
        assertNull(SleepEdits.stillHeartRate(readings(74f, 72f), nap.start, nap.end, deepSeconds = 600))
        // Nothing to compare with says nothing.
        assertNull(SleepEdits.stillHeartRate(readings(74f, 72f).filter { it.first >= nap.start }, nap.start, nap.end, deepSeconds = 0))
    }

    @Test fun `a picked clock is read on the day nearest the session`() {
        assertEquals(at(14, 5), SleepEdits.atNear(14 * 60 + 5, nap.start, zone))
        // A late nap running past midnight: 00:20 is the next day's.
        assertEquals(at(0, 20, day = 31), SleepEdits.atNear(20, at(23, 50), zone))
        // And 23:40 picked for a nap the strap started at 00:10 is the evening before.
        assertEquals(at(23, 40, day = 29), SleepEdits.atNear(23 * 60 + 40, at(0, 10), zone))
    }
}
