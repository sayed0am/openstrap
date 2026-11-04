package org.openstrap.app.ui.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.openstrap.app.data.settings.HealthConnectAuto
import org.openstrap.app.health.HcAvailability
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowSwitch
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.theme.AppIcons

/**
 * The Health Connect page, from the You page: auto sync and its switch (on asks for the permissions first), then a
 * copy now, a restore and Health Connect's own screen for OpenStrap, where its permissions and data are. Where Health
 * Connect is a Play Store app that's missing or out of date (Android 12 and 13), an install button comes first: the
 * rows' toasts can't hold one.
 */
@Composable
fun HealthConnectPage(
    auto: HealthConnectAuto,
    availability: HcAvailability,
    contentPadding: PaddingValues,
    onAuto: (Boolean) -> Unit,
    onCopyNow: () -> Unit,
    onRestore: () -> Unit,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("Health Connect", onBack) }
        if (availability == HcAvailability.INSTALLABLE) item { InstallPanel(onInstall) }
        item {
            PageRows {
                val now = System.currentTimeMillis() / 1000
                val line = healthConnectLine(auto, now, java.time.ZoneId.systemDefault(), availability = availability)
                PageRow("Auto sync to Health Connect", line, onClick = { onAuto(!auto.on) }, below = true, divider = false, icon = AppIcons.Refresh04) {
                    GlowSwitch(auto.on, onAuto)
                }
            }
        }
        item { PanelHeading("Any time") }
        item {
            PageRows {
                PageRow("Copy now", "The last 30 days, to Android Health Connect", onClick = onCopyNow, below = true, divider = false, icon = AppIcons.Heartbeat)
                PageRow("Restore", "Bring back what OpenStrap saved there", onClick = onRestore, below = true, icon = AppIcons.ArrowCounterClockwise)
                PageRow("Open Health Connect", "Permissions and data", onClick = onOpen, below = true, icon = AppIcons.SlidersHorizontal)
            }
        }
    }
}

/**
 * Health Connect missing or out of date: why, and the button to its Play Store page. Straight on the page, not in a
 * panel, so it doesn't stack on the auto sync panel as a second block of the same look.
 */
@Composable
private fun InstallPanel(onInstall: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageNote(HC_INSTALL_NOTE)
        PageButton(HC_INSTALL, onInstall, Modifier.fillMaxWidth(), primary = true, icon = AppIcons.DownloadSimple)
    }
}

/**
 * Opens Health Connect's home, where OpenStrap is one tap away under Your health apps, or its Data and access screen
 * where the home isn't there. False when neither opens (Health Connect missing or disabled). OpenStrap's own
 * permissions screen (MANAGE_HEALTH_PERMISSIONS) can't be opened directly: it needs GRANT_RUNTIME_PERMISSIONS, which
 * only system apps hold.
 */
fun openHealthConnect(context: Context): Boolean {
    val intents = listOf(
        { Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS) },
        { HealthConnectClient.getHealthConnectManageDataIntent(context) },
    )
    return intents.any { make ->
        runCatching { context.startActivity(make().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}

internal const val HC_INSTALL = "Install Health Connect"
internal const val HC_INSTALL_NOTE = "On this phone Health Connect is an app from the Play Store. Install it to copy your data there."
internal const val HC_STORE_FAILED = "Couldn't open the Play Store."

/** Health Connect's app on Android 12 and 13, the one the Play Store installs. */
private const val HC_PACKAGE = "com.google.android.apps.healthdata"
private const val PLAY_STORE = "com.android.vending"

/**
 * Health Connect's Play Store page, in the order tried: the Play Store's own, as Google documents it (the onboarding
 * url, so Health Connect opens its setup once installed, and the overlay over OpenStrap), then any store app, then
 * the web page for a phone with no store app.
 */
internal fun healthConnectStoreIntents(callerId: String): List<Intent> {
    val details = "details?id=$HC_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"
    return listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://$details")).setPackage(PLAY_STORE)
            .putExtra("overlay", true).putExtra("callerId", callerId),
        Intent(Intent.ACTION_VIEW, Uri.parse("market://$details")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/$details")),
    )
}

/** Opens Health Connect's Play Store page ([healthConnectStoreIntents]). False when nothing opens it. */
fun installHealthConnect(context: Context): Boolean =
    healthConnectStoreIntents(context.packageName).any { runCatching { context.startActivity(it) }.isSuccess }

/** Health Connect's availability, read again on every return to the page, so it follows an install from the Play Store. */
@Composable
fun rememberHealthConnectAvailability(): HcAvailability {
    val context = LocalContext.current
    var availability by remember { mutableStateOf(HcAvailability.of(context)) }
    LifecycleResumeEffect(Unit) {
        availability = HcAvailability.of(context)
        onPauseOrDispose { }
    }
    return availability
}
