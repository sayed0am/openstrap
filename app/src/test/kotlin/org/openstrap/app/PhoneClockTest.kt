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
import org.openstrap.app.testing.SourceTree
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalTime
import java.util.Locale

/**
 * The app takes the phone's 12 or 24 hour clock when it starts and follows it after. The whole app can't start under
 * Robolectric (WorkManager needs resources the unit tests don't have), so the start's first step, [PhoneClock.follow],
 * runs here on a phone set to 12 hours, and the app's start is checked to take it before anything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneClockTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @After fun reset() {
        ClockSetting.use24Hour = true
    }

    @Test fun `a 12 hour phone's clock is set when the app starts`() {
        Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "12")
        ClockSetting.use24Hour = true
        PhoneClock.follow(app)
        assertFalse(ClockSetting.use24Hour)
        assertEquals("10:30 PM", TimeOfDay.format(LocalTime.of(22, 30), locale = Locale.US))
    }

    @Test fun `the app's start takes the clock before anything writes a time`() {
        val onCreate = File(SourceTree.kotlin, "OpenStrapApp.kt").readText().substringAfter("override fun onCreate()")
        val follow = onCreate.indexOf("PhoneClock.follow(this)")
        assertTrue("onCreate follows the phone's clock", follow >= 0)
        assertTrue("before the container starts", follow < onCreate.indexOf("AppContainer(this)"))
    }

    @Test fun `the clock follows the phone's setting when it changes`() {
        Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "12")
        PhoneClock.follow(app)
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
