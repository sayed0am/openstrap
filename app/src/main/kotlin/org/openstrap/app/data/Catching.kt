package org.openstrap.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspend code. `runCatching` turns a cancellation into a failure, so a cancelled request shows an
 * error, a cleared view model sets a Failed step and a cancelled loop keeps going. This rethrows a
 * [CancellationException] once the calling coroutine is no longer active, so cancellation still unwinds.
 *
 * It asks the coroutine, not the exception's type. A `withTimeout` that expires inside [block] throws a
 * `TimeoutCancellationException`, which is a [CancellationException] too, while the caller is still active. That one
 * is a timeout, not a cancellation, so it comes back as a failure. Rethrowing every [CancellationException] would let
 * a timeout escape as if the caller were cancelled and leave its state where it was (audit C1 and C2).
 */
// Catching everything is the point: it is runCatching with cancellation kept.
@Suppress("TooGenericExceptionCaught")
suspend inline fun <T> catching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(e)
    }
