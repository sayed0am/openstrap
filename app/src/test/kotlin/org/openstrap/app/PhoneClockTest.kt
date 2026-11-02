package org.openstrap.app

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.presentation.ClockSetting
import org.openstrap.app.presentation.TimeOfDay
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.util.Locale

/**
 * The app takes the phone's 12 or 24 hour clock when it starts and follows it after. Robolectric starts [OpenStrapApp]
 * before each test, before a test can set the phone up, so the start is run again on a phone set to 12 hours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OpenStrapApp::class)
class PhoneClockTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @After fun reset() {
        ClockSetting.use24Hour = true
    }

    @Test fun `a 12 hour phone's clock is set when the app starts`() {
        Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "12")
        ClockSetting.use24Hour = true
        (app as OpenStrapApp).onCreate()
        assertFalse(ClockSetting.use24Hour)
        assertEquals("10:30 PM", TimeOfDay.format(LocalTime.of(22, 30), locale = Locale.US))
    }

    @Test fun `the clock follows the phone's setting when it changes`() {
        ClockSetting.use24Hour = false
        set("24")
        assertTrue(ClockSetting.use24Hour)
        assertEquals("22:30", TimeOfDay.format(LocalTime.of(22, 30), locale = Locale.US))
        set("12")
        assertFalse(ClockSetting.use24Hour)
    }

    /** Android's Settings writes the setting, then tells running apps with a time changed broadcast. */
    private fun set(hours: String) {
        Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, hours)
        app.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
    }
}
