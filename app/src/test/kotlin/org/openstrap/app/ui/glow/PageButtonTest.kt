package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.ComposeRules
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A disabled page button dims as a whole, its pill with its label, so it never reads half on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageButtonTest {
    @get:Rule val rules = ComposeRules()

    /** The red of the pill on black, beside its label, for a primary button [enabled] or not. */
    private fun pill(enabled: Boolean): Float {
        rules.compose.setContent {
            Box(Modifier.background(Color.Black)) {
                PageButton("Save", {}, Modifier.width(240.dp).testTag("button"), enabled = enabled, primary = true)
            }
        }
        val pixels = rules.compose.onNodeWithTag("button").captureToImage().toPixelMap()
        return pixels[pixels.width / 10, pixels.height / 2].red
    }

    @Test fun `an enabled primary button's pill is white`() {
        assertEquals(1f, pill(enabled = true), 0.02f)
    }

    @Test fun `a disabled primary button's pill dims with its label`() {
        val red = pill(enabled = false)
        assertTrue("pill at $red", red in 0.3f..0.5f)
    }
}
