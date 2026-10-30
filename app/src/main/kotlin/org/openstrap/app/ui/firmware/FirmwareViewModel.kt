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
import org.openstrap.app.AppContainer
import org.openstrap.app.data.settings.ZeppAccount
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.firmware.FirmwareCloud
import org.openstrap.app.firmware.FirmwareCloudException
import org.openstrap.app.firmware.FirmwarePart
import org.openstrap.app.firmware.StrapIdentity
import org.openstrap.app.firmware.StrapResources
import org.openstrap.app.firmware.UpdateCheck
import org.openstrap.app.zepp.BoundDevice
import org.openstrap.app.zepp.DeviceListing
import org.openstrap.app.zepp.ZeppCloud
import org.openstrap.protocol.firmware.DfuProgress
import org.openstrap.protocol.firmware.FirmwareType
import org.openstrap.protocol.firmware.HmfwTrailer
import org.openstrap.app.R
import org.openstrap.app.tr

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

class FirmwareViewModel(private val container: AppContainer) : ViewModel() {
    private val cloud = FirmwareCloud(container.app.cacheDir)
    val strap get() = container.strap

    val account: StateFlow<ZeppAccount?> =
        container.settings.zeppAccount.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _step = MutableStateFlow<FirmwareStep>(FirmwareStep.Idle)
    val step = _step.asStateFlow()

    fun saveAccount(token: String, userId: String, apiBase: String, china: Boolean) = viewModelScope.launch {
        container.settings.saveZeppAccount(
            ZeppAccount(token.trim(), userId.trim(), apiBase.trim().ifEmpty { ZeppAccount.DEFAULT_API_BASE }, china),
        )
    }

    fun saveSignIn(listing: DeviceListing, strap: BoundDevice?) = viewModelScope.launch {
        container.settings.saveZeppAccount(
            listing.session.toAccount(listing.apiBase, strap?.china ?: account.value?.china ?: false, System.currentTimeMillis()),
        )
    }

    fun clearAccount() = viewModelScope.launch { container.settings.clearZeppAccount() }

    fun reset() { if (!busy) _step.value = FirmwareStep.Idle }

    private val busy get() = _step.value.let { it is FirmwareStep.Checking || it is FirmwareStep.Downloading || it is FirmwareStep.Installing }

    fun check() {
        val acc = account.value ?: return fail(tr(R.string.firmware_view_model_add_your_zepp_app))
        val info = strap.info.value
        val firmware = info?.firmware ?: return fail(tr(R.string.firmware_view_model_connect_the_strap_first))
        _step.value = FirmwareStep.Checking
        viewModelScope.launch {
            _step.value = runCatching {
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
            }.getOrElse { FirmwareStep.Failed(it.message ?: tr(R.string.firmware_view_model_update_check_failed)) }
        }
    }

    /**
     * [acc] with a fresh app token when Zepp would renew it (A3, codemap 08 §1.4), saved for next time. A renewal
     * the server [rejected] the old token for must succeed; an early one that fails keeps the old token.
     */
    private suspend fun renewed(acc: ZeppAccount, rejected: Boolean): ZeppAccount {
        if (!ZeppCloud.needsAppTokenRefresh(acc, System.currentTimeMillis(), rejected)) return acc
        val fresh = if (rejected) ZeppCloud.refreshAppToken(acc) else runCatching { ZeppCloud.refreshAppToken(acc) }.getOrNull() ?: return acc
        container.settings.saveZeppAccount(fresh)
        return fresh
    }

    /** Downloads the first part in install order and stages it. Further parts follow after the strap restarts. */
    fun download(part: FirmwarePart) {
        val type = part.type ?: return fail(tr(R.string.firmware_view_model_unknown_part_type, part.prefix))
        _step.value = FirmwareStep.Downloading(part, 0f)
        viewModelScope.launch {
            _step.value = runCatching {
                val bytes = cloud.download(part) { done, total ->
                    if (total > 0) _step.value = FirmwareStep.Downloading(part, done.toFloat() / total)
                }
                val trailer = HmfwTrailer.read(bytes)
                FirmwareStep.Ready(
                    ReadyImage(bytes, type, "${part.label} ${part.version}", part.version, fromCloud = true, trailer, trailerWarnings(bytes, trailer, type)),
                )
            }.getOrElse { FirmwareStep.Failed(it.message ?: tr(R.string.firmware_view_model_download_failed)) }
        }
    }

    /** Stages a file the user picked. Only plain firmware images are accepted from files. */
    fun loadFile(uri: Uri) = viewModelScope.launch {
        _step.value = runCatching {
            val bytes = withContext(Dispatchers.IO) {
                container.app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            }
            require(bytes.size in 1024..16 * 1024 * 1024) { tr(R.string.firmware_view_model_that_file_is_bytes, bytes.size) }
            val trailer = HmfwTrailer.read(bytes)
            val warnings = buildList {
                add(tr(R.string.firmware_view_model_this_file_didn_t))
                if (trailer == null) add(tr(R.string.firmware_view_model_the_file_has_no))
                addAll(trailerWarnings(bytes, trailer, FirmwareType.FIRMWARE))
            }
            FirmwareStep.Ready(ReadyImage(bytes, FirmwareType.FIRMWARE, tr(R.string.firmware_view_model_firmware_from_file), null, fromCloud = false, trailer, warnings))
        }.getOrElse { FirmwareStep.Failed(it.message ?: tr(R.string.firmware_view_model_couldn_t_read_the)) }
    }

    /** Problems that make an image look wrong for this strap (docs/protocol/03 §2.2, advisory). */
    private fun trailerWarnings(bytes: ByteArray, trailer: HmfwTrailer?, type: Int): List<String> = buildList {
        if (trailer == null || type != FirmwareType.FIRMWARE) return@buildList
        if (trailer.pnpId != StrapIdentity.PRODUCT_ID) {
            add(tr(R.string.firmware_view_model_the_image_says_it, trailer.pnpId, StrapIdentity.PRODUCT_ID))
        }
        if (trailer.fwSize > bytes.size) add(tr(R.string.firmware_view_model_the_image_s_size, trailer.fwSize))
    }

    /** Problems that must be fixed before installing. */
    fun blockers(): List<String> = buildList {
        val c = strap.connection.value
        if (c !is ConnectionState.Connected) add(tr(R.string.firmware_view_model_connect_the_strap))
        else {
            val battery = c.batteryPercent
            if (battery == null) add(tr(R.string.firmware_view_model_battery_level_unknown_reconnect))
            else if (battery <= MIN_BATTERY) add(tr(R.string.firmware_view_model_charge_the_strap_above, MIN_BATTERY, battery))
        }
        if (strap.info.value?.dfuAvailable == false) add(tr(R.string.firmware_view_model_this_strap_doesn_t))
    }

    fun install(image: ReadyImage) {
        val blocking = blockers()
        if (blocking.isNotEmpty()) return fail(blocking.joinToString(" "))
        _step.value = FirmwareStep.Installing(tr(R.string.missed_starting), null)
        viewModelScope.launch {
            _step.value = runCatching {
                strap.installFirmware(image.bytes, image.type) { p ->
                    _step.value = when (p) {
                        is DfuProgress.Transferring -> FirmwareStep.Installing(tr(R.string.firmware_view_model_sending_to_strap), p.sent.toFloat() / p.total)
                        DfuProgress.Validating -> FirmwareStep.Installing(tr(R.string.firmware_view_model_strap_is_checking_the), null)
                        DfuProgress.Applying -> FirmwareStep.Installing(tr(R.string.firmware_view_model_strap_is_installing), null)
                        DfuProgress.Done -> FirmwareStep.Installing(tr(R.string.firmware_view_model_restarting), 1f)
                    }
                }
                FirmwareStep.Installed
            }.getOrElse { FirmwareStep.Failed(it.message ?: tr(R.string.firmware_view_model_install_failed)) }
        }
    }

    private fun fail(message: String) { _step.value = FirmwareStep.Failed(message) }

    companion object {
        /** Zepp's upgrade page refuses at this level or below (07 §1.1). */
        const val MIN_BATTERY = 30
    }
}
