package org.openstrap.app.ui.dashboard

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.DayData
import org.openstrap.app.data.DayRepository
import org.openstrap.app.data.DayValue
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.Healthspan
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.SleepDay
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.DailyCaloriesRow
import org.openstrap.app.data.db.DailyPai
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.testing.FixedClock
import org.openstrap.app.testing.LocaleRule
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.StageSpan
import org.openstrap.app.presentation.TrendPoint
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/** The day view model only maps a [DayData] to what the screens draw (audit A3), and hands the Activity screens state (A11). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelUiStateTest {
    @get:Rule val zone = ZoneRule()
    @get:Rule val locale = LocaleRule()

    private val date = LocalDate.of(2026, 1, 15)
    private fun at(h: Int, m: Int = 0) = date.atTime(h, m).toEpochSecond(ZoneOffset.UTC)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `an empty day maps to an empty loaded state`() {
        val s = DayViewModel.toUiState(DayData(date))
        assertTrue(s.loaded)
        assertEquals(date, s.date)
        assertEquals(DayWindow.of(date).from, s.dayStart)
        assertEquals(DayWindow.of(date).to, s.dayEnd)
        assertNull(s.restingHeartRate)
        assertFalse(s.restingEstimated)
        assertNull(s.sleep)
        assertNull(s.sleepBreakdown)
        assertEquals(List(24) { 0f }, s.hourlySteps)
    }

    @Test fun `steps come by the hour, and the shown total stays the repository's`() {
        val minutes = (0 until 5).map { ActivityMinute(at(9) + it * 60L, 100, 40, 1, null) } + ActivityMinute(at(13), 50, 40, 1, null)
        val s = DayViewModel.toUiState(DayData(date, minutes = minutes, steps = 900))
        assertEquals(900, s.steps)
        assertEquals(550, s.minuteSteps)
        assertEquals(500f, s.hourlySteps[9])
        assertEquals(50f, s.hourlySteps[13])
    }

    @Test fun `the trends carry their weekday labels, and today's resting value and flag come from the last day`() {
        val days = (6L downTo 0L).map { date.minusDays(it) }
        val data = DayData(
            date,
            restingTrend = days.map { DayValue(it, if (it == date) 52f else null) },
            restingEstimated = days.map { it == date },
            hrvTrend = days.map { DayValue(it, 40f) },
        )
        val s = DayViewModel.toUiState(data)
        val label = { d: LocalDate -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2) }
        assertEquals(days.map { TrendPoint(label(it), if (it == date) 52f else null) }, s.restingTrend)
        assertEquals("Th", s.restingTrend.last().label)
        assertEquals(52, s.restingHeartRate)
        assertTrue(s.restingEstimated)
        assertEquals(days.map { TrendPoint(label(it), 40f) }, s.hrvTrend)
    }

    @Test fun `the sleep's stages become spans, and only naps with stages get a breakdown`() {
        val main = SleepSession(at(0), at(7), score = 80)
        val stages = listOf(SleepStage(at(0), at(3), main.start, SleepStage.DEEP), SleepStage(at(3), at(7), main.start, SleepStage.AWAKE))
        val nap = SleepSession(at(14), at(14, 30), nap = true)
        val bare = SleepSession(at(16), at(16, 20), nap = true)
        val napStages = listOf(SleepStage(at(14), at(14, 30), nap.start, SleepStage.LIGHT))
        val deleted = SleepEdits.Resolved(main, null)
        val data = DayData(
            date,
            sleep = SleepDay(
                main = main, mainStages = stages, naps = listOf(nap, bare), napStages = mapOf(nap.start to napStages, bare.start to emptyList()),
                previousScore = 70, mainAsDetected = main, strapStages = stages, deletedMain = deleted, neighbours = listOf(main, nap),
            ),
        )
        val s = DayViewModel.toUiState(data)
        assertEquals(main, s.sleep)
        assertEquals(70, s.previousSleepScore)
        assertEquals(stages.map { StageSpan(it.start, it.end, it.stage) }, s.sleepStages)
        assertEquals(s.sleepStages, s.strapSleepStages)
        assertEquals(3 * 3600L, s.sleepBreakdown?.asleepSeconds)
        assertEquals(setOf(nap.start, bare.start), s.napStages.keys)
        assertEquals(setOf(nap.start), s.napBreakdowns.keys)
        assertEquals(deleted, s.deletedSleep)
        assertEquals(listOf(main, nap), s.sleepNeighbours)
    }

    @Test fun `heart rate and the night's readings become charts`() {
        val hr = (0 until 10).map { HeartRateSample(at(10) + it * 60L, 60 + it) }
        val data = DayData(date, heartRate = hr, nightHeartRate = hr, sleepHrv = listOf(HrvSample(at(2), 55)), hrv = listOf(HrvSample(at(12), 45)))
        val s = DayViewModel.toUiState(data)
        assertEquals(69, s.latestHeartRate)
        assertTrue(s.heartRate.isNotEmpty())
        assertEquals(s.heartRate, s.sleepHeartRate)
        assertEquals(listOf(ChartPoint(at(2), 55f)), s.sleepHrv)
        assertEquals(listOf(ChartPoint(at(12), 45f)), s.hrv)
    }

    @Test fun `the Activity screens get the day's Heart points and energy as state`() = runTest {
        val repo = FakeDays()
        val clock = FixedClock(Instant.parse("2026-01-15T12:00:00Z"))
        val vm = DayViewModel(repo, clock = { clock.clock }, work = UnconfinedTestDispatcher(testScheduler))
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.activityHistory.collect {} }
        val h = vm.activityHistory.first { it.date == date }
        assertEquals(date.minusDays(ActivityHistory.HEART_POINTS_DAYS - 1L) to date, repo.paiWindow)
        assertEquals(date.minusDays(ActivityHistory.CALORIES_DAYS - 1L) to date, repo.energyWindow)
        assertEquals(HeartPointsDay(12f, 80f), h.heartPointsOn(date)[date])
        assertEquals(emptyMap<LocalDate, HeartPointsDay>(), h.heartPointsOn(date.minusDays(1)))
        assertEquals(CaloriesDay(400f, 2400f, 150f, true), h.caloriesOn(date, date.minusDays(6))?.get(date))
        // A range only gets its own days, and another day's energy is still loading.
        assertEquals(setOf(date), h.caloriesOn(date, date)?.keys)
        assertNull(h.caloriesOn(date.minusDays(1), date.minusDays(7)))
        collector.cancel()
        vm.viewModelScope.coroutineContext[Job]?.cancel()
    }

    @Test fun `Steps' goal facts get the days before and the best before today`() = runTest {
        val repo = FakeDays()
        val clock = FixedClock(Instant.parse("2026-01-15T12:00:00Z"))
        val vm = DayViewModel(repo, clock = { clock.clock }, work = UnconfinedTestDispatcher(testScheduler))
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.activityHistory.collect {} }
        // The stored best is today's own, so the best before today is read from the whole history.
        repo.stored.value = org.openstrap.app.data.db.PersonalBest(
            org.openstrap.app.data.Rewards.Kind.MOST_STEPS, 30000.0, date.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
        )
        var h = vm.activityHistory.first { it.date == date && it.stepsBest == 25000 }
        assertEquals(date.minusDays(ActivityHistory.STEPS_DAYS.toLong()) to date.plusDays(6), repo.stepsWindow)
        assertEquals(GoalFacts(9500, 8000, 25000, 12000, 3), h.stepsFacts(date, 9500, 8000))
        // An older stored best stands, raised to the loaded days' when they're higher.
        repo.stored.value = org.openstrap.app.data.db.PersonalBest(
            org.openstrap.app.data.Rewards.Kind.MOST_STEPS, 11000.0, date.minusDays(100).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
        )
        h = vm.activityHistory.first { it.stepsBest == 12000 }
        assertEquals(12000, h.stepsFacts(date, 9500, 8000)!!.best)
        collector.cancel()
        vm.viewModelScope.coroutineContext[Job]?.cancel()
    }

    @Test fun `the day's state carries the sleep normals and the stress strip`() {
        val nights = (13 downTo 0).map { back ->
            val d = date.minusDays(back.toLong())
            val start = d.minusDays(1).atTime(23, 0).toEpochSecond(ZoneOffset.UTC)
            org.openstrap.app.data.SleepNight(d, SleepSession(start, start + 8 * 3600, lightMinutes = 480 - back, deepMinutes = 0, remMinutes = 0))
        }
        val stress = listOf(org.openstrap.app.data.db.StressSample(at(9), 50), org.openstrap.app.data.db.StressSample(at(9, 30), 70))
        val s = DayViewModel.toUiState(DayData(date, nights = nights, stress = stress))
        assertEquals(14, s.sleepLengthNormal?.days)
        assertEquals(11 * 60f, s.bedtimeNormal!!.mean, 0.01f)
        assertEquals(24, s.stressStrip.size)
        assertEquals(0.6f, s.stressStrip[9]!!, 0.0001f)
        assertNull(s.stressStrip[10])
    }

    /** A day repository with one day of Heart points and energy, noting the windows asked for. */
    private inner class FakeDays : DayRepository {
        var paiWindow: Pair<LocalDate, LocalDate>? = null
        var energyWindow: Pair<LocalDate, LocalDate>? = null
        var stepsWindow: Pair<LocalDate, LocalDate>? = null
        val stored = kotlinx.coroutines.flow.MutableStateFlow<org.openstrap.app.data.db.PersonalBest?>(null)

        override fun dailySteps(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> {
            stepsWindow = from to to
            return flowOf(mapOf(date to 9000, date.minusDays(1) to 8000, date.minusDays(2) to 12000, date.minusDays(3) to 100))
        }

        override fun personalBest(kind: String): Flow<org.openstrap.app.data.db.PersonalBest?> = stored
        override suspend fun mostStepsBefore(date: LocalDate): Int = 25000

        override fun day(date: LocalDate, isToday: Boolean): Flow<DayData> = flowOf(DayData(date, isToday))
        override fun daysWithData(): Flow<Set<LocalDate>> = flowOf(emptySet())
        override suspend fun hourlySteps(from: LocalDate, to: LocalDate): Map<LocalDate, List<Float>> = emptyMap()
        override suspend fun recovery(date: LocalDate, profile: UserProfile): RecoveryResult = error("not read here")
        override suspend fun healthspan(date: LocalDate, profile: UserProfile): Healthspan.Outcome = error("not read here")
        override fun changes(): Flow<Unit> = emptyFlow()
        override fun deletedSleep(): Flow<List<SleepEdits.Resolved>> = flowOf(emptyList())
        override suspend fun setSleepTimes(strap: SleepSession, start: Long, end: Long) = Unit
        override suspend fun deleteSleep(strap: SleepSession) = Unit
        override suspend fun restoreSleep(strap: SleepSession) = Unit

        override fun dailyPai(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DailyPai>> {
            paiWindow = from to to
            return flowOf(mapOf(date to DailyPai(date.toString(), 12f, 80f, at(9))))
        }

        override fun dailyCalories(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DailyCaloriesRow>> {
            energyWindow = from to to
            val row = DailyCaloriesRow(date, at(12), 2400f, 400f, 1700f, 600, true, workoutKcal = 150f, walkingKcal = 100f, hourly = "")
            return flowOf(mapOf(date to row, date.minusDays(10) to row))
        }
    }
}
