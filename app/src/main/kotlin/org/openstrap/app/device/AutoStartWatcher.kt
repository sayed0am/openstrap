package org.openstrap.app.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openstrap.app.data.AutoStart
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.SettingsStore

/**
 * "Looks like a workout" ([AutoStart]): watches for an effort under way that nobody started, while auto-start is on,
 * the strap is connected and nothing records, and offers it. [WorkoutSession] owns it and starts what is recorded.
 */
// Built once, by WorkoutSession, from what that was built with.
@Suppress("LongParameterList")
internal class AutoStartWatcher(
    private val dao: HealthDao,
    private val settings: SettingsStore,
    private val strap: StrapController,
    private val state: StateFlow<WorkoutState>,
    private val callRinging: () -> Boolean,
    private val maxHeartRate: suspend () -> Int,
    /** "Record": starts the workout [AutoStart.Offer.sportType] at [AutoStart.Offer.start], with its readings and steps. */
    private val record: (offer: AutoStart.Offer, seed: List<Pair<Long, Int>>, seedSteps: Int?) -> Unit,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    /**
     * The effort offered, while the question is open. The strap buzzes once as it is asked, and the notification and
     * the Today and Activity tabs ask it.
     */
    private val _offer = MutableStateFlow<AutoStart.Offer?>(null)
    val offer: StateFlow<AutoStart.Offer?> = _offer
    /** Idle live readings and step totals of the last [WATCH_S], from probes and from streaming while effort lasts. */
    private val idleReadings = mutableListOf<Pair<Long, Int>>()
    private var idleSteps = listOf<Pair<Long, Int>>()
    /** Effort before this isn't offered: the end of the last workout. */
    var offerNotBefore = 0L
    /** "Not now": quiet until the effort under way has ended. */
    private var declined = false

    /** "Record": the workout starts where the effort began, with the readings since. */
    fun recordOffer() {
        val o = _offer.value ?: return
        _offer.value = null
        val seed = synchronized(idleReadings) { idleReadings.filter { it.first >= o.start } }
        val steps = idleSteps.firstOrNull { it.first >= o.start }?.second
        (strap as? StrapManager)?.log("auto-start: recording ${o.sportType} from ${o.start}, ${seed.size} readings")
        record(o, seed, steps)
    }

    fun declineOffer() {
        if (_offer.value == null) return
        _offer.value = null
        declined = true
    }

    /** Watches for effort whenever auto-start is on, the strap is connected and nothing records. */
    fun watch() = scope.launch {
        combine(settings.autoStartWorkouts, state, strap.connection) { on, st, c ->
            on && st !is WorkoutState.Recording && c is ConnectionState.Connected
        }.distinctUntilChanged().collectLatest { watching ->
            if (watching) watchEffort() else {
                strap.setEffortWatch(false)
                _offer.value = null
            }
        }
    }

    /**
     * Probes live HR for [AutoStart.PROBE_S] every [AutoStart.PROBE_EVERY_S], and keeps it streaming while the heart
     * rate is up or the feet are busy; asks once an effort has lasted long enough ([AutoStart.offer]).
     */
    // One loop decides probe, stream and offer together, and reads best whole.
    @Suppress("CyclomaticComplexMethod")
    private suspend fun watchEffort() = coroutineScope {
        val maxHr = maxHeartRate()
        val resting = catching {
            dao.restingHrOnce(now() - WorkoutSession.RESTING_LOOKBACK_S, now()).lastOrNull()?.bpm
        }.getOrNull() ?: WorkoutSession.DEFAULT_RESTING_HR
        val threshold = AutoStart.threshold(resting, maxHr)
        val log = { m: String -> (strap as? StrapManager)?.log(m); Unit }
        launch {
            var last = -1L
            strap.heartRateReadings.collect { bpm ->
                val t = now()
                if (t == last || !WorkoutRecorder.isValid(bpm)) return@collect
                last = t
                synchronized(idleReadings) {
                    idleReadings += t to bpm
                    while (idleReadings.first().first < t - WATCH_S) idleReadings.removeAt(0)
                }
            }
        }
        launch {
            strap.todaySteps.collect { s ->
                s ?: return@collect
                val t = now()
                idleSteps = (idleSteps + (t to s.steps)).dropWhile { it.first < t - WATCH_S }
            }
        }
        try {
            var probeUntil = 0L
            var nextProbe = now()
            var streaming = false
            while (isActive) {
                val t = now()
                val cadence = cadence(t)
                val snapshot = synchronized(idleReadings) { idleReadings.toList() }
                val hot = AutoStart.keepStreaming(snapshot.filter { it.first >= t - 60 }, threshold, cadence)
                val stream = when {
                    t < probeUntil || hot -> true
                    t >= nextProbe -> { probeUntil = t + AutoStart.PROBE_S; true }
                    else -> false
                }
                if (stream && !hot && t >= nextProbe) nextProbe = t + AutoStart.PROBE_EVERY_S
                if (stream != streaming) log("auto-start: ${if (stream) "streaming" else "probe over"}${if (hot) " (effort)" else ""}")
                streaming = stream
                strap.setEffortWatch(stream)
                val o = AutoStart.offer(snapshot, resting, maxHr, cadence, t, offerNotBefore)
                if (o == null) {
                    declined = false
                    if (_offer.value != null) { _offer.value = null; log("auto-start: effort ended before an answer") }
                } else if (_offer.value == null && !declined) {
                    _offer.value = o
                    log("auto-start: offering ${o.sportType} from ${o.start}, ${o.averageHeartRate} bpm")
                    if (!callRinging()) launch { catching { strap.buzz(WorkoutSession.OFFER_BUZZ) } }
                }
                delay(WATCH_TICK_MS)
            }
        } finally {
            strap.setEffortWatch(false)
        }
    }

    /**
     * Steps a minute over the last few minutes of [idleSteps], or null without two totals a minute apart (the strap
     * reports about once a minute).
     */
    private fun cadence(t: Long): Int? {
        val recent = idleSteps.filter { it.first >= t - CADENCE_WINDOW_S }
        val first = recent.firstOrNull() ?: return null
        val last = recent.last()
        return if (last.first - first.first < MINUTE_S) null
        else ((last.second - first.second) * MINUTE_S / (last.first - first.first)).toInt().coerceAtLeast(0)
    }

    private fun now() = clock()

    companion object {
        /** Long enough for an effort to be offered from its start. */
        const val WATCH_S = 2 * 3600L
        const val WATCH_TICK_MS = 5_000L
        /** Cadence reads the step totals of the last four minutes, and needs two at least a minute apart. */
        const val CADENCE_WINDOW_S = 240L
        const val MINUTE_S = 60L
    }
}
