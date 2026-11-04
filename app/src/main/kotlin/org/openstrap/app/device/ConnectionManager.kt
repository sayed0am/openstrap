package org.openstrap.app.device

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.GattException
import org.openstrap.app.ble.GattStrapLink
import org.openstrap.app.data.catching
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.auth.AuthException
import org.openstrap.protocol.hexToBytes

/** The current link, read only, for the parts of [StrapManager] that talk to the strap. Written by [ConnectionManager]. */
internal interface LiveLink {
    /** The channel session, from the GATT connect on, so during setup too. */
    val session: ChannelSession?
    val gatt: GattClient?
    val link: GattStrapLink?

    /** [session] once connected. */
    val activeSession: ChannelSession?
}

/**
 * The connection's life: connect, auth, reconnect with backoff, Disconnect and the user's hold on it, and Bluetooth
 * going off and on. What happens once authenticated is [StrapManager]'s, through [Hooks].
 */
// The connect, reconnect and disconnect steps, each a small function on the lane.
@Suppress("TooManyFunctions")
internal class ConnectionManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val hooks: Hooks,
) : LiveLink {
    /** What the facade does at each stage of a connection. All run on the connect job, or on the lane. */
    interface Hooks {
        /** Reads what the app needs after auth. A failed step is logged and setup goes on. */
        suspend fun setUp(client: GattClient, link: GattStrapLink, session: ChannelSession)

        /** The state to show once [setUp] is done. */
        fun connectedState(): ConnectionState.Connected

        /** Starts this connection's work. [job] is the connect job: listeners launched in it end with the connection. */
        suspend fun onConnected(job: CoroutineScope, session: ChannelSession)

        /** The link ended, lost or by Disconnect. */
        fun onLinkEnded()
    }

    /**
     * The one lane that changes the connection's fields ([connectJob], [reconnectJob], [wantConnected], [retries],
     * [gatt], [session], [link]). The connect job runs on it too, so two connects can't interleave and leave a second
     * GATT client open (audit C5). Others only read those fields.
     */
    @Suppress("InjectDispatcher")
    private val lane = ((scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default)
        .limitedParallelism(1)

    private fun onLane(block: suspend CoroutineScope.() -> Unit) = scope.launch(lane, block = block)

    val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    /** Set before a firmware install: the strap's services can change, so re-discover on the next connect. */
    @Volatile var refreshGattOnConnect = false

    // Written on [lane] only. Volatile, as other threads read them.
    @Volatile private var connectJob: Job? = null
    @Volatile private var reconnectJob: Job? = null
    /** True while the user wants the strap connected; cleared by [disconnect]. */
    @Volatile private var wantConnected = false
    private var retries = 0
    /** Set when a connect failed in a way retrying can't fix (no permission, a bad address): presence events don't retry. */
    @Volatile private var unusable = false
    @Volatile override var gatt: GattClient? = null
        private set
    @Volatile override var session: ChannelSession? = null
        private set
    @Volatile override var link: GattStrapLink? = null
        private set

    override val activeSession: ChannelSession? get() = session?.takeIf { connection.value is ConnectionState.Connected }

    /** Connect if a strap is paired and nothing is connected or in progress. Safe to call often. */
    // On the lane, and checking connectJob: callers race at process start (app start-up, the companion presence
    // service, phone alerts), before the connect coroutine has moved the state off Disconnected.
    fun autoConnect() {
        onLane {
            when (connection.value) {
                is ConnectionState.Connected, ConnectionState.Connecting, ConnectionState.Authenticating -> return@onLane
                else -> Unit
            }
            if (reconnectJob?.isActive == true || connectJob?.isActive == true) return@onLane
            hold(false)
            wantConnection()
        }
    }

    /** The user's Disconnect, kept across process restarts; null until read from settings. See [backgroundConnect]. */
    @Volatile private var held: Boolean? = null
    // One at a time, in order, so a quick Disconnect then Connect can't land the other way round.
    // A lane of its own for the hold writes, so they can't queue behind the strap's traffic on the app scope.
    @Suppress("InjectDispatcher")
    private val heldWriter = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun hold(value: Boolean) {
        if (held == value) return
        held = value
        heldWriter.launch { settings.setHeldDisconnected(value) }
    }

    fun backgroundConnect() {
        scope.launch {
            // Presence events repeat every few seconds while the strap advertises, so a skip isn't logged.
            if (held ?: settings.heldDisconnected.first().also { held = it }) return@launch
            // Every try would fail the same way until the user acts (grants the permission, pairs again).
            if (unusable) return@launch
            autoConnect()
        }
    }

    fun connect() {
        hold(false)
        onLane { wantConnection() }
    }

    /** On the lane. */
    private fun wantConnection() {
        wantConnected = true
        unusable = false
        reconnectJob?.cancel()
        startConnect()
    }

    /** On the lane. */
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
        reconnectJob = onLane {
            delay(wait * MILLIS_PER_SECOND)
            startConnect()
        }
    }

    /** On the lane, so the cancel and the new job are one step that nothing else can come between. */
    // The catch-all rethrows whenever this job is cancelled, by asking the job rather than the exception's type (C1).
    @Suppress("SuspendFunSwallowedCancellation", "TooGenericExceptionCaught")
    private fun startConnect() {
        connectJob?.cancel()
        connectJob = onLane {
            val device = settings.pairedDevice.first()
            if (device == null) {
                connection.value = ConnectionState.NotPaired
                return@onLane
            }
            catching { StrapService.start(context) }.onFailure { log("service start failed: $it") }
            connection.value = ConnectionState.Connecting
            // A client left over from a lost link still holds one of Android's few GATT client slots.
            gatt?.close()
            val client = GattClient(context, device.address, log).also { gatt = it }
            try {
                log("connecting to ${device.address}")
                client.connect(refreshCache = refreshGattOnConnect)
                refreshGattOnConnect = false
                log("connected, services discovered, mtu=${client.mtu}")
                watchDisconnect(client)

                val l = GattStrapLink(client).also { link = it }
                l.enableChannelNotifications()
                val s = ChannelSession(l, log).also { session = it }
                s.start(this)

                connection.value = ConnectionState.Authenticating
                s.handshake(device.authKeyHex.hexToBytes())

                hooks.setUp(client, l, s)
                // setUp swallows step failures, so a link that dropped during it would otherwise show as Connected.
                if (!client.connected.value) throw GattException("Link lost during setup")
                connection.value = hooks.connectedState()
                retries = 0
                hooks.onConnected(this, s)
            } catch (e: AuthException) {
                log("auth failed: ${e.message}")
                connection.value = PairingErrors.authFailure(e)
                close()
            } catch (e: Exception) {
                // Asks the coroutine, not the exception: a timeout is a failure to report, only a cancelled job unwinds.
                if (!currentCoroutineContext().isActive) {
                    // Whoever cancelled this (Disconnect, a newer connect, the lost link) owns the state now. A client
                    // it didn't take over is closed here, so it can't keep a GATT slot.
                    if (gatt !== client) client.close()
                    throw e
                }
                log("connect failed: $e")
                connection.value = ConnectionState.Failed(ConnectErrors.reason(e))
                close()
                if (ConnectErrors.isFinal(e)) {
                    unusable = true
                    log("not retrying: ${e.message}")
                } else {
                    scheduleReconnect()
                }
            }
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
                android.bluetooth.BluetoothAdapter.STATE_ON -> onLane {
                    if (!wantConnected || connection.value is ConnectionState.Connected || connectJob?.isActive == true) return@onLane
                    log("bluetooth back on, reconnecting")
                    retries = 0
                    reconnectJob?.cancel()
                    startConnect()
                }
            }
        }
    }

    init {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.registerReceiver(
                context, bluetoothChanged, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            context.registerReceiver(bluetoothChanged, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        }
    }

    private fun watchDisconnect(client: GattClient) = onLane {
        client.connected.filter { !it }.first()
        if (gatt === client) {
            log("link lost")
            // Ends this connection's listeners, and a setup still in progress, so it can't go on to report Connected.
            connectJob?.cancel()
            hooks.onLinkEnded()
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

    fun disconnect() {
        hold(true)
        onLane {
            wantConnected = false
            reconnectJob?.cancel()
            connectJob?.cancel()
            hooks.onLinkEnded()
            close()
            connection.value = ConnectionState.Disconnected
        }
    }

    /** For the emulator's simulated strap: no strap is paired there, and a reconnect loop would knock the fake one offline. */
    suspend fun stopTrying() = withContext(lane) {
        reconnectJob?.cancel()
        connectJob?.cancel()
    }

    /** On the lane. */
    private fun close() {
        session?.stop()
        session = null
        // Clear the field first, so watchDisconnect sees the drop as ours and doesn't reconnect.
        val g = gatt
        gatt = null
        g?.close()
    }

    private companion object {
        val RETRY_SECONDS = longArrayOf(5, 15, 30, 60, 120, 300)
        const val MILLIS_PER_SECOND = 1_000L
    }
}
