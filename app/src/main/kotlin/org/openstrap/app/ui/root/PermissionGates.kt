package org.openstrap.app.ui.root

import androidx.compose.ui.res.stringResource
import org.openstrap.app.R
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.openstrap.app.PendingAction
import org.openstrap.app.PendingStrapAction
import org.openstrap.app.data.SportSettings
import org.openstrap.app.data.WorkoutPlan
import org.openstrap.app.device.BlePermissions
import org.openstrap.app.ui.glow.PageConfirm

/** The Bluetooth and location gates in front of a workout and the Strap page's actions. */
internal class PermissionGates(
    /** Runs the action once Bluetooth may be used. */
    val requestBluetooth: (() -> Unit) -> Unit,
    /** Starts a plan once Bluetooth (and location, for GPS) may be used. [SportSettings] is what Ready keeps. */
    val startPlan: (WorkoutPlan, SportSettings?) -> Unit,
    /** Asks for location, then runs the action whatever the answer. */
    val requestLocation: (() -> Unit) -> Unit,
    private val blocked: MutableState<Boolean>,
) {
    /** Bluetooth was turned down for good: Android shows no dialog any more, so the app offers its settings page. */
    val bluetoothBlocked: Boolean get() = blocked.value

    fun dismissBlocked() {
        blocked.value = false
    }
}

/**
 * The permission gates. A workout waits as saveable data, so the answer still starts it after the activity was
 * recreated behind the dialog. A Strap page action waits in [pendingStrap], which outlives a fold. Location for a GPS
 * workout is asked only when GPS is on, and the workout starts either way (steps without it). Ready's "Turn on
 * location" hands over a closure that only refreshes Ready, so that one isn't saved.
 */
@Composable
internal fun rememberPermissionGates(
    pendingStrap: PendingStrapAction,
    locationGranted: () -> Boolean,
    start: (PendingAction.StartPlan) -> Unit,
): PermissionGates {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val bluetoothRequired = stringResource(R.string.day_bluetooth_required)
    val locationDeclined = stringResource(R.string.day_location_declined)
    var pending by rememberSaveable(stateSaver = PendingAction.Saver) { mutableStateOf<PendingAction?>(null) }
    val blocked = rememberSaveable { mutableStateOf(false) }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val waiting = pending as? PendingAction.StartPlan
        val strapAction = pendingStrap.action
        pending = null
        pendingStrap.action = null
        when {
            BlePermissions.granted(result) -> waiting?.let(start) ?: strapAction?.invoke()
            activity != null && BlePermissions.turnedDownForGood(activity, BlePermissions.bluetooth) -> blocked.value = true
            else -> Toast.makeText(context, bluetoothRequired, Toast.LENGTH_LONG).show()
        }
    }
    val requestBluetooth: (() -> Unit) -> Unit = { then ->
        pendingStrap.action = then
        btLauncher.launch(BlePermissions.required())
    }
    val withBluetooth: (PendingAction.StartPlan) -> Unit = { action ->
        if (BlePermissions.granted(context)) {
            start(action)
        } else {
            pending = action
            btLauncher.launch(BlePermissions.required())
        }
    }
    var afterLocation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.none { it }) {
            Toast.makeText(context, locationDeclined, Toast.LENGTH_LONG).show()
        }
        val waiting = pending as? PendingAction.StartPlan
        if (waiting != null) {
            pending = null
            withBluetooth(waiting)
        }
        afterLocation?.invoke()
        afterLocation = null
    }
    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val requestLocation: (() -> Unit) -> Unit = { then ->
        afterLocation = then
        locationLauncher.launch(locationPermissions)
    }
    val startPlan: (WorkoutPlan, SportSettings?) -> Unit = { plan, saved ->
        val action = PendingAction.StartPlan(plan, saved)
        if (plan.useGps && !locationGranted()) {
            pending = action
            locationLauncher.launch(locationPermissions)
        } else {
            withBluetooth(action)
        }
    }
    return PermissionGates(requestBluetooth, startPlan, requestLocation, blocked)
}

/** Nearby devices turned down for good: the way to OpenStrap's settings page, where it can be turned on. */
@Composable
internal fun BluetoothBlockedDialog(gates: PermissionGates) {
    if (!gates.bluetoothBlocked) return
    val context = LocalContext.current
    PageConfirm(
        title = stringResource(R.string.day_nearby_title),
        text = stringResource(R.string.day_nearby_text),
        confirm = stringResource(R.string.day_nearby_open_settings),
        onConfirm = {
            gates.dismissBlocked()
            context.startActivity(BlePermissions.settingsIntent(context))
        },
        onDismiss = gates::dismissBlocked,
        dismiss = stringResource(R.string.day_nearby_not_now),
    )
}
