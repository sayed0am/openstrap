package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.settings.UserProfile

class Vo2maxTest {
    private val day = 86_400L
    private val today = 1000 * day

    private val profile = UserProfile(
        sex = UserProfile.Sex.Male, weightKg = 87.7f, heightCm = 172, activityLevel = 1,
        activityAnsweredAt = today - 10 * day, bodyMeasuredAt = today - 10 * day,
    )
    private val rhr = (0 until 7).associate { today - it * day to 72f }

    @Test fun `jurca equation`() {
        // Jurca 2005 NASA model: 2.77·male − 0.10·age − 0.17·BMI − 0.03·RHR + SR-PA + 18.07.
        val bmi = 87.7f / (1.72f * 1.72f)
        val expected = 2.77f - 0.10f * 45 - 0.17f * bmi - 0.03f * 72 + 0.32f + 18.07f
        assertEquals(expected, Vo2max.jurcaMets(true, 45, bmi, 72f, 1), 1e-4f)
        // Women: no sex term. The top activity level adds 3.03.
        assertEquals(expected - 2.77f - 0.32f + 3.03f, Vo2max.jurcaMets(false, 45, bmi, 72f, 4), 1e-4f)
    }

    @Test fun `estimate is a band of plus or minus one SEE, never a decimal`() {
        val (e, why) = Vo2max.estimate(profile, 45, rhr, today)
        assertNull(why)
        val ml = Vo2max.jurcaMets(true, 45, 87.7f / (1.72f * 1.72f), 72f, 1) * 3.5f
        assertEquals(Math.round(ml), e!!.value)
        assertEquals(Math.round(ml - 1.45f * 3.5f), e.low)
        assertEquals(Math.round(ml + 1.45f * 3.5f), e.high)
        // ≈ 33 ml/kg/min for a 45-year-old man: between the 40-49 row's 30th (29.7) and 50th (35.3) percentiles.
        assertEquals(Vo2max.Friend.Band.P30to50, e.band)
        assertEquals(Vo2max.Friend.fitnessAge(ml, UserProfile.Sex.Male), e.fitnessAge)
        assertEquals(UserProfile.Sex.Male, e.sex)
    }

    @Test fun `resting HR is the median of the last 7 nights`() {
        val nights = (0 until 10).associate { today - it * day to (60f + it) } // most recent 60, oldest in window 69
        val (e, _) = Vo2max.estimate(profile, 45, nights, today)
        assertEquals(63f, e!!.restingHr, 1e-5f) // last 7: 60..66
    }

    @Test fun `withholding rules`() {
        fun why(p: UserProfile = profile, age: Int? = 45, n: Map<Long, Float> = rhr) = Vo2max.estimate(p, age, n, today).second
        assertEquals(Vo2max.Withheld.Age, why(age = 19))
        assertEquals(Vo2max.Withheld.Age, why(age = 80))
        assertEquals(Vo2max.Withheld.Sex, why(profile.copy(sex = null)))
        assertEquals(Vo2max.Withheld.Body, why(profile.copy(bodyMeasuredAt = today - 91 * day)))
        assertEquals(Vo2max.Withheld.Body, why(profile.copy(heightCm = null)))
        assertEquals(Vo2max.Withheld.Activity, why(profile.copy(activityAnsweredAt = today - 181 * day)))
        assertEquals(Vo2max.Withheld.Activity, why(profile.copy(activityLevel = null)))
        assertEquals(Vo2max.Withheld.Medication, why(profile.copy(betaBlocker = true)))
        assertEquals(Vo2max.Withheld.Medication, why(profile.copy(pregnant = true)))
        assertEquals(Vo2max.Withheld.RestingHr, why(n = rhr.entries.take(4).associate { it.key to it.value }))
        // Nights older than 14 days don't count.
        assertEquals(Vo2max.Withheld.RestingHr, why(n = (20 until 30).associate { today - it * day to 60f }))
    }

    @Test fun `FRIEND tables are Table 3 treadmill rows, ascending and falling with age`() {
        for (t in listOf(Vo2max.Friend.MEN, Vo2max.Friend.WOMEN)) {
            assertEquals(listOf(20..29, 30..39, 40..49, 50..59, 60..69, 70..79, 80..89), t.keys.toList())
            for (row in t.values) {
                assertEquals(9, row.size)
                for (i in 1 until row.size) assertTrue(row[i] > row[i - 1])
            }
            val medians = t.values.map { it[4] }
            for (i in 1 until medians.size) assertTrue(medians[i] < medians[i - 1])
        }
        // Spot checks against Kaminsky 2022 Table 3.
        assertEquals(46.5f, Vo2max.Friend.MEN.getValue(20..29)[4])
        assertEquals(58.6f, Vo2max.Friend.MEN.getValue(20..29)[8])
        assertEquals(12.9f, Vo2max.Friend.MEN.getValue(80..89)[0])
        assertEquals(36.6f, Vo2max.Friend.WOMEN.getValue(20..29)[4])
        assertEquals(22.9f, Vo2max.Friend.WOMEN.getValue(50..59)[4])
        assertEquals(20.8f, Vo2max.Friend.WOMEN.getValue(80..89)[8])
    }

    @Test fun `band edges use the 30th, 50th, 70th and 90th percentiles`() {
        val f = UserProfile.Sex.Female // 40-49: 30th 21.8, 50th 25.7, 70th 30.0, 90th 37.8
        assertEquals(Vo2max.Friend.Band.Below30, Vo2max.Friend.band(21.7f, f, 45))
        assertEquals(Vo2max.Friend.Band.P30to50, Vo2max.Friend.band(21.8f, f, 45))
        assertEquals(Vo2max.Friend.Band.P50to70, Vo2max.Friend.band(25.7f, f, 45))
        assertEquals(Vo2max.Friend.Band.P70to90, Vo2max.Friend.band(30.0f, f, 45))
        assertEquals(Vo2max.Friend.Band.P70to90, Vo2max.Friend.band(37.7f, f, 45))
        assertEquals(Vo2max.Friend.Band.Top10, Vo2max.Friend.band(37.8f, f, 49))
        assertNull(Vo2max.Friend.band(30f, f, 19))
    }

    @Test fun `fitness age is where the median equals the estimate, plus or minus 10 years, clamped 20-80`() {
        val m = UserProfile.Sex.Male
        // The 50-59 median exactly: centre 55.
        assertEquals(45..65, Vo2max.Friend.fitnessAge(29.2f, m))
        // Halfway between the 30-39 (39.7) and 40-49 (35.3) medians: centre 40.
        assertEquals(30..50, Vo2max.Friend.fitnessAge(37.5f, m))
        // Above the youngest median the centre stays at 25 (the 20-29 midpoint), the range clamped at 20. Below the oldest
        // median the centre is clamped from 85 to 80.
        assertEquals(20..35, Vo2max.Friend.fitnessAge(60f, m))
        assertEquals(70..80, Vo2max.Friend.fitnessAge(10f, m))
        // Women use their own medians: 25.7 is the 40-49 median.
        assertEquals(35..55, Vo2max.Friend.fitnessAge(25.7f, UserProfile.Sex.Female))
    }

    @Test fun `fitness age is withheld with the estimate`() {
        val (e, why) = Vo2max.estimate(profile.copy(betaBlocker = true), 45, rhr, today)
        assertNull(e)
        assertEquals(Vo2max.Withheld.Medication, why)
    }
}
