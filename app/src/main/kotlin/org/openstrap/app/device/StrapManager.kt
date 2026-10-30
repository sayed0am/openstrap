package org.openstrap.app.device

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.GattException
import org.openstrap.app.ble.GattStrapLink
import org.openstrap.app.ble.StrapUuids
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.WearEventRow
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.data.settings.StrapSnapshot
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.widget.strapStateChanged
import org.openstrap.app.widget.syncStale
import org.openstrap.protocol.services.Gender
import org.openstrap.protocol.services.HealthAlert
import org.openstrap.protocol.services.UserProfile as StrapUserProfile
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.auth.AuthException
import org.openstrap.protocol.hexToBytes
import org.openstrap.protocol.services.BatteryInfo
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.AlarmCapabilities
import org.openstrap.protocol.services.AlarmCodec
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.DeviceFeatures
import org.openstrap.protocol.services.FindDevice
import org.openstrap.protocol.services.MotorVibrate
import org.openstrap.protocol.services.Setting
import org.openstrap.protocol.services.SettingsCodec
import org.openstrap.protocol.services.SettingsGroup
import org.openstrap.protocol.services.SettingsProtocol
import org.openstrap.protocol.services.LiveHeartRate
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
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * Owns the connection to the strap: GATT, the channel session and everything built on it.
 * Every step is logged to [log] so behaviour on real hardware can be checked against the spec.
 */
class StrapManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val dao: HealthDao,
) : StrapController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val healthAlerts = HealthAlertNotifier(context)
    private val autoDetected = AutoDetectNotifier(context)
    private val wearNotifier = WearNotifier(context)
    private val wearEvents = WearEventLog(scope, dao::insertWearEvent) { log("wear event not stored: $it") }

    override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val sync = MutableStateFlow<SyncState>(SyncState.Idle)
    override val syncRecord = MutableStateFlow(SyncRecord())
    override val syncEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val liveHeartRate = MutableStateFlow<Int?>(null)
    override val heartRateReadings = MutableSharedFlow<Int>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val sportSessionPushes = MutableSharedFlow<SportSession.Push>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val todaySteps = MutableStateFlow<StepTotals?>(null)
    override val liveHeartRateEnabled = MutableStateFlow(true)
    override val info = MutableStateFlow<StrapInfo?>(null)
    override val firmwareUpdating = MutableStateFlow(false)
    override val alarmsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val settingsChanged = MutableSharedFlow<SettingsGroup>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Set before a firmware install: the strap's services can change, so re-discover on the next connect. */
    @Volatile private var refreshGattOnConnect = false
    override val log = MutableStateFlow<List<String>>(emptyList())

    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    /** True while the user wants the strap connected; cleared by [disconnect]. */
    private var wantConnected = false
    private var retries = 0
    private var lastAutoSync = 0L
    private var liveJob: Job? = null
    private var gatt: GattClient? = null
    private var session: ChannelSession? = null
    private var link: GattStrapLink? = null
    private var battery: Int? = null
    private var charging = false
    private var firmware: String? = null

    /** The connected session, for features such as history sync and firmware update. */
    val activeSession: ChannelSession? get() = session?.takeIf { connection.value is ConnectionState.Connected }

    fun log(message: String) {
        Log.i("OpenStrap", message)
        val line = "%tT  %s".format(System.currentTimeMillis(), message)
        log.update { (it + line).takeLast(400) }
    }

    /** Connect if a strap is paired and nothing is connected or in progress. Safe to call often. */
    // Synchronized, and checking connectJob: callers race at process start (app start-up, the companion presence
    // service, phone alerts), before the connect coroutine has moved the state off Disconnected.
    @Synchronized
    override fun autoConnect() {
        when (connection.value) {
            is ConnectionState.Connected, ConnectionState.Connecting, ConnectionState.Authenticating -> return
            else -> Unit
        }
        if (reconnectJob?.isActive == true || connectJob?.isActive == true) return
        connect()
    }

    /** The user's Disconnect, kept across process restarts; null until read from settings. See [backgroundConnect]. */
    @Volatile private var held: Boolean? = null
    // One at a time, in order, so a quick Disconnect then Connect can't land the other way round.
    private val heldWriter = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun hold(value: Boolean) {
        if (held == value) return
        held = value
        heldWriter.launch { settings.setHeldDisconnected(value) }
    }

    override fun backgroundConnect() {
        scope.launch {
            // Presence events repeat every few seconds while the strap advertises, so a skip isn't logged.
            if (held ?: settings.heldDisconnected.first().also { held = it }) return@launch
            autoConnect()
        }
    }

    override fun connect() {
        hold(false)
        wantConnected = true
        reconnectJob?.cancel()
        startConnect()
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        // Every attempt would fail at once; Bluetooth coming back on reconnects (see bluetoothChanged), as Zepp waits too.
        if (context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == false) {
            log("bluetooth is off, reconnecting once it's back on")
            reconnectJob?.cancel()
            return
        }
        val wait = RETRY_SECONDS[minOf(retries, RETRY_SECONDS.lastIndex)]
        retries++
        log("reconnecting in ${wait}s")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait * 1000L)
            startConnect()
        }
    }

    private fun startConnect() {
        connectJob?.cancel()
        connectJob = scope.launch {
            val device = settings.pairedDevice.first()
            if (device == null) {
                connection.value = ConnectionState.NotPaired
                return@launch
            }
            runCatching { StrapService.start(context) }.onFailure { log("service start failed: $it") }
            connection.value = ConnectionState.Connecting
            // A client left over from a lost link still holds one of Android's few GATT client slots.
            gatt?.close()
            val client = GattClient(context, device.address).also { gatt = it }
            try {
                log("connecting to ${device.address}")
                client.connect(refreshCache = refreshGattOnConnect)
                refreshGattOnConnect = false
                log("connected, services discovered, mtu=${client.mtu}")
                watchDisconnect(client)

                val l = GattStrapLink(client).also { link = it }
                l.enableChannelNotifications()
                val s = ChannelSession(l, ::log).also { session = it }
                s.start(this)

                connection.value = ConnectionState.Authenticating
                s.handshake(device.authKeyHex.hexToBytes())

                afterAuth(client, l, s)
                // afterAuth swallows step failures, so a link that dropped during it would otherwise show as Connected.
                if (!client.connected.value) throw GattException(tr(R.string.strap_manager_link_lost_during_setup))
                connection.value = ConnectionState.Connected(battery, firmware, charging)
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
                retries = 0
                autoSync(s)
                liveHeartRateEnabled.value = settings.liveHeartRate.first()
                if (liveWanted()) startLive()
            } catch (e: AuthException) {
                log("auth failed: ${e.message}")
                connection.value = PairingErrors.authFailure(e)
                close()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("connect failed: $e")
                connection.value = ConnectionState.Failed(e.message ?: tr(R.string.strap_manager_connection_failed))
                close()
                scheduleReconnect()
            }
        }
    }

    private suspend fun afterAuth(client: GattClient, l: GattStrapLink, s: ChannelSession) {
        // Payload MTU reported by the strap (§1.3).
        if (s.services.has(Endpoints.CONNECT)) {
            runCatching {
                val r = s.request(Endpoints.CONNECT, byteArrayOf(1), 5_000) { it.u8(0) == 0x02 }
                val n = r.u8(1) or (r.u8(2) shl 8)
                if (n + 3 in 23..247) l.payloadMtu = n
                log("strap payload mtu=$n")
            }.onFailure { log("mtu query failed: $it") }
        }

        // The settings protocol version decides how query replies are framed (03 §3.6), so learn it first.
        settingsProtocol = null
        if (s.services.has(Endpoints.SETTINGS)) {
            runCatching {
                val r = s.request(Endpoints.SETTINGS, SettingsCodec.versionRequest, 5_000) { it.isNotEmpty() && it.u8(0) == SettingsCodec.VERSION + 1 }
                settingsProtocol = SettingsCodec.parseVersion(r)
                log("settings protocol ${settingsProtocol?.version} groups ${settingsProtocol?.groups} (${r.toHex()})")
            }.onFailure { log("settings version query failed: $it") }
        }

        // Resource versions for the firmware check, read once per connect as Zepp does with `03 00 00 00` (codemap 07
        // §1.4). Nobody has seen the strap's values yet, so log them raw.
        var deviceInformation: SettingsGroup? = null
        if (s.services.has(Endpoints.SETTINGS)) {
            runCatching {
                val group = SettingsCodec.GROUP_DEVICE_INFORMATION
                val r = s.request(Endpoints.SETTINGS, SettingsCodec.query(group, withOptions = false), 5_000) { SettingsCodec.isReplyTo(group, it) }
                deviceInformation = SettingsCodec.parse(r, settingsProtocol?.version)
                val keys = deviceInformation?.settings?.values?.joinToString(" ") { "${it.key}=${it.text ?: it.number ?: it.raw.toHex()}" }
                log("device information ${r.toHex()} -> ${keys ?: "unparsed"}")
            }.onFailure { log("device information query failed: $it") }
        }

        // Runtime feature bitmap: history gates on it (01 §3). Nobody has seen the strap's reply yet, so log it raw.
        if (s.services.has(DeviceFeatures.ENDPOINT)) {
            runCatching {
                val r = s.request(DeviceFeatures.ENDPOINT, DeviceFeatures.request, 5_000) { it.isNotEmpty() && it.u8(0) == DeviceFeatures.REPLY }
                s.features = DeviceFeatures.parseMessage(r)
                log("device features ${r.toHex()} -> ${s.features?.let { "mask %02x g2 %s alg %s".format(it.mask, it.group2?.toHex(), it.algorithm) } ?: "unparsed"}")
            }.onFailure { log("device features query failed: $it") }
        }

        firmware = runCatching {
            client.read(StrapUuids.DEVICE_INFO_SERVICE, StrapUuids.SOFTWARE_REVISION).toString(Charsets.UTF_8).trimEnd('\u0000')
        }.getOrNull()
        log("firmware=$firmware")
        val hardware = runCatching {
            client.read(StrapUuids.DEVICE_INFO_SERVICE, StrapUuids.HARDWARE_REVISION).toString(Charsets.UTF_8).trimEnd('\u0000')
        }.getOrNull()
        val serial = runCatching {
            client.read(StrapUuids.DEVICE_INFO_SERVICE, StrapUuids.SERIAL_NUMBER).toString(Charsets.UTF_8).trimEnd('\u0000')
        }.getOrNull()
        val dfu = GattDfuLink(client).available
        info.value = StrapInfo(firmware, hardware, serial, dfu, s.services.has(Endpoints.FIND_DEVICE), deviceInformation)
        log("hardware=$hardware dfu=$dfu")

        // GENERAL key 45 restarts the strap; like Zepp, offer Restart only when the strap reports it (03 §5.2, 07 §0).
        if (s.services.has(Endpoints.SETTINGS)) {
            runCatching {
                val group = SettingsCodec.GROUP_GENERAL
                val r = s.request(Endpoints.SETTINGS, SettingsCodec.query(group, listOf(SettingsCodec.KEY_RESTART), withOptions = false), 5_000) { SettingsCodec.isReplyTo(group, it) }
                val restart = SettingsCodec.parse(r, settingsProtocol?.version)?.get(SettingsCodec.KEY_RESTART)
                log("general key 45 ${r.toHex()} -> ${restart?.let { "reported" } ?: "not reported"}")
                info.value = info.value?.copy(canRestart = restart != null)
            }.onFailure { log("restart key query failed: $it") }
        }

        // Vibration strength: Zepp keeps it in SOUND key 4. Nobody knows yet whether the strap has the group, so read
        // it whole (read-only) and log the reply; a strap without it answers with an error code.
        if (s.services.has(Endpoints.SETTINGS)) {
            runCatching {
                val group = SettingsCodec.GROUP_SOUND
                val r = s.request(Endpoints.SETTINGS, SettingsCodec.query(group), 5_000) { SettingsCodec.isReplyTo(group, it) }
                val keys = SettingsCodec.parse(r, settingsProtocol?.version)?.settings?.values?.joinToString(" ") { "${it.key}=${it.text ?: it.number ?: it.raw.toHex()}" }
                log("sound group ${r.toHex()} -> ${keys ?: "unparsed (code ${SettingsCodec.replyCode(r)})"}")
            }.onFailure { log("sound group query failed: $it") }
        }

        runCatching {
            if (s.services.has(Endpoints.BATTERY)) {
                BatteryInfo.parseChannel(s.request(Endpoints.BATTERY, BatteryInfo.GET) { it.u8(0) == 0x04 })
            } else {
                BatteryInfo.parse(client.read(StrapUuids.MAIN_SERVICE, StrapUuids.BATTERY))
            }
        }.onFailure { log("battery read failed: $it") }.getOrNull()?.let {
            battery = it.level
            charging = it.charging == true
        }
        log("battery=$battery charging=$charging")

        timeProtocol = null
        if (s.services.has(Endpoints.TIME)) {
            timeProtocol = runCatching {
                TimeSync.parseVersion(s.request(Endpoints.TIME, TimeSync.versionRequest, 5_000) { it.isNotEmpty() && it.u8(0) == TimeSync.VERSION + 1 })
            }.onFailure { log("time version query failed: $it") }.getOrNull()
            log("time protocol $timeProtocol")
        }
        syncTime(s, client)
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
        runCatching {
            if (s.services.has(Endpoints.TIME)) {
                val r = s.request(Endpoints.TIME, TimeSync.setRequest(now)) { it.u8(0) == 0x06 }
                log("time set: ${r.toHex()}")
            } else {
                client.write(StrapUuids.MAIN_SERVICE, StrapUuids.CURRENT_TIME, TimeSync.struct(now), withResponse = true)
                log("time set via 2A2B")
            }
        }.onFailure { log("time sync failed: $it") }
        if ((timeProtocol ?: 0) >= 2) runCatching {
            val request = TimeSync.nextDstRequest(now)
            val r = s.request(Endpoints.TIME, request) { it.isNotEmpty() && it.u8(0) == TimeSync.NEXT_DST + 1 }
            log("next dst ${request.toHex()}: ${r.toHex()}")
        }.onFailure { log("dst hint failed: $it") }
    }

    /** The phone's clock or time zone changed (travel, DST, a manual change): pass it on while connected. */
    private val timeChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) {
            val s = activeSession ?: return
            val client = gatt ?: return
            log("phone time changed (${intent.action?.substringAfterLast('.')}), updating the strap")
            scope.launch { syncTime(s, client) }
        }
    }

    /**
     * Bluetooth turned off or on, which includes the stack restarting after a crash. Off ends the link at once, since
     * no disconnect callback comes then; on reconnects without waiting out the backoff.
     */
    private val bluetoothChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) {
            when (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF, android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                    val client = gatt ?: return
                    log("bluetooth turned off")
                    client.lost()
                }
                android.bluetooth.BluetoothAdapter.STATE_ON -> scope.launch {
                    if (!wantConnected || connection.value is ConnectionState.Connected || connectJob?.isActive == true) return@launch
                    log("bluetooth back on, reconnecting")
                    retries = 0
                    reconnectJob?.cancel()
                    startConnect()
                }
            }
        }
    }

    init {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(context, timeChanged, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        androidx.core.content.ContextCompat.registerReceiver(
            context, bluetoothChanged, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The app was stopped while it knew the wear state: close that interval now, as nothing is connected yet.
        val now = System.currentTimeMillis() / 1000
        wearEvents.post { if (dao.lastWearEvent()?.worn != null) dao.insertWearEvent(WearEventRow(now, null)) }
        // Every sync's start and end, pulled or in the background, so Today can tell a stuck sync from a slow one.
        scope.launch {
            var previous: SyncState = SyncState.Idle
            sync.collect { s ->
                syncRecord.update { it.next(previous, s, System.currentTimeMillis() / 1000) }
                // A sync starting while the widgets say the last one is old: their line turns to Syncing (widgets spec 5).
                if (s is SyncState.Running && previous !is SyncState.Running) launch {
                    val done = runCatching { settings.strapSnapshot.first().lastSyncDone }.getOrNull()
                    if (syncStale(done, System.currentTimeMillis() / 1000)) WidgetUpdater.changedAll(context)
                }
                previous = s
            }
        }
        // For the widgets' status line with the strap away: the battery and charging as last connected.
        scope.launch {
            connection.mapNotNull { c -> (c as? ConnectionState.Connected)?.let { it.batteryPercent?.let { b -> b to it.charging } } }
                .distinctUntilChanged()
                .collect { (b, ch) -> runCatching { settings.setStrapBattery(b, ch) }.onFailure { log("strap battery not kept: $it") } }
        }
        // And the widgets told when that line could change: pairing, the low battery edge, charging (widgets spec 7).
        scope.launch {
            var before: StrapSnapshot? = null
            settings.strapSnapshot.collect { now ->
                val was = before
                before = now
                if (was != null && strapStateChanged(now.paired, now.battery, now.charging, was.paired, was.battery, was.charging)) {
                    WidgetUpdater.changedAll(context)
                }
            }
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
            runCatching {
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
            runCatching {
                val group = SettingsCodec.parse(
                    settingsQuery(SettingsCodec.GROUP_HEALTH, listOf(SettingsCodec.KEY_CALORIE_TARGET), withOptions = false), settingsProtocol()?.version,
                ) ?: return@runCatching
                val write = SettingsCodec.calorieTargetFor(group, kcal) ?: return@runCatching
                val ack = command(Endpoints.SETTINGS, write)
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
            runCatching { dao.insertHealthAlerts(listOf(HealthAlertRow(alert.timestamp, alert.type.code, alert.value))) }
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
            val group = SettingsCodec.parse(p, settingsProtocol?.version)
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
            while (isActive && session === s) {
                delay(WEAR_POLL_MS)
                if (firmwareUpdating.value) continue
                wearPolling = true
                runCatching {
                    val reply = settingsQuery(SettingsCodec.GROUP_DEVICE_INFORMATION, listOf(SettingsCodec.KEY_WEAR_STATUS), withOptions = false)
                    SettingsCodec.parse(reply, settingsProtocol?.version)?.let(SettingsCodec::wearStatus)?.let { onWear(it) }
                }.onFailure { log("wear status poll failed: $it") }
                wearPolling = false
            }
        }
        info.value?.deviceInformation?.let(SettingsCodec::wearStatus)?.let { onWear(it) }
        settingsChanged.collect { g -> SettingsCodec.wearStatus(g)?.let { onWear(it) } }
    }

    /** GENERAL key 44 on, written only when the strap doesn't already report it on. */
    private suspend fun enableWearAlert() = runCatching {
        val key = SettingsCodec.KEY_WEAR_STATUS_ALERT
        val reply = settingsQuery(SettingsCodec.GROUP_GENERAL, listOf(key), withOptions = false)
        val group = SettingsCodec.parse(reply, settingsProtocol()?.version) ?: throw StrapCommandException("unparsed ${reply.toHex()}")
        if (group[key]?.bool == true) return@runCatching
        // Written even when the strap doesn't list the key, so the reply shows whether it takes it.
        val ack = command(Endpoints.SETTINGS, SettingsCodec.write(SettingsCodec.GROUP_GENERAL, group.version, listOf(SettingsCodec.boolSetting(key, true))))
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
        sportCaps = null
        if (!s.services.has(SportSession.ENDPOINT)) return@launch
        s.messagesFor(SportSession.ENDPOINT).collect { p ->
            if (p.isNotEmpty() && p.u8(0) == SportSession.REPLY) return@collect
            log("sport < ${p.toHex()}")
            SportSession.parsePush(p)?.let { sportSessionPushes.tryEmit(it) }
        }
    }

    /** Learned with the first START on each connection. */
    @Volatile private var sportCaps: SportSession.Capabilities? = null

    override suspend fun sportSession(state: SportSession.State, sportType: Int, startTime: Long, elapsedSeconds: Long) {
        val s = activeSession ?: throw StrapCommandException(tr(R.string.common_the_strap_isn_t))
        if (!s.services.has(SportSession.ENDPOINT)) throw StrapCommandException(tr(R.string.strap_manager_the_strap_has_no))
        suspend fun send(request: ByteArray): ByteArray {
            val reply = runCatching { s.request(SportSession.ENDPOINT, request, 8_000) { SportSession.isReplyTo(request, it) } }
                .getOrElse { throw StrapCommandException(tr(R.string.strap_manager_the_strap_didn_t)) }
            log("sport > ${request.toHex()} < ${reply.toHex()}")
            if (!SportSession.isOk(reply)) throw StrapCommandException(tr(R.string.strap_manager_the_strap_refused_the))
            return reply
        }
        val caps = sportCaps ?: (SportSession.parseVersion(send(SportSession.versionRequest)) ?: throw StrapCommandException(tr(R.string.strap_manager_unknown_sport_module_version)))
            .also { sportCaps = it }
        if (state == SportSession.State.START) send(SportSession.config(sportType, caps))
        send(SportSession.state(state, startTime, elapsedSeconds, caps))
    }

    override suspend fun sportSessionKeepAlive() {
        val s = activeSession ?: return
        runCatching { s.send(SportSession.ENDPOINT, SportSession.pauseKeepAlive) }
    }

    /** The strap's `07` once buzzing has stopped; it also ends Find strap. */
    private fun CoroutineScope.watchFindDevice(s: ChannelSession) = launch {
        if (!s.services.has(Endpoints.FIND_DEVICE)) return@launch
        s.messagesFor(Endpoints.FIND_DEVICE).collect { p ->
            if (p.isNotEmpty() && p.u8(0) == FindDevice.FOUND) {
                log("buzzing stopped on the strap: ${p.toHex()}")
                findBuzzOn = false
                endFind()
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
                (connection.value as? ConnectionState.Connected)?.let {
                    connection.value = it.copy(batteryPercent = info.level, charging = charging)
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
                    WidgetUpdater.stepsChanged(context)
                }
            }
        }
        runCatching { s.send(Endpoints.REALTIME_ACTIVITY, RealtimeSteps.NOTIFY_ON) }.onFailure { log("steps notify failed: $it") }
        // Pushes may only come while walking; poll as a fallback.
        while (isActive && session === s) {
            if (!firmwareUpdating.value) runCatching { s.send(Endpoints.REALTIME_ACTIVITY, RealtimeSteps.QUERY) }.onFailure { log("steps query failed: $it") }
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
            synchronized(pendingTypes) { pendingTypes += types }
            startSync(full = false)
        }
    }

    /**
     * Sync right after connecting (unless one ran recently and didn't fail), then every [AUTO_SYNC_SECONDS] while
     * connected. A failed one is retried at once: it was most likely cut off by the link this connect replaces.
     */
    private fun autoSync(s: ChannelSession) = scope.launch {
        while (isActive && session === s) {
            val now = System.currentTimeMillis() / 1000
            val due = now - lastAutoSync >= MIN_SYNC_GAP_SECONDS || sync.value is SyncState.Failed
            if (due && sync.value !is SyncState.Running && !firmwareUpdating.value) {
                lastAutoSync = now
                log("auto sync")
                syncNow()
            }
            delay(AUTO_SYNC_SECONDS * 1000L)
        }
    }

    private fun watchDisconnect(client: GattClient) = scope.launch {
        client.connected.filter { !it }.first()
        if (gatt === client) {
            log("link lost")
            // Ends this connection's listeners, and a setup still in progress, so it can't go on to report Connected.
            connectJob?.cancel()
            endFind()
            onWear(null)
            liveJob?.cancel()
            liveHeartRate.value = null
            session?.stop()
            session = null
            gatt = null
            client.close()
            if (wantConnected) {
                // Keep the foreground service up while we try to get the strap back.
                connection.value = ConnectionState.Connecting
                scheduleReconnect()
            } else {
                connection.value = ConnectionState.Disconnected
            }
        }
    }

    override fun disconnect() {
        hold(true)
        wantConnected = false
        reconnectJob?.cancel()
        connectJob?.cancel()
        liveJob?.cancel()
        endFind()
        onWear(null)
        close()
        liveHeartRate.value = null
        connection.value = ConnectionState.Disconnected
    }

    private fun close() {
        session?.stop()
        session = null
        // Clear the field first, so watchDisconnect sees the drop as ours and doesn't reconnect.
        val g = gatt
        gatt = null
        g?.close()
    }

    override fun syncNow() = startSync(full = true)

    /** One sync at a time; a full sync or types asked for meanwhile run when it ends. */
    private val syncLock = Mutex()
    private val pendingTypes = mutableSetOf<Int>()
    private val pendingFull = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun takePending(): Set<Int> = synchronized(pendingTypes) { pendingTypes.toSet().also { pendingTypes.clear() } }
    private fun hasPending(): Boolean = pendingFull.get() || synchronized(pendingTypes) { pendingTypes.isNotEmpty() }

    /** What to sync next: null for everything (a full sync covers anything pushed so far), or the pushed types. */
    private fun takeNext(): Set<Int>? = if (pendingFull.getAndSet(false)) { takePending(); null } else takePending()

    private fun startSync(full: Boolean) {
        if (firmwareUpdating.value) return
        val s = activeSession ?: return
        val client = gatt ?: return
        if (full) pendingFull.set(true)
        scope.launch {
            // A sync is running: it picks up what was just queued when it finishes.
            if (!syncLock.tryLock()) return@launch
            try {
                while (activeSession === s && !firmwareUpdating.value) {
                    val only = takeNext()
                    if (only != null && only.isEmpty()) break
                    HistorySync(s, client, dao, settings, sync, ::log, autoDetected::show).run(only)
                    (sync.value as? SyncState.Done)?.let { d -> runCatching { settings.setLastSyncDone(d.atEpochSeconds) }.onFailure { log("sync time not kept: $it") } }
                }
                runCatching { notifyBodySignals() }.onFailure { log("body signals: $it") }
                runCatching { WorkoutGapFill(dao) { settings.profile.first() }.run()?.let { log(it) } }.onFailure { log("workout gap fill: $it") }
                runCatching { updateEffectModel() }.onFailure { log("workout effect: $it") }
                runCatching { updateDailyEnergy() }.onFailure { log("daily energy: $it") }
                // Everything a widget shows may have moved (widgets spec 7).
                WidgetUpdater.changedAll(context)
                syncEnded.tryEmit(Unit)
            } finally {
                syncLock.unlock()
            }
            // A sync cut off by a lost link can end only after the reconnect, which skipped its own sync while this one
            // still showed as running. Run it again on the new link.
            val current = activeSession
            if (sync.value is SyncState.Failed && current != null && current !== s) {
                log("sync cut off by the old link, syncing again")
                startSync(full = true)
            } else if (hasPending()) {
                // Something queued between the last check and the unlock would otherwise wait for the next trigger.
                startSync(full = false)
            }
        }
    }

    /**
     * After a sync, pushes the body-signal alert for this morning once, when the state machine is in Alert
     * ([org.openstrap.app.data.BodySignals]). Watch stays in the app.
     */
    private suspend fun notifyBodySignals() {
        val today = java.time.LocalDate.now()
        val day = org.openstrap.app.data.DayWindow.of(today).from
        if ((settings.lastSync(BODY_SIGNALS_PUSHED) ?: 0L) >= day) return
        val r = org.openstrap.app.data.RecoveryEngine(dao).compute(today, settings.profile.first())
        val body = r.readiness.bodySignals ?: return
        if (body.day != day || body.state != org.openstrap.app.data.BodySignals.State.Alert) return
        healthAlerts.showBodySignals(body.families, body.nightsOff)
        settings.setLastSync(BODY_SIGNALS_PUSHED, day)
    }

    /**
     * Once per change of [org.openstrap.app.data.WorkoutEffect.MODEL], works out every phone workout's effect again,
     * so a workout saved under the old model shows the new one's Load. The strap's own figures, where it kept a
     * record, still win ([org.openstrap.app.data.WorkoutEffectEngine.strapEffect]).
     */
    private suspend fun updateEffectModel() {
        val model = org.openstrap.app.data.WorkoutEffect.MODEL.toLong()
        if (settings.lastSync(EFFECT_MODEL) == model) return
        val n = org.openstrap.app.data.WorkoutEffectEngine(dao).recomputeAll(settings.profile.first())
        settings.setLastSync(EFFECT_MODEL, model)
        log("workout effect: model $model, $n workouts again")
    }

    /**
     * After a sync, keeps each day's energy row ([org.openstrap.app.data.DailyEnergyHistory]): the first time every day
     * with data, then the recent days, and all of them again when the profile they were worked out with has changed.
     */
    private suspend fun updateDailyEnergy() {
        val profile = settings.profile.first()
        val key = org.openstrap.app.data.DailyEnergyHistory.profileKey(profile)
        val written = org.openstrap.app.data.DailyEnergyHistory(dao).update(profile, profileChanged = settings.lastSync(DAILY_ENERGY_PROFILE) != key)
        settings.setLastSync(DAILY_ENERGY_PROFILE, key)
        if (written > 0) log("daily energy: $written days")
    }

    /** Debug aid, see [HistorySync.dumpRaw]. */
    fun dumpRaw(days: Int, types: Set<Int>? = null) {
        val s = activeSession ?: return log("dump: not connected")
        val client = gatt ?: return
        scope.launch { HistorySync(s, client, dao, settings, sync, ::log).dumpRaw(System.currentTimeMillis() / 1000 - days * 86_400L, java.io.File(context.filesDir, "raw"), types) }
    }

    /** Debug aid: sends [payload] to [endpoint] and logs every reply on it for 5 s. */
    fun probe(endpoint: Int, payload: ByteArray) {
        val s = activeSession ?: return log("probe: not connected")
        scope.launch {
            val listener = launch { s.messagesFor(endpoint).collect { log("probe %04x < %s".format(endpoint, it.toHex())) } }
            // Device-side chunk ACKs on 0016 carry transport errors (e.g. status 5 = unknown endpoint).
            val acks = link?.let { l -> launch { l.controlNotifications.collect { log("probe %04x ack < %s".format(endpoint, it.toHex())) } } }
            log("probe %04x > %s".format(endpoint, payload.toHex()))
            runCatching { s.send(endpoint, payload) }.onFailure { log("probe failed: $it") }
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
            reconnectJob?.cancel()
            connectJob?.cancel()
            connection.value = ConnectionState.Connected(battery, "simulated")
            // As a real connection does: the service carries the notifications.
            runCatching { StrapService.start(context) }.onFailure { log("service start failed: $it") }
            var steps = 1_000
            var second = 0
            for ((bpm, seconds) in script) repeat(seconds) {
                onLiveHr(bpm)
                if (++second % 60 == 0) { steps += stepsPerMinute; todaySteps.value = StepTotals(steps, null, null) }
                delay(1_000)
            }
            liveHeartRate.value = null
            connection.value = ConnectionState.Disconnected
            log("simulation over")
        }
    }

    override fun setLiveHeartRate(enabled: Boolean) {
        liveHeartRateEnabled.value = enabled
        scope.launch { settings.setLiveHeartRate(enabled) }
        refreshLive()
    }

    /** Live HR streams only while the app is on screen, where it's shown, or while a workout records it. */
    @Volatile private var appVisible = false
    @Volatile private var workoutActive = false

    @Volatile private var effortWatch = false

    private fun liveWanted() = workoutActive || effortWatch || (liveHeartRateEnabled.value && appVisible)

    /** Starts or stops streaming to match [liveWanted]; a stream already running is left alone. */
    private fun refreshLive() {
        if (!liveWanted()) {
            if (liveJob?.isActive == true) pauseLive()
        } else if (liveJob?.isActive != true && !firmwareUpdating.value && connection.value is ConnectionState.Connected) {
            startLive()
        }
    }

    override fun setAppVisible(visible: Boolean) {
        if (visible == appVisible) return
        appVisible = visible
        refreshLive()
    }

    override fun setWorkoutActive(active: Boolean) {
        if (active == workoutActive) return
        workoutActive = active
        log(if (active) "workout started: live hr stays on in the background" else "workout ended")
        refreshLive()
    }

    override fun setEffortWatch(active: Boolean) {
        if (active == effortWatch) return
        effortWatch = active
        refreshLive()
    }

    /** Stops streaming without changing the user's preference (e.g. during a firmware update). */
    private fun pauseLive() {
        liveJob?.cancel()
        liveHeartRate.value = null
        val s = session
        val client = gatt
        scope.launch { stopLive(s, client) }
    }

    private fun startLive() {
        liveJob?.cancel()
        liveHeartRate.value = null
        val s = activeSession ?: return
        val client = gatt ?: return
        liveJob = scope.launch {
            try {
                // Collect from both the channel push and the standard 2A37 characteristic; de-duplicate by second.
                var pushes = 0
                launch {
                    s.messagesFor(Endpoints.HEART_RATE).collect { p ->
                        if (pushes++ == 0) log("live hr 0048 < ${p.toHex()}")
                        LiveHeartRate.parsePush(p)?.let { onLiveHr(it) }
                    }
                }
                // Each keep-alive is answered with `05 02`; only log the first reply and anything else.
                var opens = 0
                launch { s.messagesFor(Endpoints.OPEN_APP).collect { p -> if (opens++ == 0 || !p.contentEquals(KEEP_ALIVE_REPLY)) log("live hr 001d < ${p.toHex()}") } }
                var measurements = 0
                launch {
                    client.notifications.filter { it.characteristic == StrapUuids.HR_MEASUREMENT }
                        .collect { n ->
                            if (measurements++ == 0) log("live hr 2a37 < ${n.value.toHex()}")
                            LiveHeartRate.parseMeasurement(n.value)?.let { onLiveHr(it) }
                        }
                }
                runCatching { client.enableNotifications(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_MEASUREMENT) }
                    .onFailure { log("2A37 subscribe failed: $it") }

                val channel = s.services.has(Endpoints.HEART_RATE)
                if (channel) {
                    log("live hr open: ${s.request(Endpoints.OPEN_APP, LiveHeartRate.OPEN, 5_000) { it.u8(0) == 0x05 }.toHex()}")
                    log("live hr subscribe: ${s.request(Endpoints.HEART_RATE, LiveHeartRate.SUBSCRIBE, 5_000) { it.u8(0) == 0x04 }.toHex()}")
                } else {
                    client.write(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_CONTROL, LiveHeartRate.LEGACY_START, withResponse = true)
                    log("live hr started via 2A39")
                }
                while (isActive) {
                    delay(LiveHeartRate.KEEP_ALIVE_MS)
                    if (channel) s.send(Endpoints.OPEN_APP, LiveHeartRate.KEEP_ALIVE)
                    else client.write(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_CONTROL, LiveHeartRate.LEGACY_KEEP_ALIVE, withResponse = true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("live hr failed: $e")
                liveHeartRate.value = null
            }
        }
    }

    private suspend fun stopLive(s: ChannelSession?, client: GattClient?) {
        runCatching {
            if (s != null && s.services.has(Endpoints.HEART_RATE)) {
                s.send(Endpoints.OPEN_APP, LiveHeartRate.CLOSE)
                s.send(Endpoints.HEART_RATE, LiveHeartRate.UNSUBSCRIBE)
            } else {
                client?.write(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_CONTROL, LiveHeartRate.LEGACY_STOP, withResponse = true)
            }
        }.onFailure { log("live hr stop failed: $it") }
    }

    /** Sends a settings-style command and returns the reply whose first byte is the command + 1 (or that [matches]). */
    private suspend fun command(
        endpoint: Int,
        request: ByteArray,
        timeoutMs: Long = 8_000,
        matches: (ByteArray) -> Boolean = { it.isNotEmpty() && it.u8(0) == request.u8(0) + 1 },
    ): ByteArray {
        val s = activeSession ?: throw StrapCommandException(tr(R.string.common_the_strap_isn_t))
        if (firmwareUpdating.value) throw StrapCommandException(tr(R.string.strap_manager_wait_for_the_firmware))
        val reply = runCatching {
            s.request(endpoint, request, timeoutMs, matches = matches)
        }.getOrElse { throw StrapCommandException(tr(R.string.strap_manager_the_strap_didn_t)) }
        log("cmd %04x > %s < %s".format(endpoint, request.toHex(), reply.toHex()))
        return reply
    }

    private var alarmCaps: AlarmCapabilities? = null

    private suspend fun alarmCaps(): AlarmCapabilities = alarmCaps ?: (
        AlarmCodec.parseVersion(command(Endpoints.ALARMS, AlarmCodec.versionRequest))
            ?: throw StrapCommandException(tr(R.string.strap_manager_the_strap_sent_an))
        ).also { alarmCaps = it }

    override suspend fun readAlarms(): Pair<AlarmCapabilities, List<Alarm>> {
        val caps = alarmCaps()
        val list = AlarmCodec.parseList(command(Endpoints.ALARMS, AlarmCodec.listRequest), caps.titles)
            ?: throw StrapCommandException(tr(R.string.strap_manager_the_strap_sent_an_2))
        return caps to list
    }

    override suspend fun saveAlarm(alarm: Alarm, isNew: Boolean) {
        val caps = alarmCaps()
        val request = if (isNew) AlarmCodec.add(alarm, caps.titles, caps.workdaysSupported) else AlarmCodec.modify(alarm, caps.titles, caps.workdaysSupported)
        if (!AlarmCodec.isSuccess(request, command(Endpoints.ALARMS, request))) throw StrapCommandException(tr(R.string.strap_manager_the_strap_didn_t_2))
    }

    override suspend fun deleteAlarm(index: Int) {
        val request = AlarmCodec.delete(index)
        if (!AlarmCodec.isSuccess(request, command(Endpoints.ALARMS, request))) throw StrapCommandException(tr(R.string.strap_manager_the_strap_didn_t_3))
    }

    override suspend fun clearAlarms() {
        val request = AlarmCodec.clearAllRequest
        if (!AlarmCodec.isSuccess(request, command(Endpoints.ALARMS, request))) throw StrapCommandException(tr(R.string.strap_manager_the_strap_didn_t_4))
    }

    /** Learned on connect; null until then or when the strap didn't answer (then replies are read as v1–3). */
    @Volatile private var settingsProtocol: SettingsProtocol? = null

    private suspend fun settingsProtocol(): SettingsProtocol? = settingsProtocol
        ?: runCatching { SettingsCodec.parseVersion(command(Endpoints.SETTINGS, SettingsCodec.versionRequest)) }.getOrNull()
            ?.also { settingsProtocol = it }

    /** A settings query; a `04` push for another group arriving meanwhile isn't taken as its reply (03 §3.6.1). */
    private suspend fun settingsQuery(group: Int, keys: List<Int> = emptyList(), withOptions: Boolean = true): ByteArray =
        command(Endpoints.SETTINGS, SettingsCodec.query(group, keys, withOptions)) { SettingsCodec.isReplyTo(group, it) }

    override suspend fun readHealthSettings(): SettingsGroup {
        val protocol = settingsProtocol()
        val reply = settingsQuery(SettingsCodec.GROUP_HEALTH)
        return SettingsCodec.parse(reply, protocol?.version) ?: throw StrapCommandException(tr(R.string.strap_manager_the_strap_couldn_t, SettingsCodec.replyCode(reply)))
    }

    override suspend fun writeHealthSettings(version: Int, settings: List<Setting>) {
        val reply = command(Endpoints.SETTINGS, SettingsCodec.write(SettingsCodec.GROUP_HEALTH, version, settings))
        if (!SettingsCodec.isWriteSuccess(reply)) throw StrapCommandException(tr(R.string.strap_manager_the_strap_didn_t_5, reply.getOrNull(1)))
    }

    /** Writes GENERAL key 45 = true with the group version just read (07 §0). The strap may drop the link before its ack. */
    override suspend fun restartStrap() {
        val group = SettingsCodec.parse(settingsQuery(SettingsCodec.GROUP_GENERAL, listOf(SettingsCodec.KEY_RESTART), withOptions = false), settingsProtocol()?.version)
            ?: throw StrapCommandException(tr(R.string.strap_manager_the_strap_couldn_t_2))
        val request = SettingsCodec.restartFor(group) ?: throw StrapCommandException(tr(R.string.strap_manager_this_strap_can_t))
        log("restart: asking the strap to restart")
        val reply = runCatching { command(Endpoints.SETTINGS, request) }
            .getOrElse { e -> if (activeSession == null) { log("restart: link dropped before the ack"); return } else throw e }
        if (!SettingsCodec.isWriteSuccess(reply)) throw StrapCommandException(tr(R.string.strap_manager_the_strap_didn_t_6, reply.getOrNull(1)))
    }

    /**
     * Every buzz goes through [buzzLock], one command at a time, and a ringing call wins: while [callHold] is set,
     * notification and workout buzzes are dropped. Zepp has no such guard on the phone; its watches sort calls from
     * notifications in firmware (both arrive on 0x001E), and the strap has no 0x001E.
     */
    private val buzzLock = Mutex()
    @Volatile private var callHold = false
    /** When the last motor pattern ends: one still playing then cuts off a `03` sent meanwhile (seen 2026-10-20). */
    @Volatile private var motorBusyUntil = 0L
    private var pulseJob: Job? = null

    override suspend fun buzz(pattern: List<Int>) {
        if (callHold) return
        buzzLock.withLock {
            if (callHold) return
            if (activeSession?.services?.has(Endpoints.MOTOR_VIBRATE) == true) {
                playMotor(pattern)
                return
            }
            // No motor module: find-device's `03` keeps buzzing until stopped (fw 3.25, 2026-10-20), so time it by hand.
            findBuzz()
            delay(FindDevice.SHORT_BUZZ_MS)
            sendFindDevice(FindDevice.STOP)
        }
    }

    private suspend fun playMotor(pattern: List<Int>) {
        if (!MotorVibrate.isOk(command(Endpoints.MOTOR_VIBRATE, MotorVibrate.play(pattern)))) throw StrapCommandException(tr(R.string.strap_manager_the_strap_didn_t_7))
        motorBusyUntil = SystemClock.elapsedRealtime() + pattern.sum()
    }

    /**
     * Steady is find-device's `03` until [stopCallBuzz]. Pulsed plays [CALL_PULSE] on the motor once a second instead,
     * so it ends within one pulse of the stop; a strap without the motor module gets the steady buzz.
     */
    override suspend fun startCallBuzz(pulsed: Boolean) {
        callHold = true
        buzzLock.withLock {
            if (!callHold) return
            val wait = motorBusyUntil - SystemClock.elapsedRealtime()
            if (wait > 0) {
                log("call buzz: waiting $wait ms for a motor pattern to end")
                delay(wait + MOTOR_SETTLE_MS)
            }
            // Checked again right before sending, as Zepp does: the call may have been answered meanwhile.
            if (!callHold) return
            if (pulsed && activeSession?.services?.has(Endpoints.MOTOR_VIBRATE) == true) {
                // A steady buzz still on (a preview switching over) is ended first: pulses cutting it off leave the
                // strap ignoring the next `03`.
                if (findBuzzOn) {
                    sendFindDevice(FindDevice.STOP)
                    delay(BUZZ_RESET_MS)
                }
                log("call buzz: pulsing")
                pulseJob?.cancel()
                pulseJob = scope.launch { pulse() }
                return
            }
            // A pulsed buzz still running (a preview just switched to steady) would cut the steady one off.
            pulseJob?.cancel()
            pulseJob = null
            // Logged before the send too, so a slow reply shows apart from a late send.
            log("call buzz: sending 03")
            findBuzz()
        }
    }

    /** True from an answered `03` until a `06` goes out or the strap reports `07`. */
    @Volatile private var findBuzzOn = false

    /**
     * `03`, tried again once after a `06`: after a motor pattern cut a `03` off, the strap sometimes ignored the next
     * `03` until it got a `06` (fw 3.25, 2026-10-28). Waiting the usual 8 s for it held up every buzz behind it.
     */
    private suspend fun findBuzz() {
        val reply = runCatching { command(Endpoints.FIND_DEVICE, FindDevice.BUZZ, BUZZ_REPLY_MS) }.getOrElse {
            log("buzz: no answer to 03, sending 06 and trying again")
            sendFindDevice(FindDevice.STOP)
            delay(BUZZ_RESET_MS)
            command(Endpoints.FIND_DEVICE, FindDevice.BUZZ, BUZZ_REPLY_MS)
        }
        if (!FindDevice.isBuzzOk(reply)) throw StrapCommandException(tr(R.string.strap_manager_the_strap_didn_t_7))
        findBuzzOn = true
    }

    private suspend fun pulse() {
        val start = SystemClock.elapsedRealtime()
        var n = 0L
        while (callHold) {
            buzzLock.withLock { if (callHold) runCatching { playMotor(CALL_PULSE) }.onFailure { if (callHold) log("call pulse failed: $it") } }
            n++
            delay((start + n * CALL_PULSE_PERIOD_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    /** Ends the call buzz either way, and Find strap with it. Queued behind a start still in flight, so it can't be overtaken. */
    override suspend fun stopCallBuzz() {
        callHold = false
        pulseJob?.cancel()
        pulseJob = null
        stopBuzzing()
    }

    /**
     * `03`, not the `05 01` Zepp uses for its continuous find: on fw 3.25 `03` buzzes until `06`, while `05 01` doesn't
     * vibrate at all (2026-10-21).
     */
    private suspend fun startBuzzing() = buzzLock.withLock { findBuzz() }

    /** `06`. Any stop ends Find strap too (a ringing call ending, Stop in the app), so it can't wait out the timeout. */
    suspend fun stopBuzzing() {
        endFind()
        buzzLock.withLock { sendFindDevice(FindDevice.STOP) }
    }

    override val findingStrap = MutableStateFlow(false)
    private var findJob: Job? = null

    /** The same `03` … `06` as a ringing call, with Zepp's 60 s limit (03 §3.16). */
    override suspend fun findStrap() {
        startBuzzing()
        log("find strap: buzzing")
        findingStrap.value = true
        findJob?.cancel()
        findJob = scope.launch {
            delay(FindDevice.FIND_TIMEOUT_MS)
            log("find strap: timed out")
            // Cleared first, so stopBuzzing's endFind doesn't cancel this job before the STOP goes out.
            findJob = null
            runCatching { stopBuzzing() }
        }
    }

    override suspend fun stopFindingStrap() = stopBuzzing()

    private fun endFind() {
        findJob?.cancel()
        findJob = null
        findingStrap.value = false
    }

    /** STOP gets no reply (03 §3.16), so it is only sent. */
    private suspend fun sendFindDevice(request: ByteArray) {
        val s = activeSession ?: throw StrapCommandException(tr(R.string.common_the_strap_isn_t))
        if (firmwareUpdating.value) throw StrapCommandException(tr(R.string.strap_manager_wait_for_the_firmware))
        log("cmd %04x > %s".format(Endpoints.FIND_DEVICE, request.toHex()))
        if (request.contentEquals(FindDevice.STOP)) findBuzzOn = false
        runCatching { s.send(Endpoints.FIND_DEVICE, request) }.getOrElse { throw StrapCommandException(tr(R.string.strap_manager_the_strap_didn_t)) }
    }

    /** GENERAL key 47; null when the strap doesn't report it. Zepp refuses OTA while it is on (07 §1.6). */
    private suspend fun fastRechargeOn(): Boolean? = runCatching {
        val reply = settingsQuery(SettingsCodec.GROUP_GENERAL, listOf(SettingsCodec.KEY_FAST_RECHARGE), withOptions = false)
        SettingsCodec.parse(reply, settingsProtocol()?.version)?.get(SettingsCodec.KEY_FAST_RECHARGE)?.bool
    }.onFailure { log("fast-charge query failed: $it") }.getOrNull()

    override suspend fun installFirmware(file: ByteArray, type: Int, onProgress: (DfuProgress) -> Unit) {
        val client = gatt
        if (client == null || connection.value !is ConnectionState.Connected) throw DfuException(-1, tr(R.string.common_the_strap_isn_t))
        if (sync.value is SyncState.Running) throw DfuException(-1, tr(R.string.strap_manager_wait_for_the_sync))
        if (firmwareUpdating.value) throw DfuException(-1, tr(R.string.strap_manager_an_update_is_already))
        if (session?.services?.has(Endpoints.SETTINGS) == true && fastRechargeOn() == true) throw DfuException(-1, tr(R.string.strap_manager_turn_off_fast_charge))
        if (!firmwareUpdating.compareAndSet(false, true)) throw DfuException(-1, tr(R.string.strap_manager_an_update_is_already))
        val wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenStrap:firmware").apply { acquire(30 * 60_000L) }
        try {
            if (liveJob?.isActive == true) pauseLive()
            val link = GattDfuLink(client)
            if (!link.available) throw DfuException(-1, tr(R.string.strap_manager_this_strap_doesn_t))
            // Bigger ATT packets make the transfer several times faster; fall back to 20-byte writes if refused.
            runCatching { client.requestMtu(247) }.onFailure { log("dfu: mtu request failed: $it") }
            log("dfu: ${file.size} bytes, type ${"%02x".format(type)}, crc ${"%08x".format(crc32(file))}, att mtu ${client.mtu}")
            link.enable()
            refreshGattOnConnect = true
            DfuSession(link, ::log).flash(file, type, DfuCommands.MODE_NORMAL, onProgress)
            log("dfu: installed, strap is restarting")
        } catch (e: Exception) {
            log("dfu failed: ${e.message}")
            throw e
        } finally {
            firmwareUpdating.value = false
            if (wake.isHeld) wake.release()
        }
    }

    /** Shown live, and recorded during a workout; the strap also records per-second HR itself and history sync stores it. */
    private fun onLiveHr(bpm: Int) {
        liveHeartRate.value = bpm
        heartRateReadings.tryEmit(bpm)
    }

    private companion object {
        val RETRY_SECONDS = longArrayOf(5, 15, 30, 60, 120, 300)
        const val MIN_SYNC_GAP_SECONDS = 15 * 60L
        const val WEAR_POLL_MS = 60_000L
        /** Settings key: the local midnight of the last morning a body-signal alert was pushed. */
        const val BODY_SIGNALS_PUSHED = "body_signals_push"
        /** [org.openstrap.app.data.DailyEnergyHistory.profileKey] of the profile the daily energy rows were last written with. */
        const val DAILY_ENERGY_PROFILE = "daily_energy_profile"
        /** The [org.openstrap.app.data.WorkoutEffect.MODEL] phone workouts' effects were last worked out with. */
        const val EFFECT_MODEL = "workout_effect_model"
        const val AUTO_SYNC_SECONDS = 60 * 60L
        val KEEP_ALIVE_REPLY = byteArrayOf(0x05, 0x02)
        /** The pulsed call buzz: 600 ms on the motor, once a second. */
        val CALL_PULSE = listOf(600)
        const val CALL_PULSE_PERIOD_MS = 1_000L
        /** Margin after a motor pattern's own length before a call's `03`. */
        const val MOTOR_SETTLE_MS = 150L
        /** How long a `03` waits for its answer before the `06` and second try; answers took 0.1 to 3 s. */
        const val BUZZ_REPLY_MS = 3_000L
        /** Pause after a `06` before the next buzz. */
        const val BUZZ_RESET_MS = 300L
    }
}
