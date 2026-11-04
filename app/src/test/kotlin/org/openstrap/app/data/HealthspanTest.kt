package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Healthspan.Habit
import org.openstrap.app.data.settings.UserProfile

/** The worked examples in docs/algorithms/healthspan.md §7, and the rules around them. */
class HealthspanTest {
    private val man = UserProfile.Sex.Male
    private val day = 86_400L
    private val asOf = 2000 * day

    private fun assertTerm(expected: Float, low: Float, high: Float, v: Healthspan.Value) {
        assertEquals(expected, v.years, 0.015f)
        assertEquals(low, v.low, 0.015f)
        assertEquals(high, v.high, 0.015f)
    }

    @Test fun `example A, close to average`() {
        val t = Healthspan.terms(Healthspan.Habits(58f, 7000f, 75f, 400f, 0f, 44f), 35f, man)
        assertTerm(-0.13f, -0.14f, -0.11f, t.each.getValue(Habit.RestingHr))
        assertTerm(1.22f, 0.61f, 1.50f, t.each.getValue(Habit.Steps))
        assertTerm(-0.93f, -1.17f, -0.71f, t.each.getValue(Habit.Vo2max))
        assertTerm(0.16f, -0.70f, 0.68f, t.fitness)
        assertTerm(0.71f, 0.35f, 1.06f, t.each.getValue(Habit.Regularity))
        // 6 h 40 asleep is just under 7 h: HR 1.01 at 6 h 30, joined to 1.00 at 7 h.
        assertTerm(0.07f, 0.04f, 0.11f, t.each.getValue(Habit.TimeAsleep))
        assertTerm(0f, 0f, 0f, t.each.getValue(Habit.Strength))
        assertEquals(0.94f, t.sum.years, 0.02f)
        val (estimate, low, high) = Healthspan.range(35, t, Healthspan.MIN_SPREAD)
        assertEquals(35.6f, estimate, 0.05f)
        assertEquals(34, low)
        assertEquals(38, high)
    }

    @Test fun `example B, healthy`() {
        val t = Healthspan.terms(Healthspan.Habits(55f, 10_000f, 87f, 450f, 40f, 46.4f), 35f, man)
        assertTerm(-0.64f, -0.71f, -0.54f, t.each.getValue(Habit.RestingHr))
        assertTerm(-0.86f, -1.29f, -0.43f, t.each.getValue(Habit.Steps))
        assertTerm(-1.45f, -1.82f, -1.10f, t.each.getValue(Habit.Vo2max))
        assertTerm(-0.49f, -0.57f, -0.25f, t.each.getValue(Habit.Regularity))
        // 40 min a week sits between Zhang's 15 and 45 min points: HR 0.917 (0.878-0.953).
        assertTerm(-0.95f, -1.42f, -0.52f, t.each.getValue(Habit.Strength))
        assertEquals(-4.39f, t.sum.years, 0.03f)
        val (estimate, low, high) = Healthspan.range(35, t, Healthspan.MIN_SPREAD)
        assertEquals(32.4f, estimate, 0.05f)
        assertEquals(30, low)
        assertEquals(34, high)
    }

    @Test fun `example C, the fitness limit binds`() {
        val t = Healthspan.terms(Healthspan.Habits(80f, 4000f, 75f, 400f, 0f, 30f), 35f, man)
        assertTerm(3.64f, 3.04f, 4.00f, t.each.getValue(Habit.RestingHr))
        // The steps band is ±50 % after the limit (§3.2), so a capped term keeps a range.
        assertTerm(1.50f, 0.75f, 1.50f, t.each.getValue(Habit.Steps))
        assertTerm(2.00f, 1.59f, 2.00f, t.each.getValue(Habit.Vo2max))
        assertTerm(5.00f, 5.00f, 5.00f, t.fitness)
        assertTrue(t.fitnessCapped)
        val (estimate, low, high) = Healthspan.range(35, t, Healthspan.MIN_SPREAD)
        assertEquals(38.5f, estimate, 0.05f)
        assertEquals(36, low)
        assertEquals(40, high)
    }

    @Test fun `someone at every median reads their own age`() {
        val ref = Healthspan.rhrReference(35f, man) - Healthspan.SEATED_OFFSET_BPM
        val t = Healthspan.terms(Healthspan.Habits(ref, 7803f, 80.9f, 420f, 0f, Healthspan.vo2Reference(35f, man)), 35f, man)
        assertEquals(0f, t.offset, 0.01f)
    }

    @Test fun `steps blend from 55 to 65`() {
        // §3.2's table.
        assertEquals(1.22f, Healthspan.stepsTerm(7000f, 55f).years, 0.02f)
        assertEquals(-0.14f, Healthspan.stepsTerm(7000f, 60f).years, 0.02f)
        assertEquals(-1.51f, Healthspan.stepsTerm(7000f, 65f).years, 0.02f)
        assertEquals(-2.00f, Healthspan.stepsTerm(9000f, 65f).years, 0.02f)
        assertEquals(1.29f, Healthspan.stepsTerm(5000f, 60f).years, 0.02f)
    }

    @Test fun `short sleep only adds years, up to 4`() {
        assertEquals(0f, Healthspan.sleepTerm(480f).years, 0f)
        assertEquals(0f, Healthspan.sleepTerm(420f).years, 0f)
        assertEquals(1.62f, Healthspan.sleepTerm(330f).years, 0.01f)
        assertEquals(2.78f, Healthspan.sleepTerm(300f).years, 0.01f)
        // Held at 4.5 h's 3.98 below it, inside the 4-year limit.
        assertEquals(3.98f, Healthspan.sleepTerm(240f).years, 0.01f)
    }

    @Test fun `strength gives credit only, with no penalty at high doses`() {
        assertEquals(-0.79f, Healthspan.strengthTerm(30f).years, 0.01f)
        assertEquals(-1.52f, Healthspan.strengthTerm(105f).years, 0.01f)
        assertEquals(0f, Healthspan.strengthTerm(0f).years, 0f)
        // Above 135 min the credit holds at Zhang's 120+ category, HR 0.92.
        assertEquals(-0.91f, Healthspan.strengthTerm(200f).years, 0.01f)
    }

    @Test fun `pace is 1x inside the noise gate`() {
        assertEquals(1f, Healthspan.pace(0.2f, 0.1f, listOf(0f, 0.1f, 0.2f))!!, 0f)
        // 0.42 y worse than the 6 months moves pace by about 1x: rounded to the nearest 0.5x.
        assertEquals(2f, Healthspan.pace(0.52f, 0.1f, listOf(0.1f, 0.1f, 0.1f))!!, 0f)
        assertEquals(-1f, Healthspan.pace(-3f, 0f, listOf(0f, 0f, 0f))!!, 0f)
        assertNull(Healthspan.pace(0f, 0f, listOf(0f, 0f)))
    }

    /** 180 days of example A's habits, every day and night valid. */
    private fun dailyA(days: Int = 180) = Healthspan.Daily(
        asOf = asOf,
        restingHr = (1..days).associate { asOf - it * day to 58f },
        steps = (1..days).associate { asOf - it * day to 7000 },
        weeklySri = (0 until days / 7).associate { asOf - it * 7 * day - day to 75f },
        asleepMinutes = (1..days).associate { asOf - it * day to 400 },
        vo2max = (1..4).associate { asOf - it * 10 * day to 44f },
        firstDay = asOf - days * day,
    )

    @Test fun `weekly estimate from daily data`() {
        val (r, why) = Healthspan.estimate(UserProfile(sex = man), 35, dailyA())
        assertNull(why)
        r!!
        assertFalse(r.early)
        assertEquals(34, r.low)
        assertEquals(38, r.high)
        // Steady habits: pace in step with the calendar.
        assertEquals(1f, r.pace!!, 0f)
        // Steps add the most; nothing takes years off beyond VO2max.
        assertEquals(Habit.Steps, r.terms.first().habit)
        assertEquals(0.6f * 1.22f, r.terms.first().years, 0.02f)
        assertEquals(Habit.Vo2max, r.lever!!.habit)
    }

    @Test fun `early estimate after 30 days, withheld before`() {
        val profile = UserProfile(sex = man)
        assertEquals(Healthspan.Withheld.Learning, Healthspan.estimate(profile, 35, dailyA(20)).second)
        val (r, _) = Healthspan.estimate(profile, 35, dailyA(42))
        assertTrue(r!!.early)
        assertTrue(r.high - r.low >= 8)
        assertNull(r.pace)
    }

    @Test fun `withholding order`() {
        assertEquals(Healthspan.Withheld.NoAge, Healthspan.estimate(UserProfile(sex = man), null, dailyA()).second)
        assertEquals(Healthspan.Withheld.Age, Healthspan.estimate(UserProfile(sex = man), 19, dailyA()).second)
        assertEquals(Healthspan.Withheld.Sex, Healthspan.estimate(UserProfile(), 35, dailyA()).second)
        assertEquals(Healthspan.Withheld.Medication, Healthspan.estimate(UserProfile(sex = man, betaBlocker = true), 35, dailyA()).second)
        val sparse = dailyA().let { it.copy(restingHr = it.restingHr.entries.take(60).associate { e -> e.toPair() }) }
        assertEquals(Healthspan.Withheld.RestingHr, Healthspan.estimate(UserProfile(sex = man), 35, sparse).second)
    }

    @Test fun `last week's estimate is the stored Sunday before, over a clock change too`() {
        val day = 86_400L
        val sunday = 1_760_745_600L
        // A week before, an hour off for the clock change: still last week.
        val weeks = listOf(sunday - 14 * day to 31f, sunday - 7 * day + 3600 to 30.1f)
        assertEquals(30.1f, Healthspan.lastWeek(weeks, sunday))
        assertNull(Healthspan.lastWeek(listOf(sunday - 14 * day to 31f), sunday))
        // This Sunday's own result doesn't count as last week.
        assertNull(Healthspan.lastWeek(listOf(sunday to 29f), sunday))
        assertNull(Healthspan.lastWeek(emptyList(), sunday))
    }
}
