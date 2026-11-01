package org.openstrap.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** A link whose strap takes every write and never answers. */
private class SilentLink : StrapLink {
    override val payloadMtu = 20
    override suspend fun writeControl(data: ByteArray) = Unit
    override suspend fun writeData(data: ByteArray) = Unit
    override val controlNotifications: Flow<ByteArray> = MutableSharedFlow()
    override val dataNotifications: Flow<ByteArray> = MutableSharedFlow()
}

/**
 * A silent strap ends in a plain [ProtocolTimeoutException], not `withTimeout`'s cancellation, so a caller that
 * rethrows cancellations still reports the failure (audit C1).
 */
class ChannelSessionTimeoutTest {
    @Test
    fun requestTimeoutIsAPlainException() = runTest {
        val session = ChannelSession(SilentLink())
        session.start(backgroundScope)
        val e = runCatching { session.request(0x0001, byteArrayOf(1), timeoutMs = 1_000) }.exceptionOrNull()
        assertTrue("got $e", e is ProtocolTimeoutException)
        assertTrue("got $e", e !is CancellationException)
    }

    @Test
    fun handshakeTimeoutIsAPlainException() = runTest {
        val session = ChannelSession(SilentLink())
        session.start(backgroundScope)
        val e = runCatching { session.handshake(ByteArray(16)) }.exceptionOrNull()
        assertTrue("got $e", e is ProtocolTimeoutException)
    }
}
