package org.openstrap.app.device

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * Registers the paired strap with Android's companion device manager (a plain association, no watch profile).
 * Associated, the app may run and start its service from the background, is left out of battery optimization,
 * and on Android 12+ is woken through [StrapPresenceService] when the strap comes into range.
 */
class CompanionLink(private val context: Context, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val supported: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    private val cdm: CompanionDeviceManager? get() = if (supported) context.getSystemService(CompanionDeviceManager::class.java) else null

    /** True when the paired strap is associated; refreshed by [refresh], on association and on [disassociate]. */
    val associated = MutableStateFlow(false)

    /** True when the app is already left out of battery optimization, whichever way that happened. */
    val ignoringBatteryOptimizations = MutableStateFlow(false)

    /** Re-reads both from the system: the user can remove the association or change battery settings at any time. */
    fun refresh() = scope.launch {
        val address = settings.pairedDevice.first()?.address
        associated.value = address != null && isAssociated(address)
        ignoringBatteryOptimizations.value =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        if (associated.value && address != null) {
            // The system's list wins: the association may have been removed and made again in system settings.
            (myAssociationId(address) ?: LEGACY_ID).let { if (settings.companionAssociation.first() != it) settings.setCompanionAssociation(it) }
            if (settings.companionDeclined.first()) settings.setCompanionDeclined(false)
            observePresence(address)
        }
    }

    private fun isAssociated(address: String): Boolean {
        val cdm = cdm ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations.any { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
        } else {
            @Suppress("DEPRECATION")
            cdm.associations.any { it.equals(address, ignoreCase = true) }
        }
    }

    /**
     * Asks Android to associate the strap at [address]. Android scans for it and shows its own confirmation,
     * which [launch] starts; pass the activity result to [onResult]. [onError] gets a message to show.
     */
    fun associate(activity: Activity, address: String, launch: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val cdm = cdm ?: return onError(tr(R.string.companion_link_this_phone_doesn_t))
        // The strap is a BLE device; the classic filter also matches it once Android knows it from a connection.
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(ScanFilter.Builder().setDeviceAddress(address).build()).build())
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)

            @Deprecated("Android 12 and older")
            override fun onDeviceFound(intentSender: IntentSender) = launch(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                scope.launch { saved(associationInfo.id) }
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "association failed: $error")
                onError(tr(R.string.companion_link_android_couldn_t_find, error ?: tr(R.string.companion_link_android_couldn_t_find__no_reason_given)))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) cdm.associate(request, activity.mainExecutor, callback)
        else @Suppress("DEPRECATION") cdm.associate(request, callback, Handler(Looper.getMainLooper()))
    }

    /**
     * The result of the confirmation [associate] launched. On Android 13+ the association arrives through the
     * callback; older versions only report it here. Returns false when the user turned it down.
     */
    fun onResult(resultCode: Int): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            scope.launch { settings.setCompanionDeclined(true) }
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch {
            saved(LEGACY_ID)
        }
        return true
    }

    private suspend fun saved(id: Int) {
        settings.setCompanionAssociation(id)
        refresh()
    }

    /** Has Android bind [StrapPresenceService] when the strap is in range (Android 12+). Android keeps this across reboots. */
    private fun observePresence(address: String) {
        val cdm = cdm ?: return
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> {
                    val id = myAssociationId(address) ?: return
                    cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> @Suppress("DEPRECATION") cdm.startObservingDevicePresence(address)
            }
        }.onFailure { Log.w(TAG, "presence observing failed: $it") }
    }

    private fun myAssociationId(address: String): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm?.myAssociations?.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }?.id
        } else null

    /** Removes the association for the strap at [address], when there is one. Called when the strap is forgotten. */
    suspend fun disassociate(address: String) {
        val cdm = cdm ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                myAssociationId(address)?.let(cdm::disassociate)
            } else if (isAssociated(address)) {
                @Suppress("DEPRECATION") cdm.disassociate(address)
            }
        }.onFailure { Log.w(TAG, "disassociate failed: $it") }
        associated.value = false
    }

    companion object {
        private const val TAG = "OpenStrap"

        /** Stored when Android 12 and older made the association: those versions have no association ids. */
        const val LEGACY_ID = -1
    }
}
