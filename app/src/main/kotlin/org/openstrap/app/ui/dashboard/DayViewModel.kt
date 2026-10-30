package org.openstrap.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.settings.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.HealthRepository
import org.openstrap.app.data.LiveSteps
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.data.db.OsaEventRow
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.ui.components.ChartPoint
import org.openstrap.app.ui.components.StageSpan
import org.openstrap.app.ui.components.TrendPoint
import org.openstrap.protocol.services.StepTotals
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Nights of sleep loaded for the week view and the in-sleep vitals' usual values. */
const val SLEEP_NIGHTS = 14

/** Averages over the 7 days before the selected day; null where there's no data. */
data class Baselines(
    val restingHr: Float? = null,
    val hrv: Float? = null,
    val spo2: Float? = null,
    val stress: Float? = null,
    val skinTemperature: Float? = null,
    val breathRate: Float? = null,
)

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
    val sleepStages: List<StageSpan> = emptyList(),
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

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(
    private val repo: HealthRepository,
    private val strapSteps: Flow<StepTotals?> = flowOf(null),
    profile: Flow<UserProfile> = flowOf(UserProfile()),
) : ViewModel() {
    private val selected = MutableStateFlow(LocalDate.now())

    fun previousDay() { selected.value = selected.value.minusDays(1) }
    fun nextDay() { if (selected.value < LocalDate.now()) selected.value = selected.value.plusDays(1) }
    fun select(date: LocalDate) { selected.value = minOf(date, LocalDate.now()) }

    /** Days the calendar lets you pick. */
    val daysWithData: StateFlow<Set<LocalDate>> = repo.daysWithData()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val state: StateFlow<DayUiState> = selected.flatMapLatest { date -> stateFor(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState())

    /** Bumped by [retryRecovery], so the same day and data are worked out again. */
    private val recoveryRetries = MutableStateFlow(0)

    /** Works out the day's Recovery again, after it failed ([recoveryFailure]). */
    fun retryRecovery() { recoveryRetries.value++ }

    /** Keeps [start] to [end] as the times of the strap's nap [strap]. */
    fun setNapTimes(strap: SleepSession, start: Long, end: Long) {
        viewModelScope.launch { repo.setSleepTimes(strap, start, end) }
    }

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
                withContext(Dispatchers.Default) {
                    // mapLatest cancels this when newer data arrives; that's not a failure, so let it through.
                    try {
                        RecoveryOutcome.Ready(repo.recovery(d, p).also { lastRecovery = it })
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("OpenStrap", "recovery failed", e)
                        RecoveryOutcome.Failed(RecoveryFailure(d, yesterdayScore(d, p)))
                    }
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
        return try {
            repo.recovery(yesterday, profile).readiness.score
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Your usual steps by the end of each hour ([org.openstrap.app.data.UsualSteps]), from the 28 days before the day
     * shown; null until there are enough worn days. Past days don't change through the day, so it's read once per day
     * shown, off the main thread, not on every sync.
     */
    val usualSteps: StateFlow<List<Float>?> = selected
        .mapLatest { d ->
            withContext(Dispatchers.IO) {
                try {
                    org.openstrap.app.data.UsualSteps.byHour(repo.hourlySteps(d.minusDays(org.openstrap.app.data.UsualSteps.DAYS.toLong()), d.minusDays(1)), d)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("OpenStrap", "usual steps failed", e)
                    null
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The weekly Healthspan estimate for the selected day, or why it's withheld; null while loading or on failure. */
    @OptIn(FlowPreview::class)
    val healthspan: StateFlow<org.openstrap.app.data.Healthspan.Outcome?> =
        combine(selected, profile, repo.changes().debounce(800)) { d, p, _ -> d to p }
            .mapLatest { (d, p) ->
                withContext(Dispatchers.Default) {
                    try {
                        repo.healthspan(d, p)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("OpenStrap", "healthspan failed", e)
                        null
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun stateFor(date: LocalDate): Flow<DayUiState> {
        val zone = ZoneId.systemDefault()
        val day = DayWindow.of(date)
        val night = DayWindow.nightEndingOn(date)
        val week = DayWindow(DayWindow.of(date.minusDays(6)).from, day.to)

        val activityAndHr = combine(repo.activity(day), repo.heartRate(day)) { act, hr -> act to hr }
        val vitals = combine(repo.spo2(day), repo.stress(day), repo.hrv(day)) { spo2, stress, hrv -> Triple(spo2, stress, hrv) }
        // Sessions ending from the start of the night into early tomorrow, so tonight's evening naps are included too.
        val nightBefore = DayWindow.nightEndingOn(date.minusDays(1))
        val sessionsAndEdits = combine(repo.sleepSessions(DayWindow(nightBefore.from, day.to + 12 * 3600)), repo.sleepEdits()) { s, e -> s to e }
        val sleep = sessionsAndEdits.flatMapLatest { (sessions, edits) ->
            // Main sleep = the longest non-nap session that ended in the night window.
            val main = SleepHistory.mainSleep(sessions, night)
            val before = SleepHistory.mainSleep(sessions, nightBefore)
            // Naps belong to the calendar day they started on, not the night window (which ends at 18:00).
            // The strap's start decides the day, so moving a nap's times never moves it to another day.
            val resolved = SleepEdits.resolve(sessions.filter { it.nap && it.start in day.from..day.to }, edits).filter { !it.deleted }
            val detected = resolved.map { it.strap }
            val naps = resolved.mapNotNull { it.shown }
            val mainStages = if (main == null) flowOf(emptyList()) else repo.sleepStages(main.start)
            // Stages stay under the strap's start; they're trimmed to the times shown.
            val napStages = if (naps.isEmpty()) flowOf(emptyMap())
            else combine(detected.map { repo.sleepStages(it.start) }) { all ->
                naps.zip(all) { n, st -> n.start to SleepEdits.clip(st.toList(), n.start, n.end) }.toMap()
            }
            val asDetected = naps.zip(detected) { n, d -> n.start to d }.toMap()
            combine(mainStages, napStages) { st, ns -> SleepParts(main, st, naps, ns, before?.score, asDetected) }
        }
        val extras = combine(repo.skinTemperature(day), repo.breathRate(night), repo.latestPai(day.to)) { skin, breath, pai ->
            Triple(skin, breath, pai?.takeIf { it.timestamp >= day.from })
        }
        val weekly = combine(repo.restingHr(week.from, week.to), repo.heartRate(week), repo.activity(week), repo.hrv(week)) { resting, hr, act, hrv ->
            val days = (6 downTo 0).map { date.minusDays(it.toLong()) }
            val label = { d: LocalDate -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2) }
            val restingTrend = days.map { d ->
                val w = DayWindow.of(d, zone)
                val stored = resting.firstOrNull { it.day in w.from..w.to }?.bpm
                // Our own estimate (lowest 30-min average) only means something with most of a day's data.
                val series = Summaries.heartRateSeries(hr.filter { it.timestamp in w.from..w.to }, act.filter { it.timestamp in w.from..w.to })
                val estimated = if (series.size >= MIN_MINUTES_FOR_ESTIMATE) Summaries.estimateRestingHr(series) else null
                TrendPoint(label(d), (stored ?: estimated)?.toFloat()) to (stored == null && estimated != null)
            }
            val hrvTrend = days.map { d ->
                val w = DayWindow.of(d, zone)
                val v = hrv.filter { it.timestamp in w.from..w.to }.map { it.milliseconds }
                TrendPoint(label(d), if (v.isEmpty()) null else v.average().toFloat())
            }
            restingTrend to hrvTrend
        }
        val previous = DayWindow(DayWindow.of(date.minusDays(7)).from, DayWindow.of(date.minusDays(1)).to)
        val baselines = combine(
            combine(repo.restingHr(previous.from, previous.to), repo.hrv(previous), repo.spo2(previous)) { r, h, s -> Triple(r, h, s) },
            combine(repo.stress(previous), repo.skinTemperature(previous), repo.breathRate(previous)) { st, sk, br -> Triple(st, sk, br) },
        ) { (resting, hrv, spo2), (stress, skin, breath) ->
            fun List<Number>.mean() = if (isEmpty()) null else map { it.toFloat() }.average().toFloat()
            Baselines(
                restingHr = resting.map { it.bpm }.mean(),
                hrv = hrv.map { it.milliseconds }.mean(),
                spo2 = spo2.map { it.percent }.mean(),
                stress = stress.map { it.value }.mean(),
                skinTemperature = skin.map { it.celsius }.mean(),
                breathRate = breath.map { it.perMinute }.mean(),
            )
        }
        val sleepAndExtras = combine(sleep, extras, baselines) { s, e, b -> Triple(s, e, b) }
        val more = combine(
            combine(repo.sleepSpo2(night), repo.hrvSdnn(day), repo.osaEvents(night)) { a, b, c -> Triple(a, b, c) },
            repo.osaResult(date),
            repo.dailyMaxHr(day),
            repo.activityExtended(day),
            repo.healthAlerts(day),
        ) { (sleepSpo2, sdnn, events), osa, maxHr, extended, alerts ->
            MoreParts(
                sleepSpo2 = sleepSpo2.map { ChartPoint(it.timestamp, it.percent.toFloat()) },
                hrvSdnn = sdnn.map { ChartPoint(it.timestamp, it.milliseconds) },
                osaResult = osa,
                osaEvents = events,
                dailyMaxHeartRate = maxHr?.bpm,
                distanceMeters = extended.takeIf { it.isNotEmpty() }?.sumOf { it.distanceCm.toLong() }?.let { (it / 100).toInt() },
                activeCalories = extended.takeIf { it.isNotEmpty() }?.sumOf { it.calories },
                alerts = alerts,
            )
        }

        val span = DayWindow(DayWindow.nightEndingOn(date.minusDays(SLEEP_NIGHTS - 1L), zone).from, night.to)
        val nights = combine(
            repo.sleepSessions(span),
            combine(repo.hrv(span), repo.skinTemperature(span)) { h, s -> h to s },
            combine(repo.sleepSpo2(span), repo.breathRate(span)) { o, b -> o to b },
            repo.heartRate(night),
        ) { sessions, (hrv, skin), (spo2, breath), hr ->
            val main = SleepHistory.mainSleeps(sessions, zone)
            val list = (SLEEP_NIGHTS - 1 downTo 0).map { ago ->
                val d = date.minusDays(ago.toLong())
                val s = main[d]
                // Averages over the sleep itself, not the whole night window.
                fun <T> during(samples: List<T>, time: (T) -> Long, value: (T) -> Float): Float? =
                    s?.let { w -> samples.filter { time(it) in w.start..w.end }.map(value).takeIf { it.isNotEmpty() }?.average()?.toFloat() }
                SleepNight(
                    date = d,
                    session = s,
                    hrv = during(hrv, { it.timestamp }, { it.milliseconds.toFloat() }),
                    skinTemperature = during(skin, { it.timestamp }, { it.celsius }),
                    spo2 = during(spo2, { it.timestamp }, { it.percent.toFloat() }),
                    breathing = during(breath, { it.timestamp }, { it.perMinute.toFloat() }),
                )
            }
            // The night's own readings, for the charts of the night-scoped details.
            val tonight = main[date]
            val sleepHrv = tonight?.let { w -> hrv.filter { it.timestamp in w.start..w.end }.map { ChartPoint(it.timestamp, it.milliseconds.toFloat()) } }.orEmpty()
            val sleepSkin = tonight?.let { w -> skin.filter { it.timestamp in w.start..w.end }.map { ChartPoint(it.timestamp, it.celsius) } }.orEmpty()
            NightParts(
                list, Summaries.bucketAverage(Summaries.heartRateSeries(hr, emptyList())).map { ChartPoint(it.first, it.second.toFloat()) },
                sleepHrv, sleepSkin,
            )
        }
        val moreAndNights = combine(more, nights) { m, n ->
            m.copy(nights = n.nights, sleepHeartRate = n.heartRate, sleepHrv = n.hrv, sleepSkinTemperature = n.skinTemperature)
        }

        // The strap's own running total covers steps whose minute records were already taken by another app.
        val liveSteps = if (date == LocalDate.now()) strapSteps else flowOf(null)
        val liveCounter = LiveSteps()
        val actAndLive = combine(activityAndHr, liveSteps) { a, live -> a to live }

        return combine(actAndLive, vitals, sleepAndExtras, weekly, moreAndNights) { (ah, live), (spo2, stress, hrv), (sleepParts, extraParts, base), (restingWithFlag, hrvTrend), m ->
            val (session, stages, naps, napStages) = sleepParts
            val (skin, breath, pai) = extraParts
            val restingTrend = restingWithFlag.map { it.first }
            val (act, hr) = ah
            val series = Summaries.heartRateSeries(hr, act)
            val hourly = Summaries.hourlySteps(act, day.from)
            DayUiState(
                loaded = true,
                date = date,
                steps = liveCounter.today(Summaries.totalSteps(act), live),
                minuteSteps = Summaries.totalSteps(act),
                hourlySteps = hourly,
                activeHours = Summaries.activeHours(hourly),
                heartRate = Summaries.bucketAverage(series).map { ChartPoint(it.first, it.second.toFloat()) },
                latestHeartRate = series.lastOrNull()?.second,
                restingHeartRate = restingTrend.last().value?.toInt(),
                restingEstimated = restingWithFlag.last().second,
                restingTrend = restingTrend,
                hrvTrend = hrvTrend,
                sleep = session,
                previousSleepScore = sleepParts.previousScore,
                naps = naps,
                napStages = napStages.mapValues { (_, st) -> st.map { StageSpan(it.start, it.end, it.stage) } },
                napBreakdowns = napStages.filterValues { it.isNotEmpty() }.mapValues { (_, st) -> Summaries.sleepBreakdown(st) },
                napsAsDetected = sleepParts.napsAsDetected,
                sleepStages = stages.map { StageSpan(it.start, it.end, it.stage) },
                sleepBreakdown = if (stages.isEmpty()) null else Summaries.sleepBreakdown(stages),
                spo2 = spo2.map { ChartPoint(it.timestamp, it.percent.toFloat()) },
                stress = stress.map { ChartPoint(it.timestamp, it.value.toFloat()) },
                hrv = hrv.map { ChartPoint(it.timestamp, it.milliseconds.toFloat()) },
                skinTemperature = skin.map { ChartPoint(it.timestamp, it.celsius) },
                breathRate = breath.map { ChartPoint(it.timestamp, it.perMinute.toFloat()) },
                pai = pai,
                baselines = base,
                sleepSpo2 = m.sleepSpo2,
                hrvSdnn = m.hrvSdnn,
                osaResult = m.osaResult,
                osaEvents = m.osaEvents,
                dailyMaxHeartRate = m.dailyMaxHeartRate,
                distanceMeters = m.distanceMeters,
                activeCalories = m.activeCalories,
                alerts = m.alerts,
                nights = m.nights,
                sleepHeartRate = m.sleepHeartRate,
                sleepHrv = m.sleepHrv,
                sleepSkinTemperature = m.sleepSkinTemperature,
                dayStart = day.from,
                dayEnd = day.to,
            )
        }
    }

    companion object {
        /** Minutes of heart-rate data a day needs before we estimate its resting HR ourselves. */
        private const val MIN_MINUTES_FOR_ESTIMATE = 6 * 60

        val SLEEP_STAGE_ROWS = mapOf(
            SleepStage.AWAKE to 0,
            SleepStage.REM to 1,
            SleepStage.LIGHT to 2,
            SleepStage.DEEP to 3,
        )
    }
}

private data class SleepParts(
    val main: SleepSession?,
    val mainStages: List<SleepStage>,
    val naps: List<SleepSession>,
    val napStages: Map<Long, List<SleepStage>>,
    val previousScore: Int? = null,
    val napsAsDetected: Map<Long, SleepSession> = emptyMap(),
)

private data class MoreParts(
    val sleepSpo2: List<ChartPoint>,
    val hrvSdnn: List<ChartPoint>,
    val osaResult: OsaResultRow?,
    val osaEvents: List<OsaEventRow>,
    val dailyMaxHeartRate: Int?,
    val distanceMeters: Int?,
    val activeCalories: Int?,
    val alerts: List<HealthAlertRow>,
    val nights: List<SleepNight> = emptyList(),
    val sleepHeartRate: List<ChartPoint> = emptyList(),
    val sleepHrv: List<ChartPoint> = emptyList(),
    val sleepSkinTemperature: List<ChartPoint> = emptyList(),
)

private data class NightParts(
    val nights: List<SleepNight>,
    val heartRate: List<ChartPoint>,
    val hrv: List<ChartPoint>,
    val skinTemperature: List<ChartPoint>,
)

/** One night's main sleep and the vitals averaged over it. */
data class SleepNight(
    val date: LocalDate,
    val session: SleepSession?,
    val hrv: Float? = null,
    val skinTemperature: Float? = null,
    val spo2: Float? = null,
    val breathing: Float? = null,
) {
    val asleepSeconds: Long? get() = session?.let(SleepHistory::asleepSeconds)
}
