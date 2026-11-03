package org.openstrap.app.device

import kotlinx.coroutines.flow.StateFlow
import org.openstrap.app.data.catching
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.AlarmCapabilities
import org.openstrap.protocol.services.AlarmCodec
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.Setting
import org.openstrap.protocol.services.SettingsCodec
import org.openstrap.protocol.services.SettingsGroup
import org.openstrap.protocol.services.SettingsProtocol
import org.openstrap.protocol.services.SportSession
import org.openstrap.protocol.toHex
import org.openstrap.protocol.u8
import java.util.Locale

/**
 * Request and reply commands on the connected session: alarms, settings, restart and the sport module. Each throws
 * [StrapCommandException] when not connected, during a firmware update, or when the strap refuses.
 */
// One function per strap command StrapController offers.
@Suppress("TooManyFunctions")
internal class StrapCommands(
    private val link: LiveLink,
    private val firmwareUpdating: StateFlow<Boolean>,
    private val log: (String) -> Unit,
) {
    /** Sends a settings-style command and returns the reply whose first byte is the command + 1 (or that [matches]). */
    // Each throw is a reason the user is shown.
    @Suppress("ThrowsCount")
    suspend fun command(
        endpoint: Int,
        request: ByteArray,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        matches: (ByteArray) -> Boolean = { it.isNotEmpty() && it.u8(0) == request.u8(0) + 1 },
    ): ByteArray {
        val s = link.activeSession ?: throw StrapCommandException("The strap isn't connected")
        if (firmwareUpdating.value) throw StrapCommandException("Wait for the firmware update to finish")
        val reply = catching {
            s.request(endpoint, request, timeoutMs, matches = matches)
        }.getOrElse { throw StrapCommandException("The strap didn't answer") }
        log("cmd %04x > %s < %s".format(Locale.ROOT, endpoint, request.toHex(), reply.toHex()))
        return reply
    }

    private var alarmCaps: AlarmCapabilities? = null

    private suspend fun alarmCaps(): AlarmCapabilities = alarmCaps ?: (
        AlarmCodec.parseVersion(command(Endpoints.ALARMS, AlarmCodec.versionRequest))
            ?: throw StrapCommandException("The strap sent an unexpected alarm reply")
        ).also { alarmCaps = it }

    suspend fun readAlarms(): Pair<AlarmCapabilities, List<Alarm>> {
        val caps = alarmCaps()
        val list = AlarmCodec.parseList(command(Endpoints.ALARMS, AlarmCodec.listRequest), caps.titles)
            ?: throw StrapCommandException("The strap sent an unexpected alarm list")
        return caps to list
    }

    suspend fun saveAlarm(alarm: Alarm, isNew: Boolean) {
        val caps = alarmCaps()
        val request = if (isNew) {
            AlarmCodec.add(alarm, caps.titles, caps.workdaysSupported)
        } else {
            AlarmCodec.modify(alarm, caps.titles, caps.workdaysSupported)
        }
        alarmCommand(request, "The strap didn't save the alarm")
    }

    suspend fun deleteAlarm(index: Int) = alarmCommand(AlarmCodec.delete(index), "The strap didn't delete the alarm")

    suspend fun clearAlarms() = alarmCommand(AlarmCodec.clearAllRequest, "The strap didn't delete the alarms")

    private suspend fun alarmCommand(request: ByteArray, refused: String) {
        if (!AlarmCodec.isSuccess(request, command(Endpoints.ALARMS, request))) throw StrapCommandException(refused)
    }

    /** Learned on connect. Null until then or when the strap didn't answer (then replies are read as v1 to v3). */
    @Volatile var settingsProtocol: SettingsProtocol? = null

    suspend fun settingsProtocol(): SettingsProtocol? = settingsProtocol
        ?: catching { SettingsCodec.parseVersion(command(Endpoints.SETTINGS, SettingsCodec.versionRequest)) }.getOrNull()
            ?.also { settingsProtocol = it }

    /** A settings query; a `04` push for another group arriving meanwhile isn't taken as its reply (03 §3.6.1). */
    suspend fun settingsQuery(group: Int, keys: List<Int> = emptyList(), withOptions: Boolean = true): ByteArray =
        command(Endpoints.SETTINGS, SettingsCodec.query(group, keys, withOptions)) { SettingsCodec.isReplyTo(group, it) }

    suspend fun readHealthSettings(): SettingsGroup {
        val protocol = settingsProtocol()
        val reply = settingsQuery(SettingsCodec.GROUP_HEALTH)
        return SettingsCodec.parse(reply, protocol?.version)
            ?: throw StrapCommandException("The strap couldn't read its settings (code ${SettingsCodec.replyCode(reply)})")
    }

    suspend fun writeHealthSettings(version: Int, settings: List<Setting>) {
        val reply = command(Endpoints.SETTINGS, SettingsCodec.write(SettingsCodec.GROUP_HEALTH, version, settings))
        if (!SettingsCodec.isWriteSuccess(reply)) {
            throw StrapCommandException("The strap didn't accept the setting (code ${reply.getOrNull(1)})")
        }
    }

    /** Writes GENERAL key 45 = true with the group version just read (07 §0). The strap may drop the link before its ack. */
    // Each throw is a reason the user is shown.
    @Suppress("ThrowsCount")
    suspend fun restartStrap() {
        val query = settingsQuery(SettingsCodec.GROUP_GENERAL, listOf(SettingsCodec.KEY_RESTART), withOptions = false)
        val group = SettingsCodec.parse(query, settingsProtocol()?.version)
            ?: throw StrapCommandException("The strap couldn't read its settings")
        val request = SettingsCodec.restartFor(group) ?: throw StrapCommandException("This strap can't be restarted from the app")
        log("restart: asking the strap to restart")
        val reply = catching { command(Endpoints.SETTINGS, request) }
            .getOrElse { e -> if (link.activeSession == null) { log("restart: link dropped before the ack"); return } else throw e }
        if (!SettingsCodec.isWriteSuccess(reply)) {
            throw StrapCommandException("The strap didn't accept the restart (code ${reply.getOrNull(1)})")
        }
    }

    /**
     * GENERAL key 47, null when the strap doesn't report it. Zepp refuses OTA while it is on (07 §1.6). Asked straight
     * on the session, as the firmware install has set firmwareUpdating by then and [command] refuses while it is set.
     */
    suspend fun fastRechargeOn(s: ChannelSession): Boolean? = catching {
        val group = SettingsCodec.GROUP_GENERAL
        val request = SettingsCodec.query(group, listOf(SettingsCodec.KEY_FAST_RECHARGE), withOptions = false)
        val reply = s.request(Endpoints.SETTINGS, request, COMMAND_TIMEOUT_MS) { SettingsCodec.isReplyTo(group, it) }
        SettingsCodec.parse(reply, settingsProtocol?.version)?.get(SettingsCodec.KEY_FAST_RECHARGE)?.bool
    }.onFailure { log("fast-charge query failed: $it") }.getOrNull()

    /** Learned with the first START on each connection. */
    @Volatile private var sportCaps: SportSession.Capabilities? = null

    /** A new connection: the next START asks the sport module's version again. */
    fun forgetSportCaps() {
        sportCaps = null
    }

    // Each throw is a reason the user is shown.
    @Suppress("ThrowsCount")
    suspend fun sportSession(state: SportSession.State, sportType: Int, startTime: Long, elapsedSeconds: Long) {
        val s = link.activeSession ?: throw StrapCommandException("The strap isn't connected")
        if (!s.services.has(SportSession.ENDPOINT)) throw StrapCommandException("The strap has no sport module")
        suspend fun send(request: ByteArray): ByteArray {
            val reply = catching { s.request(SportSession.ENDPOINT, request, COMMAND_TIMEOUT_MS) { SportSession.isReplyTo(request, it) } }
                .getOrElse { throw StrapCommandException("The strap didn't answer") }
            log("sport > ${request.toHex()} < ${reply.toHex()}")
            if (!SportSession.isOk(reply)) throw StrapCommandException("The strap refused the workout command")
            return reply
        }
        val caps = sportCaps
            ?: (SportSession.parseVersion(send(SportSession.versionRequest)) ?: throw StrapCommandException("Unknown sport module version"))
                .also { sportCaps = it }
        if (state == SportSession.State.START) send(SportSession.config(sportType, caps))
        send(SportSession.state(state, startTime, elapsedSeconds, caps))
    }

    suspend fun sportSessionKeepAlive() {
        val s = link.activeSession ?: return
        catching { s.send(SportSession.ENDPOINT, SportSession.pauseKeepAlive) }
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 8_000L
    }
}
