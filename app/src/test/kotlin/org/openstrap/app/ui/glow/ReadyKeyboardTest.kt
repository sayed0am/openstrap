package org.openstrap.app.ui.glow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.ComposeRules
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ready's goal typing ends once the keyboard goes: the keyboard takes the first Back, and before, the caret stayed and
 * Start stayed hidden until a second Back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadyKeyboardTest {
    @get:Rule val rules = ComposeRules()

    @Test fun `typing ends when the keyboard that came up for it goes`() {
        var typing by mutableStateOf(false)
        var keyboard by mutableStateOf(false)
        var ended = 0
        rules.compose.setContent { EndTypingWithKeyboard(typing, { keyboard }) { ended++; typing = false } }

        // Typing starts before the keyboard is up: nothing ends yet.
        typing = true
        rules.compose.waitForIdle()
        assertEquals(0, ended)
        keyboard = true
        rules.compose.waitForIdle()
        assertEquals(0, ended)

        // Back closes the keyboard.
        keyboard = false
        rules.compose.waitForIdle()
        assertEquals(1, ended)
        assertEquals(false, typing)
    }

    @Test fun `a keyboard going while not typing ends nothing`() {
        var keyboard by mutableStateOf(true)
        var ended = 0
        rules.compose.setContent { EndTypingWithKeyboard(false, { keyboard }) { ended++ } }
        keyboard = false
        rules.compose.waitForIdle()
        assertEquals(0, ended)
    }
}
