package org.openstrap.app.ui.alarms

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import org.openstrap.app.data.settings.BuzzPattern
import org.openstrap.app.data.settings.CallBuzz
import org.openstrap.app.data.settings.QuietHours
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.PhoneAlerts
import org.openstrap.app.device.StrapNotificationListener
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageSheet
import org.openstrap.app.ui.glow.PagePanel
import org.openstrap.app.ui.glow.PageSwitchRow
import androidx.compose.ui.graphics.Color
import org.openstrap.app.ui.glow.GlowSegments
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.R
import org.openstrap.app.tr

/** What the Strap page's Phone alerts row says: "Calls and notifications", "Calls", "Notifications" or "Off". */
fun phoneAlertsSummary(calls: Boolean, notifications: Boolean): String = when {
    calls && notifications -> tr(R.string.phone_alerts_screen_calls_and_notifications)
    calls -> tr(R.string.phone_alerts_screen_calls)
    notifications -> tr(R.string.phone_alerts_screen_notifications)
    else -> tr(R.string.common_off)
}

/** "22:00" for minutes after midnight. */
internal fun quietClock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/** Under the Quiet hours switch: what it holds back, and when. */
internal fun quietHoursSummary(q: QuietHours, callsOn: Boolean): String = when {
    !q.on -> tr(R.string.phone_alerts_screen_pause_the_buzz_at)
    q.from == q.to -> tr(R.string.phone_alerts_screen_pick_different_start_and)
    q.calls && callsOn -> tr(R.string.phone_alerts_screen_no_buzz_from_to, quietClock(q.from), quietClock(q.to))
    else -> tr(R.string.phone_alerts_screen_no_notification_buzz_from, quietClock(q.from), quietClock(q.to))
}

/**
 * Buzzing the strap on calls and notifications, opened from the Strap page. These are phone settings, so they work
 * offline too. (The off-body reminder is a row of its own on the Strap page.)
 */
@Composable
fun PhoneAlertsScreen(alerts: PhoneAlerts, settings: SettingsStore, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calls by settings.buzzOnCalls.collectAsState(false)
    val notifications by settings.buzzOnNotifications.collectAsState(false)
    val callPermission by alerts.callPermission.collectAsState()
    val notificationAccess by alerts.notificationAccess.collectAsState()
    val pattern by settings.notificationPattern.collectAsState(BuzzPattern.TWO_SHORT)
    val callStyle by settings.callBuzzStyle.collectAsState(CallBuzz.STEADY)
    var previewError by remember { mutableStateOf<String?>(null) }
    var callPreviewError by remember { mutableStateOf<String?>(null) }
    val quiet by settings.quietHours.collectAsState(QuietHours())
    // Which end of the quiet hours is being picked: true for From, false for To.
    var pickingFrom by remember { mutableStateOf<Boolean?>(null) }

    // The user may grant access in system settings and come back.
    LifecycleResumeEffect(Unit) { alerts.refresh(); onPauseOrDispose { } }

    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alerts.refresh()
        if (granted) scope.launch { settings.setBuzzOnCalls(true) }
    }
    val openNotificationAccess = {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, StrapNotificationListener::class.java).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Unit
    }

    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.common_phone_alerts), onBack) }
        item {
            PagePanel {
                PageSwitchRow(
                    tr(R.string.phone_alerts_screen_incoming_calls), tr(R.string.phone_alerts_screen_buzzes_while_your_phone),
                    calls && callPermission,
                    { on ->
                        when {
                            !on -> scope.launch { settings.setBuzzOnCalls(false) }
                            callPermission -> scope.launch { settings.setBuzzOnCalls(true) }
                            else -> askPhone.launch(Manifest.permission.READ_PHONE_STATE)
                        }
                    },
                    divider = false,
                )
                if (calls && callPermission) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(R.string.phone_alerts_screen_call_buzz), style = GlowType.body, color = Color.White)
                        // Picking one plays it for a few seconds, so the two can be compared on the wrist.
                        GlowSegments(CallBuzz.entries.map { it.label }, callStyle.ordinal, { i ->
                            val style = CallBuzz.entries[i]
                            scope.launch {
                                settings.setCallBuzzStyle(style)
                                callPreviewError = runCatching { alerts.previewCall(style) }.exceptionOrNull()?.let { it.message ?: tr(R.string.common_couldn_t_buzz_the) }
                            }
                        })
                        callPreviewError?.let { PageNote(it, color = MetricColors.warn) }
                    }
                }
                PageSwitchRow(tr(R.string.phone_alerts_screen_notifications), tr(R.string.phone_alerts_screen_buzzes_for_notifications_that), notifications && notificationAccess, { on ->
                    scope.launch { settings.setBuzzOnNotifications(on) }
                    if (on && !notificationAccess) openNotificationAccess()
                })
                if (notifications && !notificationAccess) {
                    PageNote(tr(R.string.phone_alerts_screen_needs_notification_access), color = MetricColors.warn)
                    PageButton(tr(R.string.phone_alerts_screen_open_settings), openNotificationAccess, Modifier.fillMaxWidth())
                }
                if (notifications && notificationAccess) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(R.string.phone_alerts_screen_buzz_pattern), style = GlowType.body, color = Color.White)
                        // Picking a pattern plays it once, so it can be felt before the next notification.
                        GlowSegments(BuzzPattern.entries.map { it.label }, pattern.ordinal, { i ->
                            val p = BuzzPattern.entries[i]
                            scope.launch {
                                settings.setNotificationPattern(p)
                                previewError = runCatching { alerts.preview(p) }.exceptionOrNull()?.let { it.message ?: tr(R.string.common_couldn_t_buzz_the) }
                            }
                        })
                        previewError?.let { PageNote(it, color = MetricColors.warn) }
                    }
                    PageSwitchRow(tr(R.string.phone_alerts_screen_quiet_hours), quietHoursSummary(quiet, calls && callPermission), quiet.on, { on ->
                        scope.launch { settings.setQuietHours(quiet.copy(on = on)) }
                    })
                    if (quiet.on) {
                        PageRow(tr(R.string.phone_alerts_screen_from), quietClock(quiet.from), onClick = { pickingFrom = true }, divider = false)
                        PageRow(tr(R.string.phone_alerts_screen_to), quietClock(quiet.to), onClick = { pickingFrom = false })
                        if (calls && callPermission) {
                            PageSwitchRow(tr(R.string.phone_alerts_screen_calls_too), tr(R.string.phone_alerts_screen_off_lets_a_ringing), quiet.calls, { on ->
                                scope.launch { settings.setQuietHours(quiet.copy(calls = on)) }
                            })
                        }
                    }
                }
                // Do Not Disturb is said once, here, for calls and notifications both.
                PageNote(tr(R.string.phone_alerts_screen_follows_do_not_disturb))
            }
        }
    }

    pickingFrom?.let { from ->
        QuietTimeSheet(
            if (from) tr(R.string.phone_alerts_screen_quiet_from) else tr(R.string.phone_alerts_screen_quiet_until), if (from) quiet.from else quiet.to,
            onSave = { m ->
                pickingFrom = null
                scope.launch { settings.setQuietHours(if (from) quiet.copy(from = m) else quiet.copy(to = m)) }
            },
            onDismiss = { pickingFrom = null },
        )
    }
}

/** One end of the quiet hours on the alarms' clock, then Cancel and Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeSheet(title: String, minutes: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    val time = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
    PageSheet(onDismiss, title) {
        TimePicker(time, Modifier.align(Alignment.CenterHorizontally), colors = AlarmClockColors())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(tr(R.string.common_cancel), onDismiss, Modifier.weight(1f))
            PageButton(tr(R.string.common_save), { onSave(time.hour * 60 + time.minute) }, Modifier.weight(1f), primary = true)
        }
    }
}
