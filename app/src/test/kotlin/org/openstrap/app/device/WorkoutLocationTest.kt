package org.openstrap.app.device

import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import android.Manifest
import android.app.Application
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.Route
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class WorkoutLocationTest {
    private fun fix(mock: Boolean) = Location(LocationManager.GPS_PROVIDER).apply {
        time = 1_000_000L
        latitude = 51.5
        longitude = -0.1
        accuracy = 5f
        if (mock) isMock = true
    }

    private fun received(vararg fixes: Location): List<Route.Point> {
        val out = mutableListOf<Route.Point>()
        val gps = WorkoutLocation(ApplicationProvider.getApplicationContext()) { out += it }
        fixes.forEach(gps::receive)
        return out
    }

    @Test
    @Config(sdk = [35])
    fun mockFixesAreDropped() {
        val kept = received(fix(mock = false), fix(mock = true), fix(mock = false))
        assertEquals(2, kept.size)
        assertEquals(Route.Point(1_000, 51.5, -0.1, 5f), kept.first())
    }

    private fun started(fused: Boolean): WorkoutLocation {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(LocationManager::class.java)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled("fused", fused)
        return WorkoutLocation(app) {}.also { it.start() }
    }

    @Test
    @Config(sdk = [35])
    fun startsOnFusedAndFallsBackToGpsWhenItStaysSilent() {
        val gps = started(fused = true)
        assertEquals("fused", gps.source)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WorkoutLocation.FUSED_TIMEOUT_MS))
        assertEquals(LocationManager.GPS_PROVIDER, gps.source)
        gps.stop()
        assertNull(gps.source)
    }

    @Test
    @Config(sdk = [35])
    fun staysOnFusedOnceItGivesAFix() {
        val gps = started(fused = true)
        gps.receive(fix(mock = false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WorkoutLocation.FUSED_TIMEOUT_MS))
        assertEquals("fused", gps.source)
    }

    @Test
    @Config(sdk = [35])
    fun usesGpsWhenThereIsNoFusedProvider() {
        assertEquals(LocationManager.GPS_PROVIDER, started(fused = false).source)
    }

    @Test
    @Config(sdk = [35])
    fun keepsSpeedBearingAndAltitude() {
        val moving = fix(mock = false).apply {
            speed = 1.4f
            bearing = 90f
            altitude = 31.5
            speedAccuracyMetersPerSecond = 0.3f
            bearingAccuracyDegrees = 8f
        }
        assertEquals(Route.Point(1_000, 51.5, -0.1, 5f, 1.4f, 90f, 31.5, 0.3f, 8f), received(moving).single())
        // A fix without them leaves them null rather than nought.
        assertNull(received(fix(mock = false)).single().speed)
    }
}
