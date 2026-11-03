package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.AppWorkoutRound
import org.openstrap.app.data.db.AppWorkoutSet
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.testing.ComposeRules
import org.openstrap.app.testing.appWorkout
import org.openstrap.app.ui.workout.ZoneSeconds
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A small phone on its side: Robolectric takes the system bars off, so about 312 dp of height is left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveLandscapePhoneTest : LiveLandscapeLayoutTest()

/** A Fold's cover screen on its side, the shortest a live screen gets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w882dp-h344dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveLandscapeFoldCoverTest : LiveLandscapeLayoutTest()

/** A Fold unfolded and turned on its side: tall enough for the full cards beside the controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w823dp-h707dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveLandscapeFoldInnerTest : LiveLandscapeLayoutTest()

/** Upright, the controls stay at the foot as before, and the same check holds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LivePortraitLayoutTest : LiveLandscapeLayoutTest()

/**
 * Every live screen at a size: the controls (Pause or Resume, the hold to end, Lap or Skip) are whole on the screen,
 * and no text of the screen (the heart rate line, the figures, the prompt) lies under them. On a phone on its side the
 * controls used to sit over the heart rate strip, or off the bottom of the screen.
 */
abstract class LiveLandscapeLayoutTest {
    @get:Rule val rules = ComposeRules()

    private val connected = ConnectionState.Connected(batteryPercent = 80, firmware = null)
    private val pad = PaddingValues(bottom = 16.dp)
    private val now = System.currentTimeMillis() / 1000
    private val start = now - 600
    private val recent = (0 until 300).map { now - 300 + it to 140 + it % 20 }
    private val zones = ZoneSeconds(listOf(60, 120, 300, 60, 0))

    @Test fun indoor() = check(recording(2), 152)

    @Test fun `indoor paused`() = check(WorkoutState.Recording(appWorkout(start = start, end = null, sportType = 2).copy(pausedAt = now - 30), 190), 120)

    @Test fun `indoor with the stopped prompt`() =
        check(WorkoutState.Recording(appWorkout(start = start, end = null, sportType = 2), 190, endPrompt = now - 120), 80)

    @Test fun `indoor with the strap away`() = check(recording(2), 150, ConnectionState.Disconnected)

    @Test fun mind() = check(WorkoutState.Recording(appWorkout(start = start, end = null, sportType = 60, target = "t:600"), 190), 72)

    @Test fun game() = check(recording(18), 152)

    @Test fun strength() = check(
        WorkoutState.Recording(
            appWorkout(start = start, end = null, sportType = 52), 190,
            sets = listOf(AppWorkoutSet(start, 0, now - 200, end = now - 140), AppWorkoutSet(start, 1, now - 30)),
        ),
        120,
    )

    @Test fun `strength resting`() = check(
        WorkoutState.Recording(
            appWorkout(start = start, end = null, sportType = 52), 190,
            sets = listOf(AppWorkoutSet(start, 0, now - 200, end = now - 40)), restUntil = now + 50,
        ),
        120,
    )

    @Test fun `hiit work`() = check(hiit(AppWorkoutRound(start, 0, start + 10)), 160)

    @Test fun `hiit rest`() = check(hiit(AppWorkoutRound(start, 0, start + 10, workEnd = now - 5, peakBpm = 171)), 160)

    @Test fun distance() = check(gps(), 152)

    @Test fun `distance map`() = check(gps(), 152, liveView = 1)

    @Test fun `distance reps`() = check(
        gps("r:400m/90x6", AppWorkoutRound(start, 0, start + 10, workEnd = start + 100, restEnd = start + 190), AppWorkoutRound(start, 1, now - 30)),
        165,
    )

    private fun recording(sport: Int) = WorkoutState.Recording(appWorkout(start = start, end = null, sportType = sport), 190)

    private fun hiit(vararg rounds: AppWorkoutRound) = WorkoutState.Recording(
        appWorkout(start = start, end = null, sportType = 49).copy(structure = "t:40/20x10"), 190, rounds = rounds.toList(),
    )

    private fun gps(structure: String? = null, vararg rounds: AppWorkoutRound) = WorkoutState.Recording(
        appWorkout(start = start, end = null, sportType = 1).copy(useGps = true, distanceMeters = 2140f, structure = structure), 190,
        rounds = rounds.toList(),
    )

    private fun check(r: WorkoutState.Recording, bpm: Int, connection: ConnectionState = connected, liveView: Int? = null) {
        screen {
            GlowWorkoutLiveScreen(r, bpm, recent, null, zones, connection, pad, {}, {}, {}, {}, {}, {}, liveView = liveView)
        }
        val compose = rules.compose
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val (square, icon) = with(compose.density) { END_SQUARE.toPx() to SMALLEST_ICON.toPx() }
        val drawn = compose.onAllNodes(isControl).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("no controls found", drawn.size >= 2)
        // Squeezed by the content above it, a button still draws, over what's there, from a box with no height.
        drawn.forEach { c -> assertTrue("a control is squeezed to $c", c.width >= icon - 0.5f && c.height >= icon - 0.5f) }
        val controls = drawn.map { it.atLeast(square) }
        controls.forEach { c -> assertTrue("a control is off the screen: $c in $root", root.holds(c)) }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { text(it) !in CONTROL_LABELS }
            .forEach { t ->
                controls.forEach { c ->
                    val o = t.boundsInRoot.intersect(c)
                    assertTrue("\"${text(t)}\" ${t.boundsInRoot} lies under a control at $c", o.width <= 0.5f || o.height <= 0.5f)
                }
            }
    }

    /** No native map in a unit test: in inspection mode the map draws as its route only black. */
    private fun screen(content: @Composable () -> Unit) {
        rules.compose.mainClock.autoAdvance = false
        rules.compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { content() } }
        rules.compose.mainClock.advanceTimeBy(1_000)
    }

    private fun text(n: SemanticsNode) = texts(n).joinToString()

    /** A control drawn round a small icon (the square to hold, 64 dp) counts as its whole button. */
    private fun Rect.atLeast(side: Float): Rect {
        val w = maxOf(width, side) / 2
        val h = maxOf(height, side) / 2
        return Rect(center.x - w, center.y - h, center.x + w, center.y + h)
    }

    private fun Rect.holds(o: Rect) = o.left >= left - 0.5f && o.top >= top - 0.5f && o.right <= right + 0.5f && o.bottom <= bottom + 0.5f

    private companion object {
        fun texts(n: SemanticsNode): List<String> = n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text }

        val END_SQUARE = 64.dp
        val SMALLEST_ICON = 20.dp

        val CONTROL_DESCRIPTIONS = setOf("Pause", "Resume", "Hold to end the workout", "Lap", "Skip", "Skip rest")

        /** The controls' own labels, under them or on their pills, and the line under the paused buttons. */
        val CONTROL_LABELS = setOf(
            "Pause", "Resume", "Hold to end", "Lap", "Skip", "Skip rest", "End workout",
            "Hold End workout to finish. Resume carries on where you stopped.", "Tap anywhere to light the screen",
        )

        /** Pause or Resume, the hold to end and the left button: round ones carry their icon's words, pills their own. */
        val isControl = SemanticsMatcher("a live control") { n ->
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty().any { it in CONTROL_DESCRIPTIONS } ||
                (androidx.compose.ui.semantics.SemanticsActions.OnClick in n.config && texts(n).any { it in setOf("Pause", "Resume") })
        }
    }
}
