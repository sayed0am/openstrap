package org.openstrap.app.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.openstrap.app.MainActivity
import org.openstrap.app.data.db.WearEventRow
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * Zepp's "Wear Reminders" for the strap (codemap 07 §4 N5): a notification while the strap reports it is off-body,
 * cleared once it is back on.
 */
object WearReminder {
    enum class Action { Post, Clear, None }

    /**
     * What to do with the notification given the wear state ([worn]: null when unknown, e.g. the link is down), the
     * user's switch and whether it is [shown] now. It is posted once per off-body stretch, and cleared when the strap
     * is back on, the state is no longer known, or the switch is turned off.
     */
    fun decide(worn: Boolean?, enabled: Boolean, shown: Boolean): Action = when {
        worn == false && enabled -> if (shown) Action.None else Action.Post
        shown -> Action.Clear
        else -> Action.None
    }
}

/** Posts and clears the off-body notification on its own channel, as Zepp's `channel_device_wear` (07 §4 N5). */
class WearNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Volatile var shown = false
        private set

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.wear_reminder_wear_reminders), NotificationManager.IMPORTANCE_DEFAULT))
        // Left from before the app was stopped: whether the strap is still off isn't known now.
        manager.cancel(NOTIFICATION_ID)
    }

    fun apply(action: WearReminder.Action) {
        when (action) {
            WearReminder.Action.Post -> show()
            WearReminder.Action.Clear -> { manager.cancel(NOTIFICATION_ID); shown = false }
            WearReminder.Action.None -> Unit
        }
    }

    private fun show() {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(TITLE)
            .setContentText(TEXT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        shown = true
    }

    companion object {
        val TITLE: String get() = tr(R.string.wear_reminder_strap_is_off_your)
        val TEXT: String get() = tr(R.string.wear_reminder_heart_rate_and_sleep)
        private const val CHANNEL = "wear"
        /** Zepp's id for this notification. */
        private const val NOTIFICATION_ID = 17
    }
}

/**
 * Stores wear changes ([WearEventRow]) one at a time, in the order they happened. wear_event is keyed by the second with
 * REPLACE, so writes from separate coroutines could land out of order; here a later change in the same second always
 * replaces an earlier one, and a change is never stamped before the one it follows (should the clock step back).
 */
internal class WearEventLog(
    scope: CoroutineScope,
    private val insert: suspend (WearEventRow) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var lastAt = Long.MIN_VALUE

    init {
        scope.launch {
            for (work in queue) {
                try { work() } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e) }
            }
        }
    }

    /** Queues [worn] at [atSeconds], then [then] (e.g. the notification), after every change recorded before it. */
    fun record(atSeconds: Long, worn: Boolean?, then: suspend () -> Unit = {}) {
        synchronized(this) {
            val at = maxOf(atSeconds, lastAt)
            lastAt = at
            queue.trySend { insert(WearEventRow(at, worn)); then() }
        }
    }

    /** Queues other wear-log work (e.g. closing the interval left open when the app stopped) in the same order. */
    fun post(work: suspend () -> Unit) {
        queue.trySend(work)
    }
}
