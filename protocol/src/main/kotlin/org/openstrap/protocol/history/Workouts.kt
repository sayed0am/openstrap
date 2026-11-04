package org.openstrap.protocol.history

import org.openstrap.protocol.ByteReader

/**
 * Type 5: one workout summary (02 §3.12). [timestamp] is the trackId, which is the workout start epoch.
 * Times are seconds; [exerciseTime] excludes pauses. Fields the strap leaves out are null.
 */
data class WorkoutSummary(
    override val timestamp: Long,
    val sportType: Int,
    val subtype: Int?,
    val totalTime: Int?,
    val exerciseTime: Int?,
    val calories: Int?,
    val averageHeartRate: Int?,
    val maxHeartRate: Int?,
    val minHeartRate: Int?,
    val aerobicTrainingEffect: Float?,
    val anaerobicTrainingEffect: Float?,
    val trainingLoad: Int?,
    val vo2max: Int?,
    val steps: Int?,
) : HistoryRecord {
    val trackId: Long get() = timestamp
}

/**
 * Type 6: one workout's detail stream (02 §3.13). [timestamp] is the START header time, which equals the
 * summary's trackId. [heartRate] holds 1 Hz samples as (epoch ms, bpm); the strap keeps recording for
 * [RECOVERY_TAIL_S] after the workout ends, which is what recovery HR is measured from.
 */
data class WorkoutDetail(
    override val timestamp: Long,
    val heartRate: List<Pair<Long, Int>>,
    /** (epoch ms, event code 1..17; names unknown, pause/resume likely). */
    val events: List<Pair<Long, Int>>,
) : HistoryRecord {
    companion object {
        const val RECOVERY_TAIL_S = 180
    }
}

/**
 * Type 59: the strap's Firstbeat-style result after a workout (`SportEffectMessage`, sport_efect.proto).
 * [timestamp] is the workout end. [vo2max] is 0 until the strap has a valid estimate (it needs runs).
 */
data class SportEffect(
    override val timestamp: Long,
    val maximalHeartRate: Int?,
    val epoc: Float?,
    val vo2max: Float?,
    val aerobicTrainingEffect: Float?,
    val anaerobicTrainingEffect: Float?,
    val recoveryTimeMinutes: Int?,
    val trainingLoadWeekly: Float?,
    val trainingLoadMonthly: Float?,
    val optimalLoadMin: Int?,
    val optimalLoadMax: Int?,
    val lastValidVo2max: Int?,
) : HistoryRecord

/** Minimal protobuf reader: only the scalar fields we use; everything else is skipped by wire type. */
internal class Proto(private val b: ByteArray, private var i: Int = 0, private val end: Int = b.size) {
    val ints = HashMap<Int, Long>()
    val floats = HashMap<Int, Float>()
    val messages = HashMap<Int, Proto>()

    init {
        while (i < end) {
            val key = varint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> ints[field] = varint()
                1 -> i += 8
                2 -> {
                    val len = varint().toInt()
                    require(len >= 0 && i + len <= end) { "field $field overruns" }
                    // Sub-messages are parsed lazily below; strings and packed fields just get skipped.
                    runCatching { Proto(b, i, i + len) }.getOrNull()?.let { messages[field] = it }
                    i += len
                }
                5 -> { floats[field] = Float.fromBits(ByteReader(b, i).u32().toInt()); i += 4 }
                else -> throw IllegalArgumentException("wire type ${key and 7} at ${i - 1}")
            }
        }
        require(i == end) { "message overruns" }
    }

    private fun varint(): Long {
        var r = 0L; var shift = 0
        while (true) {
            require(i < end) { "truncated varint" }
            val c = b[i++].toInt() and 0xFF
            r = r or ((c and 0x7F).toLong() shl shift)
            if (c < 0x80) return r
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }

    fun int(field: Int): Int? = ints[field]?.toInt()
}

// The byte reader reports a short record as an IndexOutOfBoundsException, so that is what a truncated record is.
@Suppress("TooGenericExceptionCaught")
object WorkoutParsers {
    private const val PROTOBUF_SUMMARY = 0x8000

    /** Type 5 (§3.12): `u16 version` (0x8000 = protobuf), then `SportSummaryMessage`; one workout per START. */
    val summary = HistoryParser { payload, ctx ->
        try {
            if (payload.size <= 2) return@HistoryParser ParseResult.Success(emptyList(), null)
            val version = ByteReader(payload).u16()
            if (version != PROTOBUF_SUMMARY) return@HistoryParser ParseResult.Failure("summary version $version not supported")
            val m = Proto(payload, 2)
            val type = m.messages[3] ?: return@HistoryParser ParseResult.Failure("summary without sport type")
            val record = m.messages[2]
            val times = m.messages[7]
            val hr = m.messages[19]
            val other = m.messages[21]
            // Zepp uses the START header time as the start (psu.java:100-108); trackId is the same value.
            val start = record?.ints?.get(1)?.takeIf { it > 0 } ?: ctx.headerTime
            val w = WorkoutSummary(
                timestamp = start,
                sportType = type.int(1) ?: return@HistoryParser ParseResult.Failure("summary without sport type"),
                subtype = type.int(2),
                totalTime = times?.int(1),
                exerciseTime = times?.int(2),
                calories = m.messages[16]?.int(1),
                averageHeartRate = hr?.int(1)?.let(HeartRateSentinel::valid),
                maxHeartRate = hr?.int(2)?.let(HeartRateSentinel::valid),
                minHeartRate = hr?.int(3)?.let(HeartRateSentinel::valid),
                aerobicTrainingEffect = other?.floats?.get(4),
                anaerobicTrainingEffect = other?.floats?.get(5),
                trainingLoad = other?.int(6),
                vo2max = other?.int(7)?.takeIf { it > 0 },
                steps = m.messages[11]?.int(4),
            )
            ParseResult.Success(listOf(w), start + 1)
        } catch (e: IllegalArgumentException) {
            ParseResult.Failure("invalid summary: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            ParseResult.Failure("truncated summary: ${e.message}")
        }
    }

    private const val TAG_TIMESTAMP = 1
    private const val TAG_EVENT = 4
    private const val TAG_HEART_RATE = 8

    /**
     * Type 6 (§3.13): TLV nodes. A TimeStamp node (4 bytes, then `u64` epoch ms) sets the base; HeartRate
     * nodes are `offset u16 (ms), bpm u8` from that base; Event nodes `offset u16, code u16`. Other nodes are skipped.
     */
    val detail = HistoryParser { payload, ctx ->
        try {
            val r = ByteReader(payload)
            var base: Long? = null
            val hr = ArrayList<Pair<Long, Int>>()
            val events = ArrayList<Pair<Long, Int>>()
            while (r.remaining > 0) {
                val b0 = r.u8()
                var tag = b0 and 0x1F
                if (tag == 0x1F) {
                    tag = b0; var k = 1
                    while (true) { val c = r.u8(); tag = tag or ((c and 0x7F) shl (8 * k++)); if (c < 0x80) break }
                }
                val l0 = r.u8()
                val len = if (l0 and 0x80 == 0) l0 else (0 until (l0 and 0x7F)).fold(0) { acc, _ -> (acc shl 8) or r.u8() }
                require(len <= r.remaining) { "node $tag overruns" }
                val v = ByteReader(r.bytes(len))
                when (tag) {
                    TAG_TIMESTAMP -> if (len >= 12) { v.bytes(4); base = v.u32() or (v.u32() shl 32) }
                    TAG_HEART_RATE -> if (len >= 3) base?.let { b -> val off = v.u16(); HeartRateSentinel.valid(v.u8())?.let { hr += (b + off) to it } }
                    // The event code is u16 (im70.java:200-214).
                    TAG_EVENT -> if (len >= 4) base?.let { b -> val off = v.u16(); events += (b + off) to v.u16() }
                }
            }
            if (hr.isEmpty() && events.isEmpty()) return@HistoryParser ParseResult.Success(emptyList(), ctx.headerTime + 1)
            ParseResult.Success(listOf(WorkoutDetail(ctx.headerTime, hr, events)), ctx.headerTime + 1)
        } catch (e: IllegalArgumentException) {
            ParseResult.Failure("invalid detail: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            ParseResult.Failure("truncated detail: ${e.message}")
        }
    }

    /** Type 59: a bare `SportEffectMessage`; one per workout. */
    val sportEffect = HistoryParser { payload, ctx ->
        try {
            if (payload.isEmpty()) return@HistoryParser ParseResult.Success(emptyList(), null)
            val m = Proto(payload)
            val end = m.ints[26]?.takeIf { it > 0 } ?: ctx.headerTime
            val e = SportEffect(
                timestamp = end,
                maximalHeartRate = m.int(4)?.let(HeartRateSentinel::valid),
                epoc = m.floats[6],
                vo2max = m.floats[7]?.takeIf { it > 0f },
                aerobicTrainingEffect = m.floats[8],
                anaerobicTrainingEffect = m.floats[9],
                recoveryTimeMinutes = m.int(11),
                trainingLoadWeekly = m.floats[15],
                trainingLoadMonthly = m.floats[16],
                optimalLoadMin = m.int(12),
                optimalLoadMax = m.int(13),
                lastValidVo2max = m.int(21)?.takeIf { it > 0 },
            )
            ParseResult.Success(listOf(e), maxOf(end, ctx.headerTime) + 1)
        } catch (e: IllegalArgumentException) {
            ParseResult.Failure("invalid sport effect: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            ParseResult.Failure("truncated sport effect: ${e.message}")
        }
    }
}
