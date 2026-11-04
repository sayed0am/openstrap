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
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import androidx.compose.ui.graphics.Color
import org.openstrap.app.ui.glow.DestructiveInk
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.theme.ControlSurface
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
import org.openstrap.app.presentation.ClockSetting
import org.openstrap.app.presentation.TimeOfDay
import java.time.LocalTime

/** The days of the week, short, Monday first: "Mon", for an alarm on chosen days. */
private val DAY_NAMES = listOf(
    R.string.you_alarms_day_mon, R.string.you_alarms_day_tue, R.string.you_alarms_day_wed, R.string.you_alarms_day_thu,
    R.string.you_alarms_day_fri, R.string.you_alarms_day_sat, R.string.you_alarms_day_sun,
)

/** The days of the week as one letter, Monday first, on the alarm sheet's chips. */
private val DAY_LETTERS = listOf(
    R.string.you_alarms_letter_mon, R.string.you_alarms_letter_tue, R.string.you_alarms_letter_wed, R.string.you_alarms_letter_thu,
    R.string.you_alarms_letter_fri, R.string.you_alarms_letter_sat, R.string.you_alarms_letter_sun,
)

/** "Once", "Every day", "Weekdays", "Weekends" or "Mon, Wed, Fri". */
fun daysLabel(days: Int, strings: Strings = Strings.app): String = when (days) {
    0 -> strings.get(R.string.you_alarms_days_once)
    Alarm.EVERY_DAY -> strings.get(R.string.you_alarms_days_every_day)
    Alarm.WEEKDAYS -> strings.get(R.string.you_alarms_days_weekdays)
    Alarm.WEEKEND -> strings.get(R.string.you_alarms_days_weekends)
    Alarm.WORKDAYS -> strings.get(R.string.you_alarms_days_workdays)
    else -> DAY_NAMES.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ") { strings.get(it) }
}

/** How often the strap measures heart rate, as its menu says it. */
private fun intervalLabel(v: Int, strings: Strings = Strings.app) = when (v) {
    0 -> strings.get(R.string.you_value_off)
    SettingsCodec.HR_CONTINUOUS -> strings.get(R.string.you_alarms_interval_continuous)
    else -> strings.format(R.string.you_alarms_interval_every, v)
}

/**
 * What the Strap page's Alarms row says: the first alarm that's on ("06:40 weekdays"), how many more, "None", or null
 * while nothing has been read from the strap.
 */
fun alarmsSummary(state: AlarmsUiState, strings: Strings = Strings.app): String? {
    if (state.caps == null) return null
    val on = state.alarms.filter { it.enabled }
    val first = on.firstOrNull()
        ?: return strings.get(if (state.alarms.isEmpty()) R.string.you_alarms_summary_none else R.string.you_alarms_summary_all_off)
    val text = strings.format(
        R.string.you_alarms_summary_first,
        TimeOfDay.format(LocalTime.of(first.hour, first.minute)),
        daysLabel(first.days, strings).lowercase(),
    )
    val more = on.size - 1
    return if (more > 0) strings.plural(R.plurals.you_alarms_summary_more, more, more, text) else text
}

/**
 * What the Strap page's Health alerts row says, short enough for one line beside the name ([ROW_VALUE_MAX]): the
 * heart rate limits ("Over 150, under 40", "Over 150 bpm"), the oxygen one ("Oxygen under 90%"), "Heart rate and
 * oxygen" when both kinds are on, "Off" when none is, or null while the strap's settings haven't been read.
 */
fun healthAlertsSummary(state: AlarmsUiState, strings: Strings = Strings.app): String? {
    val g = state.health ?: return null
    fun on(key: Int) = g[key]?.number?.toInt()?.takeIf { it != 0 }
    val high = on(SettingsCodec.KEY_HIGH_HR_ALERT)
    val low = on(SettingsCodec.KEY_LOW_HR_ALERT)
    val oxygen = on(SettingsCodec.KEY_LOW_SPO2_ALERT)
    val heart = high != null || low != null
    return when {
        heart && oxygen != null -> strings.get(R.string.you_health_summary_both)
        high != null && low != null -> strings.format(R.string.you_health_summary_over_under, high, low)
        high != null -> strings.format(R.string.you_health_summary_over, high)
        low != null -> strings.format(R.string.you_health_summary_under, low)
        oxygen != null -> strings.format(R.string.you_health_summary_oxygen, oxygen)
        else -> strings.get(R.string.you_value_off)
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
            stringResource(R.string.you_alarms_delete_all_title), stringResource(R.string.you_alarms_delete_all_text),
            confirm = stringResource(R.string.you_alarms_delete_all), onConfirm = { vm.deleteAll(); confirmDeleteAll = false },
            onDismiss = { confirmDeleteAll = false },
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
        item { PageHeader(stringResource(R.string.you_alarms_title), onBack) }
        state.error?.let { msg -> item { ErrorPanel(msg, state.busy, vm::refresh) } }
        if (state.loading) {
            item { PagePanel { PageNote(stringResource(R.string.you_alarms_reading)) } }
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
        item { PageHeader(stringResource(R.string.you_health_title), onBack) }
        state.error?.let { msg -> item { ErrorPanel(msg, state.busy, vm::refresh) } }
        if (state.loading) {
            item { PagePanel { PageNote(stringResource(R.string.you_alarms_reading)) } }
            return@ScreenList
        }
        state.health?.let { item { HeartAlertsPanel(state, vm) } }
    }
}

@Composable
private fun ErrorPanel(message: String, busy: Boolean, onRetry: () -> Unit) {
    PagePanel {
        PageNote(message, color = MetricColors.warn)
        PageButton(stringResource(R.string.you_alarms_try_again), onRetry, Modifier.fillMaxWidth(), enabled = !busy)
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
    PagePanel(
        title = stringResource(R.string.you_alarms_on_strap),
        note = state.caps?.let { stringResource(R.string.you_alarms_count, state.alarms.size, it.maxCount) },
    ) {
        if (state.alarms.isEmpty()) PageNote(stringResource(R.string.you_alarms_none))
        state.alarms.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { onEdit(a) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val ink = if (a.enabled) Color.White else Color.White.copy(alpha = 0.45f)
                    val (digits, marker) = dotClock(a.hour, a.minute)
                    Row(verticalAlignment = Alignment.Bottom) {
                        org.openstrap.app.ui.glow.DotMatrixText(digits, dot = org.openstrap.app.ui.glow.draw.DotSize.Edge, color = ink)
                        marker?.let { Text(it, style = GlowType.secondary, color = ink, modifier = Modifier.padding(start = 6.dp)) }
                    }
                    Text(daysLabel(a.days), style = GlowType.secondary, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp))
                }
                org.openstrap.app.ui.glow.GlowSwitch(a.enabled, { onToggle(a, it) }, enabled = !state.busy)
            }
        }
        if (state.canAdd) {
            PageButton(stringResource(R.string.you_alarms_add), onAdd, Modifier.fillMaxWidth(), enabled = !state.busy, primary = true)
        }
        if (state.alarms.size > 1) {
            PageTextButton(
                stringResource(R.string.you_alarms_delete_all), onDeleteAll, Modifier.align(Alignment.CenterHorizontally),
                enabled = !state.busy, color = DestructiveInk,
            )
        }
        // Zepp's tip for this strap (alarm_stuttgart_close_tips).
        PageNote(stringResource(R.string.you_alarms_tip))
    }
}

@Composable
private fun HeartAlertsPanel(state: AlarmsUiState, vm: AlarmsViewModel) {
    val g = state.health ?: return
    val alerts = state.alertsAvailable
    val off: (Int) -> Boolean = { it == 0 }
    // A health alert's value in its menu: off, or the limit by [limit] ("Above 150", "Below 90%").
    fun alertLabel(v: Int, limit: Int) = if (v == 0) Strings.app.get(R.string.you_value_off) else Strings.app.format(limit, v)
    PagePanel(title = stringResource(R.string.you_health_panel)) {
        // Rows with a menu each, in place of 21 chips; the measuring note only when it turns the heart rate alerts off.
        Column {
            var first = true
            fun divider() = !first.also { first = false }
            g[SettingsCodec.KEY_HR_INTERVAL]?.let { s ->
                PageMenuRow(
                    stringResource(R.string.you_health_measuring), s.choices.orEmpty(), s.number?.toInt(), { intervalLabel(it) },
                    { vm.setHrInterval(it) }, divider = divider(), enabled = !state.busy, quiet = off,
                    note = if (alerts) null else stringResource(R.string.you_health_measuring_note),
                )
            }
            g[SettingsCodec.KEY_HIGH_HR_ALERT]?.let { s ->
                PageMenuRow(
                    stringResource(R.string.you_health_high), s.choices.orEmpty(), s.number?.toInt(),
                    { alertLabel(it, R.string.you_health_above) },
                    { vm.setEnum(SettingsCodec.KEY_HIGH_HR_ALERT, it) }, divider = divider(), enabled = !state.busy && alerts, quiet = off,
                )
            }
            g[SettingsCodec.KEY_LOW_HR_ALERT]?.let { s ->
                PageMenuRow(
                    stringResource(R.string.you_health_low), s.choices.orEmpty(), s.number?.toInt(),
                    { alertLabel(it, R.string.you_health_below) },
                    { vm.setEnum(SettingsCodec.KEY_LOW_HR_ALERT, it) }, divider = divider(), enabled = !state.busy && alerts, quiet = off,
                )
            }
            g[SettingsCodec.KEY_LOW_SPO2_ALERT]?.let { s ->
                PageMenuRow(
                    stringResource(R.string.you_health_oxygen), s.choices.orEmpty(), s.number?.toInt(),
                    { alertLabel(it, R.string.you_health_below_percent) },
                    { vm.setEnum(SettingsCodec.KEY_LOW_SPO2_ALERT, it) }, divider = divider(), enabled = !state.busy, quiet = off,
                )
            }
        }
        PageNote(stringResource(R.string.you_health_note))
    }
}

/**
 * A new alarm or one being changed, in a sheet: the time, the days as chips, then Cancel and Save, with Delete as quiet
 * text under them. The time wheel is Material's clock, recoloured to white on the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmSheet(alarm: Alarm, isNew: Boolean, onSave: (Alarm) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val time = rememberTimePickerState(alarm.hour, alarm.minute, is24Hour = ClockSetting.use24Hour)
    var days by remember { mutableIntStateOf(alarm.days) }
    PageSheet(onDismiss, stringResource(if (isNew) R.string.you_alarms_new else R.string.you_alarms_edit), note = daysLabel(days)) {
        TimePicker(time, Modifier.align(Alignment.CenterHorizontally), colors = AlarmClockColors())
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            DAY_LETTERS.forEachIndexed { i, d ->
                val bit = 1 shl i
                // Picking a day replaces the strap's own "workdays" calendar with a plain day set.
                PageChip(stringResource(d), days != Alarm.WORKDAYS && days and bit != 0, {
                    days = (if (days == Alarm.WORKDAYS) 0 else days) xor bit
                }, Modifier.widthIn(min = 40.dp), description = stringResource(DAY_NAMES[i]))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(stringResource(R.string.you_action_cancel), onDismiss, Modifier.weight(1f))
            PageButton(
                stringResource(R.string.you_action_save),
                { onSave(alarm.copy(hour = time.hour, minute = time.minute, days = days, enabled = true)) }, Modifier.weight(1f),
                primary = true,
            )
        }
        if (!isNew) {
            PageTextButton(
                stringResource(R.string.you_alarms_delete), onDelete, Modifier.align(Alignment.CenterHorizontally), color = DestructiveInk,
            )
        }
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
