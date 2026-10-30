package org.openstrap.app.ui.device

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.SyncState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.ui.alarms.AlarmsUiState
import org.openstrap.app.ui.alarms.alarmsSummary
import org.openstrap.app.ui.alarms.healthAlertsSummary
import org.openstrap.app.ui.alarms.phoneAlertsSummary
import org.openstrap.app.ui.dashboard.agoText
import org.openstrap.app.ui.glow.CoreShape
import org.openstrap.app.ui.glow.DestructiveInk
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.GlowCard
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowStyle
import org.openstrap.app.ui.glow.GlowSwitch
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.LightAngle
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageConfirm
import org.openstrap.app.ui.glow.PageField
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PageProgress
import org.openstrap.app.ui.glow.PageSheet
import org.openstrap.app.ui.glow.PageTextButton
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PagePanel
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.glow.StrapDrawing
import org.openstrap.app.ui.glow.StrapLight
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.dashboard.DATA_ON_STRAP
import org.openstrap.app.ui.dashboard.StrapView
import org.openstrap.app.ui.zepp.ComputerGuideButton
import org.openstrap.app.ui.zepp.OptionButton
import org.openstrap.app.ui.zepp.ZeppSignInDialog
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * The line under the strap's name on the Strap page: what it's doing and when it was last heard from ("Connected.
 * Synced 2 min ago", "Last seen 18 min ago"). [lastSeen] and [now] are epoch seconds.
 */
internal fun strapStateLine(connection: ConnectionState, sync: SyncState, lastSeen: Long?, now: Long): String = when (connection) {
    is ConnectionState.Connected -> when {
        connection.charging -> tr(R.string.device_screen_charging_wear_data_pauses)
        sync is SyncState.Running -> tr(R.string.device_screen_connected_syncing)
        sync is SyncState.Done -> tr(R.string.device_screen_connected_synced, agoText(now - sync.atEpochSeconds).lowercase())
        sync is SyncState.Failed -> tr(R.string.device_screen_connected_not_synced)
        else -> tr(R.string.device_screen_connected)
    }
    ConnectionState.Connecting, ConnectionState.Authenticating -> tr(R.string.device_screen_connecting)
    is ConnectionState.Failed -> if (connection.keyRejected) tr(R.string.device_screen_the_strap_turned_the) else tr(R.string.device_screen_can_t_connect)
    ConnectionState.NotPaired -> tr(R.string.common_not_paired)
    ConnectionState.Disconnected -> lastSeen?.let { tr(R.string.device_screen_last_seen, agoText(now - it).lowercase()) } ?: tr(R.string.common_not_connected)
}

/**
 * The Strap page (spec 3.4), opened from the header chip on any tab: the strap with its state and battery, Sync now and
 * Find strap, then its settings as rows with their values, each opening its page or switching in place, then
 * Disconnect, Restart and Forget. With no strap paired, the pairing steps.
 */
@Composable
fun DeviceScreen(
    vm: DeviceViewModel,
    contentPadding: PaddingValues,
    onRequestBluetooth: (then: () -> Unit) -> Unit,
    settings: SettingsStore,
    /** The strap's alarms and health settings as last read, for the rows' values. */
    alarms: AlarmsUiState,
    zeppSignedIn: Boolean,
    /** When the strap was last connected (epoch seconds), null when not seen this run. */
    lastSeen: Long?,
    onAlarms: () -> Unit,
    onPhoneAlerts: () -> Unit,
    onHealthAlerts: () -> Unit,
    onFirmware: () -> Unit,
    onZeppAccount: () -> Unit,
    onBack: () -> Unit,
    /** Debug: a previewed strap state, shown on the strap card only ([org.openstrap.app.ui.dashboard.StrapPreview]). */
    preview: StrapView? = null,
    /** The row whose page is open beside the list on a wide screen, by its title ("Alarms"), or null. */
    selected: String? = null,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val paired by vm.paired.collectAsState()
    val connection by vm.strap.connection.collectAsState()
    val sync by vm.strap.sync.collectAsState()
    val scan by vm.scan.collectAsState()
    val info by vm.strap.info.collectAsState()
    val calls by settings.buzzOnCalls.collectAsState(false)
    val notifications by settings.buzzOnNotifications.collectAsState(false)
    val wearReminder by settings.wearReminder.collectAsState(true)
    val autoStart by settings.autoStartWorkouts.collectAsState(true)
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var confirmRestart by rememberSaveable { mutableStateOf(false) }
    val keepConnected = rememberKeepConnected(vm)
    val offerBackground by vm.offerBackground.collectAsState()
    LaunchedEffect(offerBackground, paired) {
        val address = paired?.address
        if (offerBackground && address != null) { vm.backgroundOffered(); keepConnected.associate(address) }
    }

    if (confirmDisconnect) {
        PageConfirm(
            tr(R.string.device_screen_disconnect_the_strap),
            (if (sync is SyncState.Running) tr(R.string.device_screen_the_sync_in_progress) else "") +
                tr(R.string.device_screen_the_strap_keeps_recording),
            confirm = tr(R.string.device_screen_disconnect), onConfirm = { confirmDisconnect = false; vm.strap.disconnect() }, onDismiss = { confirmDisconnect = false },
        )
    }

    if (confirmRestart) {
        PageConfirm(
            tr(R.string.device_screen_restart_the_strap),
            (if (sync is SyncState.Running) tr(R.string.device_screen_the_sync_in_progress) else "") +
                tr(R.string.device_screen_the_strap_restarts_and),
            confirm = tr(R.string.device_screen_restart), onConfirm = { confirmRestart = false; vm.restartStrap() }, onDismiss = { confirmRestart = false },
        )
    }

    if (confirmForget) {
        PageConfirm(
            tr(R.string.device_screen_forget_this_strap),
            tr(R.string.device_screen_openstrap_will_disconnect_and),
            confirm = tr(R.string.device_screen_forget), onConfirm = { confirmForget = false; vm.forget() }, onDismiss = { confirmForget = false },
        )
    }

    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.device_screen_your_strap), onBack) }

        val device = paired
        if (device == null) {
            item { PairPanel(vm, scan, onRequestBluetooth) }
            return@ScreenList
        }
        item { StrapHero(device.name, preview?.connection ?: connection, preview?.sync ?: sync, preview?.lastSeen ?: lastSeen) }
        val failed = connection as? ConnectionState.Failed
        if (failed?.keyRejected == true) item { KeyRejectedPanel(failed.reason, onPairAgain = vm::forget) }
        else item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (connection) {
                    is ConnectionState.Connected -> {
                        PageButton(
                            tr(R.string.common_sync_now), vm.strap::syncNow, Modifier.weight(1f).height(56.dp), enabled = sync !is SyncState.Running,
                            icon = AppIcons.Refresh04,
                        )
                        if (info?.findDevice == true) FindStrapButton(vm, Modifier.weight(1f).height(56.dp))
                    }
                    ConnectionState.Connecting, ConnectionState.Authenticating ->
                        PageButton(tr(R.string.common_cancel), vm.strap::disconnect, Modifier.fillMaxWidth().height(56.dp))
                    else -> PageButton(tr(R.string.device_screen_connect), { onRequestBluetooth { vm.strap.connect() } }, Modifier.fillMaxWidth().height(56.dp), primary = true)
                }
            }
        }
        item {
            val connected = connection is ConnectionState.Connected
            // What the strap holds is read from it; until then, or without it, the row just opens its page.
            val waiting = if (connected) tr(R.string.device_screen_reading) else tr(R.string.common_on_the_strap)
            PageRows {
                PageRow(tr(R.string.common_alarms), alarmsSummary(alarms) ?: waiting, onClick = onAlarms, divider = false, icon = AppIcons.Alarm, selected = selected == tr(R.string.common_alarms))
                PageRow(tr(R.string.common_phone_alerts), phoneAlertsSummary(calls, notifications), onClick = onPhoneAlerts, icon = AppIcons.Vibrate, selected = selected == tr(R.string.common_phone_alerts))
                PageRow(tr(R.string.common_health_alerts), healthAlertsSummary(alarms) ?: waiting, onClick = onHealthAlerts, icon = AppIcons.Heartbeat, selected = selected == tr(R.string.common_health_alerts))
                PageRow(tr(R.string.device_screen_off_body_reminder), onClick = { scope.launch { settings.setWearReminder(!wearReminder) } }, icon = AppIcons.HandPalm) {
                    GlowSwitch(wearReminder, { on -> scope.launch { settings.setWearReminder(on) } })
                }
                PageRow(tr(R.string.device_screen_auto_detect_workouts), onClick = { scope.launch { settings.setAutoStartWorkouts(!autoStart) } }, icon = AppIcons.PersonSimpleRun) {
                    GlowSwitch(autoStart, { on -> scope.launch { settings.setAutoStartWorkouts(on) } })
                }
                KeepConnectedRow(vm, keepConnected, device.address)
                PageRow(tr(R.string.common_firmware), info?.firmware ?: (connection as? ConnectionState.Connected)?.firmware ?: tr(R.string.device_screen_check_for_updates), onClick = onFirmware, icon = AppIcons.Cpu, selected = selected == tr(R.string.common_firmware))
                PageRow(tr(R.string.common_zepp_account), if (zeppSignedIn) tr(R.string.common_signed_in) else tr(R.string.common_not_signed_in), onClick = onZeppAccount, icon = AppIcons.Key, selected = selected == tr(R.string.common_zepp_account))
            }
        }
        item {
            PageRows {
                var first = true
                if (connection is ConnectionState.Connected) {
                    PageRow(tr(R.string.device_screen_disconnect), onClick = { confirmDisconnect = true }, divider = false, icon = AppIcons.LinkBreak)
                    first = false
                }
                if (connection is ConnectionState.Connected && info?.canRestart == true) {
                    PageRow(tr(R.string.device_screen_restart_strap), onClick = { confirmRestart = true }, divider = !first, icon = AppIcons.ShutDown)
                    first = false
                }
                PageRow(tr(R.string.device_screen_forget_this_strap_2), onClick = { confirmForget = true }, divider = !first, titleColor = DestructiveInk, icon = AppIcons.Trash)
            }
        }
    }
}

/** Find strap: buzzes the strap and becomes Stop while it runs; it stops by itself after 60 s, as Zepp's does. */
@Composable
private fun FindStrapButton(vm: DeviceViewModel, modifier: Modifier) {
    val finding by vm.strap.findingStrap.collectAsState()
    PageButton(
        if (finding) tr(R.string.device_screen_stop_buzzing) else tr(R.string.device_screen_find_strap), { if (finding) vm.stopFindingStrap() else vm.findStrap() }, modifier,
        icon = if (finding) AppIcons.StopFill else AppIcons.SearchArea,
    )
}

/** Starts Android's companion association prompt from the strap page; the result goes back to [CompanionLink]. */
private class KeepConnected(val associate: (address: String) -> Unit)

@Composable
private fun rememberKeepConnected(vm: DeviceViewModel): KeepConnected {
    val context = LocalContext.current
    val companion = vm.companion
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (!companion.onResult(result.resultCode)) vm.showMessage(tr(R.string.device_screen_not_linked_you_can))
    }
    return remember(companion, launcher) {
        KeepConnected { address ->
            val activity = context.findActivity() ?: return@KeepConnected
            companion.associate(
                activity, address,
                launch = { launcher.launch(IntentSenderRequest.Builder(it).build()) },
                onError = vm::showMessage,
            )
        }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * "Stay connected in background": On once a companion association is in place, or battery optimization is off.
 * Off, a tap asks for the association where Android supports it, otherwise (or once the user turned that down) the
 * battery-optimization prompt.
 */
@Composable
private fun KeepConnectedRow(vm: DeviceViewModel, keepConnected: KeepConnected, address: String) {
    val context = LocalContext.current
    val associated by vm.companion.associated.collectAsState()
    val ignoringBattery by vm.companion.ignoringBatteryOptimizations.collectAsState()
    val declined by vm.companionDeclined.collectAsState()
    val title = tr(R.string.device_screen_stay_connected_in_background)
    val icon = AppIcons.Bluetooth
    when {
        associated -> PageRow(title, tr(R.string.device_screen_on), icon = icon)
        vm.companion.supported && !declined -> PageRow(title, tr(R.string.common_off), onClick = { keepConnected.associate(address) }, icon = icon)
        !ignoringBattery -> PageRow(title, tr(R.string.common_off), icon = icon, onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:${context.packageName}")),
                )
            }.onFailure { vm.showMessage(tr(R.string.device_screen_couldn_t_open_battery)) }
        })
        else -> PageRow(title, tr(R.string.device_screen_on), icon = icon)
    }
}

/** The strap in a glow card: its name, the state line, a drawing of it with its light, and the battery. */
@Composable
private fun StrapHero(name: String, connection: ConnectionState, sync: SyncState, lastSeen: Long?) {
    val connected = connection as? ConnectionState.Connected
    val battery = connected?.batteryPercent
    val led = when {
        connected != null && sync is SyncState.Failed -> MetricColors.warn
        connected != null -> StrapLight
        connection is ConnectionState.Failed -> MetricColors.warn
        else -> Color.White.copy(alpha = 0.25f)
    }
    // A clock that moves on, so "Synced 2 min ago" stays true while the page is open.
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis() / 1000) {
        while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() / 1000 }
    }
    GlowCard(StrapGlow, Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(end = 112.dp)) {
                Text(name, color = Color.White, style = GlowType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    strapStateLine(connection, sync, lastSeen, now), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.secondary, modifier = Modifier.padding(top = 4.dp),
                )
            }
            // 5 dp under it, so the space below the battery matches the space the title's line height leaves above it.
            // Clear of the strap on the right too, which sits centred and so lower as this grows.
            Column(Modifier.fillMaxWidth().padding(top = 128.dp, bottom = 5.dp, end = 112.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The card's one dotted number (spec 2.3); the word after it sits on the digits' baseline in Body at 72%.
                if (battery != null) Row(verticalAlignment = Alignment.Bottom) {
                    DotMatrixText("$battery", dot = org.openstrap.app.ui.glow.DotSize.Hero)
                    Text(tr(R.string.device_screen_battery), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.padding(start = 8.dp))
                }
                if (sync is SyncState.Running) {
                    PageProgress()
                    Text(sync.step, style = GlowType.secondary, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA))
                }
                when {
                    connection is ConnectionState.Failed && !connection.keyRejected -> {
                        Text(connection.reason, color = MetricColors.warn, style = GlowType.secondary)
                        Text(
                            tr(R.string.device_screen_openstrap_keeps_retrying_keep),
                            style = GlowType.secondary, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                        )
                    }
                    sync is SyncState.Failed -> {
                        Text(tr(R.string.device_screen_sync_stopped, sync.reason), color = MetricColors.warn, style = GlowType.secondary)
                        Text(
                            tr(R.string.device_screen_openstrap_tries_again_by, DATA_ON_STRAP),
                            style = GlowType.secondary, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                        )
                    }
                }
            }
            // The strap at the card's right, centred top to bottom.
            StrapDrawing(
                led, lit = connected != null || connection is ConnectionState.Failed, syncing = connected != null && sync is SyncState.Running,
                Modifier.align(Alignment.CenterEnd).size(width = 100.dp, height = 125.dp),
            )
        }
    }
}


/**
 * The strap's card: a quiet grey-green lit from above, so it doesn't read as any metric. The page's hero, so its light
 * is L4 at full strength (spec 2.1).
 */
internal val StrapGlow = GlowStyle(
    color = Color(0xFF6AAF98), coreShape = CoreShape.Radial, coreWidth = 1.2f, coreHeight = 1.1f, coreBlur = 30.dp,
    coreLightness = 0.03f, toneWidth = 0.dp, lightAngle = LightAngle.Above,
)

/** The protocol log, for bug reports, opened from the You page; the whole log can be copied. */
@Composable
fun DiagnosticsScreen(lines: List<String>, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item {
            PageHeader(tr(R.string.common_diagnostics), onBack) {
                PageButton(tr(R.string.device_screen_copy), {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(tr(R.string.device_screen_openstrap_log), lines.joinToString("\n")))
                }, Modifier.height(40.dp), icon = AppIcons.Copy)
            }
        }
        item {
            PagePanel(title = tr(R.string.device_screen_strap_log), note = tr(R.string.missed_n_lines, lines.size)) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
                ) {
                    Text(
                        if (lines.isEmpty()) tr(R.string.device_screen_no_events_yet) else lines.joinToString("\n"),
                        style = GlowType.caption.copy(fontFamily = FontFamily.Monospace),
                        color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

@Composable
private fun PairPanel(vm: DeviceViewModel, scan: ScanUiState, onRequestBluetooth: (then: () -> Unit) -> Unit) {
    var signIn by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }

    if (signIn) {
        ZeppSignInDialog(
            preferredApiBase = null,
            confirmLabel = tr(R.string.device_screen_pair_this_strap),
            requireKey = true,
            onDismiss = { signIn = false },
            onConfirm = { listing, strap ->
                signIn = false
                onRequestBluetooth { vm.signInAndPair(listing, strap) }
            },
        )
    }

    PagePanel(title = tr(R.string.common_pair_your_helio_strap)) {
        StepText(tr(R.string.device_screen_openstrap_needs_the_strap))
        PairStep(1, tr(R.string.device_screen_link_the_strap_in)) {
            StepText(tr(R.string.device_screen_install_zepp_sign_in))
            StepText(
                tr(R.string.device_screen_openstrap_signs_in_with),
                muted = true,
            )
        }
        PairStep(2, tr(R.string.device_screen_get_the_strap_ready)) {
            StepText(tr(R.string.common_put_the_strap_on))
            StepText(tr(R.string.device_screen_force_stop_or_uninstall), muted = true)
        }
        PairStep(3, tr(R.string.device_screen_sign_in_and_pair)) {
            StepText(tr(R.string.device_screen_sign_in_with_the))
        }
        // Full panel width, not indented under step 3, so they line up with the panel's edges.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageButton(tr(R.string.common_sign_in_with_zepp), { signIn = true }, Modifier.fillMaxWidth(), primary = true)
            ComputerGuideButton()
            OptionButton(tr(R.string.device_screen_have_the_key_already), tr(R.string.device_screen_enter_it_yourself)) { manual = true }
        }
    }

    if (manual) ManualPairSheet(vm, scan, onRequestBluetooth, onDismiss = { vm.stopScan(); manual = false })
}

@Composable
private fun PairStep(number: Int, title: String, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = GlowType.secondary, color = Color.White)
        }
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = GlowType.body, color = Color.White)
            content()
        }
    }
}

@Composable
private fun StepText(text: String, muted: Boolean = false) {
    Text(text, style = GlowType.secondary, color = Color.White.copy(alpha = if (muted) 0.6f else GlowType.SECONDARY_ALPHA))
}

/** For keys read on a computer or from an earlier pairing: scan for the address, paste the key, pair. */
@Composable
private fun ManualPairSheet(
    vm: DeviceViewModel,
    scan: ScanUiState,
    onRequestBluetooth: (then: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(DeviceViewModel.DEFAULT_NAME) }
    var key by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }

    PageSheet(
        onDismiss, tr(R.string.device_screen_enter_the_key_yourself),
        note = tr(R.string.device_screen_scan_with_the_strap),
    ) {
        if (scan.scanning) {
            PageButton(tr(R.string.device_screen_stop_scanning), vm::stopScan, Modifier.fillMaxWidth())
            PageProgress()
        } else {
            PageButton(tr(R.string.device_screen_scan_for_straps), { onRequestBluetooth { vm.startScan() } }, Modifier.fillMaxWidth(), icon = AppIcons.Bluetooth)
        }
        scan.error?.let { PageNote(tr(R.string.device_screen_scanning_stopped_check_that, it), color = MetricColors.warn) }
        if (scan.scanning && scan.found.isEmpty()) PageNote(tr(R.string.device_screen_looking_for_straps_nearby))
        if (scan.found.isNotEmpty()) PageRows {
            scan.found.forEachIndexed { i, d ->
                val selected = d.address == address
                PageRow(
                    d.name, if (selected) tr(R.string.device_screen_chosen) else "${d.rssi} dBm", onClick = { address = d.address; name = d.name },
                    divider = i > 0, icon = AppIcons.Watch,
                )
            }
        }
        PageField(
            address, { address = it.uppercase() }, tr(R.string.common_bluetooth_address), placeholder = "C8:0F:10:00:00:00",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
        PageField(
            key, { key = it }, tr(R.string.common_auth_key),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailing = { PageTextButton(if (showKey) tr(R.string.common_hide) else tr(R.string.common_show), { showKey = !showKey }) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(tr(R.string.common_cancel), onDismiss, Modifier.weight(1f))
            PageButton(
                tr(R.string.device_screen_pair),
                {
                    // A bad key or address only shows a message; a valid one saves the strap and the screen moves to its status.
                    if (DeviceViewModel.normalizeAuthKey(key) != null && DeviceViewModel.isAddress(address)) onDismiss()
                    onRequestBluetooth { vm.pair(address, name, key) }
                },
                Modifier.weight(1f), enabled = address.isNotBlank() && key.isNotBlank(), primary = true,
            )
        }
    }
}

/** Shown under the status when the strap turned the key down: retrying can't help, so offer to start pairing over. */
@Composable
private fun KeyRejectedPanel(reason: String, onPairAgain: () -> Unit) {
    PagePanel(title = tr(R.string.device_screen_pairing_didn_t_work)) {
        StepText(reason)
        PageButton(tr(R.string.device_screen_start_pairing_again), onPairAgain, Modifier.fillMaxWidth(), primary = true)
        ComputerGuideButton()
    }
}
