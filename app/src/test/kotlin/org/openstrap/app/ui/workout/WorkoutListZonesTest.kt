package org.openstrap.app.ui.workout

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.HrStreamMark
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.data.settings.UserProfile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The workout list's zone bars come from each workout's whole HR stream, and agree with its page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutListZonesTest {
    private lateinit var db: OpenStrapDatabase
    private val start = 1_792_000_000L
    private val maxHr = 190

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    private fun strap(start: Long) = Workout(start, start + 1800, 1, 1800, null, null, null, null, null, null, null, null, null)

    @Test fun `marks sum up each workout's stream`() = runBlocking {
        val dao = db.healthDao()
        dao.insertWorkoutHeartRate(listOf(WorkoutHeartRate(start, start + 1, 100), WorkoutHeartRate(start, start + 5, 120)))
        dao.insertAppWorkoutHeartRate(listOf(AppWorkoutHeartRate(start, start + 9, 90, false)))
        assertEquals(listOf(HrStreamMark(start, start + 5, 2, 220)), dao.workoutHeartRateMarks().first())
        assertEquals(listOf(HrStreamMark(start, start + 9, 1, 90)), dao.appWorkoutHeartRateMarks().first())
    }

    @Test fun `list rows get the zones the workout's page shows, and none without heart rate`() = runBlocking {
        val dao = db.healthDao()
        val hard = strap(start)
        val bare = strap(start - 86_400)
        dao.insertWorkouts(listOf(hard, bare))
        // 20 minutes at 130 (zone 2), 10 at 160 (zone 4), then the recovery tail.
        val hr = (0L until 2000L).map { WorkoutHeartRate(start, start + it, if (it < 1200) 130 else 160) }
        dao.insertWorkoutHeartRate(hr)
        val phone = AppWorkout(start + 7200, start + 9000, 1)
        val phoneHr = (0L until 1800L).map { AppWorkoutHeartRate(phone.start, phone.start + it, 150, false) }
        dao.insertAppWorkouts(listOf(phone))
        dao.insertAppWorkoutHeartRate(phoneHr)

        val vm = WorkoutsViewModel(dao, flowOf(UserProfile(maxHeartRate = maxHr)))
        val list = withTimeout(10_000) { vm.workouts.first { l -> l.size == 3 && l.count { it.zoneSeconds.sum() > 0 } == 2 } }

        val strapRow = list.single { !it.fromPhone && it.workout.start == hard.start }
        val page = Workouts.analyse(hard, dao.workoutHeartRateOnce(hard.start), dao.sportEffects().first(), maxHr)
        assertEquals(page.zoneSeconds, strapRow.zoneSeconds)
        assertTrue(strapRow.zoneSeconds[1] > 0 && strapRow.zoneSeconds[3] > 0)
        // The row's end still comes from the last sample, as before.
        assertEquals(Workouts.endOf(hard, hr.last().timestamp, null), strapRow.end)

        val phoneRow = list.single { it.fromPhone }
        assertEquals(WorkoutRecorder.analyse(phone, phoneHr, maxHr).zoneSeconds, phoneRow.zoneSeconds)

        assertEquals(List(Workouts.ZONES) { 0 }, list.single { it.workout.start == bare.start }.zoneSeconds)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); Unit
    }
}
