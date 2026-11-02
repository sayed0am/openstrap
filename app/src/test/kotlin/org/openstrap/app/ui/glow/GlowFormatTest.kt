package org.openstrap.app.ui.glow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.openstrap.app.data.Healthspan
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.presentation.ClockSetting
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.TEST_LOCALES
import org.openstrap.app.presentation.Units
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.assertCleanCopy
import org.openstrap.app.testing.workout
import org.openstrap.app.ui.components.ChartPoint
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.glow.draw.DotFont
import org.openstrap.app.ui.glow.live.speedText
import org.openstrap.app.ui.workout.PhoneSummary
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** The glow screens write numbers, times and days through presentation/, and the dot font only gets what it can draw. */
class GlowFormatTest {
    @get:Rule val zone = ZoneRule(ZoneOffset.UTC)

    /** Runs [block] with the phone in [locale], then puts the default back. */
    private fun <T> inLocale(locale: Locale, block: () -> T): T {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        return try { block() } finally { Locale.setDefault(saved) }
    }

    private fun assertDrawable(text: String) = assertTrue("'$text' has a character the dot font can't draw", text.all(DotFont::has))

    private val start = 1_792_000_000L

    private fun run(meters: Float, seconds: Long = 1800): PhoneSummary {
        val w = AppWorkout(start, start + seconds, 1, distanceMeters = meters)
        return PhoneSummary(WorkoutRecorder.analyse(w, emptyList(), 190), emptyList(), start, start + seconds, null, distanceMeters = meters)
    }

    @Test fun `numbers drawn in dots keep ASCII digits and a dot in every language`() {
        for (locale in TEST_LOCALES) inLocale(locale) {
            listOf(
                dotClock(3723), dotClock(754), clockDuration(27_600), Metric.SkinTemperature.format(36.55f, Units.DOT_LOCALE), Metric.Steps.format(13_860f, Units.DOT_LOCALE),
                speedText(300.0, Units.DOT_LOCALE)!!, summaryHero(Workouts.Family.Distance, run(5210f), null).value,
            ).forEach(::assertDrawable)
            assertEquals("1:02:03", dotClock(3723))
            assertEquals("7:40", clockDuration(27_600))
            assertEquals("13,860", Metric.Steps.format(13_860f, Units.DOT_LOCALE))
            assertEquals("36.5", Metric.SkinTemperature.format(36.54f, Units.DOT_LOCALE))
            // Read in a row's text, the same figures take the phone's own marks.
            assertEquals(String.format(locale, "%.1f", 36.54f), Metric.SkinTemperature.format(36.54f, locale))
            assertEquals(Units.count(13_860, locale), Metric.Steps.format(13_860f, locale))
            assertEquals("5.21", summaryHero(Workouts.Family.Distance, run(5210f), null).value)
        }
    }

    @Test fun `text figures follow the phone's language`() {
        inLocale(Locale.GERMANY) {
            assertEquals("12,0", speedText(300.0))
            assertEquals("12:34", workoutClock(754))
        }
        inLocale(Locale.US) { assertEquals("12.0", speedText(300.0)) }
    }

    @Test fun `a run under a kilometre leads with its metres`() {
        val h = summaryHero(Workouts.Family.Distance, run(820f), null)
        assertEquals("820", h.value)
        assertEquals("m", h.unit)
    }

    @Test fun `the summary and the share card write kcal and minutes the same way`() {
        inLocale(Locale.GERMANY) {
            val a = run(5210f).analysis.copy(activeKcal = 1312.4f)
            assertEquals("1.312", kcalFigure(a))
        }
        assertEquals("42" to "min", minutesFigure(42 * 60L + 10))
        assertEquals("45" to "s", minutesFigure(45))
    }

    @Test fun `a time of day follows the phone's 12 or 24 hour setting`() {
        val t = LocalDate.of(2026, 10, 21).atTime(22, 30).toEpochSecond(ZoneOffset.UTC)
        val saved = ClockSetting.use24Hour
        try {
            inLocale(Locale.US) {
                ClockSetting.use24Hour = true
                assertEquals("22:30", clock(t))
                ClockSetting.use24Hour = false
                assertEquals("10:30 PM", clock(t))
            }
        } finally {
            ClockSetting.use24Hour = saved
        }
    }

    @Test fun `a heading names the day as DayWords does`() {
        inLocale(Locale.UK) {
            val today = LocalDate.now()
            assertEquals("Today", dayName(today))
            assertEquals("Yesterday", dayName(today.minusDays(1)))
            assertEquals(today.minusDays(3).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.UK), dayName(today.minusDays(3)))
        }
    }

    @Test fun `chart labels are one weekday, day and month form`() {
        inLocale(Locale.UK) {
            val d = LocalDate.of(2026, 10, 21)
            assertEquals("Wed", DayWords.weekdayShort(d))
            assertEquals("36,5", Metric.SkinTemperature.format(36.54f, Locale.GERMANY))
            assertEquals("13.860", Metric.Steps.format(13_860f, Locale.GERMANY))
            assertEquals("21 Oct", DayWords.dayMonth(d))
            assertEquals("Oct", DayWords.monthShort(d))
            assertEquals("21 Oct 2025", DayWords.dayMonthYear(d.minusYears(1)))
            assertEquals("Oct 21", DayWords.monthDay(d))
        }
    }

    @Test fun `Habit Age's sleep line writes a duration as everywhere else`() {
        inLocale(Locale.US) {
            val line = termDetail(Healthspan.Term(Healthspan.Habit.TimeAsleep, 1f, 340f, null), 40)
            assertEquals("5 h 40, counts only below 6 h 30", line)
            assertCleanCopy(line)
            val steps = termDetail(Healthspan.Term(Healthspan.Habit.Steps, 0f, 6543f, 9000f), 40)
            assertTrue(steps, steps.startsWith("6,543 a day, typical: 9,000"))
        }
    }

    private fun rated(end: Long, minutes: Int?): WorkoutAnalysis =
        WorkoutAnalysis(workout(start = end - 1800, end = end), end, emptyList(), null, null, null, null, List(5) { 0 }, 190, recoveryMinutes = minutes)

    @Test fun `time to recover counts from the latest workout that has an estimate`() {
        val older = rated(1000, 600)
        val newer = rated(5000, 120)
        val unrated = rated(9000, null)
        assertEquals(newer, latestRecoveryEstimate(listOf(older, unrated, newer)))
        assertNull(latestRecoveryEstimate(listOf(unrated)))
    }

    @Test fun `heart rate and Charge share one bucketing`() {
        val range = 0L..1199L
        assertEquals(listOf(60f, 80f), bucket(listOf(ChartPoint(0, 50f), ChartPoint(60, 70f), ChartPoint(700, 80f)), range, 600))
        val charge = listOf(Recovery.ChargePoint(0, 50f, Recovery.State.Rest), Recovery.ChargePoint(700, 80f, Recovery.State.Rest))
        assertEquals(listOf(50f, 80f), bucket(charge, range, 600))
    }

    @Test fun `a year's chart groups weeks from the locale's first day`() {
        val end = LocalDate.of(2026, 10, 21)
        val start = end.minusDays(364)
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
            .map { org.openstrap.app.data.DayStat(it, 50f, 50f, 50f, 1) }.toList()
        for ((locale, first) in listOf(Locale.US to java.time.DayOfWeek.SUNDAY, Locale.GERMANY to java.time.DayOfWeek.MONDAY)) inLocale(locale) {
            val (values, _, dates) = series(org.openstrap.app.ui.dashboard.Range.Year, days, start, end)
            assertTrue(dates.all { it.dayOfWeek == first })
            // Every week finds its average: the chart and History start weeks on the same day.
            assertTrue(values.all { it == 50f })
        }
    }

    @Test fun `the shared words are clean copy`() {
        assertCleanCopy(listOf(NO_VALUE, NO_NIGHT_VALUE, NO_READING, AFTER_NEXT_SYNC, ADD_PROFILE))
    }
}
