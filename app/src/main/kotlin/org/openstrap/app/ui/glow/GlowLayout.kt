package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * One set of shape and spacing rules for every card (spec 2.4), so their contents line up down the screen: one
 * corner, one inset from the edge on every tier, the same rhythm from title to number to chart. Cards take their
 * corners and padding from here, never a literal of their own.
 */
object GlowLayout {
    /** Space between the screen edge and a card. */
    val Gutter = 20.dp

    /** Every card's corner, on every tier: glow, edge, list, strip and page panels. */
    val CardCorner = 28.dp
    val CardShape = RoundedCornerShape(CardCorner)
    /** A sheet's top corners match the cards'. */
    val SheetShape = RoundedCornerShape(topStart = CardCorner, topEnd = CardCorner)
    /** Sport tiles, chart pills, text fields: rounded, but a step tighter than a card. */
    val TileCorner = 16.dp
    val TileShape = RoundedCornerShape(TileCorner)

    /** From a card's edge to its content, on every side and every tier. */
    val CardInset = 20.dp
    val CardPadding = PaddingValues(CardInset)
    /** Above and below each list row; the panel adds the rest of [CardInset] at its top and bottom. */
    val RowPadding = 14.dp
    /** Inside a list: [CardInset] at the sides, and with the first and last row's [RowPadding] at the top and bottom. */
    val ListPadding = PaddingValues(horizontal = CardInset, vertical = CardInset - RowPadding)

    /** The 1 dp hairline round a glow or edge card, and a chart's grid: white at 8% (spec 2.2, 2.7). */
    const val HairlineAlpha = 0.08f
    /** A list panel's border and the divider between its rows: white at 6%. */
    const val DividerAlpha = 0.06f

    /** Title row to the hero number. */
    val TitleGap = 14.dp
    /** Hero number (or the last line of text) to the chart. */
    val ChartGap = 20.dp
    /** Every chart in a card has the same height, axis labels included. */
    val ChartHeight = 116.dp
    /** From this width (an unfolded foldable, a tablet, a phone on its side) the dashboards use two columns. */
    val TwoColumnWidth = 600.dp
    /** The widest a tab's two columns get together (spec 2.4 Unfolded); past it the content is centred. */
    val MaxContentWidth = 960.dp
    /** The side gutter on two columns (spec 2.4 Unfolded). */
    val UnfoldedGutter = 24.dp
    /**
     * The widest a page over a tab gets (spec 2.4 Unfolded): You, the Strap page and what they open, Ready, the sport
     * picker, Add a workout, a detail that isn't beside its tab. One centred column; a sheet is no wider either.
     */
    val PageMaxWidth = 600.dp
    /** Space between the two columns: the same as between cards in a column. */
    val ColumnGap = 12.dp
    /**
     * Beside a detail pane a dashboard is a narrow phone's width. A tighter gutter gives the cards back a phone card's
     * inner width, so tile labels and values fit as they do there. A detail takes it too.
     */
    val WideGutter = 16.dp
    /** Narrower than this (a small phone, a dashboard beside a detail) the gutter is tight. */
    val NarrowWidth = 400.dp

    /**
     * A dashboard's side gutter at [width]: on two columns [UnfoldedGutter], growing past it so the content is at most
     * [MaxContentWidth] and centred; on a phone [Gutter], or [WideGutter] when narrow.
     */
    fun dashboardGutter(width: Dp): Dp = when {
        width >= TwoColumnWidth -> maxOf(UnfoldedGutter, (width - MaxContentWidth) / 2)
        width < NarrowWidth -> WideGutter
        else -> Gutter
    }

    /**
     * The narrowest a Small gets (spec 2.4): about its width two across on a folded phone, (411 - 40 - 12) / 2, so
     * its title ("Active minutes") stays on one line and its note on two at most.
     */
    val SmallMinWidth = 176.dp

    /**
     * Whether each column of a two-column dashboard [width] wide holds a row of Smalls (two across, or a Tall beside
     * two) with each Small at least [SmallMinWidth]. When it doesn't (the Fold's inner screen), a row of Smalls spans
     * both columns instead of being squeezed into one.
     */
    fun smallsFitColumn(width: Dp): Boolean {
        val column = (width - dashboardGutter(width) * 2 - ColumnGap) / 2
        return GlowGrid.unitWidthIn(column) >= SmallMinWidth
    }

    /** Whether a dashboard [width] wide spans its rows of Smalls across both columns ([smallsFitColumn]). */
    fun smallsAcross(width: Dp): Boolean = width >= TwoColumnWidth && !smallsFitColumn(width)

    /**
     * The side padding that keeps a page's content in one centred column [column] wide at most (spec 2.4 Unfolded),
     * [gutter] inside it: just [gutter] on a phone, more on a wider screen.
     */
    fun pageSide(width: Dp, column: Dp, gutter: Dp): Dp =
        if (column == Dp.Unspecified) gutter else maxOf(gutter, (width - column) / 2 + gutter)

    /**
     * Which column each block of a two-column run goes in, by the blocks' measured heights (spec 2.4 Unfolded):
     * [left] and [right] as given, except that when one column ends well below the other, its last block moves to
     * the foot of the shorter one if that brings their ends closer. One move only, so the order stays close to the
     * phone's; a block is a whole grid row, so a pair is never split. Returns the blocks' indices per column, left
     * then right, counting [left]'s first and then [right]'s.
     */
    fun balanceColumns(left: List<Int>, right: List<Int>, gap: Int): Pair<List<Int>, List<Int>> {
        val l = left.indices.toMutableList()
        val r = right.indices.map { it + left.size }.toMutableList()
        val heights = left + right
        fun height(col: List<Int>) = col.sumOf { heights[it] } + gap * (col.size - 1).coerceAtLeast(0)
        val diff = height(l) - height(r)
        val (tall, short) = if (diff > 0) l to r else r to l
        if (tall.size > 1) {
            val moved = tall.last()
            val after = (height(tall) - heights[moved] - gap) - (height(short) + heights[moved] + gap)
            if (kotlin.math.abs(after) < kotlin.math.abs(diff)) { tall.removeAt(tall.lastIndex); short += moved }
        }
        return l to r
    }
}

/**
 * The widest a page's column gets ([GlowLayout.PageMaxWidth] over the app); [ScreenList] and the workout pages centre
 * their content in it. A dashboard lays out its own width, so [GlowScreenList] clears it.
 */
val LocalPageColumn = compositionLocalOf { GlowLayout.PageMaxWidth }

/** Where a dashboard block goes when there's room for two columns. On a phone every block stacks in the order given. */
enum class Pane { Full, Left, Right }

/** The blocks of a dashboard, in phone order, each with the column it takes on a wide screen. */
class GlowBlocks internal constructor(
    /** On two columns too narrow for Smalls ([GlowLayout.smallsAcross]): a row of Smalls is a full block. */
    val smallsAcross: Boolean = false,
) {
    internal val blocks = mutableListOf<Pair<Pane, @Composable () -> Unit>>()

    /** The block [GlowScreenList] scrolls to when asked ([focus]), or -1. */
    internal var focusBlock = -1

    /** Marks the next block as the one to scroll to. */
    fun focusNext() { focusBlock = blocks.size }
    fun full(content: @Composable () -> Unit) = add(Pane.Full, content)
    fun left(content: @Composable () -> Unit) = add(Pane.Left, content)
    fun right(content: @Composable () -> Unit) = add(Pane.Right, content)
    private fun add(pane: Pane, content: @Composable () -> Unit) {
        blocks += pane to content
    }
}

/**
 * A glow dashboard's list. Narrower than [GlowLayout.TwoColumnWidth] it's the phone list: every block in
 * order, one under the other. Wider, each run of left and right blocks sits side by side in two columns,
 * and full blocks span both. It stays one lazy list either way, so scrolling, pull to sync and the glow
 * pausing while it scrolls behave the same. With [focus] set it scrolls to the block marked with
 * [GlowBlocks.focusNext] once, then calls [onFocused].
 */
@Composable
fun GlowScreenList(
    contentPadding: PaddingValues,
    state: LazyListState,
    focus: Boolean = false,
    onFocused: () -> Unit = {},
    content: GlowBlocks.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= GlowLayout.TwoColumnWidth
        val gutter = GlowLayout.dashboardGutter(maxWidth)
        val built = GlowBlocks(smallsAcross = GlowLayout.smallsAcross(maxWidth)).apply(content)
        val blocks = built.blocks
        // The lazy item the marked block lands in: its own on a phone; on a wide screen a two-column run is one item.
        val target = built.focusBlock.takeIf { it >= 0 }?.let { if (wide) itemOf(blocks, it) else it }
        androidx.compose.runtime.LaunchedEffect(focus, target) {
            if (focus && target != null) { state.animateScrollToItem(target); onFocused() }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalPageColumn provides Dp.Unspecified) { ScreenList(contentPadding, state, gutter = gutter) {
            if (!wide) blocks.forEach { (_, block) -> item { block() } }
            else sections(blocks).forEach { section ->
                val left = section.filter { it.first == Pane.Left }.map { it.second }
                val right = section.filter { it.first == Pane.Right }.map { it.second }
                // A full block, or a run with nothing on one side, takes the whole width rather than leave a hole.
                if (left.isEmpty() || right.isEmpty()) section.forEach { (_, block) -> item { block() } }
                else item { BalancedColumns(left, right) }
            }
        } }
    }
}

/**
 * Two columns of blocks, [GlowLayout.ColumnGap] apart and between blocks, balanced by height
 * ([GlowLayout.balanceColumns]): the last block of a column that would end far below the other moves under the other.
 */
@Composable
private fun BalancedColumns(left: List<@Composable () -> Unit>, right: List<@Composable () -> Unit>) {
    // Each block in a box of its own, so a block that draws nothing (or several things) is still one child.
    Layout(content = { (left + right).forEach { androidx.compose.foundation.layout.Box(propagateMinConstraints = true) { it() } } }, modifier = Modifier.fillMaxWidth()) { measurables, cs ->
        val gap = GlowLayout.ColumnGap.roundToPx()
        val column = ((cs.maxWidth - gap) / 2).coerceAtLeast(0)
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(column)) }
        val (l, r) = GlowLayout.balanceColumns(
            placeables.take(left.size).map { it.height }, placeables.drop(left.size).map { it.height }, gap,
        )
        fun height(col: List<Int>) = col.sumOf { placeables[it].height } + gap * (col.size - 1).coerceAtLeast(0)
        layout(cs.maxWidth, maxOf(height(l), height(r))) {
            listOf(l to 0, r to column + gap).forEach { (col, x) ->
                var y = 0
                col.forEach { placeables[it].place(x, y); y += placeables[it].height + gap }
            }
        }
    }
}

/** The lazy item that block [index] lands in on a wide screen, following [GlowScreenList]'s layout. */
private fun itemOf(blocks: List<Pair<Pane, @Composable () -> Unit>>, index: Int): Int {
    var item = 0
    var seen = 0
    for (section in sections(blocks)) {
        val split = section.any { it.first == Pane.Left } && section.any { it.first == Pane.Right }
        if (index < seen + section.size) return if (split) item else item + (index - seen)
        item += if (split) 1 else section.size
        seen += section.size
    }
    return item
}

/** Splits the blocks into runs: each full block on its own, and each unbroken stretch of left and right blocks together. */
private fun sections(blocks: List<Pair<Pane, @Composable () -> Unit>>): List<List<Pair<Pane, @Composable () -> Unit>>> {
    val out = mutableListOf<MutableList<Pair<Pane, @Composable () -> Unit>>>()
    blocks.forEach { b ->
        val last = out.lastOrNull()
        if (b.first != Pane.Full && last != null && last.first().first != Pane.Full) last += b else out += mutableListOf(b)
    }
    return out
}

/**
 * Whether the card around this content opens something when tapped. [GlowCard] and [EdgeCard] provide it from their
 * `onClick`, so every card title ([CardTitle]) shows the tap caret on its own (spec 2.5, Tappable says so).
 */
val LocalCardOpens = compositionLocalOf { false }

/** The tap caret (spec 2.5): 14 dp, at [alpha] white, 50% after a card's title and 30% at a row's end. */
@Composable
internal fun TapCaret(alpha: Float, modifier: Modifier = Modifier) =
    Icon(AppIcons.TapCaret, null, tint = Color.White.copy(alpha = alpha), modifier = modifier.size(CaretSize))

internal val CaretSize = 14.dp

/**
 * (i) on a card (spec 2.5): a 32 dp round button at 10% white with a 16 dp icon at 72%, at the right of the title
 * row. The same on every card that has an explanation; it opens that card's info sheet.
 */
@Composable
internal fun InfoButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.size(InfoButtonSize).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White.copy(alpha = 0.10f))
            .clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(AppIcons.Info, label, Modifier.size(16.dp), tint = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA)) }
}

internal val InfoButtonSize = 32.dp
/** A card's caret, right after its title. */
internal const val CARD_CARET_ALPHA = 0.5f
/** A row's caret, at its end. */
internal const val ROW_CARET_ALPHA = 0.3f

/**
 * A card's title in [GlowType.cardTitle], on one line, with the tap caret 6 dp after the text when the card opens
 * something. Where a title only just fits (a Small on a narrow phone) the gap closes to [CaretMinGap] before the title
 * would ever wrap; an ellipsis is the last resort.
 */
@Composable
internal fun CardTitle(title: String, modifier: Modifier = Modifier) {
    if (!LocalCardOpens.current) {
        Text(title, modifier, color = Color.White, style = GlowType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    Layout(
        content = {
            Text(title, color = Color.White, style = GlowType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TapCaret(CARD_CARET_ALPHA)
        },
        modifier = modifier,
    ) { (text, caret), cs ->
        val c = caret.measure(Constraints())
        val gap = CaretGap.roundToPx()
        val minGap = CaretMinGap.roundToPx()
        // A title that fits only without its caret keeps every letter and drops the caret: the card still opens.
        val full = text.maxIntrinsicWidth(cs.maxHeight)
        if (cs.hasBoundedWidth && full + minGap + c.width > cs.maxWidth && full <= cs.maxWidth) {
            val t = text.measure(Constraints(maxWidth = cs.maxWidth, maxHeight = cs.maxHeight))
            val h = t.height.coerceAtLeast(cs.minHeight)
            return@Layout layout(t.width.coerceIn(cs.minWidth, cs.maxWidth), h) { t.place(0, (h - t.height) / 2) }
        }
        val t = text.measure(
            if (!cs.hasBoundedWidth) Constraints(maxHeight = cs.maxHeight)
            else Constraints(maxWidth = (cs.maxWidth - c.width - minGap).coerceAtLeast(0), maxHeight = cs.maxHeight),
        )
        val g = if (!cs.hasBoundedWidth) gap else (cs.maxWidth - t.width - c.width).coerceIn(minGap, gap)
        val w = (t.width + g + c.width).let { if (cs.hasBoundedWidth) it.coerceIn(cs.minWidth, cs.maxWidth) else it.coerceAtLeast(cs.minWidth) }
        val h = maxOf(t.height, c.height).coerceAtLeast(cs.minHeight)
        layout(w, h) {
            t.place(0, (h - t.height) / 2)
            c.place(t.width + g, (h - c.height) / 2)
        }
    }
}

/** From a card's title to its caret, and the least it closes to when the title only just fits. */
private val CaretGap = 6.dp
private val CaretMinGap = 2.dp

/**
 * A title row: [start] (the title, with its caret and mark) on the left and [end] (the note) on the right when both
 * fit on one line. When they don't, the note drops to the line under the title rather than wrap it (spec 2.3, a card
 * title stays on one line).
 */
@Composable
internal fun TitleRow(modifier: Modifier = Modifier, start: @Composable () -> Unit, end: (@Composable () -> Unit)?) {
    if (end == null) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { start() }
        return
    }
    Layout(content = { start(); end() }, modifier = modifier.fillMaxWidth()) { (s, e), cs ->
        val gap = NoteGap.roundToPx()
        val note = e.measure(Constraints(maxWidth = cs.maxWidth))
        val wanted = s.maxIntrinsicWidth(cs.maxHeight) + gap + note.width
        // Asked how wide it would like to be (an intrinsic measure), it's the one line.
        val w = if (cs.hasBoundedWidth) cs.maxWidth else wanted
        val oneLine = wanted <= w
        if (oneLine) {
            val title = s.measure(Constraints(maxWidth = w - gap - note.width))
            val h = maxOf(title.height, note.height)
            layout(w, h) {
                title.place(0, (h - title.height) / 2)
                note.place(w - note.width, (h - note.height) / 2)
            }
        } else {
            val title = s.measure(Constraints(maxWidth = w))
            val under = NoteUnder.roundToPx()
            layout(w, title.height + under + note.height) {
                title.place(0, 0)
                note.place(0, title.height + under)
            }
        }
    }
}

/** Between a title and its note on one line, and from the title to a note dropped under it. */
private val NoteGap = 12.dp
private val NoteUnder = 2.dp

/**
 * The title row of a card: the title (with the tap caret when the card opens something), an optional [marker] next to
 * it ([StateMarker]) or else a neutral [tag] ([TitleMark]), and an optional note on the right (text, or
 * [trailingContent] for anything richer).
 */
@Composable
fun GlowHeader(
    title: String,
    trailing: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    marker: String? = null,
    tag: TitleTag? = null,
) {
    val marked = marker != null || tag != null
    // A text note drops under the title when they can't share a line; richer trailing content (the (i) button) is
    // small enough to stay beside it.
    if (trailingContent == null) {
        TitleRow(
            start = {
                // The mark goes beside the title, or under it on a narrow card, so it never squeezes the title.
                if (marked) FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    CardTitle(title)
                    TitleMark(marker, tag)
                } else CardTitle(title)
            },
            end = trailing?.let { { NoteText(it) } },
        )
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (marked) FlowRow(
            Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            CardTitle(title)
            TitleMark(marker, tag)
        } else CardTitle(title, Modifier.weight(1f))
        if (trailing != null) NoteText(trailing)
        trailingContent()
    }
}

/** A title row's note, in Secondary on one line. */
@Composable
internal fun NoteText(text: String) =
    Text(text, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)

/**
 * The hero number of a card in dot-matrix, its unit beside it, and up to two short facts on the right
 * (text, or [side] for anything richer). Sits [GlowLayout.TitleGap] under the title.
 */
@Composable
fun GlowHeroNumber(value: String, unit: String, facts: List<String> = emptyList(), side: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = GlowLayout.TitleGap), verticalAlignment = Alignment.Bottom) {
        DotMatrixText(value, dot = DotSize.Hero)
        // The word after a number (spec 2.3).
        Text(unit, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.padding(start = 8.dp).weight(1f))
        if (facts.isNotEmpty()) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            facts.forEach { Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary) }
        }
        side?.invoke()
    }
}

/** The gap above a card's chart. */
@Composable
fun GlowChartGap() = Spacer(Modifier.padding(top = GlowLayout.ChartGap))

/**
 * A small sync marker next to a card's title, "Up to 09:40" (spec 4.3): amber, as the strap chip, since it's about
 * the sync and not the body.
 */
@Composable
internal fun StateMarker(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MetricColors.warn, style = GlowType.caption, maxLines = 1)
}

/**
 * A neutral tag next to a card's title (spec 4.3, E4): "Partial day", "First score". About the data, not the sync,
 * so white and not amber (spec 2.1). With [onClick], tapping it explains it (spec 7.1, one caveat per card).
 */
class TitleTag(val text: String, val onClick: (() -> Unit)? = null)

/** What sits next to a card's title: the sync [marker] when there's one, else the [tag]. One mark per card. */
@Composable
internal fun TitleMark(marker: String?, tag: TitleTag?, modifier: Modifier = Modifier) {
    if (marker != null) StateMarker(marker, modifier)
    else if (tag != null) {
        val shape = RoundedCornerShape(50)
        val onClick = tag.onClick
        Text(
            tag.text,
            modifier.clip(shape).border(1.dp, Color.White.copy(alpha = 0.22f), shape)
                .let { if (onClick != null) it.clickable(onClickLabel = tr(R.string.glow_layout_about, tag.text.lowercase()), onClick = onClick) else it }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.caption, maxLines = 1,
        )
    }
}
