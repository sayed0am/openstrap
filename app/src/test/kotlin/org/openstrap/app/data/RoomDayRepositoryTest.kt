package org.openstrap.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.protocol.services.StepTotals
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/** A day as the repository reads and resolves it: the main sleep, naps, nights, trends and steps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomDayRepositoryTest {
    @get:Rule val zone = ZoneRule()

    private lateinit var db: OpenStrapDatabase
    private val date = LocalDate.of(2026, 1, 15)

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).toEpochSecond(ZoneOffset.UTC)

    /** Last night's sleep, a shorter one after it the same night, the night before's, and today's nap. */
    private val main = SleepSession(at(date.minusDays(1), 23), at(date, 7), score = 80)
    private val short = SleepSession(at(date, 8), at(date, 9))
    private val before = SleepSession(at(date.minusDays(2), 23), at(date.minusDays(1), 7), score = 70)
    private val nap = SleepSession(at(date, 14), at(date, 14, 40), nap = true)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            db.healthDao().insertSleepSessions(listOf(main, short, before, nap))
            db.healthDao().insertActivity((0 until 10).map { ActivityMinute(at(date, 10) + it * 60L, 100, 40, 1, 90) })
            db.healthDao().insertHrv(listOf(HrvSample(at(date, 3), 60), HrvSample(at(date.minusDays(1), 12), 40)))
        }
    }

    @After fun tearDown() = db.close()

    private fun repo(strapSteps: Flow<StepTotals?> = flowOf(null)) = RoomDayRepository(HealthRepository(db.healthDao()), strapSteps)

    private fun day(repo: RoomDayRepository = repo(), isToday: Boolean = false, check: (DayData) -> Boolean = { true }): DayData =
        runBlocking { withTimeout(10_000) { repo.day(date, isToday).first(check) } }

    @Test fun `the main sleep is the night's longest, with the night before's score and the day's naps`() {
        val d = day()
        assertEquals(main, d.sleep.main)
        assertEquals(main, d.sleep.mainAsDetected)
        assertEquals(70, d.sleep.previousScore)
        assertEquals(listOf(nap), d.sleep.naps)
        assertEquals(mapOf(nap.start to nap), d.sleep.napsAsDetected)
        assertNull(d.sleep.deletedMain)
    }

    @Test fun `the main sleep follows the same rule as the widgets`() {
        val d = day()
        assertEquals(SleepHistory.mainSleep(listOf(main, short, before, nap), DayWindow.nightEndingOn(date)), d.sleep.main)
    }

    @Test fun `a deleted main sleep gives way to the next, and with none left the longest deleted is offered back`() = runBlocking {
        val repo = repo()
        val health = HealthRepository(db.healthDao())
        health.deleteSleep(main)
        assertEquals(short, day(repo) { it.sleep.main != main }.sleep.main)
        health.deleteSleep(short)
        val d = day(repo) { it.sleep.main == null }
        assertEquals(main, d.sleep.deletedMain?.strap)
    }

    @Test fun `the nights run the fortnight to the day shown`() {
        val d = day()
        assertEquals(SleepHistory.NIGHTS, d.nights.size)
        assertEquals(date, d.nights.last().date)
        assertEquals(main, d.nights.last().session)
        assertEquals(60f, d.nights.last().hrv)
        assertEquals(listOf(60), d.sleepHrv.map { it.milliseconds })
    }

    @Test fun `the week's trends run to the day shown, with nothing where a day has no data`() {
        val d = day()
        assertEquals((6L downTo 0L).map { date.minusDays(it) }, d.hrvTrend.map { it.date })
        assertEquals(listOf(null, null, null, null, null, 40f, 60f), d.hrvTrend.map { it.value })
        assertEquals(7, d.restingTrend.size)
        assertEquals(List(7) { false }, d.restingEstimated)
        assertEquals(40f, d.baselines.hrv)
    }

    @Test fun `only today adds the strap's live count`() {
        val live = flowOf(StepTotals(1500, 1200, null))
        assertEquals(1000, day(repo(live)).steps)
        assertEquals(1500, day(repo(live), isToday = true).steps)
        assertEquals(1000, day(repo(live), isToday = true).minutes.sumOf { it.steps })
    }
}
