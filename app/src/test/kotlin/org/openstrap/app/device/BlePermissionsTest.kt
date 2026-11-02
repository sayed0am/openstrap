package org.openstrap.app.device

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BlePermissionsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `Android 12 and later need scan and connect, older versions fine location`() {
        val location = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val runtime = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(location, BlePermissions.bluetooth(29))
        assertEquals(location, BlePermissions.bluetooth(30))
        assertEquals(runtime, BlePermissions.bluetooth(31))
        assertEquals(runtime, BlePermissions.bluetooth(36))
    }

    @Test @Config(sdk = [30])
    fun `on Android 11 the request asks for fine location only`() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.required().toList())
    }

    @Test @Config(sdk = [30])
    fun `on Android 11 granted follows fine location, not the Bluetooth permissions it doesn't have`() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(BlePermissions.granted(app))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(BlePermissions.granted(app))
        assertTrue(BlePermissions.granted(mapOf(Manifest.permission.ACCESS_FINE_LOCATION to true)))
        assertFalse(BlePermissions.granted(mapOf(Manifest.permission.BLUETOOTH_SCAN to true, Manifest.permission.BLUETOOTH_CONNECT to true)))
    }

    @Test fun `the request asks for scan, connect and notifications`() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS),
            BlePermissions.required().toList(),
        )
    }

    @Test fun `granted only once both scan and connect are`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(BlePermissions.granted(app))
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(BlePermissions.granted(app))
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        assertTrue(BlePermissions.granted(app))
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(BlePermissions.granted(app))
    }

    @Test fun `a request result needs both, and notifications don't matter`() {
        val both = mapOf(Manifest.permission.BLUETOOTH_SCAN to true, Manifest.permission.BLUETOOTH_CONNECT to true)
        assertTrue(BlePermissions.granted(both + (Manifest.permission.POST_NOTIFICATIONS to false)))
        assertFalse(BlePermissions.granted(both + (Manifest.permission.BLUETOOTH_SCAN to false)))
        assertFalse(BlePermissions.granted(mapOf(Manifest.permission.BLUETOOTH_CONNECT to true)))
    }

    @Test fun `turned down for good once Android owes no rationale for any of them`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val pm = shadowOf(activity.packageManager)
        val phone = listOf(Manifest.permission.READ_PHONE_STATE)
        // Denied once: Android would explain it next time, so the dialog still shows.
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE, true)
        assertFalse(BlePermissions.turnedDownForGood(activity, phone))
        // Denied twice: no rationale and no dialog, so the app's settings page is the only way.
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE, false)
        assertTrue(BlePermissions.turnedDownForGood(activity, phone))
        // One Bluetooth permission still asks again, so the pair isn't blocked yet.
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN, true)
        assertFalse(BlePermissions.turnedDownForGood(activity, BlePermissions.bluetooth))
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN, false)
        assertTrue(BlePermissions.turnedDownForGood(activity, BlePermissions.bluetooth))
    }

    @Test fun `settings opens the app's own page`() {
        val intent = BlePermissions.settingsIntent(app)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.data.toString())
    }
}
