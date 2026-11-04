package org.openstrap.app.ui.glow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.ComposeRules
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A switch row flips from a tap anywhere on it, not only on the switch, so the Strap page's rows (Off-body reminder,
 * Auto-detect workouts) kept their whole-row tap when they moved onto it. A screen reader finds one switch per row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageSwitchRowTest {
    @get:Rule val rules = ComposeRules()

    private fun row(enabled: Boolean = true) {
        rules.compose.setContent {
            var on by remember { mutableStateOf(false) }
            PageSwitchRow("Off-body reminder", "Buzzes when the strap comes off", on, { on = it }, enabled = enabled, icon = org.openstrap.app.ui.theme.AppIcons.HandPalm)
        }
    }

    @Test fun `a tap on the name flips the switch`() {
        row()
        val node = rules.compose.onNode(isToggleable())
        node.assertIsOff()
        rules.compose.onNodeWithText("Off-body reminder").performClick()
        node.assertIsOn()
        rules.compose.onNodeWithText("Buzzes when the strap comes off").performClick()
        node.assertIsOff()
    }

    @Test fun `the row is the one switch a screen reader finds`() {
        row()
        assertEquals(1, rules.compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }

    @Test fun `a disabled row stays as it is`() {
        row(enabled = false)
        rules.compose.onNodeWithText("Off-body reminder").performClick()
        rules.compose.onNode(isToggleable()).assertIsOff()
    }
}
