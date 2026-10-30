package org.openstrap.app.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openstrap.app.device.StrapCommandException
import org.openstrap.app.device.StrapController
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.AlarmCapabilities
import org.openstrap.protocol.services.Setting
import org.openstrap.protocol.services.SettingsCodec
import org.openstrap.protocol.services.SettingsGroup
import org.openstrap.app.R
import org.openstrap.app.tr

data class AlarmsUiState(
    val loading: Boolean = true,
    /** A write is in flight; controls are disabled meanwhile. */
    val busy: Boolean = false,
    val error: String? = null,
    val caps: AlarmCapabilities? = null,
    val alarms: List<Alarm> = emptyList(),
    val health: SettingsGroup? = null,
) {
    val canAdd: Boolean get() = caps != null && alarms.size < caps.maxCount

    /** The strap only raises HR alerts while it measures often enough; Zepp allows continuous or every minute. */
    val alertsAvailable: Boolean get() = health?.get(SettingsCodec.KEY_HR_INTERVAL)?.number?.toInt().let { it == SettingsCodec.HR_CONTINUOUS || it == 1 }
}

/** Reads alarms and health settings from the strap and writes changes back, re-reading after each write. */
class AlarmsViewModel(private val strap: StrapController) : ViewModel() {
    private val _state = MutableStateFlow(AlarmsUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
        // A one-time alarm switches itself off after ringing; show what the strap now holds.
        viewModelScope.launch { strap.alarmsChanged.collect { refresh() } }
        // A setting changed on the strap: apply it as a fresh read would (03 §3.6.1).
        viewModelScope.launch {
            strap.settingsChanged.collect { pushed ->
                _state.update { s -> s.health?.updatedWith(pushed)?.let { s.copy(health = it) } ?: s }
            }
        }
    }

    fun refresh() = run(loading = true) { }

    fun setEnabled(alarm: Alarm, enabled: Boolean) = run { strap.saveAlarm(alarm.copy(enabled = enabled), isNew = false) }

    fun save(alarm: Alarm, isNew: Boolean) = run { strap.saveAlarm(alarm, isNew) }

    fun delete(alarm: Alarm) = run { strap.deleteAlarm(alarm.index) }

    fun deleteAll() = run { strap.clearAlarms() }

    /** The lowest slot the strap isn't using, as Zepp picks it. */
    fun freeIndex(): Int? {
        val s = _state.value
        val used = s.alarms.map { it.index }.toSet()
        return (0 until (s.caps?.maxCount ?: return null)).firstOrNull { it !in used }
    }

    /**
     * Sets how often the strap measures HR. Like Zepp, continuous also turns on HR during activity, and a
     * rate too slow for alerts switches the HR alerts off.
     */
    fun setHrInterval(value: Int) = writeHealth { g ->
        buildList {
            add(SettingsCodec.enumSetting(SettingsCodec.KEY_HR_INTERVAL, value))
            if (value == SettingsCodec.HR_CONTINUOUS && g[SettingsCodec.KEY_ACTIVITY_HR] != null) add(SettingsCodec.boolSetting(SettingsCodec.KEY_ACTIVITY_HR, true))
            if (value != SettingsCodec.HR_CONTINUOUS && value != 1) {
                for (key in listOf(SettingsCodec.KEY_HIGH_HR_ALERT, SettingsCodec.KEY_LOW_HR_ALERT)) {
                    if ((g[key]?.number ?: 0L) != 0L) add(SettingsCodec.enumSetting(key, 0))
                }
            }
        }
    }

    fun setEnum(key: Int, value: Int) = writeHealth { listOf(SettingsCodec.enumSetting(key, value)) }

    private fun writeHealth(build: (SettingsGroup) -> List<Setting>) = run {
        val g = _state.value.health ?: throw StrapCommandException(tr(R.string.alarms_view_model_settings_haven_t_loaded))
        strap.writeHealthSettings(g.version, build(g))
    }

    /** Runs [action] against the strap, then re-reads everything so the screen shows what the strap holds. */
    private fun run(loading: Boolean = false, action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, loading = loading || it.loading, error = null) }
        viewModelScope.launch {
            val error = try {
                action()
                null
            } catch (e: StrapCommandException) {
                e.message
            }
            val read = runCatching {
                val (caps, alarms) = strap.readAlarms()
                Triple(caps, alarms.sortedWith(compareBy({ it.hour }, { it.minute })), strap.readHealthSettings())
            }
            _state.update { s ->
                read.fold(
                    { (caps, alarms, health) -> s.copy(loading = false, busy = false, error = error, caps = caps, alarms = alarms, health = health) },
                    { e -> s.copy(loading = false, busy = false, error = error ?: e.message ?: tr(R.string.alarms_view_model_couldn_t_read_from)) },
                )
            }
        }
    }
}
