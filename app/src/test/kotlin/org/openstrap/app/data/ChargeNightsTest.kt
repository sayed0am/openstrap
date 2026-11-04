package org.openstrap.app.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.RestingHeartRate
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
 * Charge over more than one night (data integrity item 13 and the 8-day run): a past day counts the evening part of a
 * night that ends the next morning as sleep, and each sleep in the run recharges against its own night's lowest half
 * hour, not the scored day's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChargeNightsTest {
    private val zone = ZoneOffset.UTC

    @get:Rule val zoneRule = ZoneRule(zone)

    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    private val date = LocalDate.of(2026, 1, 15)

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(LocalTime.of(h, m)).atZone(zone).toEpochSecond()

    /** A night from 23:00 the evening before [morning] to 07:00 on it, light sleep throughout, at [bpm]. */
    private class Night(val session: SleepSession, val bpm: Int)

    private fun night(morning: LocalDate, bpm: Int) =
        Night(SleepSession(at(morning.minusDays(1), 23), at(morning, 7), DayKey.of(morning), nap = false, score = 80), bpm)

    /** [nights] with their stages, heart rate and stress over the days around them, awake at about 75 bpm. */
    private suspend fun seed(vararg nights: Night) = seed(dao, *nights)

    private suspend fun seed(dao: org.openstrap.app.data.db.HealthDao, vararg nights: Night) {
        dao.insertSleepSessions(nights.map { it.session })
        dao.insertSleepStages(nights.map { SleepStage(it.session.start, it.session.end, it.session.start, SleepStage.LIGHT) })
        val hr = ArrayList<HeartRateSample>()
        val stress = ArrayList<StressSample>()
        var t = at(date.minusDays(3), 20)
        while (t < at(date.plusDays(1), 20)) {
            val asleep = nights.firstOrNull { t in it.session.start until it.session.end }
            hr += HeartRateSample(t, asleep?.bpm ?: (75 + (t / 60 % 5).toInt()))
            if (t % 300 == 0L) stress += StressSample(t, if (asleep != null) 15 else 40)
            t += 60
        }
        dao.insertHeartRate(hr)
        dao.insertStress(stress)
        dao.insertRestingHr((-1..8).map { RestingHeartRate(date.minusDays(it.toLong()), 66) })
    }

    private suspend fun compute(d: LocalDate, now: Long) = RecoveryEngine(dao).compute(d, profile(), zone, now)

    @Test
    fun `a past day counts the evening of a night ending the next morning as sleep`() = runTest {
        val last = night(date, 58)
        val next = Night(SleepSession(at(date, 23), at(date.plusDays(1), 7), DayKey.of(date.plusDays(1)), score = 80), 58)
        seed(last, next)
        val r = compute(date, at(date.plusDays(3), 12))

        // The day's own night is still the one that ended this morning.
        assertEquals(last.session.end, r.tonight?.end)
        val evening = r.charge.points.filter { it.t in at(date, 23) + 300..r.charge.points.last().t }
        assertTrue("points ${evening.size}", evening.size >= 10)
        assertTrue(evening.joinToString { it.state.name }, evening.all { it.state == Recovery.State.Sleep })
        assertTrue(evening.last().value > evening.first().value)
    }

    @Test
    fun `each night in the run recharges against its own lowest half hour`() = runTest {
        // The night before recharges against its own 68 bpm whatever tonight's is, so the day starts at the same Charge.
        // Tonight begins after midnight, so the day's first point comes before any of it.
        suspend fun firstPoint(tonightBpm: Int): Recovery.ChargePoint {
            val other = inMemoryDb()
            try {
                val tonight = Night(SleepSession(at(date, 0, 30), at(date, 7), DayKey.of(date), score = 80), tonightBpm)
                seed(other.healthDao(), night(date.minusDays(1), 68), tonight)
                val r = RecoveryEngine(other.healthDao()).compute(date, profile(), zone, at(date.plusDays(3), 12))
                assertEquals(tonightBpm.toFloat(), r.tonight!!.restingHr!!, 0.5f)
                return r.charge.points.first()
            } finally {
                other.close()
            }
        }
        val calm = firstPoint(54)
        val restless = firstPoint(64)
        assertEquals(at(date, 0), calm.t)
        assertEquals(calm.t, restless.t)
        assertEquals(calm.value, restless.value, 1e-4f)
    }

    /** One unedited night: what it gave before each night had its own reference. */
    @Test
    fun `a single unedited night's Charge is unchanged`() = runTest {
        seed(night(date, 56))
        val c = compute(date, at(date, 20)).charge

        // At wake, latest, recharged, drained by living, by exercise and stress, points, and the points' sum.
        val figures = "%.3f %.3f %.3f %.3f %.3f %d %.1f".format(
            java.util.Locale.ROOT, c.atWake, c.latest, c.recharged, c.drainedLiving, c.drainedExercise + c.drainedStress,
            c.points.size, c.points.sumOf { it.value.toDouble() },
        )
        assertEquals("96.827 65.551 32.230 31.324 0.000 241 19863.0", figures)
    }
}
