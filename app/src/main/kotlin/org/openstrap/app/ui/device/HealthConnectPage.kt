package org.openstrap.app.ui.device

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.health.connect.client.HealthConnectClient
import org.openstrap.app.R
import org.openstrap.app.data.settings.HealthConnectAuto
import org.openstrap.app.tr
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowSwitch
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.theme.AppIcons

/**
 * The Health Connect page, from the You page: auto sync and its switch (on asks for the permissions first), then a
 * copy now, a restore and Health Connect's own screen for OpenStrap, where its permissions and data are.
 */
@Composable
fun HealthConnectPage(
    auto: HealthConnectAuto,
    contentPadding: PaddingValues,
    onAuto: (Boolean) -> Unit,
    onCopyNow: () -> Unit,
    onRestore: () -> Unit,
    onOpen: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.you_page_health_connect), onBack) }
        item {
            PageRows {
                val line = healthConnectLine(auto, System.currentTimeMillis() / 1000, java.time.ZoneId.systemDefault())
                PageRow(tr(R.string.health_connect_page_auto_sync_to_health), line, onClick = { onAuto(!auto.on) }, below = true, divider = false, icon = AppIcons.Refresh04) {
                    GlowSwitch(auto.on, onAuto)
                }
            }
        }
        item { PanelHeading(tr(R.string.health_connect_page_any_time)) }
        item {
            PageRows {
                PageRow(tr(R.string.health_connect_page_copy_now), tr(R.string.health_connect_page_the_last_30_days), onClick = onCopyNow, below = true, divider = false, icon = AppIcons.Heartbeat)
                PageRow(tr(R.string.backup_page_restore), tr(R.string.you_page_bring_back_what_openstrap), onClick = onRestore, below = true, icon = AppIcons.ArrowCounterClockwise)
                PageRow(tr(R.string.health_connect_page_open_health_connect), tr(R.string.health_connect_page_permissions_and_data), onClick = onOpen, below = true, icon = AppIcons.SlidersHorizontal)
            }
        }
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
