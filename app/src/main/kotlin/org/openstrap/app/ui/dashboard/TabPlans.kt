package org.openstrap.app.ui.dashboard

import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.draw.Edge
import org.openstrap.app.ui.glow.Pane

/*
 * The cards of the Recovery, Sleep and Activity tabs, in order, each with its look and the column it takes on a wide
 * screen (spec 5). Unlike Today, the tabs keep one set of cards whatever the day, so each card's edge is fixed by
 * what it means (spec 2.4) and chosen so no two neighbours ever look the same: no clash to resolve at run time.
 * The cards that come and go are only the optional ones in each tab's facts, and the tests go through all of them.
 */

/**
 * A tab card: which [card], its [look], its [pane] on a wide screen (on a phone every card stacks in order) and its
 * [size] on the grid. Cards of one Small pair or Tall pair share a pane.
 */
data class TabSlot<C>(val card: C, val look: CardLook, val pane: Pane, val size: CardSize = CardSize.Wide)

/**
 * The pane each of [slots] takes on two columns: its own, except that with [smallsAcross] (columns too narrow for
 * Smalls) a row of Smalls spans both ([spanSmalls]).
 */
fun tabPanes(slots: List<TabSlot<*>>, smallsAcross: Boolean = false): List<Pane> =
    slots.map { it.pane }.let { if (smallsAcross) spanSmalls(it, slots.map { s -> s.size }) else it }

/**
 * The neighbours among [slots] that look the same. On two columns, a full-width card counts as touching the top and
 * bottom card of each column next to it, as it does on screen. Smalls side by side, and a Tall with its Smalls, touch.
 * [smallsAcross] is two columns with the rows of Smalls spanning both ([tabPanes]).
 */
fun tabClashes(slots: List<TabSlot<*>>, wide: Boolean, smallsAcross: Boolean = false): List<Pair<Int, Int>> =
    lookClashes(slots.map { it.look }, tabPanes(slots, smallsAcross && wide), wide, fullTouchesColumns = true, sizes = slots.map { it.size })

private fun glow(h: Hue) = CardLook(Tier.Glow, h)
private fun edge(h: Hue, e: Edge) = CardLook(Tier.Edge, h, e)
private val ListLook = CardLook(Tier.List, Hue.Neutral)

/** A Small edge card: its own shape, so it only ever matches another Small lit from the same side. */
private fun <C> small(card: C, h: Hue, e: Edge, pane: Pane) = TabSlot(card, CardLook(Tier.Edge, h, e, Shape.Small), pane, CardSize.Small)

/* ---------------------------------------------- Recovery -------------------------------------------- */

enum class RecoveryCard { Hero, Drivers, Charge, Recharged, Drained, BodySignals, HabitAge }

/**
 * What changes the Recovery tab. Only [habitAge] adds or removes a card; [learning] and [hasRecovery] change what
 * the cards say, never which cards there are, so the screen keeps its shape from the first night on.
 */
data class RecoveryFacts(val habitAge: Boolean = true, val learning: Boolean = false, val hasRecovery: Boolean = true)

/**
 * Recovery (spec 5.1): the score glows, what drove it as a list, then Charge as a Tall battery glowing to its level
 * with Recharged (lit from below) and Drained (from above) as Smalls beside it, so the two differ. Body signals is a
 * plain list again: the Smalls sit between it and What drove it. Habit Age is about earlier (last week), lit from the
 * left, always last.
 *
 * Wide: the hero spans both columns; What drove it and Habit Age on the left, the Charge battery and Body signals on
 * the right, so no two lists sit side by side.
 */
fun recoveryCards(facts: RecoveryFacts = RecoveryFacts()): List<TabSlot<RecoveryCard>> = listOfNotNull(
    TabSlot(RecoveryCard.Hero, glow(Hue.Recovery), Pane.Full),
    TabSlot(RecoveryCard.Drivers, ListLook, Pane.Left),
    TabSlot(RecoveryCard.Charge, CardLook(Tier.Glow, Hue.Charge, shape = Shape.Tall), Pane.Right, CardSize.Tall),
    small(RecoveryCard.Recharged, Hue.Charge, Edge.Bottom, Pane.Right),
    small(RecoveryCard.Drained, Hue.Charge, Edge.Top, Pane.Right),
    TabSlot(RecoveryCard.BodySignals, ListLook, Pane.Right),
    TabSlot(RecoveryCard.HabitAge, edge(Hue.HabitAge, Edge.Left), Pane.Left).takeIf { facts.habitAge },
)

/* ----------------------------------------------- Sleep ---------------------------------------------- */

enum class SleepCard { Hero, Vitals, Regularity, Breathing, Naps }

/** What changes the Sleep tab: whether the night had a breathing summary, and whether there were naps. */
data class SleepFacts(val breathing: Boolean = true, val naps: Boolean = false, val hasNight: Boolean = true)

/**
 * Sleep (spec 5.2): the night glows, its vitals as a list. Regularity is lit from the top like a night sky. Breathing
 * disturbances is a value against your usual, so it's lit from below, and naps happened earlier in the day, lit from
 * the left. With both they're a Small pair; alone, either is a Wide (a lone Small becomes a Wide).
 *
 * Wide: the night spans both columns, the vitals with the pair on the left, regularity on the right. A lone
 * breathing card joins regularity on the right, a lone naps card the vitals on the left.
 */
fun sleepCards(facts: SleepFacts = SleepFacts()): List<TabSlot<SleepCard>> {
    val pair = facts.breathing && facts.naps
    return listOfNotNull(
        TabSlot(SleepCard.Hero, glow(Hue.Sleep), Pane.Full),
        TabSlot(SleepCard.Vitals, ListLook, Pane.Left),
        TabSlot(SleepCard.Regularity, edge(Hue.Sleep, Edge.Top), Pane.Right),
        (if (pair) small(SleepCard.Breathing, Hue.Sleep, Edge.Bottom, Pane.Left) else TabSlot(SleepCard.Breathing, edge(Hue.Sleep, Edge.Bottom), Pane.Right))
            .takeIf { facts.breathing },
        (if (pair) small(SleepCard.Naps, Hue.Sleep, Edge.Left, Pane.Left) else TabSlot(SleepCard.Naps, edge(Hue.Sleep, Edge.Left), Pane.Left))
            .takeIf { facts.naps },
    )
}

/* --------------------------------------------- Activity --------------------------------------------- */

enum class ActivityCard { Start, Steps, ActiveMinutes, HeartPoints, Load, HeartRate, Facts, Workouts }

/**
 * What changes the Activity tab: the Start a workout card is [isToday] only, and heart rate is lit from the right while it's
 * [live]. A [suggestion] to save is a row in the workouts list, not a card of its own.
 */
data class ActivityFacts(val isToday: Boolean = true, val suggestion: Boolean = false, val live: Boolean = false)

/**
 * Activity (spec 5.3): a Wide card to start a sport (today only, unlit with a light moving round its border), steps glowing,
 * active minutes (from below) and Heart points (from above) as a Small pair, Load filling across toward your usual,
 * the day's facts as a list, the day's heart rate (from the right while live, else from below like any usual value),
 * then the workouts list, unlit like the facts. Heart rate sits between the two lists, so they never touch and
 * neither needs a light that says nothing.
 *
 * Wide: the Start a workout card and steps span both columns; the Small pair and Load on the left, the facts, heart
 * rate and workouts on the right.
 */
fun activityCards(facts: ActivityFacts = ActivityFacts()): List<TabSlot<ActivityCard>> = listOfNotNull(
    // Plain, not a list: an unlit card whose border carries the moving light.
    TabSlot(ActivityCard.Start, CardLook(Tier.Plain, Hue.Heart), Pane.Full).takeIf { facts.isToday },
    TabSlot(ActivityCard.Steps, glow(Hue.Steps), Pane.Full),
    small(ActivityCard.ActiveMinutes, Hue.Steps, Edge.Bottom, Pane.Left),
    small(ActivityCard.HeartPoints, Hue.Heart, Edge.Top, Pane.Left),
    TabSlot(ActivityCard.Load, CardLook(Tier.Level, Hue.Load, Edge.Left), Pane.Left),
    TabSlot(ActivityCard.Facts, ListLook, Pane.Right),
    TabSlot(ActivityCard.HeartRate, edge(Hue.Heart, if (facts.live) Edge.Right else Edge.Bottom), Pane.Right),
    TabSlot(ActivityCard.Workouts, ListLook, Pane.Right),
)
