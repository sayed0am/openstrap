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
        item { PageHeader("Firmware", { vm.reset(); onBack() }, backEnabled = !installing) }

        item {
            PagePanel(title = "This strap") {
                val c = connection as? ConnectionState.Connected
                InfoLine("Firmware", info?.firmware ?: c?.firmware ?: "Unknown")
                info?.hardware?.let { InfoLine("Hardware", it) }
                InfoLine("Battery", c?.batteryPercent?.let { "$it%" } ?: "Not connected")
                InfoLine("Update service", when (info?.dfuAvailable) { true -> "Available"; false -> "Not found"; null -> "Connect to check" })
            }
        }

        item { StepPanel(step, vm, onInstall = { confirm = it }) }

        item {
            PageRows {
                // Update checks need a signed-in Zepp account; its page is a row away, as on the Strap page.
                PageRow("Zepp account", if (account != null) "Signed in" else "Not signed in", onClick = onZeppAccount, divider = false, enabled = !installing)
                PageRow("Install from a file", "For an image you have", onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !installing)
            }
        }

        item {
            PagePanel(title = "Privacy and safety") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Point(AppIcons.Key, "Sign-in", "Happens on Zepp's own page. OpenStrap keeps only the app token and user ID, and fetches your device list once.")
                    Point(AppIcons.CloudArrowDown, "Update check", "Sends the update server your token, user ID and the strap's firmware, hardware and serial number. Nothing else.")
                    Point(AppIcons.CheckCircle, "Safe install", "Downloads are checked against the server's checksum, and the strap verifies the signature before installing.")
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
                CardTitle("Updates")
                Body("Ask Zepp's update server whether there's newer firmware for this strap.")
                PageButton("Check for update", vm::check, Modifier.fillMaxWidth(), primary = true)
            }
            FirmwareStep.Checking -> Busy("Checking for updates…", null)
            is FirmwareStep.Checked -> CheckResult(step.result, vm)
            is FirmwareStep.Downloading -> Busy("Downloading ${step.part.label} ${step.part.version}", step.fraction)
            is FirmwareStep.Ready -> {
                val image = step.image
                CardTitle("Ready to install")
                InfoLine("Image", image.title)
                InfoLine("Size", "${Units.count(image.bytes.size)} bytes")
                image.trailer?.let { InfoLine("Signed for", "product ${it.pnpId} v${it.pnpVersion}") }
                image.warnings.forEach { Warning(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PageButton("Cancel", vm::reset, Modifier.weight(1f))
                    PageButton("Install", { onInstall(image) }, Modifier.weight(1f), primary = true)
                }
            }
            is FirmwareStep.Installing -> {
                Busy(step.label, step.fraction)
                PageNote("Keep the strap close and this screen open. This can take several minutes.")
            }
            FirmwareStep.Installed -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(AppIcons.CheckCircle, null, tint = MetricColors.good)
                    CardTitle("Installed")
                }
                Body("The strap is restarting. OpenStrap reconnects by itself. Check again afterwards in case there are more parts to install.")
                PageButton("Done", vm::reset, Modifier.fillMaxWidth())
            }
            is FirmwareStep.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(AppIcons.WarningCircle, null, tint = MetricColors.warn)
                    CardTitle("Something went wrong")
                }
                Body(step.message)
                PageButton("OK", vm::reset, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CheckResult(result: UpdateCheck, vm: FirmwareViewModel) {
    if (result.upToDate) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AppIcons.CheckCircle, null, tint = MetricColors.good)
            CardTitle("Up to date")
        }
        Body("The update server has nothing newer for this strap.")
        PageButton("OK", vm::reset, Modifier.fillMaxWidth())
        return
    }
    val next = result.installOrder.firstOrNull()
    CardTitle(if (result.forced) "Required update" else "Update available")
    result.parts.forEach { InfoLine(it.label, "${it.version}, ${Units.count(it.length / 1024)} KB") }
    result.changeLog?.let { PageNote(it.substringBefore("###summary###").trim()) }
    if (result.installOrder.size > 1) {
        PageNote("Parts are installed one at a time. After the strap restarts, check again to install the next one.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PageButton("Not now", vm::reset, Modifier.weight(1f))
        PageButton(if (next == null) "Unsupported" else "Download", { next?.let(vm::download) }, Modifier.weight(1f), enabled = next != null, primary = true)
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
        item { PageHeader("Zepp account", onBack) }
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
            confirmLabel = "Save sign-in",
            onDismiss = { signIn = false },
            onConfirm = { listing, strap -> signIn = false; onSignIn(listing, strap) },
        )
    }
    PagePanel {
        if (!editing) {
            Body(
                if (account == null) "Zepp only answers update checks for signed-in users. Sign in, or paste your own app token and user ID. They're stored on this phone only."
                else "Update checks use your saved token${if (account.userId.isNotEmpty()) " for user ${account.userId}" else ""} on ${account.apiBase}.",
            )
            PageButton(if (account == null) "Sign in with Zepp" else "Sign in again", { signIn = true }, Modifier.fillMaxWidth(), enabled = enabled, primary = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PageButton(if (account == null) "Paste token" else "Edit", { editing = true }, Modifier.weight(1f), enabled = enabled)
                if (account != null) PageButton("Remove", onClear, Modifier.weight(1f), enabled = enabled, color = DestructiveInk)
            }
            return@PagePanel
        }
        var token by rememberSaveable { mutableStateOf("") }
        var user by rememberSaveable { mutableStateOf(account?.userId.orEmpty()) }
        var api by rememberSaveable { mutableStateOf(account?.apiBase ?: ZeppAccount.DEFAULT_API_BASE) }
        var china by rememberSaveable { mutableStateOf(account?.china == true) }
        PageField(
            token, { token = it }, "App token", Modifier.fillMaxWidth(),
            placeholder = if (account != null) "Leave empty to keep the saved token" else null,
            visualTransformation = PasswordVisualTransformation(),
        )
        PageField(
            user, { user = it }, "User ID", Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        PageField(api, { api = it }, "Server", Modifier.fillMaxWidth())
        PageSwitchRow("Chinese-market strap", "Only if the strap was bought in mainland China", china, { china = it }, divider = false)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PageButton("Cancel", { editing = false }, Modifier.weight(1f))
            PageButton(
                "Save", { onSave(token.ifBlank { account?.appToken.orEmpty() }, user, api, china); editing = false }, Modifier.weight(1f),
                enabled = token.isNotBlank() || account != null, primary = true,
            )
        }
    }
}

@Composable
private fun ConfirmInstall(image: ReadyImage, blockers: List<String>, onDismiss: () -> Unit, onInstall: () -> Unit) {
    var accepted by remember { mutableStateOf(image.warnings.isEmpty()) }
    PageConfirm(
        "Install ${image.title}?",
        if (blockers.isNotEmpty()) null
        else "The strap will receive the image, check it, install it and restart. Keep it near the phone and don't close OpenStrap until it's done.",
        confirm = "Install", onConfirm = onInstall, onDismiss = onDismiss, confirmEnabled = blockers.isEmpty() && accepted,
    ) {
        if (blockers.isNotEmpty()) {
            blockers.forEach { Warning(it) }
        } else {
            image.warnings.forEach { Warning(it) }
            if (image.warnings.isNotEmpty()) PageSwitchRow("I understand and want to install it anyway", null, accepted, { accepted = it }, divider = false)
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
