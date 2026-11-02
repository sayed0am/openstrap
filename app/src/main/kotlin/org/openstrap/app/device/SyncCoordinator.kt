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
 * History sync: one at a time, a full sync or the types the strap pushed queued behind it, the hourly auto sync, and
 * the work that follows each sync (body signals, workout gap fill, workout effect, daily energy).
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

    private var lastAutoSync = 0L

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
     * Sync right after connecting (unless one ran recently and didn't fail), then every [AUTO_SYNC_SECONDS] while
     * connected. A failed one is retried at once: it was most likely cut off by the link this connect replaces.
     */
    fun autoSync(s: ChannelSession) = scope.launch {
        while (isActive && link.session === s) {
            val now = nowSeconds()
            val due = now - lastAutoSync >= MIN_SYNC_GAP_SECONDS || sync.value is SyncState.Failed
            if (due && sync.value !is SyncState.Running && !firmwareUpdating.value) {
                lastAutoSync = now
                log("auto sync")
                syncNow()
            }
            delay(AUTO_SYNC_SECONDS * MILLIS_PER_SECOND)
        }
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
        if (full) pendingFull.set(true)
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
        catching { updateDailyEnergy() }.onFailure { log("daily energy: $it") }
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
        if ((settings.lastSync(BODY_SIGNALS_PUSHED) ?: 0L) >= day) return
        val r = org.openstrap.app.data.RecoveryEngine(dao).compute(today, settings.profile.first())
        val body = r.readiness.bodySignals ?: return
        if (body.day != day || body.state != org.openstrap.app.data.BodySignals.State.Alert) return
        notifiers.healthAlerts.showBodySignals(body.families, body.nightsOff)
        settings.setLastSync(BODY_SIGNALS_PUSHED, day)
    }

    /**
     * Once per change of [org.openstrap.app.data.WorkoutEffect.MODEL], works out every phone workout's effect again,
     * so a workout saved under the old model shows the new one's Load. The strap's own figures, where it kept a
     * record, still win ([org.openstrap.app.data.WorkoutEffectEngine.strapEffect]).
     */
    private suspend fun updateEffectModel() {
        val model = org.openstrap.app.data.WorkoutEffect.MODEL.toLong()
        if (settings.lastSync(EFFECT_MODEL) == model) return
        val n = org.openstrap.app.data.WorkoutEffectEngine(dao).recomputeAll(settings.profile.first())
        settings.setLastSync(EFFECT_MODEL, model)
        log("workout effect: model $model, $n workouts again")
    }

    /**
     * After a sync, keeps each day's energy row ([org.openstrap.app.data.DailyEnergyHistory]): the first time every day
     * with data, then the recent days, and all of them again when the profile they were worked out with has changed.
     */
    private suspend fun updateDailyEnergy() {
        val profile = settings.profile.first()
        val key = org.openstrap.app.data.DailyEnergyHistory.profileKey(profile)
        val changed = settings.lastSync(DAILY_ENERGY_PROFILE) != key
        val written = org.openstrap.app.data.DailyEnergyHistory(dao).update(profile, profileChanged = changed)
        settings.setLastSync(DAILY_ENERGY_PROFILE, key)
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
        const val MIN_SYNC_GAP_SECONDS = 15 * 60L
        const val AUTO_SYNC_SECONDS = 60 * 60L
        const val MILLIS_PER_SECOND = 1_000L
        const val SECONDS_PER_DAY = 86_400L
        /** Settings key: the local midnight of the last morning a body-signal alert was pushed. */
        const val BODY_SIGNALS_PUSHED = "body_signals_push"
        /** [org.openstrap.app.data.DailyEnergyHistory.profileKey] of the profile the daily energy rows were last written with. */
        const val DAILY_ENERGY_PROFILE = "daily_energy_profile"
        /** The [org.openstrap.app.data.WorkoutEffect.MODEL] phone workouts' effects were last worked out with. */
        const val EFFECT_MODEL = "workout_effect_model"
    }
}
