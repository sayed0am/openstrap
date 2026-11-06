package org.openstrap.protocol.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.protocol.hexToBytes
import org.openstrap.protocol.services.SportSession.Push
import org.openstrap.protocol.services.SportSession.State

class SportSessionTest {
    private val v3 = SportSession.Capabilities(3, supportsSave = true, supportsReport = false, supportsRecoveryHr = true, configFlag = 0)

    @Test
    fun versionReply() {
        val caps = SportSession.parseVersion("10 01 01 03 01 00 01 00000000".hexToBytes())!!
        assertEquals(v3, caps)
        // A version Zepp refuses, or a failed status.
        assertNull(SportSession.parseVersion("10 01 01 04 01 00 01".hexToBytes()))
        assertNull(SportSession.parseVersion("10 01 00 03".hexToBytes()))
        // Short replies read missing bytes as 0; save needs version 2+.
        assertEquals(SportSession.Capabilities(1, false, false, false, 0), SportSession.parseVersion("10 01 01 01 01".hexToBytes()))
    }

    @Test
    fun configAsZeppSendsIt() {
        // The codemap's example: outdoor run on a version 3 strap with save and recovery HR.
        assertArrayEquals("02 01 00 00 01".hexToBytes(), SportSession.config(1, v3))
        // Strength (52) asks the strap to keep its own record.
        assertArrayEquals("02 34 00 01 01".hexToBytes(), SportSession.config(52, v3))
        // A version 1 module: no optional bytes, and its own type mapping (walk 3 -> 4).
        val v1 = SportSession.Capabilities(1, false, false, false, 0)
        assertArrayEquals("02 04 00".hexToBytes(), SportSession.config(3, v1))
        assertEquals(1, SportSession.deviceSportType(52, v1))
    }

    @Test
    fun stateCommands() {
        // START carries the start time and elapsed (mask 0x06): the codemap's example 6A0E6B80.
        assertArrayEquals("03 01 06000000 806B0E6A 00000000".hexToBytes(), SportSession.state(State.START, 0x6A0E6B80, 0))
        assertArrayEquals("03 04 04000000 2C010000".hexToBytes(), SportSession.state(State.STOP, 0, 300))
        // ABANDON becomes STOP for a version 1 module.
        val v1 = SportSession.Capabilities(1, false, false, false, 0)
        assertEquals(State.STOP.code, SportSession.state(State.ABANDON, 0, 1, v1)[1].toInt())
    }

    @Test
    fun replies() {
        val req = SportSession.state(State.PAUSE, 0, 10)
        assertTrue(SportSession.isReplyTo(req, "10 03 01".hexToBytes()))
        assertTrue(SportSession.isOk("10 03 01".hexToBytes()))
        assertTrue(!SportSession.isOk("10 03 00".hexToBytes()))
    }

    @Test
    fun pushes() {
        assertEquals(Push.Status(SportSession.STATUS_HR_NORMAL), SportSession.parsePush("13 00".hexToBytes()))
        assertEquals(Push.StateChanged(State.STOP), SportSession.parsePush("11 04".hexToBytes()))
        // The codemap's constructed frame: mask 0x06000020, hr 140.
        assertEquals(Push.Data(140), SportSession.parsePush("12 00 20000006 8C 78 9A".hexToBytes()))
        // With distance (bit 3) before the heart rate.
        assertEquals(Push.Data(150), SportSession.parsePush("12 00 28000000 E8030000 96".hexToBytes()))
        assertNull(SportSession.parsePush("12 00 2000".hexToBytes()))
        // A hardware 14 frame: elapsed 75 s, hr 137, kcal 8, avg 115, max 137.
        assertEquals(Push.Data(137), SportSession.parsePush("14 00 24010006 4b000000 89 08000000 73 89".hexToBytes()))
        // The same with hr 0: the strap has no reading (off the body, or the first seconds of a session).
        assertEquals(Push.Data(null, noReading = true), SportSession.parsePush("14 00 24010006 4b000000 00 08000000 73 89".hexToBytes()))
        // No hr field: neither a reading nor "no reading".
        assertEquals(Push.Data(null), SportSession.parsePush("14 00 04000000 4b000000".hexToBytes()))
    }
}
