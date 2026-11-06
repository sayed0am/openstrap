package org.openstrap.app.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.StrapUuids
import org.openstrap.app.data.catching
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.LiveHeartRate
import org.openstrap.protocol.toHex
import org.openstrap.protocol.u8

/**
 * Live heart rate from the strap, on the channel push and the standard 2A37 characteristic. It streams only while
 * the app is on screen, a workout records it, or workout auto-start watches for effort ([wanted]). There is no switch
 * for it, as in Zepp: the strap's own measuring is set by its monitor frequency.
 */
// One setter per reason to stream, as StrapController has them, plus start, stop and the reading.
@Suppress("TooManyFunctions")
internal class LiveHeartRateStream(
    private val scope: CoroutineScope,
    private val link: LiveLink,
    private val connection: StateFlow<ConnectionState>,
    private val firmwareUpdating: StateFlow<Boolean>,
    private val log: (String) -> Unit,
) {
    val liveHeartRate = MutableStateFlow<Int?>(null)
    val heartRateReadings = MutableSharedFlow<Int>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var liveJob: Job? = null

    /** Live HR streams only while the app is on screen, where it's shown, or while a workout records it. */
    @Volatile private var appVisible = false
    @Volatile private var workoutActive = false

    @Volatile private var effortWatch = false

    private fun wanted() = workoutActive || effortWatch || appVisible

    val isRunning: Boolean get() = liveJob?.isActive == true

    fun setAppVisible(visible: Boolean) {
        if (visible == appVisible) return
        appVisible = visible
        refresh()
    }

    fun setWorkoutActive(active: Boolean) {
        if (active == workoutActive) return
        workoutActive = active
        log(if (active) "workout started: live hr stays on in the background" else "workout ended")
        refresh()
    }

    fun setEffortWatch(active: Boolean) {
        if (active == effortWatch) return
        effortWatch = active
        refresh()
    }

    /** A new connection: starts streaming if [wanted]. */
    fun onConnected() {
        if (wanted()) start()
    }

    /** The link ended: the stream with it. */
    fun onLinkEnded() {
        liveJob?.cancel()
        liveHeartRate.value = null
    }

    /** Starts or stops streaming to match [wanted]; a stream already running is left alone. */
    private fun refresh() {
        if (!wanted()) {
            if (liveJob?.isActive == true) pause()
        } else if (liveJob?.isActive != true && !firmwareUpdating.value && connection.value is ConnectionState.Connected) {
            start()
        }
    }

    /** Stops streaming for now (e.g. during a firmware update); [refresh] starts it again when it's wanted. */
    fun pause() {
        liveJob?.cancel()
        liveHeartRate.value = null
        val s = link.session
        val client = link.gatt
        scope.launch { stop(s, client) }
    }

    // The catch-all rethrows a cancellation, and a failed stream only stops showing live HR.
    @Suppress("SuspendFunSwallowedCancellation", "TooGenericExceptionCaught")
    private fun start() {
        liveJob?.cancel()
        liveHeartRate.value = null
        val s = link.activeSession ?: return
        val client = link.gatt ?: return
        liveJob = scope.launch {
            try {
                listen(s, client)
                val channel = s.services.has(Endpoints.HEART_RATE)
                if (channel) {
                    val opened = s.request(Endpoints.OPEN_APP, LiveHeartRate.OPEN, OPEN_TIMEOUT_MS) { it.u8(0) == OPEN_REPLY }
                    log("live hr open: ${opened.toHex()}")
                    val subscribed = s.request(Endpoints.HEART_RATE, LiveHeartRate.SUBSCRIBE, OPEN_TIMEOUT_MS) {
                        it.u8(0) == SUBSCRIBE_REPLY
                    }
                    log("live hr subscribe: ${subscribed.toHex()}")
                } else {
                    client.write(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_CONTROL, LiveHeartRate.LEGACY_START, withResponse = true)
                    log("live hr started via 2A39")
                }
                while (isActive) {
                    delay(LiveHeartRate.KEEP_ALIVE_MS)
                    if (channel) {
                        s.send(Endpoints.OPEN_APP, LiveHeartRate.KEEP_ALIVE)
                    } else {
                        val keepAlive = LiveHeartRate.LEGACY_KEEP_ALIVE
                        client.write(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_CONTROL, keepAlive, withResponse = true)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("live hr failed: $e")
                liveHeartRate.value = null
            }
        }
    }

    /** Collects from both the channel push and the standard 2A37 characteristic, in this stream's job. */
    private suspend fun CoroutineScope.listen(s: ChannelSession, client: GattClient) {
        var pushes = 0
        launch {
            s.messagesFor(Endpoints.HEART_RATE).collect { p ->
                if (pushes++ == 0) log("live hr 0048 < ${p.toHex()}")
                LiveHeartRate.parsePush(p)?.let { onReading(it) }
            }
        }
        // Each keep-alive is answered with `05 02`; only log the first reply and anything else.
        var opens = 0
        launch {
            s.messagesFor(Endpoints.OPEN_APP).collect { p ->
                if (opens++ == 0 || !p.contentEquals(KEEP_ALIVE_REPLY)) log("live hr 001d < ${p.toHex()}")
            }
        }
        var measurements = 0
        launch {
            client.notifications.filter { it.characteristic == StrapUuids.HR_MEASUREMENT }
                .collect { n ->
                    if (measurements++ == 0) log("live hr 2a37 < ${n.value.toHex()}")
                    LiveHeartRate.parseMeasurement(n.value)?.let { onReading(it) }
                }
        }
        catching { client.enableNotifications(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_MEASUREMENT) }
            .onFailure { log("2A37 subscribe failed: $it") }
    }

    private suspend fun stop(s: ChannelSession?, client: GattClient?) {
        catching {
            if (s != null && s.services.has(Endpoints.HEART_RATE)) {
                s.send(Endpoints.OPEN_APP, LiveHeartRate.CLOSE)
                s.send(Endpoints.HEART_RATE, LiveHeartRate.UNSUBSCRIBE)
            } else {
                client?.write(StrapUuids.HEART_RATE_SERVICE, StrapUuids.HR_CONTROL, LiveHeartRate.LEGACY_STOP, withResponse = true)
            }
        }.onFailure { log("live hr stop failed: $it") }
    }

    /** Shown live, and recorded during a workout; the strap also records per-second HR itself and history sync stores it. */
    fun onReading(bpm: Int) {
        liveHeartRate.value = bpm
        heartRateReadings.tryEmit(bpm)
    }

    private companion object {
        val KEEP_ALIVE_REPLY = byteArrayOf(0x05, 0x02)
        const val OPEN_TIMEOUT_MS = 5_000L
        const val OPEN_REPLY = 0x05
        const val SUBSCRIBE_REPLY = 0x04
    }
}
