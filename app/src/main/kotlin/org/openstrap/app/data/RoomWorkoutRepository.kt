package org.openstrap.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.data.settings.UserProfile
import java.time.LocalDate

/**
 * The [WorkoutRepository] over Room. Nothing here picks a thread: the callers' flowOn and withContext do, as the rows
 * are heavy to work through.
 */
// Most of its functions are overrides of the interface's, which detekt counts too.
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
class RoomWorkoutRepository(
    private val dao: HealthDao,
    private val profile: Flow<UserProfile>,
    maxHeartRates: MaxHeartRates,
    /** Your heart rate zones as stored ([org.openstrap.app.data.settings.SettingsStore.heartZones]). */
    zones: Flow<HeartZones>,
) : WorkoutRepository {
    /**
     * Zones fall back to the app's one max-HR rule (tested value, Tanaka, strap, raised by observed peaks) when a
     * workout has no sport effect of its own. The app's shared one, so the 90-day read behind it runs once per profile
     * change for every screen.
     */
    private val learnedMaxHr: Flow<Int> = maxHeartRates.current.filterNotNull().map { it.bpm }.distinctUntilChanged()

    /** [learnedMaxHr] with your zones, again whenever either moves, so what reads zones reads them afresh. */
    private val maxHrAndZones: Flow<Pair<Int, HeartZones>> = combine(learnedMaxHr, zones, ::Pair)

    override fun maxHeartRate(): Flow<Int> = learnedMaxHr

    override fun effects(): Flow<List<SportEffectRow>> = dao.sportEffects()

    /**
     * Each listed workout's zone seconds, by (from the phone, start), worked out from its whole HR stream as its page
     * does, so the list and the page agree. Only for workouts whose row, effect, max HR or stream, or your zones,
     * changed ([ZoneCache], one per collection). Empty at first, so the list shows before its zones fill in.
     */
    private fun listZones(): Flow<Map<Pair<Boolean, Long>, List<Int>>> = flow {
        val zoneCache = ZoneCache<Pair<Boolean, Long>>()
        emitAll(
            combine(
                dao.workouts(), dao.sportEffects(), dao.workoutHeartRateMarks(),
                combine(dao.appWorkouts(), dao.appWorkoutHeartRateMarks()) { phone, marks -> phone to marks },
                maxHrAndZones,
            ) { ws, effects, marks, (phone, phoneMarks), (maxHr, zones) ->
                val strapMark = marks.associateBy { it.workoutStart }
                val phoneMark = phoneMarks.associateBy { it.workoutStart }
                val strapByStart = ws.associateBy { it.start }
                val phoneByStart = phone.associateBy { it.start }
                val strapStamps = ws.associate { w ->
                    (false to w.start) to listOf(w, Workouts.effectOf(w, effects), maxHr, zones, strapMark[w.start])
                }
                val stamps = strapStamps + phone.associate { w -> (true to w.start) to listOf(w, maxHr, zones, phoneMark[w.start]) }
                val analyse: suspend (Pair<Boolean, Long>) -> List<Int> = { (fromPhone, start) ->
                    val a = if (fromPhone) {
                        WorkoutRecorder.analyse(phoneByStart.getValue(start), dao.appWorkoutHeartRateOnce(start), maxHr, zones)
                    } else {
                        Workouts.analyse(strapByStart.getValue(start), dao.workoutHeartRateOnce(start), effects, maxHr, zones)
                    }
                    a.zoneSeconds
                }
                stamps to analyse
            }.mapLatest { (stamps, analyse) -> zoneCache.zones(stamps, analyse) },
        )
    }.onStart { emit(emptyMap()) }

    /**
     * List rows use the stored end, and the detail screen works out the real one from the HR stream. A phone strength
     * workout carries its count of finished sets.
     */
    override fun workouts(): Flow<List<WorkoutAnalysis>> = combine(
        combine(
            dao.workouts(), dao.sportEffects(), dao.lastWorkoutHeartRates(),
            combine(dao.appWorkouts(), dao.setCounts()) { phone, sets -> phone to sets.associate { it.workoutStart to it.sets } },
            maxHrAndZones,
        ) { ws, effects, last, (phone, sets), (maxHr, _) -> Workouts.listed(ws, effects, last, phone, sets, maxHr) },
        listZones(),
    ) { list, zones -> list.map { a -> zones[a.fromPhone to a.workout.start]?.let { a.copy(zoneSeconds = it) } ?: a } }

    override suspend fun dayMinuteHeartRate(date: LocalDate): List<Pair<Long, Int>> {
        val w = DayWindow.of(date)
        return Summaries.bucketAverage(Summaries.heartRateSeries(dao.heartRateOnce(w.from, w.to), dao.activityOnce(w.from, w.to)), MINUTE_S)
    }

    /** Sleep, saved workouts and stretches answered "Not a workout" never count. */
    override fun suggestions(from: Long, now: () -> Long): Flow<List<WorkoutSuggestions.Suggestion>> = combine(
        combine(dao.heartRate(from, Long.MAX_VALUE), dao.activity(from, Long.MAX_VALUE)) { hr, minutes ->
            Summaries.bucketAverage(Summaries.heartRateSeries(hr, minutes), MINUTE_S)
        },
        combine(
            dao.workouts(), dao.appWorkouts(), dao.dismissedWorkouts(from), SleepSource(dao).sessions(from, Long.MAX_VALUE),
        ) { strap, phone, dismissed, sleep ->
            strap.map { it.start..it.end } + phone.map { it.start..(it.recordedEnd ?: it.end ?: it.start) } +
                dismissed.map { it.start..it.end } + sleep.map { it.start..it.end }
        },
        maxHrAndZones,
    ) { series, busy, basis -> Triple(series, busy, basis) }
        .mapLatest { (series, busy, basis) ->
            val (maxHr, zones) = basis
            val at = now()
            val resting = dao.restingHrOnce(at - 14 * 86_400L, at).lastOrNull()?.bpm ?: 60
            WorkoutSuggestions.find(series, resting, maxHr, busy, zones)
        }

    override fun open(start: Long): Flow<WorkoutAnalysis?> = dao.appWorkout(start).flatMapLatest { phone ->
        if (phone != null) phoneAnalysis(start)
        else combine(dao.workout(start), dao.workoutHeartRate(start), dao.sportEffects(), maxHrAndZones) { w, hr, effects, (maxHr, zones) ->
            w?.let { Workouts.analyse(it, hr, effects, maxHr, zones) }
        }
    }

    /** A phone workout with every second of it, updating as gap filling adds minutes. */
    private fun phoneAnalysis(start: Long): Flow<WorkoutAnalysis?> =
        combine(dao.appWorkout(start), dao.appWorkoutHeartRate(start), maxHrAndZones) { w, hr, (maxHr, zones) ->
            w?.let { WorkoutRecorder.analyse(it, hr, maxHr, zones) }
        }

    /** Its analysis, everything recorded (for trimming), and the strap's own record of it when there is one. */
    override fun phoneSummary(start: Long): Flow<WorkoutSummaryData?> =
        combine(
            dao.appWorkout(start), dao.appWorkoutHeartRate(start), maxHrAndZones,
            combine(dao.route(start), dao.sets(start), dao.rounds(start)) { r, s, n -> Triple(r, s, n) },
        ) { w, hr, basis, extra ->
            Triple(w, hr, basis) to extra
        }
            .mapLatest { (main, extra) ->
                val (routeRows, sets, rounds) = extra
                val (w, hr, basis) = main
                val (maxHr, zones) = basis
                w ?: return@mapLatest null
                val a = WorkoutRecorder.analyse(w, hr, maxHr, zones)
                // The strap's own records of this workout ([Workouts.sameWorkout]), which the lists leave out.
                val effects = dao.sportEffects().first()
                val strap = Workouts.strapTwins(dao.workoutsOnce(w.from, w.end ?: a.end), w.from..(w.end ?: a.end))
                    .map { s -> Workouts.analyse(s, dao.workoutHeartRate(s.start).first(), effects, maxHr, zones) }
                val route = routeRows.map { it.point }.filter { it.t in w.from..(w.end ?: Long.MAX_VALUE) }
                val below = Strength.Rest.parse(w.rest)?.belowBpm
                val live = hr.filter { !it.fromHistory }.map { it.timestamp to it.bpm }
                WorkoutSummaryData(
                    a, WorkoutRecorder.expand(hr), w.start, w.recordedEnd ?: w.end ?: a.end, w.endReason,
                    route, live, w.distanceMeters, w.distanceFromSteps, sets, strap,
                    w.lapMarks.takeIf { it.isNotEmpty() }?.let { marks ->
                        WorkoutRecorder.laps(w.from, w.end ?: a.end, marks, live, route, w.pauseRanges)
                    }.orEmpty(),
                    w.pauseRanges, w.effectFromStrap,
                    Structure.parse(w.structure), rounds, below,
                    below?.let { restBefore(w, it) },
                )
            }

    /** Saves nothing for a workout that already has its effect ([WorkoutEffectEngine.backfill]). */
    override suspend fun backfillEffect(start: Long) {
        val w = dao.appWorkoutOnce(start) ?: return
        WorkoutEffectEngine(dao).backfill(w, profile.first())
    }

    /**
     * How fast rest brought the heart rate under [below] in the last strength session before [w] with sets, in the
     * fortnight before ([WorkoutSummaryData.restRecovery]), or null without one.
     */
    private suspend fun restBefore(w: AppWorkout, below: Int): Long? {
        val last = dao.appWorkoutsOnce(w.start - 14 * 86_400L, w.start - 1)
            .filter { it.end != null && it.start < w.start && it.sportType in SportTypes.STRENGTH }
            .maxByOrNull { it.start } ?: return null
        val readings = dao.appWorkoutHeartRateOnce(last.start).map { it.timestamp to it.bpm }
        return WorkoutSummaryData.restRecovery(dao.setsOnce(last.start), readings, below)
    }

    /** From the strap's own workouts, whose recording runs on after the end. Phone workouts keep no drop yet. */
    override suspend fun recoveryDrops(before: Long, count: Int): List<Int> {
        val (maxHr, zones) = maxHrAndZones.first()
        val effects = dao.sportEffects().first()
        return dao.workouts().first()
            .filter { it.start < before && it.start >= before - USUAL_DROP_LOOKBACK_S }
            .sortedByDescending { it.start }
            .take(count)
            .mapNotNull { w ->
                Workouts.analyse(w, dao.workoutHeartRateOnce(w.start), effects, maxHr, zones).takeIf { it.recoveryIsMeaningful }?.recovery60
            }
    }

    override suspend fun deleteStrapWorkout(start: Long, now: Long) {
        dao.deleteStrapWorkout(start, now)
    }

    private companion object {
        /** Heart rate a minute at a time. */
        const val MINUTE_S = 60L

        /** Drops older than this say little about your fitness now. */
        const val USUAL_DROP_LOOKBACK_S = 180 * 86_400L
    }
}
