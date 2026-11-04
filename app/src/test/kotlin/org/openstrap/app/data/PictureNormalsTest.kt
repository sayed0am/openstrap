package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.Recovery.Minute
import org.openstrap.app.data.Recovery.Night
import org.openstrap.app.data.db.SleepStage

/** What the picture cards read from the model that it didn't hand out before: Charge at bedtime, disturbances' band. */
class PictureNormalsTest {
    private val day = 86_400L

    private fun night(i: Int, odi: Float?) =
        Night(day = i * day, start = i * day - 7 * 3600, end = i * day + 30 * 60, asleepMinutes = 450, odi = odi)

    @Test fun `breathing disturbances' band is the median of the nights before, give or take their spread`() {
        val odi = listOf(1f, 2f, 3f, 2f, 1f, 3f, 2f, 9f)
        val nights = odi.mapIndexed { i, v -> night(i + 1, v) } + night(9, 40f) + night(10, null)
        val band = requireNotNull(Recovery.disturbancesUsual(nights, 9 * day))
        // Tonight's 40 and nights without a value are left out.
        assertEquals(8, band.days)
        assertEquals(2f, band.mean, 0.001f)
        val mean = odi.average()
        val sd = kotlin.math.sqrt(odi.sumOf { (it - mean) * (it - mean) } / (odi.size - 1)).toFloat()
        assertEquals(2f + sd, band.high, 0.001f)
        assertEquals(0f, band.low, 0.001f) // Never under none.
        // The same usual the Breathing card's median gives.
        assertEquals(Recovery.breathingDisturbances(nights[8], nights)!!.usual!!, band.mean, 0.001f)
        // Under seven nights, no band.
        assertNull(Recovery.disturbancesUsual(nights.take(6), 9 * day))
    }

    @Test fun `Charge at bedtime is where Recharged starts`() {
        val params = Recovery.ChargeParams(restingHr = 58f, maxHr = 188f, nightRestingHr = 52f)
        val awake = List(120) { Minute(it * 60L, 64, 0, 30, null) }
        val asleep = List(300) { Minute((120 + it) * 60L, 52, 0, null, SleepStage.LIGHT) }
        val r = Recovery.charge(awake + asleep, params, 0L..420 * 60L, start = 80f, bedtime = 120 * 60L)
        val expected = 80f * Math.pow(1.0 - Recovery.REST_DRAIN, 120.0).toFloat()
        assertEquals(expected, r.atBedtime!!, 0.05f)
        // The night then fills it.
        org.junit.Assert.assertTrue(r.latest!! > r.atBedtime!! + 10f)
        // Without a bedtime there's none.
        assertNull(Recovery.charge(awake + asleep, params, 0L..420 * 60L, start = 80f).atBedtime)
    }
}
