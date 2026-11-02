package org.openstrap.app.ui.device

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import org.openstrap.app.AppContainer
import org.openstrap.app.ble.FoundDevice
import org.openstrap.app.data.catching
import org.openstrap.app.data.settings.PairedDevice
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.StrapManager
import org.openstrap.app.presentation.Units
import org.openstrap.app.zepp.BoundDevice
import org.openstrap.app.zepp.DeviceListing

data class ScanUiState(
    val scanning: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    val error: String? = null,
)

class DeviceViewModel(private val container: AppContainer) : ViewModel() {
    val strap: StrapController get() = container.strap
    val companion: org.openstrap.app.device.CompanionLink get() = container.companion
    val paired: StateFlow<PairedDevice?> =
        container.settings.pairedDevice.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val profile: StateFlow<org.openstrap.app.data.settings.UserProfile> =
        container.settings.profile.stateIn(viewModelScope, SharingStarted.Eagerly, org.openstrap.app.data.settings.UserProfile())

    fun saveProfile(p: org.openstrap.app.data.settings.UserProfile) = viewModelScope.launch { container.settings.saveProfile(p) }

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
            container.scanner.scan()
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
            container.settings.savePairedDevice(PairedDevice(mac, key, name))
            container.strap.connect()
            container.companion.refresh().join()
            if (container.companion.supported && !container.companion.associated.value) _offerBackground.value = true
        }
    }

    /** Keeps the Zepp sign-in for firmware checks and, when Zepp returned the strap's key, pairs with it straight away. */
    fun signInAndPair(listing: DeviceListing, strap: BoundDevice?) = viewModelScope.launch {
        container.settings.saveZeppAccount(listing.session.toAccount(listing.apiBase, strap?.china == true, System.currentTimeMillis()))
        val key = strap?.authKey
        if (strap != null && key != null) pair(strap.macAddress, DEFAULT_NAME, key)
        else _message.value = "Zepp sign-in saved, but no auth key came with it. Get the key on a computer and enter it by hand."
    }

    fun loadSampleData() = viewModelScope.launch {
        org.openstrap.app.data.SampleData.load(container.database.healthDao())
        _message.value = "Loaded a week of sample data."
    }

    /** Set at the end of pairing: the strap page then asks Android to associate the strap, once. */
    private val _offerBackground = MutableStateFlow(false)
    val offerBackground = _offerBackground.asStateFlow()
    fun backgroundOffered() { _offerBackground.value = false }

    val companionDeclined: StateFlow<Boolean> =
        container.settings.companionDeclined.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun showMessage(text: String) { _message.value = text }

    /** Find strap: buzzes it until stopped or Zepp's 60 s are up. */
    fun findStrap() = viewModelScope.launch {
        catching { container.strap.findStrap() }.onFailure { failed("find strap", it, ErrorCopy.strap(it, "Couldn't buzz the strap")) }
    }

    fun stopFindingStrap() = viewModelScope.launch {
        catching { container.strap.stopFindingStrap() }
            .onFailure { failed("stop finding strap", it, ErrorCopy.strap(it, "Couldn't stop the strap buzzing")) }
    }

    /** Restarts the strap; the link drops and the usual reconnect brings it back. */
    fun restartStrap() = viewModelScope.launch {
        _message.value = catching {
            container.strap.restartStrap()
            "Restarting the strap. OpenStrap reconnects when it's back."
        }
            .getOrElse { failed("restart strap", it, ErrorCopy.strap(it, "Couldn't restart the strap")) }
    }

    fun forget() = viewModelScope.launch {
        container.strap.disconnect()
        paired.value?.let { container.companion.disassociate(it.address) }
        container.settings.forgetDevice()
    }

    fun exportCsv(uri: Uri) = viewModelScope.launch {
        _message.value = catching {
            withContext(Dispatchers.IO) {
                val settings = org.openstrap.app.export.SettingsBackup.toJson(container.settings.backupValues())
                val out = container.app.contentResolver.openOutputStream(uri) ?: throw FileNotFoundException("no output stream for $uri")
                out.use { container.csv.exportZip(it, settings = settings) }
            }
            "Saved a copy."
        }.getOrElse { failed("save a copy", it, ErrorCopy.saveCopy(it)) }
    }

    /**
     * Restores a zip from [exportCsv] or automatic backup into what this phone is missing ([CsvImporter]), and the settings in
     * it that this phone hasn't set. Safe to run twice.
     */
    fun importCsv(uri: Uri) = viewModelScope.launch {
        _message.value = catching {
            val result = withContext(Dispatchers.IO) {
                val input = container.app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("no input stream for $uri")
                input.use { org.openstrap.app.export.CsvImporter(container.database).importZip(it) }
            }
            // A damaged settings file costs the settings only, not the records already restored.
            val settings = result.settings?.let { json ->
                catching { container.settings.restoreValues(org.openstrap.app.export.SettingsBackup.fromJson(json)) }
                    .onFailure { log("restore: settings not restored: $it") }
                    .getOrDefault(0)
            } ?: 0
            BackupCopy.restored(result.total, settings > 0, result.skipped)
        }.getOrElse { failed("restore a copy", it, ErrorCopy.restoreCopy(it)) }
    }

    val backup: StateFlow<org.openstrap.app.data.settings.BackupState> = container.settings.backup
        .stateIn(viewModelScope, SharingStarted.Eagerly, org.openstrap.app.data.settings.BackupState(null, null, null, null, null))

    /** True while Back up now runs. */
    private val _backingUp = MutableStateFlow(false)
    val backingUp = _backingUp.asStateFlow()

    /**
     * Automatic backup into the folder [uri] the user just picked: keeps the right to write there across restarts, lets
     * go of the folder before, starts the daily backup and makes the first one now.
     */
    fun chooseBackupFolder(uri: Uri) = viewModelScope.launch {
        val resolver = container.app.contentResolver
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val kept = runCatching { resolver.takePersistableUriPermission(uri, flags) }
        kept.onFailure { log("backup: folder permission not kept: $it") }
        if (kept.isFailure) {
            _message.value = "OpenStrap can't keep writing to that folder. Choose another one."
            return@launch
        }
        val before = backup.value.folder
        if (before != null && before != uri.toString()) releaseFolder(before)
        val name = withContext(Dispatchers.IO) { runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(container.app, uri)?.name }.getOrNull() }
        container.settings.setBackupFolder(uri.toString(), name)
        org.openstrap.app.export.AutoBackup.schedule(container.app)
        backUpNow(quiet = true)
    }

    /** Stops automatic backup and lets go of its folder. The backups already there stay. */
    fun turnOffBackup() = viewModelScope.launch {
        org.openstrap.app.export.AutoBackup.cancel(container.app)
        backup.value.folder?.let(::releaseFolder)
        container.settings.clearBackup()
    }

    private fun releaseFolder(folder: String) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { container.app.contentResolver.releasePersistableUriPermission(Uri.parse(folder), flags) }
    }

    /** One backup now, the daily one's way. During a workout it waits for the end instead. [quiet] says only a failure. */
    fun backUpNow(quiet: Boolean = false) = viewModelScope.launch {
        if (_backingUp.value) return@launch
        _backingUp.value = true
        try {
            val outcome = org.openstrap.app.export.AutoBackup(container.app, container).run()
            if (outcome == org.openstrap.app.export.AutoBackup.Outcome.Recording) org.openstrap.app.export.AutoBackup.afterWorkout(container.app)
            if (!quiet || outcome !is org.openstrap.app.export.AutoBackup.Outcome.Done) _message.value = BackupCopy.outcome(outcome)
        } finally {
            _backingUp.value = false
        }
    }

    /** Brings back what OpenStrap exported to Health Connect, filling gaps only. */
    fun importHealthConnect() = viewModelScope.launch {
        _message.value = catching {
            val n = withContext(Dispatchers.IO) { container.healthConnect.import() }
            if (n == 0) "Health Connect has no OpenStrap data to restore."
            else "Restored ${Units.count(n.toLong())} records from Health Connect."
        }.getOrElse { failed("health connect import", it, ErrorCopy.healthConnect("Couldn't restore from Health Connect", it)) }
    }

    fun exportHealthConnect() = viewModelScope.launch {
        _message.value = catching {
            val now = System.currentTimeMillis() / 1000
            val n = container.healthConnect.export(now - 30L * 24 * 3600, now)
            container.settings.setHealthConnectSynced(now)
            "Wrote $n records to Health Connect (last 30 days)."
        }.getOrElse { failed("health connect export", it, ErrorCopy.healthConnect("Couldn't write to Health Connect", it)) }
    }

    /** Auto sync to Health Connect, for the You page's row. */
    val healthConnectAuto: StateFlow<org.openstrap.app.data.settings.HealthConnectAuto> =
        container.settings.healthConnectAuto.stateIn(viewModelScope, SharingStarted.Eagerly, org.openstrap.app.data.settings.HealthConnectAuto())

    /** The auto sync switch. On is called once the write permissions are granted, and writes the last 30 days at once. */
    fun setAutoHealthConnect(on: Boolean) = viewModelScope.launch {
        if (!on) { container.settings.setHealthConnectAuto(false); return@launch }
        _message.value = catching {
            val n = container.autoHealthConnect.turnOn()
            "Auto sync is on. Wrote $n records to Health Connect (last 30 days)."
        }.getOrElse {
            failed("health connect auto sync", it, ErrorCopy.healthConnect("Auto sync is on, but the first copy didn't work", it))
        }
    }

    /** The You page opened: auto sync turns off, saying why, if Health Connect or its permissions went away. */
    fun checkAutoHealthConnect() = viewModelScope.launch {
        catching { container.autoHealthConnect.check() }.onFailure { log("health connect: auto sync check failed: $it") }
    }

    /** The raw failure goes to Diagnostics, and [copy], what the screen says, comes back. */
    private fun failed(what: String, e: Throwable, copy: String): String {
        log("$what failed: $e")
        return copy
    }

    private fun log(message: String) {
        android.util.Log.w("OpenStrap", message)
        (container.strap as? StrapManager)?.log(message)
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
