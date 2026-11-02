package org.openstrap.app.ui.firmware

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import org.openstrap.app.AppContainer
import org.openstrap.app.data.catching
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.data.settings.ZeppAccount
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.StrapInfo
import org.openstrap.app.device.StrapManager
import org.openstrap.app.firmware.FirmwareCloud
import org.openstrap.app.firmware.FirmwareCloudException
import org.openstrap.app.firmware.FirmwarePart
import org.openstrap.app.firmware.StrapIdentity
import org.openstrap.app.firmware.StrapResources
import org.openstrap.app.firmware.UpdateCheck
import org.openstrap.app.ui.device.ErrorCopy
import org.openstrap.app.zepp.BoundDevice
import org.openstrap.app.zepp.DeviceListing
import org.openstrap.app.zepp.ZeppCloud
import org.openstrap.protocol.firmware.DfuProgress
import org.openstrap.protocol.firmware.FirmwareType
import org.openstrap.protocol.firmware.HmfwTrailer

/** A firmware image that has been fetched and checked, waiting for the user to confirm the install. */
data class ReadyImage(
    val bytes: ByteArray,
    val type: Int,
    val title: String,
    val version: String?,
    val fromCloud: Boolean,
    val trailer: HmfwTrailer?,
    /** Things the user must read before installing; empty for a matching cloud image. */
    val warnings: List<String>,
)

sealed interface FirmwareStep {
    data object Idle : FirmwareStep
    data object Checking : FirmwareStep
    data class Checked(val result: UpdateCheck) : FirmwareStep
    data class Downloading(val part: FirmwarePart, val fraction: Float) : FirmwareStep
    data class Ready(val image: ReadyImage) : FirmwareStep
    data class Installing(val label: String, val fraction: Float?) : FirmwareStep
    data object Installed : FirmwareStep
    data class Failed(val message: String) : FirmwareStep
}

/**
 * The Firmware and Zepp account pages: checking for, downloading and installing strap firmware, and the Zepp sign-in
 * the check needs. [cacheDir] holds downloads, and [openFile] reads a file the user picked.
 */
class FirmwareViewModel(
    private val strap: StrapController,
    private val settings: SettingsStore,
    cacheDir: File,
    private val openFile: (Uri) -> InputStream?,
) : ViewModel() {
    constructor(container: AppContainer) : this(
        container.strap, container.settings, container.app.cacheDir, { container.app.contentResolver.openInputStream(it) },
    )

    private val cloud = FirmwareCloud(cacheDir)

    /** The strap's link, for what the page offers. */
    val connection: StateFlow<ConnectionState> get() = strap.connection

    /** What the strap said about itself, its firmware among it, once connected. */
    val info: StateFlow<StrapInfo?> get() = strap.info

    val account: StateFlow<ZeppAccount?> =
        settings.zeppAccount.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _step = MutableStateFlow<FirmwareStep>(FirmwareStep.Idle)
    val step = _step.asStateFlow()

    fun saveAccount(token: String, userId: String, apiBase: String, china: Boolean) = viewModelScope.launch {
        settings.saveZeppAccount(
            ZeppAccount(token.trim(), userId.trim(), apiBase.trim().ifEmpty { ZeppAccount.DEFAULT_API_BASE }, china),
        )
    }

    fun saveSignIn(listing: DeviceListing, strap: BoundDevice?) = viewModelScope.launch {
        settings.saveZeppAccount(
            listing.session.toAccount(listing.apiBase, strap?.china ?: account.value?.china ?: false, System.currentTimeMillis()),
        )
    }

    fun clearAccount() = viewModelScope.launch { settings.clearZeppAccount() }

    fun reset() { if (!busy) _step.value = FirmwareStep.Idle }

    private val busy get() = _step.value.let { it is FirmwareStep.Checking || it is FirmwareStep.Downloading || it is FirmwareStep.Installing }

    fun check() {
        val acc = account.value ?: return fail("Add your Zepp app token first.")
        val info = strap.info.value
        val firmware = info?.firmware ?: return fail("Connect the strap first so its firmware version is known.")
        _step.value = FirmwareStep.Checking
        viewModelScope.launch {
            _step.value = catching {
                val identity = StrapIdentity(
                    firmwareVersion = firmware,
                    hardwareVersion = info.hardware,
                    serial = info.serial,
                    deviceSource = if (acc.china) StrapIdentity.DEVICE_SOURCE_CHINA else StrapIdentity.DEVICE_SOURCE_GLOBAL,
                    resources = StrapResources.from(info.deviceInformation),
                )
                var current = renewed(acc, rejected = false)
                val result = try {
                    cloud.check(current.apiBase, current.appToken, current.userId, identity)
                } catch (e: FirmwareCloudException) {
                    if (!e.rejected || !ZeppCloud.needsAppTokenRefresh(current, System.currentTimeMillis(), rejected = true)) throw e
                    current = renewed(current, rejected = true)
                    cloud.check(current.apiBase, current.appToken, current.userId, identity)
                }
                FirmwareStep.Checked(result)
            }.getOrElse { failed("firmware check", it, "Update check failed") }
        }
    }

    /**
     * [acc] with a fresh app token when Zepp would renew it (A3, codemap 08 §1.4), saved for next time. A renewal
     * the server [rejected] the old token for must succeed; an early one that fails keeps the old token.
     */
    private suspend fun renewed(acc: ZeppAccount, rejected: Boolean): ZeppAccount {
        if (!ZeppCloud.needsAppTokenRefresh(acc, System.currentTimeMillis(), rejected)) return acc
        val fresh = if (rejected) ZeppCloud.refreshAppToken(acc) else catching { ZeppCloud.refreshAppToken(acc) }
            .onFailure { log("firmware: early token renewal failed, keeping the old one: $it") }
            .getOrNull() ?: return acc
        settings.saveZeppAccount(fresh)
        return fresh
    }

    /** Downloads the first part in install order and stages it. Further parts follow after the strap restarts. */
    fun download(part: FirmwarePart) {
        val type = part.type ?: return fail("Unknown part type ${part.prefix}")
        _step.value = FirmwareStep.Downloading(part, 0f)
        viewModelScope.launch {
            _step.value = catching {
                val bytes = cloud.download(part) { done, total ->
                    if (total > 0) _step.value = FirmwareStep.Downloading(part, done.toFloat() / total)
                }
                val trailer = HmfwTrailer.read(bytes)
                FirmwareStep.Ready(
                    ReadyImage(bytes, type, "${part.label} ${part.version}", part.version, fromCloud = true, trailer, trailerWarnings(bytes, trailer, type)),
                )
            }.getOrElse { failed("firmware download", it, "Download failed") }
        }
    }

    /** Stages a file the user picked. Only plain firmware images are accepted from files. */
    fun loadFile(uri: Uri) = viewModelScope.launch {
        _step.value = catching {
            val bytes = withContext(Dispatchers.IO) {
                val input = openFile(uri) ?: throw FileNotFoundException("no input stream for $uri")
                input.use { it.readBytes() }
            }
            if (bytes.size !in 1024..16 * 1024 * 1024) {
                return@catching FirmwareStep.Failed("That file is ${bytes.size} bytes, which isn't a plausible firmware image.")
            }
            val trailer = HmfwTrailer.read(bytes)
            val warnings = buildList {
                add("This file didn't come from Zepp's update server for this strap. Only install files you trust.")
                if (trailer == null) add("The file has no HMFW signature block. It's probably not a strap firmware image.")
                addAll(trailerWarnings(bytes, trailer, FirmwareType.FIRMWARE))
            }
            FirmwareStep.Ready(ReadyImage(bytes, FirmwareType.FIRMWARE, "Firmware from file", null, fromCloud = false, trailer, warnings))
        }.getOrElse {
            log("firmware: file not read: $it")
            FirmwareStep.Failed("Couldn't read the file. Choose it again.")
        }
    }

    /** Problems that make an image look wrong for this strap (docs/protocol/03 §2.2, advisory). */
    private fun trailerWarnings(bytes: ByteArray, trailer: HmfwTrailer?, type: Int): List<String> = buildList {
        if (trailer == null || type != FirmwareType.FIRMWARE) return@buildList
        if (trailer.pnpId != StrapIdentity.PRODUCT_ID) {
            add("The image says it's for product ${trailer.pnpId}, but this strap is product ${StrapIdentity.PRODUCT_ID}.")
        }
        if (trailer.fwSize > bytes.size) add("The image's size field (${trailer.fwSize}) is larger than the file.")
    }

    /** Problems that must be fixed before installing. */
    fun blockers(): List<String> = buildList {
        val c = strap.connection.value
        if (c !is ConnectionState.Connected) add("Connect the strap.")
        else {
            val battery = c.batteryPercent
            if (battery == null) add("Battery level unknown. Reconnect the strap.")
            else if (battery <= MIN_BATTERY) add("Charge the strap above $MIN_BATTERY% (now $battery%).")
        }
        if (strap.info.value?.dfuAvailable == false) add("This strap doesn't offer the firmware update service.")
    }

    fun install(image: ReadyImage) {
        val blocking = blockers()
        if (blocking.isNotEmpty()) return fail(blocking.joinToString(" "))
        _step.value = FirmwareStep.Installing("Starting…", null)
        viewModelScope.launch {
            _step.value = catching {
                strap.installFirmware(image.bytes, image.type) { p ->
                    _step.value = when (p) {
                        is DfuProgress.Transferring -> FirmwareStep.Installing("Sending to strap", p.sent.toFloat() / p.total)
                        DfuProgress.Validating -> FirmwareStep.Installing("Strap is checking the image", null)
                        DfuProgress.Applying -> FirmwareStep.Installing("Strap is installing", null)
                        DfuProgress.Done -> FirmwareStep.Installing("Restarting", 1f)
                    }
                }
                FirmwareStep.Installed
            }.getOrElse { failed("firmware install", it, "Install failed") }
        }
    }

    private fun fail(message: String) { _step.value = FirmwareStep.Failed(message) }

    /** The raw failure goes to Diagnostics, and the screen says it in plain words ([ErrorCopy.firmware]). */
    private fun failed(what: String, e: Throwable, fallback: String): FirmwareStep.Failed {
        log("$what failed: $e")
        return FirmwareStep.Failed(ErrorCopy.firmware(e, fallback))
    }

    private fun log(message: String) {
        android.util.Log.w("OpenStrap", message)
        (strap as? StrapManager)?.log(message)
    }

    companion object {
        /** Zepp's upgrade page refuses at this level or below (07 §1.1). */
        const val MIN_BATTERY = 30
    }
}
