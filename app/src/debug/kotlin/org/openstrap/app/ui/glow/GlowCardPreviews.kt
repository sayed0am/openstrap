package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.widget.WidgetSamples

/**
 * [GlowCard] as the hero and as a softer second card. The light is blurred off the main thread, so a static preview
 * may show the slab before the glow: use the interactive mode to see it settle.
 */
@LeafPreviews
@Composable
internal fun GlowCardPreview() {
    val recovery = WidgetSamples.recovery(PreviewDay, previewLocale())
    PreviewPage {
        GlowCard(GlowStyle.Ember.copy(lightLevel = 0.78f), Modifier.fillMaxWidth().height(GlowGrid.UnitHeight)) {
            Column(Modifier.fillMaxSize()) {
                Text(recovery.title, color = Color.White, style = GlowType.cardTitle)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
                    recovery.score?.let { DotMatrixText(it, dot = DotSize.Edge) }
                    recovery.word?.let { Text(it, color = Color.White, style = GlowType.secondary) }
                }
            }
        }
        Spacer(Modifier.height(GlowGrid.Gap))
        GlowCard(GlowStyle.Ember.copy(lightLevel = 0.62f, second = true), Modifier.fillMaxWidth().height(GlowGrid.UnitHeight)) {
            Text(say("Energy", "الطاقة"), color = Color.White, style = GlowType.cardTitle)
        }
    }
}
