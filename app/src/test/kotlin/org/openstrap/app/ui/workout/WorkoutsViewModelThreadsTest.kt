package org.openstrap.app.ui.workout

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.MaxHeartRates
import org.openstrap.app.data.RoomWorkoutRepository
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.testing.FixedClock
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import org.openstrap.app.data.HeartZones

/**
 * The workouts list is worked out off the main thread (audit C7), the suggestions' window moves on at midnight
 * (C10), and a phone workout's effect backfill runs beside its summary, not inside the summary's read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutsViewModelThreadsTest {
    @get:Rule val zone = ZoneRule()

    private val scheduler = TestCoroutineScheduler()
    private val clock = FixedClock(Instant.parse("2026-01-15T23:59:30Z"))
    private lateinit var db: OpenStrapDatabase
    private val collectors = mutableListOf<Job>()
    private val vms = mutableListOf<WorkoutsViewModel>()

    /** Where the list's last heart rates were read, and each window the suggestions read heart rate from. */
    private val listThreads = Collections.synchronizedList(mutableListOf<String>())
    private val heartRateFrom = Collections.synchronizedList(mutableListOf<Long>())

    private val dao: HealthDao by lazy {
        val real = db.healthDao()
        object : HealthDao by real {
            override fun lastWorkoutHeartRates(): Flow<List<WorkoutHeartRate>> =
                real.lastWorkoutHeartRates().onEach { listThreads += Thread.currentThread().name }

            override fun heartRate(from: Long, to: Long): Flow<List<HeartRateSample>> {
                heartRateFrom += from
                return real.heartRate(from, to)
            }
        }
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        db = inMemoryDb()
    }

    @After fun tearDown() {
        collectors.forEach { it.cancel() }
        vms.forEach { it.viewModelScope.coroutineContext[Job]?.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun vm(profile: Flow<UserProfile> = flowOf(profile()), onTestTime: Boolean = false): WorkoutsViewModel {
        val work = if (onTestTime) UnconfinedTestDispatcher(scheduler) else Dispatchers.Default
        return WorkoutsViewModel(RoomWorkoutRepository(dao, profile, MaxHeartRates(dao, flowOf(profile())), flowOf(HeartZones.STANDARD)), clock = { clock.clock }, work = work).also { vms += it }
    }

    private fun <T> Flow<T>.watched() {
        collectors += CoroutineScope(Dispatchers.Main).launch { collect {} }
    }

    private fun until(what: String, check: suspend () -> Boolean) = runBlocking {
        try {
            withTimeout(WAIT_MS) { while (!check()) delay(POLL_MS) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$what never came", e)
        }
    }

    /** A phone workout of half an hour at 150, finished before its effect was worked out. */
    private fun oldPhoneWorkout(): Long = runBlocking {
        val start = clock.now - 86_400
        db.healthDao().insertAppWorkouts(listOf(AppWorkout(start, start + 1800, 1)))
        db.healthDao().insertAppWorkoutHeartRate((0L until 1800L).map { AppWorkoutHeartRate(start, start + it, 150, false) })
        start
    }

    @Test fun `the list is worked out off the main thread`() {
        val vm = vm()
        vm.workouts.watched()
        until("the list's heart rates") { listThreads.isNotEmpty() }
        assertTrue("read on $listThreads", listThreads.all { it.startsWith("DefaultDispatcher") })
    }

    @Test fun `the suggestions read from the new yesterday after midnight`() {
        val vm = vm(onTestTime = true)
        vm.suggestions.watched()
        val jan15 = LocalDate.of(2026, 1, 15)
        until("yesterday's window") { DayWindow.of(jan15.minusDays(1)).from in heartRateFrom }
        clock.advance(Duration.ofMinutes(1))
        scheduler.advanceTimeBy(Duration.ofMinutes(1).toMillis())
        scheduler.runCurrent()
        until("the new yesterday's window") { DayWindow.of(jan15).from in heartRateFrom }
    }

    @Test fun `the summary shows without waiting for the effect backfill`() {
        val start = oldPhoneWorkout()
        // A profile that never comes: the backfill waits on it for good, and the summary mustn't.
        val vm = vm(profile = MutableSharedFlow())
        val summary = runBlocking { withTimeout(WAIT_MS) { vm.phoneSummary(start).first { it != null } } }
        assertNotNull(summary)
    }

    @Test fun `opening the summary still backfills the effect, and a preview writes nothing`() {
        val preview = oldPhoneWorkout()
        val opened = preview - 7200
        runBlocking {
            db.healthDao().insertAppWorkouts(listOf(AppWorkout(opened, opened + 1800, 1)))
            db.healthDao().insertAppWorkoutHeartRate((0L until 1800L).map { AppWorkoutHeartRate(opened, opened + it, 150, false) })
        }
        val vm = vm()
        runBlocking { withTimeout(WAIT_MS) { vm.phoneSummary(preview, backfill = false).first { it != null } } }
        vm.phoneSummary(opened).watched()
        until("the backfilled effect") { db.healthDao().appWorkoutOnce(opened)?.epoc != null }
        assertNull(runBlocking { db.healthDao().appWorkoutOnce(preview)?.epoc })
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
