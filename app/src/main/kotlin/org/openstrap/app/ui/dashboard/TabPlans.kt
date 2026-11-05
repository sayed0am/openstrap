package org.openstrap.app.ui.dashboard

import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.Pane

/*
 * The cards of the Recovery, Sleep and Activity tabs, in order, each with its look and the column it takes on a wide
 * screen (spec 5). Unlike Today, the tabs keep one set of cards whatever the day, each at its ranked size with its own
 * picture (spec 2.4, 2.7), chosen so no two neighbours ever look the same: no clash to resolve at run time.
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

/** A glow at [size]: the tab's hero (Large) or its level glow. */
private fun glow(h: Hue, p: PictureKind, size: CardSize = CardSize.Large) = CardLook(Tier.Glow, h, Shape.of(size), p)
private val ListLook = CardLook(Tier.List, Hue.Neutral)

/** A picture card at [size] drawing [p] in [h]'s ink. */
private fun <C> card(card: C, h: Hue, p: PictureKind, pane: Pane, size: CardSize = CardSize.Wide) =
    TabSlot(card, CardLook(Tier.Picture, h, Shape.of(size), p), pane, size)

private fun <C> list(card: C, pane: Pane) = TabSlot(card, ListLook, pane, CardSize.List)

/* ---------------------------------------------- Recovery -------------------------------------------- */

enum class RecoveryCard { Hero, Drivers, Charge, Recharged, Drained, SkinTemp, BodySignals, HabitAge }

/**
 * What changes the Recovery tab. Only [habitAge] adds or removes a card; [learning] and [hasRecovery] change what
 * the cards say, never which cards there are, so the screen keeps its shape from the first night on.
 */
data class RecoveryFacts(val habitAge: Boolean = true, val learning: Boolean = false, val hasRecovery: Boolean = true)

/**
 * Recovery (spec 5.1): the score glows with a week of dots, what drove it as a list, then Energy as a Tall glow battery
 * drawing its curve, with Recharged (a fill bar) over Drained (a split bar) as plain Smalls beside it. Skin
 * temperature is a short Wide with its dial, between the battery and Body signals, a list again. Habit Age, its age
 * scale, always last.
 *
 * Wide: the hero spans both columns; What drove it and Habit Age on the left, the Energy battery, Skin temperature and
 * Body signals on the right, so no two lists sit side by side.
 */
fun recoveryCards(facts: RecoveryFacts = RecoveryFacts()): List<TabSlot<RecoveryCard>> = listOfNotNull(
    TabSlot(RecoveryCard.Hero, glow(Hue.Recovery, PictureKind.WeekDots), Pane.Full, CardSize.Large),
    list(RecoveryCard.Drivers, Pane.Left),
    TabSlot(RecoveryCard.Charge, glow(Hue.Charge, PictureKind.Curve, CardSize.Tall), Pane.Right, CardSize.Tall),
    card(RecoveryCard.Recharged, Hue.Charge, PictureKind.FillBar, Pane.Right, CardSize.Small),
    card(RecoveryCard.Drained, Hue.Charge, PictureKind.SplitBar, Pane.Right, CardSize.Small),
    card(RecoveryCard.SkinTemp, Hue.SkinTemp, PictureKind.Dial, Pane.Right),
    list(RecoveryCard.BodySignals, Pane.Right),
    card(RecoveryCard.HabitAge, Hue.HabitAge, PictureKind.AgeScale, Pane.Left).takeIf { facts.habitAge },
)

/* ----------------------------------------------- Sleep ---------------------------------------------- */

enum class SleepCard { Hero, Vitals, Regularity, Breathing, Naps }

/** What changes the Sleep tab: whether the night had a breathing summary, and whether there were naps. */
data class SleepFacts(val breathing: Boolean = true, val naps: Boolean = false, val hasNight: Boolean = true)

/**
 * Sleep (spec 5.2): the night glows with its hypnogram and stage bar, its vitals as a list. Regularity is a Wide of
 * the week's bed and wake bars. Breathing disturbances (a week of dots through your band) and Naps (the day with the
 * nap on it) are a Small pair; alone, either is a Wide (a lone Small becomes a Wide).
 *
 * Wide: the night spans both columns, the vitals with the pair (or a lone naps card) on the left, regularity and a
 * lone breathing card on the right.
 */
fun sleepCards(facts: SleepFacts = SleepFacts()): List<TabSlot<SleepCard>> {
    val pair = facts.breathing && facts.naps
    val size = if (pair) CardSize.Small else CardSize.Wide
    return listOfNotNull(
        TabSlot(SleepCard.Hero, glow(Hue.Sleep, PictureKind.Hypnogram), Pane.Full, CardSize.Large),
        list(SleepCard.Vitals, Pane.Left),
        card(SleepCard.Regularity, Hue.Sleep, PictureKind.BedWakeBars, Pane.Right),
        card(SleepCard.Breathing, Hue.Sleep, PictureKind.WeekDots, if (pair) Pane.Left else Pane.Right, size).takeIf { facts.breathing },
        card(SleepCard.Naps, Hue.Sleep, PictureKind.NapDay, Pane.Left, size).takeIf { facts.naps },
    )
}

/* --------------------------------------------- Activity --------------------------------------------- */

enum class ActivityCard { Start, Steps, ActiveMinutes, HeartPoints, Load, HeartRate, Facts, Workouts }

/**
 * What changes the Activity tab: the Start a workout card is [isToday] only. A [suggestion] to save is a row in the
 * workouts list, not a card of its own. [live] heart rate changes what the card says, not how it looks.
 */
data class ActivityFacts(val isToday: Boolean = true, val suggestion: Boolean = false, val live: Boolean = false)

/**
 * Activity (spec 5.3): a Wide card to start a sport (today only, unlit with a light moving round its border), steps
 * as the level glow with its hourly bars, Active minutes (blocks) and Heart points (day bars) as a Small pair, Load
 * filling over your usual band, the day's facts as a list, the day's heart rate as its line, then the workouts list.
 * The Today list sits between Load and Heart rate, so two Wides never touch, and Heart rate between the two lists.
 *
 * Wide: the Start a workout card and steps span both columns; the Small pair and Load on the left, the facts, heart
 * rate and workouts on the right.
 */
fun activityCards(facts: ActivityFacts = ActivityFacts()): List<TabSlot<ActivityCard>> = listOfNotNull(
    // Plain, not a list: an unlit card whose border carries the moving light.
    TabSlot(ActivityCard.Start, CardLook(Tier.Plain, Hue.Heart, picture = PictureKind.None), Pane.Full).takeIf { facts.isToday },
    TabSlot(ActivityCard.Steps, glow(Hue.Steps, PictureKind.HourlyBars), Pane.Full, CardSize.Large),
    card(ActivityCard.ActiveMinutes, Hue.Steps, PictureKind.Blocks, Pane.Left, CardSize.Small),
    card(ActivityCard.HeartPoints, Hue.Heart, PictureKind.DayBars, Pane.Left, CardSize.Small),
    card(ActivityCard.Load, Hue.Load, PictureKind.LevelFill, Pane.Left),
    list(ActivityCard.Facts, Pane.Right),
    card(ActivityCard.HeartRate, Hue.Heart, PictureKind.Line, Pane.Right),
    list(ActivityCard.Workouts, Pane.Right),
)
