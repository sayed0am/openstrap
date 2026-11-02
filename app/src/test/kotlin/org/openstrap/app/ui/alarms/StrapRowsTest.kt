package org.openstrap.app.ui.alarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.AlarmCapabilities
import org.openstrap.protocol.services.SettingsCodec
import org.openstrap.protocol.services.SettingsGroup

/** What the Strap page's rows say about the alarms and alerts. */
class StrapRowsTest {
    private val caps = AlarmCapabilities(version = 1, maxCount = 10, titles = false, smartSupported = false)

    @Test fun `alarms wait for the strap, then name the first one that's on`() {
        assertNull(alarmsSummary(AlarmsUiState()))
        assertEquals("None", alarmsSummary(AlarmsUiState(caps = caps)))
        val alarms = listOf(Alarm(0, 6, 40, Alarm.WEEKDAYS, enabled = true), Alarm(1, 8, 0, Alarm.WEEKEND, enabled = true), Alarm(2, 9, 0, 0, enabled = false))
        assertEquals("06:40 weekdays and 1 more", alarmsSummary(AlarmsUiState(caps = caps, alarms = alarms)))
        assertEquals("06:40 weekdays", alarmsSummary(AlarmsUiState(caps = caps, alarms = alarms.take(1))))
        assertEquals("All off", alarmsSummary(AlarmsUiState(caps = caps, alarms = alarms.drop(2))))
    }

    private fun health(vararg values: Pair<Int, Int>) =
        SettingsGroup(0, 1, values.associate { (k, v) -> k to SettingsCodec.enumSetting(k, v) })

    @Test fun `health alerts in a few words, or Off`() {
        assertNull(healthAlertsSummary(AlarmsUiState()))
        assertEquals("Off", healthAlertsSummary(AlarmsUiState(health = health(SettingsCodec.KEY_HIGH_HR_ALERT to 0))))
        assertEquals("Over 140 bpm", healthAlertsSummary(AlarmsUiState(health = health(SettingsCodec.KEY_HIGH_HR_ALERT to 140))))
        assertEquals("Over 140, under 40", healthAlertsSummary(AlarmsUiState(health = health(SettingsCodec.KEY_HIGH_HR_ALERT to 140, SettingsCodec.KEY_LOW_HR_ALERT to 40))))
        assertEquals("Under 40 bpm", healthAlertsSummary(AlarmsUiState(health = health(SettingsCodec.KEY_LOW_HR_ALERT to 40))))
        assertEquals("Oxygen under 90%", healthAlertsSummary(AlarmsUiState(health = health(SettingsCodec.KEY_LOW_SPO2_ALERT to 90))))
        assertEquals("Heart rate and oxygen", healthAlertsSummary(AlarmsUiState(health = health(SettingsCodec.KEY_HIGH_HR_ALERT to 150, SettingsCodec.KEY_LOW_SPO2_ALERT to 90))))
        assertEquals(
            "Heart rate and oxygen",
            healthAlertsSummary(AlarmsUiState(health = health(SettingsCodec.KEY_HIGH_HR_ALERT to 150, SettingsCodec.KEY_LOW_HR_ALERT to 40, SettingsCodec.KEY_LOW_SPO2_ALERT to 90))),
        )
    }

    @Test fun `phone alerts in words`() {
        assertEquals("Calls and notifications", phoneAlertsSummary(calls = true, notifications = true))
        assertEquals("Calls", phoneAlertsSummary(calls = true, notifications = false))
        assertEquals("Notifications", phoneAlertsSummary(calls = false, notifications = true))
        assertEquals("Off", phoneAlertsSummary(calls = false, notifications = false))
    }

    @Test fun `alarm and quiet hour times follow the phone's clock, the dots without letters`() {
        val was = org.openstrap.app.presentation.ClockSetting.use24Hour
        val locale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("06:40" to null, dotClock(6, 40, use24Hour = true))
            assertEquals("6:40" to "AM", dotClock(6, 40, use24Hour = false))
            assertEquals("10:00" to "PM", dotClock(22, 0, use24Hour = false))
            org.openstrap.app.presentation.ClockSetting.use24Hour = false
            val alarms = listOf(Alarm(0, 6, 40, Alarm.WEEKDAYS, enabled = true))
            assertEquals("6:40 AM weekdays", alarmsSummary(AlarmsUiState(caps = caps, alarms = alarms)))
            assertEquals("10:00 PM", quietClock(22 * 60))
            org.openstrap.app.presentation.ClockSetting.use24Hour = true
            assertEquals("22:00", quietClock(22 * 60))
        } finally {
            org.openstrap.app.presentation.ClockSetting.use24Hour = was
            java.util.Locale.setDefault(locale)
        }
    }
}
