package org.openstrap.protocol.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.protocol.hexToBytes
import java.time.ZoneId
import java.time.ZonedDateTime

class ServicesTest {
    @Test
    fun parsesServiceList() {
        val list = ServiceList.parse("04 0300 2900 00 4700 01 8200 00".hexToBytes())
        assertTrue(list.has(Endpoints.BATTERY))
        assertTrue(list.requiresEncryption(Endpoints.TIME))
        assertFalse(list.requiresEncryption(Endpoints.AUTH))
    }

    @Test
    fun parsesChannelVersion() {
        assertEquals(ChannelVersion(2, true), ChannelVersion.parse("020201".hexToBytes()))
        assertEquals(ChannelVersion(2, null), ChannelVersion.parse("0202".hexToBytes()))
    }

    @Test
    fun parsesBattery() {
        assertEquals(BatteryInfo(83, true), BatteryInfo.parseChannel("04 01 53 01".hexToBytes()))
        assertEquals(BatteryInfo(40, null), BatteryInfo.parse("00 28".hexToBytes()))
    }

    @Test
    fun encodesTimeStruct() {
        // 2026-10-16 09:30:15 in Berlin (UTC+2, DST): offset 8 quarter-hours, DST flag 0x08, Friday = 5.
        val t = ZonedDateTime.of(2026, 10, 16, 9, 30, 15, 0, ZoneId.of("Europe/Berlin"))
        assertArrayEquals("ea07 0a 10 09 1e 0f 05 00 08 08".hexToBytes(), TimeSync.struct(t))
    }

    @Test
    fun encodesNextDstChange() {
        // The codemap example: Berlin leaves DST on 2026-10-25 01:00 UTC; Zepp sends that minus an hour, delta -3600.
        val t = ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        assertArrayEquals("07 8046dd6a f0f1".hexToBytes(), TimeSync.nextDstRequest(t))
        // Entering DST: 2027-03-28 01:00 UTC, +3600, unadjusted.
        val w = ZonedDateTime.of(2026, 12, 1, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        assertArrayEquals(("07" + "%08x".format(Integer.reverseBytes(1806195600)) + "100e").hexToBytes(), TimeSync.nextDstRequest(w))
        // No DST.
        assertArrayEquals("07 00000000 0000".hexToBytes(), TimeSync.nextDstRequest(t.withZoneSameInstant(ZoneId.of("Asia/Dubai"))))
        // Moscow no longer uses DST, so its 2011 change of standard offset is not sent as a DST hint.
        val moscow = ZonedDateTime.of(2011, 1, 10, 12, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        assertArrayEquals("07 00000000 0000".hexToBytes(), TimeSync.nextDstRequest(moscow))
        assertEquals(2, TimeSync.parseVersion("0202".hexToBytes()))
    }

    @Test
    fun encodesUserProfile() {
        val p = UserProfile(1990, 4, 2, Gender.MALE, 180, 75f)
        val legacy = p.legacyPayload()!!
        assertEquals(16, legacy.size)
        assertEquals(0x4F, legacy[0].toInt())
        assertEquals(75 * 200, (legacy[10].toInt() and 0xFF) or ((legacy[11].toInt() and 0xFF) shl 8))
        assertNull(UserProfile(birthYear = 1990).legacyPayload())
    }

    @Test
    fun encodesChannelUserProfile() {
        // The codemap example without the wear-location byte: female, 1990-05-17, 168 cm, 61.5 kg (03 §3.5).
        val p = UserProfile(1990, 5, 17, Gender.FEMALE, 168, 61.5f)
        assertArrayEquals("01 0f000000 c607 05 11 01 a800 0c30".hexToBytes(), p.channelPayload())
        // Unset fields are masked out: only height and weight here.
        assertArrayEquals("01 0c000000 a800 0c30".hexToBytes(), UserProfile(heightCm = 168, weightKg = 61.5f).channelPayload())
        assertNull(UserProfile().channelPayload())
    }

    @Test
    fun appliesServiceChangePush() {
        val list = ServiceList(mapOf(0x0029 to false))
        val added = list.applyChange("05 01 1900 01".hexToBytes())!!
        assertTrue(added.has(0x0019))
        assertTrue(added.requiresEncryption(0x0019))
        assertFalse(added.applyChange("05 02 1900 00".hexToBytes())!!.has(0x0019))
        assertNull(list.applyChange("04 0100 2900 00".hexToBytes()))
    }

    @Test
    fun parsesHealthAlert() {
        // The codemap example: high-HR alert, 160 bpm (03 §3.10).
        assertEquals(
            HealthAlert(HealthAlert.Type.HIGH_HEART_RATE, 0x6A0E6B80, 160),
            HealthAlert.parse("0D 01 806B0E6A A0000000".hexToBytes()),
        )
        assertEquals(HealthAlert.Type.LOW_SPO2, HealthAlert.parse("0D 04 806B0E6A 55000000".hexToBytes())!!.type)
        assertNull(HealthAlert.parse("0D 09 806B0E6A A0000000".hexToBytes())) // unknown type
        assertNull(HealthAlert.parse("05 02".hexToBytes()))
        assertNull(HealthAlert.parse("0D 01 806B".hexToBytes()))
    }

    @Test
    fun parsesHeartRate() {
        assertEquals(72, LiveHeartRate.parsePush("05 4800".hexToBytes()))
        assertEquals(64, LiveHeartRate.parseMeasurement("00 40".hexToBytes()))
        assertEquals(300 - 250, (LiveHeartRate.parseMeasurement("01 2c01".hexToBytes()) ?: 50))
    }

    @Test
    fun measurementContactBits() {
        org.junit.Assert.assertFalse(LiveHeartRate.measurementOffBody("00 40".hexToBytes())) // contact not supported
        org.junit.Assert.assertFalse(LiveHeartRate.measurementOffBody("06 40".hexToBytes())) // supported, in contact
        org.junit.Assert.assertTrue(LiveHeartRate.measurementOffBody("04 40".hexToBytes())) // supported, no contact
        org.junit.Assert.assertFalse(LiveHeartRate.measurementOffBody(ByteArray(0)))
    }
}

class RealtimeStepsTest {
    @org.junit.Test
    fun stepCountThatContradictsDistanceIsImplausible() {
        // Real reply after the 3.25.0.3 update: 8969 steps but 42 m and 5 kcal.
        val after = RealtimeSteps.parseMessage("04010c092300002a00000005000000".hexToBytes())!!
        org.junit.Assert.assertEquals(8969, after.steps)
        org.junit.Assert.assertFalse(after.isPlausible)
        org.junit.Assert.assertTrue(StepTotals(57, 42, 5).isPlausible)
        org.junit.Assert.assertTrue(StepTotals(8969, 6200, 300).isPlausible)
        org.junit.Assert.assertTrue(StepTotals(8969, null, null).isPlausible)
    }

    @org.junit.Test
    fun parsesQueryReplyAndPush() {
        // flags 0x0C: steps 1234, distance 900 m, 45 kcal.
        val reply = "04 01 0c d2040000 84030000 2d000000".replace(" ", "").hexToBytes()
        org.junit.Assert.assertEquals(StepTotals(1234, 900, 45), RealtimeSteps.parseMessage(reply))
        // flags 0x01 (running u32 present) without distance.
        val push = "03 01 64000000 05000000".replace(" ", "").hexToBytes()
        org.junit.Assert.assertEquals(StepTotals(100, null, null), RealtimeSteps.parseMessage(push))
        org.junit.Assert.assertNull(RealtimeSteps.parseMessage("0601".hexToBytes()))
        org.junit.Assert.assertNull(RealtimeSteps.parseMessage("0405".hexToBytes()))
    }
}
