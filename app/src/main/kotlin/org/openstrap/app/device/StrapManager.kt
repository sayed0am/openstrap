package org.openstrap.app.device

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.GattStrapLink
import org.openstrap.app.ble.StrapUuids
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.WearEventRow
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.protocol.services.Gender
import org.openstrap.protocol.services.HealthAlert
import org.openstrap.protocol.services.UserProfile as StrapUserProfile
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.services.BatteryInfo
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.AlarmCapabilities
import org.openstrap.protocol.services.AlarmCodec
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.DeviceFeatures
import org.openstrap.protocol.services.FindDevice
import org.openstrap.protocol.services.Setting
import org.openstrap.protocol.services.SettingsCodec
import org.openstrap.protocol.services.SettingsGroup
import org.openstrap.protocol.services.RealtimeSteps
import org.openstrap.protocol.services.StepTotals
import org.openstrap.protocol.services.SportSession
import org.openstrap.protocol.crypto.crc32
import org.openstrap.protocol.history.FetchCodec
import org.openstrap.protocol.history.GdspMessage
import org.openstrap.protocol.firmware.DfuCommands
import org.openstrap.protocol.firmware.DfuException
import org.openstrap.protocol.firmware.DfuProgress
import org.openstrap.protocol.firmware.DfuSession
import org.openstrap.app.ble.GattDfuLink
import android.os.PowerManager
import org.openstrap.protocol.services.TimeSync
import org.openstrap.protocol.toHex
import org.openstrap.protocol.u8
import java.time.ZonedDateTime

/**
 * Owns the connection to the strap: GATT, the channel session and everything built on it. The connection's life is
 * [ConnectionManager]'s, commands are [StrapCommands], buzzing is [BuzzCoordinator], history sync is [SyncCoordinator]
 * and live heart rate is [LiveHeartRateStream]. This class wires them up, sets up each connection and listens to it.
 * Every step is logged to [log] so behaviour on real hardware can be checked against the spec.
 */
class StrapManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val dao: HealthDao,
    // Its own scope until AppContainer passes appScope in. Tests pass theirs.
    @Suppress("InjectDispatcher")
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : StrapController {
    override val log = MutableStateFlow<List<String>>(emptyList())
    override val firmwareUpdating = MutableStateFlow(false)

    private val healthAlerts = HealthAlertNotifier(context)
    private val wearNotifier = WearNotifier(context)
    private val wearEvents = WearEventLog(scope, dao::insertWearEvent) { log("wear event not stored: $it") }

    private val connections: ConnectionManager = ConnectionManager(
        context, settings, scope, ::log,
        object : ConnectionManager.Hooks {
            override suspend fun setUp(client: GattClient, link: GattStrapLink, session: ChannelSession) {
                afterAuth(client, link, session)
            }
            override fun connectedState(): ConnectionState.Connected = ConnectionState.Connected(battery, firmware, charging)
            override suspend fun onConnected(job: CoroutineScope, session: ChannelSession) {
                connected(job, session)
            }
            override fun onLinkEnded() {
                buzzer.endFind()
                onWear(null)
                live.onLinkEnded()
            }
        },
    )
    private val commands: StrapCommands = StrapCommands(connections, firmwareUpdating, ::log)
    private val buzzer: BuzzCoordinator = BuzzCoordinator(scope, connections, firmwareUpdating, commands, ::log)
    private val syncs: SyncCoordinator = SyncCoordinator(
        context, scope, settings, dao, connections, firmwareUpdating,
        SyncCoordinator.Notifiers(AutoDetectNotifier(context), healthAlerts), ::log,
    )
    private val live: LiveHeartRateStream =
        LiveHeartRateStream(scope, connections, connections.connection, firmwareUpdating, ::log)

    override val connection: MutableStateFlow<ConnectionState> = connections.connection
    override val sync: MutableStateFlow<SyncState> = syncs.sync
    override val syncRecord: MutableStateFlow<SyncRecord> = syncs.syncRecord
    override val syncEnded: MutableSharedFlow<Unit> = syncs.syncEnded
    override val liveHeartRate: MutableStateFlow<Int?> = live.liveHeartRate
    override val heartRateReadings: MutableSharedFlow<Int> = live.heartRateReadings
    override val sportSessionPushes = MutableSharedFlow<SportSession.Push>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val todaySteps = MutableStateFlow<StepTotals?>(null)
    override val info = MutableStateFlow<StrapInfo?>(null)
    override val alarmsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val settingsChanged = MutableSharedFlow<SettingsGroup>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val findingStrap: MutableStateFlow<Boolean> = buzzer.findingStrap

    // Learned in setup, and the battery kept up to date by watchBattery. Volatile, as other threads read them.
    @Volatile private var battery: Int? = null
    @Volatile private var charging = false
    @Volatile private var firmware: String? = null

    /** The connected session, for features such as history sync and firmware update. */
    val activeSession: ChannelSession? get() = connections.activeSession

    fun log(message: String) {
        Log.i("OpenStrap", message)
        val line = "%tT  %s".format(System.currentTimeMillis(), message)
        log.update { (it + line).takeLast(400) }
    }

    override fun autoConnect() = connections.autoConnect()

    override fun backgroundConnect() = connections.backgroundConnect()

    override fun connect() = connections.connect()

    override fun disconnect() = connections.disconnect()

    private suspend inline fun <T> step(name: String, block: () -> T): T? = setupStep(name, { log(it) }, block)

    /** The setup after auth, as named steps. Each failed step is logged and setup goes on without it. */
    private suspend fun afterAuth(client: GattClient, l: GattStrapLink, s: ChannelSession) {
        queryMtu(l, s)
        querySettingsVersion(s)
        val deviceInformation = queryDeviceInformation(s)
        queryFeatures(s)
        readDeviceInfo(client, s, deviceInformation)
        queryRestartKey(s)
        querySoundGroup(s)
        readBattery(client, s)
        queryTimeVersion(s)
        syncTime(s, client)
    }

    /** Payload MTU reported by the strap (§1.3). */
    private suspend fun queryMtu(l: GattStrapLink, s: ChannelSession) {
        if (s.services.has(Endpoints.CONNECT)) {
            step("mtu query") {
                val r = s.request(Endpoints.CONNECT, byteArrayOf(1), 5_000) { it.u8(0) == 0x02 }
                val n = r.u8(1) or (r.u8(2) shl 8)
                if (n + 3 in 23..247) l.payloadMtu = n
                log("strap payload mtu=$n")
            }
        }
    }

    /** The settings protocol version decides how query replies are framed (03 §3.6), so learn it first. */
    private suspend fun querySettingsVersion(s: ChannelSession) {
        commands.settingsProtocol = null
        if (s.services.has(Endpoints.SETTINGS)) {
            step("settings version query") {
                val r = s.request(Endpoints.SETTINGS, SettingsCodec.versionRequest, 5_000) { it.isNotEmpty() && it.u8(0) == SettingsCodec.VERSION + 1 }
                val protocol = SettingsCodec.parseVersion(r)
                commands.settingsProtocol = protocol
                log("settings protocol ${protocol?.version} groups ${protocol?.groups} (${r.toHex()})")
            }
        }
    }

    /**
     * Resource versions for the firmware check, read once per connect as Zepp does with `03 00 00 00` (codemap 07
     * §1.4). Nobody has seen the strap's values yet, so log them raw.
     */
    private suspend fun queryDeviceInformation(s: ChannelSession): SettingsGroup? {
        var deviceInformation: SettingsGroup? = null
        if (s.services.has(Endpoints.SETTINGS)) {
            step("device information query") {
                val group = SettingsCodec.GROUP_DEVICE_INFORMATION
                val r = s.request(Endpoints.SETTINGS, SettingsCodec.query(group, withOptions = false), 5_000) { SettingsCodec.isReplyTo(group, it) }
                deviceInformation = SettingsCodec.parse(r, commands.settingsProtocol?.version)
                val keys = deviceInformation?.settings?.values?.joinToString(" ") { "${it.key}=${it.text ?: it.number ?: it.raw.toHex()}" }
                log("device information ${r.toHex()} -> ${keys ?: "unparsed"}")
            }
        }
        return deviceInformation
    }

    /** Runtime feature bitmap: history gates on it (01 §3). Nobody has seen the strap's reply yet, so log it raw. */
    private suspend fun queryFeatures(s: ChannelSession) {
        if (s.services.has(DeviceFeatures.ENDPOINT)) {
            step("device features query") {
                val r = s.request(DeviceFeatures.ENDPOINT, DeviceFeatures.request, 5_000) { it.isNotEmpty() && it.u8(0) == DeviceFeatures.REPLY }
                s.features = DeviceFeatures.parseMessage(r)
                log("device features ${r.toHex()} -> ${s.features?.let { "mask %02x g2 %s alg %s".format(it.mask, it.group2?.toHex(), it.algorithm) } ?: "unparsed"}")
            }
        }
    }

    /** Firmware, hardware and serial from the device information service, into [info]. */
    private suspend fun readDeviceInfo(client: GattClient, s: ChannelSession, deviceInformation: SettingsGroup?) {
        firmware = step("firmware read") {
            client.read(StrapUuids.DEVICE_INFO_SERVICE, StrapUuids.SOFTWARE_REVISION).toString(Charsets.UTF_8).trimEnd('\u0000')
        }
        log("firmware=$firmware")
        val hardware = step("hardware read") {
            client.read(StrapUuids.DEVICE_INFO_SERVICE, StrapUuids.HARDWARE_REVISION).toString(Charsets.UTF_8).trimEnd('\u0000')
        }
        val serial = step("serial read") {
            client.read(StrapUuids.DEVICE_INFO_SERVICE, StrapUuids.SERIAL_NUMBER).toString(Charsets.UTF_8).trimEnd('\u0000')
        }
        val dfu = GattDfuLink(client).available
        info.value = StrapInfo(firmware, hardware, serial, dfu, s.services.has(Endpoints.FIND_DEVICE), deviceInformation)
        log("hardware=$hardware dfu=$dfu")
    }

    /** GENERAL key 45 restarts the strap; like Zepp, offer Restart only when the strap reports it (03 §5.2, 07 §0). */
    private suspend fun queryRestartKey(s: ChannelSession) {
        if (s.services.has(Endpoints.SETTINGS)) {
            step("restart key query") {
                val group = SettingsCodec.GROUP_GENERAL
                val r = s.request(Endpoints.SETTINGS, SettingsCodec.query(group, listOf(SettingsCodec.KEY_RESTART), withOptions = false), 5_000) { SettingsCodec.isReplyTo(group, it) }
                val restart = SettingsCodec.parse(r, commands.settingsProtocol?.version)?.get(SettingsCodec.KEY_RESTART)
                log("general key 45 ${r.toHex()} -> ${restart?.let { "reported" } ?: "not reported"}")
                info.value = info.value?.copy(canRestart = restart != null)
            }
        }
    }

    /**
     * Vibration strength: Zepp keeps it in SOUND key 4. Nobody knows yet whether the strap has the group, so read it
     * whole (read-only) and log the reply; a strap without it answers with an error code.
     */
    private suspend fun querySoundGroup(s: ChannelSession) {
        if (s.services.has(Endpoints.SETTINGS)) {
            step("sound group query") {
                val group = SettingsCodec.GROUP_SOUND
                val r = s.request(Endpoints.SETTINGS, SettingsCodec.query(group), 5_000) { SettingsCodec.isReplyTo(group, it) }
                val parsed = SettingsCodec.parse(r, commands.settingsProtocol?.version)
                val keys = parsed?.settings?.values?.joinToString(" ") { "${it.key}=${it.text ?: it.number ?: it.raw.toHex()}" }
                log("sound group ${r.toHex()} -> ${keys ?: "unparsed (code ${SettingsCodec.replyCode(r)})"}")
            }
        }
    }

    private suspend fun readBattery(client: GattClient, s: ChannelSession) {
        step("battery read") {
            if (s.services.has(Endpoints.BATTERY)) {
                BatteryInfo.parseChannel(s.request(Endpoints.BATTERY, BatteryInfo.GET) { it.u8(0) == 0x04 })
            } else {
                BatteryInfo.parse(client.read(StrapUuids.MAIN_SERVICE, StrapUuids.BATTERY))
            }
        }?.let {
            battery = it.level
            charging = it.charging == true
        }
        log("battery=$battery charging=$charging")
    }

    private suspend fun queryTimeVersion(s: ChannelSession) {
        timeProtocol = null
        if (s.services.has(Endpoints.TIME)) {
            timeProtocol = step("time version query") {
                TimeSync.parseVersion(s.request(Endpoints.TIME, TimeSync.versionRequest, 5_000) { it.isNotEmpty() && it.u8(0) == TimeSync.VERSION + 1 })
            }
            log("time protocol $timeProtocol")
        }
    }

    /** Connected: this connection's listeners in [job], the connect job, so they end with it. Then sync and live HR. */
    private fun connected(job: CoroutineScope, s: ChannelSession) {
        with(job) {
            watchBattery(s)
            watchSteps(s)
            watchDataAvailable(s)
            watchProfile(s)
            watchCalorieGoal(s)
            watchHealthAlerts(s)
            watchAlarms(s)
            watchSettings(s)
            watchFindDevice(s)
            watchWear(s)
            watchSportSession(s)
            watchFeatures(s)
        }
        syncs.autoSync(s)
        live.onConnected()
    }

    /** The strap's `03` "features changed" push carries a new bitmap (03 §3.7). Ends with this connection. */
    private fun CoroutineScope.watchFeatures(s: ChannelSession) = launch {
        if (!s.services.has(DeviceFeatures.ENDPOINT)) return@launch
        s.messagesFor(DeviceFeatures.ENDPOINT).collect { p ->
            if (p.isEmpty() || p.u8(0) != DeviceFeatures.PUSH) return@collect
            log("device features changed ${p.toHex()}")
            DeviceFeatures.parseMessage(p)?.let { s.features = it }
        }
    }

    /** The time module's version; the next-DST hint needs 2 or later (03 §3.8). */
    @Volatile private var timeProtocol: Int? = null

    /** Sets the strap's clock and time zone, and tells it when DST next changes, as Zepp does on connect and on every change. */
    private suspend fun syncTime(s: ChannelSession, client: GattClient) {
        val now = ZonedDateTime.now()
        step("time sync") {
            if (s.services.has(Endpoints.TIME)) {
                val r = s.request(Endpoints.TIME, TimeSync.setRequest(now)) { it.u8(0) == 0x06 }
                log("time set: ${r.toHex()}")
            } else {
                client.write(StrapUuids.MAIN_SERVICE, StrapUuids.CURRENT_TIME, TimeSync.struct(now), withResponse = true)
                log("time set via 2A2B")
            }
        }
        if ((timeProtocol ?: 0) >= 2) step("dst hint") {
            val request = TimeSync.nextDstRequest(now)
            val r = s.request(Endpoints.TIME, request) { it.isNotEmpty() && it.u8(0) == TimeSync.NEXT_DST + 1 }
            log("next dst ${request.toHex()}: ${r.toHex()}")
        }
    }

    /** The phone's clock or time zone changed (travel, DST, a manual change): pass it on while connected. */
    private val timeChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) {
            val s = activeSession ?: return
            val client = connections.gatt ?: return
            log("phone time changed (${intent.action?.substringAfterLast('.')}), updating the strap")
            scope.launch { syncTime(s, client) }
        }
    }

    init {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(context, timeChanged, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        // The app was stopped while it knew the wear state: close that interval now, as nothing is connected yet.
        val now = System.currentTimeMillis() / 1000
        wearEvents.post { if (dao.lastWearEvent()?.worn != null) dao.insertWearEvent(WearEventRow(now, null)) }
        // For the widgets' status line with the strap away: the battery and charging as last connected.
        scope.launch {
            connection.mapNotNull { c -> (c as? ConnectionState.Connected)?.let { it.batteryPercent?.let { b -> b to it.charging } } }
                .distinctUntilChanged()
                .collect { (b, ch) -> catching { settings.setStrapBattery(b, ch) }.onFailure { log("strap battery not kept: $it") } }
        }
    }

    /**
     * Sends the user's profile to the strap now and whenever it changes, as Zepp does (03 §3.5); the strap uses it
     * for calories and heart-rate zones. Runs in the connect job, so it ends with this connection.
     */
    private fun CoroutineScope.watchProfile(s: ChannelSession) = launch {
        if (!s.services.has(Endpoints.USER_INFO)) return@launch
        settings.profile.map { it.toStrapProfile() }.distinctUntilChanged().collect { p ->
            val payload = p.channelPayload() ?: return@collect
            catching {
                val r = s.request(Endpoints.USER_INFO, payload, 5_000) { it.isNotEmpty() && it.u8(0) == StrapUserProfile.SET + 1 }
                log("profile sent: ${r.toHex()}")
            }.onFailure { log("profile send failed: $it") }
        }
    }

    /**
     * Sends the calorie goal to the strap as its calorie target (HEALTH key 83, an i16 of active kcal) now and whenever
     * it changes, as Zepp does, so the strap's own goal buzz matches the app's. Only a strap that reports the key is
     * written, in the type it reported, and only when it holds another value. Runs in the connect job.
     */
    private fun CoroutineScope.watchCalorieGoal(s: ChannelSession) = launch {
        if (!s.services.has(Endpoints.SETTINGS)) return@launch
        settings.goals.map { it.calories }.distinctUntilChanged().collect { kcal ->
            catching {
                val keys = listOf(SettingsCodec.KEY_CALORIE_TARGET)
                val reply = commands.settingsQuery(SettingsCodec.GROUP_HEALTH, keys, withOptions = false)
                val group = SettingsCodec.parse(reply, commands.settingsProtocol()?.version) ?: return@catching
                val write = SettingsCodec.calorieTargetFor(group, kcal) ?: return@catching
                val ack = commands.command(Endpoints.SETTINGS, write)
                log(if (SettingsCodec.isWriteSuccess(ack)) "calorie goal sent: $kcal" else "calorie goal refused: ${ack.toHex()}")
            }.onFailure { log("calorie goal send failed: $it") }
        }
    }

    /** The strap's own alerts (high/low HR, stress, low SpO2, irregular rhythm) arrive on 0x001D at any time. */
    private fun CoroutineScope.watchHealthAlerts(s: ChannelSession) = launch {
        s.messagesFor(Endpoints.OPEN_APP).collect { p ->
            if (p.isEmpty() || p.u8(0) != HealthAlert.PUSH) return@collect
            val alert = HealthAlert.parse(p)
            log("health alert < ${p.toHex()}: $alert")
            alert ?: return@collect
            healthAlerts.show(alert)
            // Kept so Today can list the day's alerts; a failed write mustn't stop the watcher.
            catching { dao.insertHealthAlerts(listOf(HealthAlertRow(alert.timestamp, alert.type.code, alert.value))) }
                .onFailure { log("health alert not stored: $it") }
        }
    }

    /**
     * The strap pushes `04` when a setting changes on its side, laid out like a query reply (03 §3.6.1, §3.6 note 4).
     * Query replies come through here too, since they share the opcode; passing them on again is harmless.
     */
    private fun CoroutineScope.watchSettings(s: ChannelSession) = launch {
        if (!s.services.has(Endpoints.SETTINGS)) return@launch
        s.messagesFor(Endpoints.SETTINGS).collect { p ->
            if (SettingsCodec.replyGroup(p) == null) return@collect
            val group = SettingsCodec.parse(p, commands.settingsProtocol?.version)
            if (group == null) return@collect log("settings 04 unparsed: ${p.toHex()}")
            // Nobody has captured the strap's wear push yet: log the first one raw, for checking against hardware.
            // The wear poll's own replies come through here too, so they're left out.
            if (!wearPushLogged && !wearPolling && SettingsCodec.wearStatus(group) != null) {
                wearPushLogged = true
                log("wear status push ${p.toHex()}")
            }
            settingsChanged.tryEmit(group)
        }
    }

    @Volatile private var wearPushLogged = false
    @Volatile private var wearPolling = false

    /**
     * Wear / off-body, as Zepp does for the strap (07 §4 N5): with the app's switch on, turns on the strap's wear
     * reminder (GENERAL key 44), then follows DEVICE_INFORMATION key 16 as read on connect, as pushed, and as polled
     * every minute. Ends with this connection.
     */
    private fun CoroutineScope.watchWear(s: ChannelSession) = launch {
        if (!s.services.has(Endpoints.SETTINGS)) return@launch
        launch {
            settings.wearReminder.distinctUntilChanged().collect { on ->
                if (on) enableWearAlert()
                wearNotifier.apply(WearReminder.decide(lastWorn, on, wearNotifier.shown))
            }
        }
        // Firmware 3.25.0.3 didn't push key 16 when taken off or put back on (2026-10-23), though a fresh read had it
        // right; poll so a change shows within a minute. The reply also reaches settingsChanged, and onWear dedupes.
        launch {
            while (isActive && connections.session === s) {
                delay(WEAR_POLL_MS)
                if (firmwareUpdating.value) continue
                wearPolling = true
                catching {
                    val keys = listOf(SettingsCodec.KEY_WEAR_STATUS)
                    val reply = commands.settingsQuery(SettingsCodec.GROUP_DEVICE_INFORMATION, keys, withOptions = false)
                    SettingsCodec.parse(reply, commands.settingsProtocol?.version)?.let(SettingsCodec::wearStatus)?.let { onWear(it) }
                }.onFailure { log("wear status poll failed: $it") }
                wearPolling = false
            }
        }
        info.value?.deviceInformation?.let(SettingsCodec::wearStatus)?.let { onWear(it) }
        settingsChanged.collect { g -> SettingsCodec.wearStatus(g)?.let { onWear(it) } }
    }

    /** GENERAL key 44 on, written only when the strap doesn't already report it on. */
    private suspend fun enableWearAlert() = catching {
        val key = SettingsCodec.KEY_WEAR_STATUS_ALERT
        val reply = commands.settingsQuery(SettingsCodec.GROUP_GENERAL, listOf(key), withOptions = false)
        val group = SettingsCodec.parse(reply, commands.settingsProtocol()?.version)
            ?: throw StrapCommandException("unparsed ${reply.toHex()}")
        if (group[key]?.bool == true) return@catching
        // Written even when the strap doesn't list the key, so the reply shows whether it takes it.
        val write = SettingsCodec.write(SettingsCodec.GROUP_GENERAL, group.version, listOf(SettingsCodec.boolSetting(key, true)))
        val ack = commands.command(Endpoints.SETTINGS, write)
        log("wear reminder on (was ${group[key]?.bool ?: "not reported"}): ${if (SettingsCodec.isWriteSuccess(ack)) "ok" else "refused"}")
    }.onFailure { log("wear reminder switch failed: $it") }

    /** The last wear state: true on body, false off, null unknown (not connected, or not reported). */
    @Volatile private var lastWorn: Boolean? = null

    /**
     * Records a change in [worn] for Charge and posts or clears the off-body notification. Queued inside the lock, so
     * the stored order (and, within one second, the state kept) is the order the changes came in ([WearEventLog]).
     */
    private fun onWear(worn: Boolean?) {
        synchronized(this) {
            if (worn == lastWorn) return
            lastWorn = worn
            wearEvents.record(System.currentTimeMillis() / 1000, worn) {
                wearNotifier.apply(WearReminder.decide(worn, settings.wearReminder.first(), wearNotifier.shown))
            }
        }
        log("wear status: ${when (worn) { true -> "on body"; false -> "off body"; null -> "unknown" }}")
    }

    /** Zepp re-reads the alarms on the strap's `0F` push (03 §3.14). */
    private fun CoroutineScope.watchAlarms(s: ChannelSession) = launch {
        s.messagesFor(Endpoints.ALARMS).collect { p ->
            if (p.isEmpty() || p.u8(0) != AlarmCodec.CHANGED) return@collect
            log("alarms changed on the strap: ${p.toHex()}")
            alarmsChanged.tryEmit(Unit)
        }
    }

    /** The sport module's own pushes (its replies, `10 …`, go to their requests). */
    private fun CoroutineScope.watchSportSession(s: ChannelSession) = launch {
        commands.forgetSportCaps()
        if (!s.services.has(SportSession.ENDPOINT)) return@launch
        s.messagesFor(SportSession.ENDPOINT).collect { p ->
            if (p.isNotEmpty() && p.u8(0) == SportSession.REPLY) return@collect
            log("sport < ${p.toHex()}")
            SportSession.parsePush(p)?.let {
                if (it is SportSession.Push.Data) live.onSessionData(it)
                sportSessionPushes.tryEmit(it)
            }
        }
    }

    override suspend fun sportSession(state: SportSession.State, sportType: Int, startTime: Long, elapsedSeconds: Long) =
        commands.sportSession(state, sportType, startTime, elapsedSeconds)

    override suspend fun sportSessionKeepAlive() = commands.sportSessionKeepAlive()

    /** The strap's `07` once buzzing has stopped; it also ends Find strap. */
    private fun CoroutineScope.watchFindDevice(s: ChannelSession) = launch {
        if (!s.services.has(Endpoints.FIND_DEVICE)) return@launch
        s.messagesFor(Endpoints.FIND_DEVICE).collect { p ->
            if (p.isNotEmpty() && p.u8(0) == FindDevice.FOUND) {
                log("buzzing stopped on the strap: ${p.toHex()}")
                buzzer.stoppedOnStrap()
            }
        }
    }

    private fun UserProfile.toStrapProfile() = StrapUserProfile(
        birthYear = birthYear,
        gender = when (sex) { UserProfile.Sex.Female -> Gender.FEMALE; UserProfile.Sex.Male -> Gender.MALE; null -> null },
        heightCm = heightCm,
        weightKg = weightKg,
    )

    // The watch* listeners run in the connect job, so they end with the connection instead of piling up.
    private fun CoroutineScope.watchBattery(s: ChannelSession) = launch {
        s.messagesFor(Endpoints.BATTERY).collect { payload ->
            BatteryInfo.parseChannel(payload)?.let { info ->
                battery = info.level
                charging = info.charging == true
                log("battery update ${info.level}% charging=$charging")
                // One atomic change, so a Disconnect landing meanwhile isn't overwritten with Connected.
                connection.update {
                    (it as? ConnectionState.Connected)?.copy(batteryPercent = info.level, charging = info.charging == true) ?: it
                }
            }
        }
    }

    private fun CoroutineScope.watchSteps(s: ChannelSession) = launch {
        if (!s.services.has(Endpoints.REALTIME_ACTIVITY)) return@launch
        launch {
            s.messagesFor(Endpoints.REALTIME_ACTIVITY).collect { p ->
                val totals = RealtimeSteps.parseMessage(p)
                if (totals == null) log("steps < ${p.toHex()}")
                totals?.let {
                    if (todaySteps.value?.isPlausible != it.isPlausible) {
                        log("steps today=${it.steps} dist=${it.distanceMeters} kcal=${it.calories}" + if (!it.isPlausible) " (total doesn't match distance: counting from synced history)" else "")
                    }
                    todaySteps.value = it
                }
            }
        }
        catching { s.send(Endpoints.REALTIME_ACTIVITY, RealtimeSteps.NOTIFY_ON) }.onFailure { log("steps notify failed: $it") }
        // Pushes may only come while walking; poll as a fallback.
        while (isActive && connections.session === s) {
            if (!firmwareUpdating.value) {
                catching { s.send(Endpoints.REALTIME_ACTIVITY, RealtimeSteps.QUERY) }.onFailure { log("steps query failed: $it") }
            }
            delay(60_000)
        }
    }

    /**
     * The strap pushes `11 <type>` when it has new data; like Zepp, sync the related types at once
     * (a finished workout, recovery HR; 04 §1.7).
     */
    private fun CoroutineScope.watchDataAvailable(s: ChannelSession) = launch {
        if (!s.services.has(Endpoints.HISTORY)) return@launch
        s.messagesFor(Endpoints.HISTORY).collect { p ->
            val m = FetchCodec.parse(p) as? GdspMessage.DataAvailable ?: return@collect
            val types = m.typesToSync.toSet()
            if (types.isEmpty()) return@collect
            log("strap has new data of type ${m.typeCode}, syncing $types")
            syncs.syncPushed(types)
        }
    }

    override fun syncNow() = syncs.syncNow()

    /** Debug aid, see [HistorySync.dumpRaw]. */
    fun dumpRaw(days: Int, types: Set<Int>? = null) = syncs.dumpRaw(days, types)

    /** Debug aid: sends [payload] to [endpoint] and logs every reply on it for 5 s. */
    fun probe(endpoint: Int, payload: ByteArray) {
        val s = activeSession ?: return log("probe: not connected")
        scope.launch {
            val listener = launch { s.messagesFor(endpoint).collect { log("probe %04x < %s".format(endpoint, it.toHex())) } }
            // Device-side chunk ACKs on 0016 carry transport errors (e.g. status 5 = unknown endpoint).
            val acks = connections.link?.let { l ->
                launch { l.controlNotifications.collect { log("probe %04x ack < %s".format(endpoint, it.toHex())) } }
            }
            log("probe %04x > %s".format(endpoint, payload.toHex()))
            catching { s.send(endpoint, payload) }.onFailure { log("probe failed: $it") }
            delay(5_000)
            listener.cancel()
            acks?.cancel()
        }
    }

    /**
     * Debug aid for the emulator, which has no strap: shows a connected strap and plays [script] as live heart rate,
     * one reading a second; each entry is (bpm, seconds). Steps rise by [stepsPerMinute] meanwhile.
     */
    fun simulate(script: List<Pair<Int, Int>>, stepsPerMinute: Int, battery: Int = 80) {
        scope.launch {
            log("simulating a strap: $script, $stepsPerMinute steps/min")
            // No strap is paired on the emulator: a reconnect loop would knock the fake one offline.
            connections.stopTrying()
            connection.value = ConnectionState.Connected(battery, "simulated")
            // As a real connection does: the service carries the notifications.
            catching { StrapService.start(context) }.onFailure { log("service start failed: $it") }
            var steps = 1_000
            var second = 0
            for ((bpm, seconds) in script) repeat(seconds) {
                live.onReading(bpm)
                if (++second % 60 == 0) { steps += stepsPerMinute; todaySteps.value = StepTotals(steps, null, null) }
                delay(1_000)
            }
            liveHeartRate.value = null
            connection.value = ConnectionState.Disconnected
            log("simulation over")
        }
    }


    override fun setAppVisible(visible: Boolean) = live.setAppVisible(visible)

    override fun setWorkoutActive(active: Boolean) = live.setWorkoutActive(active)

    override fun setEffortWatch(active: Boolean) = live.setEffortWatch(active)

    override suspend fun readAlarms(): Pair<AlarmCapabilities, List<Alarm>> = commands.readAlarms()

    override suspend fun saveAlarm(alarm: Alarm, isNew: Boolean) = commands.saveAlarm(alarm, isNew)

    override suspend fun deleteAlarm(index: Int) = commands.deleteAlarm(index)

    override suspend fun clearAlarms() = commands.clearAlarms()

    override suspend fun readHealthSettings(): SettingsGroup = commands.readHealthSettings()

    override suspend fun writeHealthSettings(version: Int, settings: List<Setting>) = commands.writeHealthSettings(version, settings)

    override suspend fun restartStrap() = commands.restartStrap()

    override suspend fun buzz(pattern: List<Int>) = buzzer.buzz(pattern)

    override suspend fun startCallBuzz(pulsed: Boolean) = buzzer.startCallBuzz(pulsed)

    override suspend fun stopCallBuzz() = buzzer.stopCallBuzz()

    /** `06`. Any stop ends Find strap too (a ringing call ending, Stop in the app), so it can't wait out the timeout. */
    suspend fun stopBuzzing() = buzzer.stopBuzzing()

    override suspend fun findStrap() = buzzer.findStrap()

    override suspend fun stopFindingStrap() = buzzer.stopBuzzing()

    override suspend fun installFirmware(file: ByteArray, type: Int, onProgress: (DfuProgress) -> Unit) {
        val client = connections.gatt
        if (client == null || connection.value !is ConnectionState.Connected) {
            throw DfuException(-1, Strings.app.get(R.string.system_command_not_connected))
        }
        // Claimed first, so a sync can't start between the check below and the claim: startSync gives way to it.
        if (!firmwareUpdating.compareAndSet(false, true)) throw DfuException(-1, Strings.app.get(R.string.system_firmware_already_running))
        // Given back on a refusal, and when the caller is cancelled during the fast-charge query.
        var claimed = false
        try {
            if (sync.value is SyncState.Running || syncs.isSyncing) {
                throw DfuException(-1, Strings.app.get(R.string.system_firmware_wait_for_sync))
            }
            val s = connections.session
            if (s != null && s.services.has(Endpoints.SETTINGS) && commands.fastRechargeOn(s) == true) {
                throw DfuException(-1, Strings.app.get(R.string.system_firmware_fast_charge))
            }
            claimed = true
        } finally {
            if (!claimed) firmwareUpdating.value = false
        }
        val wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenStrap:firmware").apply { acquire(30 * 60_000L) }
        try {
            // A failure is logged and handed on to the Firmware page. A cancellation is rethrown by catching as it came.
            catching {
                if (live.isRunning) live.pause()
                val link = GattDfuLink(client)
                if (!link.available) throw DfuException(-1, Strings.app.get(R.string.system_firmware_no_service))
                // Bigger ATT packets make the transfer several times faster. Refused, it falls back to 20-byte writes.
                catching { client.requestMtu(247) }.onFailure { log("dfu: mtu request failed: $it") }
                log("dfu: ${file.size} bytes, type ${"%02x".format(type)}, crc ${"%08x".format(crc32(file))}, att mtu ${client.mtu}")
                link.enable()
                connections.refreshGattOnConnect = true
                DfuSession(link, ::log).flash(file, type, DfuCommands.MODE_NORMAL, onProgress)
                log("dfu: installed, strap is restarting")
            }.onFailure { log("dfu failed: ${it.message}") }.getOrThrow()
        } finally {
            firmwareUpdating.value = false
            if (wake.isHeld) wake.release()
        }
    }

    private companion object {
        const val WEAR_POLL_MS = 60_000L
    }
}
