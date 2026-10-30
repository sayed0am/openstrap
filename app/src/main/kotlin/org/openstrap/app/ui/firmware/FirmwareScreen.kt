package org.openstrap.app.ui.firmware

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.settings.ZeppAccount
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.firmware.UpdateCheck
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import org.openstrap.app.ui.glow.DestructiveInk
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageConfirm
import org.openstrap.app.ui.glow.PageField
import org.openstrap.app.ui.glow.PageProgress
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PagePanel
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.glow.PageSwitchRow
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.zepp.ZeppSignInDialog
import org.openstrap.app.zepp.BoundDevice
import org.openstrap.app.zepp.DeviceListing
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/**
 * The strap's firmware: what it runs, checking for and installing an update, or installing from a file. Opened from
 * the Strap page. The Zepp account the update check signs in with has its own page ([ZeppAccountScreen]).
 */
@Composable
fun FirmwareScreen(vm: FirmwareViewModel, contentPadding: PaddingValues, onBack: () -> Unit, onZeppAccount: () -> Unit) {
    val step by vm.step.collectAsState()
    val account by vm.account.collectAsState()
    val connection by vm.strap.connection.collectAsState()
    val info by vm.strap.info.collectAsState()
    val installing = step is FirmwareStep.Installing

    // Leaving mid-install would be harmless for the strap, but keep the user here and the screen on.
    BackHandler { if (!installing) { vm.reset(); onBack() } }
    KeepScreenOn(installing)

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::loadFile) }
    var confirm by remember { mutableStateOf<ReadyImage?>(null) }
    confirm?.let { image -> ConfirmInstall(image, vm.blockers(), onDismiss = { confirm = null }, onInstall = { confirm = null; vm.install(image) }) }

    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.common_firmware), { vm.reset(); onBack() }, backEnabled = !installing) }

        item {
            PagePanel(title = tr(R.string.firmware_screen_this_strap)) {
                val c = connection as? ConnectionState.Connected
                InfoLine(tr(R.string.common_firmware), info?.firmware ?: c?.firmware ?: tr(R.string.firmware_screen_unknown))
                info?.hardware?.let { InfoLine(tr(R.string.firmware_screen_hardware), it) }
                InfoLine(tr(R.string.firmware_screen_battery), c?.batteryPercent?.let { "$it%" } ?: tr(R.string.common_not_connected))
                InfoLine(tr(R.string.firmware_screen_update_service), when (info?.dfuAvailable) { true -> tr(R.string.firmware_screen_available); false -> tr(R.string.firmware_screen_not_found); null -> tr(R.string.firmware_screen_connect_to_check) })
            }
        }

        item { StepPanel(step, vm, onInstall = { confirm = it }) }

        item {
            PageRows {
                // Update checks need a signed-in Zepp account; its page is a row away, as on the Strap page.
                PageRow(tr(R.string.common_zepp_account), if (account != null) tr(R.string.common_signed_in) else tr(R.string.common_not_signed_in), onClick = onZeppAccount, divider = false, enabled = !installing)
                PageRow(tr(R.string.firmware_screen_install_from_a_file), tr(R.string.firmware_screen_for_an_image_you), onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !installing)
            }
        }

        item {
            PagePanel(title = tr(R.string.firmware_screen_privacy_and_safety)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Point(AppIcons.Key, tr(R.string.firmware_screen_sign_in), tr(R.string.firmware_screen_happens_on_zepp_s))
                    Point(AppIcons.CloudArrowDown, tr(R.string.firmware_screen_update_check), tr(R.string.firmware_screen_sends_the_update_server))
                    Point(AppIcons.CheckCircle, tr(R.string.firmware_screen_safe_install), tr(R.string.firmware_screen_downloads_are_checked_against))
                }
            }
        }
    }
}

@Composable
private fun StepPanel(step: FirmwareStep, vm: FirmwareViewModel, onInstall: (ReadyImage) -> Unit) {
    PagePanel {
        when (step) {
            FirmwareStep.Idle -> {
                Title(tr(R.string.firmware_screen_updates))
                Body(tr(R.string.firmware_screen_ask_zepp_s_update))
                PageButton(tr(R.string.firmware_screen_check_for_update), vm::check, Modifier.fillMaxWidth(), primary = true)
            }
            FirmwareStep.Checking -> Busy(tr(R.string.firmware_screen_checking_for_updates), null)
            is FirmwareStep.Checked -> CheckResult(step.result, vm)
            is FirmwareStep.Downloading -> Busy(tr(R.string.firmware_screen_downloading, step.part.label, step.part.version), step.fraction)
            is FirmwareStep.Ready -> {
                val image = step.image
                Title(tr(R.string.firmware_screen_ready_to_install))
                InfoLine(tr(R.string.firmware_screen_image), image.title)
                InfoLine(tr(R.string.firmware_screen_size), trRaw(R.string.firmware_screen_d_bytes).format(image.bytes.size))
                image.trailer?.let { InfoLine(tr(R.string.firmware_screen_signed_for), tr(R.string.firmware_screen_product_v, it.pnpId, it.pnpVersion)) }
                image.warnings.forEach { Warning(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PageButton(tr(R.string.common_cancel), vm::reset, Modifier.weight(1f))
                    PageButton(tr(R.string.firmware_screen_install), { onInstall(image) }, Modifier.weight(1f), primary = true)
                }
            }
            is FirmwareStep.Installing -> {
                Busy(step.label, step.fraction)
                PageNote(tr(R.string.firmware_screen_keep_the_strap_close))
            }
            FirmwareStep.Installed -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(AppIcons.CheckCircle, null, tint = MetricColors.good)
                    Title(tr(R.string.firmware_screen_installed))
                }
                Body(tr(R.string.firmware_screen_the_strap_is_restarting))
                PageButton(tr(R.string.common_done), vm::reset, Modifier.fillMaxWidth())
            }
            is FirmwareStep.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(AppIcons.WarningCircle, null, tint = MetricColors.warn)
                    Title(tr(R.string.firmware_screen_something_went_wrong))
                }
                Body(step.message)
                PageButton(tr(R.string.common_ok), vm::reset, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CheckResult(result: UpdateCheck, vm: FirmwareViewModel) {
    if (result.upToDate) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AppIcons.CheckCircle, null, tint = MetricColors.good)
            Title(tr(R.string.firmware_screen_up_to_date))
        }
        Body(tr(R.string.firmware_screen_the_update_server_has))
        PageButton(tr(R.string.common_ok), vm::reset, Modifier.fillMaxWidth())
        return
    }
    val next = result.installOrder.firstOrNull()
    Title(if (result.forced) tr(R.string.firmware_screen_required_update) else tr(R.string.firmware_screen_update_available))
    result.parts.forEach { InfoLine(it.label, tr(R.string.firmware_screen_kb, it.version, "%,d".format(it.length / 1024))) }
    result.changeLog?.let { PageNote(it.substringBefore("###summary###").trim()) }
    if (result.installOrder.size > 1) {
        PageNote(tr(R.string.firmware_screen_parts_are_installed_one))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PageButton(tr(R.string.common_not_now), vm::reset, Modifier.weight(1f))
        PageButton(if (next == null) tr(R.string.firmware_screen_unsupported) else tr(R.string.firmware_screen_download), { next?.let(vm::download) }, Modifier.weight(1f), enabled = next != null, primary = true)
    }
}

/**
 * The Zepp account the firmware check signs in with: sign in, paste a token, or remove it. Opened from the Strap page
 * (and from Firmware). Kept on this phone only.
 */
@Composable
fun ZeppAccountScreen(vm: FirmwareViewModel, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val account by vm.account.collectAsState()
    val installing = vm.step.collectAsState().value is FirmwareStep.Installing
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.common_zepp_account), onBack) }
        item { AccountPanel(account, enabled = !installing, onSave = vm::saveAccount, onClear = vm::clearAccount, onSignIn = vm::saveSignIn) }
    }
}

@Composable
private fun AccountPanel(
    account: ZeppAccount?,
    enabled: Boolean,
    onSave: (String, String, String, Boolean) -> Unit,
    onClear: () -> Unit,
    onSignIn: (DeviceListing, BoundDevice?) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var signIn by rememberSaveable { mutableStateOf(false) }
    if (signIn) {
        ZeppSignInDialog(
            preferredApiBase = account?.apiBase,
            confirmLabel = tr(R.string.firmware_screen_save_sign_in),
            onDismiss = { signIn = false },
            onConfirm = { listing, strap -> signIn = false; onSignIn(listing, strap) },
        )
    }
    PagePanel {
        if (!editing) {
            Body(
                if (account == null) tr(R.string.firmware_screen_zepp_only_answers_update)
                else tr(R.string.firmware_screen_update_checks_use_your, if (account.userId.isNotEmpty()) tr(R.string.firmware_screen_update_checks_use_your__for_user, account.userId) else "", account.apiBase),
            )
            PageButton(if (account == null) tr(R.string.common_sign_in_with_zepp) else tr(R.string.common_sign_in_again), { signIn = true }, Modifier.fillMaxWidth(), enabled = enabled, primary = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PageButton(if (account == null) tr(R.string.firmware_screen_paste_token) else tr(R.string.firmware_screen_edit), { editing = true }, Modifier.weight(1f), enabled = enabled)
                if (account != null) PageButton(tr(R.string.firmware_screen_remove), onClear, Modifier.weight(1f), enabled = enabled, color = DestructiveInk)
            }
            return@PagePanel
        }
        var token by rememberSaveable { mutableStateOf("") }
        var user by rememberSaveable { mutableStateOf(account?.userId.orEmpty()) }
        var api by rememberSaveable { mutableStateOf(account?.apiBase ?: ZeppAccount.DEFAULT_API_BASE) }
        var china by rememberSaveable { mutableStateOf(account?.china == true) }
        PageField(
            token, { token = it }, tr(R.string.firmware_screen_app_token), Modifier.fillMaxWidth(),
            placeholder = if (account != null) tr(R.string.firmware_screen_leave_empty_to_keep) else null,
            visualTransformation = PasswordVisualTransformation(),
        )
        PageField(
            user, { user = it }, tr(R.string.firmware_screen_user_id), Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        PageField(api, { api = it }, tr(R.string.firmware_screen_server), Modifier.fillMaxWidth())
        PageSwitchRow(tr(R.string.firmware_screen_chinese_market_strap), tr(R.string.firmware_screen_only_if_the_strap), china, { china = it }, divider = false)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PageButton(tr(R.string.common_cancel), { editing = false }, Modifier.weight(1f))
            PageButton(
                tr(R.string.common_save), { onSave(token.ifBlank { account?.appToken.orEmpty() }, user, api, china); editing = false }, Modifier.weight(1f),
                enabled = token.isNotBlank() || account != null, primary = true,
            )
        }
    }
}

@Composable
private fun ConfirmInstall(image: ReadyImage, blockers: List<String>, onDismiss: () -> Unit, onInstall: () -> Unit) {
    var accepted by remember { mutableStateOf(image.warnings.isEmpty()) }
    PageConfirm(
        tr(R.string.firmware_screen_install_2, image.title),
        if (blockers.isNotEmpty()) null
        else tr(R.string.firmware_screen_the_strap_will_receive),
        confirm = tr(R.string.firmware_screen_install), onConfirm = onInstall, onDismiss = onDismiss, confirmEnabled = blockers.isEmpty() && accepted,
    ) {
        if (blockers.isNotEmpty()) {
            blockers.forEach { Warning(it) }
        } else {
            image.warnings.forEach { Warning(it) }
            if (image.warnings.isNotEmpty()) PageSwitchRow(tr(R.string.firmware_screen_i_understand_and_want), null, accepted, { accepted = it }, divider = false)
        }
    }
}

@Composable
private fun Busy(label: String, fraction: Float?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = GlowType.body, color = Color.White, modifier = Modifier.weight(1f))
        fraction?.let { Text("${(it * 100).toInt()}%", style = GlowType.body, color = Color.White) }
    }
    PageProgress(fraction)
}

@Composable
private fun Title(text: String) = Text(text, style = GlowType.cardTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Body(text: String) = Text(text, style = GlowType.body, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA))

/** One line of the privacy card: icon, short title, one sentence. */
@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = GlowType.body, color = Color.White)
            PageNote(body)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = GlowType.body, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), modifier = Modifier.weight(1f))
        Text(value, style = GlowType.body, color = Color.White)
    }
}

@Composable
private fun Warning(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(AppIcons.Warning, null, tint = MetricColors.warn, modifier = Modifier.size(20.dp))
        Text(text, style = GlowType.secondary, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA))
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(on) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
