package org.openstrap.app.device

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import org.openstrap.protocol.auth.AuthException

/** Turns the strap's auth status codes (docs/protocol/01 §3.4) into what the user should do next. */
object PairingErrors {
    private const val WORKOUT_RUNNING = 5
    private const val NO_KEY = 7
    private const val SIGNATURE_FAILED = 8
    private const val AES_FAILED = 0x23
    private const val CIPHERTEXT_MISMATCH = 0x25
    private const val WRONG_KEY = 0x26
    private const val BINDING_PROTECTED = 0x41
    private const val CLOUD_BIND_REQUIRED = 0x81

    fun authFailure(e: AuthException, strings: Strings = Strings.app): ConnectionState.Failed = when (e.status) {
        WRONG_KEY, CIPHERTEXT_MISMATCH, AES_FAILED ->
            ConnectionState.Failed(strings.get(R.string.system_pairing_wrong_key), keyRejected = true)
        NO_KEY -> ConnectionState.Failed(strings.get(R.string.system_pairing_no_key), keyRejected = true)
        SIGNATURE_FAILED, BINDING_PROTECTED, CLOUD_BIND_REQUIRED ->
            ConnectionState.Failed(strings.get(R.string.system_pairing_cloud_bind), keyRejected = true)
        WORKOUT_RUNNING -> ConnectionState.Failed(strings.get(R.string.system_pairing_workout_running))
        else -> ConnectionState.Failed(
            strings.format(R.string.system_pairing_failed, e.message ?: strings.get(R.string.system_pairing_unknown_error)),
        )
    }
}
