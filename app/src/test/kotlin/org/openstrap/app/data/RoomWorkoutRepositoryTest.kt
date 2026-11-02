package org.openstrap.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.appWorkout
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.openstrap.app.testing.workout
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The workouts as the repository reads them: a summary read writes nothing, and every list collection gets its zones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomWorkoutRepositoryTest {
    @get:Rule val zone = ZoneRule()

    private lateinit var db: OpenStrapDatabase
    private val start = 1_792_000_000L

    @Before fun setUp() {
        db = inMemoryDb()
    }

    @After fun tearDown() = db.close()

    private fun repo(): RoomWorkoutRepository {
        val p = flowOf(profile(maxHeartRate = 190))
        return RoomWorkoutRepository(db.healthDao(), p, MaxHeartRates(db.healthDao(), p))
    }

    @Test fun `reading a summary writes nothing, and the backfill writes the effect`() = runBlocking {
        val dao = db.healthDao()
        dao.insertAppWorkouts(listOf(appWorkout(start)))
        dao.insertAppWorkoutHeartRate((0L until 1800L).map { AppWorkoutHeartRate(start, start + it, 150, false) })
        val repo = repo()
        assertNotNull(withTimeout(WAIT_MS) { repo.phoneSummary(start).first { it != null } })
        assertNull(dao.appWorkoutOnce(start)?.epoc)
        repo.backfillEffect(start)
        assertNotNull(dao.appWorkoutOnce(start)?.epoc)
    }

    @Test fun `two collections of the list each get their zones`() = runBlocking {
        val dao = db.healthDao()
        dao.insertWorkouts(listOf(workout(start)))
        dao.insertWorkoutHeartRate((0L until 1800L).map { WorkoutHeartRate(start, start + it, 150) })
        val repo = repo()
        val zoned = { l: List<WorkoutAnalysis> -> l.size == 1 && l.single().zoneSeconds.sum() > 0 }
        val lists = withTimeout(WAIT_MS) {
            listOf(async { repo.workouts().first(zoned) }, async { repo.workouts().first(zoned) }).map { it.await() }
        }
        assertEquals(lists[0], lists[1])
    }

    @Test fun `the strength rest recovery averages the rests that fell under the level`() {
        val sets = listOf(
            org.openstrap.app.data.db.AppWorkoutSet(start, 0, start + 60, start + 100, start + 200),
            org.openstrap.app.data.db.AppWorkoutSet(start, 1, start + 300, start + 360, start + 460),
        )
        val readings = listOf(start + 130 to 105, start + 380 to 120, start + 410 to 100)
        assertEquals(40L, WorkoutSummaryData.restRecovery(sets, readings, 110))
        assertNull(WorkoutSummaryData.restRecovery(sets, readings, null))
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
