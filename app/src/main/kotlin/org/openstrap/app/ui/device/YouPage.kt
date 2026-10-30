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

/** "7 h 30", or "8 h" on the hour. */
fun hoursMinutes(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} h" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"

/** The Goals row's value: "8,000 steps, 300 active kcal, 8 active hours". */
fun goalsLine(goals: Goals): String = "%,d steps, %,d active kcal, %d active hours".format(goals.steps, goals.calories, goals.activeHours)

/** The Sleep goal row's value, with the sleep need Recovery uses beside it: "7 h 30. Your sleep need is 8 h". */
fun sleepGoalLine(goals: Goals, needMinutes: Int): String =
    "${hoursMinutes(goals.sleepMinutes)}. Your sleep need is ${hoursMinutes(needMinutes)}"

/**
 * The auto sync row's value: on and when it last wrote ("On. Last synced 10:42", "yesterday 10:42", "12 Oct"), off,
 * or stopped by itself and why. [now] in epoch seconds.
 */
fun healthConnectLine(auto: HealthConnectAuto, now: Long, zone: java.time.ZoneId, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    if (!auto.on) return when (auto.stopped) {
        HealthConnectAuto.Stopped.PERMISSIONS -> "Stopped. Health Connect permissions were turned off"
        HealthConnectAuto.Stopped.UNAVAILABLE -> "Stopped. Health Connect isn't available on this phone"
        null -> "Off. Copies new data after each strap sync"
    }
    val last = auto.lastSynced ?: return "On. Copies new data after each strap sync"
    val today = java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    val day = java.time.Instant.ofEpochSecond(last).atZone(zone).toLocalDate()
    val clock = org.openstrap.app.ui.dashboard.clockText(last, zone, locale = locale)
    return "On. Last synced " + when (day) {
        today -> clock
        today.minusDays(1) -> "yesterday $clock"
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", locale))
    }
}

/** The You page's Health Connect row: on and when it last wrote, off, or stopped and why, in a few words. */
fun healthConnectSummary(auto: HealthConnectAuto, now: Long, zone: java.time.ZoneId, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    when {
        auto.on && auto.lastSynced != null -> healthConnectLine(auto, now, zone, locale)
        auto.on -> "On"
        auto.stopped == HealthConnectAuto.Stopped.PERMISSIONS -> "Stopped. Permissions were turned off"
        auto.stopped == HealthConnectAuto.Stopped.UNAVAILABLE -> "Stopped. Not available on this phone"
        else -> "Off"
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
    /** Sleep and naps deleted in the last 7 days; the Deleted sleep row shows only when there are any. */
    deletedSleep: Int,
    onDeletedSleep: () -> Unit,
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
        item { PageHeader("You", onBack) }
        item {
            PageRows {
                PageRow("Profile", profile.summary(), onClick = onProfile, below = true, divider = false, icon = AppIcons.User, selected = selected == "Profile")
                PageRow("Goals", goalsLine(goals), onClick = onGoals, below = true, icon = AppIcons.Target)
                PageRow("Sleep goal", sleepGoalLine(goals, sleepNeedMinutes), onClick = onGoals, below = true, icon = AppIcons.Moon)
            }
        }
        item { PanelHeading("Your data") }
        item {
            PageRows {
                // Read again whenever either changes, so "today" and "yesterday" follow the last one.
                val now = remember(healthConnectAuto, backup) { System.currentTimeMillis() / 1000 }
                val zone = java.time.ZoneId.systemDefault()
                PageRow(
                    "Health Connect", healthConnectSummary(healthConnectAuto, now, zone), onClick = onHealthConnect, below = true, divider = false,
                    icon = AppIcons.Heartbeat, selected = selected == "Health Connect",
                )
                PageRow("Backup", BackupCopy.summary(backup, now, zone), onClick = onBackup, below = true, icon = AppIcons.ShieldCheck, selected = selected == "Backup")
                if (deletedSleep > 0) PageRow(
                    "Deleted sleep", org.openstrap.app.ui.dashboard.SleepCopy.deletedCount(deletedSleep), onClick = onDeletedSleep, below = true,
                    icon = AppIcons.Trash, selected = selected == "Deleted sleep",
                )
            }
        }
        item { PanelHeading("Help") }
        item {
            PageRows {
                val now = remember(updates) { System.currentTimeMillis() / 1000 }
                PageRow(
                    "Updates", UpdateCopy.summary(updates, installedVersion, now), onClick = onUpdates, below = true, divider = false,
                    icon = AppIcons.ArrowCircleUp, selected = selected == "Updates", dot = updateWaiting,
                )
                PageRow("Diagnostics", "$logLines lines, for bug reports", onClick = onDiagnostics, below = true, icon = AppIcons.Bug, selected = selected == "Diagnostics")
                if (onSampleData != null) PageRow("Load sample data", "Debug builds only", onClick = onSampleData, below = true, icon = AppIcons.Flask)
            }
        }
        item {
            Text(
                "OpenStrap ${BuildConfig.VERSION_NAME}. Everything stays on this phone.",
                Modifier.fillMaxWidth().padding(top = 4.dp),
                color = Color.White.copy(alpha = 0.45f), style = GlowType.secondary, textAlign = TextAlign.Center,
            )
        }
    }
}
