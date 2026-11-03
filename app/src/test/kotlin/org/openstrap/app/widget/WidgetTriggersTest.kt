package org.openstrap.app.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.WidgetKind
import org.openstrap.app.data.settings.StrapSnapshot
import org.openstrap.app.device.SyncState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.presentation.StrapBands
import org.openstrap.app.data.db.SleepEdit
import org.openstrap.app.testing.appWorkout
import org.openstrap.app.testing.profile
import org.openstrap.protocol.services.StepTotals

/**
 * What refreshes which widgets now they watch the data (wave 4, Decisions 5): the workout's state changes as
 * [workoutKinds] reads them, and that a process start, where every flow gives its first value, refreshes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetTriggersTest {
    private val both = setOf(WidgetKind.Moment, WidgetKind.Start)
    private val all = WidgetKind.entries.toSet()
    private val recording = WorkoutState.Recording(appWorkout(start = NOW - 600), maxHeartRate = 190)

    @Test fun `nothing before the first state`() {
        assertEquals(emptySet<WidgetKind>(), workoutKinds(null, recording))
    }

    @Test fun `starting and ending a workout refresh Moment and Start`() {
        assertEquals(both, workoutKinds(WorkoutState.Idle, recording))
        assertEquals(both, workoutKinds(recording, WorkoutState.Idle))
    }

    @Test fun `a pause or a resume refreshes Start alone`() {
        val paused = recording.copy(workout = recording.workout.copy(pausedAt = NOW))
        assertEquals(setOf(WidgetKind.Start), workoutKinds(recording, paused))
        assertEquals(setOf(WidgetKind.Start), workoutKinds(paused, recording))
    }

    @Test fun `the same state, or a new reading while recording, refreshes nothing`() {
        assertEquals(emptySet<WidgetKind>(), workoutKinds(WorkoutState.Idle, WorkoutState.Idle))
        assertEquals(emptySet<WidgetKind>(), workoutKinds(recording, recording.copy(restExtra = 10)))
    }

    // The watching, on flows the test moves.
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val goals = MutableStateFlow<Any>(1)
    private val sides = MutableStateFlow<Any>(mapOf(1 to true))
    private val snapshot = MutableStateFlow(StrapSnapshot(paired = true, battery = 80, charging = false, lastSyncDone = NOW - 60))
    private val sync = MutableStateFlow<SyncState>(SyncState.Idle)
    private val steps = MutableStateFlow<StepTotals?>(null)
    private val ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val workout = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    private val sleepEdits = MutableStateFlow<Any?>(emptyList<Any>())
    private val profiles = MutableStateFlow<Any>(profile())
    private val clock = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val changed = mutableListOf<Set<WidgetKind>>()
    private var stepPushes = 0

    private fun watch() {
        val t = WidgetTriggers(goals, sides, snapshot, sync, steps, ended, workout, sleepEdits, profiles, clock) { NOW }
        scope.watchWidgetTriggers(t, { changed += it }, { stepPushes++ })
        scope.runCurrent()
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `a process start refreshes no widget`() {
        watch()
        assertEquals(emptyList<Set<WidgetKind>>(), changed)
        assertEquals(0, stepPushes)
    }

    @Test fun `the goals and the workout sides refresh their widgets`() {
        watch()
        goals.value = 2
        sides.value = mapOf(1 to false)
        scope.runCurrent()
        assertEquals(listOf(setOf(WidgetKind.Steps, WidgetKind.Moment), setOf(WidgetKind.Start)), changed)
    }

    @Test fun `a sync starting refreshes all only while the last one is stale`() {
        watch()
        sync.value = SyncState.Running("x")
        scope.runCurrent()
        assertEquals(emptyList<Set<WidgetKind>>(), changed)
        sync.value = SyncState.Idle
        snapshot.value = snapshot.value.copy(lastSyncDone = NOW - StrapBands.STALE_AFTER_S - 1)
        sync.value = SyncState.Running("y")
        scope.runCurrent()
        assertEquals(listOf(all), changed)
    }

    @Test fun `the strap's state refreshes all only when the line could change`() {
        watch()
        snapshot.value = snapshot.value.copy(battery = 79)
        scope.runCurrent()
        assertEquals(emptyList<Set<WidgetKind>>(), changed)
        snapshot.value = snapshot.value.copy(charging = true)
        scope.runCurrent()
        assertEquals(listOf(all), changed)
    }

    @Test fun `a sync ending, live steps and a workout reach the widgets`() {
        watch()
        ended.tryEmit(Unit)
        steps.value = StepTotals(1200, 900, 40)
        workout.value = recording
        scope.runCurrent()
        assertEquals(listOf(all, both), changed)
        assertEquals(1, stepPushes)
    }

    @Test fun `a sleep edit and a profile change refresh every widget without a sync`() {
        // Data integrity item 20: before, the widgets kept the old night and Recovery until the next sync ended.
        watch()
        sleepEdits.value = listOf(SleepEdit(NOW - 30_000, nap = false, start = NOW - 29_000, end = NOW - 2_000, editedAt = NOW))
        profiles.value = profile(weightKg = 80f)
        scope.runCurrent()
        assertEquals(listOf(all, all), changed)
    }

    @Test fun `a change of time zone refreshes every widget`() {
        // Item 17: after a flight the widgets show the day it is there, and the refresh sets midnight's alarm again.
        watch()
        clock.tryEmit(Unit)
        scope.runCurrent()
        assertEquals(listOf(all), changed)
    }

    private companion object {
        const val NOW = 1_768_478_400L
    }
}
