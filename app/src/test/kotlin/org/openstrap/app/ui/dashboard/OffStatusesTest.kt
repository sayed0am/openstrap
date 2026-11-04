package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.Healthspan
import org.openstrap.app.data.History
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.presentation.ChartPoint
import java.time.LocalDate
import java.time.ZoneOffset

/** One off status per kind of value (picture cards plan, Data audit). */
class OffStatusesTest {
    private val zone = ZoneOffset.UTC
    private val date = LocalDate.of(2026, 1, 15)
    private val normal = History.Normal(50f, 40f, 60f, 20)

    @Test fun `a value against its normal`() {
        assertEquals(OffStatus.Above, offStatus(61f, normal))
        assertEquals(OffStatus.Below, offStatus(39f, normal))
        assertEquals(OffStatus.Usual, offStatus(60f, normal))
        assertEquals(OffStatus.Usual, offStatus(40f, normal))
        // Unknown either side is never news.
        assertEquals(OffStatus.Usual, offStatus(null, normal))
        assertEquals(OffStatus.Usual, offStatus(90f, null))
        assertEquals(OffStatus.Above, bodyOffStatus(70f, normal))
    }

    @Test fun `Load is off both ways, and low is only so far before the evening`() {
        assertEquals(OffStatus.Above, loadOffStatus(126f, 100f))
        assertEquals(OffStatus.Below, loadOffStatus(74f, 100f))
        assertEquals(OffStatus.Usual, loadOffStatus(100f, 100f))
        assertEquals(OffStatus.Usual, loadOffStatus(74f, 100f, endOfDay = false))
        assertEquals(OffStatus.Above, loadOffStatus(126f, 100f, endOfDay = false))
        assertEquals(OffStatus.Usual, loadOffStatus(500f, null))
        assertEquals(OffStatus.Usual, loadOffStatus(null, 100f))
    }

    private fun result(
        score: Int?, usual: History.Normal? = null, odi: Float? = null, odiUsual: History.Normal? = null,
    ) = RecoveryResult(
        date, Recovery.Readiness(score, emptyList(), 30, false, emptyList(), false),
        Recovery.ChargeDay(emptyList(), null, null, 0f, 0f, 0f, 0f, null, null, anchored = true), emptyList(),
        0f, null, 52f, 190f, true, Recovery.StressThreshold(60, null, 0),
        breathingDisturbances = odi?.let { Recovery.BreathingDisturbances(it, odiUsual?.mean, 10) },
        readinessUsual = usual, disturbancesUsual = odiUsual,
    )

    @Test fun `Recovery against its usual`() {
        val usual = History.Normal(70f, 62f, 78f, 25)
        assertEquals(OffStatus.Below, recoveryOffStatus(result(55, usual)))
        assertEquals(OffStatus.Above, recoveryOffStatus(result(90, usual)))
        assertEquals(OffStatus.Usual, recoveryOffStatus(result(70, usual)))
        assertEquals(OffStatus.Usual, recoveryOffStatus(result(30, null)))
        assertEquals(OffStatus.Usual, recoveryOffStatus(result(null, usual)))
        assertEquals(OffStatus.Usual, recoveryOffStatus(null))
    }

    @Test fun `breathing disturbances against their band`() {
        val band = History.Normal(2f, 1f, 3f, 12)
        assertEquals(OffStatus.Above, breathingOffStatus(result(70, odi = 5f, odiUsual = band)))
        assertEquals(OffStatus.Usual, breathingOffStatus(result(70, odi = 2.5f, odiUsual = band)))
        assertEquals(OffStatus.Below, breathingOffStatus(result(70, odi = 0f, odiUsual = band)))
        assertEquals(OffStatus.Usual, breathingOffStatus(result(70, odi = 5f)))
        // Fewer isn't news: the tag's words drop it.
        assertNull(offTag(OffWords.MoreOnly, OffStatus.Below, org.openstrap.app.testing.FileStrings()))
    }

    private fun night(d: LocalDate, bedHour: Int, bedMinute: Int, hours: Long): SleepNight {
        val bedDay = if (bedHour >= 12) d.minusDays(1) else d
        val start = bedDay.atTime(bedHour, bedMinute).toEpochSecond(zone)
        val end = start + hours * 3600
        return SleepNight(d, SleepSession(start, end, nap = false, deepMinutes = (hours * 60).toInt(), lightMinutes = 0, remMinutes = 0))
    }

    @Test fun `last night's length against the nights before`() {
        val nights = (13 downTo 0).map { night(date.minusDays(it.toLong()), 23, 0, if (it % 2 == 0) 7 else 8) }
        val n = requireNotNull(sleepLengthNormal(nights, date))
        assertEquals(7.5f * 3600, n.mean, 1f)
        assertEquals(14, n.days)
        val short = DayUiState(date = date, sleep = SleepSession(0, 5 * 3600L), sleepLengthNormal = n)
        assertEquals(OffStatus.Below, sleepLengthOffStatus(short))
        val long = DayUiState(date = date, sleep = SleepSession(0, 10 * 3600L), sleepLengthNormal = n)
        assertEquals(OffStatus.Above, sleepLengthOffStatus(long))
        // Six nights aren't a normal yet.
        assertNull(sleepLengthNormal(nights.takeLast(6), date))
    }

    @Test fun `bedtime against the usual, at least half an hour either side`() {
        assertEquals(0, minutesAfterNoon(date.atTime(12, 0).toEpochSecond(zone), zone))
        assertEquals(11 * 60 + 30, minutesAfterNoon(date.atTime(23, 30).toEpochSecond(zone), zone))
        assertEquals(12 * 60 + 15, minutesAfterNoon(date.atTime(0, 15).toEpochSecond(zone), zone))
        // Every night at 23:00 or 23:10: a narrow spread, widened to 30 minutes.
        val nights = (9 downTo 0).map { night(date.minusDays(it.toLong()), 23, if (it % 2 == 0) 0 else 10, 8) }
        val n = requireNotNull(bedtimeNormal(nights, date, zone))
        assertEquals(11 * 60 + 5f, n.mean, 0.01f)
        assertEquals(n.mean + 30f, n.high, 0.01f)
        assertEquals(n.mean - 30f, n.low, 0.01f)
        fun state(bed: SleepNight) = DayUiState(date = date, sleep = bed.session, bedtimeNormal = n)
        assertEquals(OffStatus.Above, bedtimeOffStatus(state(night(date, 0, 30, 7)), zone))
        assertEquals(OffStatus.Below, bedtimeOffStatus(state(night(date, 22, 0, 9)), zone))
        assertEquals(OffStatus.Usual, bedtimeOffStatus(state(night(date, 23, 20, 8)), zone))
        assertEquals(OffStatus.Usual, bedtimeOffStatus(DayUiState(date = date, bedtimeNormal = n), zone))
    }

    private fun habit(age: Int, low: Int, high: Int) = Healthspan.Outcome(
        Healthspan.Result(age, (low + high) / 2f, low, high, null, emptyList(), false, false, 0, UserProfile.Sex.Male),
        null,
    )

    @Test fun `Habit Age is off only when the whole range is past your age`() {
        assertEquals(OffStatus.Above, habitAgeOffStatus(habit(40, 41, 45)))
        assertEquals(OffStatus.Below, habitAgeOffStatus(habit(40, 34, 39)))
        assertEquals(OffStatus.Usual, habitAgeOffStatus(habit(40, 40, 44)))
        assertEquals(OffStatus.Usual, habitAgeOffStatus(habit(40, 36, 40)))
        assertEquals(OffStatus.Usual, habitAgeOffStatus(Healthspan.Outcome(null, null)))
        assertEquals(OffStatus.Usual, habitAgeOffStatus(null))
    }

    @Test fun `stress is the day's mean against your normal`() {
        val samples = listOf(ChartPoint(0, 60f), ChartPoint(60, 80f))
        assertEquals(70f, stressMean(samples)!!, 0.001f)
        assertNull(stressMean(emptyList()))
        assertEquals(OffStatus.Above, stressOffStatus(samples, History.Normal(40f, 30f, 50f, 20)))
        assertEquals(OffStatus.Usual, stressOffStatus(emptyList(), History.Normal(40f, 30f, 50f, 20)))
    }
}
