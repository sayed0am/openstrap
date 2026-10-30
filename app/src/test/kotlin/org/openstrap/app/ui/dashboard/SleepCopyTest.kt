package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.ui.components.ChartPoint
import java.time.LocalDate
import java.time.ZoneOffset

/** The Sleep tab's details (spec 5.2 and 6.1): night-scoped values come from the sleep, and say so. */
class SleepCopyTest {
    private val date = LocalDate.of(2026, 10, 17)
    private val midnight = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    private val session = SleepSession(start = midnight - 3600, end = midnight + 6 * 3600, score = 82, averageHeartRate = 55)

    /** Nine nights before this one with HRV 50 and 52 by turns, then tonight at [tonightHrv]. */
    private fun state(tonightHrv: Float? = 40f, sleep: SleepSession? = session): DayUiState {
        val before = (9L downTo 1L).map { back ->
            val d = date.minusDays(back)
            val start = d.atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 3600
            SleepNight(d, SleepSession(start, start + 7 * 3600, score = 80, averageHeartRate = 54), hrv = if (back % 2 == 0L) 50f else 52f, spo2 = 96f)
        }
        return DayUiState(
            date = date,
            sleep = sleep,
            nights = before + SleepNight(date, sleep, hrv = tonightHrv, spo2 = 94f),
            // Daytime HRV is far higher than the night's; a night-scoped value must never use it.
            hrv = listOf(ChartPoint(midnight + 14 * 3600, 90f)),
            sleepHrv = listOf(ChartPoint(midnight + 3600, 40f), ChartPoint(midnight - 7200, 41f)),
            sleepHeartRate = listOf(ChartPoint(midnight - 1800, 60f), ChartPoint(midnight + 3 * 3600, 48f), ChartPoint(midnight + 9 * 3600, 70f)),
        )
    }

    @Test fun `a night vital's value comes from the sleep, not the day`() {
        val s = state()
        assertEquals(40f, SleepCopy.nightValue(s, Metric.Hrv))
        assertEquals(94f, SleepCopy.nightValue(s, Metric.Spo2))
        // Heart rate's night value is the strap's average for the night; the lowest is only from readings while asleep.
        assertEquals(55f, SleepCopy.nightValue(s, Metric.HeartRate))
        assertEquals(48f, SleepCopy.lowestHeart(s)!!.y)
        // Readings outside the sleep (the 70 after waking) stay out of the night's line.
        assertEquals(listOf(60f, 48f), SleepCopy.nightSeries(s, Metric.HeartRate).map { it.y })
        assertEquals(listOf(40f), SleepCopy.nightSeries(s, Metric.Hrv).map { it.y })
        // No sleep, no night value, even with readings in the day.
        assertNull(SleepCopy.nightValue(state(sleep = null), Metric.Hrv))
    }

    @Test fun `a night vital reads against the nights before, as the Sleep tab's row does`() {
        val s = state()
        val normal = SleepCopy.nightNormal(s, Metric.Hrv)!!
        assertEquals(9, normal.days)
        assertEquals(51f, normal.mean, 0.2f)
        assertEquals(9, SleepCopy.nightNormalNights(s, Metric.Hrv))
        // The row and the detail agree: below your normal.
        val row = nightVitals(s).first { it.metric == Metric.Hrv }
        assertEquals(-1, row.direction)
        assertEquals("Below your normal", row.status)
        assertEquals("40", row.value)
        // Heart rate at night has no normal to read against.
        assertNull(SleepCopy.nightNormal(s, Metric.HeartRate))
    }

    @Test fun `the night's week ends on the night shown, with the same value as its Day`() {
        val week = SleepCopy.nightWeek(state(), Metric.Hrv)
        assertEquals(7, week.size)
        assertEquals(date to 40f, week.last())
        assertEquals(date.minusDays(6), week.first().first)
    }

    @Test fun `night sentences and labels say it was the night`() {
        assertEquals("Last night, below your normal.", DetailCopy.day(-1, DaySource.Night, today = true, days = 9))
        assertEquals("That night, within your normal.", DetailCopy.day(0, DaySource.Night, today = false, days = 9))
        assertEquals("Lowest 48 bpm at 03:00 last night, 55 on average while asleep.", SleepCopy.nightHeart(48, "03:00", 55, today = true))
        assertEquals("55 bpm on average while asleep that night.", SleepCopy.nightHeart(null, null, 55, today = false))
        assertEquals("ms, average asleep", SleepCopy.nightLabel(Metric.Hrv, Range.Day))
        assertEquals("ms, average of the last 7 nights", SleepCopy.nightLabel(Metric.Hrv, Range.Week))
        // Month and Year say when their days aren't only nights.
        assertTrue("day and night" in SleepCopy.nightLabel(Metric.Hrv, Range.Month))
        assertEquals("%, last 30 nights", SleepCopy.nightLabel(Metric.Spo2, Range.Month))
        assertEquals("No sleep" to "None recorded last night", SleepCopy.nightNoValue(hasSleep = false, today = true))
        assertEquals("No reading" to "Not measured that night", SleepCopy.nightNoValue(hasSleep = true, today = false))
    }

    @Test fun `night blood oxygen draws on its own table`() {
        assertEquals(DailyMetric.SleepSpo2, SleepCopy.nightDaily(Metric.Spo2))
        assertEquals(DailyMetric.Hrv, SleepCopy.nightDaily(Metric.Hrv))
        assertTrue(SleepCopy.dailyIsNightly(Metric.Breathing))
        assertFalse(SleepCopy.dailyIsNightly(Metric.SkinTemperature))
    }

    @Test fun `what shaped the score never uses the strap's band words`() {
        val contributors = Narrative.sleepContributors(session, 5 * 3600L + 50 * 60, 480, 45)
        val factors = SleepCopy.factors(contributors, 5 * 3600L + 50 * 60, 480, 45)
        assertEquals(listOf("Time asleep", "Staying asleep"), factors.map { it.name })
        assertEquals("5 h 50 of 8 h", factors[0].detail)
        assertEquals("45 min awake after falling asleep", factors[1].detail)
        Narrative.Rating.entries.forEach { r ->
            val w = SleepCopy.factorWord(r)
            listOf("Optimal", "Pay attention").forEach { assertFalse(w, it in w) }
        }
    }

    @Test fun `regularity's night sits against your usual bedtime`() {
        assertEquals(
            "Last night you fell asleep at 23:40 and woke at 07:05, 35 min later than usual.",
            SleepCopy.regularityNight(23 * 60 + 40, 7 * 60 + 5, usualBed = 23 * 60 + 5, today = true),
        )
        // Usual bedtime past midnight, kept past 24 h by SleepHistory.
        assertEquals(
            "That night you fell asleep at 23:50 and woke at 07:00, 25 min earlier than usual.",
            SleepCopy.regularityNight(23 * 60 + 50, 7 * 60, usualBed = 24 * 60 + 15, today = false),
        )
        assertEquals(
            "Last night you fell asleep at 00:10 and woke at 07:00, close to your usual.",
            SleepCopy.regularityNight(10, 7 * 60, usualBed = 24 * 60, today = true),
        )
        assertTrue(SleepCopy.regularity(SleepRegularity.Result(90f, 9000, null)).startsWith("Very regular"))
        assertTrue(SleepCopy.regularity(SleepRegularity.Result(null, 3000, 42f)).contains("about 42 min"))
    }

    @Test fun `naps say how many and how long`() {
        assertEquals("One nap, 45 min.", SleepCopy.naps(1, 45 * 60L))
        assertEquals("Two naps, 1 h 10 in all.", SleepCopy.naps(2, 70 * 60L))
        assertEquals("No naps" to "None recorded that day", SleepCopy.noNaps(today = false))
    }

    @Test fun `no sleep detail line uses an interpunct, semicolon or em dash`() {
        val s = state()
        val lines = listOf(
            SleepCopy.nightHeart(48, "03:00", 55, true), SleepCopy.nightHeart(null, null, 55, false),
            SleepCopy.heartWeek(listOf(52, null, 55)), SleepCopy.heartWeek(listOf(52)),
            SleepCopy.scoreLabel(true), SleepCopy.scoreLabel(false),
            SleepCopy.night("Short night", 5 * 3600L, listOf(7 * 3600L, 8 * 3600L, 6 * 3600L)),
            SleepCopy.regularityNight(23 * 60, 7 * 60, 23 * 60, true), SleepCopy.noNight(true), SleepCopy.noNight(false),
            SleepCopy.napsLabel(true), SleepCopy.naps(3, 3600), SleepCopy.napHeart(58, 64), SleepCopy.NAP_NO_STAGES,
            SleepCopy.REGULARITY_LABEL, SleepCopy.FELL_ASLEEP, SleepCopy.WOKE, SleepCopy.ASLEEP,
        ).filterNotNull() +
            Range.entries.flatMap { r -> NIGHT_VITALS.map { SleepCopy.nightLabel(it, r) } } +
            listOf(true, false).flatMap { t -> SleepCopy.nightNoValue(true, t).toList() + SleepCopy.nightNoValue(false, t).toList() + SleepCopy.noScore(true, t).toList() + SleepCopy.noScore(false, t).toList() + SleepCopy.noNaps(t).toList() } +
            listOf(null, SleepRegularity.Result(null, 3000, null), SleepRegularity.Result(null, 3000, 30f)).plus(listOf(60f, 75f, 82f, 92f).map { SleepRegularity.Result(it, 9000, null) })
                .flatMap { listOf(SleepCopy.regularity(it)) + SleepCopy.regularityState(it).toList() } +
            SleepCopy.factors(Narrative.sleepContributors(session, 6 * 3600L, 480, 20), 6 * 3600L, 480, 20).flatMap { listOf(it.name, it.detail, it.word) } +
            Narrative.Rating.entries.map(SleepCopy::factorWord) +
            nightVitals(s).flatMap { listOfNotNull(it.label, it.value, it.status) } +
            listOf(InfoCopy.breathingLine(Recovery.BreathingDisturbances(2.4f, null, 3), true), InfoCopy.breathingLine(Recovery.BreathingDisturbances(2.4f, 2.1f, 9), false))
        lines.forEach { l -> listOf("·", ";", "—").forEach { assertFalse("$l has $it", l.contains(it)) } }
    }

    @Test fun `the times sheet and the edited note say the times with to, never a dash`() {
        assertEquals("Sleep times", SleepCopy.timesTitle(false))
        assertEquals("Nap times", SleepCopy.timesTitle(true))
        assertEquals("The strap saw 23:20 to 07:21.", SleepCopy.strapSaw("23:20", "07:21"))
        assertEquals("Your times. The strap saw 23:20 to 07:21.", SleepCopy.yourTimes("23:20", "07:21"))
        assertEquals("Asleep for 7 h 26.", SleepCopy.timesResult(false, 7 * 3600L + 26 * 60))
        assertEquals("A nap of 42 min.", SleepCopy.timesResult(true, 42 * 60L))
        listOf(SleepCopy.yourTimes("23:20", "07:21"), SleepCopy.span("14:05", "14:47"), SleepCopy.TIMES_HINT).forEach { l ->
            listOf("·", ";", "—", "–").forEach { assertFalse("$l has $it", l.contains(it)) }
        }
    }
}
