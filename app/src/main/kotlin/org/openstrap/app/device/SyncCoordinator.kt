package org.openstrap.app.device

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.protocol.ChannelSession

/**
 * History sync: one at a time, a full sync or the types the strap pushed queued behind it, the automatic syncs
 * [SyncPolicy] allows, and the work that follows each sync (body signals, workout gap fill, workout effect, daily
 * calories).
 */
// The sync queue and each piece of work after a sync, as small functions.
@Suppress("TooManyFunctions")
internal class SyncCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val dao: HealthDao,
    private val link: LiveLink,
    private val firmwareUpdating: StateFlow<Boolean>,
    private val notifiers: Notifiers,
    private val log: (String) -> Unit,
) {
    /** Where a sync's findings go: a detected workout, and the morning's body-signal alert. */
    class Notifiers(val autoDetected: AutoDetectNotifier, val healthAlerts: HealthAlertNotifier)

    val sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncRecord = MutableStateFlow(SyncRecord())
    val syncEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** When the last full sync started, of any kind; null before the first in this process. */
    @Volatile private var lastFullSync: Long? = null

    /** Whether the app is in front, so a connect meanwhile counts as the user looking. */
    @Volatile private var appVisible = false

    init {
        // Every sync's start and end, pulled or in the background, so Today can tell a stuck sync from a slow one.
        scope.launch {
            var previous: SyncState = SyncState.Idle
            sync.collect { s ->
                syncRecord.update { it.next(previous, s, nowSeconds()) }
                previous = s
            }
        }
    }

    /**
     * On connect, then every [CHECK_SECONDS] while connected: syncs when [SyncPolicy] says one is due. A failed sync is
     * retried at the next check: it was most likely cut off by a lost link.
     */
    fun autoSync(s: ChannelSession) = scope.launch {
        // A connect while the app is open is the user looking, as Zepp's home page is.
        var trigger = if (appVisible) SyncPolicy.Trigger.APP_IN_FRONT else SyncPolicy.Trigger.CONNECT
        while (isActive && link.session === s) {
            autoSyncIfDue(trigger)
            trigger = SyncPolicy.Trigger.TIMER
            delay(CHECK_SECONDS * MILLIS_PER_SECOND)
        }
    }

    /**
     * The app came to the front or left it. In front, sync if the last one is a few minutes old, as Zepp does when
     * its home page shows; a strap not connected yet syncs by the same rule when it connects.
     */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (visible && link.activeSession != null) autoSyncIfDue(SyncPolicy.Trigger.APP_IN_FRONT)
    }

    private fun autoSyncIfDue(trigger: SyncPolicy.Trigger) {
        if (sync.value is SyncState.Running || firmwareUpdating.value) return
        if (!SyncPolicy.due(trigger, nowSeconds(), lastFullSync, sync.value is SyncState.Failed)) return
        log("auto sync (${trigger.name.lowercase()})")
        syncNow()
    }

    fun syncNow() = startSync(full = true)

    /** The strap pushed new data of [types]: sync them now, or after the sync running. */
    fun syncPushed(types: Set<Int>) {
        synchronized(pendingTypes) { pendingTypes += types }
        startSync(full = false)
    }

    /** One sync at a time; a full sync or types asked for meanwhile run when it ends. */
    private val syncLock = Mutex()
    private val pendingTypes = mutableSetOf<Int>()
    private val pendingFull = java.util.concurrent.atomic.AtomicBoolean(false)

    /** A sync holds the lock, so a firmware install mustn't start. */
    val isSyncing: Boolean get() = syncLock.isLocked

    private fun takePending(): Set<Int> = synchronized(pendingTypes) { pendingTypes.toSet().also { pendingTypes.clear() } }
    private fun hasPending(): Boolean = pendingFull.get() || synchronized(pendingTypes) { pendingTypes.isNotEmpty() }

    /** What to sync next: null for everything (a full sync covers anything pushed so far), or the pushed types. */
    private fun takeNext(): Set<Int>? = if (pendingFull.getAndSet(false)) { takePending(); null } else takePending()

    // Each return is a reason not to sync now.
    @Suppress("ReturnCount")
    private fun startSync(full: Boolean) {
        if (firmwareUpdating.value) return
        val s = link.activeSession ?: return
        val client = link.gatt ?: return
        if (full) {
            pendingFull.set(true)
            lastFullSync = nowSeconds()
        }
        scope.launch {
            // A sync is running: it picks up what was just queued when it finishes.
            if (!syncLock.tryLock()) return@launch
            try {
                while (link.activeSession === s && !firmwareUpdating.value) {
                    val only = takeNext()
                    if (only != null && only.isEmpty()) break
                    HistorySync(s, client, dao, settings, sync, log, notifiers.autoDetected::show).run(only)
                    (sync.value as? SyncState.Done)?.let { d ->
                        catching { settings.setLastSyncDone(d.atEpochSeconds) }.onFailure { log("sync time not kept: $it") }
                    }
                }
                afterSync()
                // Everything a widget shows may have moved (widgets spec 7): they watch for this.
                syncEnded.tryEmit(Unit)
            } finally {
                syncLock.unlock()
            }
            // A sync cut off by a lost link can end only after the reconnect, which skipped its own sync while this one
            // still showed as running. Run it again on the new link.
            val current = link.activeSession
            if (sync.value is SyncState.Failed && current != null && current !== s) {
                log("sync cut off by the old link, syncing again")
                startSync(full = true)
            } else if (hasPending()) {
                // Something queued between the last check and the unlock would otherwise wait for the next trigger.
                startSync(full = false)
            }
        }
    }

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

    private fun nowSeconds() = System.currentTimeMillis() / MILLIS_PER_SECOND

    private companion object {
        /** How often a connected strap is checked for a due sync. */
        const val CHECK_SECONDS = 15 * 60L
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

/**
 * When an automatic sync is due, set to Zepp's pace (zepp-alt analysis/firmware/01 §10): Zepp syncs on connect, when
 * its home page shows (no more than once in 5 minutes), on workout pushes and about every 6 hours in the background.
 * It stays connected, while unstrap reconnects many times a day, so a connect only syncs once the background interval
 * has passed. Syncing far more often went with the strap's activity store gaining minutes that never happened, which
 * placed its sleep hours late. Syncs the user asks for, workout ends and strap pushes don't go through this.
 */
internal object SyncPolicy {
    const val BACKGROUND_SECONDS = 6 * 3600L
    const val IN_FRONT_GAP_SECONDS = 5 * 60L

    enum class Trigger { CONNECT, TIMER, APP_IN_FRONT }

    /** [lastFullSync] is when the last full sync of any kind started, null when none has run yet. */
    fun due(trigger: Trigger, now: Long, lastFullSync: Long?, lastFailed: Boolean): Boolean {
        if (lastFullSync == null || lastFailed) return true
        val gap = if (trigger == Trigger.APP_IN_FRONT) IN_FRONT_GAP_SECONDS else BACKGROUND_SECONDS
        return now - lastFullSync >= gap
    }
}
