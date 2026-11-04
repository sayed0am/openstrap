package org.openstrap.app.ui.workout

import org.openstrap.app.R
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.WorkoutCopy
import org.openstrap.app.presentation.WorkoutCopy.ReadyHero

/*
 * Ready's hero from the strap's connection. Here rather than in [WorkoutCopy], since presentation reads nothing from
 * device: the words stay pure, this picks them by the connection. The text is in res/values/strings_workout_start.xml.
 */

/** "Strap connected" and "Live from your strap.", with the battery when low; "Strap not connected" otherwise. */
fun WorkoutCopy.readyHero(connection: ConnectionState, bpm: Int?, strings: Strings = Strings.app): ReadyHero = when (connection) {
    is ConnectionState.Connected -> {
        val low = connection.batteryPercent?.takeIf { it <= READY_LOW_BATTERY && !connection.charging }
        val line = when {
            low == null -> strings.get(if (bpm != null) R.string.workout_ready_live else R.string.workout_ready_waiting)
            bpm != null -> strings.format(R.string.workout_ready_live_battery, low)
            else -> strings.format(R.string.workout_ready_waiting_battery, low)
        }
        ReadyHero(strings.get(R.string.workout_ready_connected), line, live = true)
    }
    ConnectionState.Connecting, ConnectionState.Authenticating ->
        ReadyHero(strings.get(R.string.workout_ready_connecting), strings.get(R.string.workout_ready_anyway), live = false)
    ConnectionState.NotPaired ->
        ReadyHero(strings.get(R.string.workout_ready_not_paired), strings.get(R.string.workout_ready_anyway), live = false)
    ConnectionState.Disconnected, is ConnectionState.Failed ->
        ReadyHero(strings.get(R.string.workout_ready_not_connected), strings.get(R.string.workout_ready_anyway), live = false)
}
