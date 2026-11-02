package org.openstrap.app.ui.workout

import org.openstrap.app.device.ConnectionState
import org.openstrap.app.presentation.WorkoutCopy
import org.openstrap.app.presentation.WorkoutCopy.ReadyHero

/*
 * Ready's hero from the strap's connection. Here rather than in [WorkoutCopy], since presentation reads nothing from
 * device: the words stay pure, this picks them by the connection.
 */

/** "Strap connected" and "Live from your strap.", with the battery when low; "Strap not connected" otherwise. */
fun WorkoutCopy.readyHero(connection: ConnectionState, bpm: Int?): ReadyHero = when (connection) {
    is ConnectionState.Connected -> ReadyHero(
        "Strap connected",
        listOfNotNull(
            if (bpm != null) "Live from your strap." else "Waiting for heart rate.",
            connection.batteryPercent?.takeIf { it <= READY_LOW_BATTERY && !connection.charging }?.let { "Battery $it%." },
        ).joinToString(" "),
        live = true,
    )
    ConnectionState.Connecting, ConnectionState.Authenticating -> ReadyHero("Connecting to the strap", ANYWAY, live = false)
    ConnectionState.NotPaired -> ReadyHero("No strap paired", ANYWAY, live = false)
    ConnectionState.Disconnected, is ConnectionState.Failed -> ReadyHero("Strap not connected", ANYWAY, live = false)
}

private const val ANYWAY = "You can start anyway."
