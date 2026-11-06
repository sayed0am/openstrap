package org.openstrap.app.device

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.openstrap.app.data.catching

/** The most a receiver's work after `goAsync()` may take. Android calls a broadcast stuck after about 10 s. */
internal const val BROADCAST_TIMEOUT_MS = 9_000L

/**
 * The work of a receiver that called `goAsync()`, on [scope] and within [timeoutMs], with [finish] called however it
 * ends: done, failed, timed out or cancelled. Five Glance updates can outlast the broadcast window, and without the
 * bound the pending result was finished late or never (audit C11). A failure or the timeout is logged as [what].
 */
internal fun launchForBroadcast(
    scope: CoroutineScope,
    what: String,
    finish: () -> Unit,
    timeoutMs: Long = BROADCAST_TIMEOUT_MS,
    work: suspend () -> Unit,
): Job = scope.launch {
    try {
        catching { withTimeout(timeoutMs) { work() } }.onFailure { Log.w("unstrap", "$what failed: $it") }
    } finally {
        finish()
    }
}
