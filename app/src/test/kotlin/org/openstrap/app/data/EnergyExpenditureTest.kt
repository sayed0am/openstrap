package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.EnergyExpenditure.Minute
import org.openstrap.app.data.settings.UserProfile

class EnergyExpenditureTest {
    private val man = UserProfile(sex = UserProfile.Sex.Male, weightKg = 70f, heightCm = 175)
    private val age = 30
    /** The cadence MET for [man] at [age]: Moore 2021 with BMI 70 / 1.75². */
    private fun walkMet(cadence: Int) = EnergyExpenditure.cadenceMet(cadence, age, 70f / (1.75f * 1.75f), 175)
    /** 10·70 + 6.25·175 − 5·30 + 5 = 1648.75 kcal/day. */
    private val bmr = 1648.75f
    private val b = bmr / 1440f

    @Test fun `mifflin st jeor`() {
        assertEquals(bmr, EnergyExpenditure.bmr(70f, 175, 30, UserProfile.Sex.Male), 1e-3f)
        assertEquals(bmr - 166f, EnergyExpenditure.bmr(70f, 175, 30, UserProfile.Sex.Female), 1e-3f)
        assertEquals(bmr - 83f, EnergyExpenditure.bmr(70f, 175, 30, null), 1e-3f)
        assertEquals(2f * 3.5f * 70f / 200f, EnergyExpenditure.net(3f, 70f), 1e-5f)
        assertEquals(0f, EnergyExpenditure.net(0.9f, 70f), 1e-6f)
    }

    @Test fun `cadence mets`() {
        // Moore 2021's worked rows: 30 years, BMI 23, 175 cm, then 70 years, BMI 28, 165 cm.
        assertEquals(1.60f, EnergyExpenditure.cadenceMet(60, 30, 23f, 175), 0.01f)
        assertEquals(3.33f, EnergyExpenditure.cadenceMet(100, 30, 23f, 175), 0.01f)
        assertEquals(5.37f, EnergyExpenditure.cadenceMet(130, 30, 23f, 175), 0.01f)
        assertEquals(3.41f, EnergyExpenditure.cadenceMet(110, 70, 28f, 165), 0.01f)
        assertEquals(3.90f, EnergyExpenditure.cadenceMet(120, 70, 28f, 165), 0.01f)
        // Ages outside 21-84 are held at the nearest end.
        assertEquals(EnergyExpenditure.cadenceMet(100, 21, 23f, 175), EnergyExpenditure.cadenceMet(100, 16, 23f, 175), 1e-6f)
        assertEquals(EnergyExpenditure.cadenceMet(100, 84, 23f, 175), EnergyExpenditure.cadenceMet(100, 90, 23f, 175), 1e-6f)
        // Running: 160 spm × 0.45·1.75 m = 126 m/min → VO2 28.7 → 8.2 METs.
        assertEquals((3.5f + 0.2f * 160 * 0.45f * 1.75f) / 3.5f, EnergyExpenditure.runningMet(160, 175), 1e-3f)
    }

    @Test fun `each kind of minute`() {
        fun one(m: Minute, profile: UserProfile = man) = EnergyExpenditure.day(listOf(m), profile, age)!!.totalKcal
        assertEquals(0.95f * b, one(Minute(0, null, null, asleep = true)), 1e-4f)
        assertEquals(1.15f * b, one(Minute(0, 0, 0.1f, asleep = false)), 1e-4f)
        assertEquals(1.3f * b, one(Minute(0, 20, null, asleep = false)), 1e-4f)
        assertEquals(b + EnergyExpenditure.net(walkMet(100), 70f), one(Minute(0, 100, null, asleep = false)), 1e-4f)
        assertEquals(9f, one(Minute(0, 0, null, asleep = false, workoutKcal = 9f)), 1e-4f)
        // A still minute next to a stepping one is light activity.
        val pair = EnergyExpenditure.day(listOf(Minute(0, 0, null, false), Minute(60, 30, null, false)), man, age)!!
        assertEquals(2 * 1.3f * b, pair.totalKcal, 1e-4f)
        assertNull(EnergyExpenditure.day(listOf(Minute(0, 0, null, false)), man.copy(weightKg = null), age))
    }

    @Test fun `hr branch only for five still minutes at 40 percent and up`() {
        val cycling = (0 until 12).map { Minute(it * 60L, 0, 0.5f, false) }
        val d = EnergyExpenditure.day(cycling, man, age, vo2max = 42f)!!
        val met = 1f + 0.5f * (42f / 3.5f - 1f) // 6.5, capped at 6
        assertEquals(12, d.hrAssistedMinutes)
        assertEquals(12 * (b + EnergyExpenditure.net(minOf(6f, met), 70f)), d.totalKcal, 1e-3f)
        val short = EnergyExpenditure.day(cycling.take(4), man, age, vo2max = 42f)!!
        assertEquals(0, short.hrAssistedMinutes)
        assertEquals(4 * 1.15f * b, short.totalKcal, 1e-3f)
    }

    @Test fun `partial days and pal`() {
        val half = EnergyExpenditure.day((0 until 600).map { Minute(it * 60L, 0, null, false) }, man, age)!!
        assertTrue(half.partial)
        assertNull(half.pal)
        // A full sedentary day with 8 h asleep: PAL ≈ (480·0.95 + 960·1.15)/1440 ≈ 1.08, below 1.2: flagged.
        val full = EnergyExpenditure.day((0 until 1440).map { Minute(it * 60L, 0, null, it < 480) }, man, age)!!
        assertFalse(full.partial)
        assertEquals((480 * 0.95f + 960 * 1.15f) / 1440, full.pal!!, 1e-3f)
        assertTrue(full.palImplausible)
    }

    @Test fun `active kcal is what's above rest, split by hour and source`() {
        // One minute in each of the first four hours (UTC hours by default): asleep, sitting, walking, in a workout.
        val d = EnergyExpenditure.day(
            listOf(
                Minute(0, null, null, asleep = true),
                Minute(3600, 0, null, asleep = false),
                Minute(7200, 100, null, asleep = false),
                Minute(10_800, 0, null, asleep = false, workoutKcal = 9f),
            ),
            man, age,
        )!!
        val walk = EnergyExpenditure.net(walkMet(100), 70f) // 100 spm is about 3.3 METs: about 2.86 kcal above rest
        // Sleep (0.95 b) is held at zero rather than taking anything off.
        assertEquals(0.95f * b + 1.15f * b + (b + walk) + 9f, d.totalKcal, 1e-4f)
        assertEquals(0.15f * b + walk + (9f - b), d.activeKcal, 1e-4f)
        assertEquals(9f - b, d.workoutKcal, 1e-4f)
        assertEquals(walk, d.walkingKcal, 1e-4f)
        assertEquals(0.15f * b, d.everydayKcal, 1e-4f)
        listOf(0f, 0.15f * b, 0f, 0f).forEachIndexed { h, v -> assertEquals(v, d.hourly.everyday[h], 1e-5f) }
        assertEquals(walk, d.hourly.walking[2], 1e-5f)
        assertEquals(9f - b, d.hourly.workout[3], 1e-5f)
        assertEquals(d.activeKcal, d.hourly.total.sum(), 1e-4f)
        // The local hour comes from the caller.
        val shifted = EnergyExpenditure.day(listOf(Minute(7200, 100, null, false)), man, age) { 14 }!!
        assertEquals(walk, shifted.hourly.walking[14], 1e-5f)
    }

    @Test fun `an HR-assisted minute is everyday, not walking`() {
        val cycling = (0 until 10).map { Minute(it * 60L, 0, 0.5f, false) }
        val d = EnergyExpenditure.day(cycling, man, age, vo2max = 42f)!!
        assertEquals(10, d.hrAssistedMinutes)
        assertEquals(10 * EnergyExpenditure.net(6f, 70f), d.everydayKcal, 1e-3f)
        assertEquals(0f, d.walkingKcal, 0f)
    }

    @Test fun `in a workout heart rate leads from the first minute, capped at 90 percent of VO2max`() {
        // VO2max 42 is 12 METs. At 50 % of reserve: 1 + 0.5·11 = 6.5 METs, above the everyday cap of 6.
        val one = EnergyExpenditure.workoutMinutes(listOf(0L), (0L until 60L).map { it to 0.5f }, emptyMap(), man, age, vo2max = 42f)!!.single()
        assertEquals(b + EnergyExpenditure.net(6.5f, 70f), one.kcal, 1e-4f)
        assertEquals(EnergyExpenditure.net(6.5f, 70f), one.activeKcal, 1e-4f)
        assertTrue(one.hrLed)
        // Flat out (x = 1, 12 METs) is held at 0.9·12 = 10.8.
        val hard = EnergyExpenditure.workoutMinutes(listOf(0L), (0L until 60L).map { it to 1f }, emptyMap(), man, age, vo2max = 42f)!!.single()
        assertEquals(b + EnergyExpenditure.net(10.8f, 70f), hard.kcal, 1e-4f)
        // Per sample, then the minute's mean: half the minute at 0 and half at 1 is the mean of 1 and 10.8 METs' kcal.
        val mixed = EnergyExpenditure.workoutMinutes(listOf(0L), (0L until 60L).map { it to if (it < 30) 0f else 1f }, emptyMap(), man, age, vo2max = 42f)!!.single()
        // Alone, x = 0 is b, below sitting's 1.15 b, and steps would lead; the minute's mean is well above both.
        assertEquals(b + EnergyExpenditure.net(10.8f, 70f) / 2, mixed.kcal, 1e-4f)
    }

    @Test fun `a walk at a low heart rate keeps its cadence MET`() {
        // 120 spm is about 4.6 METs; x = 0.1 at the default VO2max 35 (10 METs) is 1.9 METs.
        val walk = EnergyExpenditure.workoutMinutes(listOf(0L), (0L until 60L).map { it to 0.1f }, mapOf(0L to 120), man, age)!!.single()
        assertEquals(b + EnergyExpenditure.net(walkMet(120), 70f), walk.kcal, 1e-4f)
        assertFalse(walk.hrLed)
        // A minute without heart rate or steps is sitting, still above rest.
        val still = EnergyExpenditure.workoutMinutes(listOf(0L), emptyList(), emptyMap(), man, age)!!.single()
        assertEquals(1.15f * b, still.kcal, 1e-4f)
        assertNull(EnergyExpenditure.workoutMinutes(listOf(0L), emptyList(), emptyMap(), man.copy(heightCm = null), age))
    }

    @Test fun `fitted minutes keep their shape and add up to the saved total`() {
        val minutes = listOf(
            EnergyExpenditure.WorkoutMinute(0, b + 4f, 4f, true),
            EnergyExpenditure.WorkoutMinute(60, b + 8f, 8f, true),
        )
        val fitted = EnergyExpenditure.fitted(minutes, totalKcal = 2 * b + 18f, b = b)
        assertEquals(listOf(6f, 12f), fitted.map { it.activeKcal })
        assertEquals(2 * b + 18f, fitted.sumOf { it.kcal.toDouble() }.toFloat(), 1e-4f)
        assertEquals(minutes, EnergyExpenditure.fitted(minutes, null, b))
    }
}
