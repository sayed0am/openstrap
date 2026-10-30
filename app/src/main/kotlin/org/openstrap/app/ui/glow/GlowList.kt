package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.components.LocalCardNumbers
import org.openstrap.app.ui.components.cardNumber
import org.openstrap.app.ui.components.cardOpening
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * The quiet tier of the glow language: a grouped list for small facts (body signals, time to recover, energy) and
 * for lists of things (workouts, contributors, settings). No glow at all, so the cards above it keep the light.
 */

internal val ListSurface = Color(0xFF0F0F11)
internal val RowHairline = Color.White.copy(alpha = GlowLayout.DividerAlpha)

/**
 * A grouped list: a dark panel with a title row ([title] left, an optional [note] right) over its rows, usually
 * [ListRow]s. Without a title the rows start at the top. With [onNote] the note is a way on ("All 9"), with a caret. When the screen asks for an edge ([LocalEdgeOverride]), as
 * Today does for a list right under another, the rows sit on an edge card in the neutral light instead.
 */
@Composable
fun ListPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    note: String? = null,
    onNote: (() -> Unit)? = null,
    /** A sync marker next to the title ([StateMarker]), "Up to 09:40". */
    marker: String? = null,
    /** A neutral tag next to the title when there's no [marker] ([TitleMark]), "Partial day". */
    tag: TitleTag? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val marked = marker != null || tag != null
    val inner: @Composable ColumnScope.() -> Unit = {
        // The first row's own padding sits under it, so the title row needs only a little below. The note drops
        // under the title when they can't share a line, so the title never wraps.
        if (title != null || note != null) TitleRow(
            Modifier.padding(top = GlowLayout.RowPadding, bottom = 6.dp),
            start = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title.orEmpty(), color = Color.White, style = GlowType.cardTitle, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (marked) TitleMark(marker, tag, Modifier.padding(start = 8.dp))
                }
            },
            end = note?.let { n -> { ListNote(n, onNote) } },
        )
        content()
    }
    // Rows reach out over the side padding, so a tap lights the whole row, edge to edge. The card clips them to its
    // corners.
    val rows: @Composable ColumnScope.() -> Unit = { CompositionLocalProvider(LocalRowBleed provides GlowLayout.CardInset) { inner() } }
    if (LocalEdgeOverride.current.edge != null) {
        EdgeCard(EdgeNeutral, modifier.fillMaxWidth().clip(GlowLayout.CardShape), contentPadding = GlowLayout.ListPadding, content = rows)
        return
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(GlowLayout.CardShape)
            .background(ListSurface, GlowLayout.CardShape)
            .border(1.dp, RowHairline, GlowLayout.CardShape)
            .padding(GlowLayout.ListPadding),
        content = rows,
    )
}

/** How far a [ListRow] reaches past its list's side padding: the padding itself inside a [ListPanel], else nothing. */
internal val LocalRowBleed = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/**
 * Lays the row out [bleed] wider on each side than its slot, so what's drawn and tapped next (the press light) spans
 * the list edge to edge, while the row's own content keeps to the slot.
 */
private fun Modifier.bleed(bleed: androidx.compose.ui.unit.Dp): Modifier = if (bleed == 0.dp) this else layout { m, c ->
    val e = bleed.roundToPx()
    val p = m.measure(c.copy(minWidth = c.minWidth + 2 * e, maxWidth = if (c.hasBoundedWidth) c.maxWidth + 2 * e else c.maxWidth))
    layout((p.width - 2 * e).coerceAtLeast(0), p.height) { p.place(-e, 0) }
}

/** A list title row's note: a way on ("All 9") with its caret when [onNote] is given, else plain Secondary. */
@Composable
private fun ListNote(note: String, onNote: (() -> Unit)?) {
    if (onNote == null) return NoteText(note)
    Row(
        // Tapped, the note grows into what it opens (spec 3.5).
        cardOpening(onNote).let { o -> Modifier.then(o?.modifier ?: Modifier).clickable(onClick = o?.onClick ?: onNote) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(note, color = Color.White, style = GlowType.secondary, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        TapCaret(CARD_CARET_ALPHA)
    }
}

/**
 * A row in a [ListPanel]: the metric's [icon] in its ink [color] (or in [iconTint], as a workout's sport in white), the
 * [name] with its [status] under it, the [value] in dot-matrix digits in its own column on the right, and the tap caret
 * when [onClick] opens something (spec 2.5, Grouped list). Value and status take the metric colour only when
 * [offNormal]. A null [value] is a waiting row: a dim dash, the icon at 30%, and [status] says what it waits for ("After
 * your first night"). A row that isn't a metric passes no icon. [divider] draws the hairline above the row.
 */
@Composable
fun ListRow(
    name: String,
    color: Color,
    value: String?,
    status: String?,
    modifier: Modifier = Modifier,
    offNormal: Boolean = false,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    /** A null value draws the dim dash; false leaves the value column empty, for a row whose [status] is the answer. */
    dash: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color = color,
    /** A goal's share (0..1): the icon's place shows a small ring filling to it in [color] instead (spec 2.7, Progress ring). */
    ring: Float? = null,
) {
    val waiting = value == null && dash
    val accent = if (offNormal && !waiting) color else Color.White
    // Tapped, the row grows into what it opens (spec 3.5).
    val opening = cardOpening(onClick)
    val bleed = LocalRowBleed.current
    Row(
        modifier
            .fillMaxWidth()
            .bleed(bleed)
            .then(opening?.modifier ?: Modifier)
            .let { if (onClick != null) it.clickable(onClick = opening?.onClick ?: onClick) else it }
            // The hairline keeps to the list's padding, as before.
            .let { if (divider) it.drawBehind { val e = bleed.toPx(); drawLine(RowHairline, Offset(e, 0f), Offset(size.width - e, 0f), 1.dp.toPx()) } else it }
            .padding(horizontal = bleed, vertical = GlowLayout.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ring != null) {
            RowRing(ring, color, Modifier.size(RowIcon))
            Spacer(Modifier.width(RowIconGap))
        } else if (icon != null) {
            Icon(icon, null, tint = iconTint.copy(alpha = iconTint.alpha * if (waiting) WAITING_ICON_ALPHA else 1f), modifier = Modifier.size(RowIcon))
            Spacer(Modifier.width(RowIconGap))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RowName(name)
            if (status != null) Text(
                status,
                color = when {
                    waiting -> Color.White.copy(alpha = 0.45f)
                    offNormal -> color
                    else -> Color.White.copy(alpha = 0.6f)
                },
                style = GlowType.secondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (value != null) CompositionLocalProvider(LocalCardNumbers provides opening?.numbers) {
            Text(value, Modifier.cardNumber(value), color = accent, style = GlowType.rowValue, maxLines = 1)
        }
        else if (dash) WaitingDash()
        if (onClick != null) {
            Spacer(Modifier.width(RowCaretGap))
            TapCaret(ROW_CARET_ALPHA)
        }
    }
}

/**
 * A row's progress ring (spec 2.7): a track of white at 12% and the [share] of a goal from the top, clockwise, in
 * [color], round caps, as thick as an icon's stroke reads at 20 dp. Full once the goal is met.
 */
@Composable
private fun RowRing(share: Float, color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2 + 1.dp.toPx()
        val box = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        val at = Offset(inset, inset)
        drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, at, box, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        val sweep = 360f * share.coerceIn(0f, 1f)
        if (sweep > 0f) drawArc(
            color, -90f, sweep, false, at, box,
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/** A row's icon, and the gap after it, as on the You and Strap pages. */
internal val RowIcon = 20.dp
internal val RowIconGap = 14.dp
/** From a row's value to its tap caret. */
internal val RowCaretGap = 8.dp
/** A row icon that isn't a metric's (a workout's sport): white at 72% (spec 2.8). */
internal val SportIconTint = Color.White.copy(alpha = 0.72f)
/** A waiting row's icon is dimmed to this. */
private const val WAITING_ICON_ALPHA = 0.3f

/* -------------------------------------------- Row icons --------------------------------------------- */

/** A metric's row icon (spec 2.5, Grouped list), drawn in its ink. Hugeicons names in the comments. */
internal fun Metric.rowIcon(): ImageVector = when (this) {
    Metric.HeartRate, Metric.RestingHr -> AppIcons.Heart // Favourite
    Metric.Hrv -> AppIcons.Pulse01
    Metric.Spo2 -> AppIcons.Drop // Droplet
    Metric.Stress -> AppIcons.Brain02
    Metric.SkinTemperature -> AppIcons.ThermometerSimple // Thermometer
    Metric.Breathing -> AppIcons.Lungs
    Metric.Steps -> AppIcons.Footprints // FootPrints
}

/**
 * A score's row icon. Recovery, which the spec's table leaves out, takes its tab's icon; Active minutes a timer;
 * Heart points the heart, in its ink.
 */
internal fun Score.rowIcon(): ImageVector = when (this) {
    Score.Recovery -> AppIcons.Plant
    Score.Charge -> AppIcons.BatteryCharging // BatteryCharging01
    Score.Sleep, Score.Regularity, Score.Naps -> AppIcons.Moon // Moon02
    Score.HeartPoints -> AppIcons.Heart // Favourite
    Score.ActiveMinutes -> AppIcons.Timer
    Score.Load -> AppIcons.Barbell // Dumbbell01
    Score.Vo2Max -> AppIcons.FastWind
    Score.Recover -> AppIcons.Hourglass
    Score.Energy -> AppIcons.Fire
    Score.Distance -> AppIcons.Route01
}

/** The row icon for what a row opens, when it's a metric's; null for anything else. */
internal fun Detail.rowIcon(): ImageVector? = when (this) {
    is Detail.OfMetric -> metric.rowIcon()
    is Detail.OfScore -> score.rowIcon()
    Detail.Recovery -> Score.Recovery.rowIcon()
    Detail.Sleep -> Score.Sleep.rowIcon()
    // Habit Age and the workouts aren't in the spec's table: an age in years, and a runner apart from Load's barbell.
    Detail.Healthspan -> AppIcons.CalendarBlank
    Detail.AllWorkouts -> AppIcons.PersonSimpleRun
    else -> null
}

/** The empty value: a short dim bar drawn by the UI, never a dash character. */
@Composable
fun WaitingDash(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(14.dp, 3.dp)
            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
            .semantics { contentDescription = tr(R.string.glow_list_no_value_yet) },
    )
}
