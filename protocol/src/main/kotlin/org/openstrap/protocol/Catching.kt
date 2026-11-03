package org.openstrap.protocol

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspend code, the protocol module's copy of the app's `catching`. `runCatching` turns a
 * cancellation into a failure, so a cancelled session would keep answering. This rethrows a [CancellationException]
 * once the calling coroutine is no longer active. A `withTimeout` that expires inside [block] while the caller is
 * still active comes back as a failure, since it is a timeout, not a cancellation.
 */
// Catching everything is the point: it is runCatching with cancellation kept.
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> catching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(e)
    }
