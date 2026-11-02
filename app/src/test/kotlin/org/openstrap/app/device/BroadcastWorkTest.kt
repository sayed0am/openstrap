package org.openstrap.app.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A receiver's pending result is finished however its work ends, and within the broadcast window (audit C11). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BroadcastWorkTest {
    @Test fun `work that hangs is cut off at the timeout and finished`() = runTest {
        var finished = 0
        launchForBroadcast(this, "test", { finished++ }) { awaitCancellation() }
        advanceTimeBy(BROADCAST_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(0, finished)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, finished)
    }

    @Test fun `work that fails is finished`() = runTest {
        var finished = 0
        launchForBroadcast(this, "test", { finished++ }) { error("no database") }
        runCurrent()
        assertEquals(1, finished)
    }

    @Test fun `work cancelled with the scope is finished`() = runTest {
        var finished = 0
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        launchForBroadcast(scope, "test", { finished++ }) { awaitCancellation() }
        runCurrent()
        scope.cancel()
        runCurrent()
        assertEquals(1, finished)
    }
}
