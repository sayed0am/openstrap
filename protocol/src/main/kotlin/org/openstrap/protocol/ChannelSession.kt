package org.openstrap.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.openstrap.protocol.auth.Auth
import org.openstrap.protocol.auth.AuthException
import org.openstrap.protocol.auth.AuthHandshake
import org.openstrap.protocol.crypto.ChannelCipher
import org.openstrap.protocol.services.ChannelVersion
import org.openstrap.protocol.services.ConnectCodec
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.ServiceList
import org.openstrap.protocol.transport.AckStatus
import org.openstrap.protocol.transport.ChannelMessage
import org.openstrap.protocol.transport.Reassembler
import org.openstrap.protocol.transport.buildChunks
import org.openstrap.protocol.transport.parseDeviceAck

/**
 * The radio side the session needs. The Android app implements this on top of BluetoothGatt;
 * tests implement it with a simulated strap.
 */
interface StrapLink {
    /** ATT MTU − 3. 20 until the strap reports a larger one. */
    val payloadMtu: Int

    /** Write to FEE0/0016 (phone→device chunks, and the version query). */
    suspend fun writeControl(data: ByteArray)

    /** Write to FEE0/0017 (the phone's ACKs for incoming chunks). */
    suspend fun writeData(data: ByteArray)

    /** Notifications from 0016 (version reply, device ACKs). */
    val controlNotifications: Flow<ByteArray>

    /** Notifications from 0017 (device→phone chunks). */
    val dataNotifications: Flow<ByteArray>
}

class ProtocolException(message: String) : Exception(message)

/**
 * One authenticated channel connection (docs/protocol/01 §1.4 steps 6-10).
 * Call [start] once the link's notifications are enabled, then [handshake].
 */
class ChannelSession(
    private val link: StrapLink,
    private val log: (String) -> Unit = {},
) {
    private val sendLock = Mutex()
    private var nextSessionId = 0
    private var cipher: ChannelCipher? = null
    private var reader: Job? = null
    private var ponger: Job? = null
    private var serviceWatcher: Job? = null

    var services: ServiceList = ServiceList(emptyMap())
        private set
    var version: ChannelVersion? = null
        private set
    /** Runtime feature bitmap from 0x0028, set after auth; null when not queried or the query failed (01 §3). */
    @Volatile var features: org.openstrap.protocol.services.DeviceFeatures? = null
    val isAuthenticated get() = cipher != null

    private val _messages = MutableSharedFlow<ChannelMessage>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)

    /** Every reassembled incoming message. */
    val messages: SharedFlow<ChannelMessage> = _messages

    fun messagesFor(endpoint: Int): Flow<ByteArray> = messages.filter { it.endpoint == endpoint }.mapNotNull { it.payload }

    private val reassembler = Reassembler(
        // Before the services list arrives we must accept replies on the services and auth endpoints.
        isKnownEndpoint = { ep -> ep == Endpoints.SERVICES || ep == Endpoints.AUTH || services.has(ep) },
        decrypt = { sid, data, len ->
            (cipher ?: throw ProtocolException("Encrypted message before auth")).decrypt(sid, data, len)
        },
        onMessage = { _messages.tryEmit(it) },
    ).apply { onDecryptFailure = { log("dropped a message that didn't decrypt: $it") } }

    /** Keep-alive pings answered so far (ConnectModule 0x0015, 02 §11). */
    @Volatile var pings = 0
        private set

    fun start(scope: CoroutineScope) {
        reader = scope.launch {
            link.dataNotifications.collect { packet ->
                val ack = reassembler.accept(packet)
                if (ack != null) link.writeData(ack)
            }
        }
        // The strap pings with `03`; Zepp answers `04` at once, without asking for an ACK (jt7.java:428-445).
        // Zepp looks at the first byte only.
        ponger = scope.launch {
            messagesFor(Endpoints.CONNECT).filter { it.isNotEmpty() && it.u8(0) == ConnectCodec.PING.u8(0) }.collect {
                if (pings++ == 0) log("strap ping, answering pong")
                runCatching { send(Endpoints.CONNECT, ConnectCodec.PONG, wantReply = false) }.onFailure { e -> log("pong failed: $e") }
            }
        }
        // The strap can add or remove an endpoint after the list was read (02 §6.3).
        serviceWatcher = scope.launch {
            messagesFor(Endpoints.SERVICES).collect { push ->
                services.applyChange(push)?.let {
                    log("services changed: ${push.toHex()}")
                    services = it
                }
            }
        }
    }

    fun stop() {
        reader?.cancel()
        ponger?.cancel()
        serviceWatcher?.cancel()
        cipher = null
    }

    /** Version query, services list, ECDH auth. Throws [AuthException] on a rejected key. */
    suspend fun handshake(authKey: ByteArray) {
        val v = withTimeout(5_000) {
            val versionReplies = link.controlNotifications.filter { it.isNotEmpty() && it.u8(0) == 0x02 }
            val reply = launchAndAwait(versionReplies) { link.writeControl(ChannelVersion.REQUEST) }
            ChannelVersion.parse(reply) ?: throw ProtocolException("Unexpected version reply ${reply.toHex()}")
        }
        version = v
        log("channel v${v.version}, encryptEnable=${v.encryptEnable}")
        if (v.version != 2 || v.encryptEnable == null) throw ProtocolException("Unsupported channel (v${v.version}, encryption ${v.encryptEnable})")

        // Zepp sends the services request without ACK_REQ (02 §6.1).
        services = ServiceList.parse(request(Endpoints.SERVICES, ServiceList.REQUEST, timeoutMs = 5_000, wantReply = false) { it.u8(0) == 0x04 })
        log("services: " + services.endpoints.entries.joinToString { "%04x%s".format(it.key, if (it.value) "*" else "") })
        if (!services.has(Endpoints.AUTH)) throw ProtocolException("Strap does not offer the v2 auth endpoint")

        val hs = AuthHandshake(authKey)
        val step1 = authRequest(Auth.CMD_KEY_EXCHANGE, hs.step1Request)
        val step2 = authRequest(Auth.CMD_PROVE, hs.handleStep1Reply(step1))
        cipher = hs.handleStep2Reply(step2)
        log("authenticated")
    }

    private suspend fun authRequest(command: Int, payload: ByteArray): ByteArray {
        val reply = request(Endpoints.AUTH, payload, timeoutMs = 15_000) { Auth.stripReply(command, it) != null }
        return Auth.stripReply(command, reply)!!
    }

    /** False once the strap failed to ACK an encrypted message in time; we then stop waiting for those ACKs. */
    @Volatile private var awaitEncryptedAcks = true

    /**
     * Sends [payload] to [endpoint], encrypting when the strap requires it for that endpoint. [wantReply] false
     * leaves ACK_REQ off the last chunk, for messages the strap doesn't acknowledge. Encrypted messages always ask
     * for the ACK, as Zepp does (`sk7.java:97`); one the strap reports as lost is sent again once.
     */
    suspend fun send(endpoint: Int, payload: ByteArray, wantReply: Boolean = true) = sendLock.withLock {
        if (sendOnce(endpoint, payload, wantReply) == AckStatus.PACKET_LOST) {
            log("strap lost a message on ${"%04x".format(endpoint)}, sending it again")
            sendOnce(endpoint, payload, wantReply)
        }
    }

    /** Returns the strap's ACK status for an encrypted message, or null when none was awaited or none came. */
    private suspend fun sendOnce(endpoint: Int, payload: ByteArray, wantReply: Boolean): Int? {
        val sid = nextSessionId
        nextSessionId = (nextSessionId + 1) and 0xFF
        val c = cipher
        // Zepp encrypts only when the channel version said encryption is on and the endpoint asks for it.
        val encrypt = c != null && version?.encryptEnable == true && services.requiresEncryption(endpoint)
        val body = if (encrypt) c.encrypt(sid, payload) else payload
        val chunks = buildChunks(endpoint, sid, body, payload.size, encrypt, link.payloadMtu, wantReply || encrypt)
        if (!encrypt || !awaitEncryptedAcks) {
            for (chunk in chunks) link.writeControl(chunk)
            return null
        }
        // Zepp matches the ACK on the session id only (02 §3.2).
        val acks = link.controlNotifications.mapNotNull { parseDeviceAck(it) }.filter { it.sessionId == sid }
        // Listen before writing so a fast ACK isn't missed; only the wait after the last write is timed.
        val ack = coroutineScope {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { acks.first() }
            for (chunk in chunks) link.writeControl(chunk)
            withTimeoutOrNull(ENCRYPTED_ACK_TIMEOUT_MS) { waiter.await() }.also { waiter.cancel() }
        }
        if (ack == null) {
            awaitEncryptedAcks = false
            log("no ACK for an encrypted message on ${"%04x".format(endpoint)}; not waiting for them any more")
        } else if (ack.status != AckStatus.SUCCESS) {
            log("strap ACKed ${"%04x".format(endpoint)} with status ${ack.status}")
        }
        return ack?.status
    }

    /** Sends and waits for the first reply on the same endpoint that satisfies [matches]. */
    suspend fun request(
        endpoint: Int,
        payload: ByteArray,
        timeoutMs: Long = 10_000,
        wantReply: Boolean = true,
        matches: (ByteArray) -> Boolean = { true },
    ): ByteArray = withTimeout(timeoutMs) {
        launchAndAwait(messagesFor(endpoint).filter(matches)) { send(endpoint, payload, wantReply) }
    }

    /** Subscribes to [flow] before running [action], so a fast reply can't be missed. */
    private companion object {
        const val ENCRYPTED_ACK_TIMEOUT_MS = 2_000L
    }

    private suspend fun <T> launchAndAwait(flow: Flow<T>, action: suspend () -> Unit): T =
        coroutineScope {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { flow.first() }
            action()
            waiter.await()
        }
}
