package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The card grid (spec 2.3): two columns of units, every card one of five sizes, every row one of three shapes.
 * Rows fill whatever width they're given, so the same rows work on a phone and in each column of
 * [GlowScreenList]'s wide-screen split.
 */

/** A card's size on the grid. Cards never resize by user choice. */
enum class CardSize {
    /** 2x2: the hero of the moment. Today's heroes take its width but are only as high as their content. */
    Large,

    /** 2x1: a number with a trend, a strip or a short chart. */
    Wide,

    /** 1x1: a number and a status word. */
    Small,

    /** 1x2: Charge as a battery, when it sits beside smalls. */
    Tall,

    /** Full width, [GlowGrid.StripHeight] high: a state or an action. */
    Strip,

    /** Full width, as high as its rows: a [ListPanel]. */
    List,
}

/** The grid's measures. */
object GlowGrid {
    /** Screen edge to the grid. */
    val Gutter = 16.dp

    /** Between columns and between rows. */
    val Gap = 12.dp

    /** One unit's height: 160 dp, so a Small sits just under square on a phone and two stacked meet a Tall. */
    val UnitHeight = 160.dp

    val StripHeight = 68.dp

    /** One unit's width inside a column already [width] wide (the gutters already taken off). */
    fun unitWidthIn(width: Dp): Dp = (width - Gap) / 2

    /** The height of a card of [size]; null for a list, which takes its content's height. */
    fun height(size: CardSize): Dp? = when (size) {
        CardSize.Large, CardSize.Tall -> UnitHeight * 2 + Gap
        CardSize.Wide, CardSize.Small -> UnitHeight
        CardSize.Strip -> StripHeight
        CardSize.List -> null
    }
}

/** One row of the grid, holding items of type [T]. */
sealed interface GridRow<out T> {
    /** A card across the whole width: Large, Wide, Strip or List. */
    data class Full<T>(val size: CardSize, val item: T) : GridRow<T>

    /** Two Smalls side by side. */
    data class Pair<T>(val left: T, val right: T) : GridRow<T>

    /** A Tall on the left, two Smalls stacked on the right. */
    data class TallPair<T>(val tall: T, val top: T, val bottom: T) : GridRow<T>
}

/**
 * Packs [cards] (each a size and an item) into rows, keeping their order. Two Smalls in a row make a [GridRow.Pair].
 * A Tall followed by two Smalls makes a [GridRow.TallPair]. A Small with no Small after it becomes a Wide, and so
 * does a Tall without two Smalls after it, so no row is ever left with a hole.
 */
fun <T> packGrid(cards: List<kotlin.Pair<CardSize, T>>): List<GridRow<T>> {
    val rows = mutableListOf<GridRow<T>>()
    var i = 0
    fun sizeAt(k: Int) = cards.getOrNull(k)?.first
    while (i < cards.size) {
        val (size, item) = cards[i]
        when {
            size == CardSize.Small && sizeAt(i + 1) == CardSize.Small -> {
                rows += GridRow.Pair(item, cards[i + 1].second); i += 2
            }
            size == CardSize.Tall && sizeAt(i + 1) == CardSize.Small && sizeAt(i + 2) == CardSize.Small -> {
                rows += GridRow.TallPair(item, cards[i + 1].second, cards[i + 2].second); i += 3
            }
            size == CardSize.Small || size == CardSize.Tall -> {
                rows += GridRow.Full(CardSize.Wide, item); i++
            }
            else -> {
                rows += GridRow.Full(size, item); i++
            }
        }
    }
    return rows
}

/** A card on the grid: its size, and how to draw it given a modifier that sets its exact size. */
class GridCard(val size: CardSize, val content: @Composable (Modifier) -> Unit)

/** One packed row, filling the width it's given. */
@Composable
fun GlowGridRow(row: GridRow<GridCard>, modifier: Modifier = Modifier) {
    fun sized(size: CardSize, m: Modifier = Modifier) = GlowGrid.height(size)?.let { m.height(it) } ?: m
    when (row) {
        is GridRow.Full -> row.item.content(sized(row.size, modifier.fillMaxWidth()))
        is GridRow.Pair -> Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            row.left.content(sized(CardSize.Small, Modifier.weight(1f)))
            row.right.content(sized(CardSize.Small, Modifier.weight(1f)))
        }
        is GridRow.TallPair -> Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            row.tall.content(sized(CardSize.Tall, Modifier.weight(1f)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
                row.top.content(sized(CardSize.Small, Modifier.fillMaxWidth()))
                row.bottom.content(sized(CardSize.Small, Modifier.fillMaxWidth()))
            }
        }
    }
}

/**
 * Adds [cards] to a dashboard as grid rows, each row its own block in [pane]. On a wide screen, left and right
 * blocks sit in [GlowScreenList]'s two columns, and the rows fill each column.
 */
fun GlowBlocks.grid(cards: List<GridCard>, pane: Pane = Pane.Full) {
    packGrid(cards.map { it.size to it }).forEach { row ->
        val block: @Composable () -> Unit = { GlowGridRow(row) }
        when (pane) {
            Pane.Full -> full(content = block)
            Pane.Left -> left(content = block)
            Pane.Right -> right(content = block)
        }
    }
}
