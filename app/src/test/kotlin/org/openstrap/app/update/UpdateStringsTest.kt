package org.openstrap.app.update

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.i18n.ResourceStrings
import org.openstrap.app.testing.FileStrings
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale

/** The update words come from strings_update.xml in the app's language, the pilot every later wave copies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateStringsTest {
    private val apk = ReleaseAsset("OpenStrap-v2.2.0.apk", "https://example.com/a.apk", 20_264_590, "abc")
    private val r220 = Release("v2.2.0", listOf(2, 2, 0), "", Instant.parse("2026-10-25T13:38:41Z"), "https://example.com", apk, null)

    @Test fun `a size follows the language's decimal mark, no longer always a point`() {
        assertEquals("20.3 MB", UpdateCopy.megabytes(20_264_590, FileStrings(Locale.US)))
        assertEquals("20,3 MB", UpdateCopy.megabytes(20_264_590, FileStrings(Locale.GERMANY)))
        assertEquals("Download and install, 20,3 MB", UpdateCopy.install(r220, Install.Idle, recording = false, FileStrings(Locale.GERMANY)))
    }

    @Test @Config(qualifiers = "de-rDE")
    fun `the app's own strings format in the phone's language too`() {
        val strings = ResourceStrings(ApplicationProvider.getApplicationContext())
        assertEquals("Downloading, 1,5 MB", UpdateCopy.install(r220, Install.Downloading("v2.2.0", 1_500_000, 0), recording = false, strings))
    }

    @Test fun `the notification and its channel read their words from the resources`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UpdateNotifier(context).available(r220)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals("App updates", manager.getNotificationChannel("app_updates").name)
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("OpenStrap 2.2.0 is out", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("See what's new and install it from the Updates page.", posted.extras.getString(Notification.EXTRA_TEXT))
    }
}
