package org.openstrap.app.device

import org.openstrap.app.ble.GattException
import org.openstrap.app.ble.GattTimeoutException
import org.openstrap.app.ble.GattUnusableException
import org.openstrap.protocol.ProtocolException
import org.openstrap.protocol.ProtocolTimeoutException

/**
 * Turns a failed connect into what the Device page says. The raw exception text ("Disconnected (status 133)") goes to
 * the log only (audit E6).
 */
object ConnectErrors {
    fun reason(e: Throwable): String = when {
        e.isPermission() -> "OpenStrap isn't allowed to use Bluetooth. Allow Nearby devices in the app's settings, then tap Connect."
        e is GattUnusableException -> "The saved strap address isn't valid. Forget the strap and pair it again."
        e is GattTimeoutException || e is ProtocolTimeoutException -> "The strap stopped answering."
        e is GattException -> "The Bluetooth link to the strap dropped."
        e is ProtocolException -> "The strap answered in a way OpenStrap doesn't understand."
        else -> "Couldn't connect to the strap."
    }

    /** True when trying again can't help until the user acts, so no reconnect is scheduled (audit E4). */
    fun isFinal(e: Throwable): Boolean = e is GattUnusableException || e.isPermission()

    private fun Throwable.isPermission(): Boolean = generateSequence(this) { it.cause }.any { it is SecurityException }
}
