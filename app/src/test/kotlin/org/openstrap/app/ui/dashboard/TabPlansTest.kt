package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.GridRow
import org.openstrap.app.ui.glow.packGrid
import org.openstrap.app.ui.glow.Pane

/**
 * No two neighbouring cards on the Recovery, Sleep and Activity tabs look the same (spec 2.4), with every optional
 * card there or not, on a phone and on two columns.
 */
class TabPlansTest {
    private val bools = listOf(false, true)

    /** Fails, naming the pair, when any neighbours in [slots] look the same, on a phone or on two columns. */
    private fun assertVaried(slots: List<TabSlot<*>>, wide: Boolean, what: String) {
        for (across in if (wide) bools else listOf(false)) {
            val clash = tabClashes(slots, wide, across).firstOrNull() ?: continue
            throw AssertionError("$what across=$across: ${slots[clash.first].card} and ${slots[clash.second].card} look the same, ${slots[clash.first].look}")
        }
        // No picture kind twice on a tab, so no two neighbours can share one.
        val pics = slots.mapNotNull { it.look.picture }.filter { it != PictureKind.None }
        assertEquals(what, pics.distinct(), pics)
    }

    /** Fails when two Wides follow each other on a phone with no list between them (spec 2.5). */
    private fun assertNoWidesInARow(slots: List<TabSlot<*>>, what: String) {
        slots.zipWithNext { a, b ->
            assertTrue("$what: ${a.card} and ${b.card} are two Wides in a row", !(a.size == CardSize.Wide && b.size == CardSize.Wide))
        }
    }

    @Test fun `recovery is varied in every state`() {
        var checked = 0
        for (habit in bools) for (learning in bools) for (has in bools) for (wide in bools) {
            val f = RecoveryFacts(habitAge = habit, learning = learning, hasRecovery = has)
            val slots = recoveryCards(f)
            assertVaried(slots, wide, "$f wide=$wide")
            assertNoWidesInARow(slots, "$f")
            // The same cards whether learning or not, and with or without a Recovery yet: only Habit Age comes and goes.
            assertEquals(recoveryCards(RecoveryFacts(habitAge = habit)).map { it.card }, slots.map { it.card })
            checked++
        }
        assertEquals(16, checked)
    }

    @Test fun `sleep is varied in every state`() {
        var checked = 0
        for (breathing in bools) for (naps in bools) for (night in bools) for (wide in bools) {
            val f = SleepFacts(breathing = breathing, naps = naps, hasNight = night)
            assertVaried(sleepCards(f), wide, "$f wide=$wide")
            // Spec 5.2 puts a lone breathing or naps Wide right under regularity: an open question against 2.5.
            if (breathing == naps) assertNoWidesInARow(sleepCards(f), "$f")
            checked++
        }
        assertEquals(16, checked)
    }

    @Test fun `activity is varied in every state`() {
        var checked = 0
        for (today in bools) for (suggestion in bools) for (live in bools) for (wide in bools) {
            val f = ActivityFacts(isToday = today, suggestion = suggestion, live = live)
            assertVaried(activityCards(f), wide, "$f wide=$wide")
            assertNoWidesInARow(activityCards(f), "$f")
            checked++
        }
        assertEquals(16, checked)
    }

    @Test fun `each tab leads with one glow`() {
        for (slots in listOf(recoveryCards(), sleepCards(), activityCards(ActivityFacts(isToday = false)))) {
            assertEquals(Tier.Glow, slots.first().look.tier)
        }
        // Today's start strip sits over the steps glow, which still leads the cards.
        assertEquals(listOf(ActivityCard.Start, ActivityCard.Steps), activityCards().take(2).map { it.card })
        // At most two glows and two plain lists on a screen (spec 2.4).
        for (slots in listOf(recoveryCards(), sleepCards(SleepFacts(naps = true)), activityCards())) {
            assertTrue(slots.count { it.look.tier == Tier.Glow } <= 2)
            assertTrue(slots.count { it.look.tier == Tier.List } <= 2)
        }
    }

    @Test fun `each card draws its own picture`() {
        fun <C> pictures(slots: List<TabSlot<C>>) = slots.associate { it.card to it.look.picture }
        assertEquals(
            mapOf(
                RecoveryCard.Hero to PictureKind.WeekDots, RecoveryCard.Drivers to null, RecoveryCard.Charge to PictureKind.Curve,
                RecoveryCard.Recharged to PictureKind.FillBar, RecoveryCard.Drained to PictureKind.SplitBar, RecoveryCard.SkinTemp to PictureKind.Dial,
                RecoveryCard.BodySignals to null, RecoveryCard.HabitAge to PictureKind.AgeScale,
            ),
            pictures(recoveryCards()),
        )
        assertEquals(
            mapOf(
                SleepCard.Hero to PictureKind.Hypnogram, SleepCard.Vitals to null, SleepCard.Regularity to PictureKind.BedWakeBars,
                SleepCard.Breathing to PictureKind.WeekDots, SleepCard.Naps to PictureKind.NapDay,
            ),
            pictures(sleepCards(SleepFacts(naps = true))),
        )
        assertEquals(
            mapOf(
                ActivityCard.Start to PictureKind.None, ActivityCard.Steps to PictureKind.HourlyBars, ActivityCard.ActiveMinutes to PictureKind.Blocks,
                ActivityCard.HeartPoints to PictureKind.DayBars, ActivityCard.Load to PictureKind.LevelFill, ActivityCard.Facts to null,
                ActivityCard.HeartRate to PictureKind.Line, ActivityCard.Workouts to null,
            ),
            pictures(activityCards()),
        )
        val rec = recoveryCards()
        assertEquals(RecoveryCard.HabitAge, rec.last().card)
        assertEquals(Tier.Glow, rec.first { it.card == RecoveryCard.Charge }.look.tier)
        assertEquals(Tier.Glow, activityCards().first { it.card == ActivityCard.Steps }.look.tier)
        // Recharged and Drained are plain picture cards; lists are lists.
        assertEquals(Tier.Picture, rec.first { it.card == RecoveryCard.Recharged }.look.tier)
        assertEquals(CardLook(Tier.List, Hue.Neutral), rec.first { it.card == RecoveryCard.BodySignals }.look)
    }

    @Test fun `sizes follow the ranking`() {
        fun <C> sizes(slots: List<TabSlot<C>>) = slots.associate { it.card to it.size }
        val rec = recoveryCards()
        assertEquals(
            listOf(RecoveryCard.Hero, RecoveryCard.Drivers, RecoveryCard.Charge, RecoveryCard.Recharged, RecoveryCard.Drained, RecoveryCard.SkinTemp,
                RecoveryCard.BodySignals, RecoveryCard.HabitAge),
            rec.map { it.card },
        )
        assertEquals(listOf(CardSize.Tall, CardSize.Small, CardSize.Small, CardSize.Wide), rec.subList(2, 6).map { it.size })
        // The Tall and its Smalls share a column on two columns, Skin temperature under them, What drove it and Habit
        // Age on the left.
        assertEquals(listOf(Pane.Right), rec.subList(2, 7).map { it.pane }.distinct())
        assertEquals(listOf(Pane.Left, Pane.Left), rec.filter { it.card == RecoveryCard.Drivers || it.card == RecoveryCard.HabitAge }.map { it.pane })
        assertTrue(packGrid(rec.map { it.size to it.card }).any { it is GridRow.TallPair })
        // Breathing disturbances and naps pair up only together; alone, either is a Wide.
        assertEquals(CardSize.Small, sizes(sleepCards(SleepFacts(breathing = true, naps = true)))[SleepCard.Breathing])
        assertEquals(CardSize.Small, sizes(sleepCards(SleepFacts(breathing = true, naps = true)))[SleepCard.Naps])
        assertEquals(CardSize.Wide, sizes(sleepCards(SleepFacts(breathing = true, naps = false)))[SleepCard.Breathing])
        assertEquals(CardSize.Wide, sizes(sleepCards(SleepFacts(breathing = false, naps = true)))[SleepCard.Naps])
        assertEquals(CardSize.Wide, sizes(sleepCards())[SleepCard.Regularity])
        val act = sizes(activityCards())
        assertEquals(CardSize.Large, act[ActivityCard.Steps])
        assertEquals(CardSize.Small, act[ActivityCard.ActiveMinutes])
        assertEquals(CardSize.Small, act[ActivityCard.HeartPoints])
        assertEquals(CardSize.Wide, act[ActivityCard.Load])
        assertEquals(CardSize.Wide, act[ActivityCard.HeartRate])
        // The Today list sits between Load and Heart rate, and Heart rate between the two lists.
        assertEquals(
            listOf(ActivityCard.Load, ActivityCard.Facts, ActivityCard.HeartRate, ActivityCard.Workouts),
            activityCards().map { it.card }.dropWhile { it != ActivityCard.Load },
        )
    }

    @Test fun `two cards drawing one picture in a row would clash`() {
        // Breathing disturbances drawn as regularity's bars, right under it.
        val bars = sleepCards().first { it.card == SleepCard.Regularity }.look
        val slots = sleepCards().map { if (it.card == SleepCard.Breathing) it.copy(look = bars) else it }
        assertEquals(listOf(2 to 3), tabClashes(slots, wide = false))
    }

    @Test fun `smalls side by side and a tall with its smalls are neighbours`() {
        val panes = List(6) { if (it == 0) Pane.Full else Pane.Left }
        val sizes = listOf(CardSize.Large, CardSize.Tall, CardSize.Small, CardSize.Small, CardSize.Small, CardSize.Small)
        val n = neighbours(panes, wide = false, sizes = sizes)
        // The hero touches the Tall and the top Small; the Tall touches both its Smalls; the pair under it touches the
        // Tall and the bottom Small, and each other.
        assertTrue(listOf(0 to 1, 0 to 2, 1 to 2, 1 to 3, 2 to 3, 1 to 4, 1 to 5, 3 to 4, 3 to 5, 4 to 5).all { it in n })
        assertTrue(0 to 3 !in n)
        assertTrue(2 to 4 !in n)
    }

    @Test fun `on two columns a full card touches the top of both columns`() {
        val glow = CardLook(Tier.Glow, Hue.Recovery, picture = PictureKind.WeekDots)
        val list = CardLook(Tier.List, Hue.Neutral)
        // A hero over a run whose right column starts with a glow: not next in reading order, but right under it.
        val slots = listOf(
            TabSlot("hero", glow, Pane.Full), TabSlot("a", list, Pane.Left), TabSlot("b", glow, Pane.Right),
        )
        assertTrue(tabClashes(slots, wide = false).isEmpty())
        assertEquals(listOf(0 to 2), tabClashes(slots, wide = true))
        // Without fullTouchesColumns only reading order, side by side and down each column count.
        assertEquals(setOf(0 to 1, 1 to 2), neighbours(slots.map { it.pane }, wide = true))
        assertEquals(setOf(0 to 1, 1 to 2, 0 to 2), neighbours(slots.map { it.pane }, wide = true, fullTouchesColumns = true))
    }
}
