package org.openstrap.app.device

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.openstrap.app.R
import org.openstrap.app.data.catching
import org.openstrap.app.i18n.Strings
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.protocol.ChannelSession

/**
 * History sync, queued and timed as Zepp 10.10.5 does (zepp-alt analysis/codemap/sync-zepp-vs-unstrap.md §1): a
 * connect with the app in front, a user action, every 12th strap ping in the background, and the strap's pushes. One
 * queue, one sync at a time, nothing retried on its own. Then the work that follows each sync (body signals, workout
 * gap fill, workout effect, daily calories).
 */
// The sync queue and each piece of work after a sync, as small functions.
@Suppress("TooManyFunctions", "LongParameterList")
internal class SyncCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val dao: HealthDao,
    private val link: LiveLink,
    private val firmwareUpdating: StateFlow<Boolean>,
    private val notifiers: Notifiers,
    private val log: (String) -> Unit,
    /** Whether a phone workout records: Zepp starts no connect or background sync during a phone GPS workout. */
    private val workoutActive: () -> Boolean,
    /** After every finished sync: Zepp's doFree writes the clock with its DST hint. */
    private val afterFinished: suspend (ChannelSession) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Where a sync's findings go: a detected workout, and the morning's body-signal alert. */
    class Notifiers(val autoDetected: AutoDetectNotifier, val healthAlerts: HealthAlertNotifier)

    val sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncRecord = MutableStateFlow(SyncRecord())
    val syncEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Whether the app is in front: Zepp's connect sync needs it. */
    @Volatile private var appVisible = false

    private val policy = SyncPolicy()

    /** Zepp's per-device queue: capacity 5, run one task at a time by a single consumer. */
    private val queue = Channel<SyncTask>(SyncPolicy.QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)

    @Volatile private var running = false

    /** A sync is running, so a firmware install mustn't start. */
    val isSyncing: Boolean get() = running

    init {
        // Every sync's start and end, pulled or in the background, so Today can tell a stuck sync from a slow one.
        scope.launch {
            var previous: SyncState = SyncState.Idle
            sync.collect { s ->
                syncRecord.update { it.next(previous, s, clock() / MILLIS_PER_SECOND) }
                previous = s
            }
        }
        scope.launch { for (task in queue) runTask(task) }
    }

    /**
     * Connected (DEVICE_CONNECTED): a full sync only with the app in front and more than 120 s since the last
     * successful sync. A connect in the background doesn't sync.
     */
    fun onConnected() = scope.launch {
        when {
            !appVisible -> log("sync: connected in the background, no sync")
            workoutActive() -> log("sync: connected during a phone workout, no sync")
            !SyncPolicy.successGapPassed(clock(), lastSuccessMs()) -> log("sync: connected, last sync under 120 s ago")
            else -> enqueue(SyncTask.Full(SyncTask.Trigger.DEVICE_CONNECTED))
        }
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
    }

    /**
     * A user action (the app comes to the front, pull-to-refresh, the widget, the notification's Sync, Sync now): a
     * full sync if more than 120 s since the last successful one, else a 1 s progress with nothing sent, as Zepp's
     * USER_ACTION does.
     */
    fun userAction() {
        scope.launch {
            if (link.activeSession == null) return@launch
            if (SyncPolicy.successGapPassed(clock(), lastSuccessMs())) {
                enqueue(SyncTask.Full(SyncTask.Trigger.USER_ACTION))
            } else {
                log("sync: last sync under 120 s ago, progress only")
                fakeProgress(USER_ACTION_FAKE_MS)
            }
        }
    }

    /**
     * A strap ping (WAKE_UP). Every 12th per device queues a background sync after a random 1 to 59 s, if more than
     * 120 s have passed since the last activity minute synced.
     */
    fun onPing(s: ChannelSession) {
        if (!policy.backgroundSyncDue()) return
        val wait = SyncPolicy.backgroundDelayMs()
        log("sync: ping ${policy.wakeUps}, background sync in ${wait / MILLIS_PER_SECOND} s")
        scope.launch {
            delay(wait)
            if (link.activeSession !== s) return@launch
            val minute = catching { settings.lastSync(ACTIVITY_CURSOR) }.getOrNull()?.times(MILLIS_PER_SECOND)
            when {
                workoutActive() -> log("sync: background sync skipped, a phone workout records")
                !SyncPolicy.successGapPassed(clock(), minute) -> log("sync: background sync skipped, synced under 120 s ago")
                else -> enqueue(SyncTask.Full(SyncTask.Trigger.LOW_FREQUENCY))
            }
        }
    }

    /** The strap pushed `11 <type>`: Zepp queues the types, and after a workout push a full sync too. */
    fun syncPushed(typeCode: Int, types: Set<Int>) {
        enqueue(SyncTask.Demand(types))
        if (typeCode in WORKOUT_PUSHES) enqueue(SyncTask.Full(SyncTask.Trigger.FIRMWARE_NOTIFICATION, forceAll = true))
    }

    private fun enqueue(task: SyncTask) {
        if (queue.trySend(task).isFailure) log("sync: queue closed, $task dropped")
    }

    /** Zepp's `SyncCenterManager.start` checks, the 60 s mock, then the sync itself under a 30 min limit. */
    // Each return is a reason the task doesn't reach the strap.
    @Suppress("ReturnCount")
    private suspend fun runTask(task: SyncTask) {
        val s = link.activeSession
        val client = link.gatt
        if (s == null || client == null) return log("sync: $task dropped, not connected")
        if (firmwareUpdating.value) return log("sync: $task dropped, firmware update running")
        if (task is SyncTask.Full && !task.forceAll && policy.mock(lastStartMs, clock())) {
            log("sync: $task within 60 s of the last, progress only")
            return fakeProgress(MOCK_FAKE_MS)
        }
        if (task !is SyncTask.Demand) lastStartMs = clock()
        log("sync: $task")
        running = true
        try {
            val only = (task as? SyncTask.Demand)?.types
            catching {
                withTimeout(SyncPolicy.WHOLE_SYNC_MS) {
                    HistorySync(s, client, dao, settings, sync, log, notifiers.autoDetected::show).run(only)
                }
            }.onFailure { log("sync: $it") }
            if (sync.value is SyncState.Running) sync.value = SyncState.Failed(Strings.app.get(R.string.system_sync_stopped))
            (sync.value as? SyncState.Done)?.let { d ->
                lastSuccessMs = d.atEpochSeconds * MILLIS_PER_SECOND
                catching { settings.setLastSyncDone(d.atEpochSeconds) }.onFailure { log("sync time not kept: $it") }
            }
            afterSync()
            if (link.activeSession === s) catching { afterFinished(s) }.onFailure { log("after sync: $it") }
            // Everything a widget shows may have moved (widgets spec 7): they watch for this.
            syncEnded.tryEmit(Unit)
        } finally {
            running = false
        }
    }

    /** Zepp's progress with nothing sent: Running for [ms], then back to what it showed. */
    private suspend fun fakeProgress(ms: Long) {
        val before = sync.value
        sync.value = SyncState.Running(Strings.app.get(R.string.system_sync_preparing))
        delay(ms)
        if (sync.value is SyncState.Running) sync.value = before
    }

    /** When the last full sync started, of any kind but a push's (Zepp's lastSyncTimeMap). */
    @Volatile private var lastStartMs: Long? = null

    /** When a sync last succeeded, kept across restarts in [SettingsStore.setLastSyncDone]. */
    @Volatile private var lastSuccessMs: Long? = null

    private suspend fun lastSuccessMs(): Long? =
        lastSuccessMs ?: catching { settings.strapSnapshot.first().lastSyncDone?.times(MILLIS_PER_SECOND) }.getOrNull()

    private suspend fun afterSync() {
        catching { notifyBodySignals() }.onFailure { log("body signals: $it") }
        catching { WorkoutGapFill(dao) { settings.profile.first() }.run()?.let { log(it) } }
            .onFailure { log("workout gap fill: $it") }
        catching { updateEffectModel() }.onFailure { log("workout effect: $it") }
        catching { updateDailyCalories() }.onFailure { log("daily energy: $it") }
    }

    /**
     * After a sync, pushes the body-signal alert for this morning once, when the state machine is in Alert
     * ([org.openstrap.app.data.BodySignals]). Watch stays in the app.
     */
    // Each return is a reason not to push.
    @Suppress("ReturnCount")
    private suspend fun notifyBodySignals() {
        val today = java.time.LocalDate.now()
        val day = org.openstrap.app.data.DayWindow.of(today).from
        // By date, so after a flight west the same morning doesn't push again.
        val pushDay = org.openstrap.app.data.DayKey.of(today)
        if ((settings.lastSync(BODY_SIGNALS_PUSHED) ?: 0L) >= pushDay) return
        val r = org.openstrap.app.data.RecoveryEngine(dao).compute(today, settings.profile.first())
        val body = r.readiness.bodySignals ?: return
        if (body.day != day || body.state != org.openstrap.app.data.BodySignals.State.Alert) return
        notifiers.healthAlerts.showBodySignals(body.families, body.nightsOff)
        settings.setLastSync(BODY_SIGNALS_PUSHED, pushDay)
    }

    /**
     * Once per change of [org.openstrap.app.data.WorkoutEffect.MODEL], works out every phone workout's effect again,
     * so a workout saved under the old model shows the new one's Load. The strap's own figures, where it kept a
     * record, still win ([org.openstrap.app.data.WorkoutEffectEngine.strapEffect]).
     */
    private suspend fun updateEffectModel() {
        val model = org.openstrap.app.data.WorkoutEffect.MODEL.toLong()
        if (settings.lastSync(EFFECT_MODEL) == model) return
        val n = org.openstrap.app.data.WorkoutEffectEngine(dao).recomputeAll(settings.profile.first(), WorkoutSession.LOCK)
        settings.setLastSync(EFFECT_MODEL, model)
        log("workout effect: model $model, $n workouts again")
    }

    /**
     * After a sync, keeps each day's calories row ([org.openstrap.app.data.DailyCaloriesHistory]): the first time every day
     * with data, then the recent days, and all of them again when the profile they were worked out with has changed.
     */
    private suspend fun updateDailyCalories() {
        val profile = settings.profile.first()
        val key = org.openstrap.app.data.DailyCaloriesHistory.profileKey(profile)
        val changed = settings.lastSync(DAILY_CALORIES_PROFILE) != key
        val written = org.openstrap.app.data.DailyCaloriesHistory(dao).update(profile, profileChanged = changed)
        settings.setLastSync(DAILY_CALORIES_PROFILE, key)
        if (written > 0) log("daily energy: $written days")
    }

    /** Debug aid, see [HistorySync.dumpRaw]. */
    fun dumpRaw(days: Int, types: Set<Int>? = null) {
        val s = link.activeSession ?: return log("dump: not connected")
        val client = link.gatt ?: return
        scope.launch {
            val since = nowSeconds() - days * SECONDS_PER_DAY
            HistorySync(s, client, dao, settings, sync, log).dumpRaw(since, java.io.File(context.filesDir, "raw"), types)
        }
    }

    private fun nowSeconds() = clock() / MILLIS_PER_SECOND

    private companion object {
        /** Zepp's USER_ACTION progress with nothing sent (`h2d` case 1). */
        const val USER_ACTION_FAKE_MS = 1_000L
        /** Zepp's mock sync: ten progress steps 200 ms apart (`bxt.b`). */
        const val MOCK_FAKE_MS = 2_000L
        /** Pushes after which Zepp queues a full sync too (`rqi` case 1). */
        val WORKOUT_PUSHES = setOf(5, 6)
        /** The activity cursor, Zepp's device lastSyncTime for the background sync. */
        const val ACTIVITY_CURSOR = "gdsp_1"
        const val MILLIS_PER_SECOND = 1_000L
        const val SECONDS_PER_DAY = 86_400L
        /**
         * Settings key: the last morning a body-signal alert was pushed ([org.openstrap.app.data.DayKey]). Builds before
         * kept its local midnight under another key, so the upgrade morning can push once more at most.
         */
        const val BODY_SIGNALS_PUSHED = "body_signals_push_day"
        /**
         * [org.openstrap.app.data.DailyCaloriesHistory.profileKey] of the profile the daily calories rows were last
         * written with. Stored under its first name, so the key saved before the rename still counts.
         */
        const val DAILY_CALORIES_PROFILE = "daily_energy_profile"
        /** The [org.openstrap.app.data.WorkoutEffect.MODEL] phone workouts' effects were last worked out with. */
        const val EFFECT_MODEL = "workout_effect_model"
    }
}

/** A queued sync: a full one, or the types a push named (Zepp's DemandGDSPStrategy). */
internal sealed interface SyncTask {
    enum class Trigger { DEVICE_CONNECTED, USER_ACTION, LOW_FREQUENCY, FIRMWARE_NOTIFICATION }

    /** [forceAll]: Zepp's `gvn` after a workout push, which skips the 60 s mock. */
    data class Full(val trigger: Trigger, val forceAll: Boolean = false) : SyncTask

    data class Demand(val types: Set<Int>) : SyncTask
}

/**
 * Zepp 10.10.5's sync timing, copied with its quirks (zepp-alt analysis/codemap/sync-zepp-10.10.5.md §1). Holds the
 * process-wide counters; one strap, so one device.
 */
internal class SyncPolicy {
    /** Strap pings since the app started (`rcd0.c`, per device). */
    var wakeUps = 0
        private set

    /** Calls of the 60 s mock check with a previous start (`bxt.c`, never reset). */
    private var mockChecks = 0

    /** Counts a ping. True on every [PINGS_PER_SYNC]th, Zepp's LOW_FREQUENCY sync. */
    @Synchronized
    fun backgroundSyncDue(): Boolean = ++wakeUps % PINGS_PER_SYNC == 0

    /**
     * Zepp's `bxt.a`: a full sync that starts no more than 60 s after the last one started becomes a progress run with
     * nothing sent. Every check with a previous start counts, and each 3rd count runs for real.
     */
    @Synchronized
    fun mock(lastStartMs: Long?, nowMs: Long): Boolean {
        if (lastStartMs == null || lastStartMs == 0L) return false
        val i = mockChecks++
        val forced = i != 0 && i % MOCK_FORCE_EVERY == 0
        return !forced && lastStartMs < nowMs && lastStartMs + MOCK_WINDOW_MS >= nowMs
    }

    companion object {
        const val PINGS_PER_SYNC = 12
        const val MOCK_FORCE_EVERY = 3
        const val MOCK_WINDOW_MS = 60_000L
        const val SUCCESS_GAP_MS = 120_000L
        const val QUEUE_CAPACITY = 5
        const val WHOLE_SYNC_MS = 30 * 60_000L
        private const val BACKGROUND_DELAY_MIN_MS = 1_000L
        private const val BACKGROUND_DELAY_MAX_MS = 59_000L

        /** Zepp's `n3d.f`: more than 120 s since [lastMs], or nothing yet. */
        fun successGapPassed(nowMs: Long, lastMs: Long?): Boolean = lastMs == null || nowMs - lastMs > SUCCESS_GAP_MS

        /** Zepp's `gjo(1000, 59000)`. */
        fun backgroundDelayMs(random: kotlin.random.Random = kotlin.random.Random): Long =
            random.nextLong(BACKGROUND_DELAY_MIN_MS, BACKGROUND_DELAY_MAX_MS + 1)
    }
}
