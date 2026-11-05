package org.openstrap.app.ui.device

import android.content.ClipData
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openstrap.app.R
import org.openstrap.app.device.BlePermissions
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.i18n.Strings
import org.openstrap.app.device.SyncState
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import org.openstrap.app.ui.alarms.AlarmsUiState
import org.openstrap.app.ui.alarms.alarmsSummary
import org.openstrap.app.ui.alarms.healthAlertsSummary
import org.openstrap.app.ui.alarms.phoneAlertsSummary
import org.openstrap.app.ui.dashboard.agoText
import org.openstrap.app.ui.glow.draw.CoreShape
import org.openstrap.app.ui.glow.DestructiveInk
import org.openstrap.app.ui.glow.HeroValue
import org.openstrap.app.ui.glow.GlowCard
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.PageSwitchRow
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.draw.LightAngle
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
import org.openstrap.app.ui.glow.OptionButton
import org.openstrap.app.ui.zepp.ZeppSignInDialog

/**
 * The line under the strap's name on the Strap page: what it's doing and when it was last heard from ("Connected.
 * Synced 2 min ago", "Last seen 18 min ago"). [lastSeen] and [now] are epoch seconds.
 */
internal fun strapStateLine(
    connection: ConnectionState,
    sync: SyncState,
    lastSeen: Long?,
    now: Long,
    strings: Strings = Strings.app,
): String = when (connection) {
    is ConnectionState.Connected -> when {
        connection.charging -> strings.get(R.string.you_strap_state_charging)
        sync is SyncState.Running -> strings.get(R.string.you_strap_state_syncing)
        sync is SyncState.Done -> strings.format(R.string.you_strap_state_synced, agoText(now - sync.atEpochSeconds).lowercase())
        sync is SyncState.Failed -> strings.get(R.string.you_strap_state_not_synced)
        else -> strings.get(R.string.you_strap_state_connected)
    }
    ConnectionState.Connecting, ConnectionState.Authenticating -> strings.get(R.string.you_strap_state_connecting)
    is ConnectionState.Failed ->
        strings.get(if (connection.keyRejected) R.string.you_strap_state_key_rejected else R.string.you_strap_state_cant_connect)
    ConnectionState.NotPaired -> strings.get(R.string.you_strap_state_not_paired)
    ConnectionState.Disconnected ->
        lastSeen?.let { strings.format(R.string.you_strap_state_last_seen, agoText(now - it).lowercase()) }
            ?: strings.get(R.string.you_strap_state_not_connected)
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
    /** The row whose page is open beside the list on a wide screen, by its key in [PageRowKeys], or null. */
    selected: String? = null,
) {
    BackHandler(onBack = onBack)
    val paired by vm.paired.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val calls by vm.buzzOnCalls.collectAsStateWithLifecycle()
    val notifications by vm.buzzOnNotifications.collectAsStateWithLifecycle()
    val wearReminder by vm.wearReminder.collectAsStateWithLifecycle()
    val autoStart by vm.autoStartWorkouts.collectAsStateWithLifecycle()
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var confirmRestart by rememberSaveable { mutableStateOf(false) }
    val keepConnected = rememberKeepConnected(vm)
    val offerBackground by vm.offerBackground.collectAsStateWithLifecycle()
    LaunchedEffect(offerBackground, paired) {
        val address = paired?.address
        if (offerBackground && address != null) { vm.backgroundOffered(); keepConnected.associate(address) }
    }

    if (confirmDisconnect) {
        PageConfirm(
            stringResource(R.string.you_strap_disconnect_title),
            stringResource(
                if (sync is SyncState.Running) R.string.you_strap_disconnect_text_syncing else R.string.you_strap_disconnect_text,
            ),
            confirm = stringResource(R.string.you_strap_disconnect), onConfirm = { confirmDisconnect = false; vm.disconnect() },
            onDismiss = { confirmDisconnect = false },
        )
    }

    if (confirmRestart) {
        PageConfirm(
            stringResource(R.string.you_strap_restart_title),
            stringResource(if (sync is SyncState.Running) R.string.you_strap_restart_text_syncing else R.string.you_strap_restart_text),
            confirm = stringResource(R.string.you_strap_restart), onConfirm = { confirmRestart = false; vm.restartStrap() },
            onDismiss = { confirmRestart = false },
        )
    }

    if (confirmForget) {
        PageConfirm(
            stringResource(R.string.you_strap_forget_title),
            stringResource(R.string.you_strap_forget_text),
            confirm = stringResource(R.string.you_strap_forget), onConfirm = { confirmForget = false; vm.forget() },
            onDismiss = { confirmForget = false },
        )
    }

    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(stringResource(R.string.you_strap_title), onBack) }

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
                            stringResource(R.string.you_strap_sync_now), vm::syncNow, Modifier.weight(1f).height(56.dp),
                            enabled = sync !is SyncState.Running,
                            icon = AppIcons.Refresh04,
                        )
                        if (info?.findDevice == true) FindStrapButton(vm, Modifier.weight(1f).height(56.dp))
                    }
                    ConnectionState.Connecting, ConnectionState.Authenticating ->
                        PageButton(stringResource(R.string.you_action_cancel), vm::disconnect, Modifier.fillMaxWidth().height(56.dp))
                    else -> PageButton(
                        stringResource(R.string.you_strap_connect), { onRequestBluetooth { vm.connect() } },
                        Modifier.fillMaxWidth().height(56.dp), primary = true,
                    )
                }
            }
        }
        item {
            val connected = connection is ConnectionState.Connected
            // What the strap holds is read from it; until then, or without it, the row just opens its page.
            val waiting = stringResource(if (connected) R.string.you_strap_reading else R.string.you_strap_on_strap)
            PageRows {
                PageRow(
                    stringResource(R.string.you_strap_row_alarms), alarmsSummary(alarms) ?: waiting, onClick = onAlarms, divider = false,
                    icon = AppIcons.Alarm, selected = selected == PageRowKeys.ALARMS,
                )
                PageRow(
                    stringResource(R.string.you_strap_row_phone_alerts), phoneAlertsSummary(calls, notifications), onClick = onPhoneAlerts,
                    icon = AppIcons.Vibrate, selected = selected == PageRowKeys.PHONE_ALERTS,
                )
                PageRow(
                    stringResource(R.string.you_strap_row_health_alerts), healthAlertsSummary(alarms) ?: waiting, onClick = onHealthAlerts,
                    icon = AppIcons.Heartbeat, selected = selected == PageRowKeys.HEALTH_ALERTS,
                )
                PageSwitchRow(
                    stringResource(R.string.you_strap_row_off_body), null, wearReminder, vm::setWearReminder, icon = AppIcons.HandPalm,
                )
                PageSwitchRow(
                    stringResource(R.string.you_strap_row_auto_detect), null, autoStart, vm::setAutoStartWorkouts,
                    icon = AppIcons.PersonSimpleRun,
                )
                KeepConnectedRow(vm, keepConnected, device.address)
                PageRow(
                    stringResource(R.string.you_strap_row_firmware),
                    info?.firmware ?: (connection as? ConnectionState.Connected)?.firmware
                        ?: stringResource(R.string.you_strap_check_for_updates),
                    onClick = onFirmware, icon = AppIcons.Cpu, selected = selected == PageRowKeys.FIRMWARE,
                )
                PageRow(
                    stringResource(R.string.you_strap_row_zepp),
                    stringResource(if (zeppSignedIn) R.string.you_strap_signed_in else R.string.you_strap_not_signed_in),
                    onClick = onZeppAccount, icon = AppIcons.Key, selected = selected == PageRowKeys.ZEPP_ACCOUNT,
                )
            }
        }
        item {
            PageRows {
                var first = true
                if (connection is ConnectionState.Connected) {
                    PageRow(
                        stringResource(R.string.you_strap_disconnect), onClick = { confirmDisconnect = true }, divider = false,
                        icon = AppIcons.LinkBreak,
                    )
                    first = false
                }
                if (connection is ConnectionState.Connected && info?.canRestart == true) {
                    PageRow(
                        stringResource(R.string.you_strap_row_restart), onClick = { confirmRestart = true }, divider = !first,
                        icon = AppIcons.ShutDown,
                    )
                    first = false
                }
                PageRow(
                    stringResource(R.string.you_strap_row_forget), onClick = { confirmForget = true }, divider = !first,
                    titleColor = DestructiveInk,
                    icon = AppIcons.Trash,
                )
            }
        }
    }
}

/** Find strap: buzzes the strap and becomes Stop while it runs; it stops by itself after 60 s, as Zepp's does. */
@Composable
private fun FindStrapButton(vm: DeviceViewModel, modifier: Modifier) {
    val finding by vm.findingStrap.collectAsStateWithLifecycle()
    PageButton(
        stringResource(if (finding) R.string.you_strap_find_stop else R.string.you_strap_find),
        { if (finding) vm.stopFindingStrap() else vm.findStrap() }, modifier,
        icon = if (finding) AppIcons.StopFill else AppIcons.SearchArea,
    )
}

/** Starts Android's companion association prompt from the strap page; the result goes back to [CompanionLink]. */
private class KeepConnected(val associate: (address: String) -> Unit)

@Composable
private fun rememberKeepConnected(vm: DeviceViewModel): KeepConnected {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.associationResult(result.resultCode)
    }
    return remember(vm, launcher) {
        KeepConnected { address ->
            val activity = context.findActivity() ?: return@KeepConnected
            vm.associate(activity, address, launch = { launcher.launch(IntentSenderRequest.Builder(it).build()) })
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
    val associated by vm.companionAssociated.collectAsStateWithLifecycle()
    val ignoringBattery by vm.ignoringBatteryOptimizations.collectAsStateWithLifecycle()
    val declined by vm.companionDeclined.collectAsStateWithLifecycle()
    val title = stringResource(R.string.you_strap_row_background)
    val on = stringResource(R.string.you_value_on)
    val off = stringResource(R.string.you_value_off)
    val failed = stringResource(R.string.you_strap_battery_settings_failed)
    val icon = AppIcons.Bluetooth
    when {
        associated -> PageRow(title, on, icon = icon)
        vm.companionSupported && !declined -> PageRow(title, off, onClick = { keepConnected.associate(address) }, icon = icon)
        !ignoringBattery -> PageRow(title, off, icon = icon, onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:${context.packageName}")),
                )
            }.onFailure { vm.showMessage(failed) }
        })
        else -> PageRow(title, on, icon = icon)
    }
}

/** The strap in a glow card: its name, the state line, a drawing of it with its light, and the battery. */
@Composable
internal fun StrapHero(name: String, connection: ConnectionState, sync: SyncState, lastSeen: Long?) {
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
                if (battery != null) HeroValue("$battery", stringResource(R.string.you_strap_battery_unit, "%"))
                if (sync is SyncState.Running) {
                    PageProgress()
                    Text(sync.step, style = GlowType.secondary, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA))
                }
                when {
                    connection is ConnectionState.Failed && !connection.keyRejected -> {
                        Text(connection.reason, color = MetricColors.warn, style = GlowType.secondary)
                        Text(
                            stringResource(R.string.you_strap_retrying),
                            style = GlowType.secondary, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                        )
                    }
                    sync is SyncState.Failed -> {
                        Text(
                            stringResource(R.string.you_strap_sync_stopped, sync.reason), color = MetricColors.warn,
                            style = GlowType.secondary,
                        )
                        Text(
                            stringResource(R.string.you_strap_sync_retry, DATA_ON_STRAP),
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
            val label = stringResource(R.string.you_diagnostics_clip_label)
            PageHeader(stringResource(R.string.you_diagnostics_title), onBack) {
                PageButton(stringResource(R.string.you_diagnostics_copy), {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(label, lines.joinToString("\n")))
                }, Modifier.height(40.dp), icon = AppIcons.Copy)
            }
        }
        item {
            PagePanel(
                title = stringResource(R.string.you_diagnostics_log),
                note = pluralStringResource(R.plurals.you_diagnostics_lines, lines.size, lines.size),
            ) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
                ) {
                    Text(
                        if (lines.isEmpty()) stringResource(R.string.you_diagnostics_empty) else lines.joinToString("\n"),
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
            confirmLabel = stringResource(R.string.you_pair_confirm),
            requireKey = true,
            onDismiss = { signIn = false },
            onConfirm = { listing, strap ->
                signIn = false
                onRequestBluetooth { vm.signInAndPair(listing, strap) }
            },
        )
    }

    PagePanel(title = stringResource(R.string.you_pair_title)) {
        StepText(stringResource(R.string.you_pair_intro))
        PairStep(1, stringResource(R.string.you_pair_step_link)) {
            StepText(stringResource(R.string.you_pair_step_link_text))
            StepText(stringResource(R.string.you_pair_step_link_note), muted = true)
        }
        PairStep(2, stringResource(R.string.you_pair_step_ready)) {
            StepText(stringResource(R.string.you_pair_step_ready_text))
            StepText(stringResource(R.string.you_pair_step_ready_note), muted = true)
        }
        PairStep(3, stringResource(R.string.you_pair_step_sign_in)) {
            StepText(stringResource(R.string.you_pair_step_sign_in_text))
        }
        // Full panel width, not indented under step 3, so they line up with the panel's edges.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageButton(stringResource(R.string.you_pair_sign_in), { signIn = true }, Modifier.fillMaxWidth(), primary = true)
            ComputerGuideButton()
            OptionButton(stringResource(R.string.you_pair_have_key), stringResource(R.string.you_pair_enter_key), { manual = true })
        }
    }

    if (manual) ManualPairSheet(vm, scan, onRequestBluetooth, onDismiss = { vm.stopScan(); manual = false })
}

/** One numbered pairing step: its number in a circle, its [title], and what to do under it. */
@Composable
internal fun PairStep(number: Int, title: String, content: @Composable () -> Unit) {
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
    // Android 11 only reports BLE scan results while Location is on. Read again on resume, after the settings page.
    val context = LocalContext.current
    var locationOff by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        locationOff = BlePermissions.scanNeedsLocationOn &&
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled == false
        onPauseOrDispose { }
    }
    var showKey by rememberSaveable { mutableStateOf(false) }

    PageSheet(
        onDismiss, stringResource(R.string.you_pair_manual_title),
        note = stringResource(R.string.you_pair_manual_note),
    ) {
        if (scan.scanning) {
            PageButton(stringResource(R.string.you_pair_scan_stop), vm::stopScan, Modifier.fillMaxWidth())
            PageProgress()
        } else {
            PageButton(
                stringResource(R.string.you_pair_scan), { onRequestBluetooth { vm.startScan() } }, Modifier.fillMaxWidth(),
                icon = AppIcons.Bluetooth,
            )
        }
        if (locationOff) {
            PageNote(stringResource(R.string.you_pair_scan_location_off), color = MetricColors.warn)
            PageButton(
                stringResource(R.string.you_pair_scan_location_on),
                { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                Modifier.fillMaxWidth(), icon = AppIcons.Location01,
            )
        }
        scan.error?.let { PageNote(stringResource(R.string.you_pair_scan_failed, it), color = MetricColors.warn) }
        if (scan.scanning && scan.found.isEmpty()) PageNote(stringResource(R.string.you_pair_scan_looking))
        if (scan.found.isNotEmpty()) PageRows {
            scan.found.forEachIndexed { i, d ->
                val selected = d.address == address
                PageRow(
                    d.name, if (selected) stringResource(R.string.you_pair_chosen) else stringResource(R.string.you_pair_rssi, d.rssi),
                    onClick = { address = d.address; name = d.name },
                    divider = i > 0, icon = AppIcons.Watch,
                )
            }
        }
        PageField(
            address, { address = it.uppercase() }, stringResource(R.string.you_pair_address), placeholder = "C8:0F:10:00:00:00",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
        PageField(
            key, { key = it }, stringResource(R.string.you_pair_key),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailing = {
                PageTextButton(stringResource(if (showKey) R.string.you_action_hide else R.string.you_action_show), { showKey = !showKey })
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(stringResource(R.string.you_action_cancel), onDismiss, Modifier.weight(1f))
            PageButton(
                stringResource(R.string.you_pair_pair),
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
    PagePanel(title = stringResource(R.string.you_pair_key_rejected_title)) {
        StepText(reason)
        PageButton(stringResource(R.string.you_pair_again), onPairAgain, Modifier.fillMaxWidth(), primary = true)
        ComputerGuideButton()
    }
}
