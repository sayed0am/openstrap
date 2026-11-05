package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.glow.draw.DotSize


import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.widget.WidgetSamples

/** [PictureCard] usual and rimmed. */
@LeafPreviews
@Composable
internal fun PictureCardPreview() {
    val steps = WidgetSamples.steps(PreviewDay, previewLocale())
    PreviewPage {
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            PictureCard(MetricColors.heart, Modifier.weight(1f).height(GlowGrid.UnitHeight)) {
                CardText(say("Resting heart rate", "نبض الراحة"), "52", say("Usual", "معتاد"))
            }
            PictureCard(MetricColors.temperature, Modifier.weight(1f).height(GlowGrid.UnitHeight), rim = Rim.Top) {
                CardText(
                    title = say("Skin temperature", "حرارة الجلد"),
                    value = "34.6",
                    status = say("Above your normal", "أعلى من معدلك"),
                    valueColor = MetricColors.temperature,
                )
            }
        }
        Spacer(Modifier.height(GlowGrid.Gap))
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            PictureCard(MetricColors.hrv, Modifier.weight(1f).height(GlowGrid.UnitHeight), rim = Rim.End) {
                CardText("HRV", "41", say("Below your normal", "أقل من معدلك"), MetricColors.hrv)
            }
            PictureCard(
                ink = MetricColors.steps,
                modifier = Modifier.weight(1f).height(GlowGrid.UnitHeight),
            ) { CardText(steps.title, steps.stepsText, steps.share) }
        }
        Spacer(Modifier.height(GlowGrid.Gap))
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            PictureCard(MetricColors.heart, Modifier.weight(1f).height(GlowGrid.UnitHeight)) {
                CardText(say("Heart rate", "نبض القلب"), "71", say("Now", "الآن"))
            }
            PictureCard(MetricColors.sleepDeep, Modifier.weight(1f).height(GlowGrid.UnitHeight)) {
                CardText(say("Last night", "الليلة الماضية"), "7:58", say("Asleep", "نوم"))
            }
        }
    }
}

/** A card's title at the top, its number and status at the foot, as Today's cards lay them out. */
@Composable
private fun ColumnScope.CardText(title: String, value: String, status: String, valueColor: Color = Color.White) {
    Text(title, color = Color.White, style = GlowType.cardTitle)
    Spacer(Modifier.weight(1f))
    DotMatrixText(value, dot = DotSize.Edge, color = valueColor)
    Text(
        status,
        color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
        style = GlowType.secondary,
        modifier = Modifier.padding(top = 6.dp),
    )
}
