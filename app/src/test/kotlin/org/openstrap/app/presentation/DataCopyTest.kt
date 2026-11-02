package org.openstrap.app.presentation

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.Vo2max
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.settings.CalorieGoal
import org.openstrap.app.testing.LocaleRule
import org.openstrap.app.testing.assertCleanCopy
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** The text the data package used to write itself (A10), now built here, in each language and clock setting. */
class DataCopyTest {
    @get:Rule val localeRule = LocaleRule(Locale.GERMANY)

    @After fun clock() { ClockSetting.use24Hour = true }

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 17)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m).toEpochSecond(zone)

    @Test fun `a signed change never reads as minus nothing, whatever the decimal mark`() {
        assertEquals("±0,0", Signed.of(-0.04f, 1))
        assertEquals("−0,4", Signed.of(-0.4f, 1))
        assertEquals("+3", Signed.of(3))
        assertEquals("±0", Signed.of(0))
        assertEquals("±0.0", Signed.of(-0.04f, 1, Locale.US))
        assertEquals("−3", Signed.of(-3.2f, 0, Locale.US))
    }

    @Test fun `a sleep edit's overlap follows the clock setting`() {
        val nap = SleepSession(start = at(14), end = at(15), nap = true)
        val earlier = SleepSession(start = at(13), end = at(14, 10), nap = true)
        val now = at(20)
        assertEquals(SleepEdits.Problem.RunsInto(nap = true, start = at(13)), SleepEdits.check(nap, at(14), at(14, 48), now, listOf(earlier)))
        assertEquals("That runs into your nap at 13:00.", SleepEdits.problem(nap, at(14), at(14, 48), now, listOf(earlier), zone))
        ClockSetting.use24Hour = false
        val twelve = SleepEdits.problem(nap, at(14), at(14, 48), now, listOf(earlier), zone)!!
        assertEquals("That runs into your nap at ${TimeOfDay.format(at(13), zone, use24Hour = false)}.", twelve)
        assertCleanCopy(twelve)
    }

    @Test fun `every sleep edit problem has its sentence`() {
        val all = listOf(
            SleepEdits.Problem.WakeBeforeSleep, SleepEdits.Problem.TooShort(true), SleepEdits.Problem.TooShort(false),
            SleepEdits.Problem.TooLong(true), SleepEdits.Problem.TooLong(false), SleepEdits.Problem.Future,
            SleepEdits.Problem.TooFarFromStrap, SleepEdits.Problem.RunsInto(false, at(1)),
        ).map { SleepEditCopy.text(it, zone) }
        assertEquals(all.size, all.toSet().size)
        assertCleanCopy(all)
    }

    @Test fun `the calorie range groups its digits in the phone's language`() {
        assertEquals("Pick a number from 200 to 3.000", CalorieGoal.OUT_OF_RANGE)
        assertEquals("Pick a number from 200 to 3,000", GoalCopy.outOfRange(200..3000, Locale.US))
        assertEquals("8.000 steps, 450 active kcal, 8 h sleep, 10 active hours", org.openstrap.app.data.settings.Goals(calories = 450).summary())
        assertEquals(
            "8,000 steps, 300 active kcal, 7 h 30 sleep, 10 active hours",
            GoalCopy.summary(org.openstrap.app.data.settings.Goals(sleepMinutes = 450), Locale.US),
        )
    }

    @Test fun `the VO2max words, with the bands as ranges in words`() {
        assertEquals("Needs an age between 20 and 79.", Vo2max.Withheld.Age.reason)
        assertEquals("Top 10 %", Vo2max.Friend.Band.Top10.label)
        // The ranges say "to" now: copy uses no en dash between words.
        assertEquals("30th to 50th percentile", Vo2max.Friend.Band.P30to50.label)
        assertCleanCopy(Vo2max.Withheld.entries.map { it.reason } + Vo2max.Friend.Band.entries.map { it.label })
    }

    @Test fun `recovery's contributor lines use the app's durations`() {
        val nights = (1..20).map { org.openstrap.app.testing.night(it) }
        val tonight = org.openstrap.app.testing.night(21, asleep = 460)
        val sleep = Recovery.readiness(tonight, nights).contributors.first { it.factor == Recovery.Factor.Sleep }
        assertEquals("7 h 40 asleep", sleep.detail)
        assertCleanCopy(sleep.detail)
    }
}
