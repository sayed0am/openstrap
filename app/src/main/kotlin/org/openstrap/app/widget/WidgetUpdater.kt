package org.openstrap.app.widget

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openstrap.app.BuildConfig
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.MomentInputs
import org.openstrap.app.data.WidgetKind
import org.openstrap.app.data.catching
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.WorkoutSession
import org.openstrap.app.device.launchForBroadcast
import org.openstrap.app.presentation.StrapBands
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A widget's receiver, which the launcher knows it by, and its Glance widget. */
class Registration(val receiver: Class<out GlanceAppWidgetReceiver>, val widget: () -> GlanceAppWidget)

/** Each widget, once it exists. A kind not registered here yet is never placed. */
internal val WIDGETS: Map<WidgetKind, Registration> = mapOf(
    WidgetKind.Moment to Registration(MomentWidgetReceiver::class.java) { MomentWidget() },
    WidgetKind.Sleep to Registration(SleepWidgetReceiver::class.java) { SleepWidget() },
    WidgetKind.Steps to Registration(StepsWidgetReceiver::class.java) { StepsWidget() },
    WidgetKind.Recovery to Registration(RecoveryWidgetReceiver::class.java) { RecoveryWidget() },
    WidgetKind.Start to Registration(StartWidgetReceiver::class.java) { StartWidget() },
)

/** The widgets on the home screen, any page: a kind counts once its receiver has an id (spec 3.1). */
internal fun placedWidgets(context: Context): Set<WidgetKind> = runCatching {
    val manager = AppWidgetManager.getInstance(context) ?: return emptySet()
    WIDGETS.filter { (_, r) -> manager.getAppWidgetIds(ComponentName(context, r.receiver)).isNotEmpty() }.keys
}.getOrElse { e ->
    Log.w(WIDGET_TAG, "widgets: placed check failed: $e")
    emptySet()
}

/**
 * The one place that refreshes home screen widgets (widgets spec 7). It watches what they show ([start]): the goals,
 * the workout sides, the strap's state and syncs, the live steps, the workout, sleep edits and the profile. Nobody
 * tells it, and nothing refreshes on a timer it doesn't need. Calls return at once and never throw: the work runs on
 * [scope] and failures are logged.
 *
 * Only widgets that are placed are updated, and the moment and midnight alarms are set only while one is. Each refresh
 * reads once for every widget it updates ([WidgetSnapshot.gather]), and the widgets' sessions draw from that read.
 */
class WidgetUpdater(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val strap: StrapController,
    private val workout: WorkoutSession,
    /** The user's sleep edits as they change ([org.openstrap.app.data.HealthRepository.sleepEdits]). */
    private val sleepEdits: Flow<Any?>,
    private val snapshot: WidgetSnapshot,
) {
    private val steps = UpdateThrottle(STEPS_EVERY_MS)

    /** The last refresh's read, for the sessions it starts or reloads. */
    @Volatile
    private var fresh: WidgetData? = null

    /** Starts watching, sets midnight's alarm and publishes the picker's previews. Called once, as the app starts. */
    fun start() {
        val triggers = WidgetTriggers(
            goals = settings.goals,
            workoutSides = settings.workoutSides,
            strapSnapshot = settings.strapSnapshot,
            sync = strap.sync,
            todaySteps = strap.todaySteps,
            syncEnded = strap.syncEnded,
            workoutState = workout.state,
            sleepEdits = sleepEdits,
            profile = settings.profile,
            now = { System.currentTimeMillis() / 1000 },
        )
        scope.watchWidgetTriggers(triggers, ::changed, ::stepsChanged)
        // Widgets turn the day over at midnight. Alarms don't survive a reboot or an update, so set it on each start.
        scheduleMidnight()
        publishPreviews()
    }

    /** Refreshes the placed widgets of [kinds]. */
    fun changed(kinds: Set<WidgetKind>) {
        scope.launch { update(kinds) }
    }

    /** Refreshes every placed widget: a sync ended, a new day, the strap's state changed. */
    fun changedAll() = changed(WidgetKind.entries.toSet())

    /** Today's steps moved: the Steps widget follows, at most every [STEPS_EVERY_MS]. */
    fun stepsChanged() {
        val wait = steps.request(System.currentTimeMillis()) ?: return
        if (wait == 0L) { changed(setOf(WidgetKind.Steps)); return }
        scope.launch {
            delay(wait)
            steps.ran(System.currentTimeMillis())
            update(setOf(WidgetKind.Steps))
        }
    }

    /** The widgets on the home screen ([placedWidgets]). */
    fun placed(): Set<WidgetKind> = placedWidgets(app)

    /**
     * What a [kind] widget shows now: the last refresh's read when it was for [kind] and is recent (the sessions it
     * starts or reloads draw from it), else a read of [kind] alone.
     */
    internal suspend fun data(kind: WidgetKind, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): WidgetData {
        val last = fresh
        val recent = last != null && now.toEpochMilli() - last.now.toEpochMilli() in 0..FRESH_MS
        if (recent && kind in last.kinds && last.zone == zone) return last
        return snapshot.gather(setOf(kind), now, zone)
    }

    /**
     * Sets the alarm for the moment's next change ([MomentInputs.nextChange]), from the inputs the Moment widget just
     * rendered with, so the two can't drift apart, or for [tick] when that comes first: the time something the widget
     * shows moves on by itself within the moment ("38 min since your run"). When it fires Moment refreshes, and its
     * render schedules the next. Clears the alarm when there's neither or no Moment widget ([momentAlarm]). A tick
     * never wakes the phone ([momentWakes]): it's only worth drawing while the screen is on, so it waits for that.
     */
    fun scheduleMoment(inputs: MomentInputs, tick: Instant? = null, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        runCatching {
            val next = inputs.nextChange(now, zone)
            val at = momentAlarm(WidgetKind.Moment in placed(), next, tick)
            if (at == null) cancel(ACTION_MOMENT) else setAlarm(ACTION_MOMENT, at.toEpochMilli(), wake = momentWakes(next, tick))
        }.onFailure { Log.w(WIDGET_TAG, "widgets: moment alarm not set: $it") }
    }

    /**
     * A widget was placed or removed (any kind): midnight's alarm follows whether any is placed, Moment's is cleared
     * once none is (a placed one sets its own as it renders), and Moment refreshes for the duplicate rule (spec 3.1).
     */
    fun placedChanged() {
        scheduleMidnight()
        runCatching { if (WidgetKind.Moment !in placed()) cancel(ACTION_MOMENT) }
            .onFailure { Log.w(WIDGET_TAG, "widgets: moment alarm not cleared: $it") }
        changed(setOf(WidgetKind.Moment))
    }

    /**
     * Android 15 and later: gives the launcher's picker each widget's generated preview ([GlanceAppWidget.providePreview]),
     * the real layout with sample values (spec 6.4), once per app version. The system limits how often this may be
     * called, so a limited or failed call is tried again on the next start. Older systems use the provider XML's
     * `previewLayout` and `previewImage`.
     */
    private fun publishPreviews() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = app.getSharedPreferences(PREVIEW_PREFS, Context.MODE_PRIVATE)
        val version = BuildConfig.VERSION_CODE
        if (prefs.getInt(PREVIEW_VERSION, -1) == version) return
        scope.launch {
            catching {
                val manager = GlanceAppWidgetManager(app)
                val results = WIDGETS.values.map { manager.setWidgetPreviews(it.receiver.kotlin) }
                if (results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) {
                    prefs.edit { putInt(PREVIEW_VERSION, version) }
                } else {
                    Log.i(WIDGET_TAG, "widgets: previews not all set ($results), trying again next start")
                }
            }.onFailure { Log.w(WIDGET_TAG, "widgets: previews not set: $it") }
        }
    }

    /** Sets the alarm for the next local midnight while any widget is placed, and clears it when none is. */
    fun scheduleMidnight(zone: ZoneId = ZoneId.systemDefault()) {
        runCatching {
            if (placed().isEmpty()) { cancel(ACTION_MIDNIGHT); return }
            val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            setAlarm(ACTION_MIDNIGHT, midnight)
        }.onFailure { Log.w(WIDGET_TAG, "widgets: midnight alarm not set: $it") }
    }

    /** [kind]'s receiver and widget, null before it exists: for the debug widget gallery. */
    internal fun registration(kind: WidgetKind): Registration? = WIDGETS[kind]

    internal suspend fun update(kinds: Set<WidgetKind>) {
        val shown = placed().intersect(kinds)
        if (shown.isEmpty()) return
        // One read for every widget this refresh updates. A session that can't use it reads for itself.
        catching { fresh = snapshot.gather(shown, Instant.now(), ZoneId.systemDefault()) }
            .onFailure { Log.w(WIDGET_TAG, "widgets: $shown not read: $it") }
        for (kind in shown) {
            catching {
                // A running session loads again on its version, one that isn't loads as it starts.
                WidgetRefresh.bump(kind)
                WIDGETS.getValue(kind).widget().updateAll(app)
            }.onFailure { Log.w(WIDGET_TAG, "widgets: $kind not updated: $it") }
        }
        scheduleMidnight()
        scheduleStale()
    }

    /**
     * Sets the alarm for the moment the last sync turns stale ([staleAt]), so the status line says so with the strap
     * away and nothing else refreshing (spec 5), while any widget is placed, and clears it otherwise.
     */
    private suspend fun scheduleStale() {
        catching {
            val done = settings.strapSnapshot.first().lastSyncDone
            val at = staleAt(placed().isNotEmpty(), done, System.currentTimeMillis() / 1000)
            if (at == null) cancel(ACTION_STALE) else setAlarm(ACTION_STALE, at * 1000)
        }.onFailure { Log.w(WIDGET_TAG, "widgets: stale alarm not set: $it") }
    }

    /**
     * Sets [action]'s alarm for [atMs]. One that doesn't [wake] is held until the phone is awake for something else.
     * Exact while the app may set exact alarms ("Alarms and reminders" allows it), otherwise inside [ALARM_WINDOW_MS].
     * Never `USE_EXACT_ALARM`: that's for clocks and calendars (spec 7).
     */
    private fun setAlarm(action: String, atMs: Long, wake: Boolean = true) {
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(action)
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
            Log.i(WIDGET_TAG, "widgets: exact alarm refused, setting a window: $e")
            alarms.setWindow(AlarmManager.RTC_WAKEUP, atMs, ALARM_WINDOW_MS, pending)
        }
    }

    private fun cancel(action: String) {
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(action))
    }

    private fun pendingIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
        app, when (action) { ACTION_MOMENT -> 1; ACTION_MIDNIGHT -> 2; else -> 3 },
        Intent(app, WidgetAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** Steps change by the second while the strap is connected, and the widget follows at most this often. */
        const val STEPS_EVERY_MS = 15 * 60_000L

        /** Without exact alarms a moment may change this late on a widget (spec 7: a few minutes late is fine). */
        const val ALARM_WINDOW_MS = 5 * 60_000L

        /** How long a refresh's read serves the sessions it starts: they render one after another, a few seconds in all. */
        private const val FRESH_MS = 60_000L

        private const val PREVIEW_PREFS = "widgets"
        private const val PREVIEW_VERSION = "previews_version"

        internal const val ACTION_MOMENT = "org.openstrap.app.widget.MOMENT"
        internal const val ACTION_MIDNIGHT = "org.openstrap.app.widget.MIDNIGHT"
        internal const val ACTION_STALE = "org.openstrap.app.widget.STALE"

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
         * When the last sync, done at [lastSyncDone] (epoch seconds), turns stale: a second past [StrapBands.STALE_AFTER_S],
         * while widgets are [placed] and that's still ahead of [now]. Null for no alarm.
         */
        fun staleAt(placed: Boolean, lastSyncDone: Long?, now: Long): Long? =
            lastSyncDone?.let { it + StrapBands.STALE_AFTER_S + 1 }?.takeIf { placed && it > now }
    }
}

/** The app's [WidgetUpdater], from a receiver, a widget or an action the system started. */
internal val Context.widgets: WidgetUpdater get() = (applicationContext as OpenStrapApp).container.widgets

/** Where [WidgetUpdater]'s alarms land: the moment changed, or a new day began. Not a widget, and not exported. */
class WidgetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kinds = when (intent.action) {
            WidgetUpdater.ACTION_MOMENT -> setOf(WidgetKind.Moment)
            WidgetUpdater.ACTION_MIDNIGHT, WidgetUpdater.ACTION_STALE -> WidgetKind.entries.toSet()
            else -> return
        }
        val app = context.applicationContext as OpenStrapApp
        val done = goAsync()
        launchForBroadcast(app.container.appScope, "widgets: alarm ${intent.action}", done::finish) {
            app.container.widgets.update(kinds)
            // The update sets the next midnight only when something was placed, and this one has passed either way.
            if (intent.action == WidgetUpdater.ACTION_MIDNIGHT) app.container.widgets.scheduleMidnight()
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
