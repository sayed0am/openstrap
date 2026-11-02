package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.Moment
import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.draw.Edge
import org.openstrap.app.ui.glow.Pane

/*
 * How each Today card looks, and the rule that no two cards next to each other look the same (spec 2.4), with the
 * looks themselves in CardLooks.kt. Plain functions over the final list of cards, after Edit Today, so every moment
 * and every pin and hide is tested without a screen.
 */

private fun glow(h: Hue) = CardLook(Tier.Glow, h)
private fun edge(h: Hue, e: Edge) = CardLook(Tier.Edge, h, e)
private fun strip(h: Hue, e: Edge) = CardLook(Tier.Strip, h, e)
private val ListLook = CardLook(Tier.List, Hue.Neutral)

/** The colour of the hero for [moment] (spec 4.2, How each moment looks). */
private fun heroHue(moment: Moment?): Hue = when (moment) {
    Moment.Midday -> Hue.Charge
    Moment.WorkoutWindow, Moment.During, Moment.After -> Hue.Heart
    Moment.Evening -> Hue.Evening
    Moment.Night -> Hue.Sleep
    Moment.Waking, null -> Hue.Recovery
}

/**
 * The look [slot] has on its own (spec 2.4): the edge follows what the card is about, and a Small or Tall is a shape
 * of its own. Heart rate is lit from the right only while the reading is [live]. Your week is a neutral edge from the
 * bottom: a summary of steps, sleep and workouts at their usual, in no one metric's colour. After a workout Load is a
 * Small toward your usual (from below) and Time to recover the Small beside it. Last night, Your week and Start a
 * workout are unlit, like a list: light is kept for cards where it says something (Steps toward the goal, heart rate
 * live), so it still points at them. An unlit strip reads apart from an unlit card ([Shape.Strip]).
 */
fun defaultLook(slot: TodaySlot, moment: Moment?, live: Boolean = false): CardLook = ownLook(slot, moment, live).let { l ->
    l.copy(shape = if (l.tier == Tier.List && slot.size == CardSize.Strip) Shape.Strip else Shape.of(slot.size))
}

private fun ownLook(slot: TodaySlot, moment: Moment?, live: Boolean): CardLook = when (slot.card) {
    TodayCard.Hero -> glow(heroHue(moment))
    TodayCard.Offer -> strip(Hue.Heart, Edge.Right)
    TodayCard.Suggestion -> strip(Hue.Heart, Edge.Left)
    TodayCard.Alerts -> strip(Hue.Heart, Edge.Top)
    // A strip is lit from below in the neutral light (spec 2.5): the check-in is a question, not Recovery's.
    TodayCard.CheckIn -> strip(Hue.Neutral, Edge.Bottom)
    TodayCard.BodyMorning, TodayCard.RightNow -> ListLook
    TodayCard.LoadToday -> if (slot.size == CardSize.Small) edge(Hue.Load, Edge.Bottom) else ListLook
    TodayCard.Recover -> edge(Hue.Load, Edge.Bottom)
    TodayCard.LastNight -> ListLook
    TodayCard.HeartRate -> edge(Hue.Heart, if (live) Edge.Right else Edge.Bottom)
    TodayCard.Activity -> edge(Hue.Steps, Edge.Bottom)
    TodayCard.Recovery -> edge(Hue.Recovery, Edge.Left)
    // Charge is the screen's second glow, a level glow filling across from the left (Recovery's battery on its side),
    // so it doesn't read as the hero's glow above it. The level edge card is only its step down ([options]).
    TodayCard.Charge -> if (slot.secondGlow) CardLook(Tier.Glow, Hue.Charge, Edge.Left) else CardLook(Tier.Level, Hue.Charge, Edge.Left)
    TodayCard.Workout -> edge(Hue.Heart, Edge.Left)
    TodayCard.Week -> ListLook
    TodayCard.HabitAge -> edge(Hue.HabitAge, Edge.Left)
    TodayCard.StartWorkout -> ListLook
    TodayCard.EditToday -> CardLook(Tier.Plain, Hue.Neutral)
}

/** The look a slot ends up with: the one [varyLooks] gave it, or its own. */
fun TodaySlot.lookOr(moment: Moment?, live: Boolean = false): CardLook = look ?: defaultLook(this, moment, live)

/**
 * Where each slot goes on a wide screen: the hero and strips span both columns, the rows between take turns (a Small
 * pair stays together), as [org.openstrap.app.ui.glow.GlowScreenList] lays them out. With [smallsAcross] (columns too
 * narrow for Smalls) a Small pair spans both columns.
 */
fun todayPanes(slots: List<TodaySlot>, smallsAcross: Boolean = false): List<Pane> = alternatingPanes(slots.map { it.size }, smallsAcross)

/**
 * The pairs of slots (by index, earlier first) that sit next to each other: one after the other in reading order,
 * side by side in a Small pair, and on a [wide] screen also side by side and one over the other in a column
 * ([neighbours]). A full-width card touches the top card of each column under it, as on the tabs. [smallsAcross] is
 * two columns with a Small pair spanning both ([todayPanes]).
 */
fun neighbours(slots: List<TodaySlot>, wide: Boolean, smallsAcross: Boolean = false): Set<Pair<Int, Int>> =
    neighbours(todayPanes(slots, smallsAcross && wide), wide, fullTouchesColumns = true, sizes = slots.map { it.size })

/** The pairs of neighbours that look the same, in reading order. */
fun lookClashes(
    slots: List<TodaySlot>, wide: Boolean, moment: Moment? = null, live: Boolean = false, smallsAcross: Boolean = false,
): List<Pair<Int, Int>> =
    neighbours(slots, wide, smallsAcross).filter { (a, b) -> slots[a].lookOr(moment, live).sameDesign(slots[b].lookOr(moment, live)) }
        .sortedWith(compareBy({ it.second }, { it.first }))

/** Cards whose place the spec leaves open: pins (only "under the hero"), and Your week and Habit Age, which close the list. */
private fun TodaySlot.movable() =
    (pinned || card == TodayCard.Week || card == TodayCard.HabitAge) && size != CardSize.Small && size != CardSize.Tall

/** The places a card can be put in [slots] without splitting a Small pair: the start of each grid row, and the end. */
private fun rowStarts(slots: List<TodaySlot>): Set<Int> {
    var at = 0
    return buildSet {
        org.openstrap.app.ui.glow.packGrid(slots.map { it.size to it }).forEach { add(at); at += it.items().size }
        add(at)
    }
}

private fun opposite(e: Edge) = when (e) { Edge.Top -> Edge.Bottom; Edge.Bottom -> Edge.Top; Edge.Left -> Edge.Right; Edge.Right -> Edge.Left }

/** [e]'s opposite first, then the two across from it. */
private fun flips(e: Edge): List<Edge> = listOf(opposite(e)) + Edge.entries.filter { it != e && it != opposite(e) }

/**
 * The other looks [s] may take, in the order they're tried: the edge flipped, then another tier the spec gives the
 * card, then the neutral edge. Last night is a strip or a wide card. A list can sit on an edge card in the neutral
 * light. Charge as the second glow can step down to its level card. Other glows, a level and Edit Today have no
 * other look, and an alert from the strap keeps its colour.
 */
private fun options(s: TodaySlot): List<TodaySlot> {
    val l = s.look ?: return emptyList()
    if (s.secondGlow) return listOf(s.copy(secondGlow = false, look = CardLook(Tier.Level, l.hue, Edge.Left)))
    if (l.tier == Tier.Level) return emptyList()
    if (l.tier == Tier.List) return (listOf(Edge.Bottom) + flips(Edge.Bottom)).map { s.copy(look = CardLook(Tier.Edge, Hue.Neutral, it)) }
    val e = l.edge ?: return emptyList()
    val sides = listOf(e) + flips(e)
    return buildList {
        flips(e).forEach { add(s.copy(look = l.copy(edge = it))) }
        if (s.card == TodayCard.LastNight) {
            val (size, tier) = if (l.tier == Tier.Edge) CardSize.Strip to Tier.Strip else CardSize.Wide to Tier.Edge
            sides.forEach { add(s.copy(size = size, look = l.copy(tier = tier, edge = it))) }
        }
        if (l.hue != Hue.Neutral && s.card != TodayCard.Alerts) sides.forEach { add(s.copy(look = l.copy(hue = Hue.Neutral, edge = it))) }
    }
}

/** Whether no two neighbours among the first [upTo] + 1 slots look the same. */
private fun settled(slots: List<TodaySlot>, upTo: Int, wide: Boolean, across: Boolean) =
    neighbours(slots, wide, across).none { (a, b) -> b <= upTo && slots[a].look!!.sameDesign(slots[b].look) }

/**
 * Goes down the list and, where a card looks like a neighbour before it, gives the card another look ([options]).
 * When it has none, the earlier neighbour changes instead.
 */
private fun restyle(slots: List<TodaySlot>, wide: Boolean, across: Boolean): List<TodaySlot> {
    val out = slots.toMutableList()
    for (i in out.indices) {
        val clashing = neighbours(out, wide, across).filter { (a, b) -> b == i && out[a].look!!.sameDesign(out[i].look) }.map { it.first }
        if (clashing.isEmpty()) continue
        fun tryAt(at: Int): Boolean {
            val pick = options(out[at]).firstOrNull { o -> settled(out.toMutableList().also { it[at] = o }, i, wide, across) } ?: return false
            out[at] = pick
            return true
        }
        if (!tryAt(i)) clashing.firstOrNull { tryAt(it) }
    }
    return out
}

/**
 * [slots] with a look each, such that no two neighbours ([neighbours]) look the same (spec 2.4). A clash is resolved
 * first by flipping the later card's edge, then by another tier or the neutral edge, and only then by moving a card
 * whose place the spec leaves open down to the nearest place it fits. [live] is whether heart rate is live, [wide]
 * whether Today has two columns, and [smallsAcross] whether a Small pair spans both of them ([todayPanes]).
 */
fun varyLooks(slots: List<TodaySlot>, moment: Moment?, live: Boolean = false, wide: Boolean = false, smallsAcross: Boolean = false): List<TodaySlot> {
    val across = smallsAcross && wide
    var base = slots.map { it.copy(look = defaultLook(it, moment, live)) }
    repeat(slots.size + 1) {
        val styled = restyle(base, wide, across)
        val clash = lookClashes(styled, wide, smallsAcross = across).firstOrNull() ?: return styled
        base = move(base, clash, wide, across) ?: return styled
    }
    return restyle(base, wide, across)
}

/**
 * [base] with one movable card moved to the nearest place where it looks like none of its neighbours and fewer cards
 * clash overall: a card of the [clash] first, the later one first, then any other. Nothing moves above the hero and
 * the strips that need an answer, or past Edit Today.
 */
private fun move(base: List<TodaySlot>, clash: Pair<Int, Int>, wide: Boolean, across: Boolean): List<TodaySlot>? {
    val before = lookClashes(restyle(base, wide, across), wide, smallsAcross = across).size
    val top = base.indexOfFirst { it.card !in URGENT }.let { if (it < 0) base.size else it }
    val end = base.indexOfFirst { it.card == TodayCard.EditToday }.let { if (it < 0) base.size else it }
    val movers = (listOf(clash.second, clash.first) + base.indices.sortedBy { minOf(kotlin.math.abs(it - clash.first), kotlin.math.abs(it - clash.second)) })
        .distinct().filter { base[it].movable() }
    for (from in movers) {
        val card = base[from]
        val rest = base.toMutableList().also { it.removeAt(from) }
        // Nearest first, and down before up.
        val starts = rowStarts(rest)
        val places = (top until end).filter { it != from && it in starts }.sortedWith(compareBy({ kotlin.math.abs(it - from) }, { it < from }))
        for (to in places) {
            val moved = rest.toMutableList().also { it.add(to, card) }
            val clashes = lookClashes(restyle(moved, wide, across), wide, smallsAcross = across)
            if (clashes.size < before && clashes.none { it.first == to || it.second == to }) return moved
        }
    }
    return null
}

private val URGENT = setOf(TodayCard.Hero, TodayCard.Offer, TodayCard.Suggestion, TodayCard.Alerts)
