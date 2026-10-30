package org.openstrap.protocol.history

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.openstrap.protocol.crypto.crc32
import org.openstrap.protocol.toHex
import java.time.ZoneId

/** The strap refused a START with status 51: it is in fast-recharge mode and serves no data (04 §1.5, §3.16). */
class FastRechargeModeException : Exception("The strap is in fast-charge mode")

/**
 * A GDSP command got no reply in time. A plain exception, not `withTimeout`'s cancellation, so one slow reply
 * fails that type instead of cancelling the whole sync.
 */
class GdspTimeoutException(op: Int) : Exception("no reply to GDSP command %02x".format(op))

/** What one START + TRANSFER produced. */
sealed interface GdspFetch {
    /** Nothing newer than the cursor (already acknowledged). */
    data object Empty : GdspFetch

    /** The strap refused the START. */
    data class Refused(val status: GdspStatus) : GdspFetch

    /** The transfer broke (already acknowledged with `03 00`). */
    data class Failed(val reason: String) : GdspFetch

    /**
     * One chunk, decompressed. The caller must close it with [GdspClient.finish]. [sizeField] is the START size,
     * which counts records for fixed-size types and bytes otherwise.
     */
    class Chunk(val payload: ByteArray, val headerTime: Long, val sizeField: Long) : GdspFetch {
        /** Bytes per unit of [sizeField]: 1 when it counts bytes, null when the payload doesn't fit it. */
        val unit: Int?
            get() = when {
                payload.size.toLong() == sizeField -> 1
                sizeField > 0 && payload.size % sizeField == 0L -> (payload.size / sizeField).toInt()
                else -> null
            }
    }
}

/** One workout as Zepp's `xyk` fetches it: the summary, then its detail from the same cursor. */
sealed interface WorkoutFetch {
    data object None : WorkoutFetch
    data class Failed(val reason: String) : WorkoutFetch
    /** [retryDetail] is set when the detail transfer broke (worth fetching again), not when it was empty or unreadable. */
    data class Workout(val summary: WorkoutSummary, val detail: WorkoutDetail?, val nextCursor: Long, val retryDetail: Boolean = false) : WorkoutFetch
}

/**
 * The phone side of a GDSP fetch (04 §1). [send] writes a control command (channel 0x004B or HUAMI(0004));
 * [control] carries decoded control messages and [data] the indexed packets from HUAMI(0005).
 * Data-available pushes that arrive while waiting go to [onDataAvailable].
 */
class GdspClient(
    private val send: suspend (ByteArray) -> Unit,
    private val control: ReceiveChannel<GdspMessage>,
    private val data: ReceiveChannel<ByteArray>,
    private val log: (String) -> Unit = {},
    private val onDataAvailable: (GdspMessage.DataAvailable) -> Unit = { log("history: device has new data of type ${it.typeCode}") },
) {
    /** Sends a command and waits for its `10 <op>` response. Throws [GdspTimeoutException] if none comes. */
    suspend fun command(bytes: ByteArray, timeoutMs: Long = 10_000): GdspMessage.Response {
        val op = bytes[0].toInt() and 0xFF
        send(bytes)
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                when (val m = control.receive()) {
                    is GdspMessage.Response -> if (m.opcode == op) return@withTimeoutOrNull m
                    is GdspMessage.DataAvailable -> onDataAvailable(m)
                    else -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: throw GdspTimeoutException(op)
    }

    suspend fun queryVersion(): GdspMessage.VersionReply? =
        runCatching { command(FetchCodec.queryVersion()) }.getOrNull() as? GdspMessage.VersionReply

    /**
     * START [typeCode] from [since], then TRANSFER, CRC check and inflate. Throws [FastRechargeModeException]
     * on status 51 so the caller can stop the whole sync, as Zepp does.
     */
    suspend fun fetch(typeCode: Int, since: Long, zone: ZoneId): GdspFetch {
        var start = startOrNull(typeCode, since, zone) ?: return failed("no reply to START", GdspStatus.TIMEOUT)
        if (start.status == GdspStatus.INVALID_STATE) {
            // An earlier START is still open on the strap (04 §1.5). Close it and try once more.
            log("history: start $typeCode refused with INVALID_STATE, closing the open fetch and retrying")
            finish(GdspStatus.CANCELLED_BY_APP)
            start = startOrNull(typeCode, since, zone) ?: return failed("no reply to START", GdspStatus.TIMEOUT)
        }
        if (start !is GdspMessage.StartReply || !start.isSuccess) {
            if (start.status == GdspStatus.FAST_RECHARGE_MODE) throw FastRechargeModeException()
            return GdspFetch.Refused(start.status)
        }
        // Every START must be closed with an ACK, even an empty one; otherwise the strap keeps the type
        // open and refuses other types with INVALID_STATE (seen on hardware).
        if (start.size == 0L) {
            finish()
            return GdspFetch.Empty
        }
        val headerTime = start.firstRecordEpoch ?: since
        val (wire, deviceCrc) = transfer() ?: return failed("transfer failed", GdspStatus.TIMEOUT)
        if (deviceCrc != null && deviceCrc != crc32(wire)) return failed("CRC mismatch", GdspStatus.CRC_MISMATCH)
        // Some types (SEC_HR_V2) arrive zlib-compressed; the size field is then the inflated byte count.
        val payload = if (start.isCompressed ?: (wire.size.toLong() != start.size && isZlib(wire))) {
            runCatching { inflate(wire) }.getOrElse { return failed("inflate failed: $it", GdspStatus.DECOMPRESS_FAILED) }
        } else wire
        return GdspFetch.Chunk(payload, headerTime, start.size)
    }

    /** START, or null when the strap doesn't answer (the START may still be open, so the caller ACKs). */
    private suspend fun startOrNull(typeCode: Int, since: Long, zone: ZoneId): GdspMessage.Response? =
        try {
            command(FetchCodec.startFetch(typeCode, GdspTime.of(since, zone)))
        } catch (e: GdspTimeoutException) {
            null
        }

    private suspend fun failed(reason: String, status: GdspStatus): GdspFetch {
        finish(status)
        return GdspFetch.Failed(reason)
    }

    /**
     * One workout (04 §3.16, `xyk.java:60-150`): START(5, cursor) for the summary, then START(6, same cursor) for its detail. The next cursor is the summary's time + 1 s.
     */
    suspend fun fetchWorkout(since: Long, zone: ZoneId): WorkoutFetch {
        val s = fetch(HistoryDataType.SPORT_SUMMARY.code, since, zone)
        if (s is GdspFetch.Failed) return WorkoutFetch.Failed("summary: ${s.reason}")
        if (s !is GdspFetch.Chunk) return WorkoutFetch.None
        // Zepp requires bytes == size; accept a whole multiple too, as the other types do.
        if (s.unit == null) {
            finish(GdspStatus.DATA_SIZE_MISMATCH)
            return WorkoutFetch.Failed("summary: ${s.payload.size} bytes for size ${s.sizeField}")
        }
        val parsed = WorkoutParsers.summary.parse(s.payload, ParseContext(s.headerTime))
        if (parsed !is ParseResult.Success) {
            finish(GdspStatus.CONTENT_PARSE_ERROR)
            return WorkoutFetch.Failed("summary: ${(parsed as ParseResult.Failure).reason}")
        }
        // The summary has to be closed before the next START, or the strap answers INVALID_STATE.
        finish()
        val summary = parsed.records.singleOrNull() ?: return WorkoutFetch.None
        val next = parsed.nextCursor ?: (summary.trackId + 1)

        var retry = false
        val detail = when (val d = fetch(HistoryDataType.SPORT_DETAIL.code, since, zone)) {
            is GdspFetch.Chunk -> when (val r = WorkoutParsers.detail.parse(d.payload, ParseContext(summary.trackId))) {
                is ParseResult.Success -> { finish(); r.records.singleOrNull() }
                is ParseResult.Failure -> { finish(GdspStatus.CONTENT_PARSE_ERROR); log("history 6: ${r.reason}"); null }
            }
            is GdspFetch.Failed -> { log("history 6: ${d.reason}"); retry = true; null }
            is GdspFetch.Refused -> { log("history 6: start refused (${d.status})"); retry = true; null }
            GdspFetch.Empty -> null
        }
        return WorkoutFetch.Workout(summary, detail, next, retry)
    }

    /**
     * Closes the current fetch with `03 <status>`: 1 after data we can use, otherwise the failure's own code, as
     * Zepp does (04 §1.6 step 7). The bare `03` was ignored by the strap (it then timed out with status 0x32).
     */
    suspend fun finish(status: GdspStatus = GdspStatus.SUCCESS) {
        send(FetchCodec.ack(status.code))
        val reply = withTimeoutOrNull(5_000) {
            while (true) {
                val m = control.receive()
                if (m is GdspMessage.Response && (m.opcode == 0x03 || m.opcode == 0x02)) return@withTimeoutOrNull m
                if (m is GdspMessage.DataAvailable) onDataAvailable(m)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        log("history: ack ${status.code} -> ${reply?.let { "%02x %s".format(it.opcode, it.status) } ?: "no reply"}")
    }

    /** TRANSFER, then collect indexed packets until `10 02`. Returns the payload without index bytes and the CRC. */
    suspend fun transfer(): Pair<ByteArray, Long?>? {
        while (data.tryReceive().isSuccess) {} // drop stale packets
        val out = java.io.ByteArrayOutputStream()
        var lastIndex = -1
        send(FetchCodec.transfer())
        var complete: GdspMessage.TransferComplete? = null
        while (complete == null) {
            val got = withTimeoutOrNull(NO_DATA_TIMEOUT_MS) {
                select<Any> {
                    data.onReceive { it }
                    control.onReceive { it }
                }
            } ?: run { log("history: no data for 20 s"); return null }
            when (got) {
                is ByteArray -> if (got.isNotEmpty()) {
                    val index = got[0].toInt() and 0xFF
                    if (lastIndex >= 0 && index != (lastIndex + 1) and 0xFF) log("history: packet gap $lastIndex -> $index")
                    lastIndex = index
                    out.write(got, 1, got.size - 1)
                }
                is GdspMessage.TransferComplete -> complete = got
                is GdspMessage.DataAvailable -> onDataAvailable(got)
                is GdspMessage.Malformed -> log("history: unexpected during transfer: ${got.reason} ${got.raw.toHex()}")
                else -> log("history: unexpected during transfer: $got")
            }
        }
        // Packets can trail the end marker slightly; drain briefly.
        withTimeoutOrNull(300) {
            while (true) data.receive().let { if (it.isNotEmpty()) out.write(it, 1, it.size - 1) }
        }
        if (!complete.isSuccess) {
            log("history: transfer ended with ${complete.status}")
            return null
        }
        return out.toByteArray() to complete.crc32
    }

    private companion object {
        const val NO_DATA_TIMEOUT_MS = 20_000L
    }
}

internal fun isZlib(b: ByteArray): Boolean =
    b.size >= 2 && (b[0].toInt() and 0x0F) == 8 && ((b[0].toInt() and 0xFF) * 256 + (b[1].toInt() and 0xFF)) % 31 == 0

internal fun inflate(b: ByteArray): ByteArray {
    val inflater = java.util.zip.Inflater()
    try {
        inflater.setInput(b)
        val out = java.io.ByteArrayOutputStream(b.size * 8)
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) error("truncated zlib stream")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
