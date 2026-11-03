package org.openstrap.protocol.firmware

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.openstrap.protocol.crypto.crc32

/** GATT access for DFU: commands/replies on HUAMI(1531), data on HUAMI(1532). */
interface DfuLink {
    val payloadMtu: Int
    suspend fun writeCommand(data: ByteArray)
    suspend fun writeData(data: ByteArray)
    val replies: Flow<ByteArray>
}

class DfuException(val code: Int, message: String) : Exception(message)

sealed interface DfuProgress {
    data class Transferring(val sent: Long, val total: Long) : DfuProgress
    data object Validating : DfuProgress
    data object Applying : DfuProgress
    data object Done : DfuProgress
}

/**
 * One DFU V5 transfer (docs/protocol/03 §3.4), following the brick-safety checklist:
 * the device must be idle, every step must return success, and D6 is only sent after D5 succeeded.
 */
class DfuSession(
    private val link: DfuLink,
    private val log: (String) -> Unit = {},
    private val timeoutMs: Long = 15_000,
) {
    private val inbox = Channel<DfuReply>(Channel.UNLIMITED)

    suspend fun flash(
        file: ByteArray,
        type: Int,
        mode: Int = DfuCommands.MODE_NORMAL,
        onProgress: (DfuProgress) -> Unit = {},
    ) = coroutineScope {
        // Subscribe before the first write so a fast reply cannot be missed.
        val reader = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            link.replies.collect { n -> DfuReply.parse(n)?.let { inbox.send(it) } }
        }
        try {
            val protocol = request<DfuReply.Protocol>(byteArrayOf(DfuCommands.QUERY_PROTOCOL.toByte()))
            val window = protocol.maxChunkSize.coerceAtLeast(link.payloadMtu)
            log("dfu: protocol v${protocol.version}, window $window, background=${protocol.backgroundUpgrade}")

            val status = request<DfuReply.Status>(byteArrayOf(DfuCommands.QUERY_STATUS.toByte()))
            if (status.state != DfuState.IDLE && status.state != DfuState.WAITING_NEXT_FIRMWARE) {
                throw DfuException(0, "Strap is busy (DFU state ${status.state})")
            }

            val crc = crc32(file)
            val prepared = request<DfuReply.Prepared>(DfuCommands.prepare(type, file.size, crc, protocol.maxChunkSize, mode))
            var offset = 0L
            if (prepared.receivedSize in 1 until file.size &&
                crc32(file.copyOfRange(0, prepared.receivedSize.toInt())) == prepared.receivedCrc
            ) {
                offset = prepared.receivedSize
                log("dfu: resuming at $offset")
            }
            request<DfuReply.Simple>(DfuCommands.start(resume = offset > 0))

            while (offset < file.size) {
                val end = minOf(offset + window, file.size.toLong()).toInt()
                var pos = offset.toInt()
                while (pos < end) {
                    val n = minOf(link.payloadMtu, end - pos)
                    link.writeData(file.copyOfRange(pos, pos + n))
                    pos += n
                }
                val ack = awaitProgress()
                if (ack.receivedSize != end.toLong()) {
                    throw DfuException(-4, "Strap acknowledged ${ack.receivedSize} bytes, expected $end")
                }
                offset = ack.receivedSize
                onProgress(DfuProgress.Transferring(offset, file.size.toLong()))
            }

            onProgress(DfuProgress.Validating)
            awaitFinal(DfuCommands.VALIDATE, DfuState.VALIDATING)

            if (mode == DfuCommands.MODE_BACKGROUND) {
                onProgress(DfuProgress.Done)
                return@coroutineScope
            }
            onProgress(DfuProgress.Applying)
            awaitFinal(DfuCommands.APPLY, DfuState.IN_UPGRADING)
            delay(2_000)
            onProgress(DfuProgress.Done)
        } finally {
            reader.cancel()
        }
    }

    private suspend inline fun <reified T : DfuReply> request(bytes: ByteArray): T {
        val cmd = bytes[0].toInt() and 0xFF
        link.writeCommand(bytes)
        val reply = withTimeout(timeoutMs) {
            var r: DfuReply
            do { r = inbox.receive() } while (r.command != cmd)
            r
        }
        if (!reply.ok) throw DfuException(reply.code, "Step %02X failed: %s".format(cmd, DfuCode.describe(reply.code)))
        return reply as? T ?: throw DfuException(-5, "Unexpected reply to %02X".format(cmd))
    }

    private suspend fun awaitProgress(): DfuReply.Progress = withTimeout(timeoutMs) {
        while (true) {
            when (val r = inbox.receive()) {
                is DfuReply.Progress -> return@withTimeout r
                is DfuReply.Simple -> if (r.command == DfuCommands.START && !r.ok) {
                    throw DfuException(r.code, "Transfer aborted: ${DfuCode.describe(r.code)}")
                }
                else -> Unit
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** Sends D5/D6, polling D1 while the device reports [busyState], until the final reply arrives. */
    private suspend fun awaitFinal(command: Int, busyState: Int) {
        link.writeCommand(byteArrayOf(command.toByte()))
        link.writeCommand(byteArrayOf(DfuCommands.QUERY_STATUS.toByte()))
        var waited = 0L
        while (true) {
            val r = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                var x: DfuReply
                do { x = inbox.receive() } while (x.command != command && x.command != DfuCommands.QUERY_STATUS)
                x
            }
            when {
                r == null -> throw DfuException(
                    -6,
                    "No reply to %02X. The strap may be rebooting, so reconnect before retrying.".format(java.util.Locale.ROOT, command),
                )
                r.command == command -> {
                    if (!r.ok) throw DfuException(r.code, "Step %02X failed: %s".format(command, DfuCode.describe(r.code)))
                    return
                }
                r is DfuReply.Status && r.state == busyState -> {
                    waited += 1
                    delay(1_000)
                    link.writeCommand(byteArrayOf(DfuCommands.QUERY_STATUS.toByte()))
                }
            }
            if (waited > 600) throw DfuException(10, "Step %02X took too long".format(command))
        }
    }
}
