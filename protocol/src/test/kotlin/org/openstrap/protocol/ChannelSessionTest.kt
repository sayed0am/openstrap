package org.openstrap.protocol

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.protocol.auth.AuthException
import org.openstrap.protocol.crypto.Aes
import org.openstrap.protocol.crypto.B163
import org.openstrap.protocol.crypto.ChannelCipher
import org.openstrap.protocol.services.BatteryInfo
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.transport.AckStatus
import org.openstrap.protocol.transport.ChunkFlags
import org.openstrap.protocol.transport.Reassembler
import org.openstrap.protocol.transport.buildChunks

/**
 * A simulated strap speaking the device side of docs/protocol/01: version query, services list,
 * ECDH auth and an encrypted battery endpoint.
 */
private class SimulatedStrap(private val authKey: ByteArray) : StrapLink {
    override val payloadMtu = 20
    override val controlNotifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val dataNotifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private val keys = B163.generateKeyPair()
    private val challenge = ByteArray(16) { (it * 5 + 1).toByte() }
    private var pendingCipher: ChannelCipher? = null
    private var cipher: ChannelCipher? = null
    private var deviceSession = 0
    val phoneAcks = mutableListOf<ByteArray>()
    /** Every message the phone sent, with the flags byte of its last chunk. */
    val received = MutableSharedFlow<Triple<Int, ByteArray, Int>>(replay = 32)
    private var lastFlags = 0
    /** Report the next encrypted message as lost (ACK status 3). */
    var loseNextEncrypted = false
    var encryptedAcks = 0

    private val reassembler = Reassembler({ true }, { sid, data, len ->
        // The device decrypts with its own rx counter, which starts at the phone's tx counter.
        cipher!!.decrypt(sid, data, len)
    }) { msg -> received.tryEmit(Triple(msg.endpoint, msg.payload, lastFlags)); respond(msg.endpoint, msg.payload) }

    override suspend fun writeControl(data: ByteArray) {
        if (data.contentEquals(byteArrayOf(1))) {
            controlNotifications.emit(byteArrayOf(2, 2, 1))
            return
        }
        if (data.size > 1) lastFlags = data[1].toInt() and 0xFF
        // The strap's ACK has the same `04 00 sess st idx` layout as the reassembler's.
        reassembler.accept(data)?.let { ack ->
            var status = ack[3].toInt()
            if (loseNextEncrypted && lastFlags and ChunkFlags.ENCRYPTED != 0 && lastFlags and ChunkFlags.LAST != 0) {
                loseNextEncrypted = false
                status = AckStatus.PACKET_LOST
            }
            encryptedAcks += if (lastFlags and ChunkFlags.ENCRYPTED != 0) 1 else 0
            controlNotifications.emit(byteArrayOf(0x04, 0, ack[2], status.toByte(), ack[4]))
        }
        flushOutbox()
    }

    override suspend fun writeData(data: ByteArray) {
        phoneAcks += data
    }

    private val outbox = mutableListOf<Pair<Int, ByteArray>>()
    private fun respond(endpoint: Int, payload: ByteArray) {
        when (endpoint) {
            Endpoints.SERVICES -> outbox += endpoint to "04 0400 0000 00 8200 00 2900 01 1500 00".hexToBytes()
            Endpoints.AUTH -> when (payload[0].toInt()) {
                4 -> {
                    val shared = B163.sharedSecret(keys.privateKey, payload.copyOfRange(4, 52))
                    pendingCipher = ChannelCipher(shared, authKey)
                    outbox += endpoint to byteArrayOf(0x10, 4, 1) + challenge + keys.publicKey
                }
                5 -> {
                    val c = pendingCipher!!
                    val ok = payload.copyOfRange(1, 17).contentEquals(Aes.encrypt(authKey, challenge)) &&
                        payload.copyOfRange(17, 33).contentEquals(Aes.encrypt(c.sessionKey, challenge))
                    outbox += endpoint to byteArrayOf(0x10, 5, if (ok) 1 else 0x26)
                    if (ok) cipher = mirrored(c)
                }
            }
            Endpoints.BATTERY -> outbox += endpoint to "04 01 4d 00".hexToBytes()
        }
    }

    /** Device-side cipher: its tx counter is the phone's rx counter and vice versa. */
    private fun mirrored(phoneView: ChannelCipher): ChannelCipher {
        val s = ByteArray(24)
        val tx = phoneView.rxCounter
        val rx = phoneView.txCounter
        for (i in 0..3) {
            s[i] = (tx ushr (8 * i)).toByte()
            s[4 + i] = (rx ushr (8 * i)).toByte()
        }
        // Bytes 8..23 must reproduce the same session key: S[8+i] xor K[i] == sessionKey[i].
        for (i in 0..15) s[8 + i] = (phoneView.sessionKey[i].toInt() xor authKey[i].toInt()).toByte()
        return ChannelCipher(s, authKey)
    }

    /** ConnectModule keep-alive ping (02 §11). */
    suspend fun ping() {
        outbox += Endpoints.CONNECT to byteArrayOf(0x03)
        flushOutbox()
    }

    private suspend fun flushOutbox() {
        while (outbox.isNotEmpty()) {
            val (endpoint, payload) = outbox.removeAt(0)
            val sid = deviceSession++ and 0xFF
            val c = cipher
            val encrypt = c != null && endpoint == Endpoints.BATTERY
            val body = if (encrypt) c.encrypt(sid, payload) else payload
            buildChunks(endpoint, sid, body, payload.size, encrypt, payloadMtu).forEach { dataNotifications.emit(it) }
        }
    }
}

class ChannelSessionTest {
    private val key = "3c4a6f0e9b1d22e7a8c5f01b6d9e4a77".hexToBytes()

    @Test
    fun fullHandshakeThenEncryptedRequest() = runTest {
        val strap = SimulatedStrap(key)
        val session = ChannelSession(strap)
        session.start(backgroundScope)
        session.handshake(key)
        assertTrue(session.isAuthenticated)
        assertTrue(session.services.requiresEncryption(Endpoints.BATTERY))

        val reply = session.request(Endpoints.BATTERY, BatteryInfo.GET) { it[0].toInt() == 4 }
        assertEquals(77, BatteryInfo.parseChannel(reply)!!.level)
        // The phone acknowledged the device's multi-chunk replies.
        assertTrue(strap.phoneAcks.isNotEmpty())
    }

    @Test
    fun encryptedMessagesAskForAndGetAnAck() = runTest {
        val strap = SimulatedStrap(key)
        val logs = mutableListOf<String>()
        val session = ChannelSession(strap, log = { logs += it })
        session.start(backgroundScope)
        session.handshake(key)

        // Encrypted, and sent without asking for a reply: Zepp still sets ACK_REQ on it (sk7.java:97).
        session.send(Endpoints.BATTERY, BatteryInfo.GET, wantReply = false)
        val (_, _, flags) = withTimeout(5_000) { strap.received.first { it.first == Endpoints.BATTERY } }
        assertTrue(flags and ChunkFlags.NEEDS_ACK != 0)
        assertEquals(1, strap.encryptedAcks)
        assertTrue("$logs", logs.none { it.startsWith("no ACK") })
    }

    @Test
    fun encryptedMessageReportedLostIsSentAgain() = runTest {
        val strap = SimulatedStrap(key)
        val session = ChannelSession(strap)
        session.start(backgroundScope)
        session.handshake(key)

        strap.loseNextEncrypted = true
        session.send(Endpoints.BATTERY, BatteryInfo.GET)
        assertEquals(2, strap.received.replayCache.count { it.first == Endpoints.BATTERY })
    }

    @Test
    fun answersPingWithPongWithoutAckRequest() = runTest {
        val strap = SimulatedStrap(key)
        val session = ChannelSession(strap)
        session.start(backgroundScope)
        session.handshake(key)

        strap.ping()
        val (_, payload, flags) = withTimeout(5_000) { strap.received.first { it.first == Endpoints.CONNECT } }
        assertArrayEquals(byteArrayOf(0x04), payload)
        // FIRST | LAST, and no ACK_REQ: the strap doesn't acknowledge pongs.
        assertEquals(ChunkFlags.FIRST or ChunkFlags.LAST, flags)
        assertEquals(1, session.pings)
    }

    @Test
    fun wrongKeyFailsWithStatus() = runTest {
        val strap = SimulatedStrap(key)
        val session = ChannelSession(strap)
        session.start(backgroundScope)
        val e = runCatching { session.handshake("00000000000000000000000000000000".hexToBytes()) }.exceptionOrNull()
        assertTrue("got $e", e is AuthException && e.status == 0x26)
    }
}
