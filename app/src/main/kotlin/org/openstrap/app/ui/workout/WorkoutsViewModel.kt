package org.openstrap.app.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openstrap.app.data.RecoveryEngine
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.UserProfile
import kotlin.math.roundToInt

/**
 * What a phone workout's summary shows: [analysis] over its shown range, [recorded] every sample from
 * [recordedStart] to [recordedEnd] (for the trim chart).
 */
data class PhoneSummary(
    val analysis: WorkoutAnalysis,
    val recorded: List<org.openstrap.app.data.db.WorkoutHeartRate>,
    val recordedStart: Long,
    val recordedEnd: Long,
    val endReason: Int?,
    /** The route, when it was kept; empty otherwise. */
    val route: List<org.openstrap.app.data.Route.Point> = emptyList(),
    /** Every live reading in the shown range, for colouring the route. */
    val history: List<Pair<Long, Int>> = emptyList(),
    val distanceMeters: Float? = null,
    val distanceFromSteps: Boolean = false,
    /** Strength: each set and the rest after it. */
    val sets: List<org.openstrap.app.data.db.AppWorkoutSet> = emptyList(),
    /**
     * The strap's own records of the same workout, by start, when it kept any (a strap session, or its
     * auto-detection); more than one when a long workout held several.
     */
    val strapWorkouts: List<WorkoutAnalysis> = emptyList(),
    /** Laps, when any were marked. */
    val laps: List<WorkoutRecorder.Lap> = emptyList(),
    /** Finished pauses, which no split counts. */
    val pauses: List<LongRange> = emptyList(),
    /** Its effect figures are the strap's own ([org.openstrap.app.data.db.AppWorkout.effectFromStrap]). */
    val effectFromStrap: Boolean = false,
    /** Its rounds or periods ([org.openstrap.app.data.db.AppWorkout.structure]) and each one recorded. */
    val structure: org.openstrap.app.data.Structure? = null,
    val rounds: List<org.openstrap.app.data.db.AppWorkoutRound> = emptyList(),
    /** Strength: the heart rate rest ended under, when rest ended on heart rate ([org.openstrap.app.data.Strength.Rest]). */
    val restBelow: Int? = null,
    /** Strength: how long the heart rate took to fall under [restBelow] in the last session before ([FamilySummary.restRecovery]). */
    val restBefore: Long? = null,
)

/** Workouts list (newest first) and the one open in detail. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsViewModel(private val dao: HealthDao, private val profile: Flow<UserProfile>) : ViewModel() {
    /**
     * Zones fall back to the app's one max-HR rule (tested value, Tanaka, strap, raised by observed peaks) when a
     * workout has no sport effect of its own. Shared, so the 90-day read behind it runs once per profile change.
     */
    private val fallbackMaxHr: Flow<Int> = profile
        .mapLatest { p -> RecoveryEngine(dao).maxHeartRate(p).roundToInt() }
        .flowOn(Dispatchers.Default)
        .stateIn<Int?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        .filterNotNull()

    private val zoneCache = ZoneCache<Pair<Boolean, Long>>()

    /**
     * Each listed workout's zone seconds, by (from the phone, start), worked out from its whole HR stream as its page
     * does, so the list and the page agree. Off the main thread, and only for workouts whose row, effect, max HR or
     * stream changed ([ZoneCache]); empty at first, so the list shows before its zones fill in.
     */
    private val listZones: Flow<Map<Pair<Boolean, Long>, List<Int>>> =
        combine(
            dao.workouts(), dao.sportEffects(), dao.workoutHeartRateMarks(),
            combine(dao.appWorkouts(), dao.appWorkoutHeartRateMarks()) { phone, marks -> phone to marks }, fallbackMaxHr,
        ) { ws, effects, marks, (phone, phoneMarks), maxHr ->
            val strapMark = marks.associateBy { it.workoutStart }
            val phoneMark = phoneMarks.associateBy { it.workoutStart }
            val strapByStart = ws.associateBy { it.start }
            val phoneByStart = phone.associateBy { it.start }
            val stamps = ws.associate { w -> (false to w.start) to listOf(w, Workouts.effectOf(w, effects), maxHr, strapMark[w.start]) } +
                phone.associate { w -> (true to w.start) to listOf(w, maxHr, phoneMark[w.start]) }
            val work: suspend (Pair<Boolean, Long>) -> List<Int> = { (fromPhone, start) ->
                if (fromPhone) WorkoutRecorder.analyse(phoneByStart.getValue(start), dao.appWorkoutHeartRateOnce(start), maxHr).zoneSeconds
                else Workouts.analyse(strapByStart.getValue(start), dao.workoutHeartRateOnce(start), effects, maxHr).zoneSeconds
            }
            stamps to work
        }
            .mapLatest { (stamps, work) -> zoneCache.zones(stamps, work) }
            .flowOn(Dispatchers.Default)
            .onStart { emit(emptyMap()) }

    /**
     * The strap's workouts and the phone's, newest first. List rows use the stored end; the detail screen works out
     * the real one from the HR stream. Zones come from [listZones]. A phone strength workout carries its count of
     * finished sets.
     */
    val workouts: StateFlow<List<WorkoutAnalysis>> =
        combine(
            combine(
                dao.workouts(), dao.sportEffects(), dao.lastWorkoutHeartRates(),
                combine(dao.appWorkouts(), dao.setCounts()) { phone, sets -> phone to sets.associate { it.workoutStart to it.sets } }, fallbackMaxHr,
            ) { ws, effects, last, (phone, sets), maxHr -> Workouts.listed(ws, effects, last, phone, sets, maxHr) },
            listZones,
        ) { list, zones -> list.map { a -> zones[a.fromPhone to a.workout.start]?.let { a.copy(zoneSeconds = it) } ?: a } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The strap's sport-effect records, newest last: recovery time, VO2 max and weekly load after each workout. */
    val effects: StateFlow<List<org.openstrap.app.data.db.SportEffectRow>> =
        dao.sportEffects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The max heart rate zones are shares of, once worked out. */
    val maxHeartRate: StateFlow<Int?> = fallbackMaxHr.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A day's heart rate a minute at a time, for adding a workout after the fact. */
    suspend fun dayMinuteHeartRate(date: java.time.LocalDate): List<Pair<Long, Int>> = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val w = org.openstrap.app.data.DayWindow.of(date)
        org.openstrap.app.data.Summaries.bucketAverage(org.openstrap.app.data.Summaries.heartRateSeries(dao.heartRateOnce(w.from, w.to), dao.activityOnce(w.from, w.to)), 60)
    }

    /** From the start of yesterday: the stretches the strap's history may hold that nobody started. */
    private val suggestFrom = org.openstrap.app.data.DayWindow.of(java.time.LocalDate.now().minusDays(1)).from

    /**
     * Workouts nobody started, newest first ([org.openstrap.app.data.WorkoutSuggestions]), from the strap's heart rate
     * since yesterday. Sleep, saved workouts and stretches answered "Not a workout" never count.
     */
    val suggestions: StateFlow<List<org.openstrap.app.data.WorkoutSuggestions.Suggestion>> = combine(
        combine(dao.heartRate(suggestFrom, Long.MAX_VALUE), dao.activity(suggestFrom, Long.MAX_VALUE)) { hr, minutes ->
            org.openstrap.app.data.Summaries.bucketAverage(org.openstrap.app.data.Summaries.heartRateSeries(hr, minutes), 60)
        },
        combine(dao.workouts(), dao.appWorkouts(), dao.dismissedWorkouts(suggestFrom), dao.sleepSessions(suggestFrom, Long.MAX_VALUE)) { strap, phone, dismissed, sleep ->
            strap.map { it.start..it.end } + phone.map { it.start..(it.recordedEnd ?: it.end ?: it.start) } +
                dismissed.map { it.start..it.end } + sleep.map { it.start..it.end }
        },
        fallbackMaxHr,
    ) { series, busy, maxHr -> Triple(series, busy, maxHr) }
        .mapLatest { (series, busy, maxHr) ->
            val now = System.currentTimeMillis() / 1000
            val resting = dao.restingHrOnce(now - 14 * 86_400L, now).lastOrNull()?.bpm ?: 60
            org.openstrap.app.data.WorkoutSuggestions.find(series, resting, maxHr, busy)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val openStart = MutableStateFlow<Long?>(null)

    val open: StateFlow<WorkoutAnalysis?> = openStart.flatMapLatest { start ->
        if (start == null) flowOf(null)
        else dao.appWorkout(start).flatMapLatest { phone ->
            if (phone != null) phoneAnalysis(start)
            else combine(dao.workout(start), dao.workoutHeartRate(start), dao.sportEffects(), fallbackMaxHr) { w, hr, effects, maxHr ->
                w?.let { Workouts.analyse(it, hr, effects, maxHr) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A phone workout with every second of it, updating as gap filling adds minutes. */
    fun phoneAnalysis(start: Long): Flow<WorkoutAnalysis?> =
        combine(dao.appWorkout(start), dao.appWorkoutHeartRate(start), fallbackMaxHr) { w, hr, maxHr ->
            w?.let { WorkoutRecorder.analyse(it, hr, maxHr) }
        }

    /**
     * A phone workout's summary: its analysis, everything recorded (for trimming), and the strap's own record of it
     * when there is one. [backfill] works out the effect of a workout from before there was one and saves it; off,
     * opening the summary writes nothing.
     */
    fun phoneSummary(start: Long, backfill: Boolean = true): Flow<PhoneSummary?> =
        combine(dao.appWorkout(start), dao.appWorkoutHeartRate(start), fallbackMaxHr, workouts, combine(dao.route(start), dao.sets(start), dao.rounds(start)) { r, s, n -> Triple(r, s, n) }) { w, hr, maxHr, all, extra ->
            Quad(w, hr, maxHr, all) to extra
        }
            .mapLatest { (q, extra) ->
                val (routeRows, sets, rounds) = extra
                val (w, hr, maxHr, all) = q
                w ?: return@mapLatest null
                // A workout from before its effect was worked out gets it now; saving it brings this summary round again.
                if (backfill && w.end != null && w.epoc == null && hr.isNotEmpty()) {
                    runCatching { org.openstrap.app.data.WorkoutEffectEngine(dao).backfill(w, profile.first()) }
                }
                val a = WorkoutRecorder.analyse(w, hr, maxHr)
                // The strap's own records of this workout ([Workouts.sameWorkout]), which the lists leave out.
                val effects = dao.sportEffects().first()
                val strap = Workouts.strapTwins(dao.workoutsOnce(w.from, w.end ?: a.end), w.from..(w.end ?: a.end))
                    .map { s -> Workouts.analyse(s, dao.workoutHeartRate(s.start).first(), effects, maxHr) }
                val route = routeRows.map { it.point }.filter { it.t in w.from..(w.end ?: Long.MAX_VALUE) }
                val below = org.openstrap.app.data.Strength.Rest.parse(w.rest)?.belowBpm
                PhoneSummary(
                    a, WorkoutRecorder.expand(hr), w.start, w.recordedEnd ?: w.end ?: a.end, w.endReason,
                    route, hr.filter { !it.fromHistory }.map { it.timestamp to it.bpm }, w.distanceMeters, w.distanceFromSteps, sets, strap,
                    w.lapMarks.takeIf { it.isNotEmpty() }?.let { marks ->
                        WorkoutRecorder.laps(w.from, w.end ?: a.end, marks, hr.filter { !it.fromHistory }.map { it.timestamp to it.bpm }, route, w.pauseRanges)
                    }.orEmpty(),
                    w.pauseRanges, w.effectFromStrap,
                    org.openstrap.app.data.Structure.parse(w.structure), rounds, below,
                    below?.let { restBefore(w, it) },
                )
            }.flowOn(Dispatchers.Default)

    /**
     * How fast rest brought the heart rate under [below] in the last strength session before [w] with sets, in the
     * fortnight before ([FamilySummary.restRecovery]); null without one.
     */
    private suspend fun restBefore(w: org.openstrap.app.data.db.AppWorkout, below: Int): Long? {
        val last = dao.appWorkoutsOnce(w.start - 14 * 86_400L, w.start - 1)
            .filter { it.end != null && it.start < w.start && it.sportType in org.openstrap.app.data.Strength.SPORTS }
            .maxByOrNull { it.start } ?: return null
        val readings = dao.appWorkoutHeartRateOnce(last.start).map { it.timestamp to it.bpm }
        return FamilySummary.restRecovery(dao.setsOnce(last.start), readings, below)
    }

    /**
     * Your usual heart rate drop in the first minute after a workout, before [before] ([SummaryCopy.usualDrop]): from
     * the strap's own workouts, whose recording runs on after the end, and only those ended from a real effort
     * ([WorkoutAnalysis.recoveryIsMeaningful]). Phone workouts keep no drop yet. Null with too few.
     */
    suspend fun usualDrop(before: Long): Int? = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val maxHr = fallbackMaxHr.first()
        val effects = dao.sportEffects().first()
        val drops = dao.workouts().first()
            .filter { it.start < before && it.start >= before - USUAL_DROP_LOOKBACK_S }
            .sortedByDescending { it.start }
            .take(SummaryCopy.USUAL_DROP_OF * 2)
            .mapNotNull { w -> Workouts.analyse(w, dao.workoutHeartRateOnce(w.start), effects, maxHr).takeIf { it.recoveryIsMeaningful }?.recovery60 }
        SummaryCopy.usualDrop(drops)
    }

    private companion object {
        /** Drops older than this say little about your fitness now. */
        const val USUAL_DROP_LOOKBACK_S = 180 * 86_400L
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    fun open(start: Long?) { openStart.value = start }

    /** Deletes a strap workout and keeps it from coming back with a later sync ([HealthDao.deleteStrapWorkout]). */
    fun deleteStrapWorkout(start: Long) {
        viewModelScope.launch { dao.deleteStrapWorkout(start, System.currentTimeMillis() / 1000) }
    }
}
