package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.presentation.WorkoutWords
import org.openstrap.app.testing.ComposeRules
import org.openstrap.app.testing.appWorkout
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The live top bar with the strap lost says what every other screen says, whole, on a 360 dp phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveTopBarTest {
    @get:Rule val rules = ComposeRules()

    @Test fun `the strap lost reads Waiting for the strap, not cut, beside a long sport name`() {
        val now = System.currentTimeMillis() / 1000
        val r = WorkoutState.Recording(appWorkout(start = now - 600, end = null), maxHeartRate = 190)
        rules.compose.setContent {
            LiveFrame(
                r, Color.Green, connected = false, PaddingValues(), LiveControls({}, {}, {}, {}, {}, {}),
                title = "Outdoor cycling, intervals", status = listOf("GPS good" to Color.Green),
            ) {}
        }
        val node = rules.compose.onNodeWithText(WorkoutWords.STRAP_GONE).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
        val l = layout.first()
        // Cut would be a second line dropped, or less room than the words take on one line.
        val whole = l.multiParagraph.intrinsics.maxIntrinsicWidth
        assertFalse("the words are cut to a line: ${node.boundsInRoot}", l.didOverflowHeight)
        assertTrue("${l.size.width} px for words $whole px wide", l.size.width >= kotlin.math.floor(whole))
        val root = rules.compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("past the screen's edge: ${node.boundsInRoot}", node.boundsInRoot.right <= root.right)
    }
}
