package org.openstrap.protocol.services

import org.openstrap.protocol.ByteReader
import org.openstrap.protocol.ByteWriter
import org.openstrap.protocol.bytesOf
import org.openstrap.protocol.u8

/**
 * SportModule 0x0019 (Zepp `kb70`, "SportShow"; docs/protocol/04-workout-session.md): how Zepp tells the strap a
 * workout started on the phone, so the strap runs a session of its own alongside. Every reply is
 * `10 <request opcode> <status> …`, status 1 = OK. **Not verified on hardware yet**: from static analysis only.
 */
object SportSession {
    const val ENDPOINT = 0x0019
    const val REPLY = 0x10

    const val VERSION = 0x01
    const val CONFIG = 0x02
    const val STATE = 0x03
    /** Sent every [KEEP_ALIVE_MS] while the workout is paused. */
    const val PAUSE_KEEP_ALIVE = 0x05
    const val KEEP_ALIVE_MS = 5_000L

    /** Pushed by the strap: a state change it made itself, live data, a status. */
    const val DEVICE_STATE = 0x11
    const val DEVICE_DATA = 0x12
    const val DEVICE_STATUS = 0x13
    const val DEVICE_DATA_2 = 0x14

    /** [DEVICE_STATUS] 0: heart rate is back to normal; Zepp asks "Do you need to end the workout?". */
    const val STATUS_HR_NORMAL = 0

    /** A session heart rate outside this is dropped; 0 means the strap has no reading. */
    private val PLAUSIBLE_HR = 30..240

    enum class State(val code: Int) { START(1), PAUSE(2), RESUME(3), STOP(4), ABANDON(5) }

    /** SportInfo mask bits the phone sends (encoder order matches bit order for these). */
    private const val BIT_START_TIME = 0x2L
    private const val BIT_ELAPSED = 0x4L

    val versionRequest: ByteArray = bytesOf(VERSION)
    val pauseKeepAlive: ByteArray = bytesOf(PAUSE_KEEP_ALIVE)

    /** True for the reply to [request]: `10 <its opcode> …`. */
    fun isReplyTo(request: ByteArray, reply: ByteArray): Boolean =
        reply.size >= 3 && reply.u8(0) == REPLY && reply.u8(1) == request.u8(0)

    fun isOk(reply: ByteArray): Boolean = reply.size >= 3 && reply.u8(2) == 1

    /** The strap's sport module: [version] 1–3, and which optional config bytes it takes. */
    data class Capabilities(val version: Int, val supportsSave: Boolean, val supportsReport: Boolean, val supportsRecoveryHr: Boolean, val configFlag: Long)

    /** `10 01 01 ver save report recHr flag(u32)`; null unless OK with a version Zepp accepts (1–3). Short replies read as 0. */
    fun parseVersion(reply: ByteArray): Capabilities? {
        if (reply.size < 4 || reply.u8(0) != REPLY || reply.u8(1) != VERSION || reply.u8(2) != 1) return null
        fun at(i: Int) = if (i < reply.size) reply.u8(i) else 0
        val version = at(3).takeIf { it in 1..3 } ?: return null
        val flag = if (reply.size >= 11) ByteReader(reply, 7).u32() else 0L
        return Capabilities(version, at(4) == 1 && version >= 2, at(5) == 1, at(6) == 1, flag)
    }

    /**
     * The strap's sport id for an app sport ([sportType], the summary's `Type.type`). Version 3 takes it as is; older
     * modules only know run, treadmill, walk and cycling, mapped as Zepp's `uv60.j()` does.
     */
    fun deviceSportType(sportType: Int, caps: Capabilities): Int = if (caps.version >= 3) sportType.coerceIn(1, 255) else when (sportType) {
        2 -> 2
        4 -> 3
        3 -> 4
        else -> 1
    }

    /**
     * SportConfig `02 <type> 00 [save] [recoveryHr]`: the middle byte is Zepp's always-0 "needDeviceClock"; save and
     * recovery HR are sent only when the module takes them. Zepp asks the strap to keep its own record (save) for
     * version 3 and device types from 4 up, where the phone has no GPS track to keep.
     */
    fun config(sportType: Int, caps: Capabilities, recoveryHr: Boolean = true): ByteArray {
        val type = deviceSportType(sportType, caps)
        val w = ByteWriter().u8(CONFIG).u8(type).u8(0)
        if (caps.supportsSave) w.u8(if (caps.version >= 3 && type >= 4) 1 else 0)
        if (caps.supportsRecoveryHr) w.u8(if (recoveryHr) 1 else 0)
        return w.toByteArray()
    }

    /**
     * State command `03 <state> <SportInfo>`. START carries the start time and elapsed seconds (mask 0x06), and is
     * sent again with the original start after a reconnect; PAUSE, RESUME, STOP and ABANDON carry elapsed only (0x04).
     */
    fun state(state: State, startTime: Long, elapsedSeconds: Long, caps: Capabilities? = null): ByteArray {
        // Version 1 modules don't know ABANDON; Zepp sends STOP.
        val s = if (state == State.ABANDON && caps?.version == 1) State.STOP else state
        val w = ByteWriter().u8(STATE).u8(s.code)
        if (s == State.START) w.u32(BIT_START_TIME or BIT_ELAPSED).u32(startTime).u32(elapsedSeconds)
        else w.u32(BIT_ELAPSED).u32(elapsedSeconds)
        return w.toByteArray()
    }

    /** What the strap pushes on its own during a session. */
    sealed interface Push {
        /** The strap changed the workout's state (e.g. stopped it). */
        data class StateChanged(val state: State) : Push
        /** [DEVICE_STATUS]; [STATUS_HR_NORMAL] means "heart rate back to normal". */
        data class Status(val status: Int) : Push
        /**
         * Live data; only the heart rate is read here (bit 5 of the first mask). [heartRate] is null when the frame
         * has none or it isn't plausible. [noReading] is true when the strap sent 0: it has no reading, e.g. it is off
         * the body (seen on hardware for the first seconds of a session).
         */
        data class Data(val heartRate: Int?, val noReading: Boolean = false) : Push
    }

    fun parsePush(p: ByteArray): Push? {
        if (p.isEmpty()) return null
        return when (p.u8(0)) {
            DEVICE_STATE -> p.takeIf { it.size >= 2 }?.let { s -> State.entries.firstOrNull { it.code == s.u8(1) }?.let(Push::StateChanged) }
            DEVICE_STATUS -> p.takeIf { it.size >= 2 }?.let { Push.Status(it.u8(1)) }
            DEVICE_DATA, DEVICE_DATA_2 -> if (p.size <= 6) null else dataHeartRate(p).let { hr ->
                Push.Data(hr?.takeIf { it in PLAUSIBLE_HR }, noReading = hr == 0)
            }
            else -> null
        }
    }

    /**
     * Heart rate from `12 xx <mask u32> <fields in bit order>`: fields before bit 5 are bit 0 (u32), 1 (u32), 2 (u32)
     * and 3 (u32); bit 5 is a u8. Only the first mask is meaningful (later masks alias it in Zepp's decoder).
     */
    private fun dataHeartRate(p: ByteArray): Int? {
        val r = ByteReader(p, 2)
        val mask = r.u32()
        if (mask and (1L shl 5) == 0L) return null
        for (bit in 0..3) if (mask and (1L shl bit) != 0L) r.u32()
        return r.u8()
    }
}
