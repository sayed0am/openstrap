package org.openstrap.app.ui.dashboard

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.HealthRepository
import org.openstrap.app.data.LiveSteps
import org.openstrap.app.data.RoomDayRepository
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.testing.FixedClock
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.endMain
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.protocol.services.StepTotals
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Collections

/** The day's state is worked out off the main thread (audit C7), and moves on to the new day at midnight (C10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelClockTest {
    @get:Rule val zone = ZoneRule()

    private val scheduler = TestCoroutineScheduler()
    private val clock = FixedClock(Instant.parse("2026-01-15T23:59:30Z"))
    private val jan15 = LocalDate.of(2026, 1, 15)
    private lateinit var db: OpenStrapDatabase
    private val collectors = mutableListOf<Job>()

    @Before fun open() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        db = inMemoryDb()
    }

    @After fun close() {
        endMain(collectors)
        db.close()
    }

    private fun DayViewModel.watched(): DayViewModel = also { vm ->
        collectors += CoroutineScope(Dispatchers.Main).launch { vm.state.collect {} }
    }

    private fun DayViewModel.awaitDay(date: LocalDate): DayUiState = runBlocking {
        withTimeout(WAIT_MS) { state.first { it.loaded && it.date == date } }
    }

    /** Past the minute tick, on the test's virtual time. */
    private fun nextMinute() {
        clock.advance(Duration.ofMinutes(1))
        scheduler.advanceTimeBy(Duration.ofMinutes(1).toMillis())
        scheduler.runCurrent()
    }

    @Test fun `the day's state is worked out off the main thread`() {
        val threads = Collections.synchronizedList(mutableListOf<String>())
        // Today's live steps are read inside the state's flow, so where they're read is where the state is worked out.
        val steps = flow<StepTotals?> {
            threads += Thread.currentThread().name
            emit(null)
        }
        val vm = DayViewModel(RoomDayRepository(HealthRepository(db.healthDao()), LiveSteps(), steps), clock = { clock.clock }).watched()
        vm.awaitDay(jan15)
        assertTrue("read on $threads", threads.isNotEmpty() && threads.all { it.startsWith("DefaultDispatcher") })
        vm.viewModelScope.coroutineContext[Job]?.cancel()
    }

    @Test fun `a screen left open on today moves to the new day at midnight`() {
        val vm = DayViewModel(RoomDayRepository(HealthRepository(db.healthDao()), LiveSteps()), clock = { clock.clock }).watched()
        vm.awaitDay(jan15)
        nextMinute()
        val s = vm.awaitDay(jan15.plusDays(1))
        assertEquals(clock.today, s.date)
        vm.viewModelScope.coroutineContext[Job]?.cancel()
    }

    @Test fun `a day picked in the past stays put at midnight, and next day stops at the new today`() {
        val vm = DayViewModel(RoomDayRepository(HealthRepository(db.healthDao()), LiveSteps()), clock = { clock.clock }).watched()
        vm.select(jan15.minusDays(1))
        vm.awaitDay(jan15.minusDays(1))
        nextMinute()
        vm.awaitDay(jan15.minusDays(1))
        vm.nextDay()
        vm.awaitDay(jan15)
        vm.nextDay()
        vm.awaitDay(jan15.plusDays(1))
        vm.nextDay()
        assertEquals(jan15.plusDays(1), vm.awaitDay(jan15.plusDays(1)).date)
        vm.viewModelScope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
