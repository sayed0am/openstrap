package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.openstrap.app.presentation.DetailCopy
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.widget.RecoveryWidgetModel
import org.openstrap.app.widget.WidgetSamples

/** [GlowBars] over the sample week's steps, today lit, with the day letters under them. */
@LeafPreviews
@Composable
internal fun GlowBarsPreview() {
    val week = WidgetSamples.steps(PreviewDay, previewLocale()).week
    PreviewPage {
        GlowBars(week.map { (it.steps ?: 0).toFloat() }, labels = week.map { it.letter }, accent = MetricColors.steps)
        Spacer(Modifier.height(GlowGrid.Gap))
        // No data: nothing drawn, no baseline of empty columns.
        GlowBars(emptyList())
    }
}

/** [NormalTrend] over the sample week's Recovery, with your normal as the band and days outside it lit. */
@LeafPreviews
@Composable
internal fun NormalTrendPreview() {
    val recovery = WidgetSamples.recovery(PreviewDay, previewLocale())
    PreviewPage {
        RecoveryTrend(recovery, say("Your normal", "معدلك"))
    }
}

/** The sample week's Recovery as a [NormalTrend], its normal 60 to 80. */
@Composable
internal fun RecoveryTrend(recovery: RecoveryWidgetModel, bandLabel: String, modifier: Modifier = Modifier) {
    val values = recovery.week.map { it.score?.toFloat() }
    val band = 60f..80f
    NormalTrend(
        values = values,
        levels = values.map { v ->
            when {
                v == null -> null
                v > band.endInclusive -> DetailCopy.Level.Above
                v < band.start -> DetailCopy.Level.Below
                else -> DetailCopy.Level.Inside
            }
        },
        color = MetricColors.readiness,
        modifier = modifier,
        labels = recovery.week.map { it.letter },
        band = band,
        bandLabel = bandLabel,
    )
}
