package org.openstrap.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HeartZonesTest {
    @After fun standardAgain() = HeartZones.use(HeartZones.STANDARD)

    @Test fun `the standard zones are the tens of percent the app always used`() {
        val z = HeartZones.STANDARD
        assertNull(z.zoneOf(94, 190))
        assertEquals(0, z.zoneOf(95, 190))
        assertEquals(0, z.zoneOf(113, 190))
        assertEquals(1, z.zoneOf(114, 190))
        assertEquals(4, z.zoneOf(171, 190))
        assertEquals(4, z.zoneOf(220, 190))
        assertEquals(0.5f, z.position(142, 190), 0.01f)
        assertEquals(0f, z.position(80, 190), 0f)
        assertEquals(1f, z.position(200, 190), 0f)
    }

    @Test fun `each zone's bpm is the first heart rate that counts in it`() {
        val z = HeartZones.STANDARD
        val starts = (0 until 5).map { z.startBpm(it, 192) }
        assertEquals(listOf(96, 116, 135, 154, 173), starts)
        starts.forEachIndexed { i, b -> assertEquals(i, z.zoneOf(b, 192)); if (i > 0) assertEquals(i - 1, z.zoneOf(b - 1, 192)) }
        assertEquals(115, z.endBpm(0, 192))
        assertNull(z.endBpm(4, 192))
    }

    @Test fun `moving an edge keeps the bpm you chose, and its neighbours apart`() {
        val z = HeartZones.STANDARD.withStart(1, 115, 192)
        assertEquals(115, z.startBpm(1, 192))
        assertEquals(114, z.endBpm(0, 192))
        assertEquals(1, z.zoneOf(115, 192))
        // Not past the zone above, less its width.
        assertEquals(135 - HeartZones.MIN_WIDTH_BPM, HeartZones.STANDARD.withStart(1, 170, 192).startBpm(1, 192))
        assertEquals(191, HeartZones.STANDARD.withStart(4, 250, 192).startBpm(4, 192))
        assertEquals(58, HeartZones.STANDARD.withStart(0, 10, 192).startBpm(0, 192))
        // Ending zone 2 lower starts zone 3 there.
        assertEquals(130, HeartZones.STANDARD.withEnd(1, 129, 192).startBpm(2, 192))
        assertEquals(HeartZones.STANDARD, HeartZones.STANDARD.withEnd(4, 180, 192))
    }

    @Test fun `zones follow max heart rate, as percents of it`() {
        val z = HeartZones.STANDARD.withStart(2, 140, 200)
        assertEquals(70f, z.starts[2], 0.01f)
        assertEquals(133, z.startBpm(2, 190))
    }

    @Test fun `zones read back as they were stored, and nonsense reads as nothing`() {
        val z = HeartZones.STANDARD.withStart(1, 115, 192).withStart(3, 150, 192)
        assertEquals(z, HeartZones.parse(z.encode()))
        assertEquals(115, HeartZones.parse(z.encode())!!.startBpm(1, 192))
        assertNull(HeartZones.parse(null))
        assertNull(HeartZones.parse("50,60,70,80"))
        assertNull(HeartZones.parse("50,70,60,80,90"))
        assertNull(HeartZones.parse("50,60,70,80,100"))
        assertNull(HeartZones.parse("10,60,70,80,90"))
        assertNull(HeartZones.parse("50,60,x,80,90"))
    }

    @Test fun `every zone reader follows the zones in use`() {
        HeartZones.use(HeartZones.STANDARD.withStart(1, 100, 190))
        assertEquals(1, Workouts.zoneOf(100, 190))
        assertEquals(1, WorkoutRecorder.liveZone(100, 190))
        assertTrue(WorkoutRecorder.zonePosition(100, 190) >= 0.2f)
        val seconds = WorkoutRecorder.liveZoneSeconds(listOf(0L to 100, 5L to 100), 190)
        assertEquals(5, seconds[1])
    }

    @Test fun `max heart rate says where it comes from`() {
        assertEquals(MaxHeartRate.Source.Set, Recovery.maxHeartRateSource(30, tested = 185))
        assertEquals(MaxHeartRate.Source.Learned, Recovery.maxHeartRateSource(30, observedPeak = 195))
        assertEquals(MaxHeartRate.Source.Age, Recovery.maxHeartRateSource(30, observedPeak = 180))
        assertEquals(MaxHeartRate.Source.Strap, Recovery.maxHeartRateSource(null, strapMax = 192))
        assertEquals(MaxHeartRate.Source.Guess, Recovery.maxHeartRateSource(null))
        // A tested value out of range isn't yours.
        assertEquals(MaxHeartRate.Source.Age, Recovery.maxHeartRateSource(30, tested = 300))
    }

    @Test fun `the day max heart rate was learned is the latest day at the peak`() {
        val d = LocalDate.of(2026, 10, 1)
        val maxima = mapOf(d to 196, d.plusDays(2) to 194, d.plusDays(5) to 194, d.plusDays(6) to 170)
        assertEquals(194, Recovery.observedPeak(maxima.values.toList()))
        assertEquals(d.plusDays(5), Recovery.observedPeakDay(maxima))
        assertNull(Recovery.observedPeakDay(mapOf(d to 196)))
    }
}
