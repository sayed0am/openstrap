package org.openstrap.app.ui.glow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.ui.workout.PhoneSummary

/** The summary's hero falls back to the time when its own number is missing, and a missing figure is a dash. */
class SummaryHeroTest {
    private val start = 1_792_000_000L

    private fun summary(seconds: Long, sport: Int, bpm: Int? = null): PhoneSummary {
        val w = AppWorkout(start, start + seconds, sport, averageHeartRate = bpm)
        val hr = if (bpm == null) emptyList() else (0L until seconds step 5).map { AppWorkoutHeartRate(start, start + it, bpm, false) }
        return PhoneSummary(WorkoutRecorder.analyse(w, hr, 190), emptyList(), start, start + seconds, null)
    }

    @Test
    fun anIndoorWorkoutWithoutHeartRateLeadsWithItsTime() {
        val h = summaryHero(Workouts.Family.IndoorCardio, summary(69, 2), null)
        assertEquals("1", h.value)
        assertEquals("min", h.unit)
        assertEquals(listOf("1:09", null, null), h.figures.map { it.first })
        assertEquals(listOf("Time", "Active kcal", "Peak"), h.figures.map { it.second })
    }

    @Test
    fun underAMinuteItReadsSeconds() {
        val h = summaryHero(Workouts.Family.Games, summary(45, 26), null)
        assertEquals("45", h.value)
        assertEquals("s played", h.unit)
    }

    @Test
    fun withHeartRateTheKindsOwnNumberLeads() {
        val h = summaryHero(Workouts.Family.IndoorCardio, summary(1800, 2, 141), null)
        assertEquals("141", h.value)
        assertEquals("avg bpm", h.unit)
    }

    @Test
    fun strengthAndIntervalsWithoutSetsOrRoundsFallBackToo() {
        for (family in listOf(Workouts.Family.Strength, Workouts.Family.Intervals)) {
            val h = summaryHero(family, summary(600, 52), null)
            assertEquals("10", h.value)
            assertEquals("min", h.unit)
            assertNull(h.figures.last().first)
        }
    }

    @Test
    fun noFigureEverReadsNone() {
        Workouts.Family.entries.forEach { f ->
            summaryHero(f, summary(69, 2), null).figures.forEach { (v, _) -> assertEquals(false, v == "None") }
        }
    }
}
