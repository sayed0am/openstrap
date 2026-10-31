package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Workouts.Family

class WorkoutSetupTest {
    @Test
    fun everySportHasOneFamily() {
        // Every id, kept or hidden, maps to a family without throwing; unknown ids too.
        Workouts.SPORT_IDS.forEach { Workouts.family(it) }
        assertEquals(Family.Distance, Workouts.family(1))
        assertEquals(Family.Distance, Workouts.family(3))
        assertEquals(Family.Distance, Workouts.family(4))
        assertEquals(Family.Distance, Workouts.family(15))
        assertEquals(Family.IndoorCardio, Workouts.family(2))
        assertEquals(Family.IndoorCardio, Workouts.family(8))
        assertEquals(Family.IndoorCardio, Workouts.family(9))
        assertEquals(Family.IndoorCardio, Workouts.family(23))
        assertEquals(Family.IndoorCardio, Workouts.family(40))
        assertEquals(Family.IndoorCardio, Workouts.family(5))
        Strength.SPORTS.forEach { assertEquals(Family.Strength, Workouts.family(it)) }
        assertEquals(Family.Intervals, Workouts.family(49))
        listOf(53, 55, 60, 61).forEach { assertEquals(Family.MindBody, Workouts.family(it)) }
        listOf(17, 18, 85, 205, 204).forEach { assertEquals(Family.Games, Workouts.family(it)) }
    }

    @Test
    fun sportsNotListedFallBackByTheirSides() {
        // Swims have an indoor side: indoor cardio. A sport with one kind and no list: games.
        assertEquals(Family.IndoorCardio, Workouts.family(6))
        assertEquals(Family.IndoorCardio, Workouts.family(7))
        assertEquals(Family.Games, Workouts.family(70))
        assertEquals(Family.Games, Workouts.family(9999))
    }

    @Test
    fun everyOutdoorSportIsADistanceOne() {
        Workouts.OUTDOOR.forEach { assertEquals("sport $it", Family.Distance, Workouts.family(it)) }
    }

    @Test
    fun settingsRoundTrip() {
        val s = SportSettings(goal = "d:5000", askToEnd = false, gps = false, keepRoute = true, rest = "r:90:110", sets = 5, log = true, structure = "t:20/10x8")
        assertEquals(s, SportSettings.parse(s.encode()))
        assertEquals(SportSettings(), SportSettings.parse(SportSettings().encode()))
    }

    @Test
    fun unreadableSettingsFallBackToTheDefaults() {
        assertEquals(SportSettings(), SportSettings.parse(null))
        assertEquals(SportSettings(), SportSettings.parse("nonsense|g=x:1|s=99|r=bad|a=maybe"))
        // What's readable is kept.
        assertEquals(SportSettings(goal = "t:1800"), SportSettings.parse("g=t:1800|zz=1"))
    }

    @Test
    fun goalChipsLearnTheTwoMostUsed() {
        // Never used: the family's defaults.
        assertEquals(listOf(WorkoutTarget.Distance(5000), WorkoutTarget.Time(1800)), WorkoutSetup.goalChips(Family.Distance, emptyList()))
        assertEquals(listOf(WorkoutTarget.Time(1200), WorkoutTarget.Time(1800)), WorkoutSetup.goalChips(Family.IndoorCardio, listOf(null, null)))
        // 10 km three times, 45 min twice, 5 km once: the two most used.
        val used = listOf("d:10000", "t:2700", "d:10000", "d:5000", "t:2700", "d:10000", null)
        assertEquals(listOf(WorkoutTarget.Distance(10_000), WorkoutTarget.Time(2700)), WorkoutSetup.goalChips(Family.Distance, used))
        // One learnt, topped up with a default it isn't.
        assertEquals(listOf(WorkoutTarget.Time(1800), WorkoutTarget.Distance(5000)), WorkoutSetup.goalChips(Family.Distance, listOf("t:1800")))
        // A tie goes to the latest.
        assertEquals(listOf(WorkoutTarget.Time(900), WorkoutTarget.Time(3600)), WorkoutSetup.goalChips(Family.IndoorCardio, listOf("t:900", "t:3600")))
    }

    @Test
    fun chooseFindsYourUsualAndTwoRecent() {
        val now = 100 * 86_400L
        val day = 86_400L
        // Newest first: walk, run, strength, run, treadmill (a run), yoga.
        val started = listOf(3 to now - day, 1 to now - 2 * day, 52 to now - 3 * day, 1 to now - 4 * day, 2 to now - 5 * day, 60 to now - 6 * day)
        val c = WorkoutSetup.choose(started, now)
        assertEquals(1, c.usual)
        assertEquals(listOf(3, 52), c.recent)
    }

    @Test
    fun noUsualOutsideTheLastFourWeeks() {
        val now = 100 * 86_400L
        val c = WorkoutSetup.choose(listOf(60 to now - 40 * 86_400L), now)
        assertNull(c.usual)
        assertEquals(listOf(60, 1, 3), c.recent)
    }

    @Test
    fun recentChipsAreTheSportsThenTheNewestSavedWorkouts() {
        fun saved(name: String, structure: String) = org.openstrap.app.data.db.SavedWorkout(name = name, sport = 1, structure = structure, createdAt = 0)
        val chips = WorkoutSetup.recentChips(
            listOf(52, 60),
            // Newest first; one whose rounds no longer read is left out.
            listOf(saved("Tuesday intervals", "r:400m/90x6"), saved("Broken", "nonsense"), saved("Hills", "r:200m/60x8"), saved("Oldest", "r:1000m/120x4")),
        )
        assertEquals(
            listOf(RecentChip.Sport(52), RecentChip.Sport(60), RecentChip.Saved("Tuesday intervals", 1, "r:400m/90x6"), RecentChip.Saved("Hills", 1, "r:200m/60x8")),
            chips,
        )
        assertEquals(listOf(RecentChip.Sport(1)), WorkoutSetup.recentChips(listOf(1), emptyList()))
    }

    @Test
    fun aNewUserGetsRunWalkAndStrength() {
        val c = WorkoutSetup.choose(emptyList(), 0)
        assertNull(c.usual)
        assertEquals(listOf(1, 3, 52), c.recent)
    }

    @Test
    fun startingYourUsualTakesLastTimesSettings() {
        val run = SportSettings(goal = "d:5000", keepRoute = true, askToEnd = false)
        val p = WorkoutSetup.plan(1, run, emptyMap(), location = true)
        assertEquals(WorkoutPlan(1, askToEnd = false, target = WorkoutTarget.Distance(5000), useGps = true, keepRoute = true), p)
        // On the treadmill side last time: no GPS, and 5 km from steps still counts.
        val treadmill = WorkoutSetup.plan(1, run, mapOf(1 to true), location = true)
        assertEquals(2, treadmill.sportType)
        assertFalse(treadmill.useGps)
        assertEquals(WorkoutTarget.Distance(5000), treadmill.target)
        // Location off: no GPS, never blocked.
        assertFalse(WorkoutSetup.plan(1, run, emptyMap(), location = false).useGps)
        // A ride without GPS has no distance to aim for.
        assertNull(WorkoutSetup.plan(4, run, emptyMap(), location = false).target)
        // Strength takes its rest and sets.
        val lift = WorkoutSetup.plan(52, SportSettings(rest = "r:120:110", sets = 4), emptyMap(), location = true)
        assertEquals(Strength.Rest(120, 110), lift.rest)
        assertEquals(4, lift.setsTarget)
        assertFalse(lift.useGps)
    }

    @Test
    fun offeredGoals() {
        assertTrue(WorkoutSetup.offered(WorkoutTarget.Distance(5000), hasDistance = true, kcal = false))
        assertFalse(WorkoutSetup.offered(WorkoutTarget.Distance(5000), hasDistance = false, kcal = true))
        assertFalse(WorkoutSetup.offered(WorkoutTarget.Kcal(300), hasDistance = true, kcal = false))
        assertTrue(WorkoutSetup.offered(null, hasDistance = false, kcal = false))
        assertFalse(WorkoutSetup.moreGoals(kcal = false).any { (_, goals) -> goals.any { it is WorkoutTarget.Kcal } })
    }

    @Test
    fun distanceGoals() {
        assertEquals(WorkoutTarget.Distance(5000), WorkoutTarget.parse("d:5000"))
        assertNull(WorkoutTarget.parse("d:0"))
        assertEquals("5 km", WorkoutTarget.Distance(5000).label)
        assertEquals("21.1 km", WorkoutTarget.Distance(21_100).label)
        assertEquals("800 m", WorkoutTarget.Distance(800).label)
        assertEquals("Zone 2 to 3", WorkoutTarget.Zones(1, 2).label)
    }

    @Test
    fun aDistanceGoalBuzzesAtHalfwayThenOnceReached() {
        val coach = Coach(WorkoutTarget.Distance(5000))
        assertNull(coach.distance(0, 1000f))
        assertEquals(Cue.Halfway, coach.distance(10, 2500f))
        assertNull(coach.distance(20, 3000f))
        assertEquals(Cue.GoalReached, coach.distance(30, 5001f))
        assertEquals(30L, coach.reachedAt)
        assertNull(coach.distance(40, 6000f))
        // Other goals never buzz on distance; a distance goal never on time.
        assertNull(Coach(WorkoutTarget.Time(600)).distance(0, 10_000f))
        assertNull(Coach(WorkoutTarget.Distance(5000)).feed(0, 2, 10_000))
        // Picked up again after reaching it, it stays quiet.
        assertNull(Coach(WorkoutTarget.Distance(5000), reachedAt = 5).distance(10, 6000f))
    }

    @Test
    fun strengthSteppers() {
        assertEquals(1, Strength.stepSets(null, 1))
        assertNull(Strength.stepSets(null, -1))
        assertNull(Strength.stepSets(1, -1))
        assertEquals(6, Strength.stepSets(5, 1))
        assertEquals(10, Strength.stepSets(10, 1))
        assertEquals(105L, Strength.stepRest(90, 1))
        assertEquals(30L, Strength.stepRest(30, -1))
        assertEquals(300L, Strength.stepRest(300, 1))
    }
}
