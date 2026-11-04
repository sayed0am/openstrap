package org.openstrap.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
import java.time.ZoneOffset

/**
 * Charge's HRV factor over more than one night (n-days follow-up 10): each sleep in the run recharges by its own
 * night's HRV contributor, as its morning's Readiness scored it, not by tonight's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChargeHrvNightsTest {
    private val zone = ZoneOffset.UTC

    @get:Rule val zoneRule = ZoneRule(zone)

    private val date = LocalDate.of(2026, 1, 15)
    private val nights = 20

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(LocalTime.of(h, m)).atZone(zone).toEpochSecond()

    /**
     * The first point of the day's Charge and tonight's HRV contributor, with tonight's HRV at [tonightHrv] and every
     * earlier night's about 50 ms. Tonight begins after midnight, so the day's first point comes before any of it.
     */
    private suspend fun firstPoint(tonightHrv: Int): Pair<Recovery.ChargePoint, Int?> {
        val db = inMemoryDb()
        try {
            val dao = db.healthDao()
            val sleeps = (0 until nights).map { back ->
                val wake = date.minusDays(back.toLong())
                val start = if (back == 0) at(wake, 0, 30) else at(wake.minusDays(1), 23)
                SleepSession(start, at(wake, 7), DayKey.of(wake), nap = false, score = 80)
            }
            dao.insertSleepSessions(sleeps)
            dao.insertSleepStages(sleeps.map { SleepStage(it.start, it.end, it.start, SleepStage.LIGHT) })
            val hr = ArrayList<HeartRateSample>()
            val stress = ArrayList<StressSample>()
            var t = at(date.minusDays(nights.toLong()), 20)
            while (t < at(date, 20)) {
                val asleep = sleeps.any { t in it.start until it.end }
                hr += HeartRateSample(t, if (asleep) 54 + (t / 60 % 5).toInt() else 75 + (t / 60 % 5).toInt())
                if (t % 300 == 0L) stress += StressSample(t, if (asleep) 15 else 40)
                t += 60
            }
            dao.insertHeartRate(hr)
            dao.insertStress(stress)
            dao.insertHrv(
                sleeps.flatMapIndexed { back, s ->
                    (s.start until s.end step 300L).mapIndexed { j, ts -> HrvSample(ts, if (back == 0) tonightHrv else 47 + (j + back) % 7) }
                },
            )
            val r = RecoveryEngine(dao).compute(date, profile(), zone, at(date, 20))
            val hrv = r.readiness.contributors.firstOrNull { it.factor == Recovery.Factor.HrvBalance }?.score
            return r.charge.points.first() to hrv
        } finally {
            db.close()
        }
    }

    @Test fun `an earlier night recharges by its own HRV, whatever tonight's is`() = runTest {
        val (low, lowHrv) = firstPoint(25)
        val (high, highHrv) = firstPoint(95)
        // Tonight's contributor is far apart in the two, so a run that used it for every night would show it.
        assertNotEquals(lowHrv, highHrv)
        assertEquals(at(date, 0), low.t)
        assertEquals(low.t, high.t)
        assertEquals(low.value, high.value, 1e-4f)
    }
}
