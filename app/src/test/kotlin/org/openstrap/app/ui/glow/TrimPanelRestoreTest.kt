package org.openstrap.app.ui.glow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.testing.ComposeRules
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.workout.SummaryCopy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.openstrap.app.data.HeartZones

/** The trim handles keep where they were dragged while the panel stays open across a recreate (audit K7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrimPanelRestoreTest {
    private val compose = ComposeRules()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ZoneRule()).around(compose)

    private val start = 1_792_000_000L
    private val seconds = 1800L

    private fun summary(): PhoneSummary {
        val w = AppWorkout(start, start + seconds, 1, averageHeartRate = 130)
        val hr = (0L..seconds step 5).map { AppWorkoutHeartRate(start, start + it, 110 + (it * 40 / seconds).toInt(), false) }
        return PhoneSummary(WorkoutRecorder.analyse(w, hr, 190, HeartZones.STANDARD), hr.map { WorkoutHeartRate(start, it.timestamp, it.bpm) }, start, start + seconds, null)
    }

    @Test fun `a dragged start comes back after a recreate, and Save saves it`() {
        val s = summary()
        var saved: Pair<Long, Long>? = null
        StateRestorationTester(compose.compose).let { tester ->
            tester.setContent { TrimPanel(s, onSave = { f, t -> saved = f to t }, onCancel = {}) }
            val opened = SummaryCopy.trimHeader(clock(s.analysis.shownStart), clock(s.analysis.end), s.analysis.end - s.analysis.shownStart)
            compose.compose.onNodeWithText(opened).assertExists()

            // The strip sits between the panel's title and the clocks under it, as wide as they are.
            val title = compose.compose.onNodeWithText("Trim").fetchSemanticsNode().boundsInRoot
            val left = compose.compose.onNodeWithText(clock(s.recordedStart)).fetchSemanticsNode().boundsInRoot
            val right = compose.compose.onNodeWithText(clock(s.recordedEnd)).fetchSemanticsNode().boundsInRoot
            val y = (title.bottom + left.top) / 2
            val from = left.left + 2f
            compose.compose.onRoot().performTouchInput { swipe(Offset(from, y), Offset((left.left + right.right) / 2, y), 400) }
            compose.compose.waitForIdle()
            // Dragged: the header no longer reads the range the panel opened on.
            compose.compose.onNodeWithText(opened).assertDoesNotExist()

            tester.emulateSavedInstanceStateRestore()

            compose.compose.onNodeWithText(opened).assertDoesNotExist()
            compose.compose.onNodeWithText("Save").performClick()
        }
        val (f, t) = saved!!
        assertNotEquals(start, f)
        assertEquals(start + seconds, t)
    }
}
