package org.openstrap.app.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openstrap.app.data.DayData
import org.openstrap.app.data.DayRepository
import org.openstrap.app.data.DayValue
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.Today
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.data.db.OsaEventRow
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.StageSpan
import org.openstrap.app.presentation.TrendPoint
import java.time.Clock
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.presentation.Range

/** Nights of sleep loaded for the week view and the in-sleep vitals' usual values. */
const val SLEEP_NIGHTS = SleepHistory.NIGHTS

/** The data layer's, kept under its old name for the screens. */
typealias Baselines = org.openstrap.app.data.Baselines

/** The data layer's, kept under its old name for the screens. */
typealias SleepNight = org.openstrap.app.data.SleepNight

data class DayUiState(
    val date: LocalDate = LocalDate.now(),
    val steps: Int = 0,
    /** Steps summed from the per-minute records; [steps] can be higher today from the strap's own running total. */
    val minuteSteps: Int = 0,
    val hourlySteps: List<Float> = List(24) { 0f },
    val activeHours: Int = 0,
    val heartRate: List<ChartPoint> = emptyList(),
    val latestHeartRate: Int? = null,
    val restingHeartRate: Int? = null,
    /** True when [restingHeartRate] is our own estimate rather than the strap's value. */
    val restingEstimated: Boolean = false,
    val restingTrend: List<TrendPoint> = emptyList(),
    val hrvTrend: List<TrendPoint> = emptyList(),
    /** The main sleep, carrying the strap's own score and stage totals when it reported them. */
    val sleep: SleepSession? = null,
    /** The strap's score for the night before, to show which way sleep moved. */
    val previousSleepScore: Int? = null,
    val naps: List<SleepSession> = emptyList(),
    /** Each nap's stages and totals, keyed by the nap's start. */
    val napStages: Map<Long, List<StageSpan>> = emptyMap(),
    val napBreakdowns: Map<Long, Summaries.SleepBreakdown> = emptyMap(),
    /** Each nap as the strap saw it, keyed by the nap's start as shown (the user's own when they changed its times). */
    val napsAsDetected: Map<Long, SleepSession> = emptyMap(),
    /** The main sleep as the strap saw it, when there's one ([sleep] has the user's times when they changed them). */
    val sleepAsDetected: SleepSession? = null,
    /** The night's stages, clipped to [sleep]'s times. */
    val sleepStages: List<StageSpan> = emptyList(),
    /** The strap's whole stages when the user moved the main sleep, so the chart can dim what's left out; else empty. */
    val strapSleepStages: List<StageSpan> = emptyList(),
    /** The night's main sleep the user deleted, when there's no other ([sleep] is null then), for its Restore. */
    val deletedSleep: SleepEdits.Resolved? = null,
    /** Every sleep and nap as shown around the day, from the night before to tonight: what an edit mustn't run into. */
    val sleepNeighbours: List<SleepSession> = emptyList(),
    val sleepBreakdown: Summaries.SleepBreakdown? = null,
    val spo2: List<ChartPoint> = emptyList(),
    val stress: List<ChartPoint> = emptyList(),
    val hrv: List<ChartPoint> = emptyList(),
    val skinTemperature: List<ChartPoint> = emptyList(),
    val breathRate: List<ChartPoint> = emptyList(),
    val pai: PaiSample? = null,
    val baselines: Baselines = Baselines(),
    /** Blood oxygen measured during the night's sleep (type 38), separate from daytime [spo2]. */
    val sleepSpo2: List<ChartPoint> = emptyList(),
    /** HRV as SDNN (type 78). On a real strap these come every hour or two through the day, not once a night. */
    val hrvSdnn: List<ChartPoint> = emptyList(),
    /** The strap's sleep-apnea summary for the night; units unverified, see [OsaResultRow]. */
    val osaResult: OsaResultRow? = null,
    /** Oxygen-drop events during the night. */
    val osaEvents: List<OsaEventRow> = emptyList(),
    /** The day's highest exercise heart rate as the strap reported it. */
    val dailyMaxHeartRate: Int? = null,
    /** Distance walked from the extended activity record (cm per minute, about 75 cm a step); null without data. */
    val distanceMeters: Int? = null,
    /** Calories from the extended activity record; looks like active kcal per minute, not yet verified. */
    val activeCalories: Int? = null,
    /** Alerts the strap pushed during the day. */
    val alerts: List<HealthAlertRow> = emptyList(),
    /** The [SLEEP_NIGHTS] nights ending on this day, oldest first; nights without sleep have no session. */
    val nights: List<SleepNight> = emptyList(),
    /** Heart rate across the night window (5-minute averages), so sleep before midnight is included. */
    val sleepHeartRate: List<ChartPoint> = emptyList(),
    /** HRV and skin temperature read during the night's main sleep, before midnight too, for the night-scoped details. */
    val sleepHrv: List<ChartPoint> = emptyList(),
    val sleepSkinTemperature: List<ChartPoint> = emptyList(),
    val dayStart: Long = 0,
    val dayEnd: Long = 0,
    /** False for the empty state before the day's rows arrive, so "no sleep" isn't read into data still loading. */
    val loaded: Boolean = false,
    /** The usual time asleep in seconds over [nights] ([sleepLengthNormal]), for last night's Short or Long. */
    val sleepLengthNormal: org.openstrap.app.data.History.Normal? = null,
    /** The usual bedtime in minutes after noon over [nights] ([bedtimeNormal]), for Regularity's Later or Earlier. */
    val bedtimeNormal: org.openstrap.app.data.History.Normal? = null,
    /** The day's stress hour by hour, 0..1, null for hours without a reading or to come ([stressStrip]). */
    val stressStrip: List<Float?> = emptyList(),
) {
    val asleepSeconds: Long? get() = sleepBreakdown?.asleepSeconds ?: sleep?.let { it.end - it.start }
}

/** The day's Recovery couldn't be worked out. [yesterday] is yesterday's score, shown dimmed in its place, when known. */
data class RecoveryFailure(val date: LocalDate, val yesterday: Int?)

/** Recovery worked out, or why not. */
private sealed interface RecoveryOutcome {
    data class Ready(val result: RecoveryResult) : RecoveryOutcome
    data class Failed(val failure: RecoveryFailure) : RecoveryOutcome
}

/** One day's Heart points: those earned that day and the 7-day total. */
data class HeartPointsDay(val daily: Float, val total: Float)

/** One day's stored calories ([org.openstrap.app.data.DailyCaloriesHistory]), in kcal. */
data class CaloriesDay(val active: Float, val total: Float, val workout: Float, val partial: Boolean)

/**
 * Each day's Heart points and stored calories up to [date], for the Activity tab and its details, so they're handed
 * state rather than a way to read it (audit A11).
 */
data class ActivityHistory(
    /** The day these end on, null before the first read. */
    val date: LocalDate? = null,
    /** The [HEART_POINTS_DAYS] days ending on [date]. */
    val heartPoints: Map<LocalDate, HeartPointsDay> = emptyMap(),
    /** The [CALORIES_DAYS] days ending on [date]: the longest range and the one before it. Null while loading. */
    val calories: Map<LocalDate, CaloriesDay>? = null,
    /**
     * Each day's steps from [STEPS_DAYS] days before [date] to the end of its week, as Steps' daily history counts
     * them, days without steps absent.
     */
    val steps: Map<LocalDate, Int> = emptyMap(),
    /** The most steps of any day before [date]: the stored personal best when it's older, else read from the history. */
    val stepsBest: Int? = null,
) {
    /** [heartPoints] when they're [day]'s, else none yet. */
    fun heartPointsOn(day: LocalDate): Map<LocalDate, HeartPointsDay> = if (date == day) heartPoints else emptyMap()

    /** [calories] from [from] to [day] when it's [day]'s, else null while it loads. */
    fun caloriesOn(day: LocalDate, from: LocalDate): Map<LocalDate, CaloriesDay>? =
        calories?.takeIf { date == day }?.filterKeys { it in from..day }

    /**
     * Steps' goal facts for [day] ([stepsGoalFacts]), for `goalTag(GoalMetric.Steps, facts)`: [value] is the steps
     * shown (today's live count) and [goal] the daily goal. Null while [day]'s history loads.
     */
    fun stepsFacts(day: LocalDate, value: Int, goal: Int): GoalFacts? =
        if (date == day) stepsGoalFacts(day, value, goal, steps, stepsBest) else null

    /** [day]'s week of steps for Your week ([stepsWeek]), null while [day]'s history loads. */
    fun stepsWeek(
        day: LocalDate, value: Int, goal: Int, today: LocalDate = day, firstDay: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY,
    ): StepsWeek? = if (date == day) org.openstrap.app.ui.dashboard.stepsWeek(day, value, goal, steps, today, firstDay) else null

    companion object {
        /** Days of steps before the day read for Steps' goal tags: the month's best and a streak up to this long. */
        const val STEPS_DAYS = 90

        /** Heart points' month. */
        const val HEART_POINTS_DAYS = 30

        /** Calories' year and the year before it, for the change against it. */
        val CALORIES_DAYS = 2 * Range.Year.days
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(
    private val repo: DayRepository,
    profile: Flow<UserProfile> = flowOf(UserProfile()),
    /** A sleep or nap (by the strap's start) was moved, deleted or restored: Health Connect is written again. */
    private val onSleepEdited: (Long) -> Unit = {},
    /** The clock today is read from, asked afresh each time. Tests pass one they move by hand. */
    private val clock: () -> Clock = { Clock.systemDefaultZone() },
    /** The phone's clock, time zone or date changed ([Today.changes]), so today is read again at once. */
    dateChanges: Flow<Unit> = emptyFlow(),
    /** Where the day's rows are worked into its state, off the main thread (audit C7). */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    /** Today as it moves, so a screen left open past midnight moves on to the new day (audit C10). */
    private val today: StateFlow<LocalDate> = Today.dates(clock, dateChanges)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Today.on(clock()))

    /** The day picked, or null while the screen follows today. */
    private val picked = MutableStateFlow<LocalDate?>(null)

    /** The day shown: the one picked while it's before today, else today. */
    private val shown: Flow<ShownDay> = combine(picked, today) { p, t ->
        val date = p?.takeIf { it < t } ?: t
        ShownDay(date, date == t)
    }.distinctUntilChanged()

    private val selected: Flow<LocalDate> = shown.map { it.date }.distinctUntilChanged()

    private fun shownNow(): LocalDate = Today.on(clock()).let { t -> picked.value?.takeIf { it < t } ?: t }

    fun previousDay() { picked.value = shownNow().minusDays(1) }
    fun nextDay() { picked.value = shownNow().plusDays(1).takeIf { it < Today.on(clock()) } }
    fun select(date: LocalDate) { picked.value = date.takeIf { it < Today.on(clock()) } }

    /** Days the calendar lets you pick. */
    val daysWithData: StateFlow<Set<LocalDate>> = repo.daysWithData()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val state: StateFlow<DayUiState> = shown.flatMapLatest { (date, isToday) -> repo.day(date, isToday) }
        .map(::toUiState)
        .flowOn(work)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState(date = Today.on(clock())))

    /** Bumped by [retryRecovery], so the same day and data are worked out again. */
    private val recoveryRetries = MutableStateFlow(0)

    /** Works out the day's Recovery again, after it failed ([recoveryFailure]). */
    fun retryRecovery() { recoveryRetries.value++ }

    /** Keeps [start] to [end] as the times of the strap's sleep or nap [strap]. */
    fun setSleepTimes(strap: SleepSession, start: Long, end: Long) {
        viewModelScope.launch { repo.setSleepTimes(strap, start, end); onSleepEdited(strap.start) }
    }

    /** Deletes the strap's sleep or nap [strap]; [restoreSleep] brings it back. */
    fun deleteSleep(strap: SleepSession) {
        viewModelScope.launch { repo.deleteSleep(strap); onSleepEdited(strap.start) }
    }

    /** Brings back the strap's deleted sleep or nap [strap], as it was: Undo and Restore. */
    fun restoreSleep(strap: SleepSession) {
        viewModelScope.launch { repo.restoreSleep(strap); onSleepEdited(strap.start) }
    }

    /** Sleep and naps deleted in the last 7 days, newest first, for You, Your data, Deleted sleep. */
    val deletedSleep: StateFlow<List<SleepEdits.Resolved>> = repo.deletedSleep()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The last Recovery worked out, for yesterday's score when today's fails. */
    @Volatile private var lastRecovery: RecoveryResult? = null

    /**
     * Readiness and charge, recomputed (off the main thread) when the day, profile or synced data change: the result,
     * or why there's none. Null while the first is worked out.
     */
    @OptIn(FlowPreview::class)
    private val recoveryOutcome: StateFlow<RecoveryOutcome?> =
        combine(selected, profile, repo.changes().debounce(800), recoveryRetries) { d, p, _, _ -> d to p }
            .mapLatest { (d, p) ->
                withContext(work) {
                    // mapLatest cancels this when newer data arrives. That's not a failure, and catching lets it through.
                    catching { repo.recovery(d, p) }.fold(
                        onSuccess = { RecoveryOutcome.Ready(it.also { r -> lastRecovery = r }) },
                        onFailure = { e ->
                            Log.w("OpenStrap", "recovery failed", e)
                            RecoveryOutcome.Failed(RecoveryFailure(d, yesterdayScore(d, p)))
                        },
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Readiness and charge for the day shown; null while loading or when it failed ([recoveryFailure] tells which). */
    val recovery: StateFlow<RecoveryResult?> = recoveryOutcome.map { (it as? RecoveryOutcome.Ready)?.result }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The day's Recovery failed to work out (spec 4.3), so the cards can say so instead of waiting forever. */
    val recoveryFailure: StateFlow<RecoveryFailure?> = recoveryOutcome.map { (it as? RecoveryOutcome.Failed)?.failure }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Yesterday's score for a failed [date]: from the last Recovery worked out when its week covers yesterday, else
     * worked out for yesterday itself; null when that fails too.
     */
    private suspend fun yesterdayScore(date: LocalDate, profile: UserProfile): Int? {
        val yesterday = date.minusDays(1)
        lastRecovery?.let { last ->
            if (last.date == yesterday) return last.readiness.score
            last.readinessTrend.firstOrNull { it.first == yesterday }?.let { return it.second }
        }
        return catching { repo.recovery(yesterday, profile).readiness.score }
            .onFailure { Log.w("OpenStrap", "yesterday's recovery failed", it) }
            .getOrNull()
    }

    /**
     * Your usual steps by the end of each hour ([org.openstrap.app.data.UsualSteps]), from the 28 days before the day
     * shown; null until there are enough worn days. Past days don't change through the day, so it's read once per day
     * shown, off the main thread, not on every sync.
     */
    val usualSteps: StateFlow<List<Float>?> = selected
        .mapLatest { d ->
            withContext(work) {
                catching {
                    val from = d.minusDays(org.openstrap.app.data.UsualSteps.DAYS.toLong())
                    org.openstrap.app.data.UsualSteps.byHour(repo.hourlySteps(from, d.minusDays(1)), d)
                }.onFailure { Log.w("OpenStrap", "usual steps failed", it) }.getOrNull()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The weekly Healthspan estimate for the selected day, or why it's withheld; null while loading or on failure. */
    @OptIn(FlowPreview::class)
    val healthspan: StateFlow<org.openstrap.app.data.Healthspan.Outcome?> =
        combine(selected, profile, repo.changes().debounce(800)) { d, p, _ -> d to p }
            .mapLatest { (d, p) ->
                withContext(work) {
                    catching { repo.healthspan(d, p) }.onFailure { Log.w("OpenStrap", "healthspan failed", it) }.getOrNull()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Each day's Heart points and stored calories up to the day shown, for the Activity tab and its details. */
    val activityHistory: StateFlow<ActivityHistory> = selected.flatMapLatest { d ->
        combine(
            repo.dailyPai(d.minusDays(ActivityHistory.HEART_POINTS_DAYS - 1L), d),
            repo.dailyCalories(d.minusDays(ActivityHistory.CALORIES_DAYS - 1L), d),
            stepsAndBest(d),
        ) { pai, calories, (steps, best) ->
            ActivityHistory(
                date = d,
                heartPoints = pai.mapValues { (_, p) -> HeartPointsDay(p.daily, p.total) },
                calories = calories.mapValues { (_, e) -> CaloriesDay(e.activeKcal, e.totalKcal, e.workoutKcal, e.partial) },
                steps = steps,
                stepsBest = best,
            )
        }
    }
        .flowOn(work)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityHistory())

    /**
     * [d]'s steps from [ActivityHistory.STEPS_DAYS] days before it to its week's end, and the most steps of a day
     * before it: the stored personal best when it's from an earlier day (with the loaded days, as it can lag a sync),
     * else read from the whole history, as the stored one is then [d]'s or later.
     */
    private fun stepsAndBest(d: LocalDate): Flow<Pair<Map<LocalDate, Int>, Int?>> = combine(
        repo.dailySteps(d.minusDays(ActivityHistory.STEPS_DAYS.toLong()), d.plusDays(6)),
        repo.personalBest(org.openstrap.app.data.Rewards.Kind.MOST_STEPS),
    ) { steps, stored -> steps to stored }
        .mapLatest { (steps, stored) ->
            val loaded = steps.filterKeys { it < d }.values.filter { it > 0 }.maxOrNull()
            val storedDay = stored?.let { java.time.Instant.ofEpochSecond(it.date).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
            val best = if (stored != null && storedDay != null && storedDay < d) {
                listOfNotNull(stored.value.toInt(), loaded).maxOrNull()
            } else {
                catching { repo.mostStepsBefore(d) }.onFailure { Log.w("OpenStrap", "best steps failed", it) }.getOrNull() ?: loaded
            }
            steps to best
        }

    companion object {
        /**
         * [data] as the screens show it: the heart rate as a chart, the steps by hour, the trends with their weekday
         * labels and the stages as spans.
         */
        internal fun toUiState(data: DayData): DayUiState {
            val label = { d: LocalDate -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2) }
            fun List<DayValue>.trend() = map { TrendPoint(label(it.date), it.value) }
            fun List<SleepStage>.spans() = map { StageSpan(it.start, it.end, it.stage) }
            val sleep = data.sleep
            val series = Summaries.heartRateSeries(data.heartRate, data.minutes)
            val hourly = Summaries.hourlySteps(data.minutes, data.day.from)
            return DayUiState(
                loaded = true,
                date = data.date,
                steps = data.steps,
                minuteSteps = Summaries.totalSteps(data.minutes),
                hourlySteps = hourly,
                activeHours = Summaries.activeHours(hourly),
                heartRate = Summaries.bucketAverage(series).map { ChartPoint(it.first, it.second.toFloat()) },
                latestHeartRate = series.lastOrNull()?.second,
                restingHeartRate = data.restingTrend.lastOrNull()?.value?.toInt(),
                restingEstimated = data.restingEstimated.lastOrNull() ?: false,
                restingTrend = data.restingTrend.trend(),
                hrvTrend = data.hrvTrend.trend(),
                sleep = sleep.main,
                previousSleepScore = sleep.previousScore,
                naps = sleep.naps,
                napStages = sleep.napStages.mapValues { (_, st) -> st.spans() },
                napBreakdowns = sleep.napStages.filterValues { it.isNotEmpty() }.mapValues { (_, st) -> Summaries.sleepBreakdown(st) },
                napsAsDetected = sleep.napsAsDetected,
                sleepAsDetected = sleep.mainAsDetected,
                strapSleepStages = sleep.strapStages.spans(),
                deletedSleep = sleep.deletedMain,
                sleepNeighbours = sleep.neighbours,
                sleepStages = sleep.mainStages.spans(),
                sleepBreakdown = if (sleep.mainStages.isEmpty()) null else Summaries.sleepBreakdown(sleep.mainStages),
                spo2 = data.spo2.map { ChartPoint(it.timestamp, it.percent.toFloat()) },
                stress = data.stress.map { ChartPoint(it.timestamp, it.value.toFloat()) },
                hrv = data.hrv.map { ChartPoint(it.timestamp, it.milliseconds.toFloat()) },
                skinTemperature = data.skin.map { ChartPoint(it.timestamp, it.celsius) },
                breathRate = data.breath.map { ChartPoint(it.timestamp, it.perMinute.toFloat()) },
                pai = data.pai,
                baselines = data.baselines,
                sleepSpo2 = data.sleepSpo2.map { ChartPoint(it.timestamp, it.percent.toFloat()) },
                hrvSdnn = data.hrvSdnn.map { ChartPoint(it.timestamp, it.milliseconds) },
                osaResult = data.osaResult,
                osaEvents = data.osaEvents,
                dailyMaxHeartRate = data.dailyMaxHeartRate,
                distanceMeters = data.distanceMeters,
                activeCalories = data.activeCalories,
                alerts = data.alerts,
                nights = data.nights,
                sleepHeartRate = Summaries.bucketAverage(Summaries.heartRateSeries(data.nightHeartRate, emptyList())).map {
                    ChartPoint(it.first, it.second.toFloat())
                },
                sleepHrv = data.sleepHrv.map { ChartPoint(it.timestamp, it.milliseconds.toFloat()) },
                sleepSkinTemperature = data.sleepSkin.map { ChartPoint(it.timestamp, it.celsius) },
                dayStart = data.day.from,
                dayEnd = data.day.to,
                sleepLengthNormal = sleepLengthNormal(data.nights, data.date),
                bedtimeNormal = bedtimeNormal(data.nights, data.date),
                stressStrip = stressStrip(data.stress.map { ChartPoint(it.timestamp, it.value.toFloat()) }, data.day.from, data.day.to),
            )
        }

        val SLEEP_STAGE_ROWS = mapOf(
            SleepStage.AWAKE to 0,
            SleepStage.REM to 1,
            SleepStage.LIGHT to 2,
            SleepStage.DEEP to 3,
        )
    }
}

/** The day shown, and whether it's today, so a day picked in the past isn't read again at midnight. */
private data class ShownDay(val date: LocalDate, val isToday: Boolean)
