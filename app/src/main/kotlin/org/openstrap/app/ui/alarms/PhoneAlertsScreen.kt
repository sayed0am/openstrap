package org.openstrap.app.ui.alarms

import android.Manifest
import android.content.ComponentName
import android.content.Intent
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openstrap.app.data.settings.BuzzPattern
import org.openstrap.app.data.settings.CallBuzz
import org.openstrap.app.data.settings.CallBuzzLength
import org.openstrap.app.data.settings.QuietHours
import org.openstrap.app.device.BlePermissions
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
import org.openstrap.app.presentation.ClockSetting
import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.TimeOfDay

/** What the Strap page's Phone alerts row says: "Calls and notifications", "Calls", "Notifications" or "Off". */
fun phoneAlertsSummary(calls: Boolean, notifications: Boolean, strings: Strings = Strings.app): String = strings.get(
    when {
        calls && notifications -> R.string.you_phone_summary_both
        calls -> R.string.you_phone_summary_calls
        notifications -> R.string.you_phone_summary_notifications
        else -> R.string.you_value_off
    },
)

/** Minutes after midnight on the phone's clock: "22:00", or "10:00 PM". */
internal fun quietClock(minutes: Int): String = TimeOfDay.minutes(minutes)

/** Under the Quiet hours switch: what it holds back, and when. */
internal fun quietHoursSummary(q: QuietHours, callsOn: Boolean, strings: Strings = Strings.app): String = when {
    !q.on -> strings.get(R.string.you_phone_quiet_off)
    q.from == q.to -> strings.get(R.string.you_phone_quiet_same)
    q.calls && callsOn -> strings.format(R.string.you_phone_quiet_with_calls, Ranges.of(quietClock(q.from), quietClock(q.to)))
    else -> strings.format(R.string.you_phone_quiet_notifications, Ranges.of(quietClock(q.from), quietClock(q.to)))
}

/**
 * Buzzing the strap on calls and notifications, opened from the Strap page. These are phone settings, so they work
 * offline too. (The off-body reminder is a row of its own on the Strap page.)
 */
@Composable
fun PhoneAlertsScreen(vm: PhoneAlertsViewModel, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val calls by vm.calls.collectAsStateWithLifecycle()
    val notifications by vm.notifications.collectAsStateWithLifecycle()
    val callPermission by vm.callPermission.collectAsStateWithLifecycle()
    val notificationAccess by vm.notificationAccess.collectAsStateWithLifecycle()
    val pattern by vm.pattern.collectAsStateWithLifecycle()
    val callStyle by vm.callStyle.collectAsStateWithLifecycle()
    val callLength by vm.callLength.collectAsStateWithLifecycle()
    val previewError by vm.previewError.collectAsStateWithLifecycle()
    val callPreviewError by vm.callPreviewError.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.clearPreviewErrors() } }
    val quiet by vm.quiet.collectAsStateWithLifecycle()
    // Which end of the quiet hours is being picked: true for From, false for To.
    var pickingFrom by remember { mutableStateOf<Boolean?>(null) }


    // Phone access turned down for good: Android shows no dialog any more, so the switch offers settings instead (audit E5).
    val activity = androidx.activity.compose.LocalActivity.current
    var phoneBlocked by rememberSaveable { mutableStateOf(false) }
    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refresh()
        if (granted) vm.setBuzzOnCalls(true)
        phoneBlocked = !granted && activity != null &&
            BlePermissions.turnedDownForGood(activity, listOf(Manifest.permission.READ_PHONE_STATE))
    }
    val openAppSettings = { context.startActivity(BlePermissions.settingsIntent(context)) }
    // The user may grant access in system settings and come back. Phone access turned on there counts afresh, so a
    // later turn-down asks again before it counts as for good.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        if (vm.hasCallPermission()) phoneBlocked = false
        onPauseOrDispose { }
    }
    val openNotificationAccess = {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, StrapNotificationListener::class.java).flattenToString())
        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Unit
    }

    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(stringResource(R.string.you_phone_title), onBack) }
        item {
            PagePanel {
                PageSwitchRow(
                    stringResource(R.string.you_phone_calls), stringResource(R.string.you_phone_calls_note),
                    calls && callPermission,
                    { on ->
                        when {
                            !on -> vm.setBuzzOnCalls(false)
                            callPermission -> vm.setBuzzOnCalls(true)
                            phoneBlocked -> openAppSettings()
                            else -> askPhone.launch(Manifest.permission.READ_PHONE_STATE)
                        }
                    },
                    divider = false,
                )
                if (phoneBlocked && !callPermission) {
                    PageNote(stringResource(R.string.you_phone_calls_blocked), color = MetricColors.warn)
                    PageButton(stringResource(R.string.you_phone_open_settings), openAppSettings, Modifier.fillMaxWidth())
                }
                if (calls && callPermission) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.you_phone_call_buzz), style = GlowType.body, color = Color.White)
                        // Picking one plays it for a few seconds, so the two can be compared on the wrist.
                        GlowSegments(CallBuzz.entries.map { it.label }, callStyle.ordinal, { i -> vm.pickCallBuzz(CallBuzz.entries[i]) })
                        callPreviewError?.let { PageNote(it, color = MetricColors.warn) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.you_phone_call_length), style = GlowType.body, color = Color.White)
                        GlowSegments(
                            CallBuzzLength.entries.map { it.label }, callLength.ordinal,
                            { i -> vm.pickCallLength(CallBuzzLength.entries[i]) },
                        )
                    }
                }
                PageSwitchRow(
                    stringResource(R.string.you_phone_notifications), stringResource(R.string.you_phone_notifications_note),
                    notifications && notificationAccess,
                    { on ->
                        vm.setBuzzOnNotifications(on)
                        if (on && !notificationAccess) openNotificationAccess()
                    },
                )
                if (notifications && !notificationAccess) {
                    PageNote(stringResource(R.string.you_phone_notifications_access), color = MetricColors.warn)
                    PageButton(stringResource(R.string.you_phone_open_settings), openNotificationAccess, Modifier.fillMaxWidth())
                }
                if (notifications && notificationAccess) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.you_phone_pattern), style = GlowType.body, color = Color.White)
                        // Picking a pattern plays it once, so it can be felt before the next notification.
                        GlowSegments(BuzzPattern.entries.map { it.label }, pattern.ordinal, { i -> vm.pickPattern(BuzzPattern.entries[i]) })
                        previewError?.let { PageNote(it, color = MetricColors.warn) }
                    }
                    PageSwitchRow(
                        stringResource(R.string.you_phone_quiet), quietHoursSummary(quiet, calls && callPermission), quiet.on,
                        { on -> vm.updateQuietHours { it.copy(on = on) } },
                    )
                    if (quiet.on) {
                        PageRow(
                            stringResource(R.string.you_phone_quiet_from), quietClock(quiet.from), onClick = { pickingFrom = true },
                            divider = false,
                        )
                        PageRow(stringResource(R.string.you_phone_quiet_to), quietClock(quiet.to), onClick = { pickingFrom = false })
                        if (calls && callPermission) {
                            PageSwitchRow(
                                stringResource(R.string.you_phone_quiet_calls), stringResource(R.string.you_phone_quiet_calls_note),
                                quiet.calls,
                                { on -> vm.updateQuietHours { it.copy(calls = on) } },
                            )
                        }
                    }
                }
                // Do Not Disturb is said once, here, for calls and notifications both.
                PageNote(stringResource(R.string.you_phone_note))
            }
        }
    }

    pickingFrom?.let { from ->
        QuietTimeSheet(
            stringResource(if (from) R.string.you_phone_quiet_from_title else R.string.you_phone_quiet_until_title),
            if (from) quiet.from else quiet.to,
            onSave = { m ->
                pickingFrom = null
                vm.updateQuietHours { if (from) it.copy(from = m) else it.copy(to = m) }
            },
            onDismiss = { pickingFrom = null },
        )
    }
}

/** One end of the quiet hours on the alarms' clock, then Cancel and Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeSheet(title: String, minutes: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    val time = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = ClockSetting.use24Hour)
    PageSheet(onDismiss, title) {
        TimePicker(time, Modifier.align(Alignment.CenterHorizontally), colors = AlarmClockColors())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(stringResource(R.string.you_action_cancel), onDismiss, Modifier.weight(1f))
            PageButton(
                stringResource(R.string.you_action_save), { onSave(time.hour * 60 + time.minute) }, Modifier.weight(1f), primary = true,
            )
        }
    }
}
