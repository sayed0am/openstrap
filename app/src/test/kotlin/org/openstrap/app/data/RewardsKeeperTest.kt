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
}
