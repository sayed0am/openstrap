package org.openstrap.app.ui.glow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * Dashboard blocks are keyed on their cards: a card added above another (a pin in Edit Today) leaves the state saved
 * inside the other with it, rather than handing it to whichever card now sits in its place (audit K5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashboardKeysTest {
    @get:Rule val rules = ComposeRules()
    private val compose get() = rules.compose

    /** A card that counts its taps in saved state, as a card's open info sheet is kept. */
    @Composable
    private fun Counter(name: String) {
        var taps by rememberSaveable { mutableIntStateOf(0) }
        Text("$name $taps", Modifier.clickable { taps++ })
    }

    @Test fun `a card's saved state stays with it when a card is added above it`() {
        var pinned by mutableStateOf(false)
        compose.setContent {
            GlowScreenList(PaddingValues(), rememberLazyListState()) {
                add(Pane.Full, GlowBlocks.HEADER) { Text("Header") }
                if (pinned) add(Pane.Full, GlowBlocks.cardsKey(listOf("Pinned"))) { Counter("Pinned") }
                add(Pane.Full, GlowBlocks.cardsKey(listOf("Steps"))) { Counter("Steps") }
            }
        }
        compose.onNodeWithText("Steps 0").performClick()
        compose.onNodeWithText("Steps 1").assertExists()

        pinned = true
        compose.waitForIdle()

        compose.onNodeWithText("Steps 1").assertExists()
        compose.onNodeWithText("Pinned 0").assertExists()
    }

    @Test fun `a repeated key is made unique rather than crash the list`() {
        assertEquals(listOf("a", "b", "a#1", "a#2"), uniqueKeys(listOf("a", "b", "a", "a")))
    }

    @Test fun `a row's key is its cards in order`() {
        assertEquals("Steps+Load", GlowBlocks.cardsKey(listOf("Steps", "Load")))
    }
}
