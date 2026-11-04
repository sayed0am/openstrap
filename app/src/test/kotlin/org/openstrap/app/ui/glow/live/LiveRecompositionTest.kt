package org.openstrap.app.ui.glow.live

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.AppWorkoutSet
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.testing.appWorkout
import org.openstrap.app.ui.workout.ZoneSeconds
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.annotation.Config

/**
 * The live screens over simulated seconds, with the heart beating on the glow: once settled, the only scopes composed
 * again are the clocks, one scope each (audit K3). A screen that read the time at its content level would add its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalComposeRuntimeApi::class)
class LiveRecompositionTest {
    private val controls = LiveControls({}, {}, {}, {}, {}, {})
    private val connected = ConnectionState.Connected(batteryPercent = 80, firmware = null)
    private val padding = PaddingValues()

    /**
     * One test for both screens: Compose's main dispatcher is made once per process, for the first test's looper, so
     * a second test method in this sandbox would never see a frame.
     */
    @Test
    fun `live screens compose only their clocks each second`() {
        indoorCardio()
        strengthSet()
    }

    private fun indoorCardio() {
        val now = System.currentTimeMillis() / 1000
        val r = WorkoutState.Recording(appWorkout(start = now - 600, end = null), maxHeartRate = 190)
        val recent = (0 until 120).map { now - 120 + it to 140 }
        val zones = ZoneSeconds(List(5) { 60 })
        val ticks = recompositions {
            LiveIndoorScreen(r, 140, recent, null, zones, connected, padding, controls, onLap = {}, kcal = null)
        }
        assertTrue("indoor: the clock never ticked: $ticks", ticks.entries >= 3)
        // The large moving time is the only clock on this screen.
        assertEquals("indoor: scopes composed again each second: ${ticks.why}", 1, ticks.scopes)
    }

    private fun strengthSet() {
        val now = System.currentTimeMillis() / 1000
        val start = now - 600
        val r = WorkoutState.Recording(
            appWorkout(start = start, end = null, sportType = 50), maxHeartRate = 190,
            sets = listOf(AppWorkoutSet(start, 0, now - 200, end = now - 140), AppWorkoutSet(start, 1, now - 30)),
        )
        val history = (0 until 300).map { now - 300 + it to 120 }
        val ticks = recompositions {
            LiveStrengthScreen(r, 120, history, connected, padding, controls, emptyList(), {}, {}, {}, {}, { _, _, _, _ -> })
        }
        assertTrue("strength: the clocks never ticked: $ticks", ticks.entries >= 3)
        // The total, small on the top right, and the set's own clock in dots.
        assertEquals("strength: scopes composed again each second: ${ticks.why}", 2, ticks.scopes)
    }

    /** What composed again over the simulated seconds after the screen settled. */
    private data class Ticks(val entries: Int, val scopes: Int, val why: List<String>)

    /**
     * Composes [content] in an activity under a recomposer this test observes, lets it settle (the glow's layers
     * land from a background thread), then counts the scopes composed again from the top of a pass over five seconds.
     */
    private fun recompositions(content: @Composable () -> Unit): Ticks {
        val roots = mutableListOf<RecomposeScope>()
        val reasons = mutableMapOf<RecomposeScope, MutableSet<String>>()
        var counting = false
        var depth = 0
        val observer = object : CompositionObserver {
            override fun onBeginComposition(composition: ObservableComposition) = Unit
            override fun onScopeEnter(scope: RecomposeScope) {
                if (counting && depth == 0) roots += scope
                depth++
            }
            override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
            override fun onScopeExit(scope: RecomposeScope) {
                depth--
            }
            override fun onEndComposition(composition: ObservableComposition) = Unit
            override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {
                if (counting) reasons.getOrPut(scope) { mutableSetOf() } += value.toString()
            }
            override fun onScopeDisposed(scope: RecomposeScope) = Unit
        }
        // Frames on the fake vsync: unpaused, a beating glow asks for its next frame at the same instant forever.
        ShadowChoreographer.setPaused(true)
        val recomposer = Recomposer(AndroidUiDispatcher.Main)
        val scope = CoroutineScope(AndroidUiDispatcher.Main)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        val handle = recomposer.observe(object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                composition.setObserver(observer)
            }
            override fun onCompositionUnregistered(composition: ObservableComposition) = Unit
        })
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity).apply { setParentCompositionContext(recomposer) }
        activity.setContentView(view)
        view.setContent(content)
        repeat(SETTLE_S) { second() }
        counting = true
        repeat(COUNT_S) { second() }
        counting = false
        handle.dispose()
        recomposer.cancel()
        scope.cancel()
        val distinct = roots.distinct()
        return Ticks(roots.size, distinct.size, distinct.map { reasons[it].orEmpty().joinToString() })
    }

    /** One simulated second, waited in real time too: the clocks read the wall clock. */
    private fun second() {
        Thread.sleep(1_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    private companion object {
        const val SETTLE_S = 3
        const val COUNT_S = 5
    }
}
