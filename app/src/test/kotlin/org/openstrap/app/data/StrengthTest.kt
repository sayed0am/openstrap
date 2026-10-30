package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthTest {
    private fun hr(from: Long, to: Long, bpm: Int) = (from until to).map { it to bpm }

    @Test
    fun restEndsAtItsTime() {
        val rest = Strength.Rest(90, null)
        assertFalse(Strength.restOver(rest, 0, 90, hr(0, 89, 150), 89))
        assertTrue(Strength.restOver(rest, 0, 90, hr(0, 90, 150), 90))
    }

    @Test
    fun orEarlierOnceHeartRateHoldsBelowTheLevel() {
        val rest = Strength.Rest(90, 110)
        val h = hr(0, 30, 130) + hr(30, 60, 105)
        // Below since 30; held 5 s by 35.
        assertFalse(Strength.restOver(rest, 0, 90, h, 33))
        assertTrue(Strength.restOver(rest, 0, 90, h, 35))
    }

    @Test
    fun notOnHeartRateTooEarlyOrOnOneLowReading() {
        val rest = Strength.Rest(90, 110)
        assertFalse(Strength.restOver(rest, 0, 90, hr(0, 20, 100), 15))
        val spike = hr(0, 40, 130) + listOf(40L to 100) + hr(41, 50, 130)
        assertFalse(Strength.restOver(rest, 0, 90, spike, 45))
    }

    @Test
    fun theTimeLimitAlwaysAppliesAndExtends() {
        val rest = Strength.Rest(60, 110)
        // Heart rate never drops: rest still ends at its time, or when extended, at the new time.
        assertTrue(Strength.restOver(rest, 0, 60, hr(0, 60, 140), 60))
        assertFalse(Strength.restOver(rest, 0, 60 + Strength.EXTEND_S, hr(0, 70, 140), 70))
    }

    @Test
    fun restCodesRoundTrip() {
        assertEquals(Strength.Rest(90, 110), Strength.Rest.parse(Strength.Rest(90, 110).code))
        assertEquals(Strength.Rest(120, null), Strength.Rest.parse(Strength.Rest(120, null).code))
        assertNull(Strength.Rest.parse("x"))
    }

    private fun set(i: Int, exercise: String?, reps: Int?, kg: Float?) =
        org.openstrap.app.data.db.AppWorkoutSet(1, i, 100L * i, 100L * i + 40, exercise = exercise, reps = reps, weightKg = kg)

    @Test
    fun theLogCardFillsFromTheLastSetOfThatExercise() {
        val sets = listOf(set(0, "Bench press", 10, 60f), set(1, "Squat", 8, 100f), set(2, "Bench press", 8, 62.5f), set(3, "Bench press", null, null))
        // This workout's last logged set of it comes first, matched whatever the case.
        assertEquals(8 to 62.5f, Strength.prefill("bench press", sets, 12, 50f))
        // Not logged here yet: what the exercise list remembers, else the defaults.
        assertEquals(12 to 50f, Strength.prefill("Deadlift", sets, 12, 50f))
        assertEquals(Strength.DEFAULT_REPS to Strength.DEFAULT_KG, Strength.prefill("Deadlift", sets, null, null))
    }

    @Test
    fun kgLiftedCountsOnlyLoggedSets() {
        val sets = listOf(set(0, "Bench press", 10, 60f), set(1, "Squat", 8, 100f), set(2, null, null, null), set(3, "Plank", 1, null))
        assertEquals(1400f, Strength.kgLifted(sets)!!, 1e-3f)
        assertNull(Strength.kgLifted(listOf(set(0, null, null, null))))
    }

    @Test
    fun yourExercisesComeBeforeTheCommonLifts() {
        val list = Strength.exerciseList(listOf("Cable fly", "squat"))
        assertEquals(listOf("Cable fly", "squat", "Bench press"), list.take(3))
        assertEquals(1, list.count { it.equals("Squat", ignoreCase = true) })
    }

    @Test
    fun logSteppersAndWeights() {
        assertEquals(62.5f, Strength.stepKg(60f, 1))
        assertEquals(0f, Strength.stepKg(0f, -1))
        assertEquals(1, Strength.stepReps(1, -1))
        assertEquals("60", Strength.kgText(60f))
        assertEquals("62.5", Strength.kgText(62.5f))
        assertEquals(148, Strength.peak(listOf(10L to 120, 20L to 148, 50L to 160), 0, 40))
    }
}
