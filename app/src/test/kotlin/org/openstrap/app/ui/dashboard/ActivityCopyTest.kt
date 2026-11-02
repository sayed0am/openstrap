package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Activity
import org.openstrap.app.data.UsualSteps
import java.time.LocalDate
import java.util.Locale
import org.openstrap.app.presentation.ActivityCopy
import org.openstrap.app.presentation.DetailCopy
import org.openstrap.app.presentation.Range

/** The Activity details' words (spec 5.3, 6.1 and 7). */
class ActivityCopyTest {
    private val usual = List(24) { h -> (h + 1) * 400f }

    private val every: List<String> =
        UsualSteps.Pace.entries.flatMap { listOf(ActivityCopy.pace(it, true), ActivityCopy.pace(it, false)) } +
            listOf(
                ActivityCopy.goalShare(5000, 8000), ActivityCopy.goalShare(9000, 8000),
                ActivityCopy.activityLine(5000, 8000, UsualSteps.Pace.About, true), ActivityCopy.hourLabel(23),
                ActivityCopy.heartPointsWeek(64), ActivityCopy.heartPointsWeek(120), ActivityCopy.earnedLabel("Tue 20 Oct"),
                ActivityCopy.activeWeek(96), ActivityCopy.activeWeek(160),
                ActivityCopy.activeDay(Activity.Mvpa(20, 4, 0), true), ActivityCopy.activeDay(Activity.Mvpa(0, 0, 0), false),
                ActivityCopy.activeDay(Activity.Mvpa(1, 0, 0), true), ActivityCopy.activeDay(Activity.Mvpa(0, 9, 0), false),
                ActivityCopy.loadDay(null, true), ActivityCopy.loadDay(72, true), ActivityCopy.loadDay(20, false),
                ActivityCopy.usualBandLabel(30f..50f), ActivityCopy.strapRange(120, 300),
                ActivityCopy.vo2(true), ActivityCopy.vo2(false),
                ActivityCopy.recover("Run", ActivityCopy.ended(LocalDate.of(2026, 10, 20), "18:30", LocalDate.of(2026, 10, 24), Locale.UK)),
                ActivityCopy.energy(1650), ActivityCopy.distanceSource(true), ActivityCopy.distanceSource(false),
                ActivityCopy.distance(6420, false), ActivityCopy.activeWeek(150),
            ) + listOfNotNull(
                ActivityCopy.dropsOff(LocalDate.of(2026, 10, 15), 12, Locale.UK), ActivityCopy.heartPointsWord(100),
                ActivityCopy.heartPointsMonth(listOf(40, null, 110)), ActivityCopy.heartPointsMonth(listOf(40, 60)),
                ActivityCopy.heartPointsMonth(listOf(100, 120)), ActivityCopy.activeWord(150),
                ActivityCopy.loadSpan(listOf(DetailCopy.Level.Above, DetailCopy.Level.Inside, null), Range.Week),
                ActivityCopy.balance("Building"), ActivityCopy.balance("Steady"), ActivityCopy.balance("Easing off"),
            ) + ActivityCopy.LOAD_SCALE

    @Test fun `no Activity detail line uses an interpunct, semicolon, em dash or exclamation mark`() {
        every.forEach { s -> assertFalse("\"$s\"", s.any { it == '·' || it == ';' || it == '—' || it == '!' }) }
    }

    @Test fun `steps are read against your usual by now, a past day against your usual day`() {
        // By 10:30 the usual is 4,000 and half of the next 400.
        assertEquals(UsualSteps.Pace.About, ActivityCopy.paceOf(4600, usual, today = true, hour = 10, minute = 30))
        assertEquals(UsualSteps.Pace.Fewer, ActivityCopy.paceOf(2000, usual, today = true, hour = 10, minute = 30))
        // A past day is read whole: 9,600 by the end of the day.
        assertEquals(UsualSteps.Pace.More, ActivityCopy.paceOf(14000, usual, today = false, hour = 10, minute = 30))
        assertNull(ActivityCopy.paceOf(4600, null, true, 10, 30))
        assertEquals("About usual by now", ActivityCopy.pace(UsualSteps.Pace.About, true))
        assertEquals("Fewer than your usual day", ActivityCopy.pace(UsualSteps.Pace.Fewer, false))
        assertEquals("62% of your goal, about usual by now", ActivityCopy.activityLine(5000, 8000, UsualSteps.Pace.About, true))
        assertEquals("62% of your goal", ActivityCopy.activityLine(5000, 8000, null, true))
        assertEquals("steps, 23:00 to 00:00", ActivityCopy.hourLabel(23))
    }

    @Test fun `heart points say how far to the aim and what drops off`() {
        assertEquals("36 to go for 100.", ActivityCopy.heartPointsWeek(64))
        assertNull(ActivityCopy.heartPointsWord(99))
        assertEquals("Tomorrow Thursday's 12 points drop off.", ActivityCopy.dropsOff(LocalDate.of(2026, 10, 15), 12, Locale.UK))
        assertNull(ActivityCopy.dropsOff(LocalDate.of(2026, 10, 15), 0, Locale.UK))
        assertEquals("At 100 or more on 1 of 2 days in the last 30.", ActivityCopy.heartPointsMonth(listOf(40, null, 110)))
    }

    @Test fun `active minutes count toward 150 a week`() {
        assertEquals("54 to go for 150.", ActivityCopy.activeWeek(96))
        assertEquals("10 past the 150 to aim for.", ActivityCopy.activeWeek(160))
        assertEquals("Goal met", ActivityCopy.activeWord(Activity.MVPA_WEEKLY_TARGET))
        assertEquals("So far today, 20 minutes brisk and 4 minutes hard.", ActivityCopy.activeDay(Activity.Mvpa(20, 4, 0), true))
    }

    @Test fun `Load's usual band is Today's quarter either side`() {
        val band = ActivityCopy.usualBand(40f)!!
        assertEquals(30f, band.start, 0.001f)
        assertEquals(50f, band.endInclusive, 0.001f)
        assertNull(ActivityCopy.usualBand(null))
        assertEquals(listOf(0, 1, 2), listOf(20f, 40f, 60f).map { ActivityCopy.loadBar(it, 40f) })
        assertNull(ActivityCopy.loadBar(20f, null))
        assertTrue(ActivityCopy.loadDay(72, true).startsWith("So far today"))
        assertEquals("Your usual, 30 to 50", ActivityCopy.usualBandLabel(band))
    }

    @Test fun `time to recover counts from a workout said the way a person would`() {
        val today = LocalDate.of(2026, 10, 24)
        assertEquals("today at 18:30", ActivityCopy.ended(today, "18:30", today))
        assertEquals("yesterday at 07:10", ActivityCopy.ended(today.minusDays(1), "07:10", today))
        assertEquals("Counted from your run, which ended Tuesday at 18:30.", ActivityCopy.recover("Run", ActivityCopy.ended(LocalDate.of(2026, 10, 20), "18:30", today, Locale.UK)))
    }

    @Test fun `time to recover names the day as every other line does`() {
        val today = LocalDate.of(2026, 10, 24)
        assertEquals("today at 18:30", ActivityCopy.ended(today, "18:30", today, Locale.UK))
        assertEquals("yesterday at 07:10", ActivityCopy.ended(today.minusDays(1), "07:10", today, Locale.UK))
        assertEquals("Tuesday at 18:30", ActivityCopy.ended(LocalDate.of(2026, 10, 20), "18:30", today, Locale.UK))
        assertEquals("Tue 13 Oct at 18:30", ActivityCopy.ended(LocalDate.of(2026, 10, 13), "18:30", today, Locale.UK))
    }

    @Test fun `Load's bar and band are Today's rule`() {
        assertEquals(null, ActivityCopy.loadBar(40f, null))
        assertEquals(listOf(0, 1, 1, 2), listOf(29f, 30f, 50f, 51f).map { ActivityCopy.loadBar(it, 40f) })
        assertEquals(30f..50f, ActivityCopy.usualBand(40f))
        assertEquals(listOf("Below your usual", "Usual", "Above your usual"), ActivityCopy.LOAD_SCALE)
    }
}
