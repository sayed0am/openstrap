package org.openstrap.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.openstrap.app.presentation.Units.Precision
import org.openstrap.app.testing.LocaleRule
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.assertCleanCopy
import org.openstrap.app.ui.glow.draw.DotFont
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Every unit in each test language, the phone's default set to it, in a zone that isn't the one the times use. */
@RunWith(Parameterized::class)
class UnitsTest(private val locale: Locale) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun locales() = localeParameters()
    }

    @get:Rule val localeRule = LocaleRule(locale)
    @get:Rule val zoneRule = ZoneRule(ZoneId.of("Asia/Kathmandu"))

    private fun n(ascii: String) = localNumber(ascii, locale)
    private fun assertDots(text: String) = assertTrue("not all in the dot font: $text", text.all(DotFont::has))
    private val produced = mutableListOf<String>()
    private fun <T : String?> T.kept(): T = also { it?.let(produced::add) }

    @org.junit.After
    fun cleanCopy() = assertCleanCopy(produced)

    @Test
    fun workoutDistanceHasTwoDecimalsAndDayDistanceOne() {
        assertEquals(n("5.21") + " km", Units.distance(5210f, Precision.Workout).kept())
        assertEquals(n("5.2") + " km", Units.distance(5210f, Precision.Day).kept())
        assertEquals(n("1.00") + " km", Units.distance(1000f, Precision.Workout).kept())
        assertEquals(n("12.0") + " km", Units.distance(11_960f, Precision.Day).kept())
    }

    @Test
    fun decimalMarkFollowsTheLanguage() {
        when (locale.language) {
            "de" -> assertEquals("5,2 km", Units.distance(5210f, Precision.Day))
            "en" -> assertEquals("5.2 km", Units.distance(5210f, Precision.Day))
        }
        assertEquals(Units.distance(5210f, Precision.Day, locale = locale), Units.distance(5210f, Precision.Day))
    }

    @Test
    fun underAKilometreIsMetresAndUnderTenIsNothing() {
        assertEquals(n("820") + " m", Units.distance(820f, Precision.Workout).kept())
        assertEquals(n("999") + " m", Units.distance(999.6f, Precision.Day).kept())
        assertEquals(n("10") + " m", Units.distance(10f, Precision.Day).kept())
        assertNull(Units.distance(9.9f, Precision.Day))
        assertNull(Units.distance(0f, Precision.Workout, fromSteps = true))
    }

    @Test
    fun distanceFromStepsAlwaysHasTheMark() {
        assertEquals("≈ " + n("5.2") + " km", Units.distance(5210f, Precision.Day, fromSteps = true).kept())
        assertEquals("≈ " + n("5.21") + " km", Units.distance(5210f, Precision.Workout, fromSteps = true).kept())
        assertEquals("≈ " + n("850") + " m", Units.distance(850f, Precision.Day, fromSteps = true).kept())
    }

    @Test
    fun kmFigureAlone() {
        assertEquals(n("5.21"), Units.km(5210f, Precision.Workout))
        assertEquals(n("0.8"), Units.km(820f, Precision.Day))
    }

    @Test
    fun dotFontKeepsTheDotAndAsciiDigits() {
        val dotted = listOf(
            Units.km(5210f, Precision.Workout, Units.DOT_LOCALE),
            Units.count(23_410, Units.DOT_LOCALE),
            Units.workoutClock(3723, Units.DOT_LOCALE),
            Units.clockDuration(27_600, Units.DOT_LOCALE),
            Pace.clock(324.0, Units.DOT_LOCALE)!!,
            dotFormat("%.1f", 14.25),
            dotFormat("%02d", 7),
            dotFormat("%,d", 1_234_567),
        )
        assertEquals(listOf("5.21", "23,410", "1:02:03", "7:40", "5:24", "14.3", "07", "1,234,567"), dotted)
        dotted.forEach(::assertDots)
    }

    @Test
    fun countGroupsByTheLanguage() {
        assertEquals(n("23,410"), Units.count(23_410).kept())
        assertEquals(n("950"), Units.count(950))
        if (locale.language == "de") assertEquals("23.410", Units.count(23_410))
    }

    @Test
    fun durationIsHoursAndMinutes() {
        assertEquals(n("40") + " min", Units.duration(40 * 60L).kept())
        assertEquals(n("7") + " h", Units.duration(7 * 3600L).kept())
        assertEquals(n("7") + " h " + n("40"), Units.duration(7 * 3600L + 40 * 60 + 59).kept())
        assertEquals(n("7") + " h " + n("05"), Units.duration(7 * 3600L + 5 * 60).kept())
        assertEquals(n("0") + " min", Units.duration(59))
        assertEquals(n("0") + " min", Units.duration(-300))
        assertEquals(n("25") + " h " + n("01"), Units.duration(25 * 3600L + 60))
    }

    @Test
    fun clockDurationLooksLikeAClock() {
        assertEquals(n("7:40"), Units.clockDuration(7 * 3600L + 40 * 60).kept())
        assertEquals(n("0:05"), Units.clockDuration(5 * 60L))
        assertEquals(n("12:00"), Units.clockDuration(12 * 3600L))
    }

    @Test
    fun workoutTimerAsItWas() {
        assertEquals(n("0:00"), Units.workoutClock(0).kept())
        assertEquals(n("0:45"), Units.workoutClock(45))
        assertEquals(n("12:34"), Units.workoutClock(754))
        assertEquals(n("59:59"), Units.workoutClock(3599))
        assertEquals(n("1:00:00"), Units.workoutClock(3600))
        assertEquals(n("1:02:03"), Units.workoutClock(3723).kept())
        assertEquals(n("10:00:00"), Units.workoutClock(36_000))
    }

    @Test
    fun aFinishedWorkoutUnderAMinuteIsSeconds() {
        assertEquals(n("45") + " s", Units.secondsOrClock(45).kept())
        assertEquals(n("0") + " s", Units.secondsOrClock(0))
        assertEquals(n("59") + " s", Units.secondsOrClock(59))
        assertEquals(n("1:00"), Units.secondsOrClock(60))
        assertEquals(n("1:02:03"), Units.secondsOrClock(3723))
    }

    @Test
    fun paceShowsFromOneHundredMetres() {
        assertNull(Pace.of(null, 600))
        assertNull(Pace.of(99.9f, 600))
        assertEquals(300.0, Pace.of(100f, 30)!!, 1e-9)
        assertEquals(324.0, Pace.of(5000f, 1620)!!, 1e-9)
    }

    @Test
    fun paceReadsPerKm() {
        assertEquals(n("5:24") + " /km", Pace.text(324.0).kept())
        assertEquals(n("5:24") + " /km", Pace.text(324.4))
        assertEquals(n("5:25"), Pace.clock(324.5))
        assertEquals(n("60:00") + " /km", Pace.text(3600.0))
    }

    @Test
    fun noPaceSlowerThanAnHourAKmOrWithNone() {
        assertNull(Pace.text(null))
        assertNull(Pace.text(0.0))
        assertNull(Pace.text(-5.0))
        assertNull(Pace.text(3600.5))
        assertNull(Pace.clock(7200.0))
    }

    private fun epoch(utc: String) = Instant.parse(utc).epochSecond
    private fun amPm(time: LocalTime) = DateTimeFormatter.ofPattern("a", locale).format(time)

    @Test
    fun timeOfDayFollowsThe24HourSetting() {
        val t = epoch("2026-06-10T19:40:00Z") // 21:40 in Berlin
        assertEquals("21:40", TimeOfDay.format(t, BERLIN, use24Hour = true).kept())
        assertEquals("9:40 " + amPm(LocalTime.of(21, 40)), TimeOfDay.format(t, BERLIN, use24Hour = false).kept())
        assertEquals("08:05", TimeOfDay.format(LocalTime.of(8, 5), use24Hour = true))
        assertEquals("8:05 " + amPm(LocalTime.of(8, 5)), TimeOfDay.format(LocalTime.of(8, 5), use24Hour = false))
        if (locale.language == "en") assertEquals("9:40 PM", TimeOfDay.format(t, BERLIN, use24Hour = false))
    }

    @Test
    fun timeOfDayDefaultsToTheClockSetting() {
        val t = epoch("2026-06-10T19:40:00Z")
        val saved = ClockSetting.use24Hour
        try {
            ClockSetting.use24Hour = false
            assertEquals(TimeOfDay.format(t, BERLIN, use24Hour = false), TimeOfDay.format(t, BERLIN))
            ClockSetting.use24Hour = true
            assertEquals("21:40", TimeOfDay.format(t, BERLIN))
        } finally {
            ClockSetting.use24Hour = saved
        }
    }

    @Test
    fun timeOfDayOnTheDayTheClocksGoForward() {
        // 2026-03-29: 02:00 CET becomes 03:00 CEST.
        assertEquals("01:30", TimeOfDay.format(epoch("2026-03-29T00:30:00Z"), BERLIN, use24Hour = true))
        assertEquals("03:30", TimeOfDay.format(epoch("2026-03-29T01:30:00Z"), BERLIN, use24Hour = true))
        assertEquals("3:30 " + amPm(LocalTime.of(3, 30)), TimeOfDay.format(epoch("2026-03-29T01:30:00Z"), BERLIN, use24Hour = false).kept())
    }

    @Test
    fun timeOfDayOnTheDayTheClocksGoBack() {
        // 2026-10-25: 03:00 CEST becomes 02:00 CET, so 02:30 happens twice.
        assertEquals("02:30", TimeOfDay.format(epoch("2026-10-25T00:30:00Z"), BERLIN, use24Hour = true))
        assertEquals("02:30", TimeOfDay.format(epoch("2026-10-25T01:30:00Z"), BERLIN, use24Hour = true))
        assertEquals("03:30", TimeOfDay.format(epoch("2026-10-25T02:30:00Z"), BERLIN, use24Hour = true))
        assertEquals("2:30 " + amPm(LocalTime.of(2, 30)), TimeOfDay.format(epoch("2026-10-25T01:30:00Z"), BERLIN, use24Hour = false).kept())
    }

    @Test
    fun minutesOfDayWrap() {
        assertEquals("22:30", TimeOfDay.minutes(22 * 60 + 30, use24Hour = true).kept())
        assertEquals("00:30", TimeOfDay.minutes(1470, use24Hour = true))
        assertEquals("23:30", TimeOfDay.minutes(-30, use24Hour = true))
        assertEquals("00:00", TimeOfDay.minutes(0, use24Hour = true))
        assertEquals("10:30 " + amPm(LocalTime.of(22, 30)), TimeOfDay.minutes(22 * 60 + 30, use24Hour = false).kept())
        assertFalse(TimeOfDay.minutes(22 * 60 + 30, use24Hour = false).startsWith("22"))
    }
}
