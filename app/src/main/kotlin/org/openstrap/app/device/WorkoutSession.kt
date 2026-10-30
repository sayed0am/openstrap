package org.openstrap.app.device

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.openstrap.app.data.AutoStart
import org.openstrap.app.data.RecoveryEngine
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.AppWorkoutRoutePoint
import org.openstrap.app.data.Route
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.widget.WidgetKind
import kotlin.math.roundToInt

sealed interface WorkoutState {
    data object Idle : WorkoutState
    /**
     * [workout] as stored: its pauses, and [AppWorkout.pausedAt] while paused. Zones are shares of [maxHeartRate].
     * [endPrompt] is the end proposed by "Looks like you've stopped", while that question is open.
     */
    data class Recording(
        val workout: AppWorkout,
        val maxHeartRate: Int,
        val endPrompt: Long? = null,
        val inCar: Boolean = false,
        /** Strength: the sets so far, and when the rest under way ends (null when not resting). */
        val sets: List<org.openstrap.app.data.db.AppWorkoutSet> = emptyList(),
        val restUntil: Long? = null,
        /** Strength: the exercise the next sets are of, null for numbered sets; and a log card after each set. */
        val exercise: String? = null,
        val logSets: Boolean = false,
        /**
         * A structured workout ([org.openstrap.app.data.Structure]): its rounds so far, which say the phase
         * ([org.openstrap.app.data.Phases]), and the seconds added to the rest under way (+10 s).
         */
        val rounds: List<org.openstrap.app.data.db.AppWorkoutRound> = emptyList(),
        val restExtra: Long = 0,
    ) : WorkoutState {
        val structure: org.openstrap.app.data.Structure? get() = org.openstrap.app.data.Structure.parse(workout.structure)

        /** The phase now, by the workout's active time. */
        fun phase(now: Long): org.openstrap.app.data.Phase? = structure?.let { s ->
            org.openstrap.app.data.Phases.phase(s, rounds, { t -> WorkoutRecorder.activeSeconds(workout, minOf(t, now)) }, restExtra)
        }
        val paused: Boolean get() = workout.pausedAt != null
        val strength: Boolean get() = workout.sportType in org.openstrap.app.data.Strength.SPORTS
        /** A set under way. */
        val inSet: Boolean get() = sets.lastOrNull()?.let { it.end == null } == true
        val resting: Boolean get() = restUntil != null
    }
    /**
     * Saved; its summary shows until it is dismissed. [preview] is a debug build opening the summary for a stored
     * workout ([WorkoutSession.showSummary]): nothing live, and nothing written just by showing it.
     */
    data class Ended(val start: Long, val preview: Boolean = false) : WorkoutState
}

/**
 * The heart rate after a workout [start] ended at [end], for the summary's heart rate drop: what the strap sent, a
 * reading a second (epoch seconds, bpm), until [until].
 */
data class CalmDown(val start: Long, val end: Long, val until: Long, val readings: List<Pair<Long, Int>>)

/**
 * The one workout state in the app: it drives the recorder, the notification and the screens. Live readings are
 * written to Room every [FLUSH_MS], so a killed process loses at most that much, and [restore] carries on with a
 * workout that was still recording when the process died. Minutes the phone missed are filled after the next sync
 * ([WorkoutGapFill]).
 */
class WorkoutSession(
    private val context: Context,
    private val dao: HealthDao,
    private val settings: SettingsStore,
    private val strap: StrapController,
    /** A coaching buzz never plays while a call rings: a motor pattern cuts off the call's buzz (hardware test). */
    private val callRinging: () -> Boolean = { false },
    /** A finished workout was saved or changed, by its start: auto sync writes it to Health Connect. Returns at once. */
    private val onSaved: (start: Long) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val state: StateFlow<WorkoutState> = _state

    /** Live readings of the last [RECENT_S] seconds, oldest first, for the live chart: (epoch seconds, bpm). */
    private val _recent = MutableStateFlow<List<Pair<Long, Int>>>(emptyList())
    val recent: StateFlow<List<Pair<Long, Int>>> = _recent

    /** False until [restore] has looked for a workout left recording, so the service doesn't stop before it knows. */
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored

    /** Average and peak over the whole workout so far, live readings and filled minutes alike. */
    private val _stats = MutableStateFlow<WorkoutRecorder.Stats?>(null)
    val stats: StateFlow<WorkoutRecorder.Stats?> = _stats
    private var sum = 0L
    private var count = 0L

    /** Seconds in each live zone so far ([WorkoutRecorder.liveZone]), for "6:40 in zone". */
    private val _zoneSeconds = MutableStateFlow(IntArray(org.openstrap.app.data.Workouts.ZONES))
    val zoneSeconds: StateFlow<IntArray> = _zoneSeconds
    private var lastReading: Pair<Long, Int>? = null

    /**
     * Active and total kcal so far ([WorkoutRecorder.liveKcalTick]), worked out every [KCAL_TICK_MS] from the readings
     * and steps so far, pauses left out; it never goes down. Null without weight, height and age.
     */
    private val _kcal = MutableStateFlow<WorkoutRecorder.Kcal?>(null)
    val kcal: StateFlow<WorkoutRecorder.Kcal?> = _kcal
    /** The strap's step totals over the whole workout, for its steps a minute until a sync brings the strap's own. */
    private var stepTotals = listOf<Pair<Long, Int>>()

    /** The heart rate coming down after the last workout ended, while [CALM_S] lasts; null before any. */
    private val _calmDown = MutableStateFlow<CalmDown?>(null)
    val calmDown: StateFlow<CalmDown?> = _calmDown
    private var calmJob: Job? = null

    /** A sport a launcher shortcut or the Quick Settings tile asked the ready screen to open with; the screen clears it. */
    val readyRequest = MutableStateFlow<Int?>(null)

    /** The Start widget's All sports asked for the ready screen on its sport picker; the screen clears it. */
    val readyPickRequest = MutableStateFlow(false)

    /** The start of a strap workout a notification asked to open on the Activity tab; the screen clears it. */
    val openRequest = MutableStateFlow<Long?>(null)

    /** The live screen is tucked away to use the rest of the app; the notification brings it back. */
    val minimized = MutableStateFlow(false)

    /** For "Looks like you've stopped": live readings and the strap's step totals of the last [STOP_WINDOW_S]. */
    private var window = listOf<Pair<Long, Int>>()
    private var steps = listOf<Pair<Long, Int>>()
    /** Nothing before this counts as activity for the end prompt: the start, or the last "Keep going". */
    private var askSince = 0L
    private var restingHr = DEFAULT_RESTING_HR

    private var recordJob: Job? = null
    private val buffer = mutableListOf<AppWorkoutHeartRate>()
    private var lastSecond = -1L
    private var wake: PowerManager.WakeLock? = null

    init {
        // A workout starting or ending changes the moment and the Start widget (widgets spec 7).
        scope.launch {
            var was: Boolean? = null
            var wasKind: Class<*>? = null
            var wasPaused: Boolean? = null
            _state.collect { st ->
                val recording = st is WorkoutState.Recording
                val paused = (st as? WorkoutState.Recording)?.paused
                // A summary closing can follow a discard, which takes a workout out of Start's week and Moment's
                // after a workout. A pause or a resume stops or restarts Start's timer.
                val kinds = when {
                    was != null && was != recording -> setOf(WidgetKind.Moment, WidgetKind.Start)
                    wasKind != null && wasKind != st::class.java -> setOf(WidgetKind.Moment, WidgetKind.Start)
                    paused != null && wasPaused != null && paused != wasPaused -> setOf(WidgetKind.Start)
                    else -> emptySet()
                }
                if (kinds.isNotEmpty()) WidgetUpdater.changed(context, kinds)
                was = recording
                wasKind = st::class.java
                wasPaused = paused
            }
        }
    }

    /** Picks up a workout left recording when the process died, then starts watching for auto-start. Called once at app start. */
    fun restore() {
        scope.launch {
            try {
                lock.withLock {
                    if (_state.value is WorkoutState.Recording) return@withLock
                    val w = dao.activeAppWorkout() ?: return@withLock
                    val now = now()
                    _recent.value = dao.appWorkoutHeartRateOnce(w.start)
                        .filter { !it.fromHistory && it.timestamp >= now - RECENT_S }.map { it.timestamp to it.bpm }
                    val stored = dao.appWorkoutHeartRateOnce(w.start)
                    sum = stored.sumOf { it.bpm.toLong() }
                    count = stored.size.toLong()
                    _stats.value = WorkoutRecorder.stats(stored)
                    val live = stored.filter { !it.fromHistory }.map { it.timestamp to it.bpm }
                    val maxHr = maxHeartRate()
                    _zoneSeconds.value = WorkoutRecorder.liveZoneSeconds(live, maxHr)
                    lastReading = live.lastOrNull()
                    _kcal.value = null
                    stepTotals = emptyList()
                    synchronized(allReadings) { allReadings.clear(); allReadings.addAll(live) }
                    _history.value = live
                    window = live.filter { it.first >= now - STOP_WINDOW_S }
                    // A kept route comes back; one that wasn't kept was only ever in memory.
                    _route.value = if (w.keepRoute) dao.routeOnce(w.start).map { it.point } else emptyList()
                    savedPoints = _route.value.size
                    // A restart mustn't ask at once about a stop it can't see the start of.
                    askSince = now
                    begin(w, maxHr)
                    val sets = dao.setsOnce(w.start)
                    val restUntil = sets.lastOrNull()?.takeIf { it.end != null && it.restEnd == null }?.let { it.end!! + restRule(w).seconds }
                    // Logging is the sport's setting at its start, which Ready saved then.
                    val log = runCatching { settings.sportSettings.first()[org.openstrap.app.data.Workouts.tileType(w.sportType)]?.log }.getOrNull() == true
                    val rounds = if (w.structure != null) dao.roundsOnce(w.start) else emptyList()
                    (_state.value as? WorkoutState.Recording)?.let {
                        _state.value = it.copy(sets = sets, restUntil = restUntil, exercise = sets.lastOrNull()?.exercise, logSets = log, rounds = rounds)
                    }
                }
            } finally {
                _restored.value = true
                // After the restore, so a workout left recording isn't offered as a new one.
                watchForEffort()
            }
        }
    }

    fun start(
        sportType: Int, askToEnd: Boolean = true, target: org.openstrap.app.data.WorkoutTarget? = null,
        useGps: Boolean = false, keepRoute: Boolean = false,
        rest: org.openstrap.app.data.Strength.Rest? = null, setsTarget: Int? = null,
        /** Auto-start: the workout began here, earlier, and [seed] holds its live readings so far. */
        from: Long? = null, seed: List<Pair<Long, Int>> = emptyList(), seedSteps: Int? = null,
        /** Strength: a log card after each set. Rounds or periods ([AppWorkout.structure]). */
        logSets: Boolean = false, structure: String? = null,
    ) {
        scope.launch {
            lock.withLock {
                if (_state.value is WorkoutState.Recording) return@withLock
                val w = AppWorkout(
                    start = from ?: now(), end = null, sportType = sportType, askToEnd = askToEnd, target = target?.code,
                    useGps = useGps, keepRoute = useGps && keepRoute,
                    rest = rest?.code, setsTarget = setsTarget, structure = structure,
                )
                _route.value = emptyList()
                savedPoints = 0
                firstSteps = null
                synchronized(allReadings) { allReadings.clear() }
                _history.value = emptyList()
                dao.upsertAppWorkout(w)
                _recent.value = emptyList()
                _stats.value = null
                sum = 0
                count = 0
                _zoneSeconds.value = IntArray(org.openstrap.app.data.Workouts.ZONES)
                lastReading = null
                _kcal.value = null
                // Auto-start: the strap's total where the effort began, so the steps since count.
                stepTotals = listOfNotNull(seedSteps?.let { w.start to it })
                window = emptyList()
                askSince = w.start
                minimized.value = false
                begin(w, maxHeartRate())
                if (logSets) (_state.value as? WorkoutState.Recording)?.let { _state.value = it.copy(logSets = true) }
                firstSteps = seedSteps
                for ((t, bpm) in seed) record(t, bpm)
            }
        }
    }

    /**
     * "Looks like a workout" ([AutoStart]): an effort under way that nobody started, while the question is open.
     * The strap buzzes once as it is asked; the notification and the Today and Activity tabs ask it.
     */
    private val _offer = MutableStateFlow<AutoStart.Offer?>(null)
    val offer: StateFlow<AutoStart.Offer?> = _offer
    /** Idle live readings and step totals of the last [WATCH_S], from probes and from streaming while effort lasts. */
    private val idleReadings = mutableListOf<Pair<Long, Int>>()
    private var idleSteps = listOf<Pair<Long, Int>>()
    /** Effort before this isn't offered: the end of the last workout. */
    private var offerNotBefore = 0L
    /** "Not now": quiet until the effort under way has ended. */
    private var declined = false

    /** "Record": the workout starts where the effort began, with the readings since. */
    fun recordOffer() {
        val o = _offer.value ?: return
        _offer.value = null
        val seed = synchronized(idleReadings) { idleReadings.filter { it.first >= o.start } }
        val steps = idleSteps.firstOrNull { it.first >= o.start }?.second
        (strap as? StrapManager)?.log("auto-start: recording ${o.sportType} from ${o.start}, ${seed.size} readings")
        start(o.sportType, from = o.start, seed = seed, seedSteps = steps)
    }

    fun declineOffer() {
        if (_offer.value == null) return
        _offer.value = null
        declined = true
    }

    /** Watches for effort whenever auto-start is on, the strap is connected and nothing records. */
    private fun watchForEffort() = scope.launch {
        combine(settings.autoStartWorkouts, _state, strap.connection) { on, st, c ->
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
    private suspend fun watchEffort() = coroutineScope {
        val maxHr = maxHeartRate()
        val resting = runCatching { dao.restingHrOnce(now() - RESTING_LOOKBACK_S, now()).lastOrNull()?.bpm }.getOrNull() ?: DEFAULT_RESTING_HR
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
                    if (!callRinging()) launch { runCatching { strap.buzz(OFFER_BUZZ) } }
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
        val recent = idleSteps.filter { it.first >= t - 240 }
        val first = recent.firstOrNull() ?: return null
        val last = recent.last()
        if (last.first - first.first < 60) return null
        return ((last.second - first.second) * 60 / (last.first - first.first)).toInt().coerceAtLeast(0)
    }

    fun pause() = pauseFrom(toStrap = true)

    /** [toStrap] false when the strap paused its session itself, so it isn't told again. */
    private fun pauseFrom(toStrap: Boolean) = update { w ->
        if (w.pausedAt != null) w else {
            flush()
            w.copy(pausedAt = now()).also { strapState(org.openstrap.protocol.services.SportSession.State.PAUSE, it, send = toStrap) }
        }
    }

    /** "Lap": marks the end of one lap and the start of the next; a buzz says it took. */
    fun lap() = update { w ->
        if (w.pausedAt != null) w else w.copy(laps = (w.lapMarks + now()).joinToString(",")).also {
            if (!callRinging()) scope.launch { runCatching { strap.buzz(org.openstrap.app.data.Cue.BackInTarget.pattern) } }
        }
    }

    /** A pause doesn't count as time in zone. */
    fun resume() = resumeFrom(toStrap = true)

    private fun resumeFrom(toStrap: Boolean) = update { w ->
        lastReading = null
        (w.pausedAt?.let { w.withPause(it, now()) } ?: w).also { if (it != w) strapState(org.openstrap.protocol.services.SportSession.State.RESUME, it, send = toStrap) }
    }

    /* ---------------------------------- The strap's own session (0x0019) ---------------------------------- */

    /*
     * TODO (deferred): the rest of Zepp's session. Still missing are 0x0019 `04` live SportInfo (Zepp's high heart
     * rate buzz), `06` status query and `20` strap-initiated start, plus phone GPS fixes to the strap over 0x0031
     * (codemap 05 §5, 03 §3.20). The strap's own pause and stop (`11`) and ABANDON are followed but untried on hardware.
     */

    /** Read at each start: whether a phone workout also runs a session on the strap ([SettingsStore.strapSessions]). */
    @Volatile private var strapSessions = true
    private var keepAlive: Job? = null

    /**
     * Tells the strap's sport module, when that's on; a failure is logged and the phone recording carries on. [send]
     * false when the strap made the change itself: nothing goes out, but a pause still gets its keep-alive.
     */
    private fun strapState(state: org.openstrap.protocol.services.SportSession.State, w: AppWorkout, send: Boolean = true) {
        if (!strapSessions) return
        val elapsed = WorkoutRecorder.activeSeconds(w, now())
        if (send) scope.launch {
            runCatching { strap.sportSession(state, w.sportType, w.start, elapsed) }
                .onFailure { (strap as? StrapManager)?.log("strap session $state not sent: ${it.message}") }
        }
        keepAlive?.cancel()
        // While paused, Zepp sends the module a keep-alive every 5 s.
        if (state == org.openstrap.protocol.services.SportSession.State.PAUSE) keepAlive = scope.launch {
            while (isActive) {
                delay(org.openstrap.protocol.services.SportSession.KEEP_ALIVE_MS)
                strap.sportSessionKeepAlive()
            }
        }
    }

    /**
     * The strap's pushes: a pause, resume or stop it made itself is followed ([WorkoutRecorder.strapAction]), and "heart
     * rate back to normal" asks the same question as [checkStopped].
     */
    private fun onStrapPush(p: org.openstrap.protocol.services.SportSession.Push) {
        val r = _state.value as? WorkoutState.Recording ?: return
        if (p is org.openstrap.protocol.services.SportSession.Push.StateChanged) {
            val action = WorkoutRecorder.strapAction(p.state, r.paused) ?: return
            (strap as? StrapManager)?.log("strap session: the strap sent ${p.state}, workout follows")
            when (action) {
                WorkoutRecorder.StrapAction.PAUSE -> pauseFrom(toStrap = false)
                WorkoutRecorder.StrapAction.RESUME -> resumeFrom(toStrap = false)
                WorkoutRecorder.StrapAction.END -> finish(null, AppWorkout.END_USER, toStrap = false)
            }
            return
        }
        if (p is org.openstrap.protocol.services.SportSession.Push.Status && p.status == org.openstrap.protocol.services.SportSession.STATUS_HR_NORMAL) {
            if (r.paused || !r.workout.askToEnd || r.endPrompt != null) return
            val at = WorkoutRecorder.lastActive(window, steps, restingHr, r.maxHeartRate)?.takeIf { it > r.workout.start } ?: now()
            _state.value = r.copy(endPrompt = at)
            if (!callRinging()) scope.launch { runCatching { strap.buzz(STOP_BUZZ) } }
        }
    }

    /** Saves the workout; ended while paused, it ends where the pause began. */
    fun end() = finish(null, AppWorkout.END_USER)

    /** "End at 18:50": saves the workout ending where the wearer stopped; what came after stays recorded, for undo. */
    fun endAtPrompt() {
        val r = _state.value as? WorkoutState.Recording ?: return
        finish(r.endPrompt ?: return, if (r.inCar) AppWorkout.END_CAR else AppWorkout.END_AUTO)
    }

    /** "Keep going": closes the question; it comes back only after a new active spell. */
    fun keepGoing() {
        askSince = now()
        val r = _state.value as? WorkoutState.Recording ?: return
        if (r.endPrompt != null) _state.value = r.copy(endPrompt = null, inCar = false)
    }

    /** [toStrap] false when the strap ended its session itself. */
    private fun finish(at: Long?, reason: Int, toStrap: Boolean = true) {
        scope.launch {
            lock.withLock {
                val r = _state.value as? WorkoutState.Recording ?: return@withLock
                flush()
                saveDistance()
                // Written here: an update() queued behind this lock would find the workout already ended.
                val w = r.workout.let { w -> distanceNow(w)?.let { (m, steps) -> w.copy(distanceMeters = m, distanceFromSteps = steps) } ?: w }
                val recorded = w.pausedAt ?: now()
                // The heart rate stays up for a while after a workout; that's not a new one to offer.
                offerNotBefore = now()
                val end = at?.coerceIn(w.start, recorded) ?: recorded
                val ended = w.copy(end = end, pausedAt = null, endReason = reason, recordedEnd = recorded.takeIf { it > end })
                val kept = WorkoutRecorder.activeSeconds(ended, end) >= MIN_SAVED_S
                strapState(WorkoutRecorder.closingState(kept), w, send = toStrap)
                keepAlive?.cancel()
                // A set or rest under way closes with the workout.
                r.sets.lastOrNull()?.let { last ->
                    when {
                        last.end == null -> last.copy(end = end)
                        last.restEnd == null -> last.copy(restEnd = end)
                        else -> null
                    }
                }?.let { dao.upsertSets(listOf(it)) }
                // So does a round's work or rest.
                r.rounds.lastOrNull()?.let { last ->
                    when {
                        last.workEnd == null -> last.copy(workEnd = end, restEnd = end)
                        last.restEnd == null -> last.copy(restEnd = end)
                        else -> null
                    }
                }?.let { dao.upsertRounds(listOf(it)) }
                stop(keepLive = true)
                // Under a minute is a mis-tap or a test, not a workout: nothing is kept, and the app says so.
                if (!kept) {
                    strap.setWorkoutActive(false)
                    dao.deleteAppWorkout(w.start)
                    _state.value = WorkoutState.Idle
                    // A toast, so it shows whether it ended in the app or from the notification.
                    context.mainExecutor.execute {
                        android.widget.Toast.makeText(context, "Under a minute, so not saved", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@withLock
                }
                // The route is still in memory here, kept or not, so a run gets its VO2max and its fastest stretches
                // (its bests, workouts spec 3.2) either way.
                val stretches = org.openstrap.app.data.Rewards.Stretch.format(org.openstrap.app.data.Rewards.stretches(_route.value, ended.pauseRanges))
                dao.upsertAppWorkout(withEffect(ended.copy(stretches = stretches), dao.appWorkoutHeartRateOnce(w.start), _route.value))
                _state.value = WorkoutState.Ended(w.start)
                watchCalmDown(w.start, recorded)
                // Its days' energy rows take its kcal now, after the summary is up, so Health Connect gets them with it.
                refreshDays(ended.from, end)
                onSaved(w.start)
            }
            // The strap's history fills any minutes the phone missed; it batches, so ask now.
            strap.syncNow()
        }
    }

    /**
     * Deletes a phone workout, and closes its summary if it is the one showing. The strap's own records of it
     * ([Workouts.strapTwins]) go too, as they show as this one: left, they would come back as a workout of their own.
     */
    fun discard(start: Long) {
        scope.launch {
            lock.withLock {
                val w = dao.appWorkoutOnce(start)
                if (w?.end != null) {
                    val t = now()
                    val range = org.openstrap.app.data.Workouts.phoneRange(w, t)
                    org.openstrap.app.data.Workouts.strapTwins(dao.workoutsOnce(range.first, range.last), range)
                        .forEach { dao.deleteStrapWorkout(it.start, t) }
                }
                dao.deleteAppWorkout(start)
                if (w?.end != null) refreshDays(w.start, w.recordedEnd ?: w.end)
                if ((_state.value as? WorkoutState.Ended)?.start == start) {
                    _state.value = WorkoutState.Idle
                    endCalmDown()
                }
            }
        }
    }

    /**
     * Keeps the strap's live heart rate on for [CALM_S] after the workout [start] stopped recording at [stopped], so
     * the summary can draw the drop as it happens. Done, a delete or a new workout ends it early.
     */
    private fun watchCalmDown(start: Long, stopped: Long) {
        calmJob?.cancel()
        _calmDown.value = CalmDown(start, stopped, stopped + CALM_S, emptyList())
        calmJob = scope.launch {
            try {
                withTimeoutOrNull(CALM_S * 1000) {
                    strap.heartRateReadings.collect { bpm ->
                        val t = now()
                        val c = _calmDown.value?.takeIf { it.start == start } ?: return@collect
                        // The strap sends each reading on two paths; one a second is kept.
                        if (!WorkoutRecorder.isValid(bpm) || c.readings.lastOrNull()?.first == t) return@collect
                        _calmDown.value = c.copy(readings = c.readings + (t to bpm))
                    }
                }
            } finally {
                // A workout started meanwhile wants the stream on.
                if (_state.value !is WorkoutState.Recording) strap.setWorkoutActive(false)
            }
        }
    }

    private fun endCalmDown() {
        calmJob?.cancel()
        calmJob = null
    }

    /**
     * Debug: shows the summary for the stored workout [start] as if it had just ended, to check the screen without
     * ending one. Only while no workout records or shows; nothing is written.
     */
    fun showSummary(start: Long) {
        if (_state.value is WorkoutState.Idle) _state.value = WorkoutState.Ended(start, preview = true)
    }

    /**
     * Saves a stretch nobody started as a workout ("Save as workout", or added after the fact), built from the
     * strap's history. A stretch over a workout already saved is refused.
     */
    fun saveFromHistory(from: Long, to: Long, sportType: Int) {
        scope.launch {
            lock.withLock {
                if (to - from < 60 || dao.appWorkoutsOnce(from, to).isNotEmpty()) return@withLock
                val w = AppWorkout(start = from, end = to, sportType = sportType, source = AppWorkout.SOURCE_SUGGESTED, endReason = AppWorkout.END_USER)
                dao.upsertAppWorkout(w)
                WorkoutGapFill(dao) { settings.profile.first() }.fill(w)
                refreshDays(from, to)
                onSaved(w.start)
            }
        }
    }

    /** "Not a workout": remembered for that time range, so the same stretch isn't offered again. */
    fun dismissSuggestion(from: Long, to: Long) {
        scope.launch { dao.insertDismissedWorkouts(listOf(org.openstrap.app.data.db.DismissedWorkout(from, to))) }
    }

    /* ------------------------------------------- Strength -------------------------------------------- */

    private fun restRule(w: AppWorkout) = org.openstrap.app.data.Strength.Rest.parse(w.rest) ?: org.openstrap.app.data.Strength.Rest.DEFAULT

    /** "Start set N": ends any rest under way and begins the next set. */
    fun startSet() = editSets { r ->
        if (r.inSet) return@editSets r
        val now = now()
        val sets = r.sets.toMutableList()
        sets.lastOrNull()?.takeIf { it.restEnd == null && it.end != null }?.let { sets[sets.lastIndex] = it.copy(restEnd = now) }
        sets += org.openstrap.app.data.db.AppWorkoutSet(r.workout.start, sets.size, now, exercise = r.exercise)
        r.copy(sets = sets, restUntil = null)
    }

    /** The exercise for the sets from now on ("Next exercise"), and the set under way if there is one; null numbers them. */
    fun setExercise(name: String?) = editSets { r ->
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        val sets = if (r.inSet) r.sets.dropLast(1) + r.sets.last().copy(exercise = clean) else r.sets
        r.copy(sets = sets, exercise = clean)
    }

    /**
     * The log card's Save: set [index]'s [reps] and [kg], of [exercise], kept for the next card of that exercise too.
     * Logging never touches the set's times.
     */
    fun logSet(index: Int, exercise: String?, reps: Int, kg: Float) {
        val name = exercise?.trim()?.takeIf { it.isNotEmpty() }
        editSets { r ->
            val i = r.sets.indexOfFirst { it.index == index }
            if (i < 0) return@editSets r
            r.copy(sets = r.sets.toMutableList().also { it[i] = it[i].copy(exercise = name, reps = reps, weightKg = kg) })
        }
        if (name != null) scope.launch { runCatching { dao.upsertExercise(org.openstrap.app.data.db.ExerciseRow(name, reps, kg, now())) } }
    }

    /** The exercises logged before, last used first, for the exercise list and the log card. */
    fun exercises(): kotlinx.coroutines.flow.Flow<List<org.openstrap.app.data.db.ExerciseRow>> = dao.exercises()

    /** "Set done": the rest begins, ending at its time or earlier on heart rate. */
    fun setDone() = editSets { r ->
        if (!r.inSet) return@editSets r
        val now = now()
        r.copy(sets = r.sets.dropLast(1) + r.sets.last().copy(end = now), restUntil = now + restRule(r.workout).seconds)
    }

    /** "+30 s". */
    fun extendRest() = editSets { r -> r.restUntil?.let { r.copy(restUntil = it + org.openstrap.app.data.Strength.EXTEND_S) } ?: r }

    private fun editSets(change: (WorkoutState.Recording) -> WorkoutState.Recording) {
        scope.launch {
            lock.withLock {
                val r = _state.value as? WorkoutState.Recording ?: return@withLock
                val changed = change(r)
                if (changed == r) return@withLock
                dao.upsertSets(changed.sets)
                _state.value = changed
            }
        }
    }

    /** Once a second while resting: rest is over at its time, or once heart rate holds below the chosen level. */
    private fun checkRest() {
        val r = _state.value as? WorkoutState.Recording ?: return
        val until = r.restUntil ?: return
        val last = r.sets.lastOrNull()?.end ?: return
        if (!org.openstrap.app.data.Strength.restOver(restRule(r.workout), last, until, window, now())) return
        editSets { it.copy(sets = it.sets.dropLast(1) + it.sets.last().copy(restEnd = now()), restUntil = null) }
        cue(org.openstrap.app.data.Cue.RestOver)
    }

    /* ------------------------------------------- Rounds -------------------------------------------- */

    /** Skip work, Skip rest, Done (every minute), or start the rounds after the warm up: the phase under way ends now. */
    fun skipPhase() = stepRounds(skip = true)

    /** Interval workouts saved by name, newest first. */
    fun savedWorkouts(): kotlinx.coroutines.flow.Flow<List<org.openstrap.app.data.db.SavedWorkout>> = dao.savedWorkouts()

    /** Saves [structure] for [sport] as [name] ("Tuesday intervals"); only the newest [MAX_SAVED] are kept. */
    fun saveWorkout(name: String, sport: Int, structure: String) {
        scope.launch {
            runCatching {
                dao.upsertSavedWorkout(org.openstrap.app.data.db.SavedWorkout(name = name, sport = sport, structure = structure, createdAt = now()))
                dao.savedWorkoutsOnce().drop(MAX_SAVED).forEach { dao.deleteSavedWorkout(it.id) }
            }.onFailure { (strap as? StrapManager)?.log("workout not saved: ${it.message}") }
        }
    }

    /** "+10 s" on a rest. */
    fun addRest() {
        val r = _state.value as? WorkoutState.Recording ?: return
        if (r.phase(now()) is org.openstrap.app.data.Phase.Rest) _state.value = r.copy(restExtra = r.restExtra + REST_ADD_S)
    }

    /**
     * Moves a structured workout's rounds on ([org.openstrap.app.data.Phases]): once a second by the clock (and by the
     * distance, for run reps), or now on a tap ([skip]). A switch buzzes the strap ([org.openstrap.app.data.WorkoutBuzz]),
     * and the phone beeps the last three seconds of a timed phase when its sound is on. Paused, the clock doesn't move.
     */
    private fun stepRounds(skip: Boolean) {
        scope.launch {
            lock.withLock {
                val r = _state.value as? WorkoutState.Recording ?: return@withLock
                val s = r.structure ?: return@withLock
                if (r.paused) return@withLock
                val t = now()
                val w = r.workout
                val active = { at: Long -> WorkoutRecorder.activeSeconds(w, minOf(at, t)) }
                val readings = synchronized(allReadings) { allReadings.toList() }
                val peak = { a: Long, b: Long -> org.openstrap.app.data.Strength.peak(readings, a, b) }
                val meters = { from: Long -> _route.value.filter { it.t >= from }.takeIf { it.size > 1 }?.let { Route.distance(it, w.pauseRanges).toFloat() } }
                val step = if (skip) org.openstrap.app.data.Phases.skip(s, r.rounds, w.start, t, active, peak)
                    else org.openstrap.app.data.Phases.tick(s, r.rounds, w.start, t, active, meters, peak, r.restExtra)
                if (step.rounds != r.rounds) {
                    val changed = step.rounds.filter { it !in r.rounds }
                    runCatching { dao.upsertRounds(changed) }.onFailure { (strap as? StrapManager)?.log("rounds not saved: ${it.message}") }
                    _state.value = r.copy(rounds = step.rounds, restExtra = if (step.switch != null) 0 else r.restExtra)
                }
                step.switch?.let { buzz(org.openstrap.app.data.WorkoutBuzz.of(it, game = s is org.openstrap.app.data.Structure.Periods), it.name) }
                if (step.switch == null && !skip) {
                    val ends = when (val p = (_state.value as WorkoutState.Recording).phase(t)) {
                        is org.openstrap.app.data.Phase.Work -> p.endsAt
                        is org.openstrap.app.data.Phase.Rest -> p.endsAt
                        else -> null
                    }
                    val left = ends?.let { it - active(t) }
                    if (left != null && left in 1..COUNTDOWN_S) beep()
                }
            }
        }
    }

    /** A named buzz ([org.openstrap.app.data.WorkoutBuzz]), as quiet as a coaching cue: never during a call or Do Not Disturb. */
    private fun buzz(pattern: List<Int>, what: String) {
        if (callRinging()) return
        val filter = context.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter
        if (filter == android.app.NotificationManager.INTERRUPTION_FILTER_NONE || filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS) return
        scope.launch { runCatching { strap.buzz(pattern) }.onFailure { (strap as? StrapManager)?.log("buzz $what not played: ${it.message}") } }
        (strap as? StrapManager)?.log("buzz: $what")
    }

    /** One short beep from the phone, only while its ringer is on (workouts spec 2.5, the countdown). */
    private fun beep() {
        val audio = context.getSystemService(android.media.AudioManager::class.java) ?: return
        if (audio.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            scope.launch { delay(BEEP_MS + 100L); tone.release() }
        }
    }

    /** Puts back what an early end or a trim left out: the workout as recorded. */
    fun undoTrim(start: Long) = editSaved(start) { it.copy(end = it.recordedEnd ?: it.end, recordedEnd = null, shownStart = null) }

    /** Shows a saved workout from [from] to [to], both inside what was recorded; nothing is deleted. */
    fun trim(start: Long, from: Long, to: Long) = editSaved(start) { w ->
        val recorded = w.recordedEnd ?: w.end ?: return@editSaved w
        val end = to.coerceIn(w.start + 60, recorded)
        // A trim by hand replaces whatever the app proposed, so the summary says "Trimmed", not "when you'd stopped".
        w.copy(
            shownStart = from.coerceIn(w.start, end - 60).takeIf { it > w.start }, end = end, recordedEnd = recorded.takeIf { it > end },
            endReason = AppWorkout.END_USER,
        )
    }

    private fun editSaved(start: Long, change: (AppWorkout) -> AppWorkout) {
        scope.launch {
            lock.withLock {
                val w = dao.appWorkoutOnce(start)?.takeIf { it.end != null } ?: return@withLock
                val changed = change(w)
                if (changed == w) return@withLock
                dao.upsertAppWorkout(withEffect(changed, dao.appWorkoutHeartRateOnce(start)))
                // Its kcal moved, so its days' energy rows follow: over all it was recorded, which holds both ranges.
                refreshDays(w.start, w.recordedEnd ?: w.end ?: start)
                onSaved(start)
            }
        }
    }

    /** Writes the daily energy rows from [from] to [to] again ([org.openstrap.app.data.DailyEnergyHistory.updateDays]). */
    private suspend fun refreshDays(from: Long, to: Long) {
        runCatching { org.openstrap.app.data.DailyEnergyHistory(dao).updateDays(from, to, settings.profile.first()) }
            .onFailure { (strap as? StrapManager)?.log("daily energy not refreshed: ${it.message}") }
    }

    /**
     * [w] with its average and peak, then its effect ([org.openstrap.app.data.WorkoutEffectEngine]); [route] is the one in
     * memory at the end. A failure there never costs the workout: it is saved with its stats alone.
     */
    private suspend fun withEffect(w: AppWorkout, hr: List<AppWorkoutHeartRate>, route: List<Route.Point>? = null): AppWorkout {
        val stats = WorkoutRecorder.withStats(w, hr)
        return runCatching { org.openstrap.app.data.WorkoutEffectEngine(dao).withEffect(stats, hr, settings.profile.first(), route) }.getOrDefault(stats)
    }

    fun dismissSummary() {
        if (_state.value is WorkoutState.Ended) _state.value = WorkoutState.Idle
        endCalmDown()
    }

    private fun update(change: suspend (AppWorkout) -> AppWorkout) {
        scope.launch {
            lock.withLock {
                val r = _state.value as? WorkoutState.Recording ?: return@withLock
                val w = change(r.workout)
                if (w == r.workout) return@withLock
                dao.upsertAppWorkout(w)
                _state.value = r.copy(workout = w)
            }
        }
    }

    private suspend fun maxHeartRate() =
        runCatching { RecoveryEngine(dao).maxHeartRate(settings.profile.first()).roundToInt() }.getOrDefault(DEFAULT_MAX_HR)

    private var coach = org.openstrap.app.data.Coach(null)

    /** This workout's GPS fixes, oldest first: for distance, pace and the map; saved only when the route is kept. */
    private val _route = MutableStateFlow<List<Route.Point>>(emptyList())
    val route: StateFlow<List<Route.Point>> = _route
    private var savedPoints = 0

    /** Every live reading of this workout (time, bpm), refreshed each flush: the route's colours and the splits. */
    private val _history = MutableStateFlow<List<Pair<Long, Int>>>(emptyList())
    val history: StateFlow<List<Pair<Long, Int>>> = _history
    private val allReadings = mutableListOf<Pair<Long, Int>>()
    private val location = WorkoutLocation(context) { p ->
        val r = _state.value as? WorkoutState.Recording ?: return@WorkoutLocation
        if (!r.paused) _route.value = _route.value + p
    }
    /** The strap's step total when the workout began, for distance without GPS. */
    private var firstSteps: Int? = null
    private var heightCm: Int? = null

    /** Writes new route points (when kept) and the distance so far: GPS when it has fixes, else steps (≈). */
    private suspend fun saveDistance() {
        val r = _state.value as? WorkoutState.Recording ?: return
        val w = r.workout
        val points = _route.value
        if (w.keepRoute && points.size > savedPoints) {
            val fresh = points.subList(savedPoints, points.size)
            runCatching { dao.insertRoutePoints(fresh.map { AppWorkoutRoutePoint.of(w.start, it) }) }
                .onSuccess { savedPoints = points.size }
        }
        val (meters, fromSteps) = distanceNow(w) ?: return
        // A distance goal buzzes at halfway and once reached; when is kept for the summary.
        if (!r.paused) coach.distance(now(), meters)?.let { c ->
            cue(c)
            if (c == org.openstrap.app.data.Cue.GoalReached) { val t = now(); update { it.copy(targetReachedAt = t) } }
        }
        if (kotlin.math.abs((w.distanceMeters ?: 0f) - meters) < 5f) return
        update { it.copy(distanceMeters = meters, distanceFromSteps = fromSteps) }
    }

    /**
     * Metres so far and whether they came from steps: GPS once it has settled (even at 0 m, standing still), else the
     * strap's steps.
     */
    private fun distanceNow(w: AppWorkout): Pair<Float, Boolean>? =
        if (Route.clean(_route.value).isNotEmpty()) Route.distance(_route.value, w.pauseRanges).toFloat() to false
        else stepsDistance(w)?.let { it to true }

    /**
     * The strap's steps since the start times a stride for their cadence and your height; null without either, and
     * for sports not done on foot ([org.openstrap.app.data.Workouts.ON_FOOT]).
     */
    private fun stepsDistance(w: AppWorkout): Float? {
        if (w.sportType !in org.openstrap.app.data.Workouts.ON_FOOT) return null
        val first = firstSteps ?: return null
        val last = steps.lastOrNull()?.second ?: return null
        val height = heightCm ?: return null
        val taken = last - first
        if (taken <= 0) return null
        val minutes = (WorkoutRecorder.activeSeconds(w, now()) / 60f).coerceAtLeast(1f)
        return taken * org.openstrap.app.data.Activity.stepLength((taken / minutes).toInt(), height)
    }

    private suspend fun begin(w: AppWorkout, maxHr: Int) {
        // Waited for, so the calm down's own switch-off can't land after this workout turns the stream on.
        calmJob?.cancelAndJoin()
        calmJob = null
        coach = org.openstrap.app.data.Coach(org.openstrap.app.data.WorkoutTarget.parse(w.target), w.targetReachedAt)
        strapSessions = runCatching { settings.strapSessions.first() }.getOrDefault(true)
        heightCm = runCatching { settings.profile.first().heightCm }.getOrNull()
        if (w.useGps) location.start()
        _state.value = WorkoutState.Recording(w, maxHr)
        strap.setWorkoutActive(true)
        runCatching { StrapService.start(context) }
        strap.autoConnect()
        // The CPU must keep up with the strap's readings and the flushes while the screen is off.
        wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenStrap:workout").apply { acquire(MAX_WAKE_MS) }
        watchForCar()
        restingHr = runCatching { dao.restingHrOnce(now() - RESTING_LOOKBACK_S, now()).lastOrNull()?.bpm }.getOrNull() ?: DEFAULT_RESTING_HR
        recordJob?.cancel()
        recordJob = scope.launch {
            launch { strap.heartRateReadings.collect(::onReading) }
            if (strapSessions) {
                launch { strap.sportSessionPushes.collect(::onStrapPush) }
                // On every connect, START again with the original start (Zepp's resume after a disconnect), then pause if paused.
                launch {
                    strap.connection.collect { c ->
                        if (c !is ConnectionState.Connected) return@collect
                        val now = (_state.value as? WorkoutState.Recording)?.workout ?: return@collect
                        strapState(org.openstrap.protocol.services.SportSession.State.START, now)
                        if (now.pausedAt != null) strapState(org.openstrap.protocol.services.SportSession.State.PAUSE, now)
                    }
                }
            }
            // The strap's running total; only its changes matter, so its firmware offset doesn't.
            launch {
                strap.todaySteps.collect { s ->
                    s ?: return@collect
                    val t = now()
                    steps = (steps + (t to s.steps)).dropWhile { it.first < t - STOP_WINDOW_S }
                    stepTotals = stepTotals + (t to s.steps)
                    if (firstSteps == null) firstSteps = s.steps
                }
            }
            launch { countKcal(w, maxHr) }
            // Strength rest is timed to the second, not to the flush.
            if (w.sportType in org.openstrap.app.data.Strength.SPORTS) launch {
                while (isActive) {
                    delay(1_000)
                    checkRest()
                }
            }
            // So are a structured workout's rounds, screen on or off.
            if (org.openstrap.app.data.Structure.parse(w.structure) != null) launch {
                while (isActive) {
                    stepRounds(skip = false)
                    delay(1_000 - System.currentTimeMillis() % 1_000)
                }
            }
            var tick = 0
            while (isActive) {
                delay(FLUSH_MS)
                flush()
                saveDistance()
                _history.value = synchronized(allReadings) { allReadings.toList() }
                if (++tick % STOP_CHECK_TICKS == 0) checkStopped()
            }
        }
    }

    /**
     * The live kcal, every [KCAL_TICK_MS] while [w] records. Resting HR and VO2max are read once, by the rules the saved
     * workout's kcal uses ([org.openstrap.app.data.WorkoutEffectEngine]), so the figure at the end is about the one
     * saved. Minutes a history fill wrote and the strap's own step minutes are read again every [KCAL_READ_TICKS].
     */
    private suspend fun countKcal(w: AppWorkout, maxHr: Int) {
        val profile = runCatching { settings.profile.first() }.getOrNull() ?: return
        if (org.openstrap.app.data.EnergyExpenditure.bmrPerMinute(profile, profile.age()) == null) return
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochSecond(w.from).atZone(zone).toLocalDate()
        val resting = runCatching { org.openstrap.app.data.WorkoutEffectEngine(dao).restingHr(w.from, zone) }.getOrDefault(DEFAULT_RESTING_HR.toFloat())
        val vo2 = runCatching { RecoveryEngine(dao).vo2max(profile, date, zone) }.getOrNull()
        var history = emptyList<AppWorkoutHeartRate>()
        var synced = emptyMap<Long, Int>()
        var tick = 0
        while (true) {
            val r = _state.value as? WorkoutState.Recording ?: return
            if (r.workout.start != w.start) return
            val t = now()
            if (tick++ % KCAL_READ_TICKS == 0) {
                history = runCatching { dao.appWorkoutHeartRateOnce(w.start).filter { it.fromHistory } }.getOrDefault(history)
                synced = runCatching {
                    dao.activityOnce(r.workout.from - 60, t + 60).filter { it.steps in 0..250 }.associate { it.timestamp / 60 * 60 to it.steps }
                }.getOrDefault(synced)
            }
            val live = synchronized(allReadings) { allReadings.toList() }
            _kcal.value = runCatching {
                WorkoutRecorder.liveKcalTick(
                    r.workout, live, history, stepTotals, synced, resting, maxHr.toFloat(), profile, profile.age(date), vo2, t, _kcal.value,
                )
            }.getOrDefault(_kcal.value)
            // A kcal target buzzes once, the first time the figure reaches it; when is kept for the summary.
            val active = _kcal.value?.active
            if (active != null && !r.paused) coach.kcal(t, active)?.let { c ->
                cue(c)
                update { it.copy(targetReachedAt = t) }
            }
            delay(KCAL_TICK_MS)
        }
    }

    /** Asks once the wearer has been calm and still for a while ([WorkoutRecorder.stoppedSince]); the strap buzzes. */
    private fun checkStopped() {
        val r = _state.value as? WorkoutState.Recording ?: return
        if (r.paused || !r.workout.askToEnd || r.endPrompt != null) return
        val at = WorkoutRecorder.stoppedSince(window, steps, restingHr, r.maxHeartRate, maxOf(askSince, r.workout.start), now()) ?: return
        _state.value = r.copy(endPrompt = at)
        if (!callRinging()) scope.launch { runCatching { strap.buzz(STOP_BUZZ) } }
    }

    /**
     * Car detection without location or Google services: a car stereo or hands-free kit connecting over Bluetooth
     * means a car. The question comes at once, ending at the last active moment.
     */
    private val carReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) {
            val device = androidx.core.content.IntentCompat.getParcelableExtra(intent, android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
            val cls = runCatching { device?.bluetoothClass?.deviceClass }.getOrNull() ?: return
            if (cls != android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO && cls != android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE) return
            val r = _state.value as? WorkoutState.Recording ?: return
            if (r.paused || !r.workout.askToEnd || r.endPrompt != null) return
            val at = WorkoutRecorder.lastActive(window, steps, restingHr, r.maxHeartRate)?.takeIf { it > r.workout.start } ?: now()
            _state.value = r.copy(endPrompt = at, inCar = true)
            if (!callRinging()) scope.launch { runCatching { strap.buzz(STOP_BUZZ) } }
        }
    }
    private var carRegistered = false

    private fun watchForCar() {
        if (carRegistered) return
        carRegistered = true
        androidx.core.content.ContextCompat.registerReceiver(
            context, carReceiver, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /**
     * Plays a coaching cue on the strap, never while a call rings or Do Not Disturb silences everything. A cue that
     * arrives late (out of range, ~5 s) is never retried: the notification still shows where you are.
     */
    private fun cue(c: org.openstrap.app.data.Cue) {
        if (callRinging()) return
        // Mind and body is a quiet strap: no halfway and no zone cues, and one soft buzz when its length is over.
        val r = _state.value as? WorkoutState.Recording
        if (r != null && org.openstrap.app.data.Workouts.family(r.workout.sportType) == org.openstrap.app.data.Workouts.Family.MindBody) {
            if (c == org.openstrap.app.data.Cue.GoalReached) buzz(org.openstrap.app.data.WorkoutBuzz.SOFT, "length over")
            return
        }
        val filter = context.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter
        if (filter == android.app.NotificationManager.INTERRUPTION_FILTER_NONE || filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS) return
        scope.launch { runCatching { strap.buzz(c.pattern) }.onFailure { (strap as? StrapManager)?.log("coach $c not played: ${it.message}") } }
        (strap as? StrapManager)?.log("coach: $c")
        _lastCue.value = c
    }

    /** The last coaching cue, for the live screen to show what the buzz meant. */
    private val _lastCue = MutableStateFlow<org.openstrap.app.data.Cue?>(null)
    val lastCue: StateFlow<org.openstrap.app.data.Cue?> = _lastCue

    /** [keepLive]: the live heart rate stays on for the calm down after the end ([watchCalmDown]). */
    private fun stop(keepLive: Boolean = false) {
        _lastCue.value = null
        location.stop()
        if (carRegistered) runCatching { context.unregisterReceiver(carReceiver) }
        carRegistered = false
        recordJob?.cancel()
        recordJob = null
        if (!keepLive) strap.setWorkoutActive(false)
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        _recent.value = emptyList()
        minimized.value = false
    }

    /** One reading a second: the strap sends each on two paths. Nothing is kept while paused. */
    private fun onReading(bpm: Int) = record(now(), bpm)

    private fun record(t: Long, bpm: Int) {
        val r = _state.value as? WorkoutState.Recording ?: return
        if (r.paused || !WorkoutRecorder.isValid(bpm)) return
        synchronized(buffer) {
            if (t == lastSecond) return
            lastSecond = t
            buffer += AppWorkoutHeartRate(r.workout.start, t, bpm)
        }
        synchronized(allReadings) { allReadings += t to bpm }
        _recent.value = (_recent.value + (t to bpm)).dropWhile { it.first < t - RECENT_S }
        window = (window + (t to bpm)).dropWhile { it.first < t - STOP_WINDOW_S }
        sum += bpm
        count++
        lastReading?.let { (pt, pbpm) ->
            val z = WorkoutRecorder.liveZone(pbpm, r.maxHeartRate)!!
            _zoneSeconds.value = _zoneSeconds.value.copyOf().also { it[z] += (t - pt).coerceIn(0, WorkoutRecorder.MAX_STEP_S).toInt() }
        }
        lastReading = t to bpm
        coach.feed(t, WorkoutRecorder.liveZone(bpm, r.maxHeartRate), WorkoutRecorder.activeSeconds(r.workout, t))?.let(::cue)
        _stats.value = WorkoutRecorder.Stats(Math.round(sum.toDouble() / count).toInt(), maxOf(_stats.value?.max ?: 0, bpm), minOf(_stats.value?.min ?: bpm, bpm))
    }

    private suspend fun flush() {
        val batch = synchronized(buffer) { buffer.toList().also { buffer.clear() } }
        if (batch.isEmpty()) return
        runCatching { dao.insertAppWorkoutHeartRate(batch) }.onFailure {
            // Kept for the next flush rather than lost.
            synchronized(buffer) { buffer.addAll(0, batch) }
        }
    }

    private fun now() = System.currentTimeMillis() / 1000

    internal companion object {
        const val FLUSH_MS = 10_000L
        /** The live kcal is worked out again this often. */
        const val KCAL_TICK_MS = 5_000L
        /** History minutes and the strap's step minutes are read again every sixth kcal tick, 30 s. */
        const val KCAL_READ_TICKS = 6
        const val RECENT_S = 600L
        /** Workouts with less moving time than this aren't kept. */
        const val MIN_SAVED_S = 60L
        /** How long the live heart rate stays on after a workout, for its heart rate drop: the first two minutes. */
        const val CALM_S = 120L
        /** Long enough to hold the 8 calm minutes and the active moment before them. */
        const val STOP_WINDOW_S = 20 * 60L
        /** Checked every third flush, 30 s. */
        const val STOP_CHECK_TICKS = 3
        const val RESTING_LOOKBACK_S = 14 * 86_400L
        const val DEFAULT_RESTING_HR = 60
        /** Two long pulses: unlike a notification's short ones. */
        val STOP_BUZZ = listOf(500, 250, 500)
        /** Only if the max-HR model can't run; 190 is Tanaka's estimate at 25. */
        const val DEFAULT_MAX_HR = 190
        /** Long enough for an effort to be offered from its start. */
        const val WATCH_S = 2 * 3600L
        const val WATCH_TICK_MS = 5_000L
        /** Three short pulses: unlike the stop question's two long ones. */
        val OFFER_BUZZ = listOf(150, 150, 150, 150, 150)
        /** Saved workouts kept (workouts spec 2.5). */
        const val MAX_SAVED = 10
        /** "+10 s" on an interval's rest. */
        const val REST_ADD_S = 10L
        /** The phone beeps the last this many seconds of a timed work or rest. */
        const val COUNTDOWN_S = 3L
        const val BEEP_MS = 120
        const val BEEP_VOLUME = 70
        /** A safety net should end() never come; the strap's battery is long gone by then. */
        const val MAX_WAKE_MS = 12 * 3600_000L
    }
}
