package org.openstrap.app.widget

import android.util.Log
import kotlinx.coroutines.flow.first
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.HealthRepository
import org.openstrap.app.data.LiveSteps
import org.openstrap.app.data.MomentInputs
import org.openstrap.app.data.MomentResolver
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.WidgetKind
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Weeks
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.data.settings.StrapSnapshot
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.TabCopy
import org.openstrap.app.presentation.TodayCopy
import org.openstrap.app.presentation.usualSport
import org.openstrap.protocol.services.StepTotals
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * One read of what the widgets of [kinds] show, at [now] in [zone] ([WidgetSnapshot.gather]). A section for a kind
 * the read wasn't for is null. A read that failed was logged and holds what the widget shows without it, or a failure
 * where the widget can't be drawn without it.
 */
@Suppress("LongParameterList") // One section a widget, each null when the read wasn't for it.
class WidgetData(
    val kinds: Set<WidgetKind>,
    val now: Instant,
    val zone: ZoneId,
    /** What settings keep of the strap, for the status line with the strap away, null when it couldn't be read. */
    val strap: StrapSnapshot?,
    val syncing: Boolean,
    /** What the moment is worked out from, with no usual bedtime: each widget adds its own. Null when not read. */
    val moment: Result<MomentInputs>?,
    /** When you usually go to sleep, without working out Recovery ([HealthRepository.usualBedtime]). */
    val usualBedtime: LocalTime?,
    val steps: StepsRead?,
    val start: StartRead?,
    val recovery: RecoveryRead?,
    val sleep: Result<SleepRead>?,
    val momentWidget: Result<MomentWidgetInput>?,
) {
    /** The moment's inputs with [bedtime], null when they couldn't be read. */
    fun momentInputs(bedtime: LocalTime?): MomentInputs? = moment?.getOrNull()?.copy(usualBedtime = bedtime)
}

/** The Steps widget's reads: the [goal], and the [day]'s steps, null when they failed. */
class StepsRead(val goal: Result<Int>, val day: StepsToday?)

/** Today's minutes, its steps with the strap's live total on top ([LiveSteps]) and this week's earlier days' totals. */
class StepsToday(val minutes: List<ActivityMinute>, val steps: Int, val past: Map<LocalDate, Int>)

/**
 * The Start widget's reads: the listed [workouts] and [sides] (null and empty when they failed), whether [location]
 * is on, the workout's [state] then.
 */
class StartRead(val workouts: List<WorkoutAnalysis>?, val sides: Map<Int, Boolean>, val location: Boolean, val state: WorkoutState)

/** The Recovery widget's reads: [today] and, while its score isn't in, [yesterday], null where it wasn't worked out. */
class RecoveryRead(val todayResult: RecoveryResult?, val today: RecoveryDay?, val yesterday: RecoveryDay?)

/** The Sleep widget's reads, as [SleepWidgetModel.from] takes them. */
class SleepRead(
    val tonight: SleepWidgetNight?,
    val yesterday: SleepWidgetNight?,
    val firstNight: Boolean,
    val napsToday: Boolean,
    val deleted: Boolean,
)

/**
 * Reads what the widgets show, once per refresh for every kind it's asked for (audit A4): the same live steps
 * ([LiveSteps], the one the app keeps), main night ([SleepHistory.mainSleep]) and Recovery
 * ([HealthRepository.recovery]) as Today. Widgets that share a read, like the workouts behind the moment and the
 * Moment widget, read it once.
 */
// The strap's and the workout's state now come in as functions, so a read can be checked without either.
@Suppress("LongParameterList")
class WidgetSnapshot(
    private val health: HealthRepository,
    private val dao: HealthDao,
    private val settings: SettingsStore,
    private val liveSteps: LiveSteps,
    /** Whether the strap is syncing now. */
    private val syncing: () -> Boolean,
    /** The strap's live step count now. */
    private val todaySteps: () -> StepTotals?,
    /** The workout's state now. */
    private val workoutState: () -> WorkoutState,
    /** Whether the phone's location can be used now. */
    private val location: () -> Boolean,
    /** The widgets placed now. */
    private val placed: () -> Set<WidgetKind>,
) {
    suspend fun gather(kinds: Set<WidgetKind>, now: Instant, zone: ZoneId): WidgetData {
        val today = now.atZone(zone).toLocalDate()
        val t = now.epochSecond
        val strap = catching { settings.strapSnapshot.first() }.onFailure { logUnread("strap status", it) }.getOrNull()
        val workouts = if (kinds.any { it in MOOD_KINDS }) catching { recentWorkouts(t) } else null
        val span = if (kinds.any { it in NIGHT_KINDS }) catching { sleepSpanSessions(today, zone) } else null
        val moment = workouts?.let { w -> catching { momentInputs(w.getOrThrow(), span?.getOrNull(), today, t, zone) } }
        val profile = if (kinds.any { it in RECOVERY_KINDS }) catching { settings.profile.first() } else null
        val rec = profile?.let { p ->
            catching { health.recovery(today, p.getOrThrow()) }.onFailure { logUnread("Recovery for $today", it) }.getOrNull()
        }
        val bedtime = if (bedtimeNeeded(kinds, rec)) usualBedtime(today) else null
        val goal = if (kinds.any { it in STEP_KINDS }) catching { settings.goals.first().steps } else null
        val day = goal?.let { stepsToday(today, zone, past = WidgetKind.Steps in kinds) }
        val reads = Reads(today, t, zone, workouts, span, moment, rec, bedtime, goal, day)
        return WidgetData(
            kinds = kinds,
            now = now,
            zone = zone,
            strap = strap,
            syncing = syncing(),
            moment = moment,
            usualBedtime = bedtime,
            steps = goal?.takeIf { WidgetKind.Steps in kinds }?.let { StepsRead(it, day) },
            start = if (WidgetKind.Start in kinds) start() else null,
            recovery = profile?.takeIf { WidgetKind.Recovery in kinds }?.let { recovery(today, rec, it, zone) },
            sleep = span?.takeIf { WidgetKind.Sleep in kinds }?.let { s ->
                catching { sleep(s.getOrThrow(), today, zone) }.onFailure { logUnread("sleep for $today", it) }
            },
            momentWidget = if (WidgetKind.Moment in kinds) catching { momentWidget(reads) } else null,
        )
    }

    /** What one gather already read, for the Moment widget, which reads on from it. */
    private class Reads(
        val today: LocalDate, val t: Long, val zone: ZoneId,
        val workouts: Result<RecentWorkouts>?, val span: Result<List<SleepSession>>?, val moment: Result<MomentInputs>?,
        val recovery: RecoveryResult?, val bedtime: LocalTime?, val goal: Result<Int>?, val day: StepsToday?,
    )

    private suspend fun usualBedtime(today: LocalDate): LocalTime? =
        catching { health.usualBedtime(today) }.onFailure { logUnread("usual bedtime", it) }.getOrNull()

    /** The sleep over the Sleep tab's nights up to [today]'s ([sleepSpan]). */
    private suspend fun sleepSpanSessions(today: LocalDate, zone: ZoneId): List<SleepSession> =
        sleepSpan(today, zone).let { health.sleep.sessionsOnce(it.from, it.to) }

    /** The strap's and the phone's workouts over the usual workout time's look-back, less the strap's record of a phone workout. */
    private suspend fun recentWorkouts(t: Long): RecentWorkouts {
        val from = t - MomentResolver.USUAL_WORKOUT_DAYS * 86_400L
        val phone = dao.appWorkoutsOnce(from, t)
        // The strap's record of a phone workout is that workout, as in the lists ([Workouts.sameWorkout]).
        val strap = dao.workoutsOnce(from, t).filter { Workouts.phoneTwin(it, phone, t) == null }
        return RecentWorkouts(strap, phone)
    }

    /**
     * What [MomentResolver] reads, from the database rather than Today's screen, so a widget follows the same moment as
     * Today (widgets spec 2.2): whether a workout is recording, the last workout's end, today's main sleep's end (from
     * [span] when it was read), and the start of every workout you started. The usual bedtime is each widget's own.
     */
    private suspend fun momentInputs(w: RecentWorkouts, span: List<SleepSession>?, today: LocalDate, t: Long, zone: ZoneId): MomentInputs {
        val starts = w.strap.filter(::startedByYou).map { it.start } +
            w.phone.filter { it.sportType != Workouts.GENERIC_ACTIVITY }.map { it.start }
        val lastEnd = (w.strap.map { it.end } + w.phone.mapNotNull { it.end }).filter { it <= t }.maxOrNull()
        // Today's main sleep, as Today picks it.
        val night = SleepHistory.endingOn(today, zone)
        val sessions = span ?: health.sleep.sessionsOnce(night.from, night.to)
        return MomentInputs(
            day = today,
            recording = workoutState() is WorkoutState.Recording,
            lastWorkoutEnd = lastEnd,
            sleepEnd = SleepHistory.mainSleep(sessions, night)?.end,
            usualBedtime = null,
            workoutStarts = starts.sorted(),
        )
    }

    /** Today's minutes and steps as Today's Steps card counts them, and with [past] this week's earlier days. */
    private suspend fun stepsToday(today: LocalDate, zone: ZoneId, past: Boolean): StepsToday? = catching {
        val day = DayWindow.of(today, zone)
        val minutes = dao.activityOnce(day.from, day.to)
        val steps = liveSteps.today(today, Summaries.totalSteps(minutes), todaySteps())
        val weekStart = Weeks.start(today)
        val earlier = if (past && weekStart < today) {
            health.daily(DailyMetric.Steps, weekStart, today.minusDays(1), zone).first()
                .filter { it.date < today }.associate { it.date to it.value.roundToInt() }
        } else emptyMap()
        StepsToday(minutes, steps, earlier)
    }.onFailure { logUnread("steps for $today", it) }.getOrNull()

    /** Every workout as the workout lists read them ([Workouts.listed]), the sides last chosen and whether location is on. */
    private suspend fun start(): StartRead {
        val state = workoutState()
        val workouts = catching {
            Workouts.listed(
                dao.workouts().first(), dao.sportEffects().first(), dao.lastWorkoutHeartRates().first(), dao.appWorkouts().first(),
                dao.setCounts().first().associate { it.workoutStart to it.sets }, LIST_MAX_HR,
            ) to settings.workoutSides.first()
        }.onFailure { logUnread("workouts for Start", it) }.getOrNull()
        return StartRead(workouts?.first, workouts?.second.orEmpty(), workouts != null && location(), state)
    }

    /** Today's Recovery with the strap's score, and yesterday's while this morning's isn't in. */
    private suspend fun recovery(today: LocalDate, result: RecoveryResult?, profile: Result<UserProfile>, zone: ZoneId): RecoveryRead {
        suspend fun day(date: LocalDate, known: RecoveryResult?): RecoveryDay? = catching {
            val r = known ?: health.recovery(date, profile.getOrThrow())
            val dayStart = DayWindow.of(date, zone).from
            RecoveryDay.of(r, dao.strapReadinessOnce(dayStart, dayStart).firstOrNull()?.score)
        }.onFailure { logUnread("Recovery for $date", it) }.getOrNull()
        val first = result?.let { day(today, it) }
        val yesterday = if (first?.shown?.value == null) day(today.minusDays(1), null) else null
        return RecoveryRead(result, first, yesterday)
    }

    /**
     * This morning's night as the Sleep tab reads it ([SleepHistory.mainSleep] over the night ending today, its stages,
     * the week's other nights from [SleepHistory.mainSleeps] over the tab's nights), and yesterday's while this
     * morning's isn't synced.
     */
    private suspend fun sleep(sessions: List<SleepSession>, today: LocalDate, zone: ZoneId): SleepRead {
        val nights = SleepHistory.mainSleeps(sessions, zone)
        suspend fun night(date: LocalDate): SleepWidgetNight? =
            SleepHistory.mainSleep(sessions, SleepHistory.endingOn(date, zone))?.let { main ->
                SleepWidgetNight.of(date, main, health.sleep.stagesOnce(main), nights)
            }
        val tonight = night(today)
        // A night the user deleted is this morning's answer: yesterday's doesn't stand in for it.
        val deleted = tonight == null && SleepHistory.endingOn(today, zone).let { w -> health.sleep.nightDeleted(w.from, w.to) }
        val yesterday = if (tonight == null && !deleted) night(today.minusDays(1)) else null
        val first = tonight == null && yesterday == null && !deleted && nights.isEmpty() && dao.latestSleepEnd().first() == null
        val naps = sessions.any { it.nap && SleepHistory.nightOf(it, zone) == today }
        return SleepRead(tonight, yesterday, first, naps, deleted)
    }

    /**
     * Everything Today's hero reads: the moment's inputs, today's Recovery and Energy, last night as the Sleep tab reads
     * it, the workouts, the day's heart rate and steps.
     */
    private suspend fun momentWidget(r: Reads): MomentWidgetInput {
        val (today, t, zone) = Triple(r.today, r.t, r.zone)
        val w = checkNotNull(r.workouts).getOrThrow()
        val base = checkNotNull(r.moment).getOrThrow()
        val rec = r.recovery
        val stepsDay = r.day
        suspend fun <T> read(what: String, default: T, block: suspend () -> T): T =
            catching { block() }.onFailure { logUnread("$what for Moment", it) }.getOrDefault(default)
        val inputs = base.copy(usualBedtime = rec?.usualBedtime ?: r.bedtime)
        val dayStart = DayWindow.of(today, zone).from
        val strapScore = read("the strap's score", null) { dao.strapReadinessOnce(dayStart, dayStart).firstOrNull()?.score }
        val night = read("last night", null) {
            val sessions = checkNotNull(r.span).getOrThrow()
            SleepHistory.mainSleep(sessions, SleepHistory.endingOn(today, zone))?.let { main ->
                SleepWidgetNight.of(today, main, health.sleep.stagesOnce(main), SleepHistory.mainSleeps(sessions, zone))
            }
        }
        val yours = w.strap.filter(::startedByYou).map { it.sportType to it.start } +
            w.phone.filter { it.sportType != Workouts.GENERIC_ACTIVITY }.map { it.sportType to it.start }
        val all = w.strap.map { it.sportType to it.start } + w.phone.map { it.sportType to it.start }
        val usual = usualSport(yours) ?: usualSport(all.filter { it.first != Workouts.GENERIC_ACTIVITY }) ?: Workouts.DEFAULT_SPORTS.first()
        val ended = w.strap.map { MomentWorkout(it.sportType, it.end) } +
            w.phone.mapNotNull { p -> p.end?.let { MomentWorkout(p.sportType, it) } }
        val last = ended.filter { it.end <= t }.maxByOrNull { it.end }
        val day = DayWindow.of(today, zone)
        val dayWorkouts = (w.strap.map { it.start to it.sportType } + w.phone.map { it.start to it.sportType })
            .filter { (start, sport) -> start in day.from..day.to && sport != Workouts.GENERIC_ACTIVITY }
            .sortedBy { it.first }.map { it.second }
        val recording = (workoutState() as? WorkoutState.Recording)?.let { MomentRecordingInput(it.workout.sportType, it.workout.start) }
        val minutes = stepsDay?.minutes.orEmpty()
        val hr = read("heart rate", emptyList()) { dao.heartRateOnce(day.from, day.to) }
        val series = Summaries.heartRateSeries(hr, minutes)
        val heart = Summaries.bucketAverage(series).map { ChartPoint(it.first, it.second.toFloat()) }
        val resting = read("resting heart rate", null) { dao.restingHrOnce(day.from, day.to).firstOrNull()?.bpm }
            ?: if (series.size >= RESTING_ESTIMATE_MINUTES) Summaries.estimateRestingHr(series) else null
        return MomentWidgetInput(
            inputs = inputs,
            placed = WidgetRefresh.placedOverride ?: placed(),
            recovery = rec?.let { TodayCopy.recoveryShown(it.readiness, strapScore, TabCopy.RECOVERY_WAITING) },
            score = rec?.readiness?.score,
            alert = rec?.readiness?.strainAlert == true,
            noScore = TodayCopy.noScore(rec?.readiness),
            firstNights = rec?.charge?.anchored != true,
            energy = rec?.charge?.let(MomentEnergy::of),
            load = rec?.load,
            loadStatus = rec?.let { TodayCopy.loadStatus(it.load, it.chronicLoad, endOfDay = false) },
            night = night,
            usualSport = usual,
            lastWorkout = last,
            recording = recording,
            dayWorkouts = dayWorkouts,
            heartRate = heart,
            restingHr = resting,
            // A failed read leaves the shared anchor alone: anchoring on 0 would throw off Today's count too. The day
            // check still applies, so yesterday's last reading counts for nothing after midnight.
            steps = stepsDay?.steps ?: liveSteps.current(today, todaySteps())?.takeIf { it.isPlausible }?.steps ?: 0,
            stepGoal = checkNotNull(r.goal).getOrThrow(),
            nightDeleted = night == null && read("a deleted night", false) {
                SleepHistory.endingOn(today, zone).let { n -> health.sleep.nightDeleted(n.from, n.to) }
            },
        )
    }

    private class RecentWorkouts(val strap: List<Workout>, val phone: List<AppWorkout>)

    companion object {
        /** The widgets lit in the moment's mood ([widgetMood]), and Moment, which is the moment. */
        private val MOOD_KINDS = setOf(WidgetKind.Steps, WidgetKind.Start, WidgetKind.Recovery, WidgetKind.Moment)

        /** The widgets that read the nights, those that read Recovery and those that read today's steps. */
        private val NIGHT_KINDS = setOf(WidgetKind.Sleep, WidgetKind.Moment)
        private val RECOVERY_KINDS = setOf(WidgetKind.Recovery, WidgetKind.Moment)
        private val STEP_KINDS = setOf(WidgetKind.Steps, WidgetKind.Moment)

        /** Nights the Sleep tab reads for the week's other nights, as Today's sleep (`ui.dashboard.SLEEP_NIGHTS`). */
        internal const val SLEEP_NIGHTS = 14

        /** From the first of the [SLEEP_NIGHTS] nights to the end of the one ending on [today] ([SleepHistory.endingOn]). */
        internal fun sleepSpan(today: LocalDate, zone: ZoneId) =
            DayWindow(SleepHistory.endingOn(today.minusDays(SLEEP_NIGHTS - 1L), zone).from, SleepHistory.endingOn(today, zone).to)

        /**
         * The max heart rate the lists' zones are shares of. The widget shows no zones, so it skips the app's 90-day
         * read for it ([org.openstrap.app.data.RecoveryEngine.maxHeartRate]): nothing it shows depends on it.
         */
        private const val LIST_MAX_HR = 190

        /** Our own resting estimate needs most of a day's minutes, as Today's. */
        private const val RESTING_ESTIMATE_MINUTES = 6 * 60
    }
}

/** Steps and Start light their mood by the usual bedtime, and Moment when Recovery didn't give one. */
private fun bedtimeNeeded(kinds: Set<WidgetKind>, rec: RecoveryResult?): Boolean =
    WidgetKind.Steps in kinds || WidgetKind.Start in kinds || (WidgetKind.Moment in kinds && rec?.usualBedtime == null)

/** You started it: not one the strap noticed or filed as "Activity" by itself (WorkoutAnalysis.startedByYou). */
private fun startedByYou(w: Workout) = w.subtype != Workouts.SUBTYPE_AUTO_DETECTED && w.sportType != Workouts.GENERIC_ACTIVITY

private fun logUnread(what: String, e: Throwable) = Log.w(WIDGET_TAG, "widgets: $what not read: $e")
