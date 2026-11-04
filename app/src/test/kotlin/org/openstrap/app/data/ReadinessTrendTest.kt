package org.openstrap.app.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.StrapReadinessRow
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Readiness's past days and the strap's score, over ten weeks of nights from 23:00 to 07:00 (data integrity item 23.2,
 * n-days follow-up 9): the first five weeks at an HRV around 40 ms, the rest around 60 ms, so a baseline that starts a
 * few nights later scores the same night differently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadinessTrendTest {
    private val zone = ZoneId.of("Asia/Kathmandu")

    @get:Rule val zoneRule = ZoneRule(zone)

    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    private val date = LocalDate.of(2026, 1, 15)
    private val nights = 70

    private fun at(d: LocalDate, t: LocalTime) = d.atTime(t).atZone(zone).toEpochSecond()

    /** The nights, each keyed by the strap to its morning less [strapLag] days. */
    private suspend fun seed(strapLag: Long = 0) {
        val first = date.minusDays(nights - 1L)
        val sleeps = (0 until nights).map { k ->
            val wake = first.plusDays(k.toLong())
            SleepSession(
                at(wake.minusDays(1), LocalTime.of(23, 0)), at(wake, LocalTime.of(7, 0)), DayKey.of(wake.minusDays(strapLag)),
                nap = false, score = 80, deepMinutes = 90, lightMinutes = 300, remMinutes = 90, awakeMinutes = 0, wakeCount = 0,
            )
        }
        dao.insertSleepSessions(sleeps)
        dao.insertSleepStages(
            sleeps.flatMap { s ->
                listOf(SleepStage(s.start, s.start + 3 * 3600, s.start, SleepStage.LIGHT), SleepStage(s.start + 3 * 3600, s.end, s.start, SleepStage.DEEP))
            },
        )
        dao.insertHeartRate(
            sleeps.flatMapIndexed { k, s -> (s.start until s.end step 60L).mapIndexed { i, t -> HeartRateSample(t, 50 + (i + k) % 7) } },
        )
        dao.insertHrv(
            sleeps.flatMapIndexed { k, s ->
                val usual = if (k < nights / 2) 40 else 60
                (s.start until s.end step 300L).mapIndexed { j, t -> HrvSample(t, usual - 3 + (j + k) % 7) }
            },
        )
    }

    private suspend fun compute(d: LocalDate) = RecoveryEngine(dao).compute(d, profile(), zone, at(date, LocalTime.of(20, 0)))

    @Test fun `each past day in the trend is scored against the baseline it had then`() = runTest {
        seed()
        val trend = compute(date).readinessTrend
        assertEquals((6 downTo 0).map { date.minusDays(it.toLong()) }, trend.map { it.first })
        for ((d, score) in trend) {
            val then = compute(d).readiness
            assertNotNull("$d", then.score)
            assertEquals("$d", then.score, score)
        }
        // The days differ, so a trend that reused one baseline, or a shorter one, would show it.
        assertTrue(trend.map { it.second }.toString(), trend.map { it.second }.distinct().size > 1)
    }

    @Test fun `the strap's score is the one for the strap's own night`() = runTest {
        // The strap keys every night to the day before its morning (its clock a day behind, say).
        seed(strapLag = 1)
        fun row(day: LocalDate, score: Int) = StrapReadinessRow(day, 0, score, null, null, null, null, null, null, "test")
        dao.insertStrapReadiness(listOf(row(date.minusDays(1), 77), row(date, 12)))
        val r = compute(date)
        assertEquals(date.minusDays(1), r.tonight?.strapDay)
        assertEquals(77, r.strapScore)
    }
}
