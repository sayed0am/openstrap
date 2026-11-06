package org.openstrap.app.ui.glow

import androidx.compose.runtime.ReadOnlyComposable
import org.openstrap.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import org.openstrap.app.data.Healthspan
import org.openstrap.app.ui.dashboard.ScreenList
import kotlin.math.abs
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors

/** The scale on the Healthspan screen's bars: a habit this many years either side of the middle fills its half. */
private const val BAR_FULL_YEARS = 1.5f

/** "Updated Sunday, next in 5 days", from the Sunday the estimate was made. */
@Composable
@ReadOnlyComposable
private fun updatedText(asOf: Long): String {
    val made = java.time.Instant.ofEpochSecond(asOf).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val next = 7 - java.time.temporal.ChronoUnit.DAYS.between(made, java.time.LocalDate.now()).toInt().coerceIn(0, 7)
    return when (next) {
        7 -> stringResource(R.string.glow_healthspan_updated_today)
        1 -> stringResource(R.string.glow_healthspan_updated_tomorrow)
        else -> pluralStringResource(R.plurals.glow_healthspan_updated_next, next, next)
    }
}

/**
 * The page behind the Habit Age card (spec 5.4): the same picture card at the top, its (i) holding the method, then the
 * pace it's moving at and what moves it; or, before an estimate, only the card saying why it waits.
 */
@Composable
fun GlowHealthspanScreen(state: Healthspan.Outcome?, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val r = state?.result
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(
                stringResource(R.string.glow_healthspan_title), onBack,
                subtitle = r?.let { updatedText(it.asOf) } ?: stringResource(R.string.glow_healthspan_updated_weekly),
            ) }
        // The page is what the card opens, so here it opens nothing: no caret.
        item { HabitAgeEdgeCard(state, onClick = null, onOpenProfile = null) }
        if (r != null) {
            item { PacePanel(r.pace) }
            item { TermsPanel(r) }
        }
    }
}

/**
 * Where the last 30 days sit against the 6-month habits, as a grouped list (spec 5.4): the pace in the title row's
 * note, one row in Habit Age's ink saying what it means, and the scale from -1x to 3x under it. Opens nothing.
 */
@Composable
private fun PacePanel(pace: Float?) {
    ListPanel(
        title = stringResource(R.string.glow_healthspan_pace_title),
        note = pace?.let { stringResource(R.string.glow_healthspan_pace_note, paceLabel(it), paceText(it)) } ?: paceLabel(null),
    ) {
        ListRow(
            stringResource(R.string.glow_healthspan_last_30_days), MetricColors.habitAge, null,
            stringResource(
                when {
                    pace == null -> R.string.glow_healthspan_pace_needs
                    pace < 1f -> R.string.glow_healthspan_pace_beat
                    pace > 1f -> R.string.glow_healthspan_pace_behind
                    else -> R.string.glow_healthspan_pace_match
                },
            ),
            divider = false, dash = false, icon = org.openstrap.app.ui.dashboard.Detail.Healthspan.rowIcon(),
        )
        if (pace != null) PaceScale(marker = (pace + 1f) / 4f, color = MetricColors.habitAge, Modifier.padding(start = RowIcon + RowIconGap, bottom = GlowLayout.RowPadding))
    }
}

/** A track from -1x to 3x with a dot at the pace, in the quiet panels' bar style: a faint track and a [glowDot]. */
@Composable
private fun PaceScale(marker: Float, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        ScaleTrack { x0, x1, y -> glowDot(Offset(x0 + (x1 - x0) * marker.coerceIn(0f, 1f), y), color) }
        ScaleLabels(
            stringResource(R.string.glow_healthspan_scale_slower), stringResource(R.string.glow_healthspan_scale_steady),
            stringResource(R.string.glow_healthspan_scale_faster),
        )
    }
}

/**
 * The quiet panels' scale, which the pace and each habit's effect are drawn on: a faint 2 dp track 12 dp tall, in from
 * each end by a [glowDot]'s halo so a dot at the end shows whole. [marks] draws on it from the track's ends and middle.
 */
@Composable
private fun ScaleTrack(modifier: Modifier = Modifier, marks: DrawScope.(x0: Float, x1: Float, y: Float) -> Unit) {
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val y = size.height / 2
        val r = 5.dp.toPx()
        drawLine(Color.White.copy(alpha = 0.10f), Offset(r, y), Offset(size.width - r, y), 2.dp.toPx(), StrokeCap.Round)
        marks(r, size.width - r, y)
    }
}

/** The quiet panels' bar dot: a halo, the solid dot, and a white pip. */
private fun DrawScope.glowDot(at: Offset, color: Color) {
    drawCircle(color.copy(alpha = 0.30f), 5.dp.toPx(), at)
    drawCircle(color, 3.dp.toPx(), at)
    drawCircle(Color.White, 1.2.dp.toPx(), at)
}

/**
 * Each habit against its typical value, as a grouped list (spec 5.4): the habit's metric icon in its ink, what
 * it takes off or adds and the numbers behind it, and its bar on the shared scale. No row opens anything.
 */
@Composable
private fun TermsPanel(r: Healthspan.Result) {
    ListPanel(title = stringResource(R.string.glow_healthspan_terms_title), note = stringResource(R.string.glow_healthspan_terms_note)) {
        // One scale for every bar below: a habit at either end takes off, or adds, BAR_FULL_YEARS or more.
        ScaleLabels(
            stringResource(R.string.glow_healthspan_scale_younger), "0", stringResource(R.string.glow_healthspan_scale_older),
            Modifier.padding(start = RowIcon + RowIconGap, top = 4.dp, bottom = 2.dp),
        )
        r.terms.forEachIndexed { i, t ->
            val (icon, ink) = habitIcon(t.habit)
            ListRow(
                t.habit.label, ink, null, "${effectText(t.years).replaceFirstChar { it.uppercase() }}\n${termDetail(t, r.age)}",
                divider = i > 0, dash = false, icon = icon,
            )
            EffectBar(t.years, Modifier.padding(start = RowIcon + RowIconGap, bottom = GlowLayout.RowPadding))
        }
        if (r.fitnessCapped) Text(
            stringResource(R.string.glow_healthspan_fitness_capped),
            color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
            modifier = Modifier.padding(bottom = GlowLayout.RowPadding),
        )
    }
}

/** A habit's metric icon and ink, as its metric's rows draw them (spec 2.5): Load's barbell, the heart, steps, sleep. */
private fun habitIcon(h: Healthspan.Habit): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> = when (h) {
    Healthspan.Habit.Strength -> AppIcons.Barbell to MetricColors.load
    Healthspan.Habit.Vo2max -> AppIcons.FastWind to MetricColors.heart
    Healthspan.Habit.Steps -> AppIcons.Footprints to MetricColors.steps
    Healthspan.Habit.Regularity, Healthspan.Habit.TimeAsleep -> AppIcons.Moon to MetricColors.sleepDeep
    Healthspan.Habit.RestingHr -> AppIcons.Heart to MetricColors.heart
}

/** A scale's ends and middle in Caption, the middle in white. */
@Composable
private fun ScaleLabels(start: String, middle: String, end: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        ScaleLabel(start, 0f)
        ScaleLabel(middle, at = 0.5f, color = Color.White)
        ScaleLabel(end, 1f)
    }
}

/** A scale's label in Caption, centred on [at] (0 to 1) across the scale and kept inside it. */
@Composable
internal fun BoxScope.ScaleLabel(text: String, at: Float, color: Color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA)) {
    // Centred on its point by aligning a share of the way across, as BiasAlignment does.
    Text(text, color = color, style = GlowType.caption, modifier = Modifier.align(BiasAlignment((at * 2f - 1f).coerceIn(-1f, 1f), 0f)))
}

/**
 * One habit's effect as a bar out from the middle: left takes years off, right adds them. Drawn in the quiet
 * panels' bar style: a faint track, a glowing line and a dot at its end, in Habit Age's own ink either way. A habit
 * with no effect shows only the track and the middle mark.
 */
@Composable
private fun EffectBar(years: Float, modifier: Modifier = Modifier) {
    ScaleTrack(modifier) { x0, _, y ->
        val mid = size.width / 2
        drawLine(Color.White.copy(alpha = 0.35f), Offset(mid, y - 5.dp.toPx()), Offset(mid, y + 5.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
        if (abs(years) < 0.05f) return@ScaleTrack
        // Habit Age's own ink either way: the side of the centre says better or worse, never green or amber.
        val color = MetricColors.habitAge
        val end = Offset(mid + (years / BAR_FULL_YEARS).coerceIn(-1f, 1f) * (mid - x0), y)
        drawLine(color.copy(alpha = 0.22f), Offset(mid, y), end, 6.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(mid, y), end, 2.2.dp.toPx(), StrokeCap.Round)
        glowDot(end, color)
    }
}
