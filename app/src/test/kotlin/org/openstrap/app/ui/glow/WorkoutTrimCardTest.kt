package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.testing.ComposeRules
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.ui.components.GlassHost
import org.openstrap.app.ui.components.LocalGlassHost
import org.openstrap.app.ui.workout.PhoneSummary
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A past phone workout's trim lives on its heart rate card (the user's choice, 2026-11-04): Trim in the title row opens
 * the trimmer in a sheet, Save trims and closes it, and a trimmed workout says so on the card with Undo as a button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkoutTrimCardTest {
    private val compose = ComposeRules()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ZoneRule()).around(compose)

    private val start = 1_792_000_000L
    private val seconds = 1800L

    /** A half hour run recorded on the phone, its end moved to [end] when given (the rest kept). */
    private fun summary(end: Long? = null): PhoneSummary {
        val w = AppWorkout(start, end ?: (start + seconds), 1, averageHeartRate = 130, recordedEnd = end?.let { start + seconds })
        val hr = (0L..seconds step 5).map { AppWorkoutHeartRate(start, start + it, 110 + (it * 40 / seconds).toInt(), false) }
        return PhoneSummary(
            WorkoutRecorder.analyse(w, hr, 190, HeartZones.STANDARD), hr.map { WorkoutHeartRate(start, it.timestamp, it.bpm) },
            start, start + seconds, null,
        )
    }

    private fun show(s: PhoneSummary, onTrim: (Long, Long) -> Unit = { _, _ -> }, onUndo: () -> Unit = {}) {
        val host = GlassHost()
        compose.compose.setContent {
            CompositionLocalProvider(LocalGlassHost provides host) {
                GlowWorkoutDetailScreen(
                    s.analysis, s, listOf(s.analysis), null, PaddingValues(),
                    onBack = {}, onDelete = {}, onTrim = onTrim, onUndoTrim = onUndo,
                )
                // The sheet draws in the app's overlay, whose blur isn't needed to tap it.
                host.layers.forEach { layer -> key(layer) { layer.content.value() } }
            }
        }
    }

    @Test fun `Trim on the heart rate card opens the sheet, and Save trims and closes it`() {
        var trimmed: Pair<Long, Long>? = null
        show(summary(), onTrim = { f, t -> trimmed = f to t })
        compose.compose.onNodeWithText(SHEET_TITLE).assertDoesNotExist()

        compose.compose.onNodeWithText("Trim").performScrollTo().performClick()
        compose.compose.onNodeWithText(SHEET_TITLE).assertExists()
        // The handles start on the workout's times, so Save keeps them.
        compose.compose.onNodeWithText("Save").performScrollTo().performClick()

        assertEquals(start to start + seconds, trimmed)
        compose.compose.onNodeWithText(SHEET_TITLE).assertDoesNotExist()
    }

    @Test fun `a trimmed workout says so on the card, with Undo as a button`() {
        var undone = false
        show(summary(end = start + 1200), onUndo = { undone = true })
        compose.compose.onNodeWithText("Nothing is deleted.", substring = true).assertExists()

        val undo = compose.compose.onNodeWithText("Undo")
        undo.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        undo.performScrollTo().performClick()
        assertTrue(undone)
    }

    private companion object {
        const val SHEET_TITLE = "Trim start and end"
    }
}
