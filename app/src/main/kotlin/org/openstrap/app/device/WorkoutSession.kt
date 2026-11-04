package org.openstrap.app.device

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.openstrap.app.data.AutoStart
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.RecoveryEngine
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.AppWorkoutRoutePoint
import org.openstrap.app.data.Route
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.restingHrIn
import org.openstrap.app.data.settings.SettingsStore

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
        /**
         * The last save to the database failed. It records on in memory and the next change saves again, but a
         * process death now would lose what is unsaved, so the notification says so.
         */
        val saveFailed: Boolean = false,
    ) : WorkoutState {
        /** Parsed once per state, not on every read: the rounds' clock reads it each second. */
        val structure: org.openstrap.app.data.Structure? by lazy { org.openstrap.app.data.Structure.parse(workout.structure) }

        /** The phase now, by the workout's active time. */
        fun phase(now: Long): org.openstrap.app.data.Phase? = structure?.let { s ->
            org.openstrap.app.data.Phases.phase(s, rounds, { t -> WorkoutRecorder.activeSeconds(workout, minOf(t, now)) }, restExtra)
        }
        val paused: Boolean get() = workout.pausedAt != null
        val strength: Boolean get() = workout.sportType in org.openstrap.app.data.SportTypes.STRENGTH
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
// Built once, in AppContainer, and every parameter is something a test swaps for its own.
@Suppress("LongParameterList")
class WorkoutSession(
    private val context: Context,
    private val dao: HealthDao,
    private val settings: SettingsStore,
    private val strap: StrapController,
    /** Max heart rate as every zone reads it, read afresh as each workout starts. */
    private val maxHeartRates: org.openstrap.app.data.MaxHeartRates,
    /** A coaching buzz never plays while a call rings: a motor pattern cuts off the call's buzz (hardware test). */
    private val callRinging: () -> Boolean = { false },
    /** A finished workout was saved or changed, by its start: auto sync writes it to Health Connect. Returns at once. */
    private val onSaved: (start: Long) -> Unit = {},
    /** Epoch seconds now. Tests pass a clock they move by hand. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Where the workout's long-lived work runs: the app's scope, `AppContainer.appScope`. */
    private val scope: CoroutineScope = fallbackScope(),
    /** How long a workout left recording waits for the strap's history ([LeftRecording]). Tests shorten it. */
    private val leftWaitMs: Long = LEFT_WAIT_MS,
) {
    private val lock = LOCK

    /** Discard, trim and save from history: changes to workouts already saved, under the same [lock]. */
    private val editor = SavedWorkoutEditor(
        dao, settings, lock, onSaved, onDiscarded = ::closeDiscarded, log = { log(it) }, clock = clock, scope = scope,
        track = { start -> if (start == trackStart) track() else null },
    )

    /**
     * Your heart rate zones as stored, for the recording workout and its notification, which read a zone with every
     * reading. It only ever follows what's stored, from the session's start, so it can't run ahead of the disk.
     */
    val heartZones: StateFlow<HeartZones> = settings.heartZones.stateIn(scope, SharingStarted.Eagerly, HeartZones.STANDARD)

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

    /**
     * Seconds in each live zone so far ([WorkoutRecorder.liveZone]), for "6:40 in zone". A read-only list, not an
     * IntArray, so the live screens that take it stay stable for Compose and skip when it hasn't changed.
     */
    private val _zoneSeconds = MutableStateFlow(List(org.openstrap.app.data.Workouts.ZONES) { 0 })
    val zoneSeconds: StateFlow<List<Int>> = _zoneSeconds
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

    /** The live screen is tucked away to use the rest of the app; the notification brings it back. */
    val minimized = MutableStateFlow(false)

    /** For "Looks like you've stopped": live readings and the strap's step totals of the last [STOP_WINDOW_S]. */
    @Volatile private var window = listOf<Pair<Long, Int>>()
    @Volatile private var steps = listOf<Pair<Long, Int>>()
    /** Nothing before this counts as activity for the end prompt: the start, or the last "Keep going". */
    private var askSince = 0L
    private var restingHr = DEFAULT_RESTING_HR

    private var recordJob: Job? = null
    private val buffer = mutableListOf<AppWorkoutHeartRate>()
    private var lastSecond = -1L
    private var wake: PowerManager.WakeLock? = null

    /**
     * Picks up a workout left recording when the process died, then starts watching for auto-start. Called once at app
     * start. One with no reading for [LEFT_RECORDING_S] records on too, while it waits for the strap's history of the
     * time the phone missed ([awaitStrap]). One paused that long is ended at its last reading, and its summary shows.
     */
    fun restore() {
        scope.launch {
            try {
                val waiting = lock.withLock {
                    if (_state.value is WorkoutState.Recording) return@withLock null
                    val w = dao.activeAppWorkout() ?: return@withLock null
                    val now = now()
                    val stored = dao.appWorkoutHeartRateOnce(w.start)
                    val last = maxOf(w.start, stored.maxOfOrNull { it.timestamp } ?: w.start)
                    val waits = now - last > LEFT_RECORDING_S
                    // Paused, nothing the strap measured since is part of it.
                    if (waits && w.pausedAt != null) {
                        endLeftRecording(w, last)
                        return@withLock null
                    }
                    // Set before it shows as recording, so an End tapped at once already waits for the strap.
                    left = if (waits) LeftRecording.Left(w.start, last, now) else null
                    _recent.value = stored.filter { !it.fromHistory && it.timestamp >= now - RECENT_S }.map { it.timestamp to it.bpm }
                    sum = stored.sumOf { it.bpm.toLong() }
                    count = stored.size.toLong()
                    _stats.value = WorkoutRecorder.stats(stored)
                    val live = stored.filter { !it.fromHistory }.map { it.timestamp to it.bpm }
                    val maxHr = maxHeartRate()
                    _zoneSeconds.value = WorkoutRecorder.liveZoneSeconds(live, maxHr, heartZones.value).toList()
                    lastReading = live.lastOrNull()
                    _kcal.value = null
                    stepTotals = emptyList()
                    synchronized(allReadings) { allReadings.clear(); allReadings.addAll(live) }
                    _history.value = live
                    window = live.filter { it.first >= now - STOP_WINDOW_S }
                    // A kept route comes back. One that wasn't kept was only ever in memory, and so were the step
                    // totals. What was stored then stands for the time before now, and the distance adds to it.
                    _route.value = if (w.keepRoute) dao.routeOnce(w.start).map { it.point } else emptyList()
                    savedPoints = _route.value.size
                    trackFrom(w, routeSince = if (w.keepRoute) w.start else now, stepsSince = now, before = w)
                    // A restart mustn't ask at once about a stop it can't see the start of.
                    askSince = now
                    val sets = dao.setsOnce(w.start)
                    val restUntil = sets.lastOrNull()?.takeIf { it.end != null && it.restEnd == null }?.let { it.end!! + restRule(w).seconds }
                    // Logging is the sport's setting at its start, which Ready saved then.
                    val log = catching { settings.sportSettings.first()[org.openstrap.app.data.Workouts.tileType(w.sportType)]?.log }
                        .onFailure { log("workout: sport settings not read: $it") }.getOrNull() == true
                    val rounds = if (w.structure != null) dao.roundsOnce(w.start) else emptyList()
                    begin(w, maxHr) {
                        it.copy(sets = sets, restUntil = restUntil, exercise = sets.lastOrNull()?.exercise, logSets = log, rounds = rounds)
                    }
                    left
                }
                waiting?.let { scope.launch { awaitStrap(it) } }
            } finally {
                _restored.value = true
                // After the restore, so a workout left recording isn't offered as a new one.
                autoStart.watch()
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
                trackFrom(w, routeSince = w.start, stepsSince = w.start, before = null)
                synchronized(allReadings) { allReadings.clear() }
                _history.value = emptyList()
                dao.upsertAppWorkout(w)
                _recent.value = emptyList()
                _stats.value = null
                sum = 0
                count = 0
                _zoneSeconds.value = List(org.openstrap.app.data.Workouts.ZONES) { 0 }
                lastReading = null
                _kcal.value = null
                // Auto-start: the strap's total where the effort began, so the steps since count.
                stepTotals = listOfNotNull(seedSteps?.let { w.start to it })
                window = emptyList()
                askSince = w.start
                minimized.value = false
                begin(w, maxHeartRate(), seed) { it.copy(logSets = logSets) }
            }
        }
    }

    /** "Looks like a workout": auto-start's offer, the strap's effort watch and what is recorded from it. */
    private val autoStart = AutoStartWatcher(
        dao, settings, strap, _state, callRinging, ::maxHeartRate,
        record = { o, seed, seedSteps -> start(o.sportType, from = o.start, seed = seed, seedSteps = seedSteps) },
        clock = clock, scope = scope,
    )

    /**
     * "Looks like a workout" ([AutoStart]): an effort under way that nobody started, while the question is open.
     * The strap buzzes once as it is asked; the notification and the Today and Activity tabs ask it.
     */
    val offer: StateFlow<AutoStart.Offer?> = autoStart.offer

    /** "Record": the workout starts where the effort began, with the readings since. */
    fun recordOffer() = autoStart.recordOffer()

    fun declineOffer() = autoStart.declineOffer()

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
            if (!callRinging()) {
                scope.launch {
                    catching { strap.buzz(org.openstrap.app.data.Cue.BackInTarget.pattern) }.onFailure { log("buzz lap not played: $it") }
                }
            }
        }
    }

    /** A pause doesn't count as time in zone. */
    fun resume() = resumeFrom(toStrap = true)

    private fun resumeFrom(toStrap: Boolean) = update { w ->
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
            catching { strap.sportSession(state, w.sportType, w.start, elapsed) }
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
                WorkoutRecorder.StrapAction.END -> endNow(toStrap = false)
            }
            return
        }
        if (p is org.openstrap.protocol.services.SportSession.Push.Status && p.status == org.openstrap.protocol.services.SportSession.STATUS_HR_NORMAL) {
            if (r.paused || !r.workout.askToEnd || r.endPrompt != null) return
            askToEnd(WorkoutRecorder.lastActive(window, steps, restingHr, r.maxHeartRate)?.takeIf { it > r.workout.start } ?: now())
        }
    }

    /**
     * Opens "Looks like you've stopped" at [at], and the strap buzzes. Applied to the state as it is now, not to one read
     * earlier, so a locked write can't drop it and it can't drop one: unless the question is open already, the workout
     * is paused or it doesn't ask.
     */
    private fun askToEnd(at: Long, inCar: Boolean = false) {
        var asked = false
        _state.update { s ->
            val r = s as? WorkoutState.Recording
            asked = r != null && !r.paused && r.workout.askToEnd && r.endPrompt == null
            if (r != null && asked) r.copy(endPrompt = at, inCar = inCar) else s
        }
        if (asked && !callRinging()) scope.launch { catching { strap.buzz(STOP_BUZZ) }.onFailure { log("buzz stop not played: $it") } }
    }

    /** Saves the workout; ended while paused, it ends where the pause began. */
    fun end() = endNow(toStrap = true)

    /**
     * End, from the app or the strap. While a workout left recording waits for the strap ([left]), it ends where
     * activity stopped by what has come so far ([LeftRecording.verdict]), and at now only when the strap says it was
     * still under way: never at the moment the app came back just because End was tapped then.
     */
    private fun endNow(toStrap: Boolean) {
        val l = left ?: return finish(null, AppWorkout.END_USER, toStrap)
        scope.launch {
            val v = judge(l, final = true)
            if (v is LeftRecording.Verdict.End) finish(v.at, AppWorkout.END_AUTO, toStrap, startOf = l.start)
            else finish(null, AppWorkout.END_USER, toStrap)
        }
    }

    /** "End at 18:50": saves the workout ending where the wearer stopped; what came after stays recorded, for undo. */
    fun endAtPrompt() {
        val r = _state.value as? WorkoutState.Recording ?: return
        finish(r.endPrompt ?: return, if (r.inCar) AppWorkout.END_CAR else AppWorkout.END_AUTO)
    }

    /** "Keep going": closes the question; it comes back only after a new active spell. */
    fun keepGoing() {
        askSince = now()
        _state.update { s -> (s as? WorkoutState.Recording)?.takeIf { it.endPrompt != null }?.copy(endPrompt = null, inCar = false) ?: s }
    }

    /** [toStrap] false when the strap ended its session itself. [startOf]: only if it is still that workout recording. */
    private fun finish(at: Long?, reason: Int, toStrap: Boolean = true, startOf: Long? = null) {
        scope.launch {
            lock.withLock {
                val r = _state.value as? WorkoutState.Recording ?: return@withLock
                if (startOf != null && r.workout.start != startOf) return@withLock
                flush()
                saveDistance()
                val w = r.workout
                val recorded = w.pausedAt ?: now()
                val end = at?.coerceIn(w.start, recorded) ?: recorded
                // Its distance written here, over the range it shows: an update() queued behind this lock would find
                // the workout already ended, and an early End leaves out what came after.
                val ended = w.copy(end = end, pausedAt = null, endReason = reason, recordedEnd = recorded.takeIf { it > end })
                    .let { WorkoutDistance.withShown(it, recorded, track()) }
                val kept = WorkoutRecorder.activeSeconds(ended, end) >= MIN_SAVED_S
                closeLast(r.sets, r.rounds, end)
                // Before anything stops: a workout that couldn't be saved, or deleted, keeps recording, so End can be
                // tried again and a restart doesn't bring back one that looked ended. The route is still in memory
                // here, kept or not, so a run gets its VO2max and its fastest stretches (its bests, workouts spec 3.2)
                // either way.
                val stored = store(ended, recorded, kept, _route.value)
                stored.exceptionOrNull()?.let {
                    log("workout ${if (kept) "not saved" else "not deleted"}, still recording: ${it.message}")
                    _state.update { s -> (s as? WorkoutState.Recording)?.copy(saveFailed = true) ?: s }
                    return@withLock
                }
                left = null
                // The heart rate stays up for a while after a workout, and that's not a new one to offer.
                autoStart.offerNotBefore = now()
                strapState(WorkoutRecorder.closingState(kept), w, send = toStrap)
                keepAlive?.cancel()
                stop(keepLive = true)
                // Under a minute is a mis-tap or a test, not a workout: nothing is kept, and the app says so.
                if (!kept) {
                    strap.setWorkoutActive(false)
                    _state.update { WorkoutState.Idle }
                    // A toast, so it shows whether it ended in the app or from the notification.
                    context.mainExecutor.execute {
                        val tooShort = org.openstrap.app.R.string.system_workout_too_short
                        android.widget.Toast.makeText(context, tooShort, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@withLock
                }
                _state.update { WorkoutState.Ended(w.start) }
                watchCalmDown(w.start, recorded)
                // Its days' calories rows take its kcal now, after the summary is up, so Health Connect gets them with it.
                editor.refreshDays(ended.from, end)
                onSaved(w.start)
            }
            // The strap's history fills any minutes the phone missed; it batches, so ask now.
            strap.syncNow()
        }
    }

    /**
     * Saves [ended], recorded until [recorded]: [kept], with its effect and its fastest stretches over [route] within
     * the range it shows, or deleted with its time dismissed. A failure leaves the stored row as it was.
     */
    private suspend fun store(ended: AppWorkout, recorded: Long, kept: Boolean, route: List<Route.Point>): Result<Unit> = catching<Unit> {
        if (kept) {
            val end = ended.end ?: recorded
            val stretches = org.openstrap.app.data.Rewards.stretches(route.filter { it.t in ended.from..end }, ended.pauseRanges)
                .let(org.openstrap.app.data.Rewards.Stretch::format)
            val hr = dao.appWorkoutHeartRateOnce(ended.start)
            dao.upsertAppWorkout(editor.withEffect(ended.copy(stretches = stretches), hr, route))
        } else {
            dao.deleteAppWorkout(ended.start)
            // Not kept, so a strap session that ran over it isn't one either, should the strap keep it.
            dao.insertDismissedWorkouts(listOf(org.openstrap.app.data.db.DismissedWorkout(ended.start, recorded)))
        }
    }

    /**
     * A workout the app was killed during while paused, with no reading for [LEFT_RECORDING_S]: ended at its [last]
     * reading, as End then would have, and its summary shows. Nothing after [last] is part of it, so a gap fill has
     * nothing after it to fill. Under [lock].
     */
    private suspend fun endLeftRecording(w: AppWorkout, last: Long) {
        val route = if (w.keepRoute) catching { dao.routeOnce(w.start).map { it.point } }.getOrDefault(emptyList()) else emptyList()
        // The stored distance covers up to the last save, about [last], and a kept route gives it exactly.
        val track = WorkoutDistance.Track(route, emptyList(), null, routeSince = w.start, stepsSince = last, before = w)
        val ended = w.copy(end = last, pausedAt = null, endReason = AppWorkout.END_AUTO, recordedEnd = null)
            .let { WorkoutDistance.withShown(it, last, track) }
        val kept = WorkoutRecorder.activeSeconds(ended, last) >= MIN_SAVED_S
        val sets = catching { dao.setsOnce(w.start) }.getOrDefault(emptyList())
        closeLast(sets, catching { dao.roundsOnce(w.start) }.getOrDefault(emptyList()), last)
        store(ended, last, kept, route).exceptionOrNull()?.let {
            // Still recording in the database, so the next start tries again.
            log("workout left recording not ended: ${it.message}")
            return
        }
        log("workout left recording ended at ${java.time.Instant.ofEpochSecond(last)}${if (kept) "" else ", under a minute so not kept"}")
        // A strap session it ran stops too.
        strapSessions = catching { settings.strapSessions.first() }
            .onFailure { log("workout: strap sessions setting not read: $it") }.getOrDefault(true)
        strapState(WorkoutRecorder.closingState(kept), ended)
        if (!kept) return
        _state.update { WorkoutState.Ended(w.start) }
        editor.refreshDays(ended.from, last)
        onSaved(w.start)
    }

    /** The workout restored with no reading for [LEFT_RECORDING_S], while its end waits for the strap, else null. */
    @Volatile private var left: LeftRecording.Left? = null

    /**
     * The wait for a workout left recording ([LeftRecording]): it records on, as any restored workout does, and is
     * judged again as each sync ends, the strap's history of the time the phone missed being in by then. It ends where
     * activity stopped, or records on when it was still under way. After [leftWaitMs] with no answer (no strap in
     * reach, say), what came decides, and with nothing from the strap it ends at the phone's last reading.
     */
    private suspend fun awaitStrap(l: LeftRecording.Left) {
        log("workout left recording since ${java.time.Instant.ofEpochSecond(l.leftAt)}: waiting for the strap's history")
        strap.syncNow()
        val v = withTimeoutOrNull(leftWaitMs) {
            // Once at the start too, for a history already in.
            strap.syncEnded.onSubscription { emit(Unit) }.map { judge(l, final = false) }.first { it != LeftRecording.Verdict.Wait }
        } ?: judge(l, final = true)
        when (v) {
            is LeftRecording.Verdict.End -> {
                log("workout left recording: ${v.why}, ended at ${java.time.Instant.ofEpochSecond(v.at)}")
                finish(v.at, AppWorkout.END_AUTO, startOf = l.start)
            }
            LeftRecording.Verdict.CarryOn -> {
                log("workout left recording: still under way, it records on")
                if (left == l) left = null
            }
            // Ended or gone meanwhile.
            LeftRecording.Verdict.Wait, null -> Unit
        }
    }

    /**
     * [l] judged by what the phone and the strap have seen so far, null once it no longer waits (ended, say). The
     * strap's minutes it read are written to the workout unless it waits on. Takes [lock].
     */
    private suspend fun judge(l: LeftRecording.Left, final: Boolean): LeftRecording.Verdict? = lock.withLock {
        val r = (_state.value as? WorkoutState.Recording)?.takeIf { left == l && it.workout.start == l.start } ?: return@withLock null
        catching {
            val seen = LeftRecording.load(dao, r.workout, l, restingHr, r.maxHeartRate, now())
            LeftRecording.verdict(l, seen, final).also { v ->
                if (v != LeftRecording.Verdict.Wait) dao.insertAppWorkoutHeartRateIfMissing(seen.filled)
            }
        }.getOrElse {
            log("workout left recording not judged: ${it.message}")
            if (final) LeftRecording.Verdict.End(l.leftAt, "the strap's history couldn't be read") else LeftRecording.Verdict.Wait
        }
    }

    /** A set or rest under way closes with the workout at [end], and so does a round's work or rest. */
    private suspend fun closeLast(
        sets: List<org.openstrap.app.data.db.AppWorkoutSet>, rounds: List<org.openstrap.app.data.db.AppWorkoutRound>, end: Long,
    ) {
        sets.lastOrNull()?.let { last ->
            when {
                last.end == null -> last.copy(end = end)
                last.restEnd == null -> last.copy(restEnd = end)
                else -> null
            }
        }?.let { set -> catching { dao.upsertSets(listOf(set)) }.onFailure { log("last set not closed: ${it.message}") } }
        rounds.lastOrNull()?.let { last ->
            when {
                last.workEnd == null -> last.copy(workEnd = end, restEnd = end)
                last.restEnd == null -> last.copy(restEnd = end)
                else -> null
            }
        }?.let { round -> catching { dao.upsertRounds(listOf(round)) }.onFailure { log("last round not closed: ${it.message}") } }
    }

    /**
     * Deletes a phone workout, and closes its summary if it is the one showing. The strap's own records of it
     * ([Workouts.strapTwins]) go too, as they show as this one: left, they would come back as a workout of their own.
     */
    fun discard(start: Long) = editor.discard(start)

    /** Closes the summary of [start], just deleted, if it is the one showing. */
    private fun closeDiscarded(start: Long) {
        val showing = _state.value
        if ((showing as? WorkoutState.Ended)?.start == start && _state.compareAndSet(showing, WorkoutState.Idle)) endCalmDown()
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
        _state.compareAndSet(WorkoutState.Idle, WorkoutState.Ended(start, preview = true))
    }

    /**
     * Saves a stretch nobody started as a workout ("Save as workout", or added after the fact), built from the
     * strap's history. A stretch over a workout already saved is refused.
     */
    fun saveFromHistory(from: Long, to: Long, sportType: Int) = editor.saveFromHistory(from, to, sportType)

    /** "Not a workout": remembered for that time range, so the same stretch isn't offered again. */
    fun dismissSuggestion(from: Long, to: Long) = editor.dismissSuggestion(from, to)

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
        if (name != null) scope.launch {
            catching { dao.upsertExercise(org.openstrap.app.data.db.ExerciseRow(name, reps, kg, now())) }
                .onFailure { log("exercise $name not saved: ${it.message}") }
        }
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
                // Every set is written each time, so the next change saves what a failed one didn't.
                val failed = catching { dao.upsertSets(changed.sets) }.onFailure { log("sets not saved: ${it.message}") }.isFailure
                // Only what [change] may touch, applied to the state as it is now: an end prompt or a rest's +10 s set
                // while the save ran stays.
                _state.update {
                    (it as? WorkoutState.Recording)
                        ?.copy(sets = changed.sets, restUntil = changed.restUntil, exercise = changed.exercise, saveFailed = failed) ?: it
                }
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
            catching {
                dao.upsertSavedWorkout(org.openstrap.app.data.db.SavedWorkout(name = name, sport = sport, structure = structure, createdAt = now()))
                dao.savedWorkoutsOnce().drop(MAX_SAVED).forEach { dao.deleteSavedWorkout(it.id) }
            }.onFailure { (strap as? StrapManager)?.log("workout not saved: ${it.message}") }
        }
    }

    /** "+10 s" on a rest. */
    fun addRest() {
        val t = now()
        _state.update { s ->
            (s as? WorkoutState.Recording)?.takeIf { it.phase(t) is org.openstrap.app.data.Phase.Rest }
                ?.let { it.copy(restExtra = it.restExtra + REST_ADD_S) } ?: s
        }
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
                // Copied only when a round ends and needs its peak, not every second.
                val peak = { a: Long, b: Long -> org.openstrap.app.data.Strength.peak(synchronized(allReadings) { allReadings.toList() }, a, b) }
                val meters = { from: Long -> repMeters(from, w.pauseRanges) }
                val step = if (skip) org.openstrap.app.data.Phases.skip(s, r.rounds, w.start, t, active, peak)
                    else org.openstrap.app.data.Phases.tick(s, r.rounds, w.start, t, active, meters, peak, r.restExtra)
                if (step.rounds != r.rounds) {
                    // Every round is written each time, as the sets are, so the next step saves what a failed one didn't.
                    val failed = catching { dao.upsertRounds(step.rounds) }.onFailure { log("rounds not saved: ${it.message}") }.isFailure
                    _state.update { s ->
                        val now = s as? WorkoutState.Recording ?: return@update s
                        now.copy(rounds = step.rounds, restExtra = if (step.switch != null) 0 else now.restExtra, saveFailed = failed)
                    }
                }
                step.switch?.let { buzz(org.openstrap.app.data.WorkoutBuzz.of(it, game = s is org.openstrap.app.data.Structure.Periods), it.name) }
                if (step.switch == null && !skip) {
                    val ends = when (val p = (_state.value as? WorkoutState.Recording)?.phase(t)) {
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

    /**
     * The rep's metres from [from], worked out again only on a new fix, rep or pause: the rounds' clock asks each
     * second, and smoothing the rep's fixes each time added up over a long rep.
     */
    private var repMetersOf: Pair<Triple<Long, Int, List<LongRange>>, Float?>? = null

    private fun repMeters(from: Long, pauses: List<LongRange>): Float? {
        val route = _route.value
        val key = Triple(from, route.size, pauses)
        repMetersOf?.let { (k, m) -> if (k == key) return m }
        val m = route.filter { it.t >= from }.takeIf { it.size > 1 }?.let { Route.distance(it, pauses).toFloat() }
        repMetersOf = key to m
        return m
    }

    /** A named buzz ([org.openstrap.app.data.WorkoutBuzz]), as quiet as a coaching cue: never during a call or Do Not Disturb. */
    private fun buzz(pattern: List<Int>, what: String) {
        if (callRinging()) return
        val filter = context.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter
        if (filter == android.app.NotificationManager.INTERRUPTION_FILTER_NONE || filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS) return
        scope.launch { catching { strap.buzz(pattern) }.onFailure { log("buzz $what not played: ${it.message}") } }
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
    fun undoTrim(start: Long) = editor.undoTrim(start)

    /** Shows a saved workout from [from] to [to], both inside what was recorded; nothing is deleted. */
    fun trim(start: Long, from: Long, to: Long) = editor.trim(start, from, to)

    fun dismissSummary() {
        _state.update { if (it is WorkoutState.Ended) WorkoutState.Idle else it }
        endCalmDown()
    }

    private fun update(change: suspend (AppWorkout) -> AppWorkout) {
        scope.launch {
            lock.withLock {
                val r = _state.value as? WorkoutState.Recording ?: return@withLock
                val w = change(r.workout)
                if (w == r.workout) return@withLock
                // The whole workout is written each time, and again at its end, so a failed save costs nothing kept.
                val failed = catching { dao.upsertAppWorkout(w) }.onFailure { log("workout not saved: ${it.message}") }.isFailure
                // Only the workout is ours: everything else (an end prompt, say) may have changed while the save ran.
                _state.update { (it as? WorkoutState.Recording)?.copy(workout = w, saveFailed = failed) ?: it }
            }
        }
    }

    private suspend fun maxHeartRate() =
        catching { maxHeartRates.now().bpm }.onFailure { log("workout: max heart rate not read: $it") }.getOrDefault(DEFAULT_MAX_HR)

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
    private var heightCm: Int? = null

    /** Writes new route points (when kept) and the distance so far: GPS when it has fixes, else steps (≈). */
    private suspend fun saveDistance() {
        val r = _state.value as? WorkoutState.Recording ?: return
        val w = r.workout
        val points = _route.value
        if (w.keepRoute && points.size > savedPoints) {
            val fresh = points.subList(savedPoints, points.size)
            catching { dao.insertRoutePoints(fresh.map { AppWorkoutRoutePoint.of(w.start, it) }) }
                .onSuccess { savedPoints = points.size }
                .onFailure { log("${fresh.size} route points not saved, kept for the next flush: ${it.message}") }
        }
        val (meters, fromSteps) = WorkoutDistance.shown(w, now(), track()) ?: return
        // A distance goal buzzes at halfway and once reached; when is kept for the summary.
        if (!r.paused) coach.distance(now(), meters)?.let { c ->
            cue(c)
            if (c == org.openstrap.app.data.Cue.GoalReached) { val t = now(); update { it.copy(targetReachedAt = t) } }
        }
        if (kotlin.math.abs((w.distanceMeters ?: 0f) - meters) < 5f) return
        update { it.copy(distanceMeters = meters, distanceFromSteps = fromSteps) }
    }

    /**
     * The workout whose route and step totals memory holds ([trackStart]), from when ([routeSince], [stepsSince]), and
     * as stored when a restart picked it up ([before]). Kept after its end, so a trim in its summary works its
     * distance out again from them.
     */
    private var trackStart: Long? = null
    private var routeSince = 0L
    private var stepsSince = 0L
    private var before: AppWorkout? = null

    private fun trackFrom(w: AppWorkout, routeSince: Long, stepsSince: Long, before: AppWorkout?) {
        trackStart = w.start
        this.routeSince = routeSince
        this.stepsSince = stepsSince
        this.before = before
    }

    /** What the distance is worked out from ([WorkoutDistance]): GPS once it has settled, else the strap's steps. */
    private fun track() = WorkoutDistance.Track(_route.value, stepTotals, heightCm, routeSince, stepsSince, before)

    /**
     * Starts recording [w]: its state is [setup] of a fresh one, and [seed] (auto-start's readings so far) is recorded
     * before the live readings start, so [record] only ever runs on one coroutine at a time.
     */
    private suspend fun begin(
        w: AppWorkout, maxHr: Int, seed: List<Pair<Long, Int>> = emptyList(),
        setup: (WorkoutState.Recording) -> WorkoutState.Recording = { it },
    ) {
        // Waited for, so the calm down's own switch-off can't land after this workout turns the stream on.
        calmJob?.cancelAndJoin()
        calmJob = null
        coach = org.openstrap.app.data.Coach(org.openstrap.app.data.WorkoutTarget.parse(w.target), w.targetReachedAt)
        strapSessions = catching { settings.strapSessions.first() }
            .onFailure { log("workout: strap sessions setting not read: $it") }.getOrDefault(true)
        heightCm = catching { settings.profile.first().heightCm }.onFailure { log("workout: height not read: $it") }.getOrNull()
        if (w.useGps) location.start()
        _state.update { setup(WorkoutState.Recording(w, maxHr)) }
        strap.setWorkoutActive(true)
        startService(w.sportType)
        strap.autoConnect()
        // The CPU must keep up with the strap's readings and the flushes while the screen is off.
        wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenStrap:workout").apply { acquire(MAX_WAKE_MS) }
        watchForCar()
        restingHr = catching { dao.restingHrIn(now() - RESTING_LOOKBACK_S, now(), java.time.ZoneId.systemDefault()).lastOrNull()?.bpm }
            .onFailure { log("workout: resting heart rate not read: $it") }.getOrNull() ?: DEFAULT_RESTING_HR
        recordJob?.cancelAndJoin()
        for ((t, bpm) in seed) record(t, bpm)
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
                }
            }
            launch { countKcal(w, maxHr) }
            // Strength rest is timed to the second, not to the flush.
            if (w.sportType in org.openstrap.app.data.SportTypes.STRENGTH) launch {
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
        val profile = catching { settings.profile.first() }.onFailure { log("kcal: profile not read: $it") }.getOrNull() ?: return
        if (org.openstrap.app.data.EnergyExpenditure.bmrPerMinute(profile, profile.age()) == null) return
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochSecond(w.from).atZone(zone).toLocalDate()
        val resting = catching { org.openstrap.app.data.WorkoutEffectEngine(dao).restingHr(w.from, zone) }
            .onFailure { log("kcal: resting heart rate not read: $it") }.getOrDefault(DEFAULT_RESTING_HR.toFloat())
        val vo2 = catching { RecoveryEngine(dao).vo2max(profile, date, zone) }.onFailure { log("kcal: VO2 max not read: $it") }.getOrNull()
        var history = emptyList<AppWorkoutHeartRate>()
        var synced = emptyMap<Long, Int>()
        var tick = 0
        while (true) {
            val r = _state.value as? WorkoutState.Recording ?: return
            if (r.workout.start != w.start) return
            val t = now()
            if (tick++ % KCAL_READ_TICKS == 0) {
                history = catching { dao.appWorkoutHeartRateOnce(w.start).filter { it.fromHistory } }
                    .onFailure { log("kcal: synced heart rate not read: $it") }.getOrDefault(history)
                synced = catching {
                    dao.activityOnce(r.workout.from - 60, t + 60).filter { it.steps in 0..250 }.associate { it.timestamp / 60 * 60 to it.steps }
                }.onFailure { log("kcal: synced steps not read: $it") }.getOrDefault(synced)
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
    @androidx.annotation.VisibleForTesting
    internal fun checkStopped() {
        val r = _state.value as? WorkoutState.Recording ?: return
        if (r.paused || !r.workout.askToEnd || r.endPrompt != null) return
        val since = maxOf(askSince, r.workout.start)
        WorkoutRecorder.stoppedSince(window, steps, restingHr, r.maxHeartRate, since, now())?.let { askToEnd(it) }
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
            askToEnd(at, inCar = true)
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
        scope.launch { catching { strap.buzz(c.pattern) }.onFailure { log("coach $c not played: ${it.message}") } }
        (strap as? StrapManager)?.log("coach: $c")
        _lastCue.value = c
    }

    /** The last coaching cue, for the live screen to show what the buzz meant. */
    private val _lastCue = MutableStateFlow<org.openstrap.app.data.Cue?>(null)
    val lastCue: StateFlow<org.openstrap.app.data.Cue?> = _lastCue

    /**
     * Starts the foreground service for a workout recording [sportType]. From the background (a restore after a
     * widget's broadcast, say) Android 12 and up may refuse it: the workout records on, but with no foreground service
     * it can be killed, so [KeepRecordingNotice] asks for a tap that brings the app forward, where [inFront] tries again.
     */
    private fun startService(sportType: Int) {
        runCatching { StrapService.start(context) }
            .onSuccess { KeepRecordingNotice.clear(context) }
            .onFailure {
                log("service start failed while recording, asking to keep it going: $it")
                KeepRecordingNotice.show(context, sportType)
            }
    }

    /** The app came to the front: a workout recording with no service, refused it in the background, starts it now. */
    fun inFront() {
        val r = _state.value as? WorkoutState.Recording ?: return
        if (!StrapService.running) startService(r.workout.sportType)
    }

    /** [keepLive]: the live heart rate stays on for the calm down after the end ([watchCalmDown]). */
    private fun stop(keepLive: Boolean = false) {
        KeepRecordingNotice.clear(context)
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
        window = (window + (t to bpm)).dropWhile { it.first < t - STOP_WINDOW_S }
        sum += bpm
        count++
        // A pause between this reading and the one before isn't time in zone. Checked here rather than cleared on
        // resume, so only this coroutine touches what a reading adds up.
        lastReading?.takeIf { (pt, _) -> (r.workout.pauseRanges.lastOrNull()?.last ?: Long.MIN_VALUE) <= pt }?.let { (pt, pbpm) ->
            val z = WorkoutRecorder.liveZoneOf(pbpm, r.maxHeartRate, heartZones.value)
            val added = (t - pt).coerceIn(0, WorkoutRecorder.MAX_STEP_S).toInt()
            _zoneSeconds.value = _zoneSeconds.value.toMutableList().also { it[z] += added }
        }
        lastReading = t to bpm
        coach.feed(
            t, WorkoutRecorder.liveZone(bpm, r.maxHeartRate, heartZones.value), WorkoutRecorder.activeSeconds(r.workout, t),
        )?.let(::cue)
        _stats.value = WorkoutRecorder.Stats(Math.round(sum.toDouble() / count).toInt(), maxOf(_stats.value?.max ?: 0, bpm), minOf(_stats.value?.min ?: bpm, bpm))
        // Last, so whoever sees the reading in [recent] also sees its zone time and stats.
        _recent.value = (_recent.value + (t to bpm)).dropWhile { it.first < t - RECENT_S }
    }

    private suspend fun flush() {
        val batch = synchronized(buffer) { buffer.toList().also { buffer.clear() } }
        if (batch.isEmpty()) return
        catching { dao.insertAppWorkoutHeartRate(batch) }
            .onSuccess {
                if (flushFailing) log("heart rate saved again")
                flushFailing = false
            }
            .onFailure { e ->
                // Kept for the next flush rather than lost, but only the newest [MAX_UNSAVED]: a database that keeps
                // failing mustn't fill the memory too.
                val dropped = synchronized(buffer) {
                    buffer.addAll(0, batch)
                    (buffer.size - MAX_UNSAVED).coerceAtLeast(0).also { buffer.subList(0, it).clear() }
                }
                // Once a spell, not every flush: the log keeps only its last lines.
                if (!flushFailing) log("heart rate not saved, kept for the next flush: ${e.message}")
                if (dropped > 0 && !flushDropping) log("heart rate unsaved for too long, the oldest readings are dropped")
                flushFailing = true
                flushDropping = dropped > 0
            }
    }

    /** Whether the last flush failed, and whether it had to drop readings, so a spell of failures is logged once. */
    private var flushFailing = false
    private var flushDropping = false

    private fun now() = clock()

    /** To the strap's log, which the Device screen shows. */
    private fun log(message: String) = (strap as? StrapManager)?.log(message)

    internal companion object {
        /**
         * For a session built without the app's scope (tests): one that logs an uncaught exception rather than letting
         * it kill the process, as `AppContainer.appScope` does.
         */
        // Only a fallback: the app passes its own scope, with its dispatcher, in.
        @Suppress("InjectDispatcher")
        fun fallbackScope() = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> android.util.Log.e("OpenStrap", "workout: uncaught $e", e) },
        )

        const val FLUSH_MS = 10_000L
        /** Live readings held while the database refuses them: two hours, one a second. */
        const val MAX_UNSAVED = 7_200
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
        /** Auto-start's offer ([AutoStartWatcher]): three short pulses, unlike the stop question's two long ones. */
        val OFFER_BUZZ = listOf(150, 150, 150, 150, 150)
        /** Saved workouts kept (workouts spec 2.5). */
        const val MAX_SAVED = 10
        /** "+10 s" on an interval's rest. */
        const val REST_ADD_S = 10L
        /** The phone beeps the last this many seconds of a timed work or rest. */
        const val COUNTDOWN_S = 3L
        const val BEEP_MS = 120
        const val BEEP_VOLUME = 70
        /**
         * One workout change at a time in the whole app: the session, its [SavedWorkoutEditor] and [WorkoutGapFill]
         * after a sync, which the strap's sync runs, not the session.
         */
        val LOCK = Mutex()
        /**
         * A workout left recording with no reading for this long when the app restarts waits for the strap's history
         * ([LeftRecording]), and one the strap measured nothing for this long ends at its last reading.
         */
        const val LEFT_RECORDING_S = 2 * 3600L
        /** How long a workout left recording waits for a sync to bring the strap's history. */
        const val LEFT_WAIT_MS = 10 * 60_000L
        /** A safety net should end() never come; the strap's battery is long gone by then. */
        const val MAX_WAKE_MS = 12 * 3600_000L
    }
}
