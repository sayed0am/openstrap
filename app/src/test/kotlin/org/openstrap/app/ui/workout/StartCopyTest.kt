package org.openstrap.app.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
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

    @Test fun `strength's plan says sets and rest`() {
        assertEquals("5 sets, 90 s rest, or sooner once you're under 110 bpm", StartCopy.plan(5, Strength.Rest(90, 110)))
        assertEquals("Open sets, 2 min rest", StartCopy.plan(null, Strength.Rest(120, null)))
        assertEquals("2 min 15 s", StartCopy.restText(135))
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

    @Test fun `rounds are named by their preset, else by what they are`() {
        assertEquals("Tabata", StartCopy.structureName(org.openstrap.app.data.Structure.TABATA))
        assertEquals("Tabata", StartCopy.structureName(org.openstrap.app.data.Structure.TABATA.warm(true)))
        assertEquals("40 / 20", StartCopy.structureName(org.openstrap.app.data.Structure.FORTY_TWENTY))
        assertEquals("6 × 400 m", StartCopy.structureName(org.openstrap.app.data.Structure.Reps(400, null, 90, 6)))
        assertEquals("8 × 2 min", StartCopy.structureName(org.openstrap.app.data.Structure.Reps(null, 120, 60, 8)))
        assertEquals(230L, StartCopy.totalTime(org.openstrap.app.data.Structure.TABATA))
        // Reps fit a run, rounds fit HIIT, never the other way round.
        assertEquals(true, org.openstrap.app.data.Structure.fits(org.openstrap.app.data.Structure.Reps(400, null, 90, 6), 2))
        assertEquals(false, org.openstrap.app.data.Structure.fits(org.openstrap.app.data.Structure.TABATA, 1))
        assertEquals(true, org.openstrap.app.data.Structure.fits(org.openstrap.app.data.Structure.TABATA, 49))
    }

    @Test fun `GPS says how close the fix is`() {
        assertEquals("Finding GPS", StartCopy.gpsStatus(null))
        assertEquals("Finding GPS, ±48 m", StartCopy.gpsStatus(48f))
        assertEquals("GPS ready, 8 m", StartCopy.gpsStatus(8f))
        assertEquals("GPS ready, 6 m", StartCopy.gpsStatus(6.4f))
    }

    @Test fun `what the strap will do, without banned punctuation`() {
        val reps = org.openstrap.app.data.Structure.Reps(400, null, 90, 6)
        val all = listOf(
            StartCopy.STRENGTH_STATUS, StartCopy.GAMES_STATUS, StartCopy.noGoalNote(true), StartCopy.distanceNote(7500),
            StartCopy.endsNear(java.time.LocalTime.NOON, 1800, true), StartCopy.ZONE_NOTE, StartCopy.intervalsNote(reps),
            StartCopy.zoneRangeLine(WorkoutTarget.Zones(1, 2), org.openstrap.app.data.HeartZones.STANDARD, 190),
            StartCopy.INTERVALS_TYPING, StartCopy.ASK_TO_END_NOTE, StartCopy.SAVE_NOTE, StartCopy.indoorNote(8),
            HeartZonesCopy.NOTE, HeartZonesCopy.SET_NOTE, HeartZonesCopy.edgesNote(0), HeartZonesCopy.edgesNote(4),
            HeartZonesCopy.source(org.openstrap.app.data.MaxHeartRate(190, org.openstrap.app.data.MaxHeartRate.Source.Guess)),
        )
        all.forEach { s -> assertFalse(s, s.any { it in "·;—–" }) }
    }

    @Test fun `Ready's where row names the side you're on and the other one`() {
        val run = org.openstrap.app.data.Workouts.sidesOf(1)!!
        assertEquals("Outside", StartCopy.place(run, indoor = false))
        assertEquals("On a treadmill", StartCopy.place(run, indoor = true))
        assertEquals("Treadmill", StartCopy.otherPlace(run, indoor = false))
        assertEquals("Outside", StartCopy.otherPlace(run, indoor = true))
        val ride = org.openstrap.app.data.Workouts.sidesOf(4)!!
        assertEquals("Indoors", StartCopy.place(ride, indoor = true))
        assertEquals("Indoors", StartCopy.otherPlace(ride, indoor = false))
        val swim = org.openstrap.app.data.Workouts.sidesOf(6)!!
        assertEquals("In a pool", StartCopy.place(swim, indoor = true))
        assertEquals("Open water", StartCopy.otherPlace(swim, indoor = true))
        assertEquals("Pace from your steps", StartCopy.indoorNote(2))
        assertEquals("Distance from your steps", StartCopy.indoorNote(40))
        assertEquals("Time and your heart, no distance", StartCopy.indoorNote(8))
        assertEquals("Start treadmill run", StartCopy.startLabel(2))
        assertEquals("Start run", StartCopy.startLabel(1))
    }

    @Test fun `a distance reads as typed, steps by half a km and parses what's typed`() {
        assertEquals("5", StartCopy.distanceValue(5000))
        assertEquals("7.5", StartCopy.distanceValue(7500))
        assertEquals("12.45", StartCopy.distanceValue(12_450))
        assertEquals("21.1", StartCopy.distanceValue(StartCopy.HALF_MARATHON))
        assertEquals("The strap buzzes at halfway and at 7.5 km.", StartCopy.distanceNote(7500))
        assertEquals(8000, StartCopy.stepDistance(7500, up = true))
        assertEquals(7000, StartCopy.stepDistance(7500, up = false))
        assertEquals(12_500, StartCopy.stepDistance(12_400, up = true))
        assertEquals(12_000, StartCopy.stepDistance(12_400, up = false))
        assertEquals(500, StartCopy.stepDistance(500, up = false))
        assertEquals(12_400, StartCopy.parseDistance("12.4"))
        assertEquals(12_400, StartCopy.parseDistance("12,4"))
        assertEquals(10_000, StartCopy.parseDistance("10"))
        assertEquals(null, StartCopy.parseDistance(""))
        assertEquals(null, StartCopy.parseDistance("0"))
        assertEquals(null, StartCopy.parseDistance("1000"))
        assertEquals(listOf("3", "5", "10", "Half"), StartCopy.DISTANCE_PRESETS.map { it.second })
    }

    @Test fun `a time reads in hours and minutes, steps by 5 minutes and parses two fields`() {
        assertEquals("1 h 15 min", StartCopy.timeValue(4500))
        assertEquals("45 min", StartCopy.timeValue(2700))
        assertEquals("1 h", StartCopy.timeValue(3600))
        assertEquals(listOf("1" to "h", "15" to "min"), StartCopy.timeParts(4500))
        assertEquals(4800L, StartCopy.stepTime(4500, up = true))
        assertEquals(4200L, StartCopy.stepTime(4500, up = false))
        assertEquals(4500L, StartCopy.stepTime(4380, up = true))
        assertEquals(300L, StartCopy.stepTime(300, up = false))
        assertEquals(4500L, StartCopy.parseTime("1", "15"))
        assertEquals(2700L, StartCopy.parseTime("", "45"))
        assertEquals(5400L, StartCopy.parseTime("0", "90"))
        assertEquals(7200L, StartCopy.parseTime("2", ""))
        assertEquals(null, StartCopy.parseTime("", ""))
        assertEquals(null, StartCopy.parseTime("11", "0"))
        assertEquals(null, StartCopy.parseTime("1", "x"))
        assertEquals(listOf("30 min", "45 min", "1 h", "1 h 30"), StartCopy.TIME_PRESETS.map { it.second })
        assertEquals("Ends near 19:15", StartCopy.endsNear(java.time.LocalTime.of(18, 0), 4500, is24Hour = true))
        assertEquals("Type hours, then minutes. Ends near 19:15", StartCopy.timeTypingNote("Ends near 19:15"))
    }

    @Test fun `a zone goal reads its heart rates and names from your zones`() {
        val zones = org.openstrap.app.data.HeartZones.STANDARD.withStart(1, 115, 192).withStart(2, 134, 192).withStart(3, 153, 192)
        assertEquals("115 to 152 bpm, aerobic to tempo", StartCopy.zoneRangeLine(WorkoutTarget.Zones(1, 2), zones, 192))
        assertEquals("115 to 133 bpm, aerobic", StartCopy.zoneRangeLine(WorkoutTarget.Zones(1, 1), zones, 192))
        assertEquals("173 bpm and up, max", StartCopy.zoneRangeLine(WorkoutTarget.Zones(4, 4), zones, 192))
        assertEquals("153 bpm and up, threshold to max", StartCopy.zoneRangeLine(WorkoutTarget.Zones(3, 4), zones, 192))
    }

    @Test fun `run intervals' presets, parts, note and typing`() {
        assertEquals(listOf("8 × 200 m", "6 × 400 m", "5 × 1 km", "4 × 4 min"), StartCopy.INTERVAL_PRESETS.map { StartCopy.structureName(it) })
        val reps = org.openstrap.app.data.Structure.Reps(400, null, 90, 6)
        assertEquals("400" to "m", StartCopy.repParts(reps))
        assertEquals("1.5" to "km", StartCopy.repParts(reps.copy(meters = 1500)))
        assertEquals("4" to "min", StartCopy.repParts(reps.copy(meters = null, seconds = 240)))
        assertEquals("90" to "s", StartCopy.repParts(reps.copy(meters = null, seconds = 90)))
        assertEquals("90" to "s", StartCopy.restParts(90))
        assertEquals("2" to "min", StartCopy.restParts(120))
        assertEquals("150" to "s", StartCopy.restParts(150))
        assertEquals("The strap buzzes at the end of each 400 m and when rest is up. 2.4 km in all.", StartCopy.intervalsNote(reps))
        assertEquals("The strap buzzes at the end of each 200 m and when rest is up. 800 m in all.", StartCopy.intervalsNote(reps.copy(meters = 200, rounds = 4)))
        assertEquals(
            "The strap buzzes at the end of each 4 min rep and when rest is up. 16 min of reps in all.",
            StartCopy.intervalsNote(org.openstrap.app.data.Structure.Reps(null, 240, 180, 4)),
        )
        assertEquals(8, StartCopy.parseCount("8"))
        assertEquals(null, StartCopy.parseCount("0"))
        assertEquals(null, StartCopy.parseCount("31"))
        assertEquals(reps.copy(meters = 800), StartCopy.parseRep("800", StartCopy.RepUnit.Meters, reps))
        assertEquals(reps.copy(meters = 1500), StartCopy.parseRep("1.5", StartCopy.RepUnit.Kilometers, reps))
        assertEquals(reps.copy(meters = null, seconds = 150), StartCopy.parseRep("2.5", StartCopy.RepUnit.Minutes, reps))
        assertEquals(null, StartCopy.parseRep("", StartCopy.RepUnit.Meters, reps))
        assertEquals(null, StartCopy.parseRep("10", StartCopy.RepUnit.Meters, reps))
        assertEquals(StartCopy.RepUnit.Kilometers, StartCopy.repUnit(reps.copy(meters = 1000)))
        assertEquals(StartCopy.RepUnit.Minutes, StartCopy.repUnit(reps.copy(meters = null, seconds = 240)))
        assertEquals(90L, StartCopy.parseRest("90"))
        assertEquals(0L, StartCopy.parseRest("0"))
        assertEquals(null, StartCopy.parseRest("1000"))
    }

    @Test fun `no goal says what's recorded`() {
        assertEquals("No goal. Time, distance and your heart.", StartCopy.noGoalNote(hasDistance = true))
        assertEquals("No goal. Time and your heart.", StartCopy.noGoalNote(hasDistance = false))
    }

    @Test fun `the zones page says where your max comes from and each zone's heart rates`() {
        val d = LocalDate.of(2026, 10, 3)
        assertEquals("Learned from your hardest efforts, 3 Oct", HeartZonesCopy.source(org.openstrap.app.data.MaxHeartRate(192, org.openstrap.app.data.MaxHeartRate.Source.Learned, d), Locale.UK))
        assertEquals("Set by you", HeartZonesCopy.source(org.openstrap.app.data.MaxHeartRate(185, org.openstrap.app.data.MaxHeartRate.Source.Set)))
        val zones = org.openstrap.app.data.HeartZones.STANDARD
        assertEquals("96 to 115", HeartZonesCopy.zoneBpm(zones, 0, 192))
        assertEquals("173 and up", HeartZonesCopy.zoneBpm(zones, 4, 192))
        assertEquals("Zone 2", HeartZonesCopy.zoneName(1))
        assertEquals(185, HeartZonesCopy.parseMax("185"))
        assertEquals(null, HeartZonesCopy.parseMax("100"))
    }
}
