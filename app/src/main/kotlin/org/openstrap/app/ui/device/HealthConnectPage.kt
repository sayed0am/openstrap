package org.openstrap.app.ui.device

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.openstrap.app.R
import org.openstrap.app.data.settings.HealthConnectAuto
import org.openstrap.app.health.HcAvailability
import org.openstrap.app.i18n.Strings
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.PageSwitchRow
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PanelLabelInset
import org.openstrap.app.ui.glow.SectionLabel
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.theme.AppIcons

/**
 * The Health Connect page, from the You page: auto sync and its switch (on asks for the permissions first), then a
 * copy now, a restore and Health Connect's own screen for OpenStrap, where its permissions and data are. Where Health
 * Connect is a Play Store app that's missing or out of date (Android 12 and 13), an install button comes first: the
 * rows' toasts can't hold one.
 */
// A whole page, placed by the page host like the Strap page's others, so there's nothing for a caller to modify.
@Suppress("ModifierMissing")
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
        item { PageHeader(stringResource(R.string.you_hc_title), onBack) }
        if (availability == HcAvailability.INSTALLABLE) item { InstallPanel(onInstall) }
        item {
            PageRows {
                val now = System.currentTimeMillis() / 1000
                val line = healthConnectLine(auto, now, java.time.ZoneId.systemDefault(), availability = availability)
                PageSwitchRow(stringResource(R.string.you_hc_auto), line, auto.on, onAuto, divider = false, icon = AppIcons.Refresh04)
            }
        }
        item { SectionLabel(stringResource(R.string.you_hc_any_time), Modifier.padding(top = 4.dp), PanelLabelInset) }
        item {
            PageRows {
                PageRow(
                    stringResource(R.string.you_hc_copy_now), stringResource(R.string.you_hc_copy_now_note), onClick = onCopyNow,
                    below = true,
                    divider = false, icon = AppIcons.Heartbeat,
                )
                PageRow(
                    stringResource(R.string.you_action_restore), stringResource(R.string.you_hc_restore_note), onClick = onRestore,
                    below = true,
                    icon = AppIcons.ArrowCounterClockwise,
                )
                PageRow(
                    stringResource(R.string.you_hc_open), stringResource(R.string.you_hc_open_note), onClick = onOpen, below = true,
                    icon = AppIcons.SlidersHorizontal,
                )
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
        PageNote(stringResource(R.string.you_hc_install_note))
        PageButton(
            stringResource(R.string.you_hc_install), onInstall, Modifier.fillMaxWidth(), primary = true, icon = AppIcons.DownloadSimple,
        )
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

/** Why the install button is there, the page's note. Named as the constant it was, which the tests read. */
internal val HC_INSTALL_NOTE: String get() = Strings.app.get(R.string.you_hc_install_note)

/** The toast when no app store opened Health Connect's page. A getter, so it reads the app's language when shown. */
internal val HC_STORE_FAILED: String get() = Strings.app.get(R.string.you_hc_store_failed)

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
        Intent(Intent.ACTION_VIEW, "market://$details".toUri()).setPackage(PLAY_STORE)
            .putExtra("overlay", true).putExtra("callerId", callerId),
        Intent(Intent.ACTION_VIEW, "market://$details".toUri()),
        Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/$details".toUri()),
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
