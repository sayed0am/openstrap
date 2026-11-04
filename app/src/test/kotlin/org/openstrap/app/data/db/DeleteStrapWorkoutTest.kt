package org.openstrap.app.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.workout
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deleting a strap workout removes what is keyed to it, leaves its neighbours alone and remembers it for the next sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeleteStrapWorkoutTest {
    private lateinit var db: OpenStrapDatabase
    private lateinit var dao: HealthDao

    private val junk = 1_760_000_000L
    private val real = junk + 4 * 3600

    private fun strapWorkout(start: Long, minutes: Int) =
        workout(start, minutes, sportType = 16, averageHeartRate = 110, maxHeartRate = 130, minHeartRate = 80, trainingLoad = 10, subtype = 0)

    @Before
    fun setUp() {
        db = inMemoryDb()
        dao = db.healthDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(junkEffect: Boolean) {
        dao.insertWorkouts(listOf(strapWorkout(junk, 30), strapWorkout(real, 40)))
        dao.insertWorkoutHeartRate((0L..2000L step 10).map { WorkoutHeartRate(junk, junk + it, 100) })
        dao.insertWorkoutHeartRate((0L..2400L step 10).map { WorkoutHeartRate(real, real + it, 140) })
        dao.insertRecoveryHeartRate(listOf(RecoveryHeartRateRow(junk, byteArrayOf(100, 90)), RecoveryHeartRateRow(real, byteArrayOf(120, 100))))
        val effects = listOfNotNull(
            SportEffectRow(junk + 1800, 60, null, null, null, null, null, null, null).takeIf { junkEffect },
            SportEffectRow(real + 2400, 600, null, null, null, null, null, null, null),
        )
        dao.insertSportEffects(effects)
    }

    @Test
    fun removesTheWorkoutAndWhatIsKeyedToIt() = runTest {
        seed(junkEffect = true)
        dao.deleteStrapWorkout(junk, now = 99)

        assertEquals(listOf(real), dao.workouts().first().map { it.start })
        assertNull(dao.workoutOnce(junk))
        assertEquals(emptyList<WorkoutHeartRate>(), dao.workoutHeartRateOnce(junk))
        assertEquals(listOf(real), dao.recoveryHeartRateAll().map { it.workoutStart })
        assertEquals(listOf(real + 2400), dao.sportEffects().first().map { it.end })
        assertEquals(listOf(DeletedWorkout(junk, junk + 1800, 99)), dao.deletedWorkoutsAll())
        // Its time is "Not a workout" now, to its real end (the sport effect's), so it isn't suggested either.
        assertEquals(listOf(DismissedWorkout(junk, junk + 1800)), dao.dismissedWorkoutsAll())
        // The other workout keeps everything.
        assertEquals(241, dao.workoutHeartRateOnce(real).size)
    }

    @Test
    fun keepsTheNextWorkoutsEffect() = runTest {
        // Without an effect of its own, the deleted one is shown with the next workout's; that one stays.
        seed(junkEffect = false)
        dao.deleteStrapWorkout(junk, now = 99)

        assertEquals(listOf(real + 2400), dao.sportEffects().first().map { it.end })
        assertEquals(listOf(DeletedWorkout(junk, null, 99)), dao.deletedWorkoutsAll())
        // Its end comes from its own HR stream: the last sample less the recovery tail.
        assertEquals(listOf(DismissedWorkout(junk, junk + 2000 - 180)), dao.dismissedWorkoutsAll())
    }

    @Test
    fun unknownWorkoutChangesNothing() = runTest {
        seed(junkEffect = true)
        dao.deleteStrapWorkout(12345, now = 99)

        assertEquals(2, dao.workoutCount())
        assertEquals(emptyList<DeletedWorkout>(), dao.deletedWorkoutsAll())
        assertEquals(emptyList<DismissedWorkout>(), dao.dismissedWorkoutsAll())
    }
}
