package org.openstrap.app.ui.dashboard

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Moment
import org.openstrap.app.ui.dashboard.TodayCard.Activity
import org.openstrap.app.ui.dashboard.TodayCard.Alerts
import org.openstrap.app.ui.dashboard.TodayCard.EditToday
import org.openstrap.app.ui.dashboard.TodayCard.Hero
import org.openstrap.app.ui.dashboard.TodayCard.LastNight
import org.openstrap.app.ui.dashboard.TodayCard.LoadToday
import org.openstrap.app.ui.dashboard.TodayCard.Recovery
import org.openstrap.app.ui.dashboard.TodayCard.Week
import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.GridRow
import org.openstrap.app.ui.glow.Pane
import org.openstrap.app.ui.glow.packGrid

/**
 * No two Today cards next to each other look the same (spec 2.5), whatever the moment, the pins and the hides, on a
 * phone and on two columns, and Today's rows keep the grid's rules (2.4): no lone Small, no pair right after a pair.
 */
class TodayLooksTest {
    private val allFacts = TodayFacts(suggestion = true, alerts = true, workout = true, healthspan = true)

    /** The three ways Today is laid out: a phone, two columns, and two columns with the Small pairs across both. */
    private val layouts = listOf(false to false, true to false, true to true)

    /** Fails, naming the pair, when any neighbours in [slots] look the same or draw the same picture. */
    private fun assertVaried(slots: List<TodaySlot>, wide: Boolean, what: String, across: Boolean = false) {
        val clash = lookClashes(slots, wide, smallsAcross = across).firstOrNull()
        if (clash != null) throw AssertionError("$what: ${slots[clash.first].card} and ${slots[clash.second].card} look the same, ${slots[clash.first].look}")
        // Spelled out apart from sameDesign: no picture kind on two neighbours.
        neighbours(slots, wide, across).forEach { (a, b) ->
            val p = slots[a].look?.picture
            if (p != null && p != PictureKind.None && p == slots[b].look?.picture) {
                throw AssertionError("$what: ${slots[a].card} and ${slots[b].card} both draw $p")
            }
        }
    }

    /** Fails when [slots] leave a Small alone or put a pair right after a pair. */
    private fun assertRows(slots: List<TodaySlot>, what: String) {
        val rows = packGrid(slots.map { it.size to it })
        rows.zipWithNext { a, b -> if (a is GridRow.Pair && b is GridRow.Pair) throw AssertionError("$what: ${b.left.card} pair right after ${a.left.card} pair") }
        rows.forEach { r -> if (r is GridRow.Full && r.item.size == CardSize.Small) throw AssertionError("$what: ${r.item.card} is a lone Small") }
    }

    /** Today as the screen builds it. */
    private fun today(
        m: Moment, facts: TodayFacts, edits: TodayEdits = TodayEdits(), live: Boolean = false, wide: Boolean = false, across: Boolean = false,
    ) = varyLooks(applyEdits(todayCards(m, facts), edits, m), m, live, wide, across)

    private fun look(tier: Tier, hue: Hue, picture: PictureKind?, shape: Shape = Shape.Card) = CardLook(tier, hue, shape, picture)

    @Test fun `the check catches two neighbours that look the same`() {
        val dots = look(Tier.Picture, Hue.Recovery, PictureKind.WeekDots)
        val slots = listOf(TodaySlot(Recovery, CardSize.Wide, look = dots), TodaySlot(Week, CardSize.Wide, look = dots))
        assertEquals(listOf(0 to 1), lookClashes(slots, wide = false))
        // Side by side on two columns counts, as does one over the other in a column.
        val four = List(4) { TodaySlot(Activity, CardSize.Wide, look = look(Tier.Picture, Hue.Steps, if (it % 2 == 1) PictureKind.Line else PictureKind.HourlyBars)) }
        assertTrue(lookClashes(four, wide = false).isEmpty())
        assertEquals(listOf(0 to 2, 1 to 3), lookClashes(four, wide = true))
        // Colour alone doesn't tell two cards apart.
        val bars = listOf(
            TodaySlot(TodayCard.Charge, CardSize.Wide, look = look(Tier.Picture, Hue.Charge, PictureKind.HourlyBars)),
            TodaySlot(Activity, CardSize.Wide, look = look(Tier.Picture, Hue.Steps, PictureKind.HourlyBars)),
        )
        assertEquals(listOf(0 to 1), lookClashes(bars, wide = false))
        // Nor does the tier, for one kind of picture: a glow's curve over a card's curve.
        assertTrue(look(Tier.Glow, Hue.Charge, PictureKind.Curve).sameDesign(look(Tier.Picture, Hue.Charge, PictureKind.Curve)))
        // Two strips with no picture look alike, and a strip and a card don't.
        assertTrue(look(Tier.Strip, Hue.Sleep, PictureKind.None, Shape.Strip).sameDesign(look(Tier.Strip, Hue.Neutral, PictureKind.None, Shape.Strip)))
        assertFalse(look(Tier.Strip, Hue.Sleep, PictureKind.None, Shape.Strip).sameDesign(look(Tier.Picture, Hue.Sleep, PictureKind.StageBar)))
    }

    @Test fun `each card draws its own picture, a Small as a Wide`() {
        val expected = mapOf(
            TodayCard.HeartRate to PictureKind.Line, TodayCard.Stress to PictureKind.DayStrip, Activity to PictureKind.HourlyBars,
            Recovery to PictureKind.WeekDots, LoadToday to PictureKind.LevelFill, TodayCard.Recover to PictureKind.CountdownRing,
            TodayCard.Workout to PictureKind.ZoneBar, Week to PictureKind.WeekRings, TodayCard.HabitAge to PictureKind.AgeScale,
            TodayCard.Charge to PictureKind.Curve,
        )
        val seen = mutableSetOf<TodayCard>()
        for (m in Moment.entries) for (s in today(m, allFacts)) {
            expected[s.card]?.let { assertEquals("$m ${s.card}", it, s.look?.picture); seen += s.card }
            if (s.size == CardSize.Small) assertEquals("$m ${s.card}", Shape.Small, s.look?.shape)
        }
        assertEquals(expected.keys, seen)
        // Last night is its stage bar as a card, words alone as a strip.
        assertEquals(look(Tier.Picture, Hue.Sleep, PictureKind.StageBar), today(Moment.Waking, TodayFacts()).first { it.card == LastNight }.look)
        assertEquals(look(Tier.Picture, Hue.Sleep, PictureKind.StageBar, Shape.Small), today(Moment.Midday, TodayFacts()).first { it.card == LastNight }.look)
        assertEquals(look(Tier.Strip, Hue.Sleep, PictureKind.None, Shape.Strip), today(Moment.Evening, TodayFacts()).first { it.card == LastNight }.look)
        // No picture kind is drawn twice on one Today.
        for (m in Moment.entries) {
            val pics = today(m, allFacts).mapNotNull { it.look?.picture }.filter { it != PictureKind.None }
            assertEquals("$m", pics.distinct(), pics)
        }
    }

    @Test fun `Energy as the second glow is a level glow with its curve, unlike the hero's glow`() {
        val window = today(Moment.WorkoutWindow, TodayFacts())
        assertEquals(TodayCard.Charge, window[1].card)
        assertEquals(look(Tier.Glow, Hue.Charge, PictureKind.Curve), window[1].look)
        assertEquals(true, window[1].secondGlow)
        assertFalse(window[0].look!!.sameDesign(window[1].look))
        // After a workout and in the evening it's the same level glow, and the only glow besides the hero.
        for (m in listOf(Moment.After, Moment.Evening, Moment.Night)) {
            val out = today(m, TodayFacts(workout = true))
            assertEquals("$m", look(Tier.Glow, Hue.Charge, PictureKind.Curve), out.first { it.card == TodayCard.Charge }.look)
            assertEquals("$m", 2, out.count { it.look?.tier == Tier.Glow })
        }
        // After a run the hero is Recovery teal: red is for effort.
        assertEquals(Hue.Recovery, today(Moment.After, TodayFacts(workout = true)).first().look?.hue)
    }

    @Test fun `no moment has more than one glow besides the hero, whatever is pinned`() {
        val pinnable = TodayCard.entries.filter { it.pinnable }
        for (m in Moment.entries) for (a in pinnable) for (b in pinnable.filter { it != a } + listOf(null)) {
            val out = today(m, allFacts, TodayEdits(pinned = listOfNotNull(a, b)))
            assertTrue("$m $a $b", out.count { it.card != Hero && it.look?.tier == Tier.Glow } <= 1)
        }
    }

    @Test fun `a Small is its own design, but two Smalls side by side are neighbours`() {
        val wideBars = look(Tier.Picture, Hue.Steps, PictureKind.HourlyBars)
        assertFalse(look(Tier.Glow, Hue.Steps, null).sameDesign(look(Tier.Glow, Hue.Steps, null, Shape.Small)))
        assertFalse(look(Tier.Plain, Hue.Steps, null, Shape.Tall).sameDesign(look(Tier.Plain, Hue.Steps, null, Shape.Small)))
        // One picture at two sizes is still one picture.
        assertTrue(wideBars.sameDesign(wideBars.copy(shape = Shape.Small)))
        val pair = listOf(
            TodaySlot(Hero, CardSize.Large), TodaySlot(LoadToday, CardSize.Small), TodaySlot(TodayCard.Recover, CardSize.Small),
            TodaySlot(Activity, CardSize.Wide),
        )
        // The hero touches both Smalls under it, and so does the card under them.
        assertTrue(setOf(0 to 1, 0 to 2, 1 to 2, 1 to 3, 2 to 3).all { it in neighbours(pair, wide = false) })
    }

    @Test fun `on two columns a full-width card touches the top card of each column under it`() {
        // Hero, then two cards side by side: the right one isn't next to the hero in reading order, but it's right
        // under it. Today counts it as tabClashes does, so a glow over a glow is caught.
        val glow = look(Tier.Glow, Hue.Charge, null)
        val slots = listOf(
            TodaySlot(Hero, CardSize.Large, look = glow), TodaySlot(Activity, CardSize.Wide, look = look(Tier.Picture, Hue.Steps, PictureKind.HourlyBars)),
            TodaySlot(TodayCard.Charge, CardSize.Wide, look = glow),
        )
        assertEquals(listOf(Pane.Full, Pane.Left, Pane.Right), todayPanes(slots))
        assertTrue(lookClashes(slots, wide = false).isEmpty())
        assertEquals(listOf(0 to 2), lookClashes(slots, wide = true))
        // Energy's own second glow draws its curve, so it doesn't clash with the hero's.
        val out = varyLooks(listOf(slots[0], slots[1], slots[2].copy(secondGlow = true)).map { it.copy(look = null) }, Moment.WorkoutWindow, wide = true)
        assertVaried(out, true, "full over columns")
        assertEquals(look(Tier.Glow, Hue.Charge, PictureKind.Curve), out[2].look)
    }

    @Test fun `every moment's own cards are varied and keep their places, in every layout`() {
        for (m in Moment.entries) for (facts in listOf(TodayFacts(), allFacts, TodayFacts(isToday = false, alerts = true), TodayFacts(workout = true))) {
            for (live in listOf(false, true)) for ((wide, across) in layouts) {
                val out = today(m, facts, live = live, wide = wide, across = across)
                val what = "$m $facts live=$live wide=$wide across=$across"
                assertVaried(out, wide, what, across)
                assertRows(out, what)
                // The defaults need no help: same cards, same order, same sizes.
                assertEquals(what, todayCards(m, facts).map { it.card to it.size }, out.map { it.card to it.size })
            }
        }
    }

    @Test fun `Last night as a strip next to another strip becomes its card`() {
        // In the evening without Your week, Last night's strip would sit on Start a workout's.
        val evening = today(Moment.Evening, TodayFacts(), TodayEdits(hidden = setOf(Week)))
        assertEquals(listOf(Hero, TodayCard.Charge, Activity, TodayCard.HeartRate, LastNight, TodayCard.StartWorkout, EditToday), evening.map { it.card })
        assertEquals(CardSize.Wide, evening.first { it.card == LastNight }.size)
        assertEquals(look(Tier.Picture, Hue.Sleep, PictureKind.StageBar), evening.first { it.card == LastNight }.look)
        assertVaried(evening, false, "evening without the week")
        // Pinned under an alert from the strap, the same.
        val pinned = today(Moment.Evening, TodayFacts(alerts = true), TodayEdits(pinned = listOf(LastNight)))
        assertEquals(listOf(Hero, Alerts, LastNight), pinned.map { it.card }.take(3))
        assertEquals(CardSize.Wide, pinned[2].size)
        // The strips that need an answer stack under the hero as they are.
        val stack = today(Moment.Waking, allFacts)
        assertEquals(List(3) { CardSize.Strip }, stack.drop(1).take(3).map { it.size })
        assertVaried(stack, false, "answer strips")
    }

    @Test fun `a second glow next to its twin steps down to its picture card, then moves`() {
        val charge = TodaySlot(TodayCard.Charge, CardSize.Wide, secondGlow = true, pinned = true)
        val slots = listOf(
            TodaySlot(Hero, CardSize.Large), TodaySlot(Activity, CardSize.Wide), charge, charge, TodaySlot(Recovery, CardSize.Wide),
            TodaySlot(EditToday, CardSize.Strip),
        )
        val out = varyLooks(slots, Moment.Midday)
        // Its picture card still draws the curve, so the later one moves past Recovery.
        assertEquals(listOf(Hero, Activity, TodayCard.Charge, Recovery, TodayCard.Charge, EditToday), out.map { it.card })
        assertVaried(out, false, "two glows")
    }

    @Test fun `a card moves only when no other look will do`() {
        // A picture card has no other look, so the later one, whose place is open (a pin), moves down a card.
        val charge = TodaySlot(TodayCard.Charge, CardSize.Wide, pinned = true)
        val slots = listOf(
            TodaySlot(Hero, CardSize.Large), TodaySlot(Activity, CardSize.Wide), charge, charge, TodaySlot(Recovery, CardSize.Wide),
            TodaySlot(EditToday, CardSize.Strip),
        )
        val out = varyLooks(slots, Moment.Midday)
        assertEquals(listOf(Hero, Activity, TodayCard.Charge, Recovery, TodayCard.Charge, EditToday), out.map { it.card })
        assertVaried(out, false, "two curves")
    }

    @Test fun `a moving card never splits a Small pair`() {
        for (m in Moment.entries) for ((wide, across) in layouts) for (pins in listOf(listOf(Week), listOf(Week, TodayCard.HabitAge), listOf(LoadToday, Week))) {
            val out = today(m, TodayFacts(workout = true, healthspan = true), TodayEdits(pinned = pins), wide = wide, across = across)
            val i = out.indexOfFirst { it.card == LoadToday && it.size == CardSize.Small }
            if (i >= 0) assertEquals("$m $pins", TodayCard.Recover, out[i + 1].card)
            assertRows(out, "$m $pins wide=$wide")
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
                val out = today(m, TodayFacts(workout = true), edits, wide = wide)
                val what = "$m pins=$pins hidden=$hidden wide=$wide"
                assertVaried(out, wide, what)
                assertRows(out, what)
                checked++
            }
        }
        val rnd = Random(42)
        repeat(20_000) {
            val m = Moment.entries.random(rnd)
            val facts = TodayFacts(rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean())
            val pins = pinLists.random(rnd)
            val hidden = hideable.filter { rnd.nextInt(3) == 0 }.toSet() - pins.toSet()
            val live = rnd.nextBoolean()
            val (wide, across) = layouts.random(rnd)
            val out = today(m, facts, TodayEdits(hidden, pins), live, wide, across)
            val what = "$m $facts pins=$pins hidden=$hidden live=$live wide=$wide across=$across"
            assertVaried(out, wide, what, across)
            assertRows(out, what)
            // Nothing is lost or added, and Edit Today stays last.
            assertEquals(applyEdits(todayCards(m, facts), TodayEdits(hidden, pins), m).map { it.card }.sorted(), out.map { it.card }.sorted())
            assertEquals(EditToday, out.last().card)
            assertEquals(Hero, out.first().card)
            checked++
        }
        assertTrue(checked > 100_000)
    }
}
