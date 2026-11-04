package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutSuggestionsTest {
    private val t0 = 1_800_000_000L / 60 * 60
    // Resting 60, max 190: 40 % of reserve is 112 bpm.
    private fun minutes(vararg runs: Pair<Int, Int>): List<Pair<Long, Int>> {
        var m = 0L
        return runs.flatMap { (bpm, n) -> (0 until n).map { (t0 + (m++) * 60) to bpm } }
    }

    @Test
    fun tenMinutesAboveTheLineIsOffered() {
        val hr = minutes(70 to 30, 140 to 12, 70 to 30)
        val s = WorkoutSuggestions.find(hr, 60, 190, emptyList(), HeartZones.STANDARD)
        assertEquals(1, s.size)
        assertEquals(t0 + 30 * 60, s[0].start)
        assertEquals(t0 + 42 * 60, s[0].end)
        assertEquals(140, s[0].averageHeartRate)
        // 140/190 = 74 %: zone 3.
        assertEquals(2, s[0].zone)
    }

    @Test
    fun nineMinutesIsNot() {
        assertTrue(WorkoutSuggestions.find(minutes(70 to 30, 140 to 9, 70 to 30), 60, 190, emptyList(), HeartZones.STANDARD).isEmpty())
    }

    @Test
    fun aShortDipDoesNotSplitASession() {
        // 6 on, 2 resting, 6 on: one 14-minute session of 12 qualifying minutes.
        val s = WorkoutSuggestions.find(minutes(70 to 10, 140 to 6, 90 to 2, 140 to 6, 70 to 10), 60, 190, emptyList(), HeartZones.STANDARD)
        assertEquals(1, s.size)
        assertEquals(14, s[0].minutes)
    }

    @Test
    fun aLongDipDoes() {
        assertTrue(WorkoutSuggestions.find(minutes(70 to 10, 140 to 6, 90 to 5, 140 to 6, 70 to 10), 60, 190, emptyList(), HeartZones.STANDARD).isEmpty())
    }

    @Test
    fun busyTimeNeverCounts() {
        val hr = minutes(70 to 30, 140 to 20, 70 to 30)
        // Already a workout, or answered "Not a workout".
        val busy = listOf((t0 + 30 * 60)..(t0 + 50 * 60 - 1))
        assertTrue(WorkoutSuggestions.find(hr, 60, 190, busy, HeartZones.STANDARD).isEmpty())
        // Half of it busy leaves 10 free minutes: still offered, from where the busy stretch ends.
        val half = listOf((t0 + 30 * 60)..(t0 + 40 * 60 - 1))
        val s = WorkoutSuggestions.find(hr, 60, 190, half, HeartZones.STANDARD)
        assertEquals(t0 + 40 * 60, s.single().start)
    }

    @Test
    fun newestFirst() {
        val s = WorkoutSuggestions.find(minutes(140 to 10, 70 to 30, 150 to 15), 60, 190, emptyList(), HeartZones.STANDARD)
        assertEquals(2, s.size)
        assertTrue(s[0].start > s[1].start)
    }
}
