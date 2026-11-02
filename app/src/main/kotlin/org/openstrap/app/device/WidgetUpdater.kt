package org.openstrap.app.device

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.MomentInputs
import org.openstrap.app.widget.MomentWidget
import org.openstrap.app.widget.MomentWidgetReceiver
import org.openstrap.app.widget.RecoveryWidget
import org.openstrap.app.widget.RecoveryWidgetReceiver
import org.openstrap.app.widget.SleepWidget
import org.openstrap.app.widget.SleepWidgetReceiver
import org.openstrap.app.widget.StartWidget
import org.openstrap.app.widget.StartWidgetReceiver
import org.openstrap.app.widget.StepsWidget
import org.openstrap.app.widget.StepsWidgetReceiver
import org.openstrap.app.widget.WidgetKind
import org.openstrap.app.widget.WidgetRefresh
import org.openstrap.app.widget.SYNC_STALE_SECONDS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one place that asks home screen widgets to refresh (widgets spec 7). Everything that changes what a widget
 * shows calls [changed]; nothing refreshes on a timer it doesn't need. Calls are cheap, return at once and never
 * throw: the work runs on its own scope and failures are logged.
 *
 * Only widgets that are placed are updated, and the moment and midnight alarms are set only while one is. A kind
 * not registered in [widgets] yet is never placed.
 */
object WidgetUpdater {
    /** A widget's receiver, which the launcher knows it by, and its Glance widget. */
    class Registration(val receiver: Class<out GlanceAppWidgetReceiver>, val widget: () -> GlanceAppWidget)

    /** Each widget, once it exists. */
    private val widgets: Map<WidgetKind, Registration> = mapOf(
        WidgetKind.Moment to Registration(MomentWidgetReceiver::class.java) { MomentWidget() },
        WidgetKind.Sleep to Registration(SleepWidgetReceiver::class.java) { SleepWidget() },
        WidgetKind.Steps to Registration(StepsWidgetReceiver::class.java) { StepsWidget() },
        WidgetKind.Recovery to Registration(RecoveryWidgetReceiver::class.java) { RecoveryWidget() },
        WidgetKind.Start to Registration(StartWidgetReceiver::class.java) { StartWidget() },
    )

    /** [kind]'s receiver and widget, null before it exists: for the debug widget gallery. */
    internal fun registration(kind: WidgetKind): Registration? = widgets[kind]

    /** Steps change by the second while the strap is connected; the widget follows at most this often. */
    const val STEPS_EVERY_MS = 15 * 60_000L

    /** Without exact alarms a moment may change this late on a widget (spec 7: a few minutes late is fine). */
    const val ALARM_WINDOW_MS = 5 * 60_000L

    private const val TAG = "OpenStrap"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val steps = UpdateThrottle(STEPS_EVERY_MS)

    /** Refreshes the placed widgets of [kinds]. */
    fun changed(context: Context, kinds: Set<WidgetKind>) {
        val app = context.applicationContext
        scope.launch { update(app, kinds) }
    }

    /** Refreshes every placed widget: a sync ended, a new day, the strap's state changed. */
    fun changedAll(context: Context) = changed(context, WidgetKind.entries.toSet())

    /** Today's steps moved: the Steps widget follows, at most every [STEPS_EVERY_MS]. */
    fun stepsChanged(context: Context) {
        val app = context.applicationContext
        val wait = steps.request(System.currentTimeMillis()) ?: return
        if (wait == 0L) { changed(app, setOf(WidgetKind.Steps)); return }
        scope.launch {
            delay(wait)
            steps.ran(System.currentTimeMillis())
            update(app, setOf(WidgetKind.Steps))
        }
    }

    /** The widgets on the home screen, any page: a kind counts once its receiver has an id (spec 3.1). */
    fun placed(context: Context): Set<WidgetKind> = try {
        val manager = AppWidgetManager.getInstance(context) ?: return emptySet()
        widgets.filter { (_, r) -> manager.getAppWidgetIds(ComponentName(context, r.receiver)).isNotEmpty() }.keys
    } catch (e: Exception) {
        Log.w(TAG, "widgets: placed check failed: $e")
        emptySet()
    }

    /**
     * Sets the alarm for the moment's next change ([MomentInputs.nextChange]), from the inputs the Moment widget just
     * rendered with, so the two can't drift apart, or for [tick] when that comes first: the time something the widget
     * shows moves on by itself within the moment ("38 min since your run"). When it fires Moment refreshes, and its
     * render schedules the next. Clears the alarm when there's neither or no Moment widget ([momentAlarm]). A tick
     * never wakes the phone ([momentWakes]): it's only worth drawing while the screen is on, so it waits for that.
     */
    fun scheduleMoment(context: Context, inputs: MomentInputs, tick: Instant? = null, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        try {
            val next = inputs.nextChange(now, zone)
            val at = momentAlarm(WidgetKind.Moment in placed(context), next, tick)
            if (at == null) cancel(context, ACTION_MOMENT) else setAlarm(context, ACTION_MOMENT, at.toEpochMilli(), wake = momentWakes(next, tick))
        } catch (e: Exception) {
            Log.w(TAG, "widgets: moment alarm not set: $e")
        }
    }

    /**
     * When Moment's alarm should next fire: the earlier of the moment's [nextChange] and the shown content's [tick],
     * only while a Moment widget is [placed]. Null clears it.
     */
    fun momentAlarm(placed: Boolean, nextChange: Instant?, tick: Instant?): Instant? =
        if (!placed) null else listOfNotNull(nextChange, tick).minOrNull()

    /** Whether Moment's alarm wakes the phone: only for the moment's [nextChange], not for a [tick] that comes first. */
    fun momentWakes(nextChange: Instant?, tick: Instant?): Boolean =
        tick == null || (nextChange != null && nextChange <= tick)

    /**
     * A widget was placed or removed (any kind): midnight's alarm follows whether any is placed, Moment's is cleared
     * once none is (a placed one sets its own as it renders), and Moment refreshes for the duplicate rule (spec 3.1).
     */
    fun placedChanged(context: Context) {
        val app = context.applicationContext
        scheduleMidnight(app)
        try {
            if (WidgetKind.Moment !in placed(app)) cancel(app, ACTION_MOMENT)
        } catch (e: Exception) {
            Log.w(TAG, "widgets: moment alarm not cleared: $e")
        }
        changed(app, setOf(WidgetKind.Moment))
    }

    /**
     * Android 15 and later: gives the launcher's picker each widget's generated preview ([GlanceAppWidget.providePreview]),
     * the real layout with sample values (spec 6.4), once per app version. The system limits how often this may be
     * called, so a limited or failed call is tried again on the next start. Older systems use the provider XML's
     * `previewLayout` and `previewImage`.
     */
    fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREVIEW_PREFS, Context.MODE_PRIVATE)
        val version = org.openstrap.app.BuildConfig.VERSION_CODE
        if (prefs.getInt(PREVIEW_VERSION, -1) == version) return
        scope.launch {
            try {
                val manager = androidx.glance.appwidget.GlanceAppWidgetManager(app)
                val results = widgets.values.map { manager.setWidgetPreviews(it.receiver.kotlin) }
                if (results.all { it == androidx.glance.appwidget.GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) {
                    prefs.edit().putInt(PREVIEW_VERSION, version).apply()
                } else {
                    Log.i(TAG, "widgets: previews not all set ($results), trying again next start")
                }
            } catch (e: Exception) {
                Log.w(TAG, "widgets: previews not set: $e")
            }
        }
    }

    private const val PREVIEW_PREFS = "widgets"
    private const val PREVIEW_VERSION = "previews_version"

    /** Sets the alarm for the next local midnight while any widget is placed, and clears it when none is. */
    fun scheduleMidnight(context: Context, zone: ZoneId = ZoneId.systemDefault()) {
        try {
            if (placed(context).isEmpty()) { cancel(context, ACTION_MIDNIGHT); return }
            val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            setAlarm(context, ACTION_MIDNIGHT, midnight)
        } catch (e: Exception) {
            Log.w(TAG, "widgets: midnight alarm not set: $e")
        }
    }

    internal suspend fun update(context: Context, kinds: Set<WidgetKind>) {
        val shown = placed(context).intersect(kinds)
        for (kind in shown) {
            try {
                // A running session loads again on its version; one that isn't loads as it starts.
                WidgetRefresh.bump(kind)
                widgets.getValue(kind).widget().updateAll(context)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "widgets: $kind not updated: $e")
            }
        }
        if (shown.isNotEmpty()) {
            scheduleMidnight(context)
            scheduleStale(context)
        }
    }

    /**
     * Sets the alarm for the moment the last sync turns stale ([staleAt]), so the status line says so with the strap
     * away and nothing else refreshing (spec 5), while any widget is placed; clears it otherwise.
     */
    suspend fun scheduleStale(context: Context) {
        try {
            val done = (context.applicationContext as OpenStrapApp).container.settings.strapSnapshot.first().lastSyncDone
            val at = staleAt(placed(context).isNotEmpty(), done, System.currentTimeMillis() / 1000)
            if (at == null) cancel(context, ACTION_STALE) else setAlarm(context, ACTION_STALE, at * 1000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "widgets: stale alarm not set: $e")
        }
    }

    /**
     * When the last sync, done at [lastSyncDone] (epoch seconds), turns stale: a second past [SYNC_STALE_SECONDS],
     * while widgets are [placed] and that's still ahead of [now]. Null for no alarm.
     */
    fun staleAt(placed: Boolean, lastSyncDone: Long?, now: Long): Long? =
        lastSyncDone?.let { it + SYNC_STALE_SECONDS + 1 }?.takeIf { placed && it > now }

    /**
     * Exact while the app may set exact alarms ("Alarms and reminders" allows it),
     * otherwise inside [ALARM_WINDOW_MS]. Never `USE_EXACT_ALARM`: that's for clocks and calendars (spec 7).
     */
    /** Sets [action]'s alarm for [atMs]; one that doesn't [wake] is held until the phone is awake for something else. */
    private fun setAlarm(context: Context, action: String, atMs: Long, wake: Boolean = true) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, action)
        if (!wake) {
            alarms.setWindow(AlarmManager.RTC, atMs, ALARM_WINDOW_MS, pending)
            return
        }
        val exact = alarms.canScheduleExactAlarms()
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            else alarms.setWindow(AlarmManager.RTC_WAKEUP, atMs, ALARM_WINDOW_MS, pending)
        } catch (e: SecurityException) {
            // The exact-alarm access was taken back between the check and the call.
            alarms.setWindow(AlarmManager.RTC_WAKEUP, atMs, ALARM_WINDOW_MS, pending)
        }
    }

    private fun cancel(context: Context, action: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context, action))
    }

    private fun pendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, when (action) { ACTION_MOMENT -> 1; ACTION_MIDNIGHT -> 2; else -> 3 },
        Intent(context, WidgetAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal const val ACTION_MOMENT = "org.openstrap.app.widget.MOMENT"
    internal const val ACTION_MIDNIGHT = "org.openstrap.app.widget.MIDNIGHT"
    internal const val ACTION_STALE = "org.openstrap.app.widget.STALE"
}

/** Where [WidgetUpdater]'s alarms land: the moment changed, or a new day began. Not a widget, and not exported. */
class WidgetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kinds = when (intent.action) {
            WidgetUpdater.ACTION_MOMENT -> setOf(WidgetKind.Moment)
            WidgetUpdater.ACTION_MIDNIGHT, WidgetUpdater.ACTION_STALE -> WidgetKind.entries.toSet()
            else -> return
        }
        val app = context.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetUpdater.update(app, kinds)
                // The update sets the next midnight only when something was placed; this one has passed either way.
                if (intent.action == WidgetUpdater.ACTION_MIDNIGHT) WidgetUpdater.scheduleMidnight(app)
            } catch (e: Exception) {
                Log.w("OpenStrap", "widgets: alarm ${intent.action} failed: $e")
            } finally {
                done.finish()
            }
        }
    }
}

/**
 * At most one update every [everyMs], and none lost: a request inside the gap waits for its end, and requests while
 * one waits join it.
 */
class UpdateThrottle(private val everyMs: Long) {
    private var last: Long? = null
    private var waiting = false

    /** A request at [now] (ms): 0 to update now, the ms to wait before updating, or null when one already waits. */
    @Synchronized
    fun request(now: Long): Long? {
        if (waiting) return null
        val l = last
        if (l == null || now - l >= everyMs) { last = now; return 0 }
        waiting = true
        return l + everyMs - now
    }

    /** The waiting update ran at [now] (ms). */
    @Synchronized
    fun ran(now: Long) {
        last = now
        waiting = false
    }
}
