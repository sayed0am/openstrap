// The message effect only shows a toast and clears the message, once per message, as it did in AppRoot.
@file:Suppress("UnnecessaryLaunchedEffect")

package org.openstrap.app.ui.root

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.openstrap.app.health.HcAvailability
import org.openstrap.app.ui.device.BackupCopy
import org.openstrap.app.ui.device.DeviceViewModel
import org.openstrap.app.ui.device.PickBackup
import org.openstrap.app.ui.device.SaveBackup

/** The device view model's messages, each shown once as a toast. */
@Composable
internal fun DeviceMessages(deviceVm: DeviceViewModel) {
    val context = LocalContext.current
    val message by deviceVm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            deviceVm.clearMessage()
        }
    }
}

/** The Health Connect page's actions, each with the permissions it needs, asked for first when missing. */
internal class HealthConnectActions(
    /** Restore from Health Connect. */
    val import: () -> Unit,
    /** The manual copy. */
    val export: () -> Unit,
    /** Auto sync on or off. */
    val auto: (Boolean) -> Unit,
)

internal val HC_UNAVAILABLE: String get() = Strings.app.get(R.string.day_hc_unavailable)
internal val HC_INSTALL_FIRST: String get() = Strings.app.get(R.string.day_hc_install_first)
private val HC_NOT_GRANTED: String get() = Strings.app.get(R.string.day_hc_not_granted)

/**
 * What a Health Connect action says when Health Connect isn't there: install it first where the Play Store has it
 * (the Health Connect page shows the button), else that it isn't available.
 */
internal fun hcMissingMessage(availability: HcAvailability): String =
    if (availability == HcAvailability.INSTALLABLE) HC_INSTALL_FIRST else HC_UNAVAILABLE

/**
 * The Health Connect decisions are the root view model's, the launchers stay here because they need the activity.
 * The write permissions are asked for the manual copy and for turning auto sync on, and which one asked survives the
 * activity being recreated behind the permission screen.
 */
@Composable
internal fun rememberHealthConnectActions(rootVm: AppRootViewModel, deviceVm: DeviceViewModel): HealthConnectActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hcForAuto by rememberSaveable { mutableStateOf(false) }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val auto = hcForAuto
        hcForAuto = false
        scope.launch {
            // The needed ones may have been granted before, outside what this screen returns. The calorie ones may stay off.
            when {
                !rootVm.healthConnectWriteGranted(granted) -> Toast.makeText(context, HC_NOT_GRANTED, Toast.LENGTH_LONG).show()
                auto -> deviceVm.setAutoHealthConnect(true)
                else -> deviceVm.exportHealthConnect()
            }
        }
    }
    val hcReadLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (rootVm.healthConnectReadGranted(granted)) deviceVm.importHealthConnect()
        else Toast.makeText(context, HC_NOT_GRANTED, Toast.LENGTH_LONG).show()
    }
    val import: () -> Unit = {
        scope.launch {
            when (val hc = rootVm.healthConnectRead()) {
                HcState.Unavailable -> Toast.makeText(context, HC_UNAVAILABLE, Toast.LENGTH_LONG).show()
                HcState.Installable -> Toast.makeText(context, HC_INSTALL_FIRST, Toast.LENGTH_LONG).show()
                HcState.Granted -> deviceVm.importHealthConnect()
                is HcState.Needs -> hcReadLauncher.launch(hc.permissions)
            }
        }
    }
    // The manual copy, or turning auto sync on ([forAuto]): with the write permissions, asked for first when missing.
    val write: (Boolean) -> Unit = { forAuto ->
        scope.launch {
            // The calorie permissions are optional, but asked for again until granted: the launcher goes on with the
            // ones it needs.
            when (val hc = rootVm.healthConnectWrite()) {
                HcState.Unavailable -> Toast.makeText(context, HC_UNAVAILABLE, Toast.LENGTH_LONG).show()
                HcState.Installable -> Toast.makeText(context, HC_INSTALL_FIRST, Toast.LENGTH_LONG).show()
                HcState.Granted -> if (forAuto) deviceVm.setAutoHealthConnect(true) else deviceVm.exportHealthConnect()
                is HcState.Needs -> {
                    hcForAuto = forAuto
                    hcLauncher.launch(hc.permissions)
                }
            }
        }
    }
    return HealthConnectActions(
        import = import,
        export = { write(false) },
        auto = { on -> if (on) write(true) else deviceVm.setAutoHealthConnect(false) },
    )
}

/** The Backup page's pickers: the zip saved by hand, automatic backup's folder, and the backup to restore. */
internal class BackupActions(
    val saveCopy: () -> Unit,
    /** Opens the folder picker on [folder], or on Documents to turn backup on. A cancel leaves it as it was. */
    val chooseFolder: (Uri?) -> Unit,
    val turnOn: () -> Unit,
    /** Restore, opening on [folder] or Download. */
    val restore: (Uri?) -> Unit,
)

/** The launchers stay with the app root, composed all the time, so an answer after a fold still arrives. */
@Composable
internal fun rememberBackupActions(deviceVm: DeviceViewModel): BackupActions {
    val csvLauncher = rememberLauncherForActivityResult(SaveBackup()) { uri ->
        uri?.let(deviceVm::exportCsv)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(deviceVm::chooseBackupFolder)
    }
    val restoreLauncher = rememberLauncherForActivityResult(PickBackup()) { uri ->
        uri?.let(deviceVm::importCsv)
    }
    return BackupActions(
        saveCopy = { csvLauncher.launch("openstrap-export.zip") },
        chooseFolder = folderLauncher::launch,
        turnOn = { folderLauncher.launch(BackupCopy.DOCUMENTS) },
        restore = restoreLauncher::launch,
    )
}
