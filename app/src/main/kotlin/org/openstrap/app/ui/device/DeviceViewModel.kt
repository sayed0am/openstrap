package org.openstrap.app.ui.device

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openstrap.app.ble.FoundDevice
import org.openstrap.app.data.BackupRepository
import org.openstrap.app.data.BackupResult
import org.openstrap.app.data.catching
import org.openstrap.app.data.settings.PairedDevice
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.CompanionLink
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.StrapInfo
import org.openstrap.app.device.StrapManager
import org.openstrap.app.device.SyncState
import org.openstrap.app.export.AutoBackup
import org.openstrap.app.health.HealthConnectAutoSync
import org.openstrap.app.health.HealthConnectExporter
import org.openstrap.app.presentation.Units
import org.openstrap.app.zepp.BoundDevice
import org.openstrap.app.zepp.DeviceListing

data class ScanUiState(
    val scanning: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    val error: String? = null,
)

/**
 * The Strap page and the You page's data rows: pairing, the strap's commands, its phone settings, backup and Health
 * Connect. The screens only read state and call functions here, so nothing they start dies with the composition
 * (audit A2), and the strap itself stays private.
 */
// Each parameter is one area of the two pages. Wave 5 splits the pages' view models, and with them this list.
@Suppress("LongParameterList")
class DeviceViewModel(
    private val settings: SettingsStore,
    private val strap: StrapController,
    private val companion: CompanionLink,
    private val scanStraps: () -> Flow<FoundDevice>,
    private val backups: BackupRepository,
    private val autoBackup: AutoBackup,
    private val healthConnect: HealthConnectExporter,
    private val autoHealthConnect: HealthConnectAutoSync,
    private val sampleData: suspend () -> Unit,
) : ViewModel() {
    val paired: StateFlow<PairedDevice?> =
        settings.pairedDevice.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val profile: StateFlow<org.openstrap.app.data.settings.UserProfile> =
        settings.profile.stateIn(viewModelScope, SharingStarted.Eagerly, org.openstrap.app.data.settings.UserProfile())

    fun saveProfile(p: org.openstrap.app.data.settings.UserProfile) = viewModelScope.launch { settings.saveProfile(p) }

    val connection: StateFlow<ConnectionState> get() = strap.connection
    val sync: StateFlow<SyncState> get() = strap.sync
    val info: StateFlow<StrapInfo?> get() = strap.info
    val findingStrap: StateFlow<Boolean> get() = strap.findingStrap

    fun connect() = strap.connect()
    fun disconnect() = strap.disconnect()
    fun syncNow() = strap.syncNow()

    /** The strap's settings kept on the phone, as the Strap page's rows show them. */
    val buzzOnCalls: StateFlow<Boolean> = settings.buzzOnCalls.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val buzzOnNotifications: StateFlow<Boolean> = settings.buzzOnNotifications.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val wearReminder: StateFlow<Boolean> = settings.wearReminder.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val autoStartWorkouts: StateFlow<Boolean> = settings.autoStartWorkouts.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setWearReminder(on: Boolean) = viewModelScope.launch { settings.setWearReminder(on) }
    fun setAutoStartWorkouts(on: Boolean) = viewModelScope.launch { settings.setAutoStartWorkouts(on) }

    private val _scan = MutableStateFlow(ScanUiState())
    val scan = _scan.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private var scanJob: Job? = null

    fun startScan() {
        scanJob?.cancel()
        _scan.value = ScanUiState(scanning = true)
        scanJob = viewModelScope.launch {
            scanStraps()
                .catch { e -> _scan.update { it.copy(scanning = false, error = e.message) } }
                .collect { d -> _scan.update { s -> s.copy(found = (s.found.filter { it.address != d.address } + d).sortedByDescending { it.rssi }) } }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _scan.update { it.copy(scanning = false) }
    }

    fun pair(address: String, name: String, authKey: String) {
        val key = normalizeAuthKey(authKey)
        if (key == null) {
            val n = authKey.trim().removePrefix("0x").removePrefix("0X").count { it.isLetterOrDigit() }
            _message.value = "The auth key must be 32 characters of 0 to 9 and a to f. You entered $n."
            return
        }
        // Trimmed once, as the sheet's own check does, so a pasted address with a trailing space pairs (audit E7).
        val mac = pairAddress(address)
        if (mac == null) {
            _message.value = "The Bluetooth address should look like C8:0F:10:AB:CD:EF. Scan to fill it in."
            return
        }
        stopScan()
        viewModelScope.launch {
            settings.savePairedDevice(PairedDevice(mac, key, name))
            strap.connect()
            companion.refresh().join()
            if (companion.supported && !companion.associated.value) _offerBackground.value = true
        }
    }

    /** Keeps the Zepp sign-in for firmware checks and, when Zepp returned the strap's key, pairs with it straight away. */
    fun signInAndPair(listing: DeviceListing, strap: BoundDevice?) = viewModelScope.launch {
        settings.saveZeppAccount(listing.session.toAccount(listing.apiBase, strap?.china == true, System.currentTimeMillis()))
        val key = strap?.authKey
        if (strap != null && key != null) pair(strap.macAddress, DEFAULT_NAME, key)
        else _message.value = "Zepp sign-in saved, but no auth key came with it. Get the key on a computer and enter it by hand."
    }

    fun loadSampleData() = viewModelScope.launch {
        sampleData()
        _message.value = "Loaded a week of sample data."
    }

    /** Set at the end of pairing: the strap page then asks Android to associate the strap, once. */
    private val _offerBackground = MutableStateFlow(false)
    val offerBackground = _offerBackground.asStateFlow()
    fun backgroundOffered() { _offerBackground.value = false }

    val companionDeclined: StateFlow<Boolean> =
        settings.companionDeclined.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** "Stay connected in background": whether Android can associate the strap, and whether it is, or battery optimization is off. */
    val companionSupported: Boolean get() = companion.supported
    val companionAssociated: StateFlow<Boolean> get() = companion.associated
    val ignoringBatteryOptimizations: StateFlow<Boolean> get() = companion.ignoringBatteryOptimizations

    /** Asks Android to associate the strap at [address]. [launch] shows its prompt from [activity]. */
    fun associate(activity: Activity, address: String, launch: (IntentSender) -> Unit) =
        companion.associate(activity, address, launch, onError = ::showMessage)

    /** The association prompt's answer. Turned down, the page says what else keeps the strap connected. */
    fun associationResult(resultCode: Int) {
        if (!companion.onResult(resultCode)) showMessage("Not linked. You can turn off battery optimization for OpenStrap instead.")
    }

    fun showMessage(text: String) { _message.value = text }

    /** Find strap: buzzes it until stopped or Zepp's 60 s are up. */
    fun findStrap() = viewModelScope.launch {
        catching { strap.findStrap() }.onFailure { failed("find strap", it, ErrorCopy.strap(it, "Couldn't buzz the strap")) }
    }

    fun stopFindingStrap() = viewModelScope.launch {
        catching { strap.stopFindingStrap() }
            .onFailure { failed("stop finding strap", it, ErrorCopy.strap(it, "Couldn't stop the strap buzzing")) }
    }

    /** Restarts the strap; the link drops and the usual reconnect brings it back. */
    fun restartStrap() = viewModelScope.launch {
        _message.value = catching {
            strap.restartStrap()
            "Restarting the strap. OpenStrap reconnects when it's back."
        }
            .getOrElse { failed("restart strap", it, ErrorCopy.strap(it, "Couldn't restart the strap")) }
    }

    fun forget() = viewModelScope.launch {
        strap.disconnect()
        paired.value?.let { companion.disassociate(it.address) }
        settings.forgetDevice()
    }

    fun exportCsv(uri: Uri) = viewModelScope.launch {
        _message.value = when (val result = backups.exportCsv(uri)) {
            is BackupResult.Failed -> failed("save a copy", result.error, ErrorCopy.saveCopy(result.error))
            else -> "Saved a copy."
        }
    }

    /**
     * Restores a zip from [exportCsv] or automatic backup into what this phone is missing, and the settings in it that
     * this phone hasn't set. Safe to run twice.
     */
    fun importCsv(uri: Uri) = viewModelScope.launch {
        _message.value = when (val result = backups.importCsv(uri)) {
            is BackupResult.Restored -> BackupCopy.restored(result.total, result.settings, result.skipped)
            is BackupResult.Failed -> failed("restore a copy", result.error, ErrorCopy.restoreCopy(result.error))
            BackupResult.Saved -> null
        }
    }

    val backup: StateFlow<org.openstrap.app.data.settings.BackupState> = settings.backup
        .stateIn(viewModelScope, SharingStarted.Eagerly, org.openstrap.app.data.settings.BackupState(null, null, null, null, null))

    /** True while Back up now runs. */
    private val _backingUp = MutableStateFlow(false)
    val backingUp = _backingUp.asStateFlow()

    /**
     * Automatic backup into the folder [uri] the user just picked: keeps the right to write there across restarts, lets
     * go of the folder before, starts the daily backup and makes the first one now.
     */
    fun chooseBackupFolder(uri: Uri) = viewModelScope.launch {
        if (!autoBackup.chooseFolder(uri)) {
            _message.value = "OpenStrap can't keep writing to that folder. Choose another one."
            return@launch
        }
        backUpNow(quiet = true)
    }

    /** Stops automatic backup and lets go of its folder. The backups already there stay. */
    fun turnOffBackup() = viewModelScope.launch { autoBackup.turnOff() }

    /** One backup now, the daily one's way. During a workout it waits for the end instead. [quiet] says only a failure. */
    fun backUpNow(quiet: Boolean = false) = viewModelScope.launch {
        if (_backingUp.value) return@launch
        _backingUp.value = true
        try {
            val outcome = autoBackup.runNow()
            if (!quiet || outcome !is AutoBackup.Outcome.Done) _message.value = BackupCopy.outcome(outcome)
        } finally {
            _backingUp.value = false
        }
    }

    /** Brings back what OpenStrap exported to Health Connect, filling gaps only. */
    fun importHealthConnect() = viewModelScope.launch {
        _message.value = catching {
            val n = withContext(Dispatchers.IO) { healthConnect.import() }
            if (n == 0) "Health Connect has no OpenStrap data to restore."
            else "Restored ${Units.count(n.toLong())} records from Health Connect."
        }.getOrElse { failed("health connect import", it, ErrorCopy.healthConnect("Couldn't restore from Health Connect", it)) }
    }

    fun exportHealthConnect() = viewModelScope.launch {
        _message.value = catching {
            val now = System.currentTimeMillis() / 1000
            val n = healthConnect.export(now - 30L * 24 * 3600, now)
            settings.setHealthConnectSynced(now)
            "Wrote $n records to Health Connect (last 30 days)."
        }.getOrElse { failed("health connect export", it, ErrorCopy.healthConnect("Couldn't write to Health Connect", it)) }
    }

    /** Auto sync to Health Connect, for the You page's row. */
    val healthConnectAuto: StateFlow<org.openstrap.app.data.settings.HealthConnectAuto> =
        settings.healthConnectAuto.stateIn(viewModelScope, SharingStarted.Eagerly, org.openstrap.app.data.settings.HealthConnectAuto())

    /** The auto sync switch. On is called once the write permissions are granted, and writes the last 30 days at once. */
    fun setAutoHealthConnect(on: Boolean) = viewModelScope.launch {
        if (!on) { settings.setHealthConnectAuto(false); return@launch }
        _message.value = catching {
            val n = autoHealthConnect.turnOn()
            "Auto sync is on. Wrote $n records to Health Connect (last 30 days)."
        }.getOrElse {
            failed("health connect auto sync", it, ErrorCopy.healthConnect("Auto sync is on, but the first copy didn't work", it))
        }
    }

    /** The You page opened: auto sync turns off, saying why, if Health Connect or its permissions went away. */
    fun checkAutoHealthConnect() = viewModelScope.launch {
        catching { autoHealthConnect.check() }.onFailure { log("health connect: auto sync check failed: $it") }
    }

    /** The raw failure goes to Diagnostics, and [copy], what the screen says, comes back. */
    private fun failed(what: String, e: Throwable, copy: String): String {
        log("$what failed: $e")
        return copy
    }

    private fun log(message: String) {
        android.util.Log.w("OpenStrap", message)
        (strap as? StrapManager)?.log(message)
    }

    companion object {
        const val DEFAULT_NAME = "Amazfit Helio Strap"
        private val MAC = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        fun isAddress(input: String) = pairAddress(input) != null

        /** [input] as the address to pair with, trimmed and upper case, or null when it isn't a Bluetooth address. */
        fun pairAddress(input: String): String? = input.trim().uppercase().takeIf { MAC.matches(it) }

        /** Accepts "0x…", spaces or colons; returns 32 lowercase hex chars or null. */
        fun normalizeAuthKey(input: String): String? {
            val hex = input.trim().removePrefix("0x").removePrefix("0X").filter { it.isLetterOrDigit() }.lowercase()
            return hex.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        }
    }
}
