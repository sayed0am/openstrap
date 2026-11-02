package org.openstrap.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.presentation.Metric
import org.openstrap.app.ui.dashboard.Score

class CardMotionTest {
    private val hero = 11L
    private val note = 22L
    private val recovery = Detail.keyOf(Detail.OfScore(Score.Recovery))
    private val charge = Detail.keyOf(Detail.OfScore(Score.Charge))

    /** The hero on the tab's root pressed, then clicked. */
    private fun clicked(source: Long = hero, number: String? = "72") =
        CardMotion.click(CardMotion.press(emptyMap(), CardTap(source, 0, number)), 0, source)

    @Test
    fun keysAreTheSavedRouteKeys() {
        assertEquals("m:Hrv", Detail.keyOf(Detail.OfMetric(Metric.Hrv)))
        assertEquals("n:Hrv", Detail.keyOf(Detail.OfMetric(Metric.Hrv, night = true)))
        assertEquals("c:Recovery", recovery)
        assertEquals("w:42", Detail.keyOf(Detail.Workout(42)))
        assertEquals("r:", Detail.keyOf(Detail.Recovery))
    }

    @Test
    fun templateDetailsGrowIntoTheirHeroTheRestIntoThePage() {
        listOf("m:Hrv", "n:Spo2", "c:Charge", "k:").forEach { assertTrue(it, CardMotion.surfaceInHero(it)) }
        listOf("w:42", "a:", "h:", "r:", "s:").forEach { assertFalse(it, CardMotion.surfaceInHero(it)) }
    }

    @Test
    fun aClickedCardGrowsIntoWhatItOpensAndBackShrinksIntoIt() {
        val taps = clicked()
        assertEquals(StackMotion.Grow, CardMotion.of(emptyList(), listOf(recovery), taps, animate = true))
        // The push binds the tap to the detail it opened; back then shrinks into the card.
        val bound = CardMotion.settle(taps, listOf(recovery), settled = false)
        assertEquals(recovery, bound[0]?.bound)
        assertEquals(StackMotion.Grow, CardMotion.of(emptyList(), listOf(recovery), bound, animate = true))
        assertEquals(StackMotion.Shrink, CardMotion.of(listOf(recovery), emptyList(), bound, animate = true))
    }

    @Test
    fun withoutATapAnOpenCrossfades() {
        // A deep link, or a card pressed but not clicked.
        assertEquals(StackMotion.Crossfade, CardMotion.of(emptyList(), listOf(recovery), emptyMap(), animate = true))
        val pressed = CardMotion.press(emptyMap(), CardTap(hero, 0, "72"))
        assertEquals(StackMotion.Crossfade, CardMotion.of(emptyList(), listOf(recovery), pressed, animate = true))
        assertEquals(StackMotion.CrossfadeBack, CardMotion.of(listOf(recovery), emptyList(), emptyMap(), animate = true))
    }

    @Test
    fun theSwitcherAndRelatedRowsCrossfade() {
        val bound = CardMotion.settle(clicked(), listOf(recovery), settled = true)
        // The switcher replaces the top.
        assertEquals(StackMotion.Crossfade, CardMotion.of(listOf(recovery), listOf(charge), bound, animate = true))
        // A Related row pushes on top, and back from it.
        assertEquals(StackMotion.Crossfade, CardMotion.of(listOf(recovery), listOf(recovery, "m:Hrv"), bound, animate = true))
        assertEquals(StackMotion.CrossfadeBack, CardMotion.of(listOf(recovery, "m:Hrv"), listOf(recovery), bound, animate = true))
        // Back from a sibling the card didn't open: no shrinking into the card.
        assertEquals(StackMotion.CrossfadeBack, CardMotion.of(listOf(charge), emptyList(), bound, animate = true))
    }

    @Test
    fun animationsOffJump() {
        assertEquals(StackMotion.Jump, CardMotion.of(emptyList(), listOf(recovery), clicked(), animate = false))
        assertEquals(StackMotion.Jump, CardMotion.of(listOf(recovery), emptyList(), emptyMap(), animate = false))
        assertEquals(StackMotion.Idle, CardMotion.of(listOf(recovery), listOf(recovery), emptyMap(), animate = true))
    }

    @Test
    fun springsAre500InAnd300Out() {
        assertEquals(500f, CardMotion.stiffness(StackMotion.Grow))
        assertEquals(500f, CardMotion.stiffness(StackMotion.Crossfade))
        assertEquals(300f, CardMotion.stiffness(StackMotion.Shrink))
        assertEquals(300f, CardMotion.stiffness(StackMotion.CrossfadeBack))
    }

    @Test
    fun onlyThePressedCardTakesPart() {
        // The hero and the "All" note both open Recovery; the note was tapped.
        val taps = CardMotion.settle(clicked(source = note, number = null), listOf(recovery), settled = false)
        assertTrue(CardMotion.cardTakesPart(taps[0], note, 0, StackMotion.Grow))
        assertFalse(CardMotion.cardTakesPart(taps[0], hero, 0, StackMotion.Grow))
        // Nobody takes part in a crossfade.
        assertFalse(CardMotion.cardTakesPart(taps[0], note, 0, StackMotion.Crossfade))
        assertTrue(CardMotion.detailTakesPart(taps, listOf(recovery), StackMotion.Grow))
        assertFalse(CardMotion.detailTakesPart(taps, listOf(charge), StackMotion.Grow))
        assertFalse(CardMotion.detailTakesPart(taps, emptyList(), StackMotion.Idle))
    }

    @Test
    fun beforeTheBindingLandsTheClickedTapStillOpensTheNewDetail() {
        val taps = clicked()
        assertTrue(CardMotion.detailTakesPart(taps, listOf(recovery), StackMotion.Grow))
        // Unbound, it never claims a detail at rest or going back.
        assertFalse(CardMotion.detailTakesPart(taps, listOf(recovery), StackMotion.Idle))
    }

    @Test
    fun aNumberMovesOnlyWhenTheTextIsTheSame() {
        assertTrue(CardMotion.sharesNumber(CardTap(hero, 0, "72"), "72"))
        // A week total against a day value, a formatted against a plain number: surface only.
        assertFalse(CardMotion.sharesNumber(CardTap(hero, 0, "8,412"), "8412"))
        assertFalse(CardMotion.sharesNumber(CardTap(hero, 0, "97%"), "97"))
        assertFalse(CardMotion.sharesNumber(CardTap(hero, 0, null), null))
        assertFalse(CardMotion.sharesNumber(null, "72"))
    }

    @Test
    fun aClickThatOpenedNothingExpires() {
        val expired = CardMotion.expire(clicked(), 0, hero)
        assertFalse(expired[0]!!.clicked)
        assertEquals(StackMotion.Crossfade, CardMotion.of(emptyList(), listOf(recovery), expired, animate = true))
        // A bound tap is left alone.
        val bound = CardMotion.settle(clicked(), listOf(recovery), settled = false)
        assertEquals(bound, CardMotion.expire(bound, 0, hero))
    }

    @Test
    fun settledStacksDropTapsThatCanNoLongerTakePart() {
        val bound = CardMotion.settle(clicked(), listOf(recovery), settled = true)
        assertEquals(setOf(0), bound.keys)
        // Still open under a Related detail: kept for the way back.
        assertEquals(bound, CardMotion.settle(bound, listOf(recovery, "m:Hrv"), settled = true))
        // Mid-way back nothing is dropped; once back at the root the tap has done its job.
        assertEquals(bound, CardMotion.settle(bound, emptyList(), settled = false))
        assertTrue(CardMotion.settle(bound, emptyList(), settled = true).isEmpty())
        // A press waiting on the root it was made on stays.
        val pressed = CardMotion.press(emptyMap(), CardTap(hero, 0, "72"))
        assertEquals(pressed, CardMotion.settle(pressed, emptyList(), settled = true))
    }

    @Test
    fun aPressOnAPageReplacesItsTapAndKeepsTheOnesUnderIt() {
        // Today opened the Recovery page from a card; a card on that page pressed.
        val today = CardMotion.settle(clicked(), listOf("r:"), settled = true)
        val both = CardMotion.press(today, CardTap(note, 1, null))
        assertEquals(setOf(0, 1), both.keys)
        // Pressing on the root again drops the page's.
        assertEquals(setOf(0), CardMotion.press(both, CardTap(hero, 0, null)).keys)
    }

    @Test
    fun tapsSurviveProcessDeath() {
        val taps = CardMotion.press(CardMotion.settle(clicked(number = "8,412"), listOf("m:Steps"), settled = true), CardTap(-5L, 1, null))
        assertEquals(taps, CardMotion.restore(CardMotion.save(taps)))
        assertTrue(CardMotion.restore("").isEmpty())
        assertTrue(CardMotion.restore("garbage").isEmpty())
    }
}
