package org.openstrap.app.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.Today
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutRepository
import org.openstrap.app.data.WorkoutSuggestions
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.SportEffectRow
import java.time.Clock
import java.time.LocalDate

/** The data layer's summary, kept under its old name for the screens. */
typealias PhoneSummary = org.openstrap.app.data.WorkoutSummaryData

/** Workouts list (newest first) and the one open in detail. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsViewModel(
    private val repo: WorkoutRepository,
    /** The clock today and now are read from, asked afresh each time. Tests pass one they move by hand. */
    private val clock: () -> Clock = { Clock.systemDefaultZone() },
    /** The phone's clock, time zone or date changed ([Today.changes]), so today is read again at once. */
    dateChanges: Flow<Unit> = emptyFlow(),
    /** Where rows are worked into what the screens show, off the main thread (audit C7). */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    /** Today as it moves, so the suggestions' window moves on at midnight (audit C10). */
    private val today: Flow<LocalDate> = Today.dates(clock, dateChanges)

    private fun nowSeconds(): Long = clock().instant().epochSecond

    /**
     * The strap's workouts and the phone's, newest first, worked out off the main thread. Zones fill in after the
     * list shows ([WorkoutRepository.workouts]).
     */
    val workouts: StateFlow<List<WorkoutAnalysis>> = repo.workouts()
        .flowOn(work)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The strap's sport-effect records, newest last: recovery time, VO2 max and weekly load after each workout. */
    val effects: StateFlow<List<SportEffectRow>> =
        repo.effects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The max heart rate zones are shares of, once worked out. */
    val maxHeartRate: StateFlow<Int?> = repo.maxHeartRate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A day's heart rate a minute at a time, for adding a workout after the fact. */
    suspend fun dayMinuteHeartRate(date: LocalDate): List<Pair<Long, Int>> = withContext(work) { repo.dayMinuteHeartRate(date) }

    /**
     * Workouts nobody started, newest first ([WorkoutSuggestions]), from the strap's heart rate since yesterday. Sleep,
     * saved workouts and stretches answered "Not a workout" never count.
     */
    val suggestions: StateFlow<List<WorkoutSuggestions.Suggestion>> = today
        .flatMapLatest { d -> repo.suggestions(DayWindow.of(d.minusDays(1)).from, ::nowSeconds) }
        .flowOn(work)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val openStart = MutableStateFlow<Long?>(null)

    val open: StateFlow<WorkoutAnalysis?> = openStart.flatMapLatest { start -> if (start == null) flowOf(null) else repo.open(start) }
        .flowOn(work).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * A phone workout's summary: its analysis, everything recorded (for trimming), and the strap's own record of it
     * when there is one. [backfill] works out the effect of a workout from before there was one and saves it
     * ([backfillEffect]) as the summary opens. Off, opening the summary writes nothing.
     */
    fun phoneSummary(start: Long, backfill: Boolean = true): Flow<PhoneSummary?> =
        repo.phoneSummary(start).flowOn(work)
            .onStart { if (backfill) backfillEffect(start) }

    /**
     * Works out and saves the effect of phone workout [start] when it ended before effects were worked out
     * ([WorkoutRepository.backfillEffect]). On a coroutine of its own, not in the summary's read, so the summary shows
     * without waiting for it and comes round again once the effect is saved.
     */
    private fun backfillEffect(start: Long) {
        viewModelScope.launch(work) {
            catching { repo.backfillEffect(start) }.onFailure { Log.w("unstrap", "effect backfill failed for $start", it) }
        }
    }

    /**
     * Your usual heart rate drop in the first minute after a workout, before [before] ([SummaryCopy.usualDrop]): from
     * the strap's own workouts, whose recording runs on after the end, and only those ended from a real effort
     * ([WorkoutAnalysis.recoveryIsMeaningful]). Phone workouts keep no drop yet. Null with too few.
     */
    suspend fun usualDrop(before: Long): Int? = withContext(work) {
        SummaryCopy.usualDrop(repo.recoveryDrops(before, SummaryCopy.USUAL_DROP_OF * 2))
    }

    fun open(start: Long?) { openStart.value = start }

    /** Deletes a strap workout and keeps it from coming back with a later sync ([HealthDao.deleteStrapWorkout]). */
    fun deleteStrapWorkout(start: Long) {
        viewModelScope.launch { repo.deleteStrapWorkout(start, nowSeconds()) }
    }
}
