package org.openstrap.app.device

import org.openstrap.app.R
import org.openstrap.app.ble.GattException
import org.openstrap.app.ble.GattTimeoutException
import org.openstrap.app.ble.GattUnusableException
import org.openstrap.app.i18n.Strings
import org.openstrap.protocol.ProtocolException
import org.openstrap.protocol.ProtocolTimeoutException

/**
 * Turns a failed connect into what the Device page says. The raw exception text ("Disconnected (status 133)") goes to
 * the log only (audit E6).
 */
object ConnectErrors {
    fun reason(e: Throwable, strings: Strings = Strings.app): String = strings.get(
        when {
            e.isPermission() -> R.string.system_connect_permission
            e is GattUnusableException -> R.string.system_connect_bad_address
            e is GattTimeoutException || e is ProtocolTimeoutException -> R.string.system_connect_timeout
            e is GattException -> R.string.system_connect_link_dropped
            e is ProtocolException -> R.string.system_connect_protocol
            else -> R.string.system_connect_failed
        },
    )

    /** True when trying again can't help until the user acts, so no reconnect is scheduled (audit E4). */
    fun isFinal(e: Throwable): Boolean = e is GattUnusableException || e.isPermission()

    private fun Throwable.isPermission(): Boolean = generateSequence(this) { it.cause }.any { it is SecurityException }
}
