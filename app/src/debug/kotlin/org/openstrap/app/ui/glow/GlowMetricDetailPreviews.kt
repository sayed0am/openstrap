package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import org.openstrap.app.presentation.DetailCopy
import org.openstrap.app.presentation.Range
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.OpenStrapTheme
import org.openstrap.app.widget.WidgetSamples

/** [DetailTemplate] for the sample morning's Recovery: the hero, its week strip, the range pills and the trend. */
@PagePreviews
@Composable
internal fun DetailTemplatePreview() {
    val locale = previewLocale()
    val recovery = WidgetSamples.recovery(PreviewDay, locale)
    val withData = (0L..6L).map { PreviewDay.minusDays(it) }.toSet()
    OpenStrapTheme {
        DetailTemplate(
            title = recovery.title,
            color = MetricColors.readiness,
            siblings = listOf(
                DetailSibling(recovery.title, current = true, icon = null) {},
                DetailSibling("HRV", current = false, icon = null) {},
            ),
            onInfo = {},
            onBack = {},
            contentPadding = PaddingValues(),
            headline = DetailHeadline(recovery.score, recovery.note.orEmpty(), recovery.word),
            strip = DetailCopy.strip(PreviewDay, PreviewDay, withData, locale),
            onDay = {},
            ranges = Range.entries,
            range = Range.Week,
            onRange = {},
        ) {
            item { RecoveryTrend(recovery, say("Your normal", "معدلك")) }
        }
    }
}

/** [DetailTemplate] with no reading yet: the state as a word in the value's place. */
@PagePreviews
@Composable
internal fun DetailTemplateWaitingPreview() {
    OpenStrapTheme {
        DetailTemplate(
            title = say("Skin temperature", "حرارة الجلد"),
            color = MetricColors.temperature,
            siblings = emptyList(),
            onInfo = {},
            onBack = {},
            contentPadding = PaddingValues(),
            headline = DetailHeadline.none(say("Learning", "يتعلم"), say("After your third night", "بعد ليلتك الثالثة")),
            strip = DetailCopy.strip(PreviewDay, PreviewDay, emptySet(), previewLocale()),
            onDay = {},
            ranges = listOf(Range.Week),
            range = Range.Week,
            onRange = {},
        ) {}
    }
}
