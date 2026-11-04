package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.Moment
import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.Pane

/*
 * How each Today card looks, and the rule that no two cards next to each other look the same (spec 2.5), with the
 * looks themselves in CardLooks.kt. Plain functions over the final list of cards, after Edit Today, so every moment
 * and every pin and hide is tested without a screen.
 */

private fun glow(h: Hue, p: PictureKind? = null) = CardLook(Tier.Glow, h, picture = p)
private fun picture(h: Hue, p: PictureKind) = CardLook(Tier.Picture, h, picture = p)
private fun strip(h: Hue) = CardLook(Tier.Strip, h, picture = PictureKind.None)
private val ListLook = CardLook(Tier.List, Hue.Neutral)

/** The colour of the hero for [moment] (spec 4.2, How each moment looks). */
private fun heroHue(moment: Moment?): Hue = when (moment) {
    Moment.Midday -> Hue.Charge
    Moment.WorkoutWindow, Moment.During -> Hue.Heart
    // Red is for effort: once the run is done the hero is Recovery teal.
    Moment.After -> Hue.Recovery
    Moment.Evening -> Hue.Evening
    Moment.Night -> Hue.Sleep
    Moment.Waking, null -> Hue.Recovery
}

/**
 * The look [slot] has on its own (spec 2.5, 2.7, 4.2): each metric draws its own picture, at a Small or a Wide alike,
 * and no two cards Today can show draw the same kind, so neighbours differ by picture. Strips and Start a workout are
 * unlit strips with no picture, Your body this morning a list, Edit Today a plain piece. Last night is its stage bar
 * as a card, and words alone as a strip. Energy as the second glow draws its curve in a level glow; stepped down
 * ([options]) it's the same curve on a picture card. [live] no longer changes a look: the rim, not an edge, says
 * what's off, and that is the screen's to draw.
 */
@Suppress("UNUSED_PARAMETER")
fun defaultLook(slot: TodaySlot, moment: Moment?, live: Boolean = false): CardLook =
    ownLook(slot, moment).copy(shape = Shape.of(slot.size))

private fun ownLook(slot: TodaySlot, moment: Moment?): CardLook = when (slot.card) {
    TodayCard.Hero -> glow(heroHue(moment))
    TodayCard.Offer, TodayCard.Suggestion, TodayCard.Alerts -> strip(Hue.Heart)
    TodayCard.CheckIn, TodayCard.StartWorkout -> strip(Hue.Neutral)
    TodayCard.BodyMorning, TodayCard.RightNow -> ListLook
    TodayCard.LastNight -> if (slot.size == CardSize.Strip) strip(Hue.Sleep) else picture(Hue.Sleep, PictureKind.StageBar)
    TodayCard.HeartRate -> picture(Hue.Heart, PictureKind.Line)
    TodayCard.Stress -> picture(Hue.Stress, PictureKind.DayStrip)
    TodayCard.Activity -> picture(Hue.Steps, PictureKind.HourlyBars)
    TodayCard.Recovery -> picture(Hue.Recovery, PictureKind.WeekDots)
    TodayCard.Charge -> if (slot.secondGlow) glow(Hue.Charge, PictureKind.Curve) else picture(Hue.Charge, PictureKind.Curve)
    TodayCard.LoadToday -> picture(Hue.Load, PictureKind.LevelFill)
    TodayCard.Recover -> picture(Hue.Load, PictureKind.CountdownRing)
    TodayCard.Workout -> picture(Hue.Heart, PictureKind.ZoneBar)
    TodayCard.Week -> picture(Hue.Steps, PictureKind.WeekRings)
    TodayCard.HabitAge -> picture(Hue.HabitAge, PictureKind.AgeScale)
    TodayCard.EditToday -> CardLook(Tier.Plain, Hue.Neutral)
}

/**
 * The strips that need an answer (spec 4.2), stacked under the hero by urgency with nothing allowed between them.
 * Unlit strips with no picture look alike, so a run of these is the one place two strips may touch: each holds its
 * own question and buttons.
 */
private val ANSWER = setOf(TodayCard.Offer, TodayCard.Suggestion, TodayCard.Alerts, TodayCard.CheckIn)

/** Whether [a] and [b], neighbours, look the same in [look]s: the same design, unless both are strips needing an answer. */
private fun clash(a: TodaySlot, b: TodaySlot, look: (TodaySlot) -> CardLook): Boolean =
    look(a).sameDesign(look(b)) && !(a.card in ANSWER && b.card in ANSWER)

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
    neighbours(slots, wide, smallsAcross).filter { (a, b) -> clash(slots[a], slots[b]) { it.lookOr(moment, live) } }
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

/**
 * The other looks [s] may take (spec 2.5): another tier the spec gives the card. Energy as the second glow steps down
 * to its picture card. Last night as a card becomes a strip, and as a strip a card. A Last night Small stays in its
 * pair. Every other card has one look.
 */
private fun options(s: TodaySlot): List<TodaySlot> = when {
    s.secondGlow -> listOf(s.copy(secondGlow = false)).map { it.copy(look = defaultLook(it, null)) }
    s.card == TodayCard.LastNight && s.size == CardSize.Wide -> listOf(s.copy(size = CardSize.Strip)).map { it.copy(look = defaultLook(it, null)) }
    s.card == TodayCard.LastNight && s.size == CardSize.Strip -> listOf(s.copy(size = CardSize.Wide)).map { it.copy(look = defaultLook(it, null)) }
    else -> emptyList()
}

/** Whether no two neighbours among the first [upTo] + 1 slots look the same. */
private fun settled(slots: List<TodaySlot>, upTo: Int, wide: Boolean, across: Boolean) =
    neighbours(slots, wide, across).none { (a, b) -> b <= upTo && clash(slots[a], slots[b]) { it.look!! } }

/**
 * Goes down the list and, where a card looks like a neighbour before it, gives the card another look ([options]).
 * When it has none, the earlier neighbour changes instead.
 */
private fun restyle(slots: List<TodaySlot>, wide: Boolean, across: Boolean): List<TodaySlot> {
    val out = slots.toMutableList()
    for (i in out.indices) {
        val clashing = neighbours(out, wide, across).filter { (a, b) -> b == i && clash(out[a], out[i]) { it.look!! } }.map { it.first }
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
 * [slots] with a look each, such that no two neighbours ([neighbours]) look the same (spec 2.5). Each card first takes
 * its own look, which draws its own picture ([defaultLook]). A clash left (two strips, a pin beside its twin) is
 * resolved first by the later card taking another tier the spec gives it (Last night as a strip or a card, the second
 * glow as its picture card), and only then by moving a card whose place the spec leaves open (a pin, Your week, Habit
 * Age) to the nearest place it fits, never splitting a pair, with the row rules kept ([fitRows]). [live] is whether
 * heart rate is live, [wide] whether Today has two columns, and [smallsAcross] whether a Small pair spans both of
 * them ([todayPanes]).
 */
fun varyLooks(slots: List<TodaySlot>, moment: Moment?, live: Boolean = false, wide: Boolean = false, smallsAcross: Boolean = false): List<TodaySlot> {
    val across = smallsAcross && wide
    val dress = { l: List<TodaySlot> -> fitRows(l).map { if (it.look == null) it.copy(look = defaultLook(it, moment, live)) else it } }
    var base = dress(slots.map { it.copy(look = null) })
    repeat(slots.size + 1) {
        val styled = restyle(base, wide, across)
        val clash = lookClashes(styled, wide, smallsAcross = across).firstOrNull() ?: return styled
        base = move(base, clash, wide, across, dress) ?: return styled
    }
    return restyle(base, wide, across)
}

/**
 * [base] with one movable card moved to the nearest place where it looks like none of its neighbours and fewer cards
 * clash overall: a card of the [clash] first, the later one first, then any other. Nothing moves above the hero and
 * the strips that need an answer, or past Edit Today. [dress] keeps the row rules and gives a resized card its look.
 */
private fun move(
    base: List<TodaySlot>, clash: Pair<Int, Int>, wide: Boolean, across: Boolean, dress: (List<TodaySlot>) -> List<TodaySlot>,
): List<TodaySlot>? {
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
            val moved = dress(rest.toMutableList().also { it.add(to, card) })
            val clashes = lookClashes(restyle(moved, wide, across), wide, smallsAcross = across)
            if (clashes.size < before && clashes.none { it.first == to || it.second == to }) return moved
        }
    }
    return null
}

private val URGENT = setOf(TodayCard.Hero, TodayCard.Offer, TodayCard.Suggestion, TodayCard.Alerts)
