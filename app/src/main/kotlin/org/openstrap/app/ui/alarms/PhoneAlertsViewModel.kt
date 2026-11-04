package org.openstrap.app.ui.alarms

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openstrap.app.data.catching
import org.openstrap.app.data.settings.BuzzPattern
import org.openstrap.app.data.settings.CallBuzz
import org.openstrap.app.data.settings.QuietHours
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.PhoneAlerts

/** What the Phone alerts page needs from [PhoneAlerts]: the two accesses and the buzz previews. */
interface BuzzAlerts {
    val callPermission: StateFlow<Boolean>
    val notificationAccess: StateFlow<Boolean>
    fun refresh()
    fun hasCallPermission(): Boolean
    suspend fun preview(pattern: BuzzPattern)
    suspend fun previewCall(style: CallBuzz)
}

/**
 * The Phone alerts page: buzzing on calls and notifications, the patterns and quiet hours. Every write runs here, so
 * leaving the page mid-write doesn't cancel it, and quiet hours change from what is stored, never from the screen's
 * copy, which reads the defaults until the store has answered (audit A2).
 */
class PhoneAlertsViewModel(private val settings: SettingsStore, private val alerts: BuzzAlerts) : ViewModel() {
    constructor(alerts: PhoneAlerts, settings: SettingsStore) : this(settings, alerts.asBuzzAlerts())

    val calls: StateFlow<Boolean> = settings.buzzOnCalls.state(false)
    val notifications: StateFlow<Boolean> = settings.buzzOnNotifications.state(false)
    val pattern: StateFlow<BuzzPattern> = settings.notificationPattern.state(BuzzPattern.TWO_SHORT)
    val callStyle: StateFlow<CallBuzz> = settings.callBuzzStyle.state(CallBuzz.STEADY)
    val quiet: StateFlow<QuietHours> = settings.quietHours.state(QuietHours())
    val callPermission: StateFlow<Boolean> get() = alerts.callPermission
    val notificationAccess: StateFlow<Boolean> get() = alerts.notificationAccess

    private val _previewError = MutableStateFlow<String?>(null)
    /** Why the last notification pattern didn't play on the strap, or null. */
    val previewError = _previewError.asStateFlow()

    private val _callPreviewError = MutableStateFlow<String?>(null)
    /** Why the last call buzz didn't play on the strap, or null. */
    val callPreviewError = _callPreviewError.asStateFlow()

    private val quietWrites = Mutex()

    fun refresh() = alerts.refresh()

    fun hasCallPermission(): Boolean = alerts.hasCallPermission()

    fun setBuzzOnCalls(on: Boolean) = viewModelScope.launch { settings.setBuzzOnCalls(on) }

    fun setBuzzOnNotifications(on: Boolean) = viewModelScope.launch { settings.setBuzzOnNotifications(on) }

    /** Keeps [style] and plays it for a few seconds, so the two can be compared on the wrist. */
    fun pickCallBuzz(style: CallBuzz) = viewModelScope.launch {
        settings.setCallBuzzStyle(style)
        _callPreviewError.value = catching { alerts.previewCall(style) }.exceptionOrNull()?.let { it.message ?: noBuzz }
    }

    /** Keeps [pattern] and plays it once, so it can be felt before the next notification. */
    fun pickPattern(pattern: BuzzPattern) = viewModelScope.launch {
        settings.setNotificationPattern(pattern)
        _previewError.value = catching { alerts.preview(pattern) }.exceptionOrNull()?.let { it.message ?: noBuzz }
    }

    /** The page closed: a failed preview isn't said again next time, as before the page had a view model. */
    fun clearPreviewErrors() {
        _previewError.value = null
        _callPreviewError.value = null
    }

    /** Quiet hours as [change] makes them from what is stored. One change at a time, so two quick taps both count. */
    fun updateQuietHours(change: (QuietHours) -> QuietHours) = viewModelScope.launch {
        quietWrites.withLock { settings.setQuietHours(change(settings.quietHours.first())) }
    }

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    /** What a preview that failed without a reason of its own says. */
    private val noBuzz: String get() = Strings.app.get(R.string.you_strap_buzz_failed)
}

private fun PhoneAlerts.asBuzzAlerts(): BuzzAlerts = object : BuzzAlerts {
    override val callPermission: StateFlow<Boolean> get() = this@asBuzzAlerts.callPermission
    override val notificationAccess: StateFlow<Boolean> get() = this@asBuzzAlerts.notificationAccess
    override fun refresh() = this@asBuzzAlerts.refresh()
    override fun hasCallPermission(): Boolean = this@asBuzzAlerts.hasCallPermission()
    override suspend fun preview(pattern: BuzzPattern) = this@asBuzzAlerts.preview(pattern)
    override suspend fun previewCall(style: CallBuzz) = this@asBuzzAlerts.previewCall(style)
}
