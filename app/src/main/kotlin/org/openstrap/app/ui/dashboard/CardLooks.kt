package org.openstrap.app.ui.dashboard

import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.GridRow
import org.openstrap.app.ui.glow.draw.Edge
import org.openstrap.app.ui.glow.Pane
import org.openstrap.app.ui.glow.packGrid

/*
 * How a card looks at a glance, and which cards sit next to each other, for every screen (spec 2.4). The rule they
 * serve: never two neighbours with the same design. Plain functions, so each screen's layout is tested without one.
 * Today resolves clashes as it goes ([varyLooks]); the tabs fix their looks up front ([recoveryCards] and the like).
 */

/**
 * A card's tier (spec 2.4), with strips and plain pieces (Edit Today) told apart from edge cards. [Level] is an edge
 * card whose light fills from the left across to the value, like Charge: a fill, not an edge, so it reads as its own
 * design.
 */
enum class Tier { Glow, Edge, Level, List, Strip, Plain }

/** The colour a card is lit in: its metric's, or [Neutral], the cool grey of hsl 220 10% 55%. */
enum class Hue { Recovery, Charge, Sleep, Heart, Steps, Load, HabitAge, Neutral, /** The evening hero's violet: a moment, not a metric. */ Evening }

/**
 * A card's shape where it counts for its design (spec 2.3, 2.4): a [Small] or a [Tall] reads as its own design whatever
 * its tier and edge. Every other size (Large, Wide, Strip, List) is a [Card]: a full-width card, told apart by its
 * tier and edge alone.
 */
enum class Shape {
    /** An unlit strip ([Tier.List] at [CardSize.Strip]): with no light to tell them apart, its height does. */
    Card, Small, Tall, Strip;

    companion object {
        fun of(size: CardSize): Shape = when (size) {
            CardSize.Small -> Small
            CardSize.Tall -> Tall
            else -> Card
        }
    }
}

/**
 * What a card looks like at a glance: its [tier], its [hue], for edge cards and strips the [edge] its light comes in
 * from, and its [shape]. Two cards look the same when tier, edge and shape match ([sameDesign]): a colour alone doesn't
 * tell two cards apart, so two glows in a row, or two edge cards lit from one side, look the same whatever their
 * colours. Two Smalls side by side are neighbours too, so they need different edges.
 */
data class CardLook(val tier: Tier, val hue: Hue, val edge: Edge? = null, val shape: Shape = Shape.Card, val picture: PictureKind? = null) {
    /**
     * Same tier and shape, and the same picture (spec 2.5): two cards drawing one kind of picture look alike whatever
     * their colours. A look with no picture yet still compares by [edge], until every screen gives its pictures.
     */
    fun sameDesign(other: CardLook?): Boolean = other != null && tier == other.tier && shape == other.shape &&
        if (picture != null || other.picture != null) picture == other.picture else edge == other.edge
}

/**
 * Every picture a card draws (spec 2.7). Each metric draws its own, and two neighbours never draw the same kind
 * ([CardLook.sameDesign]). [None] is a card with words alone: a strip, Start a workout.
 */
enum class PictureKind {
    /** Heart rate: the day's line through your resting range. */
    Line,
    /** Steps: the day's hourly bars. */
    HourlyBars,
    /** Recovery's Small and breathing disturbances: a dot a night through the band of your usual. */
    WeekDots,
    /** Last night: one bar split by stage. */
    StageBar,
    /** Last night on the Sleep tab: the hypnogram. */
    Hypnogram,
    /** Sleep regularity: the week's bed and wake bars against your usual times. */
    BedWakeBars,
    /** Habit Age: the range as a band on a scale of ages, your age a hairline. */
    AgeScale,
    /** Stress: the day as one strip, calm to high. */
    DayStrip,
    /** Skin temperature: a half dial centred on your normal. */
    Dial,
    /** Load: a level filling over the band of your usual. */
    LevelFill,
    /** Time to recover: a ring filling toward Ready. */
    CountdownRing,
    /** Active minutes: a block per stretch toward the week's target. */
    Blocks,
    /** Heart points: the last 7 days as bars (the zone split isn't stored). */
    DayBars,
    /** Naps: the day as a hairline, the nap as its stage strip at its time. */
    NapDay,
    /** Recharged: a fill from where the night began to where it ended. */
    FillBar,
    /** Drained: one bar split by what took it. */
    SplitBar,
    /** Your week: a ring a day. */
    WeekRings,
    /** A workout: its zone bar. */
    ZoneBar,
    /** Energy, as a glow: its curve so far. */
    Curve,
    /** Words alone. */
    None,
}

/**
 * Where each card goes on a wide screen when nothing says otherwise: Large cards and strips span both columns, the
 * rows between take turns, as [org.openstrap.app.ui.glow.GlowScreenList] lays them out. A row of two Smalls, or of a
 * Tall and its Smalls, stays together in one column ([packGrid]). With [smallsAcross] (columns too narrow for Smalls,
 * [org.openstrap.app.ui.glow.GlowLayout.smallsAcross]) it spans both columns instead and takes no turn.
 */
fun alternatingPanes(sizes: List<CardSize>, smallsAcross: Boolean = false): List<Pane> {
    var side = Pane.Left
    val out = MutableList(sizes.size) { Pane.Full }
    packGrid(sizes.mapIndexed { i, s -> s to i }).forEach { row ->
        val first = row.items().first()
        val pane = when {
            sizes[first] == CardSize.Large || sizes[first] == CardSize.Strip -> Pane.Full
            smallsAcross && row !is GridRow.Full -> Pane.Full
            else -> side.also { side = if (it == Pane.Left) Pane.Right else Pane.Left }
        }
        row.items().forEach { out[it] = pane }
    }
    return out
}

/**
 * [panes] as drawn on two columns too narrow for Smalls ([org.openstrap.app.ui.glow.GlowLayout.smallsAcross]): every
 * row of Smalls (two Smalls, or a Tall with its two) spans both columns, so no Small is narrower than on a folded
 * phone. The other cards keep their column.
 */
fun spanSmalls(panes: List<Pane>, sizes: List<CardSize>): List<Pane> {
    val out = panes.toMutableList()
    packGrid(sizes.mapIndexed { i, s -> s to i }).forEach { row -> if (row !is GridRow.Full) row.items().forEach { out[it] = Pane.Full } }
    return out
}

/** Every item of a row, in reading order. */
internal fun <T> GridRow<T>.items(): List<T> = when (this) {
    is GridRow.Full -> listOf(item)
    is GridRow.Pair -> listOf(left, right)
    is GridRow.TallPair -> listOf(tall, top, bottom)
}

/** The items along a row's top edge: what the row above it touches. A Tall reaches both edges, its top Small one. */
private fun <T> GridRow<T>.topEdge(): List<T> = if (this is GridRow.TallPair) listOf(tall, top) else items()

/** The items along a row's bottom edge. */
private fun <T> GridRow<T>.bottomEdge(): List<T> = if (this is GridRow.TallPair) listOf(tall, bottom) else items()

/** The items inside a row that touch each other: a Pair's two, and a Tall with each of its Smalls and those two. */
private fun <T> GridRow<T>.inside(): List<kotlin.Pair<T, T>> = when (this) {
    is GridRow.Full -> emptyList()
    is GridRow.Pair -> listOf(left to right)
    is GridRow.TallPair -> listOf(tall to top, tall to bottom, top to bottom)
}

/** What the screen draws in one band down the page: a row across the width, or a run split into two columns of rows. */
private sealed interface Band {
    data class Across(val row: GridRow<Int>) : Band
    data class Split(val left: List<GridRow<Int>>, val right: List<GridRow<Int>>) : Band
}

/**
 * The pairs of cards (by index, earlier first) that sit next to each other, given each card's [panes] and [sizes] (all
 * Wide when null): one after the other in reading order, side by side in a row of Smalls, one over the other, and on a
 * [wide] screen also side by side and one over the other in a column, laid out as
 * [org.openstrap.app.ui.glow.GlowScreenList] does with each grid row its own block. A row of two Smalls touches every
 * card over and under it. With [fullTouchesColumns], a full-width card also touches the top card of each column under
 * it and the last card of each column over it.
 */
fun neighbours(
    panes: List<Pane>, wide: Boolean, fullTouchesColumns: Boolean = false, sizes: List<CardSize>? = null,
): Set<kotlin.Pair<Int, Int>> {
    val out = (0 until panes.size - 1).mapTo(mutableSetOf()) { it to it + 1 }
    fun add(a: Int, b: Int) { if (a != b) out += minOf(a, b) to maxOf(a, b) }
    fun touch(upper: GridRow<Int>, lower: GridRow<Int>) { for (a in upper.bottomEdge()) for (b in lower.topEdge()) add(a, b) }
    val rows = packGrid((sizes ?: List(panes.size) { CardSize.Wide }).mapIndexed { i, s -> s to i })
    rows.forEach { r -> r.inside().forEach { (a, b) -> add(a, b) } }
    if (!wide) {
        rows.zipWithNext { a, b -> touch(a, b) }
        return out
    }
    fun paneOf(r: GridRow<Int>) = panes[r.items().first()]
    // Bands down the screen: a full row on its own, and each unbroken run of column rows split in two. A run with one
    // side empty is drawn full width, in reading order.
    val bands = mutableListOf<Band>()
    var i = 0
    while (i < rows.size) {
        if (paneOf(rows[i]) == Pane.Full) { bands += Band.Across(rows[i]); i++; continue }
        val run = rows.drop(i).takeWhile { paneOf(it) != Pane.Full }
        val left = run.filter { paneOf(it) == Pane.Left }
        val right = run.filter { paneOf(it) == Pane.Right }
        if (left.isEmpty() || right.isEmpty()) run.forEach { bands += Band.Across(it) } else bands += Band.Split(left, right)
        i += run.size
    }
    bands.forEach { b ->
        if (b !is Band.Split) return@forEach
        listOf(b.left, b.right).forEach { col -> col.zipWithNext { x, y -> touch(x, y) } }
        b.left.zip(b.right).forEach { (l, r) -> for (x in l.items()) for (y in r.items()) add(x, y) }
    }
    bands.zipWithNext { a, b ->
        when {
            a is Band.Across && b is Band.Across -> touch(a.row, b.row)
            !fullTouchesColumns -> Unit
            a is Band.Across && b is Band.Split -> { touch(a.row, b.left.first()); touch(a.row, b.right.first()) }
            a is Band.Split && b is Band.Across -> { touch(a.left.last(), b.row); touch(a.right.last(), b.row) }
            else -> Unit
        }
    }
    return out
}

/** The pairs of neighbours among [looks] (placed in [panes], at [sizes]) that look the same, in reading order. */
fun lookClashes(
    looks: List<CardLook>, panes: List<Pane>, wide: Boolean, fullTouchesColumns: Boolean = false, sizes: List<CardSize>? = null,
): List<kotlin.Pair<Int, Int>> =
    neighbours(panes, wide, fullTouchesColumns, sizes).filter { (a, b) -> looks[a].sameDesign(looks[b]) }
        .sortedWith(compareBy({ it.second }, { it.first }))
