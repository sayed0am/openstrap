package org.openstrap.app.ui.root

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openstrap.app.data.Days
import org.openstrap.app.data.HealthRepository
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.Rewards
import org.openstrap.app.data.RewardsKeeper
import org.openstrap.app.data.SportSettings
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.Goals
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.SyncRecord
import org.openstrap.app.device.SyncState
import org.openstrap.app.device.WorkoutSession
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.health.HealthConnectExporter
import org.openstrap.app.presentation.RewardsView
import org.openstrap.app.presentation.SummaryRewards
import org.openstrap.app.presentation.summary
import org.openstrap.app.presentation.view
import org.openstrap.app.ui.dashboard.TodayEdits
import org.openstrap.app.ui.workout.LiveGlance
import org.openstrap.app.ui.workout.liveGlance
import org.openstrap.app.update.AppUpdater
import java.time.LocalDate
import java.time.ZoneId

/** What the store and the strap say about the strap, for the header chip, Today and the pairing stage. */
data class StrapFacts(
    /** Paired until the store says otherwise, so a paired strap never flashes the pairing hero or "Not paired" on start. */
    val pairedKnown: Boolean,
    val connection: ConnectionState,
    val sync: SyncState,
    val syncRecord: SyncRecord,
)

/** The first real score's day (E4), once the store has answered. */
sealed interface FirstScoreDay {
    /** The store hasn't answered: nothing is decided yet. */
    data object Loading : FirstScoreDay

    data object None : FirstScoreDay

    data class Day(val date: LocalDate) : FirstScoreDay
}

/** The day shown's own extras: the check-in. The strap's score comes with its Recovery ([RecoveryResult.strapScore]). */
data class DayExtras(val checkIn: Int? = null)

/** A badge past history earned, with the weekly goal it was earned at when it is a goal badge. */
data class EarnedBadge(val badge: Rewards.Badge, val goal: Int?)

/** The celebrations switches: the goal moment, the weekly recap, and the week the goal moment last showed. */
data class CelebrationSettings(val goalMoment: Boolean = true, val weeklyRecap: Boolean = true, val momentWeek: Long? = null)

/** What Health Connect needs before a copy or a restore. */
sealed interface HcState {
    data object Unavailable : HcState

    data object Granted : HcState

    data class Needs(val permissions: Set<String>) : HcState
}

/**
 * Everything the app root reads from the data and writes to it (audit A1): the strap, the settings it shows and saves,
 * the rewards, the first score and the Health Connect checks. Where the app is lives in [AppNavState], and the
 * permission launchers, which need the activity, stay in the composables.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions", "LongParameterList") // one function per write the root makes, from the pieces it reads
class AppRootViewModel(
    private val settings: SettingsStore,
    private val health: HealthRepository,
    dao: HealthDao,
    strap: StrapController,
    workout: WorkoutSession,
    private val rewards: RewardsKeeper,
    private val healthConnect: HealthConnectExporter,
    updater: AppUpdater,
    /** Every workout, the one merged list ([org.openstrap.app.ui.workout.WorkoutsViewModel.workouts]). */
    private val workouts: StateFlow<List<WorkoutAnalysis>>,
    /** Where the rewards are worked out, off the main thread. */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), initial)

    val strap: StateFlow<StrapFacts> = combine(
        settings.pairedDevice.map { it != null }.onStart { emit(true) },
        strap.connection,
        strap.sync,
        strap.syncRecord,
        ::StrapFacts,
    ).distinctUntilChanged().state(StrapFacts(true, strap.connection.value, strap.sync.value, strap.syncRecord.value))

    val liveHeartRate: StateFlow<Int?> = strap.liveHeartRate
    val liveEnabled: StateFlow<Boolean> = strap.liveHeartRateEnabled
    val goals: StateFlow<Goals> = settings.goals.state(Goals())

    /** Today's hidden and pinned cards, parsed once per change: a new object each time would recompose every tab. */
    val todayEdits: StateFlow<TodayEdits> = combine(settings.todayHiddenNames, settings.todayPinnedNames) { h, p -> TodayEdits.parse(h, p) }
        .state(TodayEdits.parse(null, null))
    val habitAgeSeenAsOf: StateFlow<LocalDate?> = settings.habitAgeSeenAsOf.state(null)
    val firstScoreDay: StateFlow<FirstScoreDay> = settings.firstScoreDay
        .map { day -> day?.let { FirstScoreDay.Day(LocalDate.ofEpochDay(it)) } ?: FirstScoreDay.None }
        .state(FirstScoreDay.Loading)
    val updateWaiting: StateFlow<Boolean> = updater.waiting
    val updateAuto: StateFlow<Boolean> = settings.updateAuto.state(true)
    val workoutSides: StateFlow<Map<Int, Boolean>> = settings.workoutSides.state(emptyMap())
    val sportSettings: StateFlow<Map<Int, SportSettings>> = settings.sportSettings.state(emptyMap())
    val workoutLiveView: StateFlow<Int?> = settings.workoutLiveView.state(null)
    val celebrations: StateFlow<CelebrationSettings> =
        combine(settings.goalMoment, settings.weeklyRecap, settings.goalMomentWeek, ::CelebrationSettings).state(CelebrationSettings())

    /** The workout at a glance, for the Today hero and the floating bar (workouts spec 2.10). */
    val liveGlance: StateFlow<LiveGlance?> = liveGlance(workout, strap.liveHeartRate, strap.connection, settings.heartZones).state(null)

    /** The badges past history earned, shown once all together (requests spec 7.2). Null while none wait. */
    val historyBadges: StateFlow<List<EarnedBadge>?> = settings.historyBadgesWaiting.flatMapLatest { waiting ->
        if (!waiting) flowOf(null)
        else combine(dao.badges(), dao.weekResults()) { earned, weeks ->
            earned.mapNotNull { row ->
                Rewards.badge(row.id)?.let { EarnedBadge(it, Rewards.goalAt(row, weeks, rewards.firstDay, ZoneId.systemDefault())) }
            }
        }
    }.state(null)

    /** Goals and bests, All badges and the recap (workouts spec 3.5), once there are workouts. */
    val rewardsView: StateFlow<RewardsView?> =
        combine(workouts, goals.map { it.workoutsPerWeek }, dao.personalBests(), dao.badges()) { list, goal, bests, earned ->
            RewardsInputs(list, goal, bests, earned)
        }.filter { it.workouts.isNotEmpty() }
            .mapLatest { i -> catching { rewards.view(i.workouts, i.goal, i.bests, i.earned) }.getOrNull() }
            .flowOn(work)
            .state(null)

    private class RewardsInputs(
        val workouts: List<WorkoutAnalysis>,
        val goal: Int,
        val bests: List<org.openstrap.app.data.db.PersonalBest>,
        val earned: List<org.openstrap.app.data.db.BadgeRow>,
    )

    private val shownDay = MutableStateFlow<LocalDate?>(null)

    /** The day shown's check-in, from [dayShown]. */
    val dayExtras: StateFlow<DayExtras> = shownDay.filterNotNull().flatMapLatest { date ->
        health.checkIn(date).map { DayExtras(it?.feeling) }
    }.state(DayExtras())

    init {
        // The weekly goal, bests and badges follow the workouts, worked out again on a new one, a trim or a delete
        // (workouts spec 4), and on a new goal. The one recording isn't in it until it ends. Started at once, as the
        // weekly recap notification reads what's stored.
        val recording = workout.state.map { (it as? WorkoutState.Recording)?.workout?.start }.distinctUntilChanged()
        viewModelScope.launch(work) {
            combine(workouts, recording, settings.goals) { list, start, g ->
                list.filter { it.workout.start != start } to g.workoutsPerWeek
            }
                .distinctUntilChangedBy { (list, goal) -> list.map { Triple(it.workout.start, it.shownStart, it.end) } to goal }
                .collectLatest { (list, goal) -> catching { rewards.refresh(list, goal) }.onFailure { Log.w(TAG, "rewards failed", it) } }
        }
    }

    /** The day shown moved: [dayExtras] follows it. */
    fun dayShown(date: LocalDate) {
        shownDay.value = date
    }

    private fun write(what: String, block: suspend () -> Unit) {
        viewModelScope.launch { catching { block() }.onFailure { Log.w(TAG, "$what failed", it) } }
    }

    /** Opening Habit Age marks the estimate seen, so Today stops offering it. */
    fun seeHabitAge(asOf: Long) = write("habit age") { settings.setHabitAgeSeen(Days.date(asOf, ZoneId.systemDefault())) }

    fun markFirstScore(day: LocalDate) = write("first score") { settings.setFirstScoreDay(day.toEpochDay()) }

    fun saveCheckIn(date: LocalDate, feeling: Int?) = write("check-in") { health.saveCheckIn(date, feeling) }

    fun saveTodayEdits(edits: TodayEdits) =
        write("today edits") { settings.saveTodayEdits(edits.hidden.map { it.name }, edits.pinned.map { it.name }) }

    /** The goals. The widgets that show them follow the store. */
    fun saveGoals(goals: Goals) = write("goals") { settings.saveGoals(goals) }

    fun saveSportSettings(sport: Int, saved: SportSettings) = write("sport settings") { settings.setSportSettings(sport, saved) }

    /** The Outdoor or Indoor side Ready opens on. The Start widget follows the store. */
    fun setWorkoutSide(sport: Int, indoor: Boolean) = write("workout side") { settings.setWorkoutSide(sport, indoor) }

    fun setWorkoutLiveView(view: Int) = write("live view") { settings.setWorkoutLiveView(view) }

    fun setGoalMoment(on: Boolean) = write("goal moment") { settings.setGoalMoment(on) }

    fun setWeeklyRecap(on: Boolean) = write("weekly recap") { settings.setWeeklyRecap(on) }

    fun setGoalMomentWeek(week: Long) = write("goal moment week") { settings.setGoalMomentWeek(week) }

    /** Your max, or null to go back to the one the app works out. */
    fun setMaxHeartRate(bpm: Int?) = write("max heart rate") { settings.setMaxHeartRate(bpm) }

    /** Your heart rate zones as stored: the screens read these, so they never run ahead of what's on disk (audit A8). */
    val heartZones: StateFlow<HeartZones> = settings.heartZones.state(HeartZones.STANDARD)

    fun setHeartZones(zones: HeartZones) = write("heart zones") { settings.setHeartZones(zones) }

    fun historyBadgesShown() = write("history badges") { settings.historyBadgesShown() }

    /** The calorie goal your last four weeks suggest (calories spec 3.4), null when it can't be worked out. */
    suspend fun suggestedCalorieGoal(): Int? = catching { health.suggestedCalorieGoal() }.getOrNull()

    /** What the workout starting at [start] earned, from every workout and the weekly goal (workouts spec 2.8). */
    suspend fun summaryRewards(start: Long): SummaryRewards? =
        catching { rewards.summary(start, workouts.value, goals.value.workoutsPerWeek) }.getOrNull()

    /** The manual copy and auto sync write with these. The calorie ones are optional but asked for until granted. */
    suspend fun healthConnectWrite(): HcState = when {
        !healthConnect.isAvailable() -> HcState.Unavailable
        healthConnect.hasRequestedPermissions() -> HcState.Granted
        else -> HcState.Needs(healthConnect.requestedPermissions)
    }

    suspend fun healthConnectRead(): HcState = when {
        !healthConnect.isAvailable() -> HcState.Unavailable
        healthConnect.hasReadPermissions() -> HcState.Granted
        else -> HcState.Needs(healthConnect.readPermissions)
    }

    /** The needed ones may have been granted before, outside what the permission screen returns. */
    suspend fun healthConnectWriteGranted(result: Set<String>): Boolean =
        result.containsAll(healthConnect.permissions) || catching { healthConnect.hasPermissions() }.getOrDefault(false)

    fun healthConnectReadGranted(result: Set<String>): Boolean = result.any { it in healthConnect.readPermissions }

    private companion object {
        const val TAG = "OpenStrap"
        const val STOP_MS = 5_000L
    }
}
