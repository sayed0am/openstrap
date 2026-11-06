package org.openstrap.protocol.services

import org.openstrap.protocol.ByteReader
import org.openstrap.protocol.ByteWriter
import org.openstrap.protocol.bytesOf
import org.openstrap.protocol.u8
import java.time.ZonedDateTime

/** Channel endpoint ids (docs/protocol/01 §4). */
object Endpoints {
    const val SERVICES = 0x0000
    const val CONNECT = 0x0015
    const val USER_INFO = 0x0017
    const val MOTOR_VIBRATE = 0x0018
    const val FIND_DEVICE = 0x001A
    const val OPEN_APP = 0x001D
    const val REALTIME_ACTIVITY = 0x0016
    const val BATTERY = 0x0029
    const val DEVICE_INFO = 0x0043
    const val TIME = 0x0047
    const val HEART_RATE = 0x0048
    const val HISTORY = 0x004B
    const val FIRMWARE = 0x000C
    const val SETTINGS = 0x000A
    const val ALARMS = 0x000F
    const val READINESS = 0x0049
    const val AUTH = 0x0082
}

/**
 * ConnectModule 0x0015 (02 §11): `01` → `02 n u16` (payload MTU, also pushed when it changes); the strap's
 * `03` ping is answered with a `04` pong.
 */
object ConnectCodec {
    val MTU_QUERY: ByteArray = bytesOf(0x01)
    val PING: ByteArray = bytesOf(0x03)
    val PONG: ByteArray = bytesOf(0x04)
}

/** Supported endpoints and whether each requires encryption (§4). */
data class ServiceList(val endpoints: Map<Int, Boolean>) {
    fun has(endpoint: Int) = endpoint in endpoints
    fun requiresEncryption(endpoint: Int) = endpoints[endpoint] == true

    /**
     * Applies the strap's unsolicited `05 op endpoint u16 requireEncryption` push (02 §6.3): op 1 adds the
     * endpoint, 2 removes it. Returns null for anything else.
     */
    fun applyChange(push: ByteArray): ServiceList? {
        if (push.size < 4 || push.u8(0) != CHANGED) return null
        val endpoint = push.u8(2) or (push.u8(3) shl 8)
        return when (push.u8(1)) {
            1 -> ServiceList(endpoints + (endpoint to (push.size > 4 && push.u8(4) != 0)))
            2 -> ServiceList(endpoints - endpoint)
            else -> null
        }
    }

    companion object {
        val REQUEST: ByteArray = bytesOf(0x03)
        const val CHANGED = 0x05

        /** Parses the v2 reply `04 count(u16) {endpoint u16, requireEncryption bool}*`. */
        fun parse(reply: ByteArray): ServiceList {
            require(reply.isNotEmpty() && reply.u8(0) == 0x04) { "Not a v2 services reply" }
            val r = ByteReader(reply, 1)
            val count = r.u16()
            val map = LinkedHashMap<Int, Boolean>()
            repeat(count) { map[r.u16()] = r.bool() }
            require(r.remaining == 0) { "Trailing bytes in services list" }
            return ServiceList(map)
        }
    }
}

/** Channel version reply on 0016 (§2.1): `02 version [encryptEnable] [supportBT] [featureFlag u16]`. */
data class ChannelVersion(val version: Int, val encryptEnable: Boolean?) {
    companion object {
        val REQUEST: ByteArray = bytesOf(0x01)

        fun parse(reply: ByteArray): ChannelVersion? {
            if (reply.size < 2 || reply.u8(0) != 0x02) return null
            return ChannelVersion(reply.u8(1), if (reply.size >= 3) reply.u8(2) != 0 else null)
        }
    }
}

/** BatteryInfo (§5.1). Only the fields the UI needs. */
data class BatteryInfo(val level: Int, val charging: Boolean?) {
    companion object {
        val GET: ByteArray = bytesOf(0x03)

        /** Parses a raw BatteryInfo (legacy 0006 value, or channel payload after the `04`/`05` byte). */
        fun parse(info: ByteArray): BatteryInfo {
            val flags = info.u8(0)
            val level = info.u8(1)
            val charging = if (flags and 0x01 != 0 && info.size > 2) info.u8(2) in 1..2 else null
            return BatteryInfo(level, charging)
        }

        /** Channel reply or push: `04`/`05` + BatteryInfo. */
        fun parseChannel(payload: ByteArray): BatteryInfo? =
            if (payload.size >= 3 && (payload.u8(0) == 0x04 || payload.u8(0) == 0x05)) parse(payload.copyOfRange(1, payload.size)) else null
    }
}

/** The 11-byte time struct (§5.3). */
object TimeSync {
    fun struct(now: ZonedDateTime): ByteArray {
        val offset = now.offset.totalSeconds
        val dst = now.zone.rules.isDaylightSavings(now.toInstant())
        return ByteWriter()
            .u16(now.year).u8(now.monthValue).u8(now.dayOfMonth)
            .u8(now.hour).u8(now.minute).u8(now.second)
            .u8(now.dayOfWeek.value % 7) // 0 = Sunday
            .u8(now.nano / 1_000_000 / 4)
            .u8(if (dst) 0x08 else 0x00)
            .u8(offset / (15 * 60))
            .toByteArray()
    }

    /** Channel endpoint 0x0047 set request; reply `06 01` on success. */
    fun setRequest(now: ZonedDateTime): ByteArray = bytesOf(0x05) + struct(now)

    const val VERSION = 0x01
    const val NEXT_DST = 0x07
    val versionRequest: ByteArray = bytesOf(VERSION)

    /** `02 version`; the DST hint needs version 2 or later (03 §3.8). */
    fun parseVersion(reply: ByteArray): Int? = if (reply.size >= 2 && reply.u8(0) == VERSION + 1) reply.u8(1) else null

    /**
     * `07 u32 transition, i16 deltaSeconds`: the zone's next DST change after [now], answered `08 status`. Zepp sends
     * the transition minus the delta when DST ends (`b930.java:632-641`), and zeros unless the zone uses DST
     * (`TimeZone.useDaylightTime()`, `b930.java:630`), so a plain change of standard offset is never sent.
     */
    fun nextDstRequest(now: ZonedDateTime): ByteArray {
        val t = now.zone.rules.nextTransition(now.toInstant())
            ?.takeIf { java.util.TimeZone.getTimeZone(now.zone.id).useDaylightTime() }
        val delta = t?.let { it.offsetAfter.totalSeconds - it.offsetBefore.totalSeconds } ?: 0
        val at = t?.let { it.toEpochSecond() + if (delta < 0) delta else 0 } ?: 0L
        return ByteWriter().u8(NEXT_DST).u32(at).u16(delta and 0xFFFF).toByteArray()
    }
}

enum class Gender(val code: Int) { MALE(0), FEMALE(1), UNSPECIFIED(2) }

/** What the strap is told about its wearer. Unset fields are left out of the channel request. */
data class UserProfile(
    val birthYear: Int? = null,
    val birthMonth: Int = 1,
    val birthDay: Int = 1,
    val gender: Gender? = null,
    val heightCm: Int? = null,
    val weightKg: Float? = null,
    val userId: Long = 1,
) {
    private val weightUnits: Int? get() = weightKg?.let { (it * 200).toInt() }

    /** Legacy FEE0/0008 write (§5.5). Null unless every field is set, since the record has no mask. */
    fun legacyPayload(): ByteArray? {
        if (birthYear == null || heightCm == null || weightUnits == null) return null
        return ByteWriter()
            .u8(0x4F).u8(0x00).u8(0x00)
            .u16(birthYear).u8(birthMonth).u8(birthDay)
            .u8((gender ?: Gender.UNSPECIFIED).code)
            .u16(heightCm).u16(weightUnits!!)
            .u32(userId)
            .toByteArray()
    }

    /**
     * Channel 0x0017 set request `01 mask u32` + the set fields in bit order (03 §3.5): birthday (bit 0),
     * gender (1), height cm (2), weight as kg × 200 (3). Null when nothing is set. Reply `02 status`, 1 = OK.
     */
    fun channelPayload(): ByteArray? {
        var mask = 0
        val fields = ByteWriter()
        if (birthYear != null) { mask = mask or 0x01; fields.u16(birthYear).u8(birthMonth).u8(birthDay) }
        if (gender != null) { mask = mask or 0x02; fields.u8(gender.code) }
        if (heightCm != null) { mask = mask or 0x04; fields.u16(heightCm) }
        weightUnits?.let { mask = mask or 0x08; fields.u16(it) }
        if (mask == 0) return null
        return ByteWriter().u8(SET).u32(mask.toLong()).toByteArray() + fields.toByteArray()
    }

    companion object {
        const val SET = 0x01
    }
}

/** Real-time heart rate (§5.6). */
object LiveHeartRate {
    // Endpoint 0x001D (03 §3.10): open 04 02, close 04 01, keep-alive 04 03. On hardware open answers 05 03
    // and keep-alive 05 02.
    val OPEN: ByteArray = bytesOf(0x04, 0x02)
    val CLOSE: ByteArray = bytesOf(0x04, 0x01)
    val KEEP_ALIVE: ByteArray = bytesOf(0x04, 0x03)

    // Endpoint 0x0048.
    val SUBSCRIBE: ByteArray = bytesOf(0x03, 0x01)
    val UNSUBSCRIBE: ByteArray = bytesOf(0x03, 0x00)

    // Legacy 180D/2A39.
    val LEGACY_START: ByteArray = bytesOf(0x15, 0x01, 0x01)
    val LEGACY_STOP: ByteArray = bytesOf(0x15, 0x01, 0x00)
    val LEGACY_KEEP_ALIVE: ByteArray = bytesOf(0x16)

    const val KEEP_ALIVE_MS = 10_000L

    /** Channel push on 0x0048: `05 hr(u16)`. */
    fun parsePush(payload: ByteArray): Int? =
        if (payload.size >= 3 && payload.u8(0) == 0x05) (payload.u8(1) or (payload.u8(2) shl 8)).takeIf { it in 1..250 } else null

    /**
     * True when a 2A37 value says the sensor supports contact detection (flags bit 2) and has no contact (bit 1 clear),
     * i.e. the strap is off the body. Zepp ignores these bits; the BLE spec says the heart rate is then not valid.
     */
    fun measurementOffBody(value: ByteArray): Boolean {
        if (value.isEmpty()) return false
        val flags = value.u8(0)
        return flags and CONTACT_SUPPORTED != 0 && flags and CONTACT_DETECTED == 0
    }

    /** 2A37 flags: bit 2 = contact detection supported, bit 1 = contact detected. */
    private const val CONTACT_SUPPORTED = 0x04
    private const val CONTACT_DETECTED = 0x02

    /** Standard BLE HR measurement (2A37). */
    fun parseMeasurement(value: ByteArray): Int? {
        if (value.size < 2) return null
        val wide = value.u8(0) and 0x01 != 0
        val bpm = if (wide && value.size >= 3) value.u8(1) or (value.u8(2) shl 8) else value.u8(1)
        return bpm.takeIf { it in 1..250 }
    }
}

/** Today's running totals since local midnight. [distanceMeters]/[calories] are null when not reported. */
data class StepTotals(val steps: Int, val distanceMeters: Int?, val calories: Int?) {
    /**
     * False when the step count can't match the strap's own distance. After a firmware update (seen on
     * 3.20 -> 3.25) the strap reported 8969 steps with 42 m: the step counter came back wrong while
     * distance and calories stayed right. Strides under [MIN_STRIDE_METERS] are not plausible.
     */
    val isPlausible: Boolean
        get() = distanceMeters == null || steps <= SLACK_STEPS || distanceMeters >= steps * MIN_STRIDE_METERS

    companion object {
        const val MIN_STRIDE_METERS = 0.25
        /** Below this many steps rounding in the strap's distance can't be judged. */
        const val SLACK_STEPS = 200
    }
}

/**
 * Endpoint 0x0016 "RealtimeActivity": query `03` -> `04 status record`; notify on `05 01` -> `06 status`,
 * off `05 00`. Record: `flags u8, steps u32, [bit0: running u32], [bit1: walking u32],
 * [bit2+bit3: distance u32, calories u32], [bit4: gait…]`, little-endian.
 */
object RealtimeSteps {
    val QUERY: ByteArray = bytesOf(0x03)
    val NOTIFY_ON: ByteArray = bytesOf(0x05, 0x01)
    val NOTIFY_OFF: ByteArray = bytesOf(0x05, 0x00)

    fun parseRecord(b: ByteArray, offset: Int = 0): StepTotals? {
        if (b.size - offset < 5) return null
        val r = ByteReader(b, offset)
        val flags = r.u8()
        val steps = r.u32().toInt()
        if (flags and 0x01 != 0) { if (r.remaining < 4) return StepTotals(steps, null, null); r.u32() }
        if (flags and 0x02 != 0) { if (r.remaining < 4) return StepTotals(steps, null, null); r.u32() }
        if (flags and 0x0C == 0x0C && r.remaining >= 8) {
            return StepTotals(steps, r.u32().toInt(), r.u32().toInt())
        }
        return StepTotals(steps, null, null)
    }

    /** Query reply (`04 01 record`) or an unsolicited push (`op record`); null for command acks. */
    fun parseMessage(payload: ByteArray): StepTotals? {
        if (payload.isEmpty()) return null
        return when (payload.u8(0)) {
            0x04 -> if (payload.size > 2 && payload.u8(1) == 0x01) parseRecord(payload, 2) else null
            0x02, 0x06 -> null
            else -> parseRecord(payload, 1)
        }
    }
}

/** A health alert the strap raised on its own (0x001D `0D`, 03 §3.10 table A). [value] is bpm, stress or SpO2 %. */
data class HealthAlert(val type: Type, val timestamp: Long, val value: Long) {
    enum class Type(val code: Int) { HIGH_HEART_RATE(1), LOW_HEART_RATE(2), HIGH_STRESS(3), LOW_SPO2(4), IRREGULAR_RHYTHM(5) }

    companion object {
        const val PUSH = 0x0D

        /** `0D type u8, ts u32, value u32`. Null for other messages and for alert types Zepp ignores too. */
        fun parse(payload: ByteArray): HealthAlert? {
            if (payload.size < 10 || payload.u8(0) != PUSH) return null
            val type = Type.entries.firstOrNull { it.code == payload.u8(1) } ?: return null
            val r = ByteReader(payload, 2)
            return HealthAlert(type, r.u32(), r.u32())
        }
    }
}
