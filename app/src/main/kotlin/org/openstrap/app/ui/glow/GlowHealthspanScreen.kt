package org.openstrap.app.ui.glow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import org.openstrap.app.data.Healthspan
import org.openstrap.app.ui.dashboard.ScreenList
import kotlin.math.abs
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.R
import org.openstrap.app.tr

/** The scale on the Healthspan screen's bars: a habit this many years either side of the middle fills its half. */
private const val BAR_FULL_YEARS = 1.5f

/** "Updated Sunday, next in 5 days", from the Sunday the estimate was made. */
private fun updatedText(asOf: Long): String {
    val made = java.time.Instant.ofEpochSecond(asOf).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val next = 7 - java.time.temporal.ChronoUnit.DAYS.between(made, java.time.LocalDate.now()).toInt().coerceIn(0, 7)
    return when (next) {
        7 -> tr(R.string.glow_healthspan_screen_updated_today_next_on)
        1 -> tr(R.string.glow_healthspan_screen_updated_sunday_next_tomorrow)
        else -> tr(R.string.glow_healthspan_screen_updated_sunday_next_in, next)
    }
}

/**
 * The page behind the Habit Age card (spec 5.4): the same edge card at the top, its (i) holding the method, then the
 * pace it's moving at and what moves it; or, before an estimate, only the card saying why it waits.
 */
@Composable
fun GlowHealthspanScreen(state: Healthspan.Outcome?, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val r = state?.result
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.common_habit_age), onBack, subtitle = r?.let { updatedText(it.asOf) } ?: tr(R.string.glow_healthspan_screen_updated_weekly_on_sunday)) }
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
    ListPanel(title = tr(R.string.glow_healthspan_screen_pace_of_aging), note = pace?.let { "${paceLabel(it)}, ${paceText(it)}" } ?: paceLabel(null)) {
        ListRow(
            tr(R.string.glow_healthspan_screen_last_days), MetricColors.habitAge, null,
            when {
                pace == null -> tr(R.string.glow_healthspan_screen_needs_days_with_enough)
                pace < 1f -> tr(R.string.glow_healthspan_screen_your_last_days_beat)
                pace > 1f -> tr(R.string.glow_healthspan_screen_your_last_days_fall)
                else -> tr(R.string.glow_healthspan_screen_your_last_days_match)
            },
            divider = false, dash = false, icon = org.openstrap.app.ui.dashboard.Detail.Healthspan.rowIcon(),
        )
        if (pace != null) PaceScale(marker = (pace + 1f) / 4f, color = MetricColors.habitAge, Modifier.padding(start = RowIcon + RowIconGap, bottom = GlowLayout.RowPadding))
    }
}

/** A track from -1x to 3x with a dot at the pace, in the quiet panels' bar style (see [GlowBar]). */
@Composable
private fun PaceScale(marker: Float, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val y = size.height / 2
            val r = 5.dp.toPx()
            val x0 = r
            val x1 = size.width - r
            drawLine(Color.White.copy(alpha = 0.10f), Offset(x0, y), Offset(x1, y), 2.dp.toPx(), StrokeCap.Round)
            glowDot(Offset(x0 + (x1 - x0) * marker.coerceIn(0f, 1f), y), color)
        }
        Box(Modifier.fillMaxWidth()) {
            ScaleLabels(tr(R.string.glow_healthspan_screen_x_slower), "1x", tr(R.string.glow_healthspan_screen_x_faster))
        }
    }
}

/** [GlowBar]'s dot: a halo, the solid dot, and a white pip. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.glowDot(at: Offset, color: Color) {
    drawCircle(color.copy(alpha = 0.30f), 5.dp.toPx(), at)
    drawCircle(color, 3.dp.toPx(), at)
    drawCircle(Color.White, 1.2.dp.toPx(), at)
}

/**
 * Each habit against the typical person your age, as a grouped list (spec 5.4): the habit's metric icon in its ink, what
 * it takes off or adds and the numbers behind it, and its bar on the shared scale. No row opens anything.
 */
@Composable
private fun TermsPanel(r: Healthspan.Result) {
    ListPanel(title = tr(R.string.common_what_moves_it), note = tr(R.string.glow_healthspan_screen_over_months)) {
        // One scale for every bar below: a habit at either end takes off, or adds, BAR_FULL_YEARS or more.
        ScaleLabels(tr(R.string.glow_healthspan_screen_y_younger), "0", tr(R.string.glow_healthspan_screen_older_y), Modifier.padding(start = RowIcon + RowIconGap, top = 4.dp, bottom = 2.dp))
        r.terms.forEachIndexed { i, t ->
            val (icon, ink) = habitIcon(t.habit)
            ListRow(
                t.habit.label, ink, null, "${effectText(t.years).replaceFirstChar { it.uppercase() }}\n${termDetail(t, r.age)}",
                divider = i > 0, dash = false, icon = icon,
            )
            EffectBar(t.years, Modifier.padding(start = RowIcon + RowIconGap, bottom = GlowLayout.RowPadding))
        }
        if (r.fitnessCapped) Text(
            tr(R.string.glow_healthspan_screen_fitness_terms_capped_resting),
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
        val label = Color.White.copy(alpha = GlowType.CAPTION_ALPHA)
        Text(start, color = label, style = GlowType.caption, modifier = Modifier.align(Alignment.CenterStart))
        Text(middle, color = Color.White, style = GlowType.caption, modifier = Modifier.align(Alignment.Center))
        Text(end, color = label, style = GlowType.caption, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * One habit's effect as a bar out from the middle: left takes years off, right adds them. Drawn like [GlowBar], the quiet
 * panels' bar: a faint track, a glowing line and a dot at its end, green for years off and amber for years added. A habit
 * with no effect shows only the track and the middle mark.
 */
@Composable
private fun EffectBar(years: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val y = size.height / 2
        val r = 5.dp.toPx()
        val x0 = r
        val x1 = size.width - r
        val mid = size.width / 2
        drawLine(Color.White.copy(alpha = 0.10f), Offset(x0, y), Offset(x1, y), 2.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.35f), Offset(mid, y - 5.dp.toPx()), Offset(mid, y + 5.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
        if (abs(years) < 0.05f) return@Canvas
        // Habit Age's own ink either way: the side of the centre says better or worse, never green or amber.
        val color = MetricColors.habitAge
        val end = Offset(mid + (years / BAR_FULL_YEARS).coerceIn(-1f, 1f) * (mid - x0), y)
        drawLine(color.copy(alpha = 0.22f), Offset(mid, y), end, 6.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(mid, y), end, 2.2.dp.toPx(), StrokeCap.Round)
        glowDot(end, color)
    }
}
