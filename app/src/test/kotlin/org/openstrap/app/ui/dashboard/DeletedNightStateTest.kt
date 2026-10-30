package org.openstrap.app.ui.dashboard

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.HealthRepository
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.SleepSession
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** The day's state after a delete: the night leaves the nights (the strip's dots) and comes back on restore. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeletedNightStateTest {
    private lateinit var db: OpenStrapDatabase

    @Before fun open() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun close() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test fun `a deleted night leaves the nights, and a restored one comes back`() = runTest {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m).atZone(zone).toEpochSecond()
        val night = SleepSession(at(today.minusDays(1), 23, 20), at(today, 7, 21), score = 83)
        val before = SleepSession(at(today.minusDays(2), 23, 0), at(today.minusDays(1), 7, 0), score = 80)
        db.healthDao().insertSleepSessions(listOf(before, night))
        val repo = HealthRepository(db.healthDao())
        val vm = DayViewModel(repo)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        suspend fun settled(check: (DayUiState) -> Boolean) = vm.state.first { it.loaded && check(it) }

        settled { it.sleep != null }.let { s -> assertEquals(setOf(today.minusDays(1), today), s.nights.filter { it.session != null }.map { it.date }.toSet()) }
        repo.deleteSleep(night)
        settled { it.sleep == null }.let { s ->
            assertNotNull(s.deletedSleep)
            assertEquals(setOf(today.minusDays(1)), s.nights.filter { it.session != null }.map { it.date }.toSet())
        }
        repo.restoreSleep(night)
        settled { it.sleep != null }.let { s ->
            assertNull(s.deletedSleep)
            assertEquals(setOf(today.minusDays(1), today), s.nights.filter { it.session != null }.map { it.date }.toSet())
        }
        collector.cancel()
    }
}
