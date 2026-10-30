package org.openstrap.app.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.settings.HealthConnectAuto
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.StrapController
import java.util.concurrent.TimeUnit

/**
 * Auto sync to Health Connect's decisions: what range to write and when. Plain functions, so every boundary is tested.
 */
object AutoHealthConnect {
    /** After a sync, the last two days are written again: late minutes, a night that only just ended, a trimmed workout. */
    const val RECENT_S = 2 * 86_400L

    /** Never more than the manual export's 30 days in one go. */
    const val MAX_S = 30 * 86_400L

    /** A sync's end waits this long for the syncs that follow it (pushes, the gap fill) before writing. */
    const val QUIET_MS = 60_000L

    /** And writes at most this often, however often the strap syncs. */
    const val GAP_MS = 15 * 60_000L

    /**
     * What to write at [now] (epoch seconds): the last [RECENT_S], reaching back to [RECENT_S] before the last write
     * ([lastSynced]) when that's longer ago, so days the phone was away still go across. At most [MAX_S].
     */
    fun range(now: Long, lastSynced: Long?): Pair<Long, Long> {
        val from = minOf(now, lastSynced ?: now) - RECENT_S
        return from.coerceAtLeast(now - MAX_S) to now
    }

    /**
     * How long after a sync ending at [nowMs] to write: [QUIET_MS], or longer to keep [GAP_MS] after the last write
     * ([lastSyncedMs]).
     */
    fun delayMs(nowMs: Long, lastSyncedMs: Long?): Long =
        maxOf(QUIET_MS, lastSyncedMs?.let { it + GAP_MS - nowMs } ?: 0L)

    /**
     * The range a saved workout from [from] to [end] is written over: from the start of [from]'s hour to the end of
     * [end]'s, not past [now], so each hour of heart rate is written whole and never shrinks to the workout's part.
     */
    fun workoutRange(from: Long, end: Long, now: Long): Pair<Long, Long> =
        from / 3600 * 3600 to minOf(maxOf(now, end), (end / 3600 + 1) * 3600 - 1)

    /**
     * The range written after a sleep the strap started at [strapStart] was edited: one that reaches every time it
     * can be shown at ([org.openstrap.app.data.SleepEdits.MAX_SHIFT_SECONDS] either side of a session up to 16 h), not
     * past [now]. The export's own sleep delete reaches a day further back.
     */
    fun sleepRange(strapStart: Long, now: Long): Pair<Long, Long> {
        val shift = org.openstrap.app.data.SleepEdits.MAX_SHIFT_SECONDS
        return strapStart - shift to minOf(now, strapStart + org.openstrap.app.data.SleepEdits.SLEEP_MAX_SECONDS + shift)
    }

    /** Why auto sync has to stop: Health Connect not [available], or the write permissions not [granted]. Null when fine. */
    fun stopReason(available: Boolean, granted: Boolean): HealthConnectAuto.Stopped? = when {
        !available -> HealthConnectAuto.Stopped.UNAVAILABLE
        !granted -> HealthConnectAuto.Stopped.PERMISSIONS
        else -> null
    }
}

/**
 * Writes to Health Connect by itself while the user's "Auto sync to Health Connect" is on: the recent days after
 * every strap sync ([StrapController.syncEnded], debounced through WorkManager), and a workout as soon as it's saved.
 * Everything runs off the main thread and never throws; a failure is logged. Health Connect gone or its permissions
 * taken back turns the setting off with the reason, for the You page to say; no notification.
 */
class HealthConnectAutoSync(
    private val context: Context,
    private val settings: SettingsStore,
    private val exporter: HealthConnectExporter,
    strap: StrapController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch { strap.syncEnded.collect { syncEnded() } }
    }

    /** A strap sync ended: queues a write of the recent days, unless one is queued already. */
    private suspend fun syncEnded() {
        try {
            val auto = settings.healthConnectAuto.first()
            if (!auto.on) return
            val delay = AutoHealthConnect.delayMs(System.currentTimeMillis(), auto.lastSynced?.times(1000))
            // KEEP: a write already waiting reads the database when it runs, so it covers this sync too.
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HealthConnectWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "health connect: auto sync not queued: $e")
        }
    }

    /** A phone workout was saved (ended, added from history, trimmed): writes it now. */
    fun workoutSaved(start: Long) {
        scope.launch {
            guarded("workout $start") {
                val n = exporter.exportWorkout(start)
                Log.i(TAG, "health connect: wrote workout $start, $n records")
            }
        }
    }

    /**
     * A sleep or nap the strap started at [strapStart] was moved, deleted or brought back: writes its night again now
     * ([AutoHealthConnect.sleepRange]), not on the next sync, so Health Connect moves or drops it too.
     */
    fun sleepEdited(strapStart: Long) {
        scope.launch {
            guarded("sleep $strapStart") {
                val (from, to) = AutoHealthConnect.sleepRange(strapStart, System.currentTimeMillis() / 1000)
                val n = exporter.export(from, to)
                Log.i(TAG, "health connect: wrote the sleep at $strapStart again, $n records")
            }
        }
    }

    /** Writes the recent days ([AutoHealthConnect.range]); from [HealthConnectWorker]. */
    suspend fun syncRecent() {
        guarded("recent days") {
            val now = System.currentTimeMillis() / 1000
            val (from, to) = AutoHealthConnect.range(now, settings.healthConnectAuto.first().lastSynced)
            val n = exporter.export(from, to)
            settings.setHealthConnectSynced(now)
            Log.i(TAG, "health connect: auto sync wrote $n records")
        }
    }

    /** The user turned auto sync on, with the permissions granted: on, and the last 30 days written at once. Returns the records written. */
    suspend fun turnOn(): Int {
        settings.setHealthConnectAuto(true)
        val now = System.currentTimeMillis() / 1000
        val n = exporter.export(now - AutoHealthConnect.MAX_S, now)
        settings.setHealthConnectSynced(now)
        return n
    }

    /**
     * While auto sync is on, whether it still can: Health Connect there and the write permissions granted. When not,
     * turns it off with the reason. False when it's off.
     */
    suspend fun check(): Boolean {
        if (!settings.healthConnectAuto.first().on) return false
        val available = runCatching { exporter.isAvailable() }.getOrDefault(false)
        val granted = available && runCatching { exporter.hasPermissions() }.getOrDefault(false)
        val why = AutoHealthConnect.stopReason(available, granted) ?: return true
        settings.stopHealthConnectAuto(why)
        Log.i(TAG, "health connect: auto sync stopped, $why")
        return false
    }

    /** Runs [write] while auto sync is on and still can; a permission taken back mid-write stops it too. Never throws. */
    private suspend fun guarded(what: String, write: suspend () -> Unit) {
        try {
            if (!check()) return
            write()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            runCatching { settings.stopHealthConnectAuto(HealthConnectAuto.Stopped.PERMISSIONS) }
            Log.w(TAG, "health connect: auto sync stopped writing $what: $e")
        } catch (e: Exception) {
            Log.w(TAG, "health connect: auto sync of $what failed: $e")
        }
    }

    companion object {
        private const val TAG = "OpenStrap"
        private const val WORK = "health_connect_auto"
    }
}

/** The debounced write after a sync; its result is always success, so WorkManager never retries in a loop. */
class HealthConnectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as OpenStrapApp).container.autoHealthConnect.syncRecent()
        return Result.success()
    }
}
