package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.Moment
import java.time.LocalDate
import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * Which cards Today shows for a moment, in what order and at what size (spec 4.2), and how Edit Today changes that.
 * Plain functions, so every moment's layout and the pin and hide rules are tested without a screen.
 */

/**
 * A card on Today. [label] names it in Edit Today. Only [editable] cards can be hidden, only [pinnable] ones pinned
 * under the hero: those that make sense at any time of day.
 */
enum class TodayCard(val label: String, val editable: Boolean = true, val pinnable: Boolean = false) {
    Hero("", editable = false),
    Offer(tr(R.string.today_plan_workout_under_way), editable = false),
    Suggestion(tr(R.string.today_plan_workout_to_save), editable = false),
    Alerts(tr(R.string.common_from_your_strap), editable = false),
    CheckIn(tr(R.string.today_plan_how_do_you_feel)),
    BodyMorning(tr(R.string.common_your_body_this_morning)),
    RightNow(tr(R.string.common_right_now), pinnable = true),
    LastNight(tr(R.string.common_last_night_2), pinnable = true),
    HeartRate(tr(R.string.common_heart_rate), pinnable = true),
    Activity(tr(R.string.common_steps), pinnable = true),
    Recovery(tr(R.string.common_recovery), pinnable = true),
    Charge(tr(R.string.common_energy), pinnable = true),
    LoadToday(tr(R.string.today_plan_load_and_time_to), pinnable = true),
    /** Time to recover as a Small beside Load's after a workout: part of [LoadToday], hidden and pinned with it. */
    Recover("", editable = false),
    Workout(tr(R.string.common_the_workout)),
    Week(tr(R.string.common_your_week), pinnable = true),
    HabitAge(tr(R.string.common_habit_age), pinnable = true),
    /** Start a workout (spec 4.2): a strip above Edit Today, opening Ready with your usual sport. */
    StartWorkout(tr(R.string.common_start_a_workout)),
    EditToday("", editable = false),
}

/**
 * A card in Today's list: its [size] on the grid, whether it's the screen's [secondGlow] (spec 2.4), whether Edit
 * Today [pinned] it, and the [look] [varyLooks] gave it (null until then).
 */
data class TodaySlot(
    val card: TodayCard, val size: CardSize, val secondGlow: Boolean = false, val pinned: Boolean = false, val look: CardLook? = null,
)

/** What Today has to show beyond the moment's own cards; each false leaves its card out. */
data class TodayFacts(
    val isToday: Boolean = true,
    val offer: Boolean = false,
    val suggestion: Boolean = false,
    val alerts: Boolean = false,
    /** A finished workout to show after it (the after-workout moment). */
    val workout: Boolean = false,
    /**
     * A new Habit Age estimate: from the day it updates until it has been seen or the next day ends. Edit Today can pin
     * it to keep it every day, but never before there's a first estimate ([applyEdits]' unavailable).
     */
    val healthspan: Boolean = false,
)

/** Cards that come and go with another: they are hidden with it, and a pin moves them with it. */
private val PARTNER = mapOf(TodayCard.Recover to TodayCard.LoadToday)

/** [this] card, or the card it belongs to. */
private fun TodayCard.owner(): TodayCard = PARTNER[this] ?: this

/**
 * Whether Today offers Habit Age without a pin: a new estimate, for the Sunday [asOf], from that day until the end of
 * the next, unless it has been [seen] (opened). Never before there's an estimate (null [asOf]), and only on [today].
 */
fun habitAgeNew(asOf: LocalDate?, seen: Boolean, date: LocalDate, today: LocalDate): Boolean =
    asOf != null && !seen && date == today && today in asOf..asOf.plusDays(1)

/** The moment Today is laid out for: a past day always shows how it began (spec 4.1). */
fun layoutMoment(moment: Moment?, isToday: Boolean): Moment? = if (!isToday) Moment.Waking else moment

/**
 * Today's cards for [moment], in order (spec 4.2). A null moment (today's recovery still loading) lays out like
 * waking before noon and like midday after, as Today did before moments, so nothing flashes when it arrives.
 * [morning] picks between those two.
 */
fun todayCards(moment: Moment?, facts: TodayFacts, morning: Boolean = true): List<TodaySlot> {
    val m = moment ?: if (morning) Moment.Waking else Moment.Midday
    val wide = { c: TodayCard -> TodaySlot(c, CardSize.Wide) }
    val strip = { c: TodayCard -> TodaySlot(c, CardSize.Strip) }
    val list = { c: TodayCard -> TodaySlot(c, CardSize.List) }
    val small = { c: TodayCard -> TodaySlot(c, CardSize.Small) }
    // Charge is always the screen's second glow: a level glow filling across to its value (spec 2.1).
    val charge = TodaySlot(TodayCard.Charge, CardSize.Wide, secondGlow = true)
    val own = when (m) {
        Moment.Waking -> listOfNotNull(
            strip(TodayCard.CheckIn).takeIf { facts.isToday },
            list(TodayCard.BodyMorning), wide(TodayCard.LastNight), wide(TodayCard.HeartRate), wide(TodayCard.Activity),
        )
        Moment.Midday -> listOf(
            strip(TodayCard.LastNight), wide(TodayCard.Activity), wide(TodayCard.HeartRate), list(TodayCard.RightNow),
            wide(TodayCard.Recovery),
        )
        // During a workout the hero is the workout and its live heart rate, so the rest stays as midday without it.
        Moment.During -> listOf(strip(TodayCard.LastNight), wide(TodayCard.Activity), list(TodayCard.RightNow), wide(TodayCard.Recovery))
        Moment.WorkoutWindow -> listOf(
            charge, list(TodayCard.LoadToday), wide(TodayCard.Activity),
            strip(TodayCard.LastNight),
        )
        // What the workout did as a Small pair, then Charge as the screen's second glow.
        Moment.After -> listOfNotNull(
            wide(TodayCard.Workout).takeIf { facts.workout }, small(TodayCard.LoadToday), small(TodayCard.Recover),
            charge, wide(TodayCard.Activity),
        )
        Moment.Evening, Moment.Night -> listOf(
            charge, wide(TodayCard.Activity), wide(TodayCard.HeartRate), strip(TodayCard.LastNight),
        )
    }
    // Things that need an answer come straight under the hero; the week and Habit Age close the list, then Start a
    // workout, except where the hero is the workout or has Start of its own (spec 4.2).
    return listOfNotNull(
        TodaySlot(TodayCard.Hero, CardSize.Large),
        strip(TodayCard.Offer).takeIf { facts.offer && facts.isToday },
        strip(TodayCard.Suggestion).takeIf { facts.suggestion && facts.isToday },
        strip(TodayCard.Alerts).takeIf { facts.alerts },
    ) + own + listOfNotNull(
        wide(TodayCard.Week),
        wide(TodayCard.HabitAge).takeIf { facts.healthspan },
        strip(TodayCard.StartWorkout).takeIf { facts.isToday && m != Moment.During && m != Moment.WorkoutWindow },
        strip(TodayCard.EditToday),
    )
}

/** The card the hero of [moment] already is, so pinning it there would show it twice. */
fun heroSubject(moment: Moment?): TodayCard? = when (moment) {
    Moment.Waking -> TodayCard.Recovery
    Moment.Midday -> TodayCard.Charge
    else -> null
}

/** The size a pinned card takes in a moment that doesn't have it. */
private fun pinnedSize(card: TodayCard): CardSize = when (card) {
    TodayCard.RightNow, TodayCard.LoadToday -> CardSize.List
    else -> CardSize.Wide
}

/** What Edit Today changed: cards [hidden], and up to [MAX_PINS] [pinned] under the hero, first pinned first. */
data class TodayEdits(val hidden: Set<TodayCard> = emptySet(), val pinned: List<TodayCard> = emptyList()) {
    /** Pins [card], or unpins it. A third pin does nothing, and a pinned card is never hidden. */
    fun togglePin(card: TodayCard): TodayEdits = when {
        card in pinned -> copy(pinned = pinned - card)
        !card.pinnable || pinned.size >= MAX_PINS -> this
        else -> TodayEdits(hidden - card, pinned + card)
    }

    /** Hides [card], or shows it again. Hiding a pinned card unpins it. */
    fun toggleHidden(card: TodayCard): TodayEdits = when {
        card in hidden -> copy(hidden = hidden - card)
        !card.editable -> this
        else -> TodayEdits(hidden + card, pinned - card)
    }

    fun canPin(card: TodayCard) = card.pinnable && (card in pinned || pinned.size < MAX_PINS)

    companion object {
        const val MAX_PINS = 2

        /** From the names the settings store keeps; unknown names (a card since renamed) drop out. */
        fun parse(hidden: List<String>?, pinned: List<String>?): TodayEdits {
            fun cards(names: List<String>?) = names.orEmpty().mapNotNull { n -> TodayCard.entries.firstOrNull { it.name == n } }
            val pins = cards(pinned).filter { it.pinnable }.distinct().take(MAX_PINS)
            return TodayEdits(cards(hidden).filter { it.editable && it !in pins }.toSet(), pins)
        }
    }
}

/**
 * [slots] with the edits applied: hidden cards gone, pinned ones moved under the hero (after anything that needs an
 * answer), in pin order. A pinned card the moment doesn't have is added, unless it's [unavailable] (Habit Age before
 * its first estimate); one the hero already shows is left where it is. A card's partner ([TodayCard.Recover] with
 * Load) goes where it goes.
 */
fun applyEdits(slots: List<TodaySlot>, edits: TodayEdits, moment: Moment?, unavailable: Set<TodayCard> = emptySet()): List<TodaySlot> {
    val pins = edits.pinned.filter { it != heroSubject(moment) && it !in unavailable }
    val pinned = pins.flatMap { card ->
        slots.filter { it.card.owner() == card }
            .ifEmpty { listOf(TodaySlot(card, pinnedSize(card), secondGlow = card == TodayCard.Charge)) }.map { it.copy(pinned = true) }
    }
    val rest = slots.filter { it.card.owner() !in edits.hidden && it.card.owner() !in pins }
    val urgent = setOf(TodayCard.Hero, TodayCard.Offer, TodayCard.Suggestion, TodayCard.Alerts)
    val at = rest.indexOfFirst { it.card !in urgent }.let { if (it < 0) rest.size else it }
    return rest.take(at) + pinned + rest.drop(at)
}

/**
 * The sport you usually do: the most frequent in [workouts] (sport, start), ties to the latest. Null without any.
 * Callers pass workouts you started, so a walk the strap noticed doesn't count.
 */
fun usualSport(workouts: List<Pair<Int, Long>>): Int? =
    workouts.groupBy { it.first }
        .maxWithOrNull(compareBy<Map.Entry<Int, List<Pair<Int, Long>>>> { it.value.size }.thenBy { e -> e.value.maxOf { it.second } })
        ?.key
