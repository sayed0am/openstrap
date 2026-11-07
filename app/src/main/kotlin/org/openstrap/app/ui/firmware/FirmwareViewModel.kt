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
import org.openstrap.app.R
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
import org.openstrap.app.i18n.Strings
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
    private val cloud = FirmwareCloud(cacheDir)

    /** The words of its steps and failures, in res/values/strings_you_firmware.xml. Read on each use, so they follow the app's language. */
    private val strings: Strings get() = Strings.app

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
        val acc = account.value ?: return fail(strings.get(R.string.you_firmware_no_account))
        val info = strap.info.value
        val firmware = info?.firmware ?: return fail(strings.get(R.string.you_firmware_no_version))
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
            }.getOrElse { failed("firmware check", it, strings.get(R.string.you_firmware_check_failed)) }
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
        val type = part.type ?: return fail(strings.format(R.string.you_firmware_unknown_part, part.prefix))
        _step.value = FirmwareStep.Downloading(part, 0f)
        viewModelScope.launch {
            _step.value = catching {
                val bytes = cloud.download(part) { done, total ->
                    if (total > 0) _step.value = FirmwareStep.Downloading(part, done.toFloat() / total)
                }
                val trailer = HmfwTrailer.read(bytes)
                FirmwareStep.Ready(
                    ReadyImage(
                        bytes, type, strings.format(R.string.you_firmware_part_title, part.label, part.version), part.version,
                        fromCloud = true,
                        trailer, trailerWarnings(bytes, trailer, type),
                    ),
                )
            }.getOrElse { failed("firmware download", it, strings.get(R.string.you_firmware_download_failed)) }
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
                return@catching FirmwareStep.Failed(strings.plural(R.plurals.you_firmware_file_size, bytes.size, bytes.size))
            }
            val trailer = HmfwTrailer.read(bytes)
            val warnings = buildList {
                add(strings.get(R.string.you_firmware_file_untrusted))
                if (trailer == null) add(strings.get(R.string.you_firmware_file_unsigned))
                addAll(trailerWarnings(bytes, trailer, FirmwareType.FIRMWARE))
            }
            FirmwareStep.Ready(
                ReadyImage(
                    bytes, FirmwareType.FIRMWARE, strings.get(R.string.you_firmware_file_title), null, fromCloud = false, trailer, warnings,
                ),
            )
        }.getOrElse {
            log("firmware: file not read: $it")
            FirmwareStep.Failed(strings.get(R.string.you_firmware_file_unread))
        }
    }

    /** Problems that make an image look wrong for this strap (docs/protocol/03 §2.2, advisory). */
    private fun trailerWarnings(bytes: ByteArray, trailer: HmfwTrailer?, type: Int): List<String> = buildList {
        if (trailer == null || type != FirmwareType.FIRMWARE) return@buildList
        if (trailer.pnpId != StrapIdentity.PRODUCT_ID) {
            add(strings.format(R.string.you_firmware_wrong_product, trailer.pnpId, StrapIdentity.PRODUCT_ID))
        }
        if (trailer.fwSize > bytes.size) {
            // The plural only needs the count's form, and a size past Int's range reads as many.
            val count = trailer.fwSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            add(strings.plural(R.plurals.you_firmware_wrong_size, count, trailer.fwSize))
        }
    }

    /** Problems that must be fixed before installing. */
    fun blockers(): List<String> = buildList {
        val c = strap.connection.value
        if (c !is ConnectionState.Connected) add(strings.get(R.string.you_firmware_block_connect))
        else {
            val battery = c.batteryPercent
            if (battery == null) add(strings.get(R.string.you_firmware_block_battery_unknown))
            else if (battery <= MIN_BATTERY) add(strings.format(R.string.you_firmware_block_battery, MIN_BATTERY, battery))
        }
        if (strap.info.value?.dfuAvailable == false) add(strings.get(R.string.system_firmware_no_service))
    }

    fun install(image: ReadyImage) {
        if (!UPDATES_ENABLED) return fail(strings.get(R.string.you_firmware_install_failed))
        val blocking = blockers()
        if (blocking.isNotEmpty()) return fail(blocking.joinToString(" "))
        _step.value = FirmwareStep.Installing(strings.get(R.string.you_firmware_step_starting), null)
        viewModelScope.launch {
            _step.value = catching {
                strap.installFirmware(image.bytes, image.type) { p ->
                    _step.value = when (p) {
                        is DfuProgress.Transferring -> FirmwareStep.Installing(
                            strings.get(R.string.you_firmware_step_sending), p.sent.toFloat() / p.total,
                        )
                        DfuProgress.Validating -> FirmwareStep.Installing(strings.get(R.string.you_firmware_step_validating), null)
                        DfuProgress.Applying -> FirmwareStep.Installing(strings.get(R.string.you_firmware_step_applying), null)
                        DfuProgress.Done -> FirmwareStep.Installing(strings.get(R.string.you_firmware_step_restarting), 1f)
                    }
                }
                FirmwareStep.Installed
            }.getOrElse { failed("firmware install", it, strings.get(R.string.you_firmware_install_failed)) }
        }
    }

    private fun fail(message: String) { _step.value = FirmwareStep.Failed(message) }

    /** The raw failure goes to Diagnostics, and the screen says it in plain words ([ErrorCopy.firmware]). */
    private fun failed(what: String, e: Throwable, fallback: String): FirmwareStep.Failed {
        log("$what failed: $e")
        return FirmwareStep.Failed(ErrorCopy.firmware(e, fallback))
    }

    private fun log(message: String) {
        android.util.Log.w("unstrap", message)
        (strap as? StrapManager)?.log(message)
    }

    companion object {
        /** Zepp's upgrade page refuses at this level or below (07 §1.1). */
        const val MIN_BATTERY = 30

        /**
         * Off for now: since firmware 3.27.0.1 the strap's own sleep result is empty or wrong on nights it was worn
         * (2026-11-07), so no more strap updates until that is understood. The Strap page shows the version without
         * opening this page, and [install] refuses.
         */
        const val UPDATES_ENABLED = false
    }
}
