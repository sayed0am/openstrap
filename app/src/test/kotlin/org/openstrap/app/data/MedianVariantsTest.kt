package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.db.WorkoutHeartRate
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/** The medians moved onto [Stats] (D14) keep each caller's own variant. */
class MedianVariantsTest {
    @Test fun `the usual workout time takes the whole minute below a half`() {
        val zone = ZoneOffset.UTC
        val now = Instant.parse("2026-10-17T20:00:00Z")
        // Two Saturdays at 07:00 and 07:01 each: the mean of the middle two, 07:00.5, cut to 07:00.
        val starts = listOf(1L, 2L).flatMap { w ->
            val d = now.minusSeconds(w * 7 * 86_400L).atZone(zone).toLocalDate()
            listOf(d.atTime(7, 0).toEpochSecond(zone), d.atTime(7, 1).toEpochSecond(zone))
        }
        assertEquals(LocalTime.of(7, 0), MomentResolver.usualWorkoutTime(starts, now, zone))
    }

    @Test fun `the usual load is the mean of the middle two`() {
        assertEquals(105f, Workouts.usualLoad(listOf(100, 110, 90, 120))!!, 0f)
        assertEquals(100f, Workouts.usualLoad(listOf(100, 110, 90))!!, 0f)
    }

    @Test fun `a heart rate at a moment is a reading that was taken`() {
        val hr = listOf(WorkoutHeartRate(0, 100, 120), WorkoutHeartRate(0, 101, 130))
        assertEquals(130, Workouts.heartRateAt(hr.map { it.timestamp to it.bpm }, 100))
    }

    @Test fun `the percentile interpolates`() {
        assertEquals(2.5f, Recovery.percentile(listOf(4f, 1f, 3f, 2f), 0.5f)!!, 1e-6f)
        assertEquals(1.3f, Recovery.percentile(listOf(4f, 1f, 3f, 2f), 0.1f)!!, 1e-6f)
    }
}
