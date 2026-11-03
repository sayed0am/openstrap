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
import org.openstrap.app.data.db.SleepEdit
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
 * A night trimmed at its start through [RecoveryEngine], as on the Fold on 3 Nov 2026: 11:05 PM to 8:16 AM cut to
 * 3:00 AM to 8:16 AM, with the night's calmest heart rate in the cut hours. Charge's sleep term measures each minute
 * against the night's lowest half hour, taken from the strap's own night, so the sleep kept recharges at the same rate
 * as the same minutes of the untrimmed night. Before, the trimmed night's own, higher, lowest half hour made the
 * same minutes count as more restful.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChargeEditedNightTest {
    private val zone = ZoneOffset.UTC

    @get:Rule val zoneRule = ZoneRule(zone)

    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    private val date = LocalDate.of(2026, 1, 15)

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(LocalTime.of(h, m)).atZone(zone).toEpochSecond()

    private val bedtime = at(date.minusDays(1), 23, 5)
    private val cut = at(date, 3)
    private val wake = at(date, 8, 16)

    private suspend fun seed() {
        val before = SleepSession(at(date.minusDays(2), 23), at(date.minusDays(1), 7), DayKey.of(date.minusDays(1)), nap = false, score = 80)
        val tonight = SleepSession(bedtime, wake, DayKey.of(date), nap = false, score = 79)
        dao.insertSleepSessions(listOf(before, tonight))
        dao.insertSleepStages(
            listOf(
                SleepStage(before.start, before.end, before.start, SleepStage.LIGHT),
                SleepStage(bedtime, at(date, 1), bedtime, SleepStage.DEEP),
                SleepStage(at(date, 1), cut, bedtime, SleepStage.LIGHT),
                SleepStage(cut, at(date, 5), bedtime, SleepStage.LIGHT),
                SleepStage(at(date, 5), at(date, 6), bedtime, SleepStage.REM),
                SleepStage(at(date, 6), wake, bedtime, SleepStage.LIGHT),
            ),
        )
        val hr = ArrayList<HeartRateSample>()
        val stress = ArrayList<StressSample>()
        var t = at(date.minusDays(2), 20)
        while (t < at(date, 20)) {
            val asleep = t in before.start until before.end || t in bedtime until wake
            // The night's calm part is in the hours the trim cuts.
            val bpm = when {
                t in bedtime until cut -> 54
                asleep -> 68
                else -> 75 + (t / 60 % 5).toInt()
            }
            hr += HeartRateSample(t, bpm)
            if (t % 300 == 0L) stress += StressSample(t, if (asleep) 15 else 40)
            t += 60
        }
        dao.insertHeartRate(hr)
        dao.insertStress(stress)
        // The strap's daytime resting heart rate, so trimming the night can't move it.
        dao.insertRestingHr((0..8).map { RestingHeartRate(date.minusDays(it.toLong()), 66) })
    }

    private suspend fun compute() = RecoveryEngine(dao).compute(date, profile(), zone, at(date, 20))

    /** How much of the headroom each 5-minute step of the kept sleep leaves, by its start. Charge doesn't change it. */
    private fun rates(c: Recovery.ChargeDay): Map<Long, Float> = c.points.zipWithNext()
        .filter { (a, b) -> a.t >= cut + 300 && b.t <= wake - 300 && a.state == Recovery.State.Sleep && b.state == Recovery.State.Sleep }
        .associate { (a, b) -> a.t to (100 - b.value) / (100 - a.value) }

    @Test
    fun aTrimmedNightsKeptSleepRechargesAsItDidUntrimmed() = runTest {
        seed()
        val whole = compute()
        dao.upsertSleepEdit(SleepEdit(bedtime, nap = false, start = cut, end = wake, editedAt = 1))
        val trimmed = compute()

        // The trim is what the rest of the app shows: tonight's own lowest half hour is the kept sleep's.
        assertEquals(cut, trimmed.tonight!!.start)
        assertEquals(68f, trimmed.tonight!!.restingHr!!, 0.5f)
        assertEquals(54f, whole.tonight!!.restingHr!!, 0.5f)

        val before = rates(whole.charge)
        val after = rates(trimmed.charge)
        assertTrue("steps ${after.size}", after.size > 50)
        assertEquals(before.keys, after.keys)
        for ((t, r) in after) assertEquals("at $t", before.getValue(t), r, 1e-4f)

        // Less sleep still ends the night lower.
        assertTrue(trimmed.charge.atWake!! < whole.charge.atWake!! - 3f)
    }

    /**
     * A night with no edit keeps its own lowest half hour, so its Charge is what it was before the strap's night became
     * the reference. The figures are the ones this night gave then, but for the night before it, which since 2026-11-04
     * recharges against its own lowest half hour (68 bpm) rather than tonight's (54), so the day starts a little higher.
     * The single night case, where nothing moved, is pinned in [ChargeNightsTest].
     */
    @Test
    fun anUneditedNightsChargeIsUnchanged() = runTest {
        seed()
        val c = compute().charge

        // At wake, latest, recharged, drained by living, by exercise and stress, points, and the points' sum.
        val figures = "%.3f %.3f %.3f %.3f %.3f %d %.1f".format(
            java.util.Locale.ROOT, c.atWake, c.latest, c.recharged, c.drainedLiving, c.drainedExercise + c.drainedStress,
            c.points.size, c.points.sumOf { it.value.toDouble() },
        )
        assertEquals("96.571 68.048 23.597 28.525 0.000 241 20548.5", figures)
    }
}
