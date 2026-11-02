package org.openstrap.app.ui.glow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.ComposeRules
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every leaf preview composes from its fixtures, in English and in Arabic right to left, so a preview that would only
 * fail when Android Studio opens it fails here first (audit K12). It lives in testDebug because the previews are in
 * the debug source set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LeafPreviewsTest {
    @get:Rule val rules = ComposeRules()
    private val compose get() = rules.compose

    private val previews: List<@Composable () -> Unit> = listOf(
        { ListRowPreview() },
        { EdgeCardPreview() },
        { GlowCardPreview() },
        { GlowBarsPreview() },
        { NormalTrendPreview() },
        { DetailTemplatePreview() },
        { DetailTemplateWaitingPreview() },
    )

    private val showing = mutableIntStateOf(0)

    /**
     * Shows each preview in turn in [direction], ending back on the first. The unit tests run without the manifest's
     * supportsRtl, so the Arabic run sets the direction itself, as Android Studio's renderer does for an `ar` preview.
     */
    private fun composeEach(direction: LayoutDirection) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) { previews[showing.intValue]() }
        }
        (previews.indices + 0).forEach { i ->
            compose.runOnIdle { showing.intValue = i }
            compose.waitForIdle()
        }
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun `every preview composes in English, left to right`() {
        composeEach(LayoutDirection.Ltr)
        assertTrue(shows("Breathing rate"))
    }

    @Config(qualifiers = "ar")
    @Test
    fun `every preview composes in Arabic, right to left, with its Arabic words`() {
        composeEach(LayoutDirection.Rtl)
        assertTrue(shows("معدل التنفس"))
    }
}
