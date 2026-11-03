package org.openstrap.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.testing.assertCleanCopy
import org.openstrap.app.ui.dashboard.InfoCopy
import org.openstrap.app.ui.dashboard.STALE_AFTER_S
import org.openstrap.app.widget.syncStale

/** The bands are the ones the screens wrote by hand, at every score. */
class BandsTest {
    @Test
    fun recoveryBoundaries() {
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3), listOf(54, 55, 69, 70, 84, 85, 100).map(RecoveryBands::bar))
        assertEquals("Take it easy", RecoveryBands.word(54))
        assertEquals("Moderate", RecoveryBands.word(55))
        assertEquals("Ready", RecoveryBands.word(70))
        assertEquals("Ready for more", RecoveryBands.word(85))
        assertEquals("Take it easy", RecoveryBands.word(95, alert = true))
        assertCleanCopy(RecoveryBands.WORDS)
    }

    @Test
    fun recoveryMatchesTheScreens() {
        assertEquals(InfoCopy.RECOVERY_SCALE, RecoveryBands.WORDS)
        for (score in 0..100) {
            assertEquals(InfoCopy.recoveryBar(score), RecoveryBands.bar(score))
            for (alert in listOf(false, true)) assertEquals(TodayCopy.recoveryWord(score, alert), RecoveryBands.word(score, alert))
        }
    }

    @Test
    fun sleepBoundaries() {
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3), listOf(59, 60, 79, 80, 89, 90, 100).map(SleepBands::bar))
        assertEquals(listOf("Poor", "Fair", "Good", "Great"), listOf(0, 60, 80, 90).map(SleepBands::word))
        assertCleanCopy(SleepBands.WORDS)
    }

    @Test
    fun sleepMatchesTheScreens() {
        assertEquals(InfoCopy.SLEEP_SCALE, SleepBands.WORDS)
        for (score in 0..100) {
            assertEquals(InfoCopy.sleepBar(score), SleepBands.bar(score))
            assertEquals(TabCopy.sleepScoreWord(score), SleepBands.word(score))
        }
    }

    @Test
    fun loadIsTodaysRule() {
        assertNull(LoadBands.of(50f, null))
        assertNull(LoadBands.of(50f, 0f))
        assertEquals(LoadBand.Usual, LoadBands.of(125f, 100f))
        assertEquals(LoadBand.Above, LoadBands.of(125.1f, 100f))
        assertEquals(LoadBand.Usual, LoadBands.of(75f, 100f))
        assertEquals(LoadBand.Below, LoadBands.of(74.9f, 100f))
        assertEquals(LoadBand.SoFar, LoadBands.of(10f, 100f, endOfDay = false))
        assertEquals(LoadBand.Above, LoadBands.of(130f, 100f, endOfDay = false))
        assertEquals(75f..125f, LoadBands.usualRange(100f))
        assertNull(LoadBands.usualRange(0f))
        assertCleanCopy(LoadBand.entries.map { it.text })
    }

    @Test
    fun loadMatchesTodayAndActivity() {
        for (usual in listOf(null, 0f, 40f, 100f, 333f)) {
            for (load in (0..500 step 5).map { it.toFloat() }) {
                for (endOfDay in listOf(true, false)) {
                    assertEquals(TodayCopy.loadStatus(load, usual, endOfDay)?.name, LoadBands.of(load, usual, endOfDay)?.name)
                }
                // Activity's bar is Today's rule for a whole day.
                assertEquals(ActivityCopy.loadBar(load, usual), LoadBands.of(load, usual)?.let { ActivityCopy.LOAD_SCALE.indexOf(it.text) })
            }
            assertEquals(ActivityCopy.usualBand(usual), LoadBands.usualRange(usual))
        }
    }

    @Test
    fun strapStaleAfterThreeHours() {
        assertEquals(3 * 3600L, StrapBands.STALE_AFTER_S)
        val now = 1_800_000_000L
        assertFalse(StrapBands.stale(null, now))
        assertFalse(StrapBands.stale(now - 3 * 3600, now))
        assertTrue(StrapBands.stale(now - 3 * 3600 - 1, now))
        // The widgets' rule, which the app's 90 minutes gave way to (wave 3C): one threshold now.
        for (age in listOf(0L, 5400L, 10_800L, 10_801L, 86_400L)) assertEquals(syncStale(now - age, now), StrapBands.stale(now - age, now))
        assertEquals(StrapBands.STALE_AFTER_S, STALE_AFTER_S)
    }

    @Test
    fun lowBatteryAtFifteen() {
        assertTrue(StrapBands.lowBattery(15, charging = false))
        assertTrue(StrapBands.lowBattery(0, charging = false))
        assertFalse(StrapBands.lowBattery(16, charging = false))
        assertFalse(StrapBands.lowBattery(5, charging = true))
        assertFalse(StrapBands.lowBattery(null, charging = false))
    }
}
