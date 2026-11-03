package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Activity
import org.openstrap.app.data.Route
import org.openstrap.app.data.SportTypes
import org.openstrap.app.data.db.AppWorkout

/** Item 11 of the 2026-11-03 data integrity review: a workout's distance follows the range it shows. */
class WorkoutDistanceTest {
    private val t0 = 1_800_000_000L

    /** A straight line north at 3 m/s, a fix every 10 s, for [seconds]. */
    private fun line(seconds: Long) = (0..seconds step 10).map { Route.Point(t0 + it, 51.5 + it * 3 / 111_195.0, -0.1, 5f) }

    /** The strap's running total, 100 steps a minute from [from] for [minutes], starting at [total]. */
    private fun totals(from: Long, minutes: Int, total: Int = 1_000) = (0..minutes).map { from + it * 60L to total + it * 100 }

    private fun track(route: List<Route.Point> = emptyList(), steps: List<Pair<Long, Int>> = emptyList(), since: Long = t0, before: AppWorkout? = null) =
        WorkoutDistance.Track(route, steps, 175, routeSince = since, stepsSince = since, before = before)

    @Test
    fun anEarlyEndLeavesOutTheRouteAfterIt() {
        val route = line(600)
        val all = WorkoutDistance.shown(AppWorkout(t0, t0 + 600, SportTypes.WALK), t0 + 600, track(route))!!
        val ended = WorkoutDistance.shown(AppWorkout(t0, t0 + 300, SportTypes.WALK, recordedEnd = t0 + 600), t0 + 600, track(route))!!
        assertEquals(false, ended.second)
        assertEquals(all.first / 2, ended.first, all.first * 0.05f)
        // A trimmed start leaves out the start too.
        val trimmed = WorkoutDistance.shown(AppWorkout(t0, t0 + 450, SportTypes.WALK, shownStart = t0 + 150), t0 + 600, track(route))!!
        assertEquals(all.first / 2, trimmed.first, all.first * 0.05f)
    }

    @Test
    fun stepsCountOnlyInsideTheRange() {
        val m = WorkoutDistance.shown(AppWorkout(t0, t0 + 300, SportTypes.WALK, recordedEnd = t0 + 600), t0 + 600, track(steps = totals(t0, 10)))!!
        assertEquals(500 * Activity.stepLength(100, 175), m.first, 0.01f)
        assertTrue(m.second)
    }

    @Test
    fun aRestartAddsToWhatWasStored() {
        // Killed at 10 minutes with 1 km stored, and memory holds only the 5 minutes since the restart.
        val stored = AppWorkout(t0, null, SportTypes.WALK, distanceMeters = 1_000f, distanceFromSteps = true)
        val since = t0 + 600
        val m = WorkoutDistance.shown(stored, t0 + 900, track(steps = totals(since, 5), since = since, before = stored))!!
        assertEquals(1_000 + 500 * Activity.stepLength(100, 175), m.first, 0.01f)
        assertTrue(m.second)
        // Ended before the restart, it keeps its share of what was stored.
        val early = stored.copy(end = t0 + 300, recordedEnd = t0 + 900)
        assertEquals(500f, WorkoutDistance.shown(early, t0 + 900, track(steps = totals(since, 5), since = since, before = stored))!!.first, 0.01f)
    }

    @Test
    fun theTotalGoingBackToZeroAtMidnightCostsOnlyTheStepsBeforeIt() {
        assertEquals(200, WorkoutDistance.taken(listOf(0L to 9_000, 60L to 9_100, 120L to 50, 180L to 150), 0, 180))
        assertEquals(0, WorkoutDistance.taken(emptyList(), 0, 180))
    }

    @Test
    fun withNothingLeftTheDistanceFollowsTheShareOfMovingTimeAndUndoGivesItBack() {
        val full = AppWorkout(t0, t0 + 600, SportTypes.WALK, distanceMeters = 3_000f)
        val trimmed = full.copy(end = t0 + 300, recordedEnd = t0 + 600)
        assertEquals(1_500f, WorkoutDistance.scaledTo(full, trimmed)!!, 0.01f)
        assertEquals(3_000f, WorkoutDistance.scaledTo(trimmed.copy(distanceMeters = 1_500f), full)!!, 0.01f)
    }
}
