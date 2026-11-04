package org.openstrap.app.ui.glow

import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.ComposeRules
import org.openstrap.app.ui.components.GlassHost
import org.openstrap.app.ui.components.LocalGlassHost
import org.openstrap.app.ui.dashboard.TodayCard
import org.openstrap.app.ui.dashboard.TodayEdits
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edit Today's pins and hides, not yet saved, come back after the activity is recreated (a rotation or a fold), and
 * through a parcelled bundle as after process death (audit K7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditTodayRestoreTest {
    @get:Rule val rules = ComposeRules()
    private val compose get() = rules.compose

    private val editable = TodayCard.entries.filter { it.editable }
    private val pinnable = editable.filter { it.pinnable }

    @Test fun `pins and hides made in the sheet are still there after a recreate, and Done saves them`() {
        val host = GlassHost()
        var saved: TodayEdits? = null
        val tester = StateRestorationTester(compose)
        tester.setContent {
            CompositionLocalProvider(LocalGlassHost provides host) {
                EditTodaySheet(TodayEdits(), onSave = { saved = it }, onDismiss = {})
                // The sheet draws in the app's overlay; the overlay's blur isn't needed to tap it.
                host.layers.forEach { layer -> key(layer) { layer.content.value() } }
            }
        }
        val hidden = editable.last { !it.pinnable }
        compose.onAllNodesWithText("Pin")[0].performClick()
        compose.onAllNodesWithText("Pin")[0].performClick()
        compose.onAllNodes(isToggleable())[editable.indexOf(hidden)].performScrollTo().performClick()
        val expected = TodayEdits().togglePin(pinnable[0]).togglePin(pinnable[1]).toggleHidden(hidden)

        tester.emulateSavedInstanceStateRestore()

        compose.onAllNodesWithText("Pinned").assertCountEquals(2)
        // Done sits under the rows, scrolled to as a finger would.
        compose.onNodeWithText("Done").performScrollTo().performClick()
        assertEquals(expected, saved)
    }

    @Test fun `the saved edits are plain values a bundle keeps through a parcel`() {
        val edits = TodayEdits().togglePin(pinnable[1]).togglePin(pinnable[0]).toggleHidden(editable.last { !it.pinnable })
        val saved = with(TodayEditsSaver) { SaverScope { true }.save(edits) }
        val bundle = Bundle().apply { putSerializable(KEY, saved as java.io.Serializable) }
        val parcel = Parcel.obtain()
        val back = try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            parcel.readBundle(javaClass.classLoader)!!
        } finally {
            parcel.recycle()
        }
        @Suppress("DEPRECATION")
        val restored = TodayEditsSaver.restore(back.getSerializable(KEY)!!)
        assertEquals(edits, restored)
        // Pins keep their order: the first pinned sits first under the hero.
        assertEquals(listOf(pinnable[1], pinnable[0]), restored?.pinned)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) =
        assertEquals(n, fetchSemanticsNodes().size)

    private companion object {
        const val KEY = "edits"
    }
}
