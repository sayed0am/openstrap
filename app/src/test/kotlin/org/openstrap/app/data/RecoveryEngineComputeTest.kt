package org.openstrap.app.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.StressSample
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * [RecoveryEngine.compute] end to end on a real database (audit test 9): three weeks of nights from 23:00 to 07:00,
 * so every sleep crosses midnight, heart rate every minute, steps by day, HRV on every night but the last, and a
 * birthday year before the one the test runs in, so the age is the one the scored day had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryEngineComputeTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @get:Rule val zoneRule = ZoneRule(zone)

    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    /** The day scored: a year before any day the suite runs on, so its age differs from today's. */
    private val date = LocalDate.of(2025, 12, 30)
    private val nights = 21

    private fun at(d: LocalDate, t: LocalTime) = d.atTime(t).atZone(zone).toEpochSecond()

    private suspend fun seed() {
        val first = date.minusDays(nights.toLong())
        val sleeps = (0 until nights).map { k ->
            val wake = first.plusDays(k + 1L)
            val start = at(wake.minusDays(1), LocalTime.of(23, 0))
            SleepSession(
                start, at(wake, LocalTime.of(7, 0)), DayKey.of(wake), nap = false, score = 80,
                deepMinutes = 90, lightMinutes = 300, remMinutes = 90, awakeMinutes = 0, wakeCount = 0,
            )
        }
        dao.insertSleepSessions(sleeps)
        dao.insertSleepStages(
            sleeps.flatMap { s ->
                listOf(
                    SleepStage(s.start, s.start + 2 * 3600, s.start, SleepStage.LIGHT),
                    SleepStage(s.start + 2 * 3600, s.start + 210 * 60, s.start, SleepStage.DEEP),
                    SleepStage(s.start + 210 * 60, s.start + 5 * 3600, s.start, SleepStage.REM),
                    SleepStage(s.start + 5 * 3600, s.end, s.start, SleepStage.LIGHT),
                )
            },
        )
        fun asleep(t: Long) = sleeps.any { t in it.start until it.end }
        val from = at(first, LocalTime.MIDNIGHT)
        val to = at(date, LocalTime.of(20, 0))
        val hr = ArrayList<HeartRateSample>()
        val activity = ArrayList<ActivityMinute>()
        val stress = ArrayList<StressSample>()
        var t = from
        while (t < to) {
            val i = ((t - from) / 60).toInt()
            val hour = java.time.Instant.ofEpochSecond(t).atZone(zone).hour
            val walking = !asleep(t) && hour == 17
            hr += HeartRateSample(t, if (asleep(t)) 50 + i % 7 else if (walking) 115 + i % 10 else 68 + i % 9)
            val steps = when {
                asleep(t) -> 0
                walking -> 110
                hour in 8..21 && i % 4 == 0 -> 20
                else -> 0
            }
            activity += ActivityMinute(t, steps, intensity = if (steps > 0) 40 else 0, kind = 0, heartRate = null)
            if (!asleep(t) && i % 5 == 0) stress += StressSample(t, 20 + i % 30)
            t += 60
        }
        dao.insertHeartRate(hr)
        dao.insertActivity(activity)
        dao.insertStress(stress)
        // HRV every five minutes asleep, on every night but the one before the scored day.
        dao.insertHrv(
            sleeps.dropLast(1).flatMap { s -> (s.start until s.end step 300L).mapIndexed { j, ts -> HrvSample(ts, 45 + j % 15) } },
        )
    }

    @Test fun `a day is scored from a real database`() = runTest {
        seed()
        val now = at(date, LocalTime.of(20, 0))
        val r = RecoveryEngine(dao).compute(date, profile(birthYear = 1986), zone, now)

        // Tonight's sleep started the evening before and ended on the scored morning.
        val tonight = requireNotNull(r.tonight)
        assertEquals(at(date.minusDays(1), LocalTime.of(23, 0)), tonight.start)
        assertEquals(at(date, LocalTime.of(7, 0)), tonight.end)
        assertEquals(date.atStartOfDay(zone).toEpochSecond(), tonight.day)
        assertEquals(480, tonight.asleepMinutes)
        // No HRV that night: the night has none, and its resting HR still comes from its heart rate.
        assertNull(tonight.hrv)
        assertEquals(0, tonight.hrvSamples)
        assertNotNull(tonight.restingHr)
        // Age 39 on the scored day (1986 to 2025), not today's: Tanaka gives 208 - 0.7 x 39.
        assertEquals(180.7f, r.maxHr, 0.01f)
        assertTrue(r.maxHrFromAge)

        assertEquals(SNAPSHOT, snapshot(r))
    }

    /** The figures the engine works out, so reading its inputs another way can't change them. */
    private fun snapshot(r: RecoveryResult) = listOf(
        r.readiness.score, r.readiness.baselineNights, r.readinessTrend.map { it.second },
        r.load, r.chronicLoad, r.restingHr, r.stressThreshold.value, r.charge.latest, r.charge.atWake, r.charge.points.size,
        r.strain, r.strainPercentile, r.activity?.steps, r.activity?.mvpa?.moderateMinutes, r.activity?.distanceMeters,
        r.energy?.totalKcal, r.energy?.activeKcal, r.energy?.minutesWithData, r.regularity?.sri, r.sleepHealth?.count,
        r.workoutLoad, r.everydayLoad, r.usualBedtime, r.dailyLoads.size,
    ).joinToString("|")

    private companion object {
        /**
         * As worked out before compute read each table once: Readiness is withheld on the night without HRV, the six
         * days before it score 84, and the hour's walk each evening is the day's moderate minutes. Charge's latest and
         * at wake moved in the fifth figure on 2026-11-04, when each earlier night began to recharge against its own
         * lowest half hour rather than tonight's. The load (and Strain with it) rose 3 % on 2026-11-04, when the
         * evening walk, an hour at over 30 % of reserve, became a sustained run and its first two minutes counted too,
         * as Charge drains them.
         */
        const val SNAPSHOT = "null|20|[84, 84, 84, 84, 84, 84, null]|52.26888|52.26888|55.833332|50|51.24335|96.531845|241|" +
            "5.292476|100|9900|60|7172.55|1736.3221|423.3075|1200|100.0|3|0.0|52.26888|23:00|21"
    }
}
