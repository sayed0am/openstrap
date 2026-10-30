package org.openstrap.app.device

import org.openstrap.protocol.auth.AuthException
import org.openstrap.app.R
import org.openstrap.app.tr

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

    fun authFailure(e: AuthException): ConnectionState.Failed = when (e.status) {
        WRONG_KEY, CIPHERTEXT_MISMATCH, AES_FAILED -> ConnectionState.Failed(
            tr(R.string.pairing_errors_the_strap_rejected_this),
            keyRejected = true,
        )
        NO_KEY -> ConnectionState.Failed(
            tr(R.string.pairing_errors_the_strap_has_no),
            keyRejected = true,
        )
        SIGNATURE_FAILED, BINDING_PROTECTED, CLOUD_BIND_REQUIRED -> ConnectionState.Failed(
            tr(R.string.pairing_errors_the_strap_only_accepts),
            keyRejected = true,
        )
        WORKOUT_RUNNING -> ConnectionState.Failed(tr(R.string.pairing_errors_a_workout_is_running))
        else -> ConnectionState.Failed(
            tr(R.string.pairing_errors_pairing_failed_put_the, e.message ?: tr(R.string.pairing_errors_pairing_failed_put_the__unknown_error)),
        )
    }
}
