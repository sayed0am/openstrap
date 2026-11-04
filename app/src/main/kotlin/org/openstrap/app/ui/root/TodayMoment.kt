// Keyed effects run once per key after the frame commits, as they did in AppRoot. A SideEffect would run on every
// recomposition.
@file:Suppress("UnnecessaryLaunchedEffect")

package org.openstrap.app.ui.root

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import org.openstrap.app.data.Moment
import org.openstrap.app.data.MomentResolver
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.RecoveryFailure
import org.openstrap.app.ui.dashboard.layoutMoment
import org.openstrap.app.ui.workout.WorkoutsViewModel

/** What the clock says about Today (spec 4.1). */
class TodayMoment(
    /** Today by the clock. */
    val clockDate: LocalDate,
    /** The moment, forced or the clock's: whichever day is shown, the mood follows it. */
    val moment: Moment?,
    /** What Today is laid out for: the moment today, the waking layout for a past day. */
    val layout: Moment?,
    /** Your usual bedtime, kept from when today was last loaded. */
    val usualBedtime: LocalTime?,
    /** The workout that ended last, for the header's sport after one. */
    val lastWorkout: WorkoutAnalysis?,
)

private const val MINUTE_MS = 60_000L

/**
 * The moment on a clock that ticks each minute only while the app is in view. It stays in composition: it reads the
 * day and Recovery from one view model and the workouts from another, and moving it would change when the kept facts
 * update (wave 4 Decisions 8). Open across midnight on today, the day shown moves on with the clock ([onNewDay]).
 */
// The ticker reads the clock as each minute starts, not once in composition.
@Suppress("ForbiddenMethodCall/uiClock")
@Composable
internal fun rememberTodayMoment(
    day: DayUiState,
    recovery: RecoveryResult?,
    recoveryFailure: RecoveryFailure?,
    recording: Boolean,
    forced: Moment?,
    workoutsVm: WorkoutsViewModel,
    onNewDay: (LocalDate) -> Unit,
): TodayMoment {
    val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock by produceState(Instant.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = Instant.now()
                delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
            }
        }
    }
    val zone = ZoneId.systemDefault()
    val clockDate = clock.atZone(zone).toLocalDate()
    // Open across midnight on today, the day moves on with the clock, so night carries on past 00:00.
    var shownToday by remember { mutableStateOf(clockDate) }
    val newDay by rememberUpdatedState(onNewDay)
    LaunchedEffect(clockDate) {
        if (clockDate != shownToday) {
            if (day.date == shownToday) newDay(clockDate)
            shownToday = clockDate
        }
    }
    // Your usual workout time comes from workouts you started, not walks the strap noticed by itself.
    val workoutStarts = remember(workouts) { workouts.filter { it.startedByYou }.map { it.workout.start } }
    val lastWorkout = remember(workouts) { workouts.maxByOrNull { it.end } }
    // The moment is the clock's, whichever day is shown: browsing yesterday at night is still night (the mood follows
    // it), and only Today's layout goes back to waking for a past day. Today's wake and usual bedtime are kept from
    // when today was last loaded, so a past day doesn't lose them. Until today's recovery has loaded once, the
    // bedtime isn't known and the fallback would show Evening for a second before the real moment, so there's no
    // moment until then: Today's plain title, the day's mood.
    // A Recovery that failed counts as loaded: the moment shouldn't wait on it.
    val todayLoaded = day.date == clockDate && (recovery?.date == clockDate || recoveryFailure?.date == clockDate)
    val freshClockFacts = if (todayLoaded) day.sleep?.end to recovery?.usualBedtime else null
    var keptClockFacts by remember(clockDate) { mutableStateOf<Pair<Long?, LocalTime?>?>(null) }
    SideEffect { if (freshClockFacts != null && freshClockFacts != keptClockFacts) keptClockFacts = freshClockFacts }
    val clockFacts = freshClockFacts ?: keptClockFacts
    val clockMoment = remember(clock.epochSecond / 60, clockDate, recording, lastWorkout?.end, clockFacts, workoutStarts) {
        clockFacts?.let { (sleepEnd, bedtime) ->
            MomentResolver.resolve(clock, zone, clockDate, recording, lastWorkout?.end, sleepEnd, bedtime, workoutStarts)
        }
    }
    val moment = forced ?: clockMoment
    LaunchedEffect(forced, clockMoment) {
        forced?.let { Log.i("OpenStrap", "moment: forced $it, the clock says $clockMoment") }
    }
    return TodayMoment(clockDate, moment, layoutMoment(moment, day.date == clockDate), clockFacts?.second, lastWorkout)
}
