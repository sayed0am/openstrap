package org.openstrap.protocol.transport

import org.openstrap.protocol.ByteWriter
import org.openstrap.protocol.u16
import org.openstrap.protocol.u32
import org.openstrap.protocol.u8

/** v2 channel framing (docs/protocol/01 §2.2–2.3). */
object ChunkFlags {
    const val FIRST = 0x01
    const val LAST = 0x02
    const val NEEDS_ACK = 0x04
    const val ENCRYPTED = 0x08
}

object PacketType {
    const val DATA = 0x03
    const val ACK = 0x04
}

object AckStatus {
    const val SUCCESS = 1
    const val SESSION_MISMATCH = 2
    const val PACKET_LOST = 3
    const val BAD_FORMAT = 4
    const val UNKNOWN_ENDPOINT = 5
    const val CANNOT_DECRYPT = 12
}

private const val FIRST_HEADER = 11
private const val NEXT_HEADER = 5
private const val REPLY_FREQ = 16

/**
 * Splits one logical message into chunks for the phone→device characteristic (0016).
 * [data] is the already-encrypted body when [encrypted] is set; [plainLength] is always the plaintext size.
 */
fun buildChunks(
    endpoint: Int,
    sessionId: Int,
    data: ByteArray,
    plainLength: Int,
    encrypted: Boolean,
    payloadMtu: Int,
    wantReply: Boolean = true,
): List<ByteArray> {
    require(payloadMtu > FIRST_HEADER) { "Payload MTU too small: $payloadMtu" }
    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    var index = 0
    do {
        val first = index == 0
        val room = payloadMtu - if (first) FIRST_HEADER else NEXT_HEADER
        val n = minOf(room, data.size - offset)
        val last = offset + n >= data.size
        var flags = 0
        if (first) flags = flags or ChunkFlags.FIRST
        if (last) flags = flags or ChunkFlags.LAST
        if (encrypted) flags = flags or ChunkFlags.ENCRYPTED
        if ((last && wantReply) || (index + 1) % REPLY_FREQ == 0) flags = flags or ChunkFlags.NEEDS_ACK
        val w = ByteWriter().u8(PacketType.DATA).u8(flags).u8(0).u8(sessionId).u8(index)
        if (first) w.u32(plainLength.toLong()).u16(endpoint)
        w.bytes(data.copyOfRange(offset, offset + n))
        chunks += w.toByteArray()
        offset += n
        index = (index + 1) and 0xFF
    } while (offset < data.size)
    return chunks
}

fun buildAck(sessionId: Int, status: Int, chunkIndex: Int): ByteArray =
    ByteWriter().u8(PacketType.ACK).u8(0).u8(sessionId).u8(status).u8(chunkIndex).toByteArray()

/** A device ACK received on 0016. */
data class DeviceAck(val sessionId: Int, val status: Int, val chunkIndex: Int)

/**
 * The strap sends `04 00 sess status idx`, the same layout as the phone's ACK (seen on fw 3.25.0.3). Doc 02 §3.2
 * reads Zepp's parser `fhe.i` as `04 sess status idx appId`; the strap doesn't send that. The 4-byte v1 form
 * `04 sess status idx` is accepted too. A 5-byte ACK whose second byte isn't 0 is ignored.
 */
fun parseDeviceAck(packet: ByteArray): DeviceAck? = when {
    packet.size < 4 || packet.u8(0) != PacketType.ACK -> null
    packet.size == 4 -> DeviceAck(packet.u8(1), packet.u8(2), packet.u8(3))
    packet.u8(1) != 0 -> null
    else -> DeviceAck(packet.u8(2), packet.u8(3), packet.u8(4))
}

/** A fully reassembled incoming message. [payload] is decrypted when the transport had a cipher. */
data class ChannelMessage(val endpoint: Int, val sessionId: Int, val payload: ByteArray, val encrypted: Boolean)

/**
 * Reassembles device→phone chunks from 0017. Feed each notification to [accept]; it returns any
 * ACK bytes the phone must write back to 0017, and completed messages via [onMessage].
 */
class Reassembler(
    private val isKnownEndpoint: (Int) -> Boolean,
    private val decrypt: (sessionId: Int, cipher: ByteArray, totalLength: Int) -> ByteArray,
    private val onMessage: (ChannelMessage) -> Unit,
) {
    /** Told about each message dropped because it didn't decrypt. */
    var onDecryptFailure: (Exception) -> Unit = {}

    private var sessionId = -1
    private var endpoint = 0
    private var totalLength = 0
    private var encrypted = false
    private var lastIndex = 0
    private val buffer = java.io.ByteArrayOutputStream()

    private fun reset() {
        sessionId = -1
        buffer.reset()
    }

    /** [raw] decrypted when the message is encrypted, or null with the failure told to [onDecryptFailure]. */
    @Suppress("TooGenericExceptionCaught") // Any decrypt failure (a bad tag, a bad key, a short block) drops the message.
    private fun payloadOf(raw: ByteArray): ByteArray? =
        if (!encrypted) {
            raw
        } else {
            try {
                decrypt(sessionId, raw, totalLength)
            } catch (e: Exception) {
                onDecryptFailure(e)
                null
            }
        }

    /** Returns an ACK packet to send, or null. Every error drops the message in progress, as Zepp does (02 §4). */
    fun accept(packet: ByteArray): ByteArray? {
        if (packet.size < NEXT_HEADER || packet.u8(0) != PacketType.DATA || packet.u8(2) != 0) return null
        val flags = packet.u8(1)
        val session = packet.u8(3)
        val index = packet.u8(4)
        val needsAck = flags and ChunkFlags.NEEDS_ACK != 0
        fun ack(status: Int) = if (needsAck || status != AckStatus.SUCCESS) buildAck(session, status, index) else null

        val body: ByteArray
        if (flags and ChunkFlags.FIRST != 0) {
            if (packet.size < FIRST_HEADER) return ack(AckStatus.BAD_FORMAT)
            sessionId = session
            totalLength = packet.u32(5).toInt()
            endpoint = packet.u16(9)
            encrypted = flags and ChunkFlags.ENCRYPTED != 0
            lastIndex = index
            buffer.reset()
            if (!isKnownEndpoint(endpoint)) {
                reset()
                return buildAck(session, AckStatus.UNKNOWN_ENDPOINT, index)
            }
            body = packet.copyOfRange(FIRST_HEADER, packet.size)
        } else {
            // A continuation with no message in progress (its first chunk was lost or rejected): nothing to ACK.
            if (sessionId == -1) return null
            if (session != sessionId) { reset(); return ack(AckStatus.SESSION_MISMATCH) }
            if (index != ((lastIndex + 1) and 0xFF)) { reset(); return ack(AckStatus.PACKET_LOST) }
            lastIndex = index
            // Zepp ORs the flag over every chunk.
            if (flags and ChunkFlags.ENCRYPTED != 0) encrypted = true
            body = packet.copyOfRange(NEXT_HEADER, packet.size)
        }
        buffer.write(body)

        val expected = if (encrypted) ((totalLength + 8 + 15) / 16 * 16) else totalLength
        if (buffer.size() > expected) {
            reset()
            return ack(AckStatus.BAD_FORMAT)
        }
        if (flags and ChunkFlags.LAST != 0) {
            if (buffer.size() != expected) {
                reset()
                return ack(AckStatus.BAD_FORMAT)
            }
            val raw = buffer.toByteArray()
            val payload = payloadOf(raw)
            if (payload == null) {
                // Zepp ACKs these as received and drops them silently (02 §4.3); status 12 only makes the strap resend.
                reset()
                return ack(AckStatus.SUCCESS)
            }
            val message = ChannelMessage(endpoint, sessionId, payload, encrypted)
            reset()
            val reply = ack(AckStatus.SUCCESS)
            onMessage(message)
            return reply
        }
        return ack(AckStatus.SUCCESS)
    }
}
