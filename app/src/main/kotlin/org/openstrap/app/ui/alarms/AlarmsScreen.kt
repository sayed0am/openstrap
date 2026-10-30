package org.openstrap.app.ui.alarms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import org.openstrap.app.ui.glow.DestructiveInk
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.ControlSurface
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageChip
import org.openstrap.app.ui.glow.PageConfirm
import org.openstrap.app.ui.glow.PageSheet
import org.openstrap.app.ui.glow.PageTextButton
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageMenuRow
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PagePanel
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.SettingsCodec
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

private val DAY_LETTERS: List<String> get() = trRaw(R.string.missed_day_letters).split(',')
private val DAY_NAMES = listOf(tr(R.string.alarms_screen_mon), tr(R.string.alarms_screen_tue), tr(R.string.alarms_screen_wed), tr(R.string.alarms_screen_thu), tr(R.string.alarms_screen_fri), tr(R.string.alarms_screen_sat), tr(R.string.alarms_screen_sun))

/** "Once", "Every day", "Weekdays", "Weekends" or "Mon, Wed, Fri". */
fun daysLabel(days: Int): String = when (days) {
    0 -> tr(R.string.alarms_screen_once)
    Alarm.EVERY_DAY -> tr(R.string.alarms_screen_every_day)
    Alarm.WEEKDAYS -> tr(R.string.alarms_screen_weekdays)
    Alarm.WEEKEND -> tr(R.string.alarms_screen_weekends)
    Alarm.WORKDAYS -> tr(R.string.alarms_screen_workdays)
    else -> DAY_NAMES.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
}

private fun intervalLabel(v: Int) = when (v) {
    0 -> tr(R.string.common_off)
    SettingsCodec.HR_CONTINUOUS -> tr(R.string.alarms_screen_continuous)
    else -> tr(R.string.alarms_screen_every_min, v)
}

/**
 * What the Strap page's Alarms row says: the first alarm that's on ("06:40 weekdays"), how many more, "None", or null
 * while nothing has been read from the strap.
 */
fun alarmsSummary(state: AlarmsUiState): String? {
    if (state.caps == null) return null
    val on = state.alarms.filter { it.enabled }
    val first = on.firstOrNull() ?: return if (state.alarms.isEmpty()) tr(R.string.common_none) else tr(R.string.alarms_screen_all_off)
    val text = "%02d:%02d %s".format(first.hour, first.minute, daysLabel(first.days).lowercase())
    return if (on.size > 1) tr(R.string.alarms_screen_and_more, text, on.size - 1) else text
}

/**
 * What the Strap page's Health alerts row says, short enough for one line beside the name ([ROW_VALUE_MAX]): the
 * heart rate limits ("Over 150, under 40", "Over 150 bpm"), the oxygen one ("Oxygen under 90%"), "Heart rate and
 * oxygen" when both kinds are on, "Off" when none is, or null while the strap's settings haven't been read.
 */
fun healthAlertsSummary(state: AlarmsUiState): String? {
    val g = state.health ?: return null
    fun on(key: Int) = g[key]?.number?.toInt()?.takeIf { it != 0 }
    val high = on(SettingsCodec.KEY_HIGH_HR_ALERT)
    val low = on(SettingsCodec.KEY_LOW_HR_ALERT)
    val oxygen = on(SettingsCodec.KEY_LOW_SPO2_ALERT)
    val heart = high != null || low != null
    return when {
        heart && oxygen != null -> tr(R.string.alarms_screen_heart_rate_and_oxygen)
        high != null && low != null -> tr(R.string.alarms_screen_over_under, high, low)
        high != null -> tr(R.string.alarms_screen_over_bpm, high)
        low != null -> tr(R.string.alarms_screen_under_bpm, low)
        oxygen != null -> tr(R.string.alarms_screen_oxygen_under, oxygen)
        else -> tr(R.string.common_off)
    }
}

/** The longest value a Strap page row shows beside its name and still keeps to one line on a 360 dp phone. */
const val ROW_VALUE_MAX = 22

/** The strap's alarms: each with its days and a switch, add and delete all. Opened from the Strap page. */
@Composable
fun AlarmsScreen(
    vm: AlarmsViewModel,
    state: AlarmsUiState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var editing by remember { mutableStateOf<Pair<Alarm, Boolean>?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    if (confirmDeleteAll) {
        PageConfirm(
            tr(R.string.alarms_screen_delete_all_alarms), tr(R.string.alarms_screen_this_removes_every_alarm),
            confirm = tr(R.string.alarms_screen_delete_all), onConfirm = { vm.deleteAll(); confirmDeleteAll = false }, onDismiss = { confirmDeleteAll = false },
        )
    }
    editing?.let { (alarm, isNew) ->
        AlarmSheet(
            alarm, isNew,
            onSave = { vm.save(it, isNew); editing = null },
            onDelete = { vm.delete(alarm); editing = null },
            onDismiss = { editing = null },
        )
    }
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.common_alarms), onBack) }
        state.error?.let { msg -> item { ErrorPanel(msg, state.busy, vm::refresh) } }
        if (state.loading) {
            item { PagePanel { PageNote(tr(R.string.alarms_screen_reading_from_the_strap)) } }
            return@ScreenList
        }
        item {
            AlarmsPanel(state, onToggle = vm::setEnabled, onEdit = { editing = it to false }, onAdd = {
                vm.freeIndex()?.let { editing = Alarm(it, 7, 0, Alarm.WEEKDAYS, enabled = true) to true }
            }, onDeleteAll = { confirmDeleteAll = true })
        }
    }
}

/** How often the strap measures heart rate, and its heart rate and blood oxygen alerts. Opened from the Strap page. */
@Composable
fun HealthAlertsScreen(vm: AlarmsViewModel, state: AlarmsUiState, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.common_health_alerts), onBack) }
        state.error?.let { msg -> item { ErrorPanel(msg, state.busy, vm::refresh) } }
        if (state.loading) {
            item { PagePanel { PageNote(tr(R.string.alarms_screen_reading_from_the_strap)) } }
            return@ScreenList
        }
        state.health?.let { item { HeartAlertsPanel(state, vm) } }
    }
}

@Composable
private fun ErrorPanel(message: String, busy: Boolean, onRetry: () -> Unit) {
    PagePanel {
        PageNote(message, color = MetricColors.warn)
        PageButton(tr(R.string.common_try_again), onRetry, Modifier.fillMaxWidth(), enabled = !busy)
    }
}

@Composable
private fun AlarmsPanel(
    state: AlarmsUiState,
    onToggle: (Alarm, Boolean) -> Unit,
    onEdit: (Alarm) -> Unit,
    onAdd: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    PagePanel(title = tr(R.string.common_on_the_strap), note = state.caps?.let { tr(R.string.missed_n_of_max, state.alarms.size, it.maxCount) }) {
        if (state.alarms.isEmpty()) PageNote(tr(R.string.alarms_screen_no_alarms_on_the))
        state.alarms.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { onEdit(a) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    org.openstrap.app.ui.glow.DotMatrixText(
                        "%02d:%02d".format(a.hour, a.minute), dot = org.openstrap.app.ui.glow.DotSize.Edge,
                        color = if (a.enabled) Color.White else Color.White.copy(alpha = 0.45f),
                    )
                    Text(daysLabel(a.days), style = GlowType.secondary, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp))
                }
                org.openstrap.app.ui.glow.GlowSwitch(a.enabled, { onToggle(a, it) }, enabled = !state.busy)
            }
        }
        if (state.canAdd) PageButton(tr(R.string.alarms_screen_add_alarm), onAdd, Modifier.fillMaxWidth(), enabled = !state.busy, primary = true)
        if (state.alarms.size > 1) {
            PageTextButton(tr(R.string.alarms_screen_delete_all), onDeleteAll, Modifier.align(Alignment.CenterHorizontally), enabled = !state.busy, color = DestructiveInk)
        }
        // Zepp's tip for this strap (alarm_stuttgart_close_tips).
        PageNote(tr(R.string.alarms_screen_the_strap_vibrates_at))
    }
}

@Composable
private fun HeartAlertsPanel(state: AlarmsUiState, vm: AlarmsViewModel) {
    val g = state.health ?: return
    val alerts = state.alertsAvailable
    val off: (Int) -> Boolean = { it == 0 }
    PagePanel(title = tr(R.string.alarms_screen_heart_rate_and_blood)) {
        // Rows with a menu each, in place of 21 chips; the measuring note only when it turns the heart rate alerts off.
        Column {
            var first = true
            fun divider() = !first.also { first = false }
            g[SettingsCodec.KEY_HR_INTERVAL]?.let { s ->
                PageMenuRow(
                    tr(R.string.alarms_screen_heart_rate_measuring), s.choices.orEmpty(), s.number?.toInt(), ::intervalLabel, { vm.setHrInterval(it) },
                    divider = divider(), enabled = !state.busy, quiet = off,
                    note = if (alerts) null else tr(R.string.alarms_screen_heart_rate_alerts_need_2),
                )
            }
            g[SettingsCodec.KEY_HIGH_HR_ALERT]?.let { s ->
                PageMenuRow(
                    tr(R.string.alarms_screen_high_heart_rate_alert), s.choices.orEmpty(), s.number?.toInt(), { if (it == 0) tr(R.string.common_off) else tr(R.string.alarms_screen_above, it) },
                    { vm.setEnum(SettingsCodec.KEY_HIGH_HR_ALERT, it) }, divider = divider(), enabled = !state.busy && alerts, quiet = off,
                )
            }
            g[SettingsCodec.KEY_LOW_HR_ALERT]?.let { s ->
                PageMenuRow(
                    tr(R.string.alarms_screen_low_heart_rate_alert), s.choices.orEmpty(), s.number?.toInt(), { if (it == 0) tr(R.string.common_off) else tr(R.string.alarms_screen_below, it) },
                    { vm.setEnum(SettingsCodec.KEY_LOW_HR_ALERT, it) }, divider = divider(), enabled = !state.busy && alerts, quiet = off,
                )
            }
            g[SettingsCodec.KEY_LOW_SPO2_ALERT]?.let { s ->
                PageMenuRow(
                    tr(R.string.alarms_screen_low_blood_oxygen_alert), s.choices.orEmpty(), s.number?.toInt(), { if (it == 0) tr(R.string.common_off) else tr(R.string.alarms_screen_below_4, it) },
                    { vm.setEnum(SettingsCodec.KEY_LOW_SPO2_ALERT, it) }, divider = divider(), enabled = !state.busy, quiet = off,
                )
            }
        }
        PageNote(tr(R.string.alarms_screen_the_strap_vibrates_when))
    }
}

/**
 * A new alarm or one being changed, in a sheet: the time, the days as chips, then Cancel and Save, with Delete as quiet
 * text under them. The time wheel is Material's clock, recoloured to white on the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmSheet(alarm: Alarm, isNew: Boolean, onSave: (Alarm) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val time = rememberTimePickerState(alarm.hour, alarm.minute, is24Hour = true)
    var days by remember { mutableIntStateOf(alarm.days) }
    PageSheet(onDismiss, if (isNew) tr(R.string.alarms_screen_new_alarm) else tr(R.string.alarms_screen_edit_alarm), note = daysLabel(days)) {
        TimePicker(time, Modifier.align(Alignment.CenterHorizontally), colors = AlarmClockColors())
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            DAY_LETTERS.forEachIndexed { i, d ->
                val bit = 1 shl i
                // Picking a day replaces the strap's own "workdays" calendar with a plain day set.
                PageChip(d, days != Alarm.WORKDAYS && days and bit != 0, {
                    days = (if (days == Alarm.WORKDAYS) 0 else days) xor bit
                }, Modifier.widthIn(min = 40.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(tr(R.string.common_cancel), onDismiss, Modifier.weight(1f))
            PageButton(tr(R.string.common_save), { onSave(alarm.copy(hour = time.hour, minute = time.minute, days = days, enabled = true)) }, Modifier.weight(1f), primary = true)
        }
        if (!isNew) PageTextButton(tr(R.string.alarms_screen_delete_alarm), onDelete, Modifier.align(Alignment.CenterHorizontally), color = DestructiveInk)
    }
}

/** The clock in the page's colours: white for the chosen hand and digits, the Control surface for the rest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlarmClockColors() = TimePickerDefaults.colors(
    clockDialColor = ControlSurface,
    clockDialSelectedContentColor = Color.Black,
    clockDialUnselectedContentColor = Color.White,
    selectorColor = Color.White,
    containerColor = Color.Transparent,
    timeSelectorSelectedContainerColor = Color.White,
    timeSelectorUnselectedContainerColor = ControlSurface,
    timeSelectorSelectedContentColor = Color.Black,
    timeSelectorUnselectedContentColor = Color.White,
)
