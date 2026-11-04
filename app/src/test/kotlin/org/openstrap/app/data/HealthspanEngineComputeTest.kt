package org.openstrap.app.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.NightMetricsRow
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * [HealthspanEngine.compute] end to end on a real database: six weeks of minutes with heart rate and steps, a night's
 * sleep every day, and the resting HR logged for every other night, so the rest are worked out from the night's heart
 * rate. The estimate is the early one, from the weeks there are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HealthspanEngineComputeTest {
    private val zone = ZoneOffset.UTC

    @get:Rule val zoneRule = ZoneRule(zone)

    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    /** A Sunday, the day the estimate is made. */
    private val sunday = LocalDate.of(2025, 11, 30)
    private val days = 43

    private suspend fun seed() {
        val first = sunday.minusDays(days.toLong())
        val sleeps = (0 until days).map { k ->
            val wake = first.plusDays(k + 1L).atStartOfDay(zone).toEpochSecond()
            SleepSession(wake - 3600, wake + 7 * 3600, DayKey.of(first.plusDays(k + 1L)), nap = false, deepMinutes = 90, lightMinutes = 300, remMinutes = 90, awakeMinutes = 0)
        }
        dao.insertSleepSessions(sleeps)
        dao.insertSleepStages(
            sleeps.flatMap { s ->
                listOf(SleepStage(s.start, s.start + 3 * 3600, s.start, SleepStage.LIGHT), SleepStage(s.start + 3 * 3600, s.end, s.start, SleepStage.DEEP))
            },
        )
        dao.insertNightMetrics(
            sleeps.filterIndexed { i, _ -> i % 2 == 0 }.map { s -> NightMetricsRow(s.strapDate!!, 0, null, 0, 52f, null, null, 400, null, null) },
        )
        fun asleep(t: Long) = sleeps.any { t in it.start until it.end }
        val from = first.atStartOfDay(zone).toEpochSecond()
        val to = sunday.atStartOfDay(zone).toEpochSecond()
        val minutes = ArrayList<ActivityMinute>()
        var t = from
        while (t < to) {
            val i = ((t - from) / 60).toInt()
            val sleeping = asleep(t)
            minutes += ActivityMinute(t, if (sleeping || i % 3 != 0) 0 else 25, 0, 0, if (sleeping) 48 + i % 9 else 70 + i % 11)
            t += 60
        }
        dao.insertActivity(minutes)
    }

    @Test fun `the weekly estimate is made from a real database`() = runTest {
        seed()
        val outcome = HealthspanEngine(dao).compute(sunday, profile(birthYear = 1986), zone)
        assertNotNull(outcome.result)
        assertEquals(39, outcome.age)
        assertMatchesSnapshot(SNAPSHOT, outcome.toString())
    }

    @Test fun `a value replaced in place refreshes the cached estimate`() = runTest {
        seed()
        val engine = HealthspanEngine(dao)
        fun Healthspan.Result.restingHr() = terms.first { it.habit == Healthspan.Habit.RestingHr }.value
        val before = requireNotNull(engine.compute(sunday, profile(birthYear = 1986), zone).result).restingHr()
        // The same nights logged again, with a higher resting HR: as many rows as before, under the same keys.
        val first = sunday.minusDays(days.toLong())
        val again = (0 until days step 2).map { k -> NightMetricsRow(first.plusDays(k + 1L), 1, null, 0, 64f, null, null, 400, null, null) }
        dao.insertNightMetrics(again)
        val after = requireNotNull(engine.compute(sunday, profile(birthYear = 1986), zone).result).restingHr()
        assertNotEquals(before, after)
        // A fresh engine, with no cache, agrees with the refreshed one.
        assertEquals(after, requireNotNull(HealthspanEngine(dao).compute(sunday, profile(birthYear = 1986), zone).result).restingHr())
    }

    /**
     * The snapshot with its decimals compared to a part in ten thousand, not as text: `Math.exp`, `pow` and `log` may
     * differ in the last bit between CPUs, which changes a printed Float on CI's x86 runners but not on an ARM Mac.
     */
    private fun assertMatchesSnapshot(expected: String, actual: String) {
        val decimal = Regex("-?\\d+\\.\\d+(E-?\\d+)?")
        assertEquals(expected.replace(decimal, "#"), actual.replace(decimal, "#"))
        val want = decimal.findAll(expected).map { it.value.toDouble() }.toList()
        val got = decimal.findAll(actual).map { it.value.toDouble() }.toList()
        want.zip(got).forEach { (w, g) -> assertEquals("in $actual", w, g, maxOf(1e-4, abs(w) * 1e-4)) }
    }

    private companion object {
        /**
         * As worked out before the nights' heart rate and stages were read in batches, with SQLite's days in UTC; resting HR
         * and the time-asleep reference moved with Aune 2017 and Saint-Maurice 2024.
         */
        const val SNAPSHOT =
            "Outcome(result=Result(age=39, estimate=37.76176, low=34, high=42, pace=null, terms=[Term(habit=RestingHr, yea" +
            "rs=-0.68865836, value=51.85, reference=58.550003), Term(habit=Regularity, years=-0.342, value=98.50427, referen" +
            "ce=81.0), Term(habit=Steps, years=-0.2075821, value=8081.3955, reference=7803.0), Term(habit=Strength, years=" +
            "0.0, value=0.0, reference=0.0), Term(habit=Vo2max, years=0.0, value=null, reference=37.94), Term(habit=TimeAsl" +
            "eep, years=0.0, value=480.0, reference=420.0)], fitnessCapped=false, early=true, asOf=1764460800, sex=Male), w" +
            "ithheld=null, age=39, missing=[], firstEstimate=null, lastWeek=null)"
    }
}
