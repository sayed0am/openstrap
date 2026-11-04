package org.openstrap.app.device

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Keep recording notice pops up (heads-up) on a channel of its own, silent and without vibration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeepRecordingNoticeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun assertHeadsUpAndSilent() {
        val channel = manager.getNotificationChannel(KeepRecordingNotice.CHANNEL)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals("Keep recording", channel.name)
        assertEquals("Asks you to open OpenStrap when Android may stop a workout.", channel.description)
    }

    @Test
    fun theNoticeShowsOnItsOwnHeadsUpChannelWithNoSoundOrVibration() {
        KeepRecordingNotice.show(context, sportType = 1)
        val n = shadowOf(manager).getNotification(KeepRecordingNotice.NOTIFICATION_ID)
        assertEquals(KeepRecordingNotice.CHANNEL, n.channelId)
        assertHeadsUpAndSilent()
    }

    @Test
    fun theServiceCreatesTheChannelWithTheOthers() {
        val service = Robolectric.buildService(StrapService::class.java).create()
        try {
            assertHeadsUpAndSilent()
        } finally {
            // Clears StrapService.running, which would otherwise outlive the test and stop later ones starting it.
            service.destroy()
        }
    }
}
