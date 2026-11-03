package org.openstrap.app.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Items 12 and 15 of the 2026-11-03 data integrity review: what gap fill fills, and that it leaves edits alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutGapFillTest {
    private lateinit var db: OpenStrapDatabase
    private val dao: HealthDao get() = db.healthDao()
    private val t0 = 1_800_000_000L / 60 * 60
    private val now = t0 + 4 * 3600

    @Before
    fun setUp() {
        db = inMemoryDb()
        // The strap's history holds a reading every minute for the four hours since the start, the night included.
        runBlocking { dao.insertHeartRate((0 until 240).map { HeartRateSample(t0 + it * 60L, 70) }) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** Where a fill waits on its own while the test edits the workout. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Live readings each second of minutes [from] until [to] (minute numbers from the start). */
    private fun live(from: Int, to: Int) = runBlocking {
        dao.insertAppWorkoutHeartRate((from * 60L until to * 60L).map { AppWorkoutHeartRate(t0, t0 + it, 140) })
    }

    private fun filled() = runBlocking { dao.appWorkoutHeartRateOnce(t0).filter { it.fromHistory }.map { (it.timestamp - t0) / 60 } }

    @Test
    fun aWorkoutStillRecordingIsFilledOnlyUpToItsLastReading() {
        runBlocking { dao.upsertAppWorkout(AppWorkout(t0, null, 1)) }
        // Minutes 1 to 9 missed, live again for minute 10, then nothing: the app was killed.
        live(0, 1)
        live(10, 11)
        runBlocking { WorkoutGapFill(dao, Mutex()).run(now) }
        assertEquals((1L..9L).toList(), filled())
    }

    @Test
    fun aTrimMadeWhileTheFillWaitedStands() {
        runBlocking { dao.upsertAppWorkout(AppWorkout(t0, t0 + 600, 1)) }
        live(0, 1)
        val gate = GatedDao(dao)
        val run = scope.async { WorkoutGapFill(gate, Mutex()).run(now) }
        runBlocking {
            withTimeout(WAIT_MS) { gate.listed.await() }
            // Trimmed after the fill read the list.
            dao.upsertAppWorkout(AppWorkout(t0, t0 + 300, 1, recordedEnd = t0 + 600))
            gate.release.complete(Unit)
            withTimeout(WAIT_MS) { run.await() }
        }
        val w = runBlocking { dao.appWorkoutOnce(t0) }!!
        assertEquals(t0 + 300, w.end)
        assertEquals(t0 + 600, w.recordedEnd)
        // The minutes it filled are over the whole recording, so an undo has them.
        assertEquals((1L..9L).toList(), filled())
    }

    @Test
    fun aDiscardMadeWhileTheFillWaitedStays() {
        runBlocking { dao.upsertAppWorkout(AppWorkout(t0, t0 + 600, 1)) }
        live(0, 1)
        val gate = GatedDao(dao)
        val run = scope.async { WorkoutGapFill(gate, Mutex()).run(now) }
        runBlocking {
            withTimeout(WAIT_MS) { gate.listed.await() }
            dao.deleteAppWorkout(t0)
            gate.release.complete(Unit)
            withTimeout(WAIT_MS) { run.await() }
        }
        assertNull(runBlocking { dao.appWorkoutOnce(t0) })
        assertEquals(emptyList<Long>(), filled())
    }

    @Test
    fun theFillWaitsForTheWorkoutLock() {
        runBlocking { dao.upsertAppWorkout(AppWorkout(t0, t0 + 600, 1)) }
        live(0, 1)
        val lock = Mutex()
        runBlocking {
            lock.lock()
            val run = async(Dispatchers.Default) { WorkoutGapFill(dao, lock).run(now) }
            delay(SETTLE_MS)
            assertTrue("filled while an edit held the lock", filled().isEmpty())
            lock.unlock()
            withTimeout(WAIT_MS) { run.await() }
        }
        assertEquals((1L..9L).toList(), filled())
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
