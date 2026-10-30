package org.openstrap.app.device

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openstrap.app.data.settings.BuzzPattern
import org.openstrap.app.data.settings.CallBuzz
import org.openstrap.app.data.settings.QuietHours
import org.openstrap.app.data.settings.SettingsStore

/**
 * Buzzes the strap for phone calls and notifications. The strap has no notification module (Zepp leaves the
 * feature off for it), so a ringing call uses its find-device buzz until the call is answered or ends, and a
 * notification plays a short motor pattern (docs/protocol/01 §5.8). Both follow Do Not Disturb, and the user's quiet
 * hours hold notifications back (calls too, when they chose so). Calls from apps
 * (WhatsApp, Telegram, Teams) never reach the call state, so their incoming call notification and the phone's
 * ringtone audio mode count as ringing too, while notification access is on. A ringing call wins:
 * [StrapController.startCallBuzz] drops other buzzes until the stop, and a call notification holds them back
 * before the ringing state arrives.
 */
class PhoneAlerts(
    private val context: Context,
    private val settings: SettingsStore,
    private val strap: StrapController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile private var callsOn = false
    @Volatile var notificationsOn = false
        private set
    @Volatile private var pattern = BuzzPattern.TWO_SHORT
    @Volatile private var callStyle = CallBuzz.STEADY
    @Volatile private var quiet = QuietHours()

    /** Re-checked by [refresh]; the screen calls it on resume, after the user may have granted access. */
    val callPermission = MutableStateFlow(hasCallPermission())
    val notificationAccess = MutableStateFlow(hasNotificationAccess())

    private var callListener: Any? = null
    private var modeListener: Any? = null
    private var ringing: Job? = null
    private var lastBuzz = 0L
    /** When a call notification last came in, from any app; Android often posts it before the ringing state. */
    @Volatile private var callNoticeAt = Long.MIN_VALUE / 2

    init {
        scope.launch {
            combine(settings.buzzOnCalls, settings.buzzOnNotifications, callPermission) { c, n, _ -> c to n }.collect { (c, n) ->
                callsOn = c
                notificationsOn = n
                updateCallListener()
            }
        }
        scope.launch { settings.notificationPattern.collect { pattern = it } }
        scope.launch { settings.callBuzzStyle.collect { callStyle = it } }
        scope.launch { settings.quietHours.collect { quiet = it } }
    }

    fun refresh() {
        callPermission.value = hasCallPermission()
        notificationAccess.value = hasNotificationAccess()
    }

    fun hasCallPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, StrapNotificationListener::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Plays [p] once, so the pattern picker can be felt. */
    suspend fun preview(p: BuzzPattern) = strap.buzz(p.millis)

    private val previews = AtomicInteger()

    /**
     * Plays the call buzz [style] for a few seconds, so steady and pulsed can be compared. Not while a call rings.
     * Picking the other one meanwhile takes over: only the latest preview sends the stop.
     */
    suspend fun previewCall(style: CallBuzz) {
        if (callRinging) return
        val mine = previews.incrementAndGet()
        try {
            strap.startCallBuzz(style == CallBuzz.PULSED)
            delay(CALL_PREVIEW_MS)
        } finally {
            if (!callRinging && previews.get() == mine) withContext(NonCancellable) { runCatching { strap.stopCallBuzz() } }
        }
    }

    // ---- Calls ----

    private fun updateCallListener() {
        val want = callsOn && hasCallPermission()
        if (want == (callListener != null)) return
        // Callbacks are registered and removed on the main thread, which PhoneStateListener needs (it binds a Looper).
        ContextCompat.getMainExecutor(context).execute {
            if (want && callListener == null) {
                log("call listener on")
                callListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) = onCallState(state)
                    }.also { telephony.registerTelephonyCallback(context.mainExecutor, it) }
                } else {
                    @Suppress("DEPRECATION")
                    object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCallState(state)
                    }.also { @Suppress("DEPRECATION") telephony.listen(it, PhoneStateListener.LISTEN_CALL_STATE) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    modeListener = AudioManager.OnModeChangedListener { onAudioMode(it) }
                        .also { audio.addOnModeChangedListener(context.mainExecutor, it) }
                }
            } else if (!want) {
                val l = callListener
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && l is TelephonyCallback) {
                    telephony.unregisterTelephonyCallback(l)
                } else {
                    @Suppress("DEPRECATION")
                    if (l is PhoneStateListener) telephony.listen(l, PhoneStateListener.LISTEN_NONE)
                }
                callListener = null
                val m = modeListener
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && m is AudioManager.OnModeChangedListener) audio.removeOnModeChangedListener(m)
                modeListener = null
                endAllRinging()
            }
        }
    }

    @Volatile private var phoneRinging = false

    /**
     * What rings now: [SOURCE_PHONE] (the call state), [SOURCE_AUDIO] (the ringtone audio mode) or a calling app's
     * incoming call notification, by key. The strap buzzes from the first one on until the last one ends.
     */
    private val ringSources = HashSet<String>()

    /** True while a call rings (known only while buzzing for calls is on); other buzzes stay quiet then. */
    val callRinging: Boolean get() = phoneRinging || synchronized(ringSources) { ringSources.isNotEmpty() }

    private fun onCallState(state: Int) {
        log("call state $state (calls ${if (callsOn) "on" else "off"})")
        phoneRinging = state == TelephonyManager.CALL_STATE_RINGING
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> { reconnectStrap(); ringStarted(SOURCE_PHONE) }
            // Answered: whatever else was ringing (a calling app's notification) stops too.
            TelephonyManager.CALL_STATE_OFFHOOK -> endAllRinging()
            else -> ringEnded(SOURCE_PHONE)
        }
    }

    /** MODE_RINGTONE is any call ringing, a calling app's included when it rings through the system. */
    private fun onAudioMode(mode: Int) {
        log("audio mode $mode")
        when (mode) {
            AudioManager.MODE_RINGTONE -> ringStarted(SOURCE_AUDIO)
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> endAllRinging()
            else -> ringEnded(SOURCE_AUDIO)
        }
    }

    private fun ringStarted(source: String) {
        if (!callsOn) return
        reconnectStrap()
        val first = synchronized(ringSources) { ringSources.add(source) && ringSources.size == 1 }
        if (first) {
            log("ringing: $source")
            startRinging()
        }
    }

    private fun ringEnded(source: String) {
        val last = synchronized(ringSources) { ringSources.remove(source) && ringSources.isEmpty() }
        if (last) {
            log("ringing ended: $source")
            stopRinging()
        }
    }

    private fun endAllRinging() {
        synchronized(ringSources) { ringSources.clear() }
        stopRinging()
    }

    private fun startRinging() {
        if (ringing?.isActive == true) return
        ringing = scope.launch {
            if (quiet.calls && quietNow()) { log("call ringing: held back by quiet hours"); return@launch }
            val allowed = dndAllowsAnyCaller()
            val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
            log("call ringing: ${if (allowed) "buzz" else "held back by Do Not Disturb"} (interruption filter $filter)")
            if (!allowed) return@launch
            // The strap may be reconnecting (reconnectStrap above): wait for it rather than miss the call.
            if (strap.connection.value !is ConnectionState.Connected) {
                log("call ringing: waiting for the strap to connect")
                withTimeoutOrNull(CONNECT_WAIT_MS) { strap.connection.first { it is ConnectionState.Connected } }
                    ?: run { log("call ringing: the strap didn't connect in time"); return@launch }
            }
            runCatching { strap.startCallBuzz(callStyle == CallBuzz.PULSED) }.onFailure { log("call buzz failed: ${it.message}"); return@launch }
            // A missed call can end without a state change reaching us; don't leave the strap buzzing.
            delay(MAX_RING_MS)
            log("call ringing: ${MAX_RING_MS / 1000} s limit")
            synchronized(ringSources) { ringSources.clear() }
            runCatching { strap.stopCallBuzz() }
        }
    }

    private fun stopRinging() {
        val job = ringing ?: return
        ringing = null
        job.cancel()
        scope.launch { runCatching { strap.stopCallBuzz() } }
    }

    /**
     * True unless Do Not Disturb would block a call from an unknown caller. Exceptions for starred contacts and repeat
     * callers aren't followed: telling them apart needs the caller's number, which needs the call-log permission.
     * The call notification's ranking doesn't help: on Samsung it said "blocked" for a repeat caller the phone let
     * ring (2026-10-21).
     */
    private fun dndAllowsAnyCaller(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> runCatching {
                val p = nm.notificationPolicy
                p.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0 &&
                    p.priorityCallSenders == NotificationManager.Policy.PRIORITY_SENDERS_ANY
            }.getOrDefault(false)
            else -> false
        }
    }

    private fun log(message: String) = (strap as? StrapManager)?.log(message)

    /**
     * Reconnects a paired strap that isn't connected. Android can restart the process just to deliver a call or a
     * notification (or to bind [StrapNotificationListener]); this alert is lost, but the next one reaches the strap.
     * A no-op while connected or connecting, while the user's Disconnect holds, and when the strap turned the key
     * down (retrying can't help).
     */
    fun reconnectStrap() {
        val c = strap.connection.value
        if (c is ConnectionState.Connected || (c as? ConnectionState.Failed)?.keyRejected == true) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        strap.backgroundConnect()
    }

    // ---- Notifications ----

    /** Called by [StrapNotificationListener] for every posted notification. */
    fun onNotification(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?, update: Boolean) {
        reconnectStrap()
        val now = SystemClock.elapsedRealtime()
        val n = sbn.notification
        val callType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) else 0
        // A call notification, from the dialer, Samsung's call screen or a calling app, never plays a pattern: it
        // usually comes just before the ringing state, and a motor pattern then cuts off the call's buzz (seen 2026-10-20).
        if (n.category == Notification.CATEGORY_CALL || callType != 0) {
            callNoticeAt = now
            val incoming = isIncomingCall(n, callType)
            log("call notification ${sbn.packageName}: type $callType, ongoing ${sbn.isOngoing}, full screen ${n.fullScreenIntent != null}, update $update, filter ${ranking?.matchesInterruptionFilter()} -> ${if (incoming) "ringing" else "not ringing"}")
            // An app's call that Do Not Disturb holds back on the phone stays quiet on the strap too.
            if (incoming && ranking?.matchesInterruptionFilter() != false) ringStarted(sbn.key) else ringEnded(sbn.key)
            return
        }
        if (callRinging || now - callNoticeAt < CALL_NOTICE_HOLD_MS) return
        if (!notificationsOn || !shouldBuzz(sbn, ranking, update)) return
        if (quietNow()) return
        synchronized(this) {
            if (now - lastBuzz < MIN_GAP_MS) return
            lastBuzz = now
        }
        scope.launch { runCatching { strap.buzz(pattern.millis) } }
    }

    private fun quietNow(): Boolean = LocalTime.now().let { quiet.covers(it.hour * 60 + it.minute) }

    /** Called by [StrapNotificationListener]: a calling app's notification going away ends its ringing. */
    fun onNotificationRemoved(key: String) = ringEnded(key)

    /**
     * Android 12's CallStyle says "incoming" outright. An app without it is taken as ringing while its call
     * notification has a full-screen intent, the incoming call screen; once answered or ended it is removed or posted
     * again without one.
     */
    private fun isIncomingCall(n: Notification, callType: Int): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && callType != 0 -> callType == Notification.CallStyle.CALL_TYPE_INCOMING
        else -> n.category == Notification.CATEGORY_CALL && n.fullScreenIntent != null
    }

    private fun shouldBuzz(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?, update: Boolean): Boolean {
        val n = sbn.notification
        if (sbn.packageName == context.packageName || sbn.isOngoing) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // An update that the app marked "alert once" makes no sound on the phone either.
        if (update && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return false
        if (ranking != null) {
            // Follow the phone: nothing during Do Not Disturb that the phone itself would hold back, and nothing
            // from channels the user made silent.
            if (!ranking.matchesInterruptionFilter()) return false
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
            if (ranking.isSuspended) return false
        }
        return true
    }

    private companion object {
        const val MAX_RING_MS = 60_000L
        /** How long a call waits for a reconnecting strap. */
        const val CONNECT_WAIT_MS = 15_000L
        /** Other notifications stay quiet this long after a call notification, until the ringing state arrives. */
        const val CALL_NOTICE_HOLD_MS = 3_000L
        const val CALL_PREVIEW_MS = 4_000L
        const val SOURCE_PHONE = "phone"
        const val SOURCE_AUDIO = "audio"
        /** A burst of notifications (a group chat) buzzes once. */
        const val MIN_GAP_MS = 5_000L
    }
}
