package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.Recovery.Contributor
import org.openstrap.app.data.Recovery.Factor
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.presentation.ClockSetting
import org.openstrap.app.testing.LocaleRule
import org.openstrap.app.testing.assertCleanCopy
import java.util.Locale
import org.openstrap.app.presentation.TabCopy

/** The words on the Recovery, Sleep and Activity tabs (spec 5 and 7). */
class TabCopyTest {
    // The expected numbers are written the English way, and the German test below sets its own.
    @get:Rule val locale = LocaleRule(Locale.US)

    private fun c(f: Factor, score: Int) = Contributor(f, score, org.openstrap.app.data.Recovery.Detail.Asleep(0))

    @Test fun `recovery against yesterday`() {
        assertEquals("+3 vs yesterday", TabCopy.vsYesterday(78, 75))
        assertEquals("−4 vs yesterday", TabCopy.vsYesterday(71, 75))
        assertEquals("Same as yesterday", TabCopy.vsYesterday(75, 75))
        assertNull(TabCopy.vsYesterday(75, null))
    }

    @Test fun `a charge amount signs with a real minus, never a hyphen`() {
        assertEquals("+32", TabCopy.chargeAmount(32.7f, +1))
        assertEquals("−12", TabCopy.chargeAmount(12.2f, -1))
        assertEquals("0", TabCopy.chargeAmount(0.4f, -1))
    }

    @Test fun `the learning caveat counts toward the next step, then goes`() {
        assertEquals("Learning, 4 of 7 nights", TabCopy.learning(Recovery.Phase.Withheld, 4))
        assertEquals("Learning, 9 of 14 nights", TabCopy.learning(Recovery.Phase.Learning, 9))
        assertNull(TabCopy.learning(Recovery.Phase.Scored, 20))
        assertNull(TabCopy.learning(Recovery.Phase.Calibrated, 40))
    }

    @Test fun `the sentence names what held the score back`() {
        val drivers = listOf(c(Factor.HrvBalance, 52), c(Factor.RestingHr, 81), c(Factor.Sleep, 74), c(Factor.BloodOxygen, 20))
        // Blood oxygen weighs too little to lead the sentence, so HRV is named.
        assertEquals(Factor.HrvBalance, TabCopy.heldBackBy(drivers))
        assertEquals("Recovered enough for your usual training. Mostly held back by HRV.", TabCopy.recovery(72, false, drivers))
        assertEquals(
            "Not fully recovered. Keep the effort moderate. Mostly held back by resting heart rate.",
            TabCopy.recovery(60, false, listOf(c(Factor.RestingHr, 40), c(Factor.Sleep, 80))),
        )
        // Nothing under the bar, well recovered, or an alert that already says why: the meaning alone.
        assertEquals("Recovered enough for your usual training.", TabCopy.recovery(75, false, listOf(c(Factor.HrvBalance, 80))))
        assertEquals("Well recovered. A good day for a hard session.", TabCopy.recovery(88, false, drivers))
        assertEquals("Some overnight signals are off your normal. An easy day suits.", TabCopy.recovery(50, true, drivers))
        assertNull(TabCopy.heldBackBy(emptyList()))
    }

    @Test fun `contributors read in words, three that weigh most`() {
        assertEquals("Helping", TabCopy.contributorWord(90))
        assertEquals("Usual", TabCopy.contributorWord(80))
        assertEquals("Holding back a little", TabCopy.contributorWord(60))
        assertEquals("Holding you back", TabCopy.contributorWord(30))
        assertFalse(TabCopy.contributorOff(80))
        assertTrue(TabCopy.contributorOff(60))
        val all = listOf(c(Factor.BloodOxygen, 50), c(Factor.Sleep, 70), c(Factor.HrvBalance, 80), c(Factor.RestingHr, 90))
        assertEquals(listOf(Factor.HrvBalance, Factor.RestingHr, Factor.Sleep), TabCopy.topDrivers(all).map { it.factor })
        assertEquals("HRV", TabCopy.factorName(Factor.HrvBalance))
    }

    @Test fun `charge rows and line`() {
        assertEquals(
            // A drain is an amount under "Drained", so it has no minus.
            listOf("+32", "12", "5", "0"),
            TabCopy.chargeRows(32.7f, 12.2f, 5f, 0.4f).map { it.value },
        )
        assertEquals(listOf("Recharged", "Drained", "Drained", "Drained"), TabCopy.chargeRows(1f, 1f, 1f, 1f).map { it.status })
        assertEquals("Used 20 since waking", TabCopy.chargeLine(64, 84, true))
        assertEquals("Up 5 since waking", TabCopy.chargeLine(89, 84, true))
        assertEquals("Level since waking", TabCopy.chargeLine(84, 84, true))
        assertEquals("Energy starts after your first night. Sleep is what fills it.", TabCopy.chargeLine(null, null, false))
    }

    @Test fun `the night against the rest of the week`() {
        val h = 3600L
        val week = listOf(7 * h, 6 * h, 7 * h + 1800)
        assertEquals("7 h 40, your longest this week.", TabCopy.night(7 * h + 2400, week))
        assertEquals("5 h 50, your shortest this week.", TabCopy.night(5 * h + 3000, week))
        assertEquals("7 h 10 asleep.", TabCopy.night(7 * h + 600, week))
        // Too few other nights to call it the longest.
        assertEquals("9 h asleep.", TabCopy.night(9 * h, listOf(7 * h, 6 * h)))
        assertEquals("No night sleep recorded, only naps.", TabCopy.noNight(naps = true, isToday = true))
        assertEquals("No sleep recorded last night. Was the strap on?", TabCopy.noNight(naps = false, isToday = true))
    }

    @Test fun `sleep words`() {
        assertEquals("Great", TabCopy.sleepScoreWord(92))
        assertEquals("Good", TabCopy.sleepScoreWord(83))
        assertEquals("Fair", TabCopy.sleepScoreWord(65))
        assertEquals("Poor", TabCopy.sleepScoreWord(40))
        assertEquals("Woke 3 times", TabCopy.woke(3))
        assertEquals("Woke once", TabCopy.woke(1))
        assertEquals("Slept through", TabCopy.woke(0))
        assertNull(TabCopy.woke(null))
        assertEquals("Regular", TabCopy.regularity(SleepRegularity.Result(80f, 7000, null)))
        assertEquals("Steady bedtimes this week", TabCopy.regularity(SleepRegularity.Result(null, 2000, 30f)))
        assertEquals("Needs about 5 days of wear", TabCopy.regularity(SleepRegularity.Result(null, 100, null)))
        assertEquals("Usually asleep by 23:10, up at 06:50.", TabCopy.usualTimes(23 * 60 + 10, 6 * 60 + 50))
        assertEquals("Usually asleep by 00:30, up at 07:00.", TabCopy.usualTimes(24 * 60 + 30, 7 * 60))
    }

    @Test fun `breathing disturbances against your usual`() {
        assertEquals("Your usual is 2.1 an hour", TabCopy.breathing(Recovery.BreathingDisturbances(3.4f, 2.1f, 20)))
        assertEquals("Learning your usual, 3 of 7 nights", TabCopy.breathing(Recovery.BreathingDisturbances(3.4f, null, 3)))
    }

    @Test fun `load and its balance`() {
        assertEquals("Your usual is 40", TabCopy.loadUsual(40.4f))
        assertEquals("Learning your usual", TabCopy.loadUsual(null))
        assertEquals("Learning your usual", TabCopy.loadUsual(0f))
        assertEquals("Building", TabCopy.loadBalance(Recovery.LoadTrend(ctl = 40f, atl = 55f, form = -15f)))
        assertEquals("Steady", TabCopy.loadBalance(Recovery.LoadTrend(ctl = 40f, atl = 45f, form = -5f)))
        assertEquals("Easing off", TabCopy.loadBalance(Recovery.LoadTrend(ctl = 40f, atl = 25f, form = 15f)))
        assertNull(TabCopy.loadBalance(Recovery.LoadTrend(0f, 0f, 0f)))
        assertNull(TabCopy.loadBalance(null))
    }

    @Test fun `activity facts`() {
        assertEquals("Last 7 days, goal 150", TabCopy.activeMinutes(90, 150))
        assertEquals("Last 7 days, goal of 150 reached", TabCopy.activeMinutes(160, 150))
        assertEquals("Last 7 days, aim for 100", TabCopy.heartPoints(64, 100))
        assertEquals("Last 7 days, at 100 or more", TabCopy.heartPoints(100, 100))
        assertEquals("Estimated kcal so far today", TabCopy.energy(partial = true, isToday = true))
        assertEquals("Estimated kcal, part of the day worn", TabCopy.energy(partial = true, isToday = false))
        assertEquals("Estimated kcal", TabCopy.energy(partial = false, isToday = false))
    }

    @Test fun `no interpunct, semicolon or em dash in any of it`() {
        val all = buildList {
            add(TabCopy.vsYesterday(78, 75)); add(TabCopy.vsYesterday(70, 75)); add(TabCopy.vsYesterday(75, 75))
            Recovery.Phase.entries.forEach { add(TabCopy.learning(it, 3)) }
            Factor.entries.forEach { f ->
                add(TabCopy.factorName(f))
                add(TabCopy.recovery(60, false, listOf(c(f, 30))))
            }
            listOf(30, 60, 80, 90).forEach { add(TabCopy.contributorWord(it)); add(TabCopy.sleepScoreWord(it)) }
            TabCopy.chargeRows(3f, 2f, 1f, 0f).forEach { add(it.name); add(it.status) }
            add(TabCopy.chargeLine(50, 60, true)); add(TabCopy.chargeLine(null, null, true)); add(TabCopy.chargeLine(null, null, false))
            add(TabCopy.night(25_000, listOf(20_000, 21_000, 22_000))); add(TabCopy.noNight(true, false)); add(TabCopy.noNight(false, false))
            listOf(null, 0, 1, 4).forEach { add(TabCopy.woke(it)) }
            add(TabCopy.regularity(null)); add(TabCopy.regularity(SleepRegularity.Result(60f, 7000, null)))
            add(TabCopy.regularity(SleepRegularity.Result(null, 2000, 90f)))
            add(TabCopy.usualTimes(0, 0)); add(TabCopy.breathing(Recovery.BreathingDisturbances(1f, null, 0)))
            add(TabCopy.loadUsual(null)); add(TabCopy.loadUsual(10f))
            add(TabCopy.activeMinutes(1, 150)); add(TabCopy.heartPoints(1, 100)); add(TabCopy.energy(false, true))
            add(TabCopy.RECOVERY_WAITING)
        }.filterNotNull()
        all.forEach { s -> listOf("·", ";", "—").forEach { bad -> assertFalse("'$s' contains $bad", bad in s) } }
    }

    @Test fun `the Charge Smalls`() {
        assertEquals("Mostly being awake", TabCopy.drainedMostly(movement = 4f, stress = 10f, awake = 22f))
        assertEquals("Mostly movement", TabCopy.drainedMostly(movement = 30f, stress = 10f, awake = 22f))
        assertEquals("Nothing yet", TabCopy.drainedMostly(0f, 0f, 0f))
        assertEquals("Sleep and calm rest", TabCopy.RECHARGED)
    }

    @Test fun `the sleep Smalls`() {
        assertEquals("Usual 2.1, early estimate", TabCopy.breathingShort(Recovery.BreathingDisturbances(2.4f, 2.1f, 20)))
        assertEquals("Learning, 3 of 7 nights", TabCopy.breathingShort(Recovery.BreathingDisturbances(2.4f, null, 3)))
        assertEquals("Nap", TabCopy.napsTitle(1))
        assertEquals("Naps", TabCopy.napsTitle(2))
        assertEquals("At 14:10", TabCopy.naps(1, "14:10"))
        assertEquals("2 naps, first at 14:10", TabCopy.naps(2, "14:10"))
    }

    @Test fun `habit age against last week, the range until there is one`() {
        assertEquals(TabCopy.HabitAgeLine("1 year younger than last week", moved = true), TabCopy.habitAgeLine(29.2f, 30.1f, 27, 31))
        assertEquals(TabCopy.HabitAgeLine("2 years older than last week", moved = true), TabCopy.habitAgeLine(31.4f, 29.4f, 29, 34))
        assertEquals(TabCopy.HabitAgeLine("Same as last week", moved = false), TabCopy.habitAgeLine(29.2f, 28.9f, 27, 31))
        assertEquals(TabCopy.HabitAgeLine("Likely 27 to 31", moved = false), TabCopy.habitAgeLine(29.2f, null, 27, 31))
        val uk = java.util.Locale.UK
        assertEquals("Updated Sun 18 Oct", TabCopy.habitAgeUpdated(java.time.LocalDate.of(2026, 10, 18), uk))
        assertEquals("First estimate Sunday 22 Nov", TabCopy.firstEstimate(java.time.LocalDate.of(2026, 11, 22), uk))
        assertEquals("27 to 31", TabCopy.ageRange(27, 31))
        assertEquals("You, 34", TabCopy.yourAge(34))
    }

    @Test fun `each sport leads with its own fact`() {
        assertEquals(TabCopy.SportFact.Run, TabCopy.sportFact(1))
        assertEquals(TabCopy.SportFact.Run, TabCopy.sportFact(2))
        assertEquals(TabCopy.SportFact.Walk, TabCopy.sportFact(3))
        assertEquals(TabCopy.SportFact.Walk, TabCopy.sportFact(15))
        assertEquals(TabCopy.SportFact.Strength, TabCopy.sportFact(52))
        assertEquals(TabCopy.SportFact.Other, TabCopy.sportFact(4))
        assertEquals(TabCopy.SportFact.Other, TabCopy.sportFact(223))
        // 5.2 km in 28:05 of running: 5:24 /km.
        assertEquals("5.2 km, 5:24 /km", TabCopy.workoutFact(TabCopy.SportFact.Run, 5200f, false, 1685, null, 152))
        assertEquals("3.4 km", TabCopy.workoutFact(TabCopy.SportFact.Walk, 3400f, false, 2530, null, 108))
        assertEquals("14 sets", TabCopy.workoutFact(TabCopy.SportFact.Strength, null, false, 2100, 14, 121))
        assertEquals("1 set", TabCopy.workoutFact(TabCopy.SportFact.Strength, null, false, 300, 1, 121))
        assertEquals("Average 128 bpm", TabCopy.workoutFact(TabCopy.SportFact.Other, null, false, 782, null, 128))
    }

    @Test fun `a workout's fact falls back to the next one it has`() {
        // Distance from steps is approximate and gets no pace.
        assertEquals("≈ 5.2 km", TabCopy.workoutFact(TabCopy.SportFact.Run, 5200f, true, 1685, null, 152))
        // Under 100 m, too short for a pace to mean much. From 100 m it has one, as on the live screen.
        assertEquals("80 m", TabCopy.workoutFact(TabCopy.SportFact.Run, 80f, false, 30, null, 152))
        assertEquals("420 m, 5:57 /km", TabCopy.workoutFact(TabCopy.SportFact.Run, 420f, false, 150, null, 152))
        // A strap run carries no distance: its heart rate.
        assertEquals("Average 152 bpm", TabCopy.workoutFact(TabCopy.SportFact.Run, null, false, 1685, null, 152))
        assertEquals("Average 108 bpm", TabCopy.workoutFact(TabCopy.SportFact.Walk, null, false, 2530, null, 108))
        assertEquals("Average 121 bpm", TabCopy.workoutFact(TabCopy.SportFact.Strength, null, false, 2100, 0, 121))
        // A ride with GPS but no heart rate says its distance.
        assertEquals("21.3 km", TabCopy.workoutFact(TabCopy.SportFact.Other, 21300f, false, 3000, null, null))
        assertNull(TabCopy.workoutFact(TabCopy.SportFact.Other, null, false, 600, null, null))
        assertNull(TabCopy.workoutFact(TabCopy.SportFact.Walk, 5f, false, 600, null, 0))
    }

    @Test fun `distance and pace`() {
        assertEquals("850 m", TabCopy.distanceText(850f, false))
        assertEquals("≈ 1.0 km", TabCopy.distanceText(1000f, true))
        assertEquals("4:05 /km", TabCopy.paceText(245f))
        assertEquals("10:00 /km", TabCopy.paceText(600f))
        assertNull(TabCopy.paceText(4000f))
        assertNull(TabCopy.paceText(0f))
    }

    @Test fun `a workout row's line ends with when it started`() {
        assertEquals("3.4 km, 17:35", TabCopy.workoutLine("3.4 km", "17:35"))
        assertEquals("09:04", TabCopy.workoutLine(null, "09:04"))
    }

    @Test fun `workout days read as today, yesterday, the weekday, then the date`() {
        val today = java.time.LocalDate.of(2026, 10, 23)
        val uk = java.util.Locale.UK
        assertEquals("Today", TabCopy.workoutDay(today, today, uk))
        assertEquals("Yesterday", TabCopy.workoutDay(today.minusDays(1), today, uk))
        assertEquals("Wednesday", TabCopy.workoutDay(today.minusDays(2), today, uk))
        assertEquals("Fri 16 Oct", TabCopy.workoutDay(today.minusDays(7), today, uk))
        assertEquals("Sat 27 Dec 2025", TabCopy.workoutDay(java.time.LocalDate.of(2025, 12, 27), today, uk))
    }

    @Test fun `workout copy never uses the banned punctuation`() {
        val lines = listOf(
            TabCopy.AUTO_DETECTED, TabCopy.workoutFact(TabCopy.SportFact.Run, 5200f, false, 1685, null, 152)!!,
            TabCopy.workoutLine("14 sets", "07:10"), TabCopy.workoutDay(java.time.LocalDate.of(2026, 10, 21), java.time.LocalDate.of(2026, 10, 23)),
        )
        lines.forEach { l -> listOf("·", ";", "—").forEach { assertFalse("$l has $it", l.contains(it)) } }
    }

    @Test fun `before Habit Age is learned your age stands in`() {
        assertEquals("Your age for now", TabCopy.habitAgeForNow(null))
        assertEquals("Your age for now. First estimate Sunday 1 Nov", TabCopy.habitAgeForNow("First estimate Sunday 1 Nov"))
    }

    @Test fun `numbers and pace follow the phone's language`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("5,2 km, 5:24 /km", TabCopy.workoutFact(TabCopy.SportFact.Run, 5200f, false, 1685, null, 152))
        assertEquals("≈ 1,0 km", TabCopy.distanceText(1000f, true))
        assertEquals("Your usual is 2,1 an hour", TabCopy.breathing(Recovery.BreathingDisturbances(3.4f, 2.1f, 20)))
    }

    @Test fun `the night's times and usual times follow the phone's clock`() {
        val zone = java.time.ZoneOffset.UTC
        val start = java.time.LocalDateTime.of(2026, 10, 22, 22, 58).toEpochSecond(zone)
        val end = java.time.LocalDateTime.of(2026, 10, 23, 6, 56).toEpochSecond(zone)
        val was = ClockSetting.use24Hour
        try {
            ClockSetting.use24Hour = true
            assertEquals("22:58 to 06:56", TabCopy.nightTimes(start, end, zone))
            assertEquals("Usually asleep by 23:10, up at 06:50.", TabCopy.usualTimes(23 * 60 + 10, 6 * 60 + 50))
            ClockSetting.use24Hour = false
            assertEquals("10:58 PM to 6:56 AM", TabCopy.nightTimes(start, end, zone))
            assertEquals("Usually asleep by 11:10 PM, up at 6:50 AM.", TabCopy.usualTimes(23 * 60 + 10, 6 * 60 + 50))
            assertCleanCopy(TabCopy.usualTimes(23 * 60 + 10, 6 * 60 + 50))
        } finally {
            ClockSetting.use24Hour = was
        }
    }

    @Test fun `the bands come from the shared definitions`() {
        assertEquals(listOf("Poor", "Fair", "Good", "Great"), listOf(59, 60, 80, 90).map(TabCopy::sleepScoreWord))
        assertEquals(listOf("Holding you back", "Holding back a little", "Usual", "Helping"), listOf(10, Recovery.FAIR_FROM, 70, 85).map(TabCopy::contributorWord))
    }
}
