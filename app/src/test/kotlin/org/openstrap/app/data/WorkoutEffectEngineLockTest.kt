package org.openstrap.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.testing.appWorkout
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.openstrap.app.testing.workout
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The effect engine's writes to phone workouts after a sync take the app's workout lock, a workout at a time, and read
 * the workout again inside it, as the gap fill does: a trim or an end made meanwhile stands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutEffectEngineLockTest {
    private lateinit var db: OpenStrapDatabase
    private val dao: HealthDao get() = db.healthDao()
    private val t0 = 1_800_000_000L / 60 * 60

    @Before
    fun setUp() {
        db = inMemoryDb()
        runBlocking {
            dao.upsertAppWorkout(appWorkout(start = t0, minutes = 30))
            dao.insertAppWorkoutHeartRate((0L until 1800L step 5).map { AppWorkoutHeartRate(t0, t0 + it, 150) })
        }
    }

    @After
    fun tearDown() = db.close()

    private fun stored(): AppWorkout? = runBlocking { dao.appWorkoutOnce(t0) }

    /** The strap's own record of the whole workout, a session it ran alongside, with its load. */
    private fun strapRecord() = runBlocking { dao.insertWorkouts(listOf(workout(start = t0 + 3, end = t0 + 1800, trainingLoad = 80, averageHeartRate = 140))) }

    @Test
    fun strapEffectsWaitForTheWorkoutLock() {
        strapRecord()
        val lock = Mutex()
        runBlocking {
            lock.lock()
            val run = async(Dispatchers.Default) { WorkoutEffectEngine(dao).takeStrapEffects(t0 - 60, lock) }
            delay(SETTLE_MS)
            assertFalse("written while an edit held the lock", stored()!!.effectFromStrap)
            lock.unlock()
            withTimeout(WAIT_MS) { run.await() }
        }
        assertTrue(stored()!!.effectFromStrap)
        assertEquals(80f, stored()!!.epoc!!, 0f)
    }

    @Test
    fun aTrimMadeWhileStrapEffectsWaitedStands() {
        strapRecord()
        val gate = GatedDao(dao)
        runBlocking {
            val run = async(Dispatchers.Default) { WorkoutEffectEngine(gate).takeStrapEffects(t0 - 60, Mutex()) }
            withTimeout(WAIT_MS) { gate.listed.await() }
            // Trimmed to its first ten minutes after the list was read: the strap's record is no longer of it.
            dao.upsertAppWorkout(appWorkout(start = t0, end = t0 + 600).copy(recordedEnd = t0 + 1800))
            gate.release.complete(Unit)
            withTimeout(WAIT_MS) { run.await() }
        }
        val w = stored()!!
        assertEquals(t0 + 600, w.end)
        assertFalse(w.effectFromStrap)
        assertNull(w.epoc)
    }

    @Test
    fun recomputeAllWaitsForTheWorkoutLock() {
        val lock = Mutex()
        val done = runBlocking {
            lock.lock()
            val run = async(Dispatchers.Default) { WorkoutEffectEngine(dao).recomputeAll(profile(), lock) }
            delay(SETTLE_MS)
            assertNull("written while an edit held the lock", stored()!!.epoc)
            lock.unlock()
            withTimeout(WAIT_MS) { run.await() }
        }
        assertEquals(1, done)
        assertNotNull(stored()!!.epoc)
    }

    /** [HealthDao] whose list of recent workouts is handed back only once [release] completes. */
    private class GatedDao(private val real: HealthDao) : HealthDao by real {
        val listed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun appWorkoutsOnce(from: Long, to: Long): List<AppWorkout> {
            val list = real.appWorkoutsOnce(from, to)
            listed.complete(Unit)
            release.await()
            return list
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 300L
    }
}
