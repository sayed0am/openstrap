package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Moment
import org.openstrap.app.ui.dashboard.TodayCard.Activity
import org.openstrap.app.ui.dashboard.TodayCard.Alerts
import org.openstrap.app.ui.dashboard.TodayCard.BodyMorning
import org.openstrap.app.ui.dashboard.TodayCard.Charge
import org.openstrap.app.ui.dashboard.TodayCard.CheckIn
import org.openstrap.app.ui.dashboard.TodayCard.EditToday
import org.openstrap.app.ui.dashboard.TodayCard.StartWorkout
import org.openstrap.app.ui.dashboard.TodayCard.HabitAge
import org.openstrap.app.ui.dashboard.TodayCard.HeartRate
import org.openstrap.app.ui.dashboard.TodayCard.Hero
import org.openstrap.app.ui.dashboard.TodayCard.LastNight
import org.openstrap.app.ui.dashboard.TodayCard.LoadToday
import org.openstrap.app.ui.dashboard.TodayCard.Offer
import org.openstrap.app.ui.dashboard.TodayCard.Recover
import org.openstrap.app.ui.dashboard.TodayCard.Recovery
import org.openstrap.app.ui.dashboard.TodayCard.RightNow
import org.openstrap.app.ui.dashboard.TodayCard.Stress
import org.openstrap.app.ui.dashboard.TodayCard.Suggestion
import org.openstrap.app.ui.dashboard.TodayCard.Week
import org.openstrap.app.ui.dashboard.TodayCard.Workout
import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.GridRow
import org.openstrap.app.ui.glow.packGrid
import org.openstrap.app.presentation.usualSport

/** Which cards Today shows per moment (spec 4.2) and the Edit Today rules. */
class TodayPlanTest {
    private fun cards(m: Moment?, facts: TodayFacts = TodayFacts(), morning: Boolean = true) = todayCards(m, facts, morning).map { it.card }

    /** [slots] as grid rows, a Pair as "A+B" and a full-width card as "Card Size". */
    private fun rows(slots: List<TodaySlot>) = packGrid(slots.map { it.size to it }).map { r ->
        when (r) {
            is GridRow.Pair -> "${r.left.card}+${r.right.card}"
            is GridRow.Full -> "${r.item.card} ${r.item.size}"
            is GridRow.TallPair -> "${r.tall.card}|${r.top.card}+${r.bottom.card}"
        }
    }

    private fun rows(m: Moment, facts: TodayFacts = TodayFacts()) = rows(todayCards(m, facts))

    @Test fun `each moment has the spec's cards in order`() {
        assertEquals(listOf(Hero, CheckIn, BodyMorning, LastNight, HeartRate, Activity, Week, StartWorkout, EditToday), cards(Moment.Waking))
        // The Right now list is gone: Stress has its card beside Heart rate.
        assertEquals(listOf(Hero, Recovery, LastNight, Activity, HeartRate, Stress, Week, StartWorkout, EditToday), cards(Moment.Midday))
        // The workout window's hero has Start, so no strip.
        assertEquals(listOf(Hero, Charge, LoadToday, Recover, Activity, LastNight, Week, EditToday), cards(Moment.WorkoutWindow))
        assertEquals(
            listOf(Hero, Workout, LoadToday, Recover, Charge, Activity, LastNight, Week, StartWorkout, EditToday),
            cards(Moment.After, TodayFacts(workout = true)),
        )
        assertEquals(listOf(Hero, Charge, Activity, HeartRate, LastNight, Week, StartWorkout, EditToday), cards(Moment.Evening))
        // At night as in the evening, but no Start a workout this late.
        assertEquals(cards(Moment.Evening) - StartWorkout, cards(Moment.Night))
        // During a workout the hero has the live heart rate and one is already under way.
        assertEquals(listOf(Hero, Activity, Recovery, LastNight, Week, EditToday), cards(Moment.During))
        // Heart rate shows at waking, midday, the evening and night, never around a workout. Stress only at midday.
        for (m in listOf(Moment.WorkoutWindow, Moment.During, Moment.After)) assertFalse("$m", HeartRate in cards(m, TodayFacts(workout = true)))
        for (m in Moment.entries) {
            assertEquals("$m", m == Moment.Midday, Stress in cards(m, TodayFacts(workout = true)))
            assertFalse("$m", RightNow in cards(m, TodayFacts(workout = true)))
        }
    }

    @Test fun `each moment is a grid one size down, as the spec lays it out`() {
        assertEquals(
            listOf("Hero Large", "CheckIn Strip", "BodyMorning List", "LastNight Wide", "HeartRate+Activity", "Week Wide", "StartWorkout Strip", "EditToday Strip"),
            rows(Moment.Waking),
        )
        assertEquals(
            listOf("Hero Large", "Recovery+LastNight", "Activity Wide", "HeartRate+Stress", "Week Wide", "StartWorkout Strip", "EditToday Strip"),
            rows(Moment.Midday),
        )
        assertEquals(
            listOf("Hero Large", "Charge Wide", "LoadToday+Recover", "Activity Wide", "LastNight Strip", "Week Wide", "EditToday Strip"),
            rows(Moment.WorkoutWindow),
        )
        assertEquals(listOf("Hero Large", "Activity Wide", "Recovery+LastNight", "Week Wide", "EditToday Strip"), rows(Moment.During))
        assertEquals(
            listOf(
                "Hero Large", "Workout Wide", "LoadToday+Recover", "Charge Wide", "Activity+LastNight", "Week Wide", "StartWorkout Strip",
                "EditToday Strip",
            ),
            rows(Moment.After, TodayFacts(workout = true)),
        )
        assertEquals(
            listOf("Hero Large", "Charge Wide", "Activity+HeartRate", "LastNight Strip", "Week Wide", "StartWorkout Strip", "EditToday Strip"),
            rows(Moment.Evening),
        )
        // Habit Age, when new, after Your week.
        assertEquals(listOf("Week Wide", "HabitAge Wide", "StartWorkout Strip", "EditToday Strip"), rows(Moment.Midday, TodayFacts(healthspan = true)).takeLast(4))
    }

    @Test fun `sizes and tiers follow the spec`() {
        // Energy is the second glow wherever it shows: before a workout, after it and in the evening.
        for (m in listOf(Moment.WorkoutWindow, Moment.After, Moment.Evening, Moment.Night)) {
            assertTrue("$m", todayCards(m, TodayFacts()).first { it.card == Charge }.secondGlow)
        }
        // Pinned where the moment doesn't have it, it's the second glow too.
        assertTrue(applyEdits(todayCards(Moment.Waking, TodayFacts()), TodayEdits(pinned = listOf(Charge)), Moment.Waking).first { it.card == Charge }.secondGlow)
        // At most one second glow on any moment.
        Moment.entries.forEach { m -> assertTrue(todayCards(m, TodayFacts()).count { it.secondGlow } <= 1) }
    }

    @Test fun `a lone Small becomes a Wide, and a pair never follows a pair`() {
        assertEquals(
            listOf(TodaySlot(Recovery, CardSize.Wide), TodaySlot(Week, CardSize.Wide)),
            fitRows(listOf(TodaySlot(Recovery, CardSize.Small), TodaySlot(Week, CardSize.Wide))),
        )
        val two = listOf(Recovery, LastNight, HeartRate, Stress).map { TodaySlot(it, CardSize.Small) }
        assertEquals(listOf("Recovery+LastNight", "HeartRate Wide", "Stress Wide"), rows(fitRows(two)))
        // Without Steps between them at midday, the later pair splits into Wides.
        assertEquals(
            listOf("Hero Large", "Recovery+LastNight", "HeartRate Wide", "Stress Wide"),
            rows(applyEdits(todayCards(Moment.Midday, TodayFacts()), TodayEdits(hidden = setOf(Activity)), Moment.Midday)).take(4),
        )
        // With Stress hidden, Heart rate is left alone and becomes a Wide.
        assertEquals(
            listOf("Hero Large", "Recovery+LastNight", "Activity Wide", "HeartRate Wide", "Week Wide"),
            rows(applyEdits(todayCards(Moment.Midday, TodayFacts()), TodayEdits(hidden = setOf(Stress)), Moment.Midday)).take(5),
        )
    }

    @Test fun `strips that need an answer sit under the hero, today only`() {
        val facts = TodayFacts(offer = true, suggestion = true, alerts = true, healthspan = true)
        assertEquals(listOf(Hero, Offer, Suggestion, Alerts, Recovery), cards(Moment.Midday, facts).take(5))
        assertEquals(listOf(Week, HabitAge, StartWorkout, EditToday), cards(Moment.Midday, facts).takeLast(4))
        // A past day: no offer, suggestion or check-in, but its alerts still show.
        val past = cards(layoutMoment(Moment.Evening, isToday = false), facts.copy(isToday = false))
        assertEquals(listOf(Hero, Alerts, BodyMorning), past.take(3))
        assertFalse(StartWorkout in past)
        assertFalse(Workout in cards(Moment.After))
    }

    @Test fun `past days use the waking layout`() {
        assertEquals(Moment.Waking, layoutMoment(Moment.Night, isToday = false))
        assertEquals(Moment.Waking, layoutMoment(null, isToday = false))
        assertEquals(Moment.Night, layoutMoment(Moment.Night, isToday = true))
    }

    @Test fun `no moment yet keeps the old morning and afternoon layouts`() {
        assertEquals(cards(Moment.Waking), cards(null, morning = true))
        assertEquals(cards(Moment.Midday), cards(null, morning = false))
    }

    @Test fun `pins are at most two, first pinned first, and never hidden`() {
        var e = TodayEdits().toggleHidden(HeartRate)
        assertEquals(setOf(HeartRate), e.hidden)
        e = e.togglePin(HeartRate).togglePin(Week).togglePin(Activity)
        assertEquals(listOf(HeartRate, Week), e.pinned)
        assertTrue(e.hidden.isEmpty())
        assertFalse(e.canPin(Activity))
        assertTrue(e.canPin(Week))
        e = e.togglePin(HeartRate)
        assertEquals(listOf(Week), e.pinned)
        // Hiding a pinned card unpins it.
        e = e.toggleHidden(Week)
        assertTrue(e.pinned.isEmpty())
        assertEquals(setOf(Week), e.hidden)
    }

    @Test fun `only some cards can be pinned or hidden`() {
        assertEquals(TodayEdits(), TodayEdits().togglePin(CheckIn))
        assertEquals(TodayEdits(), TodayEdits().togglePin(Workout))
        assertEquals(TodayEdits(), TodayEdits().toggleHidden(Hero))
        assertEquals(TodayEdits(), TodayEdits().toggleHidden(Alerts))
        assertEquals(TodayEdits(), TodayEdits().toggleHidden(EditToday))
    }

    @Test fun `edits apply under the hero and after the strips`() {
        val slots = todayCards(Moment.Midday, TodayFacts(alerts = true))
        val edits = TodayEdits(hidden = setOf(Stress), pinned = listOf(HeartRate, LoadToday))
        val out = applyEdits(slots, edits, Moment.Midday)
        assertEquals(listOf(Hero, Alerts, HeartRate, LoadToday, Recover, Recovery, LastNight, Activity, Week, StartWorkout, EditToday), out.map { it.card })
        // A pin the moment doesn't have comes in at its Today size: Load with To recover as their pair. Heart rate,
        // pinned away from Stress, is a Wide, and the pair under Load's splits, as a pair never follows a pair.
        assertEquals(
            listOf("Hero Large", "Alerts Strip", "HeartRate Wide", "LoadToday+Recover", "Recovery Wide", "LastNight Wide", "Activity Wide"),
            rows(out).take(7),
        )
        // A pin keeps the moment's own size where it has one: Last night is a strip in the evening.
        assertEquals(
            CardSize.Strip,
            applyEdits(todayCards(Moment.Evening, TodayFacts()), TodayEdits(pinned = listOf(LastNight)), Moment.Evening).first { it.card == LastNight }.size,
        )
        // Pins pair only with their own partner: Heart rate and Steps pinned at waking are two Wides.
        assertEquals(
            listOf("Hero Large", "HeartRate Wide", "Activity Wide", "CheckIn Strip"),
            rows(applyEdits(todayCards(Moment.Waking, TodayFacts()), TodayEdits(pinned = listOf(HeartRate, Activity)), Moment.Waking)).take(4),
        )
    }

    @Test fun `a pin the hero already shows stays where it is`() {
        val slots = todayCards(Moment.Midday, TodayFacts())
        assertEquals(slots, applyEdits(slots, TodayEdits(pinned = listOf(Charge)), Moment.Midday))
        val waking = todayCards(Moment.Waking, TodayFacts())
        assertFalse(Recovery in applyEdits(waking, TodayEdits(pinned = listOf(Recovery)), Moment.Waking).map { it.card })
    }

    @Test fun `edits survive the settings store and drop what they no longer know`() {
        val e = TodayEdits.parse(listOf("HeartRate", "Gone", "Hero", "RightNow"), listOf("RightNow", "Week", "CheckIn", "Activity", "Charge"))
        assertEquals(setOf(HeartRate), e.hidden)
        assertEquals(listOf(Week, Activity), e.pinned)
        assertEquals(TodayEdits(), TodayEdits.parse(null, null))
        // A card both hidden and pinned (edited by hand) is pinned.
        assertEquals(TodayEdits(pinned = listOf(Week)), TodayEdits.parse(listOf("Week"), listOf("Week")))
    }

    @Test fun `time to recover goes with Load, hidden or pinned`() {
        val after = todayCards(Moment.After, TodayFacts(workout = true))
        val hidden = applyEdits(after, TodayEdits(hidden = setOf(LoadToday)), Moment.After).map { it.card }
        assertFalse(LoadToday in hidden)
        assertFalse(Recover in hidden)
        val pinned = applyEdits(after, TodayEdits(pinned = listOf(LoadToday)), Moment.After)
        assertEquals(listOf(Hero, LoadToday, Recover, Workout), pinned.map { it.card }.take(4))
        assertTrue(pinned.take(3).drop(1).all { it.pinned && it.size == CardSize.Small })
        // Recover is never offered on its own in Edit Today.
        assertFalse(Recover.editable)
        assertFalse(Recover.pinnable)
    }

    @Test fun `habit age is on Today only while its estimate is new, or pinned once there is one`() {
        val sunday = java.time.LocalDate.of(2026, 10, 18)
        assertTrue(habitAgeNew(sunday, seen = false, date = sunday, today = sunday))
        assertTrue(habitAgeNew(sunday, seen = false, date = sunday.plusDays(1), today = sunday.plusDays(1)))
        assertFalse(habitAgeNew(sunday, seen = false, date = sunday.plusDays(2), today = sunday.plusDays(2)))
        assertFalse(habitAgeNew(sunday, seen = true, date = sunday, today = sunday))
        assertFalse(habitAgeNew(null, seen = false, date = sunday, today = sunday))
        // A past day never offers it.
        assertFalse(habitAgeNew(sunday, seen = false, date = sunday.minusDays(1), today = sunday))
        assertTrue(HabitAge in cards(Moment.Midday, TodayFacts(healthspan = true)))
        assertFalse(HabitAge in cards(Moment.Midday, TodayFacts(healthspan = false)))
        // Pinned, it shows every day, but never before the first estimate.
        val slots = todayCards(Moment.Midday, TodayFacts())
        assertEquals(HabitAge, applyEdits(slots, TodayEdits(pinned = listOf(HabitAge)), Moment.Midday)[1].card)
        assertFalse(HabitAge in applyEdits(slots, TodayEdits(pinned = listOf(HabitAge)), Moment.Midday, unavailable = setOf(HabitAge)).map { it.card })
    }

    @Test fun `usual sport is the most frequent, ties to the latest`() {
        assertEquals(1, usualSport(listOf(1 to 10L, 1 to 20L, 4 to 30L)))
        assertEquals(4, usualSport(listOf(1 to 10L, 4 to 30L)))
        assertEquals(null, usualSport(emptyList()))
    }
}
