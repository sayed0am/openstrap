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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R
import org.openstrap.app.data.settings.ZeppAccount
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.firmware.UpdateCheck
import org.openstrap.app.presentation.Units
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import org.openstrap.app.ui.glow.CardTitle
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

/**
 * The strap's firmware: what it runs, checking for and installing an update, or installing from a file. Opened from
 * the Strap page. The Zepp account the update check signs in with has its own page ([ZeppAccountScreen]).
 */
@Composable
fun FirmwareScreen(vm: FirmwareViewModel, contentPadding: PaddingValues, onBack: () -> Unit, onZeppAccount: () -> Unit) {
    val step by vm.step.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val installing = step is FirmwareStep.Installing

    // Leaving mid-install would be harmless for the strap, but keep the user here and the screen on.
    BackHandler { if (!installing) { vm.reset(); onBack() } }
    KeepScreenOn(installing)

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::loadFile) }
    var confirm by remember { mutableStateOf<ReadyImage?>(null) }
    confirm?.let { image -> ConfirmInstall(image, vm.blockers(), onDismiss = { confirm = null }, onInstall = { confirm = null; vm.install(image) }) }

    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(stringResource(R.string.you_firmware_title), { vm.reset(); onBack() }, backEnabled = !installing) }

        item {
            PagePanel(title = stringResource(R.string.you_firmware_this_strap)) {
                val c = connection as? ConnectionState.Connected
                InfoLine(
                    stringResource(R.string.you_firmware_title),
                    info?.firmware ?: c?.firmware ?: stringResource(R.string.you_firmware_unknown),
                )
                info?.hardware?.let { InfoLine(stringResource(R.string.you_firmware_hardware), it) }
                InfoLine(
                    stringResource(R.string.you_firmware_battery),
                    c?.batteryPercent?.let { stringResource(R.string.you_firmware_percent, it) }
                        ?: stringResource(R.string.you_firmware_not_connected),
                )
                InfoLine(
                    stringResource(R.string.you_firmware_service),
                    stringResource(
                        when (info?.dfuAvailable) {
                            true -> R.string.you_firmware_service_available
                            false -> R.string.you_firmware_service_not_found
                            null -> R.string.you_firmware_service_unknown
                        },
                    ),
                )
            }
        }

        item { StepPanel(step, vm, onInstall = { confirm = it }) }

        item {
            PageRows {
                // Update checks need a signed-in Zepp account; its page is a row away, as on the Strap page.
                PageRow(
                    stringResource(R.string.you_strap_row_zepp),
                    stringResource(if (account != null) R.string.you_strap_signed_in else R.string.you_strap_not_signed_in),
                    onClick = onZeppAccount, divider = false, enabled = !installing,
                )
                PageRow(
                    stringResource(R.string.you_firmware_from_file), stringResource(R.string.you_firmware_from_file_note),
                    onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !installing,
                )
            }
        }

        item {
            PagePanel(title = stringResource(R.string.you_firmware_privacy)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Point(
                        AppIcons.Key, stringResource(R.string.you_firmware_privacy_sign_in),
                        stringResource(R.string.you_firmware_privacy_sign_in_text),
                    )
                    Point(
                        AppIcons.CloudArrowDown, stringResource(R.string.you_firmware_privacy_check),
                        stringResource(R.string.you_firmware_privacy_check_text),
                    )
                    Point(
                        AppIcons.CheckCircle, stringResource(R.string.you_firmware_privacy_install),
                        stringResource(R.string.you_firmware_privacy_install_text),
                    )
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
                CardTitle(stringResource(R.string.you_firmware_updates))
                Body(stringResource(R.string.you_firmware_updates_text))
                PageButton(stringResource(R.string.you_firmware_check), vm::check, Modifier.fillMaxWidth(), primary = true)
            }
            FirmwareStep.Checking -> Busy(stringResource(R.string.you_firmware_checking), null)
            is FirmwareStep.Checked -> CheckResult(step.result, vm)
            is FirmwareStep.Downloading -> Busy(
                stringResource(R.string.you_firmware_downloading, step.part.label, step.part.version), step.fraction,
            )
            is FirmwareStep.Ready -> {
                val image = step.image
                CardTitle(stringResource(R.string.you_firmware_ready))
                InfoLine(stringResource(R.string.you_firmware_image), image.title)
                InfoLine(
                    stringResource(R.string.you_firmware_size),
                    pluralStringResource(R.plurals.you_firmware_bytes, image.bytes.size, Units.count(image.bytes.size)),
                )
                image.trailer?.let {
                    InfoLine(
                        stringResource(R.string.you_firmware_signed_for),
                        stringResource(R.string.you_firmware_signed_for_value, it.pnpId, it.pnpVersion),
                    )
                }
                image.warnings.forEach { Warning(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PageButton(stringResource(R.string.you_action_cancel), vm::reset, Modifier.weight(1f))
                    PageButton(stringResource(R.string.you_firmware_install), { onInstall(image) }, Modifier.weight(1f), primary = true)
                }
            }
            is FirmwareStep.Installing -> {
                Busy(step.label, step.fraction)
                PageNote(stringResource(R.string.you_firmware_installing_note))
            }
            FirmwareStep.Installed -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(AppIcons.CheckCircle, null, tint = MetricColors.good)
                    CardTitle(stringResource(R.string.you_firmware_installed))
                }
                Body(stringResource(R.string.you_firmware_installed_text))
                PageButton(stringResource(R.string.you_firmware_done), vm::reset, Modifier.fillMaxWidth())
            }
            is FirmwareStep.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(AppIcons.WarningCircle, null, tint = MetricColors.warn)
                    CardTitle(stringResource(R.string.you_firmware_failed))
                }
                Body(step.message)
                PageButton(stringResource(R.string.you_firmware_ok), vm::reset, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CheckResult(result: UpdateCheck, vm: FirmwareViewModel) {
    if (result.upToDate) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AppIcons.CheckCircle, null, tint = MetricColors.good)
            CardTitle(stringResource(R.string.you_firmware_up_to_date))
        }
        Body(stringResource(R.string.you_firmware_up_to_date_text))
        PageButton(stringResource(R.string.you_firmware_ok), vm::reset, Modifier.fillMaxWidth())
        return
    }
    val next = result.installOrder.firstOrNull()
    CardTitle(stringResource(if (result.forced) R.string.you_firmware_required else R.string.you_firmware_available))
    result.parts.forEach { InfoLine(it.label, stringResource(R.string.you_firmware_part_size, it.version, Units.count(it.length / 1024))) }
    result.changeLog?.let { PageNote(it.substringBefore("###summary###").trim()) }
    if (result.installOrder.size > 1) {
        PageNote(stringResource(R.string.you_firmware_parts_note))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PageButton(stringResource(R.string.you_firmware_not_now), vm::reset, Modifier.weight(1f))
        PageButton(
            stringResource(if (next == null) R.string.you_firmware_unsupported else R.string.you_firmware_download),
            { next?.let(vm::download) }, Modifier.weight(1f), enabled = next != null, primary = true,
        )
    }
}

/**
 * The Zepp account the firmware check signs in with: sign in, paste a token, or remove it. Opened from the Strap page
 * (and from Firmware). Kept on this phone only.
 */
@Composable
fun ZeppAccountScreen(vm: FirmwareViewModel, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val account by vm.account.collectAsStateWithLifecycle()
    val installing = vm.step.collectAsStateWithLifecycle().value is FirmwareStep.Installing
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(stringResource(R.string.you_zepp_title), onBack) }
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
            confirmLabel = stringResource(R.string.you_zepp_save_sign_in),
            onDismiss = { signIn = false },
            onConfirm = { listing, strap -> signIn = false; onSignIn(listing, strap) },
        )
    }
    PagePanel {
        if (!editing) {
            Body(
                when {
                    account == null -> stringResource(R.string.you_zepp_account_none)
                    account.userId.isNotEmpty() -> stringResource(R.string.you_zepp_account_user, account.userId, account.apiBase)
                    else -> stringResource(R.string.you_zepp_account, account.apiBase)
                },
            )
            PageButton(
                stringResource(if (account == null) R.string.you_pair_sign_in else R.string.you_zepp_sign_in_again), { signIn = true },
                Modifier.fillMaxWidth(), enabled = enabled, primary = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PageButton(
                    stringResource(if (account == null) R.string.you_zepp_paste_token else R.string.you_zepp_edit), { editing = true },
                    Modifier.weight(1f), enabled = enabled,
                )
                if (account != null) {
                    PageButton(
                        stringResource(R.string.you_zepp_remove), onClear, Modifier.weight(1f), enabled = enabled, color = DestructiveInk,
                    )
                }
            }
            return@PagePanel
        }
        var token by rememberSaveable { mutableStateOf("") }
        var user by rememberSaveable { mutableStateOf(account?.userId.orEmpty()) }
        var api by rememberSaveable { mutableStateOf(account?.apiBase ?: ZeppAccount.DEFAULT_API_BASE) }
        var china by rememberSaveable { mutableStateOf(account?.china == true) }
        PageField(
            token, { token = it }, stringResource(R.string.you_zepp_app_token), Modifier.fillMaxWidth(),
            placeholder = if (account != null) stringResource(R.string.you_zepp_keep_token) else null,
            visualTransformation = PasswordVisualTransformation(),
        )
        PageField(
            user, { user = it }, stringResource(R.string.you_zepp_user_id), Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        PageField(api, { api = it }, stringResource(R.string.you_zepp_server), Modifier.fillMaxWidth())
        PageSwitchRow(
            stringResource(R.string.you_zepp_china), stringResource(R.string.you_zepp_china_note), china, { china = it }, divider = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PageButton(stringResource(R.string.you_action_cancel), { editing = false }, Modifier.weight(1f))
            PageButton(
                stringResource(R.string.you_action_save),
                { onSave(token.ifBlank { account?.appToken.orEmpty() }, user, api, china); editing = false }, Modifier.weight(1f),
                enabled = token.isNotBlank() || account != null, primary = true,
            )
        }
    }
}

@Composable
private fun ConfirmInstall(image: ReadyImage, blockers: List<String>, onDismiss: () -> Unit, onInstall: () -> Unit) {
    var accepted by remember { mutableStateOf(image.warnings.isEmpty()) }
    PageConfirm(
        stringResource(R.string.you_firmware_confirm_title, image.title),
        if (blockers.isNotEmpty()) null else stringResource(R.string.you_firmware_confirm_text),
        confirm = stringResource(R.string.you_firmware_install), onConfirm = onInstall, onDismiss = onDismiss,
        confirmEnabled = blockers.isEmpty() && accepted,
    ) {
        if (blockers.isNotEmpty()) {
            blockers.forEach { Warning(it) }
        } else {
            image.warnings.forEach { Warning(it) }
            if (image.warnings.isNotEmpty()) {
                PageSwitchRow(stringResource(R.string.you_firmware_confirm_anyway), null, accepted, { accepted = it }, divider = false)
            }
        }
    }
}

@Composable
private fun Busy(label: String, fraction: Float?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = GlowType.body, color = Color.White, modifier = Modifier.weight(1f))
        fraction?.let {
            Text(stringResource(R.string.you_firmware_percent, (it * 100).toInt()), style = GlowType.body, color = Color.White)
        }
    }
    PageProgress(fraction)
}

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
