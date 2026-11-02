package org.openstrap.app.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.openstrap.app.data.Route

/**
 * Location for an outdoor workout through Android's location service. Fixes go to [onFix] once a second while
 * [start]ed. Off unless the wearer turned GPS on for this workout.
 *
 * Like Zepp (native 01 §2.1), it starts on the fused provider, which seeds the GPS chip with a Wi-Fi and cell
 * position so the first fix comes in seconds, and falls back to the plain GPS provider when fused gives nothing within
 * [FUSED_TIMEOUT_MS] or the phone has none. On most phones fused is Google Play services, so the network part of
 * the position comes from Google; coarse fixes never reach the route (`Route.clean` keeps only accurate ones).
 */
class WorkoutLocation(private val context: Context, private val onFix: (Route.Point) -> Unit) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var mockFixes = 0
    private var fixes = 0

    /** The provider in use while started: [LocationManager.FUSED_PROVIDER] or [LocationManager.GPS_PROVIDER]. */
    internal var source: String? = null
        private set

    private val fallback = Runnable {
        if (listening && source == FUSED && fixes == 0) {
            Log.i(TAG, "gps: fused gave no location in ${FUSED_TIMEOUT_MS / 1000} s, using plain GPS")
            runCatching { manager?.removeUpdates(listener) }
            if (!request(GPS)) stop()
        }
    }

    private val listener = LocationListener(::receive)

    /** Mock fixes are dropped, as Zepp does before its engine (native 01 §2.3); the rest go to [onFix]. */
    internal fun receive(l: Location) {
        if (isMock(l)) {
            mockFixes++
            return
        }
        fixes++
        onFix(point(l))
    }

    /** False without permission or a GPS provider; the workout then measures distance from the strap's steps. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listening) return true
        if (!granted(context) || manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) != true) return false
        warmUp(manager)
        mockFixes = 0
        fixes = 0
        val fused = runCatching { manager.isProviderEnabled(FUSED) }.getOrDefault(false) && request(FUSED)
        if (!fused && !request(GPS)) return false
        listening = true
        if (fused) handler.postDelayed(fallback, FUSED_TIMEOUT_MS)
        return true
    }

    /**
     * Updates once a second at high accuracy. That has to be asked for: the plain overload leaves fused at balanced
     * power, which may never turn the GPS chip on.
     */
    @SuppressLint("MissingPermission")
    private fun request(provider: String): Boolean {
        val m = manager ?: return false
        return runCatching {
            val request = LocationRequest.Builder(INTERVAL_MS).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
            m.requestLocationUpdates(provider, request, context.mainExecutor, listener)
            source = provider
        }.isSuccess
    }

    /**
     * Ask the GPS chip to fetch satellite orbit predictions (XTRA) and the network time now, as Zepp does at the start
     * of a GPS workout, so the first good fix comes sooner. Both come from the chip vendor's servers set in the
     * phone's GNSS config, not Google services. Best effort: a phone that ignores the commands just settles slower.
     */
    private fun warmUp(manager: LocationManager) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        for (command in listOf("force_xtra_injection", "force_time_injection")) {
            runCatching { manager.sendExtraCommand(LocationManager.GPS_PROVIDER, command, Bundle()) }
        }
    }

    fun stop() {
        handler.removeCallbacks(fallback)
        if (!listening) return
        runCatching { manager?.removeUpdates(listener) }
        listening = false
        source = null
        if (mockFixes > 0) Log.i(TAG, "gps: dropped $mockFixes mock fixes")
    }

    companion object {
        private const val INTERVAL_MS = 1_000L

        /** How long fused gets to produce a location before plain GPS takes over, as in Zepp (native 01 §2.1). */
        internal const val FUSED_TIMEOUT_MS = 20_000L
        private const val FUSED = LocationManager.FUSED_PROVIDER
        private const val GPS = LocationManager.GPS_PROVIDER
        private const val TAG = "OpenStrap"

        /** From a mock-location app rather than the GPS chip. */
        internal fun isMock(l: Location): Boolean = l.isMock

        /**
         * Everything the route can use from a fix: speed and bearing let `Route.smooth` follow turns (native 02 §3.7),
         * altitude is kept for later. Each only when the fix has it.
         */
        internal fun point(l: Location) = Route.Point(
            l.time / 1000, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else Float.MAX_VALUE,
            speed = l.speed.takeIf { l.hasSpeed() && !EMULATOR },
            bearing = l.bearing.takeIf { l.hasBearing() && !EMULATOR },
            altitude = l.altitude.takeIf { l.hasAltitude() },
            speedAccuracy = l.speedAccuracyMetersPerSecond.takeIf { l.hasSpeedAccuracy() },
            bearingAccuracy = l.bearingAccuracyDegrees.takeIf { l.hasBearingAccuracy() },
        )

        /** A debug build on the emulator, whose `geo fix` always claims standing still: its speed and bearing are left out. */
        private val EMULATOR = org.openstrap.app.BuildConfig.DEBUG && Build.HARDWARE == "ranchu"

        /** Fine or approximate; approximate fixes are too coarse to count, so distance falls back to steps. */
        fun granted(context: Context) =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
