package org.openstrap.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.testing.LocaleRule
import org.openstrap.app.testing.assertCleanCopy
import org.openstrap.app.ui.workout.Glance
import org.openstrap.app.ui.workout.WorkoutCopy
import java.util.Locale

/** The workout's shared words, and the notification and live lines that read them, on a German phone. */
class WorkoutWordsTest {
    @get:Rule val localeRule = LocaleRule(Locale.GERMANY)

    @Test
    fun theStrapGoneReadsOneWay() {
        assertEquals("Waiting for the strap", WorkoutWords.noHeartRate(paused = false, connected = false))
        assertEquals(WorkoutWords.STRAP_GONE, WorkoutCopy.noHeartRate(paused = false, connected = false))
        assertEquals("Paused", WorkoutWords.noHeartRate(paused = true, connected = false))
        assertEquals(WorkoutWords.STRAP_GONE_NOTE, WorkoutCopy.STRAP_GONE_NOTE)
        assertCleanCopy(listOf(WorkoutWords.STRAP_GONE, WorkoutWords.STRAP_GONE_NOTE))
    }

    @Test
    fun aKilometreIsNamedOneWay() {
        assertEquals("Km 3 in 5:42", WorkoutWords.split(2, 342))
        assertEquals("Km 12 in 1:02:03", WorkoutWords.split(11, 3723))
    }

    @Test
    fun zoneWords() {
        assertEquals("Zone 3, tempo", WorkoutWords.zoneLine(2))
        assertEquals("Zone 3," to "tempo", WorkoutCopy.zoneTitle(2))
        assertEquals("Under a minute in this zone", WorkoutWords.zoneTime(59))
        assertEquals("4 min in this zone", WorkoutWords.zoneTime(250))
        assertEquals("312 kcal", WorkoutWords.kcalText(312.7f))
    }

    @Test
    fun theNotificationWritesDistanceInTheLanguage() {
        val card = NotificationCopy.card(
            "Outdoor run", 1421, paused = false, connected = true, bpm = 152, maxHr = 190, gps = true, distanceMeters = 4210f,
            distanceFromSteps = false, target = null as WorkoutTarget?, zoneSeconds = listOf(0, 0, 0, 60, 0), confirming = false,
        )
        assertEquals("4,21 km", card.detail)
        assertCleanCopy(card.text)
    }

    @Test
    fun theLiveLinesWriteDistanceInTheLanguageAndTheDotFigureWithADot() {
        assertEquals("4,32 km", Glance.kmWithUnit(4320f))
        assertEquals("0 m", Glance.kmWithUnit(0f))
        assertEquals("4.32", Glance.kmFigure(4320f))
        assertEquals("5:42", Glance.paceClock(342.0))
    }
}
