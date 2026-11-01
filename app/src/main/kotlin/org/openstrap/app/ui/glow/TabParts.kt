package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openstrap.app.ui.components.cardOpening
import org.openstrap.app.ui.dashboard.CardLook
import org.openstrap.app.ui.dashboard.Hue
import org.openstrap.app.ui.dashboard.TabSlot
import org.openstrap.app.ui.dashboard.Tier
import org.openstrap.app.ui.dashboard.items

/*
 * What the Recovery, Sleep and Activity tabs share: laying out a tab's planned cards ([tabCards]) with the edge each
 * one's look fixes, and the rows their lists need beyond [ListRow].
 */

/**
 * The edge card override that draws [this] look: the side it fixes, and the neutral light for a neutral card. Glow
 * cards and lists get none, so a list stays a list.
 */
internal fun CardLook.edgeOverride(): EdgeOverride = when (tier) {
    Tier.Edge, Tier.Strip -> EdgeOverride(edge = edge, color = EdgeNeutral.takeIf { hue == Hue.Neutral })
    // An unlit card drawn as an edge card (Activity's Start a workout, Plain) has no light; a list ignores it.
    Tier.List, Tier.Plain -> EdgeOverride(plain = true)
    else -> EdgeOverride()
}

/**
 * Adds a tab's [slots] to a dashboard as grid rows ([packGrid]), each row in its first card's pane ([tabPanes]), every card drawn
 * by [draw] under the edge its look fixes. [draw] gets the modifier that sizes a Small or a Tall in its row; a
 * full-width card is as high as its content. The row holding [focus] is the one the list scrolls to when asked.
 */
internal fun <C> GlowBlocks.tabCards(slots: List<TabSlot<C>>, focus: C? = null, draw: @Composable (C, Modifier) -> Unit) {
    // On columns too narrow for Smalls, a row of them spans both columns ([GlowBlocks.smallsAcross]).
    val panes = org.openstrap.app.ui.dashboard.tabPanes(slots, smallsAcross)
    packGrid(slots.indices.map { slots[it].size to it }).forEach { row ->
        val pane = panes[row.items().first()]
        if (row.items().any { slots[it].card == focus }) focusNext()
        val block: @Composable () -> Unit = {
            GridRowBlock(row) { i, m ->
                val slot = slots[i]
                CompositionLocalProvider(LocalEdgeOverride provides slot.look.edgeOverride()) { draw(slot.card, m) }
            }
        }
        when (pane) {
            Pane.Full -> full(block)
            Pane.Left -> left(block)
            Pane.Right -> right(block)
        }
    }
}

/**
 * One grid row as the screens draw it: a full-width card as given, two Smalls side by side at a unit's height, or a
 * Tall beside two stacked Smalls. [draw] gets each card's sizing modifier.
 */
@Composable
internal fun <T> GridRowBlock(row: GridRow<T>, draw: @Composable (T, Modifier) -> Unit) {
    when (row) {
        is GridRow.Full -> draw(row.item, Modifier.fillMaxWidth())
        is GridRow.Pair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            draw(row.left, Modifier.weight(1f).height(GlowGrid.UnitHeight))
            draw(row.right, Modifier.weight(1f).height(GlowGrid.UnitHeight))
        }
        is GridRow.TallPair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            draw(row.tall, Modifier.weight(1f).height(GlowGrid.height(CardSize.Tall)!!))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
                draw(row.top, Modifier.fillMaxWidth().height(GlowGrid.UnitHeight))
                draw(row.bottom, Modifier.fillMaxWidth().height(GlowGrid.UnitHeight))
            }
        }
    }
}

/**
 * A contributor bar (chart kit): the [name] on its own line, so it never wraps beside the bar, then a bar filled to
 * [fraction] (0..1) with the [word] after it. Bar and word take [color] only when [offNormal]; otherwise they stay
 * white. [detail] is a dim line under the name (the reading behind it), [onClick] opens what the row is part of (with
 * the tap caret at the end), and [divider] draws the hairline above the row.
 */
@Composable
internal fun ContributorRow(
    name: String, color: Color, word: String, fraction: Float, offNormal: Boolean = false, divider: Boolean = true,
    detail: String? = null, onClick: (() -> Unit)? = null,
) {
    val accent = if (offNormal) color else Color.White.copy(alpha = 0.7f)
    // Tapped, the row grows into what it opens (spec 3.5).
    val opening = cardOpening(onClick)
    Row(
        Modifier.fillMaxWidth()
            .then(opening?.modifier ?: Modifier)
            .let { if (onClick != null) it.clickable(onClick = opening?.onClick ?: onClick) else it }
            .let { if (divider) it.drawBehind { drawLine(RowHairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else it }
            .padding(vertical = GlowLayout.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, style = GlowType.body, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (detail != null) Text(
                detail, color = Color.White.copy(alpha = 0.5f), style = GlowType.secondary.copy(fontSize = 11.5.sp), maxLines = 2,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val shape = RoundedCornerShape(3.dp)
                Box(Modifier.weight(1f).height(6.dp).background(Color.White.copy(alpha = 0.07f), shape)) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(accent, shape))
                }
                Spacer(Modifier.width(12.dp))
                Text(word, color = if (offNormal) color else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(RowCaretGap))
            TapCaret(ROW_CARET_ALPHA)
        }
    }
}

/** The last rows of a list: an action or a way on, with a caret. */
@Composable
internal fun LinkRow(text: String, onClick: () -> Unit, divider: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .let { if (divider) it.drawBehind { drawLine(RowHairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else it }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, style = GlowType.body, modifier = Modifier.weight(1f))
        TapCaret(ROW_CARET_ALPHA)
    }
}

/** A small pill button inside a row: white for the main action, glass otherwise. */
@Composable
internal fun RowPill(text: String, onClick: () -> Unit, primary: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.height(32.dp)
            .let { if (primary) it.background(Color.White, shape).clickable(onClick = onClick) else it.glass(shape, onClick) }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (primary) Color.Black else Color.White, style = GlowType.secondary, maxLines = 1) }
}

/**
 * A few figures side by side, each a [HeroFigure] in an equal share of the width, so four still fit a phone. Four or
 * more take smaller dots.
 */
@Composable
internal fun FigureRow(figures: List<Pair<String?, String>>, modifier: Modifier = Modifier) {
    val dot = if (figures.size >= 4) DotSize.Row else DotSize.Edge
    Row(modifier.fillMaxWidth()) {
        figures.forEachIndexed { i, (value, label) ->
            val end = i == figures.lastIndex && i > 0
            HeroFigure(value, label, horizontal = if (end) Alignment.End else Alignment.Start, dot = dot, modifier = Modifier.weight(1f))
        }
    }
}

/** A dot in [color] and a short [text], for a caveat like "Learning, 9 of 14 nights". */
@Composable
internal fun CaveatLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary)
    }
}

/**
 * Your normal for [metric] as of [date] (mean ± one standard deviation of the last days, [History.normal]), loaded
 * from [history]; null without a history or before there are enough days. For your resting range behind a heart
 * rate line, from the nightly resting heart rate.
 */
@Composable
internal fun rememberNormal(metric: org.openstrap.app.ui.dashboard.Metric, date: java.time.LocalDate, history: org.openstrap.app.ui.dashboard.DailyHistory?): org.openstrap.app.data.History.Normal? {
    val from = date.minusDays(org.openstrap.app.data.History.NORMAL_DAYS - 1L)
    val stats by androidx.compose.runtime.remember(metric, date, history) {
        history?.invoke(metric.daily, from, date) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(emptyList())
    return androidx.compose.runtime.remember(stats, date) { org.openstrap.app.data.History.normal(stats, date) }
}
