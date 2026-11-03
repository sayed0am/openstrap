package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Recovery.Minute
import org.openstrap.app.data.db.SleepStage

/**
 * A night trimmed at its start, as on the Fold on 3 Nov 2026: 11:05 PM to 8:16 AM cut to 3:00 AM to 8:16 AM, with
 * the cut hours calm and still. Energy fell from 53 to 49 while the day's Recharged rose from +30 to +36. This pins why
 * that is the model working as meant: Recharged counts from midnight, so the untrimmed night's first hour recharged
 * into the day's starting point instead, and sleep refills a share of what is missing ([Recovery.SLEEP_K]), so the
 * shorter night, starting lower, refills faster. Over the whole night the trimmed one still recharges less, and the
 * cut hours themselves add only Relax's slight recharge. Both nights get the same night resting heart rate, as the
 * engine takes it from the strap's own night ([ChargeEditedNightTest]).
 */
class ChargeTrimmedNightTest {
    // Minute 0 is 8:00 PM the evening before.
    private val bedtime = 185 // 11:05 PM
    private val midnight = 240
    private val trimmedStart = 420 // 3:00 AM
    private val wake = 736 // 8:16 AM
    private val end = 1500 // 9:00 PM

    private val params = Recovery.ChargeParams(restingHr = 66.5f, maxHr = 183f, nightRestingHr = 58f, stressFrom = 58)

    private fun day(sleepFrom: Int): List<Minute> = List(end) { i ->
        val t = i * 60L
        when {
            i in sleepFrom until wake -> Minute(t, 62, 0, 15, if ((i / 90) % 3 == 1) SleepStage.DEEP else SleepStage.LIGHT)
            // Lying still in bed before the (trimmed) sleep: low heart rate and stress, so Relax.
            i in bedtime until wake -> Minute(t, 62, 0, 15, null)
            else -> Minute(t, 80, 0, 40, null)
        }
    }

    private fun run(minutes: List<Minute>, from: Int, to: Int = end) =
        Recovery.charge(minutes, params, from * 60L until to * 60L, start = 52.5f, sampleSeconds = 60, requireAnchor = false)

    private fun Recovery.ChargeDay.at(minute: Int) = points.first { it.t == minute * 60L }.value

    @Test
    fun aNightTrimmedAtItsStartRechargesMoreAfterMidnightButLessOverall() {
        val whole = day(bedtime)
        val trimmed = day(trimmedStart)
        val wholeDay = run(whole, midnight)
        val trimmedDay = run(trimmed, midnight)

        // The day starts lower: the untrimmed night's first 55 minutes recharged before midnight.
        assertTrue("midnight ${wholeDay.at(midnight)} vs ${trimmedDay.at(midnight)}", wholeDay.at(midnight) - trimmedDay.at(midnight) > 5f)
        // And the day's Recharged is larger, the phone's +30 to +36, while what drained is about the same.
        assertTrue("recharged ${wholeDay.recharged} vs ${trimmedDay.recharged}", trimmedDay.recharged > wholeDay.recharged + 2f)
        val drained = { c: Recovery.ChargeDay -> c.drainedExercise + c.drainedStress + c.drainedLiving }
        assertEquals(drained(wholeDay), drained(trimmedDay), 2f)
        // Yet the trimmed night ends lower, and so does the day.
        assertTrue(trimmedDay.at(wake) < wholeDay.at(wake) - 3f)
        assertTrue(trimmedDay.latest!! < wholeDay.latest!!)

        // From bedtime, the whole night recharged more: the extra Recharged comes from where midnight falls.
        assertTrue(run(trimmed, bedtime, wake).recharged < run(whole, bedtime, wake).recharged - 3f)
        // The cut hours are Relax: a slight recharge ([Recovery.RELAX_K]), about 2 in 4 hours, where sleep added over 15.
        val cut = run(trimmed, bedtime, trimmedStart)
        assertTrue("cut hours ${cut.recharged}", cut.recharged < 2.5f)
        assertTrue(run(whole, bedtime, trimmedStart).recharged > 15f)
        assertTrue(cut.points.all { it.state == Recovery.State.Relax })
    }
}
