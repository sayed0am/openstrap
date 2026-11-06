package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.BodySignals.Flag
import org.openstrap.app.data.BodySignals.State
import org.openstrap.app.data.Recovery.Night

class BodySignalsTest {
    private val day = 86_400L

    /** An ordinary night: mean sleep HR 60, skin 33.5 °C, breathing 14 /min, each with a little night-to-night noise. */
    private fun night(i: Int, rhr: Float = 60f + (i % 3 - 1) * 0.5f, temp: Float = 33.5f + (i % 3 - 1) * 0.03f, rr: Float = 14f + (i % 3 - 1) * 0.1f, hrv: Float = 50f) =
        Night(
            day = i * day, start = i * day - 7 * 3600, end = i * day + 30 * 60, asleepMinutes = 450, hrv = hrv,
            restingHr = rhr - 4, rhrMeanSleep = rhr, skinTemperature = temp, breathRate = rr, hrvSamples = 20, hrMinutes = 420,
        )

    private val base = (1..30).map { night(it) }

    private fun run(vararg tail: Night) = BodySignals.evaluate(base + tail.toList())
    private fun List<BodySignals.Day>.at(i: Int) = first { it.day == i * day }

    @Test fun `no alerts before 14 valid nights`() {
        // Night 18's baseline is nights 1-12 (the 5 most recent are left out): too few.
        val days = BodySignals.evaluate((1..16).map { night(it) } + night(17, rhr = 75f) + night(18, rhr = 75f))
        assertEquals(State.Normal, days.last().state)
        assertTrue(days.last().flags.isEmpty())
        assertEquals(12, days.last().baselineNights)
    }

    @Test fun `one night of high resting HR is only a watch`() {
        val d = run(night(31, rhr = 66f)).last()
        assertEquals(setOf(Flag.RhrFlag, Flag.RhrWatch), d.flags)
        assertEquals(State.Watch, d.state)
        assertEquals(1, d.nightsOff)
        // Readiness is not capped by a watch.
        val r = Recovery.readiness(night(31, rhr = 66f), base)
        assertFalse(r.strainAlert)
    }

    @Test fun `resting HR up two nights in a row is an alert, and caps readiness`() {
        val days = run(night(31, rhr = 66f), night(32, rhr = 66f))
        assertEquals(State.Alert, days.last().state)
        assertEquals(2, days.last().nightsOff)
        assertEquals(listOf("resting heart rate"), days.last().families)
        val r = Recovery.readiness(night(32, rhr = 66f), base + night(31, rhr = 66f))
        assertTrue(r.strainAlert)
        assertTrue(r.score!! <= Recovery.ALERT_CAP)
    }

    @Test fun `one missing night is bridged, two reset persistence`() {
        assertEquals(State.Alert, run(night(31, rhr = 66f), night(33, rhr = 66f)).last().state)
        assertEquals(State.Watch, run(night(31, rhr = 66f), night(34, rhr = 66f)).last().state)
    }

    @Test fun `two signal families on one night is an alert`() {
        val d = run(night(31, rhr = 66f, temp = 34.2f)).last()
        assertTrue(Flag.TempFlag in d.flags)
        assertEquals(State.Alert, d.state)
        assertEquals(listOf("resting heart rate", "skin temperature"), d.families)
    }

    @Test fun `temperature needs half a degree`() {
        // +0.4 °C doesn't flag even on a very steady baseline: it's below the half-degree line.
        assertEquals(State.Normal, run(night(31, temp = 33.9f)).last().state)
        assertEquals(State.Watch, run(night(31, temp = 34.05f)).last().state)
    }

    @Test fun `any core flag on 3 of the last 7 nights is an alert`() {
        val days = run(night(31, temp = 34.1f), night(32), night(33, temp = 34.1f), night(34), night(35, temp = 34.1f))
        assertEquals(State.Watch, days.at(31).state)
        assertEquals(State.Watch, days.at(33).state)
        assertEquals(State.Alert, days.at(35).state)
    }

    @Test fun `breathing rate up 3 is an alert on its own, up 2 a watch`() {
        assertEquals(State.Alert, run(night(31, rr = 17.2f)).last().state)
        assertEquals(State.Watch, run(night(31, rr = 16.2f)).last().state)
        assertEquals(State.Normal, run(night(31, rr = 15.5f)).last().state)
    }

    @Test fun `an alert clears after two clean nights`() {
        val days = run(night(31, rhr = 66f), night(32, rhr = 66f), night(33), night(34), night(35))
        assertEquals(State.Alert, days.at(32).state)
        assertEquals(State.Alert, days.at(33).state)
        assertEquals(State.Normal, days.at(34).state)
        assertEquals(State.Normal, days.at(35).state)
    }

    @Test fun `low HRV supports a small resting HR rise but never votes alone`() {
        assertEquals(State.Normal, run(night(31, hrv = 25f)).last().state)
        val d = run(night(31, rhr = 63.2f, hrv = 25f)).last()
        assertEquals(setOf(Flag.RhrWatch, Flag.HrvSupport), d.flags)
        assertEquals(State.Watch, d.state)
    }

    @Test fun `flagged nights stay out of the baseline`() {
        // A run of high nights: each stays flagged instead of becoming the new normal.
        val days = run(*(31..36).map { night(it, temp = 34.1f) }.toTypedArray())
        assertTrue(days.filter { it.day >= 31 * day }.all { Flag.TempFlag in it.flags })
        // And Readiness's baselines skip them too: a 7th high night still reads as off.
        val r = Recovery.readiness(night(37, temp = 34.1f), base + (31..36).map { night(it, temp = 34.1f) })
        assertTrue(r.contributors.first { it.factor == Recovery.Factor.Temperature }.score < 60)
    }

    @Test fun `likely causes are annotated, not suppressed`() {
        val days = BodySignals.evaluate(base + night(31, rhr = 66f) + night(32, rhr = 66f)) { if (it == 32 * day) listOf("alcohol") else emptyList() }
        assertEquals(State.Alert, days.last().state)
        assertEquals(listOf("alcohol"), days.last().causes)
    }

    @Test fun `spo2 no longer raises an alert`() {
        val d = run(night(31).copy(spo2 = 88f)).last()
        assertEquals(State.Normal, d.state)
    }
}
