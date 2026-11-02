package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.db.WorkoutHeartRate

class WorkoutsTest {
    private val start = 1_000_000L
    private fun workout(exercise: Int = 600) = Workout(start, start + exercise, 223, exercise, 60, 120, 140, 90, 1f, 0.1f, 6, null, null)

    /** 20 min at 140 bpm, then three minutes falling 0.5 bpm a second. */
    private val hr = (0 until 1200).map { WorkoutHeartRate(start, start + it, 140) } +
        (0..180).map { WorkoutHeartRate(start, start + 1200 + it, 140 - it / 2) }

    @Test
    fun endComesFromTheRecoveryTail() {
        val a = Workouts.analyse(workout(), hr, emptyList(), 190, HeartZones.STANDARD)
        assertEquals(start + 1200, a.end)
        assertEquals(30, a.recovery60)
        assertEquals(60, a.recovery120)
        assertEquals(140, a.endHeartRate)
        org.junit.Assert.assertTrue(a.recoveryIsMeaningful)
        // 140/190 = 73 %: all workout time in zone 3 (index 2).
        assertEquals(1200, a.zoneSeconds[2])
    }

    @Test
    fun sportEffectSetsEndAndMaxHr() {
        val effect = SportEffectRow(start + 1100, 127, null, null, null, null, null, null, 160)
        val a = Workouts.analyse(workout(), hr, listOf(effect), 190, HeartZones.STANDARD)
        assertEquals(start + 1100, a.end)
        assertEquals(160, a.maxHeartRate)
        assertEquals(127, a.effect?.recoveryMinutes)
    }

    @Test
    fun noHeartRateFallsBackToExerciseTime() {
        val a = Workouts.analyse(workout(600), emptyList(), emptyList(), 190, HeartZones.STANDARD)
        assertEquals(start + 600, a.end)
        assertNull(a.recovery60)
        assertEquals(0, a.zoneSeconds.sum())
    }

    @Test
    fun zones() {
        assertNull(Workouts.zoneOf(90, 190, HeartZones.STANDARD))
        assertEquals(0, Workouts.zoneOf(100, 190, HeartZones.STANDARD))
        assertEquals(4, Workouts.zoneOf(190, 190, HeartZones.STANDARD))
        assertEquals(4, Workouts.zoneOf(200, 190, HeartZones.STANDARD))
    }

    /** Only a fresh, auto-detected strap workout gets the "detected" notification (codemap 05 §8). */
    @Test
    fun autoDetectNotification() {
        val auto = workout().copy(subtype = Workouts.SUBTYPE_AUTO_DETECTED)
        val justEnded = auto.end + 600
        org.junit.Assert.assertTrue(Workouts.notifyAutoDetected(auto, storedBefore = false, now = justEnded))
        // Already stored: the strap re-sent it, so no second notification.
        org.junit.Assert.assertFalse(Workouts.notifyAutoDetected(auto, storedBefore = true, now = justEnded))
        // Backlog from a first sync, more than a day old.
        org.junit.Assert.assertFalse(Workouts.notifyAutoDetected(auto, storedBefore = false, now = auto.end + Workouts.AUTO_DETECT_NOTIFY_WINDOW_S + 1))
        // Normal (0), mirrored phone workout (6) and rows from before the subtype was stored (null).
        listOf(0, 6, null).forEach {
            org.junit.Assert.assertFalse(Workouts.notifyAutoDetected(auto.copy(subtype = it), storedBefore = false, now = justEnded))
        }
    }

    @Test
    fun startedByYouLeavesOutWhatTheStrapLoggedByItself() {
        val run = workout().copy(sportType = 1)
        val auto = run.copy(subtype = Workouts.SUBTYPE_AUTO_DETECTED)
        val activity = run.copy(sportType = Workouts.GENERIC_ACTIVITY)
        org.junit.Assert.assertTrue(Workouts.analyse(run, emptyList(), emptyList(), 190, HeartZones.STANDARD).startedByYou)
        org.junit.Assert.assertFalse(Workouts.analyse(auto, emptyList(), emptyList(), 190, HeartZones.STANDARD).startedByYou)
        org.junit.Assert.assertFalse(Workouts.analyse(activity, emptyList(), emptyList(), 190, HeartZones.STANDARD).startedByYou)
    }

    @org.junit.Test
    fun autoDetectedLabelOnlyOnStrapWorkouts() {
        val auto = workout().copy(subtype = Workouts.SUBTYPE_AUTO_DETECTED)
        org.junit.Assert.assertTrue(Workouts.analyse(auto, emptyList(), emptyList(), 190, HeartZones.STANDARD).autoDetected)
        org.junit.Assert.assertFalse(Workouts.analyse(workout(), emptyList(), emptyList(), 190, HeartZones.STANDARD).autoDetected)
    }

    @Test
    fun usualLoadIsTheMedianOfRecentWorkoutsWithOne() {
        assertNull(Workouts.usualLoad(listOf(80, null, 0, 120)))
        assertEquals(80f, Workouts.usualLoad(listOf(80, null, 40, 0, 120))!!, 0.001f)
        assertEquals(60f, Workouts.usualLoad(listOf(80, 40, 120, 20))!!, 0.001f)
        // Only the latest 20 count: the old hard ones fall out.
        assertEquals(30f, Workouts.usualLoad(List(20) { 30 } + List(10) { 300 })!!, 0.001f)
    }

    @Test
    fun effortGlowsWithLoadAgainstYourUsual() {
        // A usual workout glows at half, twice usual or more at full.
        assertEquals(0.5f, Workouts.effort(60, 60f), 0.001f)
        assertEquals(1f, Workouts.effort(120, 60f), 0.001f)
        assertEquals(1f, Workouts.effort(400, 60f), 0.001f)
        assertEquals(0.25f, Workouts.effort(30, 60f), 0.001f)
        // Without a usual, the fixed scale.
        assertEquals(0.5f, Workouts.effort(75, null), 0.001f)
        // No Load, or a tiny one, keeps the faint minimum.
        assertEquals(Workouts.EFFORT_MIN, Workouts.effort(null, 60f), 0.001f)
        assertEquals(Workouts.EFFORT_MIN, Workouts.effort(0, null), 0.001f)
        assertEquals(Workouts.EFFORT_MIN, Workouts.effort(2, 60f), 0.001f)
    }

    @Test
    fun aPhoneWorkoutShowsItsMovingTimeAndAStrapWorkoutItsWholeLength() {
        // 108 s from start to end, 97 s of them moving.
        val w = Workout(start, start + 108, 1, 97, null, null, null, null, null, null, null, null, null)
        val strap = Workouts.analyse(w, emptyList(), emptyList(), 190, HeartZones.STANDARD)
        assertEquals(108, strap.activeSeconds)
        assertEquals(97, strap.copy(fromPhone = true).activeSeconds)
    }

    @Test
    fun aStrapWorkoutInsideAPhoneOneIsTheSameWorkout() {
        // As on 29 Oct: a walk started on the phone 21:36 to 23:31, the strap's auto-detected "Activity" 22:53 to 23:31.
        val walk = org.openstrap.app.data.db.AppWorkout(start, start + 6892, 3)
        val detected = Workout(start + 4638, start + 6876, 223, 2238, 277, 117, 140, 90, 1f, 0.1f, 16, null, null, subtype = 1)
        assertEquals(walk, Workouts.phoneTwin(detected, listOf(walk), start + 9000))
        val listed = Workouts.listed(listOf(detected), emptyList(), emptyList(), listOf(walk), emptyMap(), 190, now = start + 9000)
        assertEquals(listOf(true), listed.map { it.fromPhone })
        // Noticed 5 min before Start: the same. 50 min before, mostly outside: its own.
        org.junit.Assert.assertTrue(Workouts.sameWorkout((start - 300)..(start + 1500), start..(start + 6892)))
        org.junit.Assert.assertFalse(Workouts.sameWorkout((start - 3000)..(start + 600), start..(start + 6892)))
        // Right after it ended: another workout.
        org.junit.Assert.assertFalse(Workouts.sameWorkout((start + 6950)..(start + 9000), start..(start + 6892)))
        // A walk 18:00 to 18:30, then the gym 18:28 to 19:30: starting inside isn't enough, an hour lies outside.
        org.junit.Assert.assertFalse(Workouts.sameWorkout((start + 1680)..(start + 5400), start..(start + 1800)))
        // Ran on 8 min past Stop: still the same.
        org.junit.Assert.assertTrue(Workouts.sameWorkout((start + 600)..(start + 2280), start..(start + 1800)))
        // While the walk still records, its range runs to now.
        org.junit.Assert.assertTrue(Workouts.sameWorkout((start + 4638)..(start + 6876), Workouts.phoneRange(walk.copy(end = null), start + 7000)))
        // A workout left running for hours holds two of the strap's: both are its, by start.
        val forgotten = start..(start + 20_000)
        val second = detected.copy(start = start + 12_000, end = start + 13_800)
        assertEquals(listOf(detected.start, second.start), Workouts.strapTwins(listOf(second, detected), forgotten).map { it.start })
        // With no phone workout over it, the strap's is listed as before.
        assertEquals(1, Workouts.listed(listOf(detected), emptyList(), emptyList(), emptyList(), emptyMap(), 190).size)
    }

    @Test
    fun onlyARecordOfNearlyAllOfAPhoneWorkoutStandsForItsEffect() {
        val walk = start..(start + 6892)
        // A session the strap ran alongside, a few seconds off at each end: its figures are the walk's.
        org.junit.Assert.assertTrue(Workouts.strapCovers((start + 3)..(start + 6880), walk))
        // The strap noticed only the last 37 minutes: a third of it, so the model stands.
        org.junit.Assert.assertFalse(Workouts.strapCovers((start + 4638)..(start + 6876), walk))
    }

    @Test
    fun recoveryCountsFromTheWorkoutWhoseOwnRunsOutLast() {
        // A long walk ending 23:31 that needs 17 h, then a 6-minute walk at 00:15 that needs 35 min of its own and
        // carries the long one's 16 h: the time left counts from the long walk.
        val long = Workouts.analyse(workout(), emptyList(), emptyList(), 190, HeartZones.STANDARD).copy(end = start, recoveryMinutes = 1022, ownRecoveryMinutes = 1022)
        val short = Workouts.analyse(workout(), emptyList(), emptyList(), 190, HeartZones.STANDARD).copy(end = start + 3000, recoveryMinutes = 978, ownRecoveryMinutes = 35)
        assertEquals(long, Workouts.recoverySource(listOf(short, long)))
        // Without its own, a workout's whole time stands.
        val unknown = short.copy(ownRecoveryMinutes = null)
        assertEquals(unknown, Workouts.recoverySource(listOf(unknown, long)))
    }
}
