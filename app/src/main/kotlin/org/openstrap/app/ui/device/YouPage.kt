package org.openstrap.app.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openstrap.app.BuildConfig
import org.openstrap.app.data.settings.BackupState
import org.openstrap.app.data.settings.Goals
import org.openstrap.app.data.settings.HealthConnectAuto
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/** "7 h 30", or "8 h" on the hour. */
fun hoursMinutes(minutes: Int): String =
    if (minutes % 60 == 0) tr(R.string.missed_duration_h, minutes / 60) else tr(R.string.missed_duration_h_m, minutes / 60, "%02d".format(minutes % 60))

/** The Goals row's value: "8,000 steps, 300 active kcal, 8 active hours". */
fun goalsLine(goals: Goals): String = tr(R.string.you_page_steps_active_kcal_active, goals.steps, goals.calories, goals.activeHours)

/** The Sleep goal row's value, with the sleep need Recovery uses beside it: "7 h 30. Your sleep need is 8 h". */
fun sleepGoalLine(goals: Goals, needMinutes: Int): String =
    tr(R.string.you_page_your_sleep_need_is, hoursMinutes(goals.sleepMinutes), hoursMinutes(needMinutes))

/**
 * The auto sync row's value: on and when it last wrote ("On. Last synced 10:42", "yesterday 10:42", "12 Oct"), off,
 * or stopped by itself and why. [now] in epoch seconds.
 */
fun healthConnectLine(auto: HealthConnectAuto, now: Long, zone: java.time.ZoneId, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    if (!auto.on) return when (auto.stopped) {
        HealthConnectAuto.Stopped.PERMISSIONS -> tr(R.string.you_page_stopped_health_connect_permissions)
        HealthConnectAuto.Stopped.UNAVAILABLE -> tr(R.string.you_page_stopped_health_connect_isnt)
        null -> tr(R.string.you_page_off_copies_new_data)
    }
    val last = auto.lastSynced ?: return tr(R.string.you_page_on_copies_new_data)
    val today = java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    val day = java.time.Instant.ofEpochSecond(last).atZone(zone).toLocalDate()
    val clock = org.openstrap.app.ui.dashboard.clockText(last, zone, locale = locale)
    return tr(R.string.you_page_on_last_synced) + when (day) {
        today -> clock
        today.minusDays(1) -> tr(R.string.you_page_yesterday_clock, clock)
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", locale))
    }
}

/** The You page's Health Connect row: on and when it last wrote, off, or stopped and why, in a few words. */
fun healthConnectSummary(auto: HealthConnectAuto, now: Long, zone: java.time.ZoneId, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    when {
        auto.on && auto.lastSynced != null -> healthConnectLine(auto, now, zone, locale)
        auto.on -> tr(R.string.device_screen_on)
        auto.stopped == HealthConnectAuto.Stopped.PERMISSIONS -> tr(R.string.you_page_stopped_permissions_were_turned)
        auto.stopped == HealthConnectAuto.Stopped.UNAVAILABLE -> tr(R.string.you_page_stopped_not_available_on)
        else -> tr(R.string.common_off)
    }

/** A small heading over a panel of rows, so two panels never sit back to back. */
@Composable
internal fun PanelHeading(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, modifier = Modifier.padding(start = 18.dp, top = 4.dp))
}

/**
 * The You page (spec 3.4), from the avatar on any tab: who you are and what happens to your data, things visited
 * monthly. A page like the Strap page rather than a sheet, because most rows open pages of their own and Back from
 * those should come back here. Every row shows its current value under its name, with an icon on its left.
 */
@Composable
fun YouPage(
    profile: UserProfile,
    goals: Goals,
    /** The sleep need Recovery uses, in minutes: the profile's own or the one for your age. */
    sleepNeedMinutes: Int,
    logLines: Int,
    contentPadding: PaddingValues,
    onProfile: () -> Unit,
    onGoals: () -> Unit,
    /** Auto sync to Health Connect, said on its row; the row opens the Health Connect page. */
    healthConnectAuto: HealthConnectAuto,
    onHealthConnect: () -> Unit,
    /** Automatic backup, said on its row; the row opens the Backup page. */
    backup: BackupState,
    onBackup: () -> Unit,
    /** New versions from GitHub, said on its row; the row opens the Updates page. */
    updates: org.openstrap.app.update.UpdateState,
    installedVersion: String,
    /** A new version the Updates page hasn't shown yet: a dot after the row's name. */
    updateWaiting: Boolean,
    onUpdates: () -> Unit,
    onDiagnostics: () -> Unit,
    onSampleData: (() -> Unit)?,
    onBack: () -> Unit,
    /** The row whose page is open beside the list on a wide screen, by its title ("Profile"), or null. */
    selected: String? = null,
) {
    BackHandler(onBack = onBack)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.common_you), onBack) }
        item {
            PageRows {
                PageRow(tr(R.string.common_profile), profile.summary(), onClick = onProfile, below = true, divider = false, icon = AppIcons.User, selected = selected == tr(R.string.common_profile))
                PageRow(tr(R.string.you_page_goals), goalsLine(goals), onClick = onGoals, below = true, icon = AppIcons.Target)
                PageRow(tr(R.string.you_page_sleep_goal), sleepGoalLine(goals, sleepNeedMinutes), onClick = onGoals, below = true, icon = AppIcons.Moon)
            }
        }
        item { PanelHeading(tr(R.string.you_page_your_data)) }
        item {
            PageRows {
                // Read again whenever either changes, so "today" and "yesterday" follow the last one.
                val now = remember(healthConnectAuto, backup) { System.currentTimeMillis() / 1000 }
                val zone = java.time.ZoneId.systemDefault()
                PageRow(
                    tr(R.string.you_page_health_connect), healthConnectSummary(healthConnectAuto, now, zone), onClick = onHealthConnect, below = true, divider = false,
                    icon = AppIcons.Heartbeat, selected = selected == tr(R.string.you_page_health_connect),
                )
                PageRow(tr(R.string.main_activity_backup), BackupCopy.summary(backup, now, zone), onClick = onBackup, below = true, icon = AppIcons.ShieldCheck, selected = selected == tr(R.string.main_activity_backup))
            }
        }
        item { PanelHeading(tr(R.string.you_page_help)) }
        item {
            PageRows {
                val now = remember(updates) { System.currentTimeMillis() / 1000 }
                PageRow(
                    tr(R.string.firmware_screen_updates), UpdateCopy.summary(updates, installedVersion, now), onClick = onUpdates, below = true, divider = false,
                    icon = AppIcons.ArrowCircleUp, selected = selected == tr(R.string.firmware_screen_updates), dot = updateWaiting,
                )
                PageRow(tr(R.string.common_diagnostics), tr(R.string.you_page_lines_for_bug_reports, logLines), onClick = onDiagnostics, below = true, icon = AppIcons.Bug, selected = selected == tr(R.string.common_diagnostics))
                if (onSampleData != null) PageRow(tr(R.string.you_page_load_sample_data), tr(R.string.you_page_debug_builds_only), onClick = onSampleData, below = true, icon = AppIcons.Flask)
            }
        }
        item {
            Text(
                tr(R.string.you_page_openstrap_everything_stays_on, BuildConfig.VERSION_NAME),
                Modifier.fillMaxWidth().padding(top = 4.dp),
                color = Color.White.copy(alpha = 0.45f), style = GlowType.secondary, textAlign = TextAlign.Center,
            )
        }
    }
}
