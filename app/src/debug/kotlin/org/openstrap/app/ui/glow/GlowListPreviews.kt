package org.openstrap.app.ui.glow

import androidx.compose.runtime.Composable
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.widget.WidgetSamples

/** [ListRow] in a [ListPanel]: the sample morning's figures, an off normal row, a waiting row and a goal ring. */
@LeafPreviews
@Composable
internal fun ListRowPreview() {
    val recovery = WidgetSamples.recovery(PreviewDay, previewLocale())
    val inks = listOf(MetricColors.heart, MetricColors.hrv, MetricColors.sleepDeep)
    PreviewPage {
        ListPanel(title = say("Your body this morning", "جسمك هذا الصباح"), note = say("Against your normal", "مقارنة بمعدلك")) {
            recovery.figures.forEachIndexed { i, f ->
                ListRow(f.label, inks[i % inks.size], f.value, say("Usual", "معتاد"), divider = i > 0, onClick = {})
            }
            ListRow(
                name = say("Skin temperature", "حرارة الجلد"),
                color = MetricColors.temperature,
                value = "34.6",
                status = say("Above your normal", "أعلى من معدلك"),
                offNormal = true,
            )
            ListRow(say("Breathing rate", "معدل التنفس"), MetricColors.sleepLight, null, say("After your first night", "بعد ليلتك الأولى"))
            val steps = WidgetSamples.steps(PreviewDay, previewLocale())
            ListRow(steps.title, MetricColors.steps, steps.stepsText, steps.share, ring = steps.progress)
        }
    }
}
