package org.openstrap.app.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openstrap.app.data.catching
import org.openstrap.app.data.settings.StrapSnapshot
import org.openstrap.app.device.SyncState
import org.openstrap.app.device.WorkoutState
import org.openstrap.protocol.services.StepTotals

/**
 * Which widgets a workout's state moving from [previous] to [next] refreshes (widgets spec 7). Starting or ending a
 * workout changes the moment and the Start widget, and so does any other change of state class: a summary closing can
 * follow a discard, which takes a workout out of Start's week and Moment's after a workout. A pause or a resume stops
 * or restarts Start's timer. Nothing before the first state is known ([previous] null).
 */
fun workoutKinds(previous: WorkoutState?, next: WorkoutState): Set<WidgetKind> {
    if (previous == null) return emptySet()
    val wasPaused = (previous as? WorkoutState.Recording)?.paused
    val paused = (next as? WorkoutState.Recording)?.paused
    return when {
        (previous is WorkoutState.Recording) != (next is WorkoutState.Recording) -> setOf(WidgetKind.Moment, WidgetKind.Start)
        previous::class != next::class -> setOf(WidgetKind.Moment, WidgetKind.Start)
        paused != null && wasPaused != null && paused != wasPaused -> setOf(WidgetKind.Start)
        else -> emptySet()
    }
}

/**
 * Everything a widget's refresh follows (widgets spec 7). The widgets watch these instead of being told: settings,
 * the strap and the workout never name a widget. [now] is epoch seconds.
 */
internal class WidgetTriggers(
    val goals: Flow<Any>,
    val workoutSides: Flow<Any>,
    val strapSnapshot: Flow<StrapSnapshot>,
    val sync: Flow<SyncState>,
    val todaySteps: Flow<StepTotals?>,
    val syncEnded: Flow<Unit>,
    val workoutState: Flow<WorkoutState>,
    val now: () -> Long,
)

/**
 * Watches [t] in this scope, calling [changed] with the kinds to refresh and [stepsChanged] for each live step count.
 * Each flow's value when watching starts refreshes nothing, so a process start doesn't refresh every widget: the
 * settings skip their first value, and the strap and workout start from idle as they do at start.
 */
internal fun CoroutineScope.watchWidgetTriggers(t: WidgetTriggers, changed: (Set<WidgetKind>) -> Unit, stepsChanged: () -> Unit) {
    val all = WidgetKind.entries.toSet()
    // Goals: the Steps goal and Moment's evening line.
    launch { t.goals.drop(1).distinctUntilChanged().collect { changed(setOf(WidgetKind.Steps, WidgetKind.Moment)) } }
    // The Outdoor or Indoor side last chosen per sport, on Start's tiles.
    launch { t.workoutSides.drop(1).distinctUntilChanged().collect { changed(setOf(WidgetKind.Start)) } }
    // A sync starting while the widgets say the last one is old: their line turns to Syncing (spec 5).
    launch {
        var previous: SyncState = SyncState.Idle
        t.sync.collect { s ->
            if (s is SyncState.Running && previous !is SyncState.Running) launch {
                val done = catching { t.strapSnapshot.first().lastSyncDone }.getOrNull()
                if (syncStale(done, t.now())) changed(all)
            }
            previous = s
        }
    }
    // When the status line could change: pairing, the low battery edge, charging.
    launch {
        var before: StrapSnapshot? = null
        t.strapSnapshot.collect { now ->
            val was = before
            before = now
            if (was != null && strapStateChanged(now.paired, now.battery, now.charging, was.paired, was.battery, was.charging)) changed(all)
        }
    }
    launch { t.todaySteps.filterNotNull().collect { stepsChanged() } }
    // Everything a widget shows may have moved.
    launch { t.syncEnded.collect { changed(all) } }
    launch {
        var previous: WorkoutState = WorkoutState.Idle
        t.workoutState.collect { s ->
            val kinds = workoutKinds(previous, s)
            if (kinds.isNotEmpty()) changed(kinds)
            previous = s
        }
    }
}
