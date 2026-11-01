package org.openstrap.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.settings.SettingsStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * First launch with history (requests spec 7.2): the badges history earned are stored and wait to be shown once; the
 * list's empty first value starts nothing, and later workouts never bring the screen back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RewardsKeeperTest {
    private lateinit var db: OpenStrapDatabase
    private val settings = SettingsStore(ApplicationProvider.getApplicationContext())
    private val start = 1_792_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun workout(day: Int) = AppWorkout(start + day * 86_400L, start + day * 86_400L + 1800, 52)

    @Test fun `history badges show once on a fresh install with history`() = runBlocking {
        val dao = db.healthDao()
        val history = (0 until 3).map(::workout)
        dao.insertAppWorkouts(history)
        val keeper = RewardsKeeper(dao, settings)
        assertFalse(settings.rewardsStarted.first())

        // The workouts list starts empty before it loads: with workouts in the database, that starts nothing.
        keeper.refresh(emptyList(), 3)
        assertFalse(settings.rewardsStarted.first())
        assertFalse(settings.historyBadgesWaiting.first())

        val listed = history.map { WorkoutRecorder.analyse(it, emptyList(), 190) }
        keeper.refresh(listed, 3)
        assertTrue(settings.rewardsStarted.first())
        assertTrue(settings.historyBadgesWaiting.first())
        assertTrue(dao.badgesOnce().any { it.id == "first_workout" })

        // Seen: a later workout's badges are new ones for its summary, never this screen again.
        settings.historyBadgesShown()
        val next = workout(3)
        dao.insertAppWorkouts(listOf(next))
        keeper.refresh(listed + WorkoutRecorder.analyse(next, emptyList(), 190), 3)
        assertFalse(settings.historyBadgesWaiting.first())
        assertEquals(4, dao.weekResultsOnce().sumOf { it.count })
    }
    @Test fun `the steps per day it keeps follow new minutes, older ones and a rewritten one`() = runBlocking {
        val dao = db.healthDao()
        val keeper = RewardsKeeper(dao, settings)
        val listed = listOf(WorkoutRecorder.analyse(workout(0), emptyList(), 190))
        fun minute(t: Long, steps: Int) = ActivityMinute(t, steps, 0, 0, null)
        suspend fun steps() = keeper.facts(listed).days.sumOf { it.steps }
        dao.insertActivity(listOf(minute(start, 100), minute(start + 60, 100)))
        assertEquals(200, steps())
        // A newer minute, and the last one rewritten by a sync: only the last day is read again.
        dao.insertActivity(listOf(minute(start + 60, 150), minute(start + 120, 50)))
        assertEquals(300, steps())
        // A minute older than any kept: everything is read again.
        dao.insertActivity(listOf(minute(start - 3 * 86_400L, 70)))
        assertEquals(370, steps())
    }

    @Test fun `a refresh without the screen counts workouts synced while the app was closed, less the one recording`() = runBlocking {
        val dao = db.healthDao()
        val keeper = RewardsKeeper(dao, settings)
        dao.insertAppWorkouts((0 until 3).map(::workout))
        assertEquals(0, dao.weekResultsOnce().sumOf { it.count })
        // The weekly recap's refresh, while the last one is still recording: it waits until that one ends.
        keeper.refreshNow(recordingStart = workout(2).start)
        assertEquals(2, dao.weekResultsOnce().sumOf { it.count })
        keeper.refreshNow(recordingStart = null)
        assertEquals(3, dao.weekResultsOnce().sumOf { it.count })
    }
}
