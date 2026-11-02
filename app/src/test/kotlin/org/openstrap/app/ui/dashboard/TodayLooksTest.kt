package org.openstrap.app.ui.dashboard

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Moment
import org.openstrap.app.ui.dashboard.TodayCard.Activity
import org.openstrap.app.ui.dashboard.TodayCard.EditToday
import org.openstrap.app.ui.dashboard.TodayCard.Hero
import org.openstrap.app.ui.dashboard.TodayCard.LastNight
import org.openstrap.app.ui.dashboard.TodayCard.LoadToday
import org.openstrap.app.ui.dashboard.TodayCard.Recovery
import org.openstrap.app.ui.dashboard.TodayCard.RightNow
import org.openstrap.app.ui.dashboard.TodayCard.Week
import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.draw.Edge
import org.openstrap.app.ui.glow.Pane

/** No two Today cards next to each other look the same (spec 2.4), whatever the moment, the pins and the hides. */
class TodayLooksTest {
    private val allFacts = TodayFacts(offer = true, suggestion = true, alerts = true, workout = true, healthspan = true)

    /** Fails, naming the pair, when any neighbours in [slots] look the same. */
    private fun assertVaried(slots: List<TodaySlot>, wide: Boolean, what: String) {
        val clash = lookClashes(slots, wide).firstOrNull() ?: return
        throw AssertionError("$what: ${slots[clash.first].card} and ${slots[clash.second].card} look the same, ${slots[clash.first].look}")
    }

    /** Today as the screen builds it. */
    private fun today(m: Moment, facts: TodayFacts, edits: TodayEdits = TodayEdits(), live: Boolean = false, wide: Boolean = false) =
        varyLooks(applyEdits(todayCards(m, facts), edits, m), m, live, wide)

    @Test fun `the check catches two neighbours that look the same`() {
        val teal = CardLook(Tier.Edge, Hue.Recovery, Edge.Left)
        val slots = listOf(TodaySlot(Recovery, CardSize.Wide, look = teal), TodaySlot(Week, CardSize.Wide, look = teal))
        assertEquals(listOf(0 to 1), lookClashes(slots, wide = false))
        // Side by side on two columns counts, as does one over the other in a column.
        val four = List(4) { TodaySlot(Activity, CardSize.Wide, look = CardLook(Tier.Edge, Hue.Steps, if (it % 2 == 1) Edge.Top else Edge.Bottom)) }
        assertTrue(lookClashes(four, wide = false).isEmpty())
        assertEquals(listOf(0 to 2, 1 to 3), lookClashes(four, wide = true))
        // Colour alone doesn't tell two cards apart.
        val bars = listOf(TodaySlot(TodayCard.Charge, CardSize.Wide, look = CardLook(Tier.Edge, Hue.Charge, Edge.Bottom)), TodaySlot(Activity, CardSize.Wide, look = CardLook(Tier.Edge, Hue.Steps, Edge.Bottom)))
        assertEquals(listOf(0 to 1), lookClashes(bars, wide = false))
    }

    @Test fun `Charge as the second glow is a level glow across from the left, unlike the hero's glow`() {
        val window = today(Moment.WorkoutWindow, TodayFacts())
        assertEquals(TodayCard.Charge, window[1].card)
        assertEquals(CardLook(Tier.Glow, Hue.Charge, Edge.Left), window[1].look)
        assertEquals(true, window[1].secondGlow)
        // After a workout and in the evening it's the same level glow, and the only glow besides the hero.
        for (m in listOf(Moment.After, Moment.Evening, Moment.Night)) {
            val out = today(m, TodayFacts(workout = true))
            assertEquals("$m", CardLook(Tier.Glow, Hue.Charge, Edge.Left), out.first { it.card == TodayCard.Charge }.look)
            assertEquals("$m", 2, out.count { it.look?.tier == Tier.Glow })
        }
    }

    @Test fun `no moment has more than one glow besides the hero, whatever is pinned`() {
        val pinnable = TodayCard.entries.filter { it.pinnable }
        for (m in Moment.entries) for (a in pinnable) for (b in pinnable.filter { it != a } + listOf(null)) {
            val out = today(m, allFacts, TodayEdits(pinned = listOfNotNull(a, b)))
            assertTrue("$m $a $b", out.count { it.card != Hero && it.look?.tier == Tier.Glow } <= 1)
        }
    }

    @Test fun `after a run Load and time to recover differ side by side, as mocked`() {
        for (wide in listOf(false, true)) {
            val after = today(Moment.After, TodayFacts(workout = true), wide = wide)
            assertEquals(CardLook(Tier.Edge, Hue.Load, Edge.Bottom, Shape.Small), after.first { it.card == LoadToday }.look)
            assertEquals(CardLook(Tier.Edge, Hue.Load, Edge.Top, Shape.Small), after.first { it.card == TodayCard.Recover }.look)
            // Your week is unlit, so it needs no flip under Activity's light.
            assertEquals(CardLook(Tier.List, Hue.Neutral), after.first { it.card == Week }.look)
            assertVaried(after, wide, "after wide=$wide")
        }
    }

    @Test fun `in the evening only the glows, Steps and heart rate are lit`() {
        for (wide in listOf(false, true)) {
            val evening = today(Moment.Evening, TodayFacts(), wide = wide)
            val unlit = evening.filter { it.look?.tier == Tier.List }.map { it.card }
            assertEquals(listOf(LastNight, Week, TodayCard.StartWorkout), unlit)
            // Two unlit strips and an unlit card between them: told apart by their height.
            assertEquals(Shape.Strip, evening.first { it.card == LastNight }.look?.shape)
            assertEquals(Shape.Card, evening.first { it.card == Week }.look?.shape)
            assertVaried(evening, wide, "evening wide=$wide")
        }
    }

    @Test fun `in the evening heart rate flips to the top under Activity`() {
        val evening = today(Moment.Evening, TodayFacts())
        assertEquals(listOf(Hero, TodayCard.Charge, Activity, TodayCard.HeartRate, LastNight, Week, TodayCard.StartWorkout, EditToday), evening.map { it.card })
        assertEquals(Edge.Bottom, evening.first { it.card == Activity }.look?.edge)
        assertEquals(Edge.Top, evening.first { it.card == TodayCard.HeartRate }.look?.edge)
        // Live, it's lit from the right and needs no flip.
        assertEquals(Edge.Right, today(Moment.Evening, TodayFacts(), live = true).first { it.card == TodayCard.HeartRate }.look?.edge)
    }

    @Test fun `a Small is its own design, but two Smalls side by side are neighbours`() {
        val wideBottom = CardLook(Tier.Edge, Hue.Steps, Edge.Bottom)
        assertFalse(wideBottom.sameDesign(wideBottom.copy(shape = Shape.Small)))
        assertFalse(wideBottom.copy(shape = Shape.Tall).sameDesign(wideBottom.copy(shape = Shape.Small)))
        val pair = listOf(
            TodaySlot(Hero, CardSize.Large), TodaySlot(LoadToday, CardSize.Small), TodaySlot(TodayCard.Recover, CardSize.Small),
            TodaySlot(Activity, CardSize.Wide),
        )
        // The hero touches both Smalls under it, and so does the card under them.
        assertTrue(setOf(0 to 1, 0 to 2, 1 to 2, 1 to 3, 2 to 3).all { it in neighbours(pair, wide = false) })
    }

    @Test fun `on two columns a full-width card touches the top card of each column under it`() {
        // Hero, then two edge cards side by side: the right one isn't next to the hero in reading order, but it's
        // right under it. Today counts it as tabClashes does, so the glow over a glow is caught.
        val glow = CardLook(Tier.Glow, Hue.Charge)
        val slots = listOf(
            TodaySlot(Hero, CardSize.Large, look = glow), TodaySlot(Activity, CardSize.Wide, look = CardLook(Tier.Edge, Hue.Steps, Edge.Bottom)),
            TodaySlot(TodayCard.Charge, CardSize.Wide, look = glow),
        )
        assertEquals(listOf(Pane.Full, Pane.Left, Pane.Right), todayPanes(slots))
        assertTrue(lookClashes(slots, wide = false).isEmpty())
        assertEquals(listOf(0 to 2), lookClashes(slots, wide = true))
        // Charge's own second glow is lit from the left, so it doesn't clash with the hero's.
        val out = varyLooks(listOf(slots[0], slots[1], slots[2].copy(look = null, secondGlow = true)).map { it.copy(look = null) }, Moment.WorkoutWindow, wide = true)
        assertVaried(out, true, "full over columns")
        assertEquals(CardLook(Tier.Glow, Hue.Charge, Edge.Left), out[2].look)
    }

    @Test fun `every moment's own cards are varied, on a phone and on two columns`() {
        for (m in Moment.entries) for (facts in listOf(TodayFacts(), allFacts, TodayFacts(isToday = false, alerts = true))) {
            for (live in listOf(false, true)) for (wide in listOf(false, true)) {
                val out = today(m, facts, live = live, wide = wide)
                assertVaried(out, wide, "$m $facts live=$live wide=$wide")
                // The defaults need no help: same cards, same order.
                assertEquals(todayCards(m, facts).map { it.card }, out.map { it.card })
            }
        }
    }

    @Test fun `your week no longer borrows Recovery's teal`() {
        val midday = today(Moment.Midday, TodayFacts())
        val rec = midday.first { it.card == Recovery }.look
        val week = midday.first { it.card == Week }.look
        assertEquals(CardLook(Tier.List, Hue.Neutral), week)
        assertNotEquals(rec?.hue, week?.hue)
        assertNotEquals(rec?.tier, week?.tier)
    }

    @Test fun `a clash flips the later card's edge first`() {
        val slots = listOf(TodaySlot(Hero, CardSize.Large), TodaySlot(Activity, CardSize.Wide), TodaySlot(Activity, CardSize.Wide))
        val out = varyLooks(slots, Moment.Midday)
        assertEquals(Edge.Bottom, out[1].look?.edge)
        assertEquals(Edge.Top, out[2].look?.edge)
        assertEquals(Hue.Steps, out[2].look?.hue)
    }

    @Test fun `two lists together keep their places, the second on a neutral edge`() {
        val out = today(Moment.Midday, TodayFacts(), TodayEdits(pinned = listOf(RightNow, LoadToday)))
        assertEquals(listOf(Hero, RightNow, LoadToday, LastNight, Activity), out.map { it.card }.take(5))
        assertEquals(CardLook(Tier.List, Hue.Neutral), out[1].look)
        assertEquals(CardLook(Tier.Edge, Hue.Neutral, Edge.Bottom), out[2].look)
        assertVaried(out, false, "midday pins")
    }

    @Test fun `a second glow next to another steps down to its level card`() {
        val charge = TodaySlot(TodayCard.Charge, CardSize.Wide, secondGlow = true, pinned = true)
        val slots = listOf(
            TodaySlot(Hero, CardSize.Large), TodaySlot(Activity, CardSize.Wide), charge, charge, TodaySlot(Recovery, CardSize.Wide),
            TodaySlot(EditToday, CardSize.Strip),
        )
        val out = varyLooks(slots, Moment.Midday)
        assertEquals(slots.map { it.card }, out.map { it.card })
        assertEquals(CardLook(Tier.Glow, Hue.Charge, Edge.Left), out[2].look)
        assertEquals(CardLook(Tier.Level, Hue.Charge, Edge.Left), out[3].look)
        assertFalse(out[3].secondGlow)
        assertVaried(out, false, "two glows")
    }

    @Test fun `a card moves only when no other look will do`() {
        // Level cards have no other look, so the later one, whose place is open (a pin), moves down a card.
        val charge = TodaySlot(TodayCard.Charge, CardSize.Wide, pinned = true)
        val slots = listOf(
            TodaySlot(Hero, CardSize.Large), TodaySlot(Activity, CardSize.Wide), charge, charge, TodaySlot(Recovery, CardSize.Wide),
            TodaySlot(EditToday, CardSize.Strip),
        )
        val out = varyLooks(slots, Moment.Midday)
        assertEquals(listOf(Hero, Activity, TodayCard.Charge, Recovery, TodayCard.Charge, EditToday), out.map { it.card })
        assertVaried(out, false, "two levels")
    }

    @Test fun `a moving card never splits a Small pair`() {
        for (m in Moment.entries) for (wide in listOf(false, true)) for (pins in listOf(listOf(Week), listOf(Week, TodayCard.HabitAge), listOf(LoadToday, Week))) {
            val out = today(m, TodayFacts(workout = true, healthspan = true), TodayEdits(pinned = pins), wide = wide)
            val i = out.indexOfFirst { it.card == LoadToday && it.size == CardSize.Small }
            if (i >= 0) assertEquals("$m $pins", TodayCard.Recover, out[i + 1].card)
        }
    }

    @Test fun `every pin and hide on every moment stays varied`() {
        val pinnable = TodayCard.entries.filter { it.pinnable }
        val hideable = TodayCard.entries.filter { it.editable }
        val pinLists = listOf(emptyList<TodayCard>()) + pinnable.map { listOf(it) } +
            pinnable.flatMap { a -> pinnable.filter { it != a }.map { listOf(a, it) } }
        // Every set of up to three hidden cards with every pin, then random larger ones.
        val hideSets = buildList {
            add(emptySet<TodayCard>())
            hideable.forEachIndexed { i, a ->
                add(setOf(a))
                hideable.drop(i + 1).forEachIndexed { j, b ->
                    add(setOf(a, b))
                    hideable.drop(i + j + 2).forEach { c -> add(setOf(a, b, c)) }
                }
            }
        }
        var checked = 0
        for (m in Moment.entries) for (pins in pinLists) for (hidden in hideSets) {
            val edits = TodayEdits(hidden - pins.toSet(), pins)
            for (wide in listOf(false, true)) {
                assertVaried(today(m, TodayFacts(workout = true), edits, wide = wide), wide, "$m pins=$pins hidden=$hidden wide=$wide")
                checked++
            }
        }
        val rnd = Random(42)
        repeat(20_000) {
            val m = Moment.entries.random(rnd)
            val facts = TodayFacts(rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean())
            val pins = pinLists.random(rnd)
            val hidden = hideable.filter { rnd.nextInt(3) == 0 }.toSet() - pins.toSet()
            val live = rnd.nextBoolean()
            val wide = rnd.nextBoolean()
            val out = today(m, facts, TodayEdits(hidden, pins), live, wide)
            assertVaried(out, wide, "$m $facts pins=$pins hidden=$hidden live=$live wide=$wide")
            // Nothing is lost or added, and Edit Today stays last.
            assertEquals(applyEdits(todayCards(m, facts), TodayEdits(hidden, pins), m).map { it.card }.sorted(), out.map { it.card }.sorted())
            assertEquals(EditToday, out.last().card)
            assertEquals(Hero, out.first().card)
            checked++
        }
        assertTrue(checked > 100_000)
    }
}
