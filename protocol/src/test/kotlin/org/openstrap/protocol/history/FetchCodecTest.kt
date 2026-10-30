package org.openstrap.protocol.history

import org.openstrap.protocol.ByteWriter
import org.openstrap.protocol.hexToBytes
import org.openstrap.protocol.toHex
import org.openstrap.protocol.services.DeviceFeatures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.CRC32

class FetchCodecTest {

    @Test
    fun simpleCommands() {
        assertEquals("00", FetchCodec.queryVersion().toHex())
        assertEquals("02", FetchCodec.transfer().toHex())
        assertEquals("03", FetchCodec.ack().toHex())
        assertEquals("0301", FetchCodec.ack(1).toHex())
        assertEquals("05", FetchCodec.queryDataStatus().toHex())
    }

    @Test
    fun startFetchWithDstOffset() {
        // Berlin summer time: UTC+2 -> 8 quarter-hours.
        val t = ZonedDateTime.of(2026, 7, 15, 13, 45, 30, 0, ZoneId.of("Europe/Berlin"))
        val cmd = FetchCodec.startFetch(HistoryDataType.ACTIVITY, t)
        assertEquals("01" + "01" + "ea07" + "07" + "0f" + "0d" + "2d" + "1e" + "08" + "01", cmd.toHex())
    }

    @Test
    fun startFetchWinterAndNegativeAndHalfHourOffsets() {
        val winter = FetchCodec.startFetch(HistoryDataType.SLEEP_RESULT, ZonedDateTime.of(2026, 1, 2, 0, 0, 0, 0, ZoneId.of("Europe/Berlin")), flag = null)
        assertEquals(10, winter.size)
        assertEquals(4, winter[9].toInt())
        assertEquals(72, winter[1].toInt())

        val ny = FetchCodec.startFetch(HistoryDataType.PAI, ZonedDateTime.of(2026, 1, 2, 0, 0, 0, 0, ZoneId.of("America/New_York")))
        assertEquals(-20, ny[9].toInt()) // UTC-5
        val india = FetchCodec.startFetch(HistoryDataType.PAI, ZonedDateTime.of(2026, 1, 2, 0, 0, 0, 0, ZoneId.of("Asia/Kolkata")))
        assertEquals(22, india[9].toInt()) // UTC+5:30
    }

    @Test
    fun startFetchFromEpochMatchesZoned() {
        val zone = ZoneId.of("Europe/Berlin")
        val zdt = ZonedDateTime.of(2026, 3, 29, 3, 30, 0, 0, zone) // right after DST switch
        assertArrayEquals(
            FetchCodec.startFetch(HistoryDataType.ACTIVITY, zdt),
            FetchCodec.startFetch(HistoryDataType.ACTIVITY, zdt.toEpochSecond(), zone),
        )
    }

    @Test
    fun gdspTimeRoundTrip() {
        val zdt = ZonedDateTime.of(2026, 10, 16, 23, 59, 1, 0, ZoneId.of("America/Los_Angeles"))
        val t = GdspTime.of(zdt)
        assertEquals(-28, t.tzQuarters)
        assertEquals(zdt.toEpochSecond(), t.toEpochSecond())
    }

    @Test
    fun parseStartReply() {
        val bytes = ByteWriter().u8(0x10).u8(0x01).u8(0x01).u32(1234)
            .u16(2026).u8(10).u8(16).u8(8).u8(0).u8(0).u8(8).u8(0x01).toByteArray()
        val msg = FetchCodec.parse(bytes) as GdspMessage.StartReply
        assertTrue(msg.isSuccess)
        assertEquals(1234L, msg.size)
        assertEquals(ZonedDateTime.of(2026, 10, 16, 8, 0, 0, 0, ZoneId.of("Europe/Berlin")).toEpochSecond(), msg.firstRecordEpoch)
        assertEquals(1, msg.expansion.size)
        assertEquals(true, msg.isCompressed)
    }

    @Test
    fun parseStartReplyEmptyAndErrors() {
        val empty = "1001 01 00000000 ea07 0a 10 08 00 00 08".hexToBytes()
        val e = FetchCodec.parse(empty) as GdspMessage.StartReply
        assertEquals(0L, e.size)
        assertNull(e.isCompressed) // no expansion

        val busy = FetchCodec.parse("100107".hexToBytes()) as GdspMessage.StartReply
        assertFalse(busy.isSuccess)
        assertEquals(GdspStatus.BUSY, busy.status)
        assertNull(busy.firstRecordTime)

        assertTrue(FetchCodec.parse("10010100".hexToBytes()) is GdspMessage.Malformed)
        assertTrue(FetchCodec.parse("10".hexToBytes()) is GdspMessage.Malformed)
        assertTrue(FetchCodec.parse(ByteArray(0)) is GdspMessage.Malformed)
        assertTrue(FetchCodec.parse("99".hexToBytes()) is GdspMessage.Malformed)
    }

    @Test
    fun parseTransferCompleteAckAndVersion() {
        val done = FetchCodec.parse("100201".hexToBytes()) as GdspMessage.TransferComplete
        assertTrue(done.isSuccess)
        assertNull(done.crc32)
        val withCrc = FetchCodec.parse("100201 78563412".hexToBytes()) as GdspMessage.TransferComplete
        assertEquals(0x12345678L, withCrc.crc32)

        val ack = FetchCodec.parse("100301".hexToBytes()) as GdspMessage.AckReply
        assertTrue(ack.isSuccess)

        val ver = FetchCodec.parse("100001 03 00 0200 0101 4801".hexToBytes()) as GdspMessage.VersionReply
        assertEquals(3, ver.version)
        assertEquals(setOf(1, 0x48), ver.supportedTypes())

        val other = FetchCodec.parse("10050e".hexToBytes()) as GdspMessage.OtherReply
        assertEquals(GdspStatus.DATA_SIZE_MISMATCH, other.status)
        assertEquals(GdspStatus.UNKNOWN, GdspStatus.fromCode(200))
    }

    @Test
    fun parseDataAvailable() {
        val n = FetchCodec.parse("1105".hexToBytes()) as GdspMessage.DataAvailable
        assertEquals(HistoryDataType.SPORT_SUMMARY, n.type)
        assertEquals(listOf(5, 6, 79), n.typesToSync)
        assertEquals(listOf(5, 6, 79), (FetchCodec.parse("1106".hexToBytes()) as GdspMessage.DataAvailable).typesToSync)
        assertEquals(listOf(70), (FetchCodec.parse("1146".hexToBytes()) as GdspMessage.DataAvailable).typesToSync)
        assertEquals(listOf(96), (FetchCodec.parse("1160".hexToBytes()) as GdspMessage.DataAvailable).typesToSync)
        // Zepp ignores pushes for other types.
        assertEquals(emptyList<Int>(), (FetchCodec.parse("1101".hexToBytes()) as GdspMessage.DataAvailable).typesToSync)
        assertTrue(FetchCodec.parse("11".hexToBytes()) is GdspMessage.Malformed)
    }

    // ---- assembler ----

    @Test
    fun assemblerConcatenatesAndWraps() {
        val a = FetchAssembler(6)
        assertEquals(FetchAssembler.State.Collecting, a.accept("ff0102".hexToBytes()))
        assertEquals(FetchAssembler.State.Collecting, a.accept("000304".hexToBytes())) // 255 -> 0 wrap
        assertEquals(FetchAssembler.State.Complete, a.accept("010506".hexToBytes()))
        val r = a.finish() as FetchAssembler.Result.Complete
        assertEquals("010203040506", r.payload.toHex())
        val crc = CRC32().apply { update(r.payload) }.value
        assertEquals(true, a.crcMatches(crc))
        assertEquals(false, a.crcMatches(crc xor 1))
        assertNull(a.crcMatches(null))
    }

    @Test
    fun assemblerDetectsGap() {
        val a = FetchAssembler(10)
        a.accept("0001".hexToBytes())
        val s = a.accept("0202".hexToBytes())
        assertEquals(FetchAssembler.State.Failed(FetchAssembler.Error.IndexGap(1, 2)), s)
        assertTrue(a.finish() is FetchAssembler.Result.Failed)
    }

    @Test
    fun assemblerDetectsShortAndOverflow() {
        val short = FetchAssembler(4)
        short.accept("000102".hexToBytes())
        assertEquals(
            FetchAssembler.Result.Failed(FetchAssembler.Error.SizeMismatch(2, 4)),
            short.finish(),
        )
        val over = FetchAssembler(2)
        assertTrue(over.accept("00010203".hexToBytes()) is FetchAssembler.State.Failed)
        val late = FetchAssembler(1)
        late.accept("0001".hexToBytes())
        assertTrue(late.accept("0102".hexToBytes()) is FetchAssembler.State.Failed)
        assertTrue(FetchAssembler(5).accept(ByteArray(0)) is FetchAssembler.State.Failed)
        assertTrue(FetchAssembler(0).finish() is FetchAssembler.Result.Complete)
    }

    // ---- type table / specs ----

    @Test
    fun dataTypesAndSpecs() {
        assertEquals(HistoryDataType.SLEEP_RESULT, HistoryDataType.fromCode(72))
        assertNull(HistoryDataType.fromCode(250))
        assertTrue(HistoryDataType.ACTIVITY.usedByStrap)
        assertFalse(HistoryDataType.ECG.usedByStrap)
        assertEquals(HistoryDataType.entries.size, HistoryDataType.entries.map { it.code }.toSet().size)

        val all = HistoryTypeSpecs.all
        assertEquals(HistoryDataType.ACTIVITY, all.first().type)
        assertEquals(all.sortedBy { it.syncOrder }, all)
        assertEquals(WorkoutParsers.summary, HistoryTypeSpecs.forCode(5)!!.parser)
        assertEquals(listOf(72, 1).toSet(), HistoryTypeSpecs.plan(setOf(1, 72, 3)).map { it.code }.toSet())
        assertTrue(HistoryTypeSpecs.plan(null).all { it.type.strap == StrapRelevance.EXPECTED })
        // The workout detail is fetched with its summary, never on its own.
        assertEquals(HistoryDataType.SPORT_DETAIL, HistoryTypeSpecs.forCode(5)!!.followedBy)
        assertEquals(setOf(5, 59), HistoryTypeSpecs.plan(setOf(5, 6, 59)).map { it.code }.toSet())
        assertTrue(HistoryTypeSpecs.plan(null).none { it.type == HistoryDataType.SPORT_DETAIL })
        // Legacy temperature is never fetched from the strap; recovery HR only when advertised.
        assertEquals(setOf(1), HistoryTypeSpecs.plan(setOf(1, 4)).map { it.code }.toSet())
        assertTrue(HistoryTypeSpecs.plan(null).none { it.code == 70 })
        assertTrue(HistoryTypeSpecs.plan(setOf(70)).any { it.code == 70 })

        val act = HistoryTypeSpecs.forCode(1)!!
        assertTrue(HistoryTypeSpecs.shouldContinue(act, 100, 101))
        assertFalse(HistoryTypeSpecs.shouldContinue(act, 100, 100))
        assertFalse(HistoryTypeSpecs.shouldContinue(act, 100, null))
        assertFalse(HistoryTypeSpecs.shouldContinue(HistoryTypeSpecs.forCode(18)!!, 100, 200))
    }
}

class ActivityLayoutTest {
    @org.junit.Test
    fun detectsRecordSize() {
        // Not a whole number of 8-byte records: must be 4-byte.
        org.junit.Assert.assertEquals(4, ActivityLayout.detect(ByteArray(12) { 1 }))
        // Byte 4 (0x3A, low nibble 10) is no mode, so these only read as 8-byte records.
        val four = ByteArray(64) { i -> when (i % 8) { 0 -> 1; 4 -> 0x3A; else -> 40 }.toByte() }
        org.junit.Assert.assertEquals(8, ActivityLayout.detect(four))
        // 8-byte records whose bytes 4..7 (retain, posture, deep, rem) would be invalid modes.
        val eight = ByteArray(64) { i -> when (i % 8) { 0 -> 5; 1 -> 3; 2 -> 0; 3 -> 55; 4 -> 200; 5 -> 180; 6 -> 90; else -> 140 }.toByte() }
        org.junit.Assert.assertEquals(8, ActivityLayout.detect(eight))
        // Real Helio Strap minutes: mode byte 0x70/0x76 (state in the high nibble), byte 4 = 5, then 0xFF padding.
        val strap = w8("7603 00ff 0500 0000 7047 0051 0500 0000 7038 0857 0500 0000 ffff ffff ffff ffff")
        org.junit.Assert.assertEquals(8, ActivityLayout.detect(strap))
    }

    private fun w8(hex: String) = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

class VersionReplyTest {
    @org.junit.Test
    fun parsesSupportedTypesCapturedFromStrap() {
        // Real reply payload from a Helio Strap (fw 3.20.0.1), truncated to three types.
        val reply = "10 00 01 03 00 0300 0701 0101 4801".hexToBytes()
        val v = FetchCodec.parse(reply) as GdspMessage.VersionReply
        org.junit.Assert.assertEquals(setOf(7, 1, 0x48), v.supportedTypes())
    }

    @org.junit.Test
    fun acceptsTrailingQueryDataStatusByte() {
        val reply = "10 00 01 03 00 0300 0701 0101 4801 01".hexToBytes()
        val v = FetchCodec.parse(reply) as GdspMessage.VersionReply
        org.junit.Assert.assertEquals(setOf(7, 1, 0x48), v.supportedTypes())
    }

    @org.junit.Test
    fun rejectsTruncatedTypeList() {
        val reply = "10 00 01 03 00 0300 0701 0101".hexToBytes()
        org.junit.Assert.assertNull((FetchCodec.parse(reply) as GdspMessage.VersionReply).supportedTypes())
    }
}

class ActivityPaddingTest {
    @org.junit.Test
    fun dropsTrailingFfRecords() {
        val payload = "702300500d00000070430a5005000000ffffffffffffffffffffffffffffffff".hexToBytes()
        val r = HistoryParsers.activity.parse(payload, ParseContext(1000, activityRecordSize = 8)) as ParseResult.Success
        org.junit.Assert.assertEquals(2, r.records.size)
        org.junit.Assert.assertEquals(listOf(0, 10), r.records.map { it.steps })
        org.junit.Assert.assertEquals(1000 + 120L, r.nextCursor)
    }

    @org.junit.Test
    fun stopsAtFirstFfRecordEvenWithStaleSlotsAfter() {
        // Captured shape: real minutes, unwritten 0xFF slots, then stale non-0xFF slots.
        val payload = ("603300500500000060431a5207000000" + "ff".repeat(16) + "7300000005000000".repeat(3)).hexToBytes()
        val r = HistoryParsers.activity.parse(payload, ParseContext(1000, activityRecordSize = 8)) as ParseResult.Success
        org.junit.Assert.assertEquals(listOf(0, 26), r.records.map { it.steps })
        org.junit.Assert.assertEquals(1000 + 120L, r.nextCursor)
    }

    @org.junit.Test
    fun skipsLeadingFfRecordsAndAdvancesCursor() {
        // Captured on hardware: the chunk opens with a 0xFF slot. Stopping there stalled activity sync for good.
        val payload = ("ff".repeat(8) + "5016004705000000" + "ff".repeat(8) + "500e004605000000").hexToBytes()
        val r = HistoryParsers.activity.parse(payload, ParseContext(1000, activityRecordSize = 8)) as ParseResult.Success
        org.junit.Assert.assertEquals(listOf(1060L), r.records.map { it.timestamp })
        org.junit.Assert.assertEquals(1000 + 120L, r.nextCursor)
    }

    @org.junit.Test
    fun allFfChunkStillAdvancesCursor() {
        val r = HistoryParsers.activity.parse("ff".repeat(24).hexToBytes(), ParseContext(1000, activityRecordSize = 8)) as ParseResult.Success
        org.junit.Assert.assertTrue(r.records.isEmpty())
        org.junit.Assert.assertEquals(1000 + 180L, r.nextCursor)
    }
}

class FeatureGatedPlanTest {
    private fun features(vararg g2Bits: Int, alg: Int? = null): DeviceFeatures {
        val g2 = ByteArray(10)
        for (b in g2Bits) g2[b / 8] = (g2[b / 8].toInt() or (1 shl (b % 8))).toByte()
        return DeviceFeatures(0, if (alg != null) 0x0A else 0x02, null, g2, null, alg?.let { listOf(it) }, null)
    }

    private fun codes(advertised: Set<Int>?, f: DeviceFeatures?) = HistoryTypeSpecs.plan(advertised, f).map { it.code }.toSet()

    @Test
    fun gatesOnRuntimeBits() {
        val advertised = setOf(1, 38, 70, 72, 73, 74)
        // No bitmap: today's defaults.
        assertEquals(advertised, codes(advertised, null))
        // The advertised list wins over clear bits, which are only logged.
        assertEquals(advertised, codes(advertised, features(21, 68)))
        assertEquals(listOf(70, 72, 73), HistoryTypeSpecs.featureDisagreements(advertised, features(21, 68)).sorted())
        assertTrue(HistoryTypeSpecs.featureDisagreements(advertised, features(21, 62, 65, 66, 68)).isEmpty())
        // No advertised list: a set bit brings in an IF_ADVERTISED type, a clear bit drops an EXPECTED one.
        assertTrue(70 in codes(null, features(62, 65)))
        assertFalse(70 in codes(null, features(65)))
        assertFalse(72 in codes(null, features(62)))
        // Group 2 not reported: defaults again.
        val noG2 = DeviceFeatures(0, 0x01, byteArrayOf(0x10), null, null, null, null)
        assertEquals(advertised, codes(advertised, noG2))
        // NOT_APPLICABLE stays off whatever the bit says (HLD, 63).
        assertFalse(71 in codes(setOf(71), features(63)))
    }

    @Test
    fun recordSizeFromFeatures() {
        val payload = ByteArray(24) { 1 }
        // The size field wins when it divides the payload cleanly.
        assertEquals(8, ActivityLayout.recordSize(payload, 3, features(alg = 4)))
        // Otherwise the feature blob's value, then the guess.
        assertEquals(4, ActivityLayout.recordSize(payload, 0, features(alg = 4)))
        assertEquals(8, ActivityLayout.recordSize(payload, 0, features(alg = 8)))
        assertEquals(ActivityLayout.detect(payload), ActivityLayout.recordSize(payload, 0, features()))
        assertEquals(ActivityLayout.detect(payload), ActivityLayout.recordSize(payload, 0, null))
    }
}
