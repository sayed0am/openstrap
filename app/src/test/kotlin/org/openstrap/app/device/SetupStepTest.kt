package org.openstrap.app.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.ble.GattTimeoutException
import org.openstrap.app.testing.assertCleanCopy
import org.openstrap.protocol.ProtocolTimeoutException

/** afterAuth's steps (audit C5) and the connect failure copy (E4, E6). */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupStepTest {
    @Test
    fun aCancelledConnectStopsAtTheStepInsteadOfFailing() = runTest {
        val log = mutableListOf<String>()
        val inStep = CompletableDeferred<Unit>()
        var after = false
        var failed = false
        val connect = launch {
            try {
                setupStep("settings version query", { log += it }) {
                    inStep.complete(Unit)
                    awaitCancellation()
                }
                after = true
            } catch (e: Exception) {
                // What the connect catch sees: it must find the job cancelled, not a failure to report.
                if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) failed = true
                throw e
            }
        }
        inStep.await()
        connect.cancel()
        runCurrent()
        assertTrue(connect.isCancelled)
        assertEquals(false, after)
        assertEquals(false, failed)
        assertTrue("$log", log.isEmpty())
    }

    @Test
    fun aTimedOutStepIsLoggedAndSetupGoesOn() = runTest {
        val log = mutableListOf<String>()
        val plain = setupStep("mtu query", { log += it }) { throw ProtocolTimeoutException("No reply") }
        // withTimeout's own expiry, while the caller is still active, is a failure too.
        val timed = setupStep<Unit>("time sync", { log += it }) { withTimeout(10) { awaitCancellation() } }
        val ok = setupStep("battery read", { log += it }) { 80 }
        assertNull(plain)
        assertNull(timed)
        assertEquals(80, ok)
        assertEquals(2, log.size)
        assertTrue(log[0], log[0].startsWith("mtu query failed: "))
    }

    @Test
    fun connectFailuresReadAsCopy() {
        val reasons = listOf(
            GattTimeoutException("No answer to connect in 30000 ms"),
            ProtocolTimeoutException("No reply"),
            org.openstrap.app.ble.GattException("Disconnected (status 133)"),
            org.openstrap.protocol.ProtocolException("Unsupported channel"),
            org.openstrap.app.ble.GattUnusableException("Not a Bluetooth address: x", IllegalArgumentException("x")),
            org.openstrap.app.ble.GattUnusableException("No Bluetooth permission", SecurityException("denied")),
            IllegalStateException("boom"),
        ).map(ConnectErrors::reason)
        reasons.forEach { assertCleanCopy(it) }
        // No raw exception text.
        assertTrue("$reasons", reasons.none { it.contains("133") || it.contains("boom") || it.contains("30000") || it.contains("Unsupported") })
    }

    @Test
    fun onlyPermissionAndAddressFailuresAreFinal() {
        assertTrue(ConnectErrors.isFinal(org.openstrap.app.ble.GattUnusableException("bad", IllegalArgumentException())))
        assertTrue(ConnectErrors.isFinal(SecurityException("denied")))
        assertEquals(false, ConnectErrors.isFinal(GattTimeoutException("slow")))
        assertEquals(false, ConnectErrors.isFinal(ProtocolTimeoutException("slow")))
    }
}
