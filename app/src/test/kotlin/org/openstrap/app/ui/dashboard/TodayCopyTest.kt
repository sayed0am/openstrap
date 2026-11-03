package org.openstrap.app.ui.dashboard

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.testing.assertCleanCopy
import org.openstrap.app.presentation.LoadBand
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.TodayCopy

/** Today's hero sentences and small facts (spec 4.2, 4.3, 7). */
class TodayCopyTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(h: Int, m: Int = 0, day: Int = 23) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toEpochSecond()

    @Test fun `durations read like the mockups`() {
        assertEquals("40 min", Units.duration(40 * 60L))
        assertEquals("7 h 40", Units.duration((7 * 60 + 40) * 60L))
        assertEquals("1 h 05", Units.duration(65 * 60L))
        assertEquals("8 h", Units.duration(8 * 3600L))
        assertEquals("0 min", Units.duration(-5))
    }

    private val learning = TodayCopy.NoScore.Learning(5, 7)

    @Test fun `waking says the night then what Recovery means`() {
        val slept = (7 * 60 + 40) * 60L
        assertEquals("Slept 7 h 40. Recovered enough for your usual training.", TodayCopy.waking(slept, 78, false, null, false, true))
        assertEquals("Slept 7 h 40. Well recovered. A good day for a hard session.", TodayCopy.waking(slept, 90, false, null, false, true))
        assertEquals("Slept 7 h 40. Some overnight signals are off your normal. An easy day suits.", TodayCopy.waking(slept, 90, true, null, false, true))
    }

    @Test fun `waking falls back honestly`() {
        assertEquals("No sleep recorded last night. Was the strap on?", TodayCopy.waking(null, null, false, null, false, true))
        assertEquals("No sleep recorded that night.", TodayCopy.waking(null, null, false, null, false, false))
        assertEquals("Recovery starts after your first night with the strap.", TodayCopy.waking(null, null, false, learning, true, true))
        val slept = (5 * 60 + 32) * 60L
        assertEquals("Slept 5 h 32. OpenStrap is still learning your nights, 5 of 7.", TodayCopy.waking(slept, null, false, learning, false, true))
        assertEquals(
            "Slept 5 h 32. Not enough heart data last night for a Recovery score.",
            TodayCopy.waking(slept, null, false, TodayCopy.NoScore.NoHeartData, false, true),
        )
        // Still loading: only the night.
        assertEquals("Slept 5 h 32.", TodayCopy.waking(slept, null, false, null, false, true))
    }

    @Test fun `recovery shows the score, else the strap's estimate, else why not`() {
        assertEquals(TodayCopy.RecoveryShown(78, "Ready", "Recovered enough for your usual training."), TodayCopy.recoveryShown(78, false, null, 90, "No reading yet"))
        assertEquals(
            TodayCopy.RecoveryShown(90, "Strap's estimate", "OpenStrap is still learning your nights, 5 of 7.", estimate = true),
            TodayCopy.recoveryShown(null, false, learning, 90, "No reading yet"),
        )
        assertEquals(TodayCopy.RecoveryShown(null, null, "OpenStrap is still learning your nights, 5 of 7."), TodayCopy.recoveryShown(null, false, learning, null, "No reading yet"))
        assertEquals(TodayCopy.RecoveryShown(null, null, "No reading yet"), TodayCopy.recoveryShown(null, false, null, null, "No reading yet"))
    }

    @Test fun `recovery words follow the bands`() {
        assertEquals("Ready for more", TodayCopy.recoveryWord(85, false))
        assertEquals("Ready", TodayCopy.recoveryWord(70, false))
        assertEquals("Moderate", TodayCopy.recoveryWord(55, false))
        assertEquals("Take it easy", TodayCopy.recoveryWord(54, false))
        assertEquals("Take it easy", TodayCopy.recoveryWord(95, true))
    }

    @Test fun `charge says what the day took and what's left`() {
        assertEquals("The morning drained 22. Plenty left for this evening.", TodayCopy.charge(62, 22, true, 11))
        assertEquals("The day so far drained 40. Enough left for an easy evening.", TodayCopy.charge(44, 40, true, 15))
        assertEquals("Nothing drained since waking. Running low. An early night helps.", TodayCopy.charge(20, 0, true, 15))
        assertEquals("Plenty left for this evening.", TodayCopy.charge(80, null, true, 13))
        assertEquals("Energy starts after your first night. Sleep is what fills it.", TodayCopy.charge(80, 80, false, 13))
        assertEquals("Energy starts after your first night. Sleep is what fills it.", TodayCopy.charge(null, null, true, 13))
    }

    @Test fun `workout window names the usual sport and the charge`() {
        assertEquals("Ready for effort", TodayCopy.windowTitle(78))
        assertEquals("Fine for a steady session", TodayCopy.windowTitle(60))
        assertEquals("An easy session suits today", TodayCopy.windowTitle(40))
        assertEquals("Your usual workout time", TodayCopy.windowTitle(null))
        assertEquals("You usually run around now. Energy is 58, plenty left.", TodayCopy.window("run", 58))
        assertEquals("You usually work out around now.", TodayCopy.window(null, null))
        assertEquals("You usually ride around now. Energy is 20, running low.", TodayCopy.window("ride", 20))
    }

    @Test fun `after a workout the heart settles toward resting`() {
        assertEquals("Heart rate settling.", TodayCopy.after(74, 52))
        assertEquals("Heart rate back near resting.", TodayCopy.after(62, 52))
        assertEquals("Heart rate 74 now.", TodayCopy.after(74, null))
        assertEquals("Heart rate since the end shows once the strap sends it.", TodayCopy.after(null, 52))
    }

    @Test fun `load is only low at the end of the day`() {
        assertEquals(LoadBand.Above, TodayCopy.loadStatus(60f, 40f, endOfDay = false))
        assertEquals(LoadBand.SoFar, TodayCopy.loadStatus(10f, 40f, endOfDay = false))
        assertEquals(LoadBand.Below, TodayCopy.loadStatus(10f, 40f, endOfDay = true))
        assertEquals(LoadBand.Usual, TodayCopy.loadStatus(45f, 40f, endOfDay = true))
        assertNull(TodayCopy.loadStatus(45f, null, endOfDay = true))
    }

    @Test fun `the load line says it's a Load`() {
        assertEquals("Load, learning your usual", TodayCopy.loadLine(null))
        assertEquals("Load above your usual", TodayCopy.loadLine(LoadBand.Above))
        assertEquals("Load so far today", TodayCopy.loadLine(LoadBand.SoFar))
        assertEquals("Your usual Load", TodayCopy.loadLine(LoadBand.Usual))
        assertEquals("Load below your usual", TodayCopy.loadLine(LoadBand.Below))
    }

    @Test fun `evening tells the day`() {
        assertEquals(
            "A full day. A run, 13,860 steps and a Load above your usual.",
            TodayCopy.evening(listOf("run"), 13_860, 8_000, LoadBand.Above, Locale.UK),
        )
        assertEquals("A quiet day. 2,100 steps and a Load below your usual.", TodayCopy.evening(emptyList(), 2_100, 8_000, LoadBand.Below, Locale.UK))
        assertEquals("A steady day. 2 workouts and 6,000 steps.", TodayCopy.evening(listOf("run", null), 6_000, 8_000, null, Locale.UK))
        assertEquals("A steady day. A workout and 6,000 steps.", TodayCopy.evening(listOf(null), 6_000, 8_000, LoadBand.SoFar, Locale.UK))
        // The app's hero says the steps against the goal, since it shows the count in dots.
        assertEquals("A quiet day. Well short of your step goal and a Load below your usual.", TodayCopy.evening(emptyList(), 2_100, 8_000, LoadBand.Below, Locale.UK, stepCount = false))
        assertEquals("A full day. A run, past your step goal and a Load above your usual.", TodayCopy.evening(listOf("run"), 13_860, 8_000, LoadBand.Above, Locale.UK, stepCount = false))
    }

    @Test fun `bedtime counts down and stops once passed`() {
        assertEquals("Bedtime in 1 h 10", TodayCopy.bedtimeIn(LocalTime.of(21, 50), LocalTime.of(23, 0)))
        assertEquals("Bedtime in 30 min", TodayCopy.bedtimeIn(LocalTime.of(23, 50), LocalTime.of(0, 20)))
        assertNull(TodayCopy.bedtimeIn(LocalTime.of(23, 10), LocalTime.of(23, 0)))
        assertNull(TodayCopy.bedtimeIn(LocalTime.of(21, 0), null))
    }

    @Test fun `time to recover counts down to a clock time or tomorrow`() {
        val end = at(17, 40)
        assertEquals(3600L, TodayCopy.recoverLeft(end, 120, at(18, 40)))
        assertEquals(0L, TodayCopy.recoverLeft(end, 60, at(19, 0)))
        assertEquals(null to "Recovered", TodayCopy.recoverText(0, at(19), zone))
        assertEquals("2" to "Hours left, ready by 20:30", TodayCopy.recoverText(90 * 60L, at(19), zone))
        assertEquals("1" to "Hour left, ready by 19:30", TodayCopy.recoverText(30 * 60L, at(19), zone))
        assertEquals("14" to "Hours left, ready tomorrow", TodayCopy.recoverText(14 * 3600L, at(18), zone))
    }

    @Test fun `your week is the last full week, monday to sunday where the week starts on monday`() {
        // 23 Oct 2026 is a Friday.
        val monday = java.time.DayOfWeek.MONDAY
        assertEquals(LocalDate.of(2026, 10, 12)..LocalDate.of(2026, 10, 18), TodayCopy.lastWeek(LocalDate.of(2026, 10, 23), monday))
        assertEquals(LocalDate.of(2026, 10, 12)..LocalDate.of(2026, 10, 18), TodayCopy.lastWeek(LocalDate.of(2026, 10, 19), monday))
        assertEquals(LocalDate.of(2026, 10, 12)..LocalDate.of(2026, 10, 18), TodayCopy.lastWeek(LocalDate.of(2026, 10, 25), monday))
    }

    @Test fun `your week follows the locale's first day`() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            // In the US the week starts on Sunday: the week before Friday 23 Oct is Sunday 11 to Saturday 17.
            assertEquals(LocalDate.of(2026, 10, 11)..LocalDate.of(2026, 10, 17), TodayCopy.lastWeek(LocalDate.of(2026, 10, 23)))
            assertEquals("Week on week comparisons start next Sunday.", TodayCopy.WEEK_FIRST)
            Locale.setDefault(Locale.UK)
            assertEquals(LocalDate.of(2026, 10, 12)..LocalDate.of(2026, 10, 18), TodayCopy.lastWeek(LocalDate.of(2026, 10, 23)))
            assertEquals("Week on week comparisons start next Monday.", TodayCopy.WEEK_FIRST)
        } finally {
            Locale.setDefault(was)
        }
    }

    @Test fun `ready by follows the phone's clock`() {
        val was = org.openstrap.app.presentation.ClockSetting.use24Hour
        try {
            org.openstrap.app.presentation.ClockSetting.use24Hour = false
            assertEquals("2" to "Hours left, ready by 8:30 PM", TodayCopy.recoverText(90 * 60L, at(19), zone, Locale.US))
            assertCleanCopy(TodayCopy.recoverText(90 * 60L, at(19), zone, Locale.US).second)
        } finally {
            org.openstrap.app.presentation.ClockSetting.use24Hour = was
        }
    }

    @Test fun `steps change and last night line`() {
        assertEquals("Steps up 10%", TodayCopy.stepsChange(1100.0, 1000.0))
        assertEquals("Steps held steady", TodayCopy.stepsChange(1001.0, 1000.0))
        assertNull(TodayCopy.stepsChange(1000.0, null))
        assertEquals("7 h 40, score 83", TodayCopy.lastNight((7 * 60 + 40) * 60L, 83))
        assertEquals("6 h", TodayCopy.lastNight(6 * 3600L, null))
        assertNull(TodayCopy.lastNight(null, 83))
    }

    @Test fun `no sentence carries the forbidden marks`() {
        val all = listOf(
            TodayCopy.waking(1000, 80, false, null, false, true), TodayCopy.waking(1000, null, false, learning, false, true),
            TodayCopy.WEEK_FIRST, TodayCopy.recoverText(5000, at(10), zone).second, TodayCopy.charge(50, 30, true, 10),
            TodayCopy.window("run", 50), TodayCopy.after(80, 50), TodayCopy.evening(listOf("run"), 9000, 8000, LoadBand.Usual, Locale.UK),
        )
        assertCleanCopy(all)
    }

    @Test fun `values read against your normal`() {
        assertEquals("Above your normal", TodayCopy.normalWord(1))
        assertEquals("Below your normal", TodayCopy.normalWord(-1))
        assertEquals("Usual", TodayCopy.normalWord(0))
        assertEquals("Learning your normal", TodayCopy.normalWord(null))
    }

    @Test fun `the after workout Smalls say a number and a word`() {
        assertEquals("Above your usual", TodayCopy.loadWord(LoadBand.Above))
        assertEquals("So far today", TodayCopy.loadWord(LoadBand.SoFar))
        assertEquals("Learning your usual", TodayCopy.loadWord(null))
        val zone = ZoneId.of("Europe/London")
        val now = ZonedDateTime.of(2026, 10, 23, 18, 0, 0, 0, zone).toEpochSecond()
        assertEquals("14" to "Ready tomorrow", TodayCopy.recoverShort(14 * 3600L, now, zone))
        assertEquals("2" to "Ready by 20:00", TodayCopy.recoverShort(2 * 3600L, now, zone))
        assertEquals(null to "Recovered", TodayCopy.recoverShort(0, now, zone))
    }

    @Test fun `the heart rate card says how to read its line`() {
        assertEquals("Hold to scrub, the number follows. The band is your resting range.", TodayCopy.heartLineNote(band = true))
        assertEquals("Hold to scrub, the number follows.", TodayCopy.heartLineNote(band = false))
        assertEquals("Live now", TodayCopy.heartAt(live = true, at = "20:52"))
        assertEquals("At 20:52", TodayCopy.heartAt(live = false, at = "20:52"))
        assertEquals("Waiting for the strap", TodayCopy.heartAt(live = false, at = null))
    }

    @Test fun `new lines keep to the copy rules`() {
        val zone = ZoneId.of("Europe/London")
        val lines = listOf(
            TodayCopy.heartLineNote(true), TodayCopy.heartLineNote(false), TodayCopy.loadWord(null),
            TodayCopy.recoverShort(3600L * 30, 0, zone).second, TodayCopy.heartAt(false, "07:00"),
        )
        assertCleanCopy(lines)
    }

    @Test
    fun `steps against the goal read as words`() {
        assertEquals("well short of your step goal", TodayCopy.stepsAgainstGoal(3_999, 8_000))
        assertEquals("short of your step goal", TodayCopy.stepsAgainstGoal(4_000, 8_000))
        assertEquals("close to your step goal", TodayCopy.stepsAgainstGoal(6_400, 8_000))
        assertEquals("past your step goal", TodayCopy.stepsAgainstGoal(8_000, 8_000))
    }
}
