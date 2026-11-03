package org.openstrap.protocol.history

import org.openstrap.protocol.ByteReader

/**
 * Inputs a parser may need beyond the payload.
 *
 * @param headerTime epoch seconds of the START reply time (first record of the chunk).
 * @param activityRecordSize band feature parameter key 0; values <= 4 (or absent) mean 4.
 * @param spo2Versioned band feature-set 2 bit 25: SpO2 payloads start with a version byte. Null when the
 *   feature bit hasn't been read; the parser then looks at the payload itself.
 */
data class ParseContext(
    val headerTime: Long,
    val activityRecordSize: Int = 4,
    val spo2Versioned: Boolean? = null,
)

sealed class ParseResult<out T : HistoryRecord> {
    /**
     * @param nextCursor epoch seconds for the next START, or null when no records were produced.
     * @param droppedBytes trailing bytes that did not form a whole record.
     */
    data class Success<out T : HistoryRecord>(
        val records: List<T>,
        val nextCursor: Long?,
        val droppedBytes: Int = 0,
    ) : ParseResult<T>()

    /** Whole chunk rejected (the app discards the chunk on any record error). */
    data class Failure(val reason: String) : ParseResult<Nothing>()
}

fun interface HistoryParser<out T : HistoryRecord> {
    fun parse(payload: ByteArray, ctx: ParseContext): ParseResult<T>
}

// ---------- helpers ----------

private fun ByteReader.s16(): Int = u16().toShort().toInt()
private fun ByteReader.s32(): Int = u32().toInt()
private fun ByteReader.f32(): Float = Float.fromBits(u32().toInt())

// The byte reader reports a short record as an IndexOutOfBoundsException, so that is what a truncated record is.
@Suppress("TooGenericExceptionCaught")
private inline fun <T : HistoryRecord> guarded(block: () -> ParseResult<T>): ParseResult<T> = try {
    block()
} catch (e: IndexOutOfBoundsException) {
    ParseResult.Failure("truncated payload: ${e.message}")
} catch (e: IllegalArgumentException) {
    ParseResult.Failure("invalid payload: ${e.message}")
}

private fun <T : HistoryRecord> lastPlusOne(records: List<T>): Long? = records.maxOfOrNull { it.timestamp }?.plus(1)

/**
 * Fixed-size records back to back. If [strict], a length that is not a multiple of [size] rejects
 * the whole payload; otherwise a trailing partial record is dropped (02 §3 general rules).
 */
private fun <T : HistoryRecord> fixed(
    payload: ByteArray,
    size: Int,
    strict: Boolean,
    offset: Int = 0,
    next: (List<T>) -> Long? = ::lastPlusOne,
    read: (ByteReader, Int) -> T,
): ParseResult<T> = guarded {
    val body = payload.size - offset
    if (body < 0) return@guarded ParseResult.Failure("payload shorter than header")
    val rem = body % size
    if (strict && rem != 0) return@guarded ParseResult.Failure("length $body not a multiple of $size")
    val r = ByteReader(payload, offset)
    val out = ArrayList<T>(body / size)
    var i = 0
    while (r.remaining >= size) {
        val start = r.pos
        out += read(r, i++)
        r.pos = start + size
    }
    ParseResult.Success(out, next(out), rem)
}

private fun versionOf(payload: ByteArray, ctx: ParseContext): Pair<Int?, Int>? =
    if (!isSpo2Versioned(payload, ctx)) null to 0
    else if (payload.isEmpty()) null
    else (payload[0].toInt() and 0xFF) to 1

/**
 * Without the feature bit, a payload is versioned when byte 0 is a known version and the first record's
 * time sits right after it. On hardware (Helio Strap 3.20) type 37 arrives as `02` + 65-byte records.
 */
private fun isSpo2Versioned(payload: ByteArray, ctx: ParseContext): Boolean {
    ctx.spo2Versioned?.let { return it }
    if (payload.size < 5 || (payload[0].toInt() and 0xFF) !in 1..2) return false
    fun near(at: Int) = kotlin.math.abs(ByteReader(payload, at).u32() - ctx.headerTime) <= NEAR_HEADER_SECONDS
    return near(1) && !near(0)
}

private const val NEAR_HEADER_SECONDS = 2 * 86_400L

private fun ByteReader.u8List(n: Int): List<Int> = List(n) { u8() }

// ---------- parsers ----------

object HistoryParsers {

    /** Type 1 (§3.1): per-minute activity; 4-byte or >= 8-byte records. */
    val activity = HistoryParser { raw, ctx ->
        val size = if (ctx.activityRecordSize <= 4) 4 else ctx.activityRecordSize
        // Real minutes end at the first all-0xFF record (a slot the strap hasn't written). On hardware the
        // strap sometimes sends its whole buffer: a few real minutes, hours of 0xFF, then weeks of stale
        // slots. Read as data the 0xFF records are 255 steps each and push the cursor into the future.
        // A chunk can also *start* with 0xFF slots (minutes the strap has no record for). Those are skipped
        // and the cursor still moves past them; otherwise every sync re-fetches the same chunk forever.
        val whole = raw.size - raw.size % size
        fun isPadding(at: Int) = (at until at + size).all { raw[it] == 0xFF.toByte() }
        var begin = 0
        while (begin < whole && isPadding(begin)) begin += size
        var end = begin
        while (end < whole && !isPadding(end)) end += size
        val skipped = begin / size
        val first = ctx.headerTime + skipped * 60L
        // Keep any trailing partial record so it's still reported as dropped bytes.
        val payload = if (begin == 0 && end == whole) raw else raw.copyOfRange(begin, end) + raw.copyOfRange(whole, raw.size)
        fixed(payload, size, strict = false, next = { recs ->
            if (recs.isEmpty() && skipped == 0) null else first + recs.size * 60L
        }) { r, i ->
            val mode = r.u8(); val intensity = r.u8(); val steps = r.u8(); val hr = r.u8()
            val extra = if (size >= 8) ActivitySleepExtra(r.u8(), r.u8(), r.u8(), r.u8()) else null
            ActivitySample(first + i * 60L, mode, intensity, steps, hr, extra)
        }
    }

    /** Type 88 (§3.1.3): 12-byte records per minute; rejected if not a multiple of 12. */
    val todayActivityExtended = HistoryParser { payload, ctx ->
        // Cursor: minute series -> header + N minutes (02 §1.6).
        fixed(payload, 12, strict = true, next = { if (it.isEmpty()) null else ctx.headerTime + it.size * 60L }) { r, i ->
            TodayActivityExtended(
                ctx.headerTime + i * 60L, r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(),
                r.u8(), r.u8(), r.u16(),
            )
        }
    }

    /** Type 76 (§3.2): `ts u32, hr u8`. */
    val secondHr = HistoryParser { payload, _ ->
        fixed(payload, 5, strict = false) { r, _ -> SecondHeartRate(r.u32(), r.u8()) }
    }

    /** Type 87 (§3.2): blocks `start_ts u32, count u16, hr[count]`, trailing 0xFF trimmed. */
    val secondHrV2 = HistoryParser { payload, _ ->
        guarded {
            val r = ByteReader(payload)
            val out = ArrayList<SecondHeartRate>()
            var blocksEnd: Long? = null
            while (r.remaining >= 6) {
                val start = r.u32()
                val count = r.u16()
                if (count == 0) continue
                if (r.remaining < count) return@guarded ParseResult.Failure("SEC_HR_V2 block truncated")
                blocksEnd = maxOf(blocksEnd ?: 0L, start + count)
                val hrs = r.bytes(count)
                var n = count
                while (n > 0 && hrs[n - 1] == 0xFF.toByte()) n--
                for (k in 0 until n) out += SecondHeartRate(start + k, hrs[k].toInt() and 0xFF)
            }
            // On hardware a short block sometimes repeats the next block's first second; keep the later value.
            val unique = out.associateBy { it.timestamp }.values.sortedBy { it.timestamp }
            // A chunk of only 0xFF padding still moves the cursor past its blocks, or the type stalls.
            ParseResult.Success(unique, lastPlusOne(unique) ?: blocksEnd, r.remaining)
        }
    }

    /** Type 2 (§3.4): `ts u32, tz i8, hr u8`; rejected if not a multiple of 6. */
    val spotHr = HistoryParser { payload, _ ->
        fixed(payload, 6, strict = true) { r, _ -> SpotHeartRate(r.u32(), r.s8(), r.u8()) }
    }

    const val SLEEP_RESULT_SIZE = 594

    /** Type 72 (§3.3): 594-byte on-device sleep result. */
    val sleepResult = HistoryParser { payload, _ ->
        fixed(payload, SLEEP_RESULT_SIZE, strict = false) { r, _ ->
            val ts = r.u32(); val belong = r.u32(); val version = r.u8()
            val hasMain = r.u8() == 1
            val mainStart = r.s16(); val mainEnd = r.s16(); val onBed = r.s16(); val offBed = r.s16()
            val remSup = r.u8() == 1; val shortSup = r.u8() == 1; val hasShort = r.u8() == 1
            val aveHr = r.u8(); val score = r.u8()
            // Counts are i8; clamp to the fixed array capacity.
            val shortCount = r.s8().coerceIn(0, 10)
            val shorts = List(10) { ShortSleep(r.s16(), r.s16(), r.s16()) }.take(shortCount)
            val mainCount = r.s8().coerceIn(0, 50)
            r.s8() // nap stage count; Zepp ignores it and takes every nap slot with a valid code
            val mainStages = List(50) { SleepStage(r.s16(), r.s16(), r.u8()) }.take(mainCount)
            val shortStages = List(50) { SleepStage(r.s16(), r.s16(), r.u8()) }.filter { it.rawType in SleepStageCodes.VALID }
            SleepResult(
                ts, belong, version, hasMain, mainStart, mainEnd, onBed, offBed, remSup, shortSup, hasShort,
                aveHr, score, shorts, mainStages, shortStages, r.s16(), r.s16(), r.s16(), r.s16(),
            )
        }
    }

    /** Type 37 (§3.5): spot/auto SpO2, bit 7 = isAuto; v2 adds 60 history samples. */
    val spo2Single = HistoryParser { payload, ctx ->
        val (v, off) = versionOf(payload, ctx) ?: return@HistoryParser ParseResult.Success(emptyList(), null)
        when (v) {
            null, 1 -> fixed(payload, 5, strict = false, offset = off) { r, _ ->
                val ts = r.u32(); val b = r.u8()
                SpO2Reading(ts, b and 0x7F, b and 0x80 != 0, null)
            }
            2 -> fixed(payload, 65, strict = false, offset = off) { r, _ ->
                val ts = r.u32(); val b = r.u8()
                SpO2Reading(ts, b and 0x7F, b and 0x80 != 0, r.u8List(60))
            }
            else -> ParseResult.Failure("SPO2 version $v not supported")
        }
    }

    /** Type 38 (§3.5): sleep SpO2 samples. */
    val spo2Sleep = HistoryParser { payload, ctx ->
        val (v, off) = versionOf(payload, ctx) ?: return@HistoryParser ParseResult.Success(emptyList(), null)
        when (v) {
            null, 1 -> fixed(payload, 6, strict = false, offset = off) { r, _ ->
                SleepSpO2Sample(r.u32(), r.u8(), r.u8(), null)
            }
            2 -> fixed(payload, 30, strict = false, offset = off) { r, _ ->
                SleepSpO2Sample(r.u32(), r.u8(), r.u8(), SleepSpO2Extra(r.bytes(6), r.bytes(6), r.bytes(8), r.bytes(4)))
            }
            else -> ParseResult.Failure("SPO2 sleep version $v not supported")
        }
    }

    /** Type 39 (§3.5): OSA events. No version byte or v=2: 485 B; v=1: 5 B. */
    val spo2OsaEvent = HistoryParser { payload, ctx ->
        val (v, off) = versionOf(payload, ctx) ?: return@HistoryParser ParseResult.Success(emptyList(), null)
        when (v) {
            null, 2 -> fixed(payload, 485, strict = false, offset = off) { r, _ ->
                SpO2OsaEvent(r.u32(), r.u8(), r.u8List(240), r.u8List(240))
            }
            1 -> fixed(payload, 5, strict = false, offset = off) { r, _ -> SpO2OsaEvent(r.u32(), r.u8(), null, null) }
            else -> ParseResult.Failure("OSA event version $v not supported")
        }
    }

    /** Type 43 (§3.5): 5-byte `ts, descend`. */
    val spo2OsaEventAlt = HistoryParser { payload, ctx ->
        // UNVERIFIED: 02 §3.5 - whether type 43 carries the version byte; assumed yes when versioned.
        val off = if (isSpo2Versioned(payload, ctx)) 1 else 0
        fixed(payload, 5, strict = false, offset = off) { r, _ -> SpO2OsaEvent(r.u32(), r.u8(), null, null) }
    }

    private const val TEMP_INVALID = 0x7FFF

    const val OSA_RESULT_SIZE = 16 + 240 * 12

    /** Type 74 (§3.5): ODI result, 2896 B per record. */
    val osaResult = HistoryParser { payload, _ ->
        fixed(payload, OSA_RESULT_SIZE, strict = false) { r, _ ->
            val belong = r.u32(); val valid = r.u32(); val refined = r.u32()
            val score = r.u8(); val disp = r.u8(); val ver = r.u8(); val num = r.u8()
            val events = List(240) { OsaDesaturation(r.u32(), r.s32(), r.s32()) }
            OsaResult(belong, valid, refined, score, disp, ver, num, events)
        }
    }

    /** Type 18 (§3.6): `ts u32, stress u8`; rejected if not a multiple of 5. */
    val stressSingle = HistoryParser { payload, _ ->
        fixed(payload, 5, strict = true) { r, _ -> StressSample(r.u32(), r.u8(), allDay = false) }
    }

    /** Type 19 (§3.6): one byte per minute; cursor = header + N min + 1 s. */
    val stressAllDay = HistoryParser { payload, ctx ->
        fixed(payload, 1, strict = false, next = { if (it.isEmpty()) null else ctx.headerTime + it.size * 60L + 1 }) { r, i ->
            StressSample(ctx.headerTime + i * 60L, r.u8(), allDay = true)
        }
    }

    /**
     * Type 46 (§3.10): one 8-byte record per minute from the header time: `env s16, touch s16, reserved[4]`,
     * in 0.01 °C; 0x7FFF = no reading. Verified on hardware against the strap's `HEA_TEMP` log lines.
     */
    val skinTemperature = HistoryParser { payload, ctx ->
        fixed(payload, 8, strict = false, next = { if (it.isEmpty()) null else ctx.headerTime + it.size * 60L + 1 }) { r, i ->
            val env = r.s16(); val touch = r.s16(); r.bytes(4)
            SkinTemperatureSample(ctx.headerTime + i * 60L, touch.takeIf { it != TEMP_INVALID }, env.takeIf { it != TEMP_INVALID })
        }
    }

    /** Type 73 (§3.7): `ts u32, tz i8, rmssd u8`. */
    val hrvRmssd = HistoryParser { payload, _ ->
        fixed(payload, 6, strict = false) { r, _ -> HrvRmssd(r.u32(), r.s8(), r.u8()) }
    }

    /** Type 78 (§3.7): `ts u32, tz i8, sdnn u32` in 0.01 ms (verified on hardware). */
    val hrvSdnn = HistoryParser { payload, _ ->
        fixed(payload, 9, strict = false) { r, _ -> HrvSdnn(r.u32(), r.s8(), r.u32()) }
    }

    /** Type 86 (§3.7): `ts, tz, realtimeRestHr, restHrBaseline, level`. */
    val restHrRealtime = HistoryParser { payload, _ ->
        fixed(payload, 8, strict = false) { r, _ -> RestHrRealtime(r.u32(), r.s8(), r.u8(), r.u8(), r.u8()) }
    }

    /** Type 58 (§3.7): `ts, tz, hr`. */
    val restingHr = HistoryParser { payload, _ ->
        fixed(payload, 6, strict = false) { r, _ -> RestingHeartRate(r.u32(), r.s8(), r.u8()) }
    }

    /** Type 61 (§3.7): `ts, tz, maxHr`. */
    val dailyMaxHr = HistoryParser { payload, _ ->
        fixed(payload, 6, strict = false) { r, _ -> DailyMaxHeartRate(r.u32(), r.s8(), r.u8()) }
    }

    /** Type 56 (§3.8): `ts, tz, breathRate, snr, channel`. */
    val sleepBreathRate = HistoryParser { payload, _ ->
        fixed(payload, 8, strict = false) { r, _ -> SleepBreathRate(r.u32(), r.s8(), r.u8(), r.u8(), r.u8()) }
    }

    /** Type 57 (§3.8): opaque 569-byte events; first five bytes `ts, tz`. */
    val sleepRespiratoryEvent = HistoryParser { payload, _ ->
        fixed(payload, 569, strict = false) { r, _ ->
            val start = r.pos
            val ts = r.u32(); val tz = r.s8()
            r.pos = start
            SleepRespiratoryEvent(ts, tz, r.bytes(569))
        }
    }

    /** Type 70 (§3.11): blocks `trackId u32, count u32, hr[count]`; stops at first truncated block. */
    val recoveryHr = HistoryParser { payload, _ ->
        guarded {
            val r = ByteReader(payload)
            val out = ArrayList<RecoveryHeartRate>()
            while (r.remaining >= 8) {
                val start = r.pos
                val track = r.u32(); val count = r.u32()
                if (count > r.remaining) { r.pos = start; break }
                out += RecoveryHeartRate(track, r.u8List(count.toInt()))
            }
            ParseResult.Success(out, lastPlusOne(out), r.remaining)
        }
    }

    /** Type 4 (§3.10): skip byte 0, then `temp u8, ts u32`. */
    val legacyTemperature = HistoryParser { payload, _ ->
        if (payload.isEmpty()) ParseResult.Success(emptyList(), null)
        else fixed(payload, 5, strict = false, offset = 1) { r, _ ->
            val t = r.u8()
            LegacyTemperature(r.u32(), t)
        }
    }

    /** Type 13 (§3.9): the whole payload is one record. */
    val pai = HistoryParser { payload, _ ->
        guarded {
            if (payload.isEmpty()) return@guarded ParseResult.Success(emptyList(), null)
            val r = ByteReader(payload)
            val v = r.u8()
            val time = r.u32(); val tz = r.u32()
            val scores = List(7) { r.f32() }
            val low = r.f32(); val med = r.f32(); val high = r.f32()
            val lowM = r.u16(); val medM = r.u16(); val highM = r.u16()
            val daily = r.f32(); val total = r.f32()
            val restHr = r.u16(); val maxHr = r.u16(); val index = r.u8(); val type = r.u8()
            val next = if (v >= 2) List(7) { r.f32() } else null
            val gender = if (v >= 4) r.u8() else null
            val age = if (v >= 4) r.u8() else null
            val limits = if (v >= 5) Triple(r.u8(), r.u8(), r.u8()) else null
            val rec = PaiRecord(
                time, v, tz, scores, low, med, high, lowM, medM, highM, daily, total, restHr, maxHr, index, type,
                next, gender, age, limits?.first, limits?.second, limits?.third,
            )
            ParseResult.Success(listOf(rec), time + 1, r.remaining)
        }
    }
}
