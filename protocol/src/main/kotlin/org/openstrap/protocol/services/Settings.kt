package org.openstrap.protocol.services

import org.openstrap.protocol.ByteReader
import org.openstrap.protocol.ByteWriter
import org.openstrap.protocol.u8

/**
 * One setting from the strap's settings module (0x000A, Zepp `gm40`/`emj`). [raw] is the value as sent
 * (without the type byte) so any type can be written back unchanged; [number] is set for numeric types.
 * [choices] are the allowed values the strap offers for enum settings.
 */
data class Setting(
    val key: Int,
    val type: Int,
    val raw: ByteArray,
    val number: Long?,
    val choices: List<Int>? = null,
    /** Per-value flag bytes sent from settings protocol 4 on: `disable`, then (v5) `hidden`, then (v6) `lock`. */
    val flags: List<Int> = emptyList(),
) {
    val bool: Boolean? get() = if (type == SettingsCodec.TYPE_BOOL) number != 0L else null

    /** A string value without its terminating zero; null for other types. */
    val text: String? get() =
        if (type == SettingsCodec.TYPE_STRING || type == SettingsCodec.TYPE_STRING_ENUM) raw.toString(Charsets.UTF_8).substringBefore('\u0000') else null

    override fun equals(other: Any?) = other is Setting && key == other.key && type == other.type &&
        raw.contentEquals(other.raw) && choices == other.choices && flags == other.flags
    override fun hashCode() = key * 31 + raw.contentHashCode()
}

/** Reply to the version query (`02 version [n groups… [n flags]]`, yk40.java:489-528). */
data class SettingsProtocol(val version: Int, val groups: List<Int>)

/** A group as read: its version (needed for writes) and settings by key. */
data class SettingsGroup(val group: Int, val version: Int, val settings: Map<Int, Setting>) {
    operator fun get(key: Int): Setting? = settings[key]

    /**
     * This group with [update] applied: the strap's `04` push has the query reply's layout but may carry only the
     * keys that changed (03 §3.6.1 table C), so keys it leaves out keep their value. Its group version wins, since
     * writes must echo the latest one. Null when [update] is for another group.
     */
    fun updatedWith(update: SettingsGroup): SettingsGroup? =
        if (update.group != group) null else SettingsGroup(group, update.version, settings + update.settings)
}

/**
 * Settings module commands (version 2+). Query `03 withOptions group n keys…` (n = 0 reads the whole group),
 * reply `04 code group grpVer withOptions n {key type value [options]}`; write `05 group grpVer 00 n {key type
 * value}` with keys ascending, answered `06 01` (zj40.k, yk40.java:776). Integers are little-endian.
 */
object SettingsCodec {
    const val VERSION = 0x01
    const val QUERY = 0x03
    /** Query reply, and also the strap's unsolicited "settings changed" push (03 §3.6.1). */
    const val REPLY = 0x04
    const val WRITE = 0x05

    const val TYPE_I16 = 0x01
    const val TYPE_I16_LIST = 0x02
    const val TYPE_I32 = 0x03
    const val TYPE_I32_LIST = 0x04
    const val TYPE_BOOL = 0x0B
    const val TYPE_ENUM = 0x10
    const val TYPE_MULTI = 0x11
    const val TYPE_STRING = 0x20
    const val TYPE_STRING_ENUM = 0x21
    const val TYPE_TIME = 0x30
    const val TYPE_I64 = 0x40
    const val TYPE_I32_B = 0x50

    /**
     * Device-information group (read-only) and the keys the firmware check sends (codemap 07 §1.4, 03 §3.6.2):
     * key 2 is an int sent as `firmwareFlag`; keys 3–6 are "version,flag" strings.
     */
    const val GROUP_DEVICE_INFORMATION = 0x00
    const val KEY_LANGUAGE_FAMILY = 0x02
    const val KEY_BASE_RESOURCE_VERSION = 0x03
    const val KEY_RESOURCE_VERSION = 0x04
    const val KEY_FONT_VERSION = 0x05
    const val KEY_EXT_RESOURCE_VERSION = 0x06
    /**
     * DEVICE_INFORMATION key 16 `wearStatus`, a bool: false while the strap is off-body. Zepp listens for its `04`
     * push and posts its "Wear Reminders" notification on false (07 §0 row 37, §4 N5). It only pushes while GENERAL
     * [KEY_WEAR_STATUS_ALERT] is on (INFERRED from Zepp turning that on). On firmware 3.25.0.3 with key 44 on, no push
     * came in either direction within minutes (2026-10-23), while a fresh query read it right; OpenStrap polls it.
     */
    const val KEY_WEAR_STATUS = 0x10
    /** GENERAL key 44 `wearStatusAlertSwitch`: the strap's own switch for the wear reminder (07 §4 N5). */
    const val KEY_WEAR_STATUS_ALERT = 0x2C

    /**
     * Sound and vibration group (`x960`/`y960`): key 3 notification vibration, key 4 vibration strength (an enum),
     * key 5 system vibration (codemap 03 §3.6.2). Whether the strap has the group is unknown; OpenStrap only reads it.
     */
    const val GROUP_SOUND = 0x03

    /** Health group and its keys (kel.java:62-110). */
    const val GROUP_HEALTH = 0x08
    const val KEY_HR_INTERVAL = 0x01
    const val KEY_HIGH_HR_ALERT = 0x02
    const val KEY_LOW_HR_ALERT = 0x03
    const val KEY_ACTIVITY_HR = 0x04
    const val KEY_STRESS = 0x13
    const val KEY_SMART_SPO2 = 0x31
    const val KEY_LOW_SPO2_ALERT = 0x32
    /** HEALTH key 83 `calorieTarget`, an i16 of active kcal; the strap reports 300 in 200 to 3000 (03 §3.6.2). */
    const val KEY_CALORIE_TARGET = 0x53

    /** General group; key 47 is fast-recharge mode, which blocks firmware updates and history sync (c6j.java:135). */
    const val GROUP_GENERAL = 0x0A
    const val KEY_FAST_RECHARGE = 0x2F
    /** GENERAL key 45 `resetSwitch`: writing true restarts the strap. Zepp offers Restart only when the strap reports it (07 §0, 03 §5.2). */
    const val KEY_RESTART = 0x2D

    /** HR interval value for continuous measuring; 0 is off, other values are minutes. */
    const val HR_CONTINUOUS = 0xFE

    /** Settings protocol version and group list. Zepp sends `01 04 07`; `01` alone works (Gadgetbridge). */
    val versionRequest: ByteArray = byteArrayOf(VERSION.toByte())

    fun parseVersion(reply: ByteArray): SettingsProtocol? {
        if (reply.size < 2 || reply.u8(0) != VERSION + 1) return null
        val version = reply.u8(1)
        if (reply.size == 2) return SettingsProtocol(version, emptyList())
        val n = reply.u8(2)
        if (reply.size < 3 + n) return null
        return SettingsProtocol(version, List(n) { reply.u8(3 + it) })
    }

    /**
     * Flag bytes after each value (and its options) in query replies: none up to protocol 3, then one per
     * version up to three (eez v4, r9o v5, xux v6+; yk40.java:77-96).
     */
    fun flagBytes(protocolVersion: Int?): Int = ((protocolVersion ?: 1) - 3).coerceIn(0, 3)

    fun query(group: Int, keys: List<Int> = emptyList(), withOptions: Boolean = true): ByteArray =
        ByteWriter().u8(QUERY).u8(if (withOptions) 1 else 0).u8(group).u8(keys.size).apply { keys.forEach { u8(it) } }.toByteArray()

    /** Result code of a query reply: 1 is success (a630.y). */
    fun replyCode(reply: ByteArray): Int? = if (reply.size >= 2 && reply.u8(0) == REPLY) reply.u8(1) else null

    /** The group a successful `04` reply or push is about; null for anything else. */
    fun replyGroup(reply: ByteArray): Int? = if (replyCode(reply) == 1 && reply.size >= 3) reply.u8(2) else null

    /**
     * Whether [reply] answers a query for [group]. Replies and pushes share opcode `04` (03 §3.6.1), so a push for
     * another group can arrive while a query waits; only a reply for the asked group, or a failure (which carries
     * no group), counts.
     */
    fun isReplyTo(group: Int, reply: ByteArray): Boolean {
        val code = replyCode(reply) ?: return false
        return code != 1 || replyGroup(reply) == group
    }

    /** Parses a query reply; [protocolVersion] comes from [parseVersion] and decides the per-value flag bytes. */
    fun parse(reply: ByteArray, protocolVersion: Int? = null): SettingsGroup? {
        val trailer = flagBytes(protocolVersion)
        if (replyCode(reply) != 1 || reply.size < 6) return null
        val r = ByteReader(reply, 2)
        val group = r.u8(); val version = r.u8(); val options = r.u8() != 0; val n = r.u8()
        val out = LinkedHashMap<Int, Setting>()
        repeat(n) {
            val key = r.u8(); val type = r.u8()
            val start = r.pos
            val number: Long? = when (type) {
                TYPE_I16 -> r.u16().toShort().toLong()
                TYPE_I32, TYPE_I32_B -> r.u32().toInt().toLong()
                TYPE_BOOL, TYPE_ENUM -> r.u8().toLong()
                TYPE_I64 -> r.u32() or (r.u32() shl 32)
                TYPE_TIME -> { val h = r.u8(); val m = r.u8(); h * 60L + m }
                TYPE_I16_LIST -> { r.bytes(r.u8() * 2); null }
                TYPE_I32_LIST -> { r.bytes(r.u8() * 4); null }
                TYPE_MULTI -> { r.bytes(r.u8()); null }
                TYPE_STRING, TYPE_STRING_ENUM -> { cString(r); null }
                else -> return null // an unknown type: the rest can't be framed
            }
            val raw = reply.copyOfRange(start, r.pos)
            var choices: List<Int>? = null
            if (options) when (type) {
                TYPE_I16 -> r.bytes(4)
                TYPE_I32 -> r.bytes(8)
                TYPE_ENUM -> choices = List(r.u8()) { r.u8() }
                TYPE_MULTI -> r.bytes(r.u8())
                TYPE_I16_LIST -> r.bytes(6)
                TYPE_I32_LIST -> r.bytes(10)
                TYPE_STRING -> r.bytes(1)
                TYPE_STRING_ENUM -> { r.bytes(1); repeat(r.u8()) { cString(r) } }
                else -> Unit
            }
            val flags = List(trailer) { r.u8() }
            out[key] = Setting(key, type, raw, number, choices, flags)
        }
        return SettingsGroup(group, version, out)
    }

    /** Writes [settings] (key → type + raw value) to [group]; [version] is the group version from the last read. */
    fun write(group: Int, version: Int, settings: List<Setting>): ByteArray {
        val w = ByteWriter().u8(WRITE).u8(group).u8(version).u8(0).u8(settings.size)
        settings.sortedBy { it.key }.forEach { w.u8(it.key).u8(it.type).bytes(it.raw) }
        return w.toByteArray()
    }

    fun enumSetting(key: Int, value: Int) = Setting(key, TYPE_ENUM, byteArrayOf(value.toByte()), value.toLong())
    /**
     * An integer setting in [type] as the strap reported the key: an i16 or an i32, little-endian. Null for any other
     * type, so a key whose type is unexpected is never written with the wrong width.
     */
    fun intSetting(key: Int, type: Int, value: Int): Setting? = when (type) {
        TYPE_I16 -> Setting(key, type, byteArrayOf(value.toByte(), (value shr 8).toByte()), value.toLong())
        TYPE_I32 -> Setting(key, type, ByteArray(4) { (value shr (8 * it)).toByte() }, value.toLong())
        else -> null
    }

    /**
     * The write that sets the strap's calorie target to [kcal] given its HEALTH group as read ([health]), in the type
     * it reported for key 83. Null when the strap doesn't report the key, already holds [kcal], or reports a type
     * other than an integer.
     */
    fun calorieTargetFor(health: SettingsGroup, kcal: Int): ByteArray? {
        val current = health[KEY_CALORIE_TARGET] ?: return null
        if (current.number == kcal.toLong()) return null
        val setting = intSetting(KEY_CALORIE_TARGET, current.type, kcal) ?: return null
        return write(GROUP_HEALTH, health.version, listOf(setting))
    }

    fun boolSetting(key: Int, on: Boolean) = Setting(key, TYPE_BOOL, byteArrayOf(if (on) 1 else 0), if (on) 1 else 0)

    /** Restarts the strap: GENERAL key 45 = true; [version] is the GENERAL group version as read. Answered `06 01`, then the link drops. */
    fun restart(version: Int): ByteArray = write(GROUP_GENERAL, version, listOf(boolSetting(KEY_RESTART, true)))

    /**
     * The [restart] write for a GENERAL read ([general]), or null when the strap doesn't report key 45: like Zepp, only
     * a strap that lists it is asked (03 §5.2, 07 §0).
     */
    fun restartFor(general: SettingsGroup): ByteArray? = if (general[KEY_RESTART] == null) null else restart(general.version)

    /**
     * A "version,flag" string read as Zepp does (codemap 07 §1.4): split on commas, both of the first two parts must
     * be integers, anything after them is ignored. Null when it isn't such a string.
     */
    fun versionFlag(setting: Setting?): Pair<Int, Int>? {
        val parts = setting?.text?.split(",") ?: return null
        if (parts.size < 2) return null
        val version = parts[0].toIntOrNull() ?: return null
        val flag = parts[1].toIntOrNull() ?: return null
        return version to flag
    }

    /** The wear state in a DEVICE_INFORMATION reply or push: true on body, false off-body; null when it doesn't carry key 16. */
    fun wearStatus(group: SettingsGroup): Boolean? =
        if (group.group == GROUP_DEVICE_INFORMATION) group[KEY_WEAR_STATUS]?.bool else null

    fun isWriteSuccess(reply: ByteArray): Boolean = reply.size == 2 && reply.u8(0) == WRITE + 1 && reply.u8(1) == 1

    private fun cString(r: ByteReader) { while (r.u8() != 0) {} }
}
