package org.openstrap.app.device

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.StateFlow
import org.openstrap.app.i18n.Strings
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.AlarmCapabilities
import org.openstrap.protocol.services.Setting
import org.openstrap.protocol.services.SettingsGroup
import org.openstrap.protocol.services.StepTotals

sealed interface ConnectionState {
    data object NotPaired : ConnectionState
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Authenticating : ConnectionState
    data class Connected(val batteryPercent: Int?, val firmware: String?, val charging: Boolean = false) : ConnectionState
    /** [keyRejected]: the strap refused the auth key, so retrying with the same key can't work. */
    data class Failed(val reason: String, val keyRejected: Boolean = false) : ConnectionState
}

sealed interface SyncState {
    data object Idle : SyncState
    /** [progress] is the share of data types done (0 to 1), when known. */
    data class Running(val step: String, val progress: Float? = null) : SyncState
    data class Done(val atEpochSeconds: Long) : SyncState
    data class Failed(val reason: String) : SyncState
}

/**
 * When syncs last ran and how they ended, for the header chip and Today's stuck sync (spec 4.3). Epoch seconds, null
 * when not seen since the app started. [failingSince] is the first failure after the last good sync, null while
 * syncing works.
 */
data class SyncRecord(val lastSuccess: Long? = null, val lastAttempt: Long? = null, val failingSince: Long? = null) {
    /**
     * The record after the sync state moved from [previous] to [state] at [now]. Each step of a running sync is a new
     * [SyncState.Running], so only the first counts as an attempt. A failure seen without its running state (a state
     * flow can skip one) counts as an attempt of its own.
     */
    fun next(previous: SyncState, state: SyncState, now: Long): SyncRecord = when (state) {
        is SyncState.Running -> if (previous is SyncState.Running) this else copy(lastAttempt = now)
        is SyncState.Done -> SyncRecord(lastSuccess = state.atEpochSeconds, lastAttempt = lastAttempt ?: state.atEpochSeconds)
        is SyncState.Failed -> copy(lastAttempt = if (previous is SyncState.Running) lastAttempt ?: now else now, failingSince = failingSince ?: now)
        SyncState.Idle -> this
    }
}

/**
 * Details read from the strap at connect time. [deviceInformation] is Settings group 0x00, whose resource versions
 * the firmware check sends (codemap 07 §1.4); null when the strap has no settings module or didn't answer.
 */
data class StrapInfo(
    val firmware: String?,
    val hardware: String?,
    val serial: String?,
    val dfuAvailable: Boolean,
    /** The strap lists find device (0x001A), so Find strap can buzz it. */
    val findDevice: Boolean = false,
    val deviceInformation: SettingsGroup? = null,
    /** The strap reports GENERAL key 45, so it can be restarted from the app; Zepp shows Restart only then (codemap 03 §5.2). */
    val canRestart: Boolean = false,
)

/** What the UI can see and ask of the strap. Implemented on top of the BLE stack. */
interface StrapController {
    val connection: StateFlow<ConnectionState>
    val sync: StateFlow<SyncState>

    /** When syncs last ran and how they ended, kept while the app runs, in the background too. */
    val syncRecord: StateFlow<SyncRecord>

    /**
     * Once each time a sync ends, after everything it queued and the workout gap fill, as the widgets refresh: for
     * work that follows a sync ([org.openstrap.app.health.HealthConnectAutoSync]).
     */
    val syncEnded: kotlinx.coroutines.flow.SharedFlow<Unit>

    /** Latest live heart-rate reading in bpm, or null when live HR is off. */
    val liveHeartRate: StateFlow<Int?>

    /**
     * Every live heart-rate reading as it arrives (bpm), repeats included, for recording a workout. [liveHeartRate]
     * drops a reading equal to the one before, and the strap sends each second on two paths, so de-duplicate by time.
     */
    val heartRateReadings: kotlinx.coroutines.flow.SharedFlow<Int>

    /**
     * The strap's live step counter for today, or null when unknown. Taken as sent: on firmware 3.25 the
     * total carries a large constant offset, so check [StepTotals.isPlausible] before showing it directly.
     */
    val todaySteps: StateFlow<StepTotals?>

    /** Versions and capabilities of the connected strap, or null before the first connect. */
    val info: StateFlow<StrapInfo?>

    /** True while a firmware image is being sent; other traffic to the strap is paused. */
    val firmwareUpdating: StateFlow<Boolean>

    /** Recent protocol log lines, shown on the Device screen for troubleshooting. */
    val log: StateFlow<List<String>>

    fun connect()

    /** Connect to the paired strap if not already connected; no-op when nothing is paired. */
    fun autoConnect()

    /**
     * [autoConnect] for reconnects nobody asked for on screen (companion presence, phone alerts, process start):
     * a no-op while the user's Disconnect holds, which lasts until they connect again or open the app.
     */
    fun backgroundConnect()
    fun disconnect()
    fun syncNow()

    /** Called as the app's screen comes to the front or leaves it; live HR only runs while it's visible. */
    fun setAppVisible(visible: Boolean)

    /**
     * While a workout records, live HR streams whatever the switch and the screen say, and starts again after each
     * reconnect.
     */
    fun setWorkoutActive(active: Boolean)

    /** While idle: live HR streams for workout auto-start ([org.openstrap.app.data.AutoStart]) while this is on. */
    fun setEffortWatch(active: Boolean)

    /**
     * Sends [file] to the strap and applies it (docs/protocol/03 §3.4). The strap reboots afterwards and the
     * app reconnects on its own. Throws [org.openstrap.protocol.firmware.DfuException] on any refusal.
     */
    suspend fun installFirmware(file: ByteArray, type: Int, onProgress: (org.openstrap.protocol.firmware.DfuProgress) -> Unit)

    /** The strap's alarms and its alarm limits. Throws [StrapCommandException] when not connected or refused. */
    suspend fun readAlarms(): Pair<AlarmCapabilities, List<Alarm>>

    /** Adds [alarm] when its slot is free on the strap, otherwise replaces that slot. */
    suspend fun saveAlarm(alarm: Alarm, isNew: Boolean)
    suspend fun deleteAlarm(index: Int)
    suspend fun clearAlarms()

    /** Emits when the strap reports that its alarms changed on their own (e.g. a one-time alarm switched itself off). */
    val alarmsChanged: kotlinx.coroutines.flow.SharedFlow<Unit>

    /** The health settings group (HR measuring, HR and SpO2 alerts, …) with the choices the strap allows. */
    suspend fun readHealthSettings(): SettingsGroup

    /**
     * Settings the strap sent in a `04`: its push when something changed on the strap, or a query reply. Only the
     * keys it carries are current; merge with [SettingsGroup.updatedWith].
     */
    val settingsChanged: kotlinx.coroutines.flow.SharedFlow<SettingsGroup>

    /** Writes [settings] into the health group; [version] comes from the last [readHealthSettings]. */
    suspend fun writeHealthSettings(version: Int, settings: List<Setting>)

    /**
     * Restarts the strap (GENERAL key 45). The link drops and the usual reconnect brings it back. Throws
     * [StrapCommandException] when not connected or refused.
     */
    suspend fun restartStrap()

    /**
     * Plays [pattern] on the strap's motor: alternating vibrate and pause milliseconds (a phone notification).
     * Throws [StrapCommandException] when not connected or refused.
     */
    suspend fun buzz(pattern: List<Int>)

    /**
     * Sends a workout state to the strap's sport module (0x0019): START also learns the module and sends the config
     * first. Throws [StrapCommandException] when not connected, refused, or the strap has no such module.
     */
    suspend fun sportSession(state: org.openstrap.protocol.services.SportSession.State, sportType: Int, startTime: Long, elapsedSeconds: Long)

    /** The strap's sport-module keep-alive while a workout is paused, every 5 s. */
    suspend fun sportSessionKeepAlive()

    /** What the strap pushes on its own during a sport session ("heart rate back to normal", a stop). */
    val sportSessionPushes: kotlinx.coroutines.flow.SharedFlow<org.openstrap.protocol.services.SportSession.Push>

    /**
     * Keeps the strap buzzing for a ringing call until [stopCallBuzz]: steady, or [pulsed] on the motor. Notification
     * and workout buzzes ([buzz]) are dropped from now until the stop, so they can't cut it off.
     */
    suspend fun startCallBuzz(pulsed: Boolean)
    suspend fun stopCallBuzz()

    /** True while Find strap is buzzing the strap. */
    val findingStrap: StateFlow<Boolean>

    /**
     * Find strap: buzzes until [stopFindingStrap], the strap reports it stopped, or Zepp's 60 s timeout
     * ([org.openstrap.protocol.services.FindDevice.FIND_TIMEOUT_MS]). Throws [StrapCommandException] when not connected or refused.
     */
    suspend fun findStrap()
    suspend fun stopFindingStrap()
}

/** The strap couldn't be asked, or said no. [message] is fit to show. */
class StrapCommandException(message: String) : Exception(message) {
    /** With a [reason] from res/values/strings_system_strap.xml, in the app's language. */
    constructor(@StringRes reason: Int) : this(Strings.app.get(reason))
}
