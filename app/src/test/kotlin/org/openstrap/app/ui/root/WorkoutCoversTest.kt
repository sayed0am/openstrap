package org.openstrap.app.ui.root

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.testing.ComposeRules
import org.openstrap.app.testing.appWorkout
import org.openstrap.app.ui.glow.GlowCard
import org.openstrap.app.ui.glow.LocalGlowPagePaused
import org.openstrap.app.ui.glow.draw.GlowMotion
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tabs under the full-screen workout hold their glows still: the Today hero beating at the live heart rate under
 * the dimmed Mind and body screen drew every frame (about 97 a second) with nothing to see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutCoversTest {
    @get:Rule val rules = ComposeRules()

    private val recording = WorkoutState.Recording(appWorkout(start = 1_792_000_000L, end = null), maxHeartRate = 190)

    @Test fun `the live screen, the summary, Choose and Ready cover the tabs, the capsule doesn't`() {
        assertTrue(workoutCovers(recording, minimized = false, WorkoutOverlay.None))
        assertFalse(workoutCovers(recording, minimized = true, WorkoutOverlay.None))
        assertTrue(workoutCovers(WorkoutState.Ended(recording.workout.start), minimized = false, WorkoutOverlay.None))
        assertTrue(workoutCovers(WorkoutState.Idle, minimized = false, WorkoutOverlay.Choose))
        assertTrue(workoutCovers(WorkoutState.Idle, minimized = false, WorkoutOverlay.Ready(sport = 1)))
        assertFalse(workoutCovers(WorkoutState.Idle, minimized = false, WorkoutOverlay.None))
    }

    @Test fun `a beating card on a tab stops drawing while the workout covers it, and beats again under the capsule`() {
        val state = MutableStateFlow<WorkoutState>(recording)
        val minimized = MutableStateFlow(true)
        val nav = AppNavState()
        // The outline look renders no layers, so the only state written frame by frame is the heartbeat's.
        val style = GlowStyle(Color.Red, outline = true, motion = GlowMotion.Heartbeat(120))
        val compose = rules.compose
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val covered = rememberWorkoutCovers(state, minimized, nav)
            val pager = rememberPagerState { 1 }
            CompositionLocalProvider(LocalGlowPagePaused provides remember(pager, covered) { tabPaused(pager, 0, covered) }) {
                GlowCard(style, Modifier.size(200.dp)) {}
            }
        }
        var changes = 0
        val observer = Snapshot.registerApplyObserver { changed, _ -> changes += changed.size }
        // State changes written over [frames] frames, after a few to let a change of cover settle.
        fun written(frames: Int): Int {
            repeat(SETTLE) { compose.mainClock.advanceTimeByFrame() }
            compose.runOnIdle { Snapshot.sendApplyNotifications() }
            changes = 0
            repeat(frames) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle { Snapshot.sendApplyNotifications() }
            }
            return changes
        }
        try {
            assertTrue("tucked into the capsule, the tab's card beats", written(FRAMES) >= FRAMES)
            minimized.value = false
            assertEquals("under the live screen, nothing changes frame by frame", 0, written(FRAMES))
            minimized.value = true
            assertTrue("back under the capsule, it beats again", written(FRAMES) >= FRAMES)
            state.value = WorkoutState.Ended(recording.workout.start)
            assertEquals("under the summary, nothing changes frame by frame", 0, written(FRAMES))
        } finally {
            observer.dispose()
        }
    }

    private companion object {
        const val FRAMES = 30
        const val SETTLE = 5
    }
}
