package org.openstrap.app.ui.glow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.SleepEditCopy
import org.openstrap.app.testing.ComposeRules
import org.openstrap.app.testing.ZoneRule
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A sleep's and a nap's times are set on the workout's trim bar, which can trim or extend them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepTrimTest {
    private val compose = ComposeRules()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ZoneRule()).around(compose)

    private val start = 1_792_000_020L // on a whole minute
    private val night = SleepSession(start, start + 8 * 3600 + 37)
    private val now = night.end + 3600
    private val heartRate = (start - 7200..now step 300).map { ChartPoint(it, if (it in night.start..night.end) 52f else 70f) }

    private fun show(others: List<SleepSession> = emptyList(), onSave: (Long, Long) -> Unit) =
        compose.compose.setContent { SleepTrim(night, night, heartRate, others, now, onSave, onCancel = {}) }

    /** Drags the start handle (or the end one) of the bar, from where it is to the time [to], clamped to the bar's ends. */
    private fun drag(startHandle: Boolean, to: Long, others: List<SleepSession> = emptyList()) {
        val reach = Trim.sleepReach(night, night.start, night.end, now, others)
        compose.compose.onNodeWithTag(TRIM_BAR_TAG).performTouchInput {
            val inset = TrimSide.toPx()
            val w = width - 2 * inset
            fun x(t: Long) = (inset + (t - reach.first).toFloat() / (reach.last - reach.first) * w).coerceIn(0f, width - 1f)
            val y = height / 2f
            swipe(Offset(x(if (startHandle) night.start else night.end), y), Offset(x(to), y), 400)
        }
        compose.compose.waitForIdle()
    }

    @Test fun `the start dragged all the way out extends the sleep by two hours, the end staying the strap's to the second`() {
        var saved: Pair<Long, Long>? = null
        show { f, t -> saved = f to t }
        drag(startHandle = true, to = night.start - 9 * 3600)
        compose.compose.onNodeWithText("Save").performClick()
        assertEquals(night.start - 2 * 3600 to night.end, saved)
    }

    @Test fun `a trimmed start lands on a whole minute`() {
        var saved: Pair<Long, Long>? = null
        show { f, t -> saved = f to t }
        drag(startHandle = true, to = night.start + 3 * 3600)
        compose.compose.onNodeWithText("Save").performClick()
        val (f, t) = saved!!
        assertTrue(f > night.start)
        assertEquals(0L, f % 60)
        assertEquals(night.end, t)
    }

    @Test fun `the end can't go past now`() {
        var saved: Pair<Long, Long>? = null
        show { f, t -> saved = f to t }
        drag(startHandle = false, to = now + 9 * 3600)
        compose.compose.onNodeWithText("Save").performClick()
        assertEquals(night.start to now, saved)
    }

    @Test fun `the end stops at the nap after it`() {
        val nap = SleepSession(night.end + 1800, night.end + 3000, nap = true)
        var saved: Pair<Long, Long>? = null
        show(listOf(nap)) { f, t -> saved = f to t }
        drag(startHandle = false, to = now, others = listOf(nap))
        compose.compose.onNodeWithText("Save").performClick()
        assertEquals(night.start to nap.start, saved)
    }

    @Test fun `times too short to keep say so and can't be saved`() {
        var saved: Pair<Long, Long>? = null
        show { f, t -> saved = f to t }
        drag(startHandle = true, to = night.end - 600)
        val why = SleepEditCopy.text(SleepEdits.Problem.TooShort(false), ZoneId.systemDefault())
        compose.compose.onNodeWithText(why).assertExists()
        compose.compose.onNodeWithText("Save").performClick()
        assertNull(saved)
    }

    @Test fun `the times and length sit above the bar`() {
        show { _, _ -> }
        val line = "${org.openstrap.app.ui.dashboard.SleepCopy.span(clock(night.start), clock(night.end))}, ${org.openstrap.app.presentation.Units.duration(night.end - night.start)}"
        val above = compose.compose.onNodeWithText(line).fetchSemanticsNode().boundsInRoot
        assertTrue(above.bottom <= compose.compose.onNodeWithTag(TRIM_BAR_TAG).fetchSemanticsNode().boundsInRoot.top)
    }
}
