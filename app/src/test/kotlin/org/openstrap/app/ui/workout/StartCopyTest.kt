package org.openstrap.app.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.openstrap.app.data.SportSettings
import org.openstrap.app.data.Strength
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.db.Workout
import org.openstrap.app.device.ConnectionState
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class StartCopyTest {
    private val today = LocalDate.of(2026, 10, 30)

    @Test fun `the strap line says it's ready, or what's wrong`() {
        assertEquals("Strap ready, 72 bpm", StartCopy.strapLine(ConnectionState.Connected(80, null), 72))
        assertEquals("Strap ready", StartCopy.strapLine(ConnectionState.Connected(80, null), null))
        assertEquals("Strap not connected. You can start anyway.", StartCopy.strapLine(ConnectionState.Disconnected, null))
    }

    @Test fun `no goal is named for the sport`() {
        assertEquals("Just run", StartCopy.noGoal(1))
        assertEquals("Just run", StartCopy.noGoal(2))
        assertEquals("Just walk", StartCopy.noGoal(15))
        assertEquals("Just ride", StartCopy.noGoal(8))
        assertEquals("Just go", StartCopy.noGoal(9))
    }

    @Test fun `the plan and the recent cards say last time's settings`() {
        assertEquals("5 sets, 90 s rest, or sooner once you're under 110 bpm", StartCopy.plan(5, Strength.Rest(90, 110)))
        assertEquals("Open sets, 2 min rest", StartCopy.plan(null, Strength.Rest(120, null)))
        assertEquals("2 min 15 s", StartCopy.restText(135))
        assertEquals("5 sets, 90 s rest", StartCopy.settingsLine(52, SportSettings(sets = 5, rest = "r:90:0")))
        assertEquals("45 min", StartCopy.settingsLine(60, SportSettings(goal = "t:2700")))
        assertEquals("Open", StartCopy.settingsLine(60, SportSettings()))
        assertEquals("5 km", StartCopy.settingsLine(1, SportSettings(goal = "d:5000")))
        assertEquals("Just run", StartCopy.settingsLine(1, SportSettings()))
    }

    @Test fun `last time says what and when`() {
        val tuesday = LocalDate.of(2026, 10, 27).atTime(18, 0).toEpochSecond(ZoneOffset.UTC)
        val run = WorkoutAnalysis(Workout(tuesday, tuesday + 1800, 1, 1800, null, null, null, null, null, null, null, null, null),
            tuesday + 1800, emptyList(), null, null, null, null, List(5) { 0 }, 190, fromPhone = true, distanceMeters = 5210f)
        assertEquals("Last time 5.21 km, Tuesday", StartCopy.lastTime(run, today, ZoneOffset.UTC))
        val lift = run.copy(workout = run.workout.copy(sportType = 52), distanceMeters = null, sets = 5)
        assertEquals("Last time 5 sets, Tuesday", StartCopy.lastTime(lift, today, ZoneOffset.UTC))
        val yoga = run.copy(workout = run.workout.copy(sportType = 60), distanceMeters = null)
        assertEquals("Last time 30 min, Tuesday", StartCopy.lastTime(yoga, today, ZoneOffset.UTC))
    }

    @Test fun `days are today, yesterday, a weekday, then a date`() {
        assertEquals("today", StartCopy.dayWord(today, today, Locale.UK))
        assertEquals("yesterday", StartCopy.dayWord(today.minusDays(1), today, Locale.UK))
        assertEquals("Saturday", StartCopy.dayWord(today.minusDays(6), today, Locale.UK))
        assertEquals("23 Oct", StartCopy.dayWord(today.minusDays(7), today, Locale.UK))
    }

    @Test fun `GPS says how close the fix is`() {
        assertEquals("Finding GPS", StartCopy.gpsStatus(null))
        assertEquals("Finding GPS, ±48 m", StartCopy.gpsStatus(48f))
        assertEquals("GPS ready", StartCopy.gpsStatus(8f))
    }

    @Test fun `what the strap will do, without banned punctuation`() {
        val goals = listOf(null, WorkoutTarget.Time(1800), WorkoutTarget.Distance(5000), WorkoutTarget.Kcal(300), WorkoutTarget.Zones(1, 2))
        assertEquals("The strap buzzes if you drift out of Zone 2 to 3.", StartCopy.goalStatus(WorkoutTarget.Zones(1, 2)))
        val all = goals.map { StartCopy.goalStatus(it) } + StartCopy.STRENGTH_STATUS + StartCopy.GAMES_STATUS + StartCopy.GPS_OFF
        all.forEach { s -> assertFalse(s, s.any { it in "·;—–" }) }
    }
}
