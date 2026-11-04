package org.openstrap.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** [catching] is runCatching that lets a real cancellation through and keeps a timeout as a failure. */
class CatchingTest {
    @Test
    fun successIsSuccess() = runTest {
        assertEquals(Result.success(42), catching { delay(10).let { 42 } })
    }

    @Test
    fun anErrorIsAFailure() = runTest {
        val r = catching { throw IOException("gone") }
        assertTrue(r.exceptionOrNull() is IOException)
    }

    @Test
    fun cancellingTheCallerRethrows() = runTest {
        val started = CompletableDeferred<Unit>()
        var returned = false
        var rethrown = false
        val job = launch {
            try {
                catching {
                    started.complete(Unit)
                    awaitCancellation()
                }
                returned = true
            } catch (e: CancellationException) {
                rethrown = true
                throw e
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue(rethrown)
        assertFalse(returned)
        assertTrue(job.isCancelled)
    }

    @Test
    fun aTimeoutInsideIsAFailure() = runTest {
        // TimeoutCancellationException is a CancellationException, but the caller is still active: the C1 trap.
        val r = catching { withTimeout(100) { awaitCancellation() } }
        assertTrue(r.exceptionOrNull() is TimeoutCancellationException)
    }
}
