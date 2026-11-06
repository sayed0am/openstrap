package org.openstrap.app.ui.dashboard

import androidx.annotation.StringRes
import org.openstrap.app.R
import org.openstrap.app.data.Moment
import org.openstrap.app.i18n.Strings
import java.time.LocalDate
import org.openstrap.app.ui.glow.CardSize

/*
 * Which cards Today shows for a moment, in what order and at what size (spec 4.2), and how Edit Today changes that.
 * Plain functions, so every moment's layout and the pin and hide rules are tested without a screen.
 */

/**
 * A card on Today. [label] names it in Edit Today (empty for the cards it doesn't list). Only [editable] cards can be
 * hidden, only [pinnable] ones pinned under the hero: those that make sense at any time of day.
 */
enum class TodayCard(@StringRes private val labelRes: Int?, val editable: Boolean = true, val pinnable: Boolean = false) {
    Hero(null, editable = false),
    Offer(R.string.day_card_offer, editable = false),
    Suggestion(R.string.day_card_suggestion, editable = false),
    Alerts(R.string.day_card_alerts, editable = false),
    CheckIn(R.string.day_card_check_in),
    BodyMorning(R.string.day_card_body_morning),
    /**
     * The Right now list, gone from Today (spec 4.2: Stress has its card, HRV lives on Recovery). No moment has it and
     * Edit Today no longer offers it, so a pin of it saved before drops out ([TodayEdits.parse]).
     */
    LastNight(R.string.day_card_last_night, pinnable = true),
    HeartRate(R.string.day_card_heart_rate, pinnable = true),
    /** Stress as a day strip, calm to high: a Small beside Heart rate at midday (spec 4.2). */
    Stress(R.string.day_card_stress, pinnable = true),
    Activity(R.string.day_card_activity, pinnable = true),
    Recovery(R.string.day_card_recovery, pinnable = true),
    Charge(R.string.day_card_charge, pinnable = true),
    LoadToday(R.string.day_card_load, pinnable = true),
    /**
     * Time to recover as a Small beside Load's, in the workout window and after a workout: part of [LoadToday], hidden
     * and pinned with it.
     */
    Recover(null, editable = false),
    Workout(R.string.day_card_workout),
    Week(R.string.day_card_week, pinnable = true),
    HabitAge(R.string.day_card_habit_age, pinnable = true),
    /** Start a workout (spec 4.2): a strip above Edit Today, opening Ready with your usual sport. */
    StartWorkout(R.string.day_card_start_workout),
    EditToday(null, editable = false),
    ;

    /** The card's name in Edit Today, in the app's language. */
    val label: String get() = label()

    /** [label] in [strings]. */
    fun label(strings: Strings = Strings.app): String = labelRes?.let(strings::get).orEmpty()
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
 * Today's cards for [moment], in order (spec 4.2): a grid one size down from the tabs, Smalls in pairs and a pair never
 * right after a pair. A null moment (today's recovery still loading) lays out like waking before noon and like midday
 * after, as Today did before moments, so nothing flashes when it arrives. [morning] picks between those two.
 */
fun todayCards(moment: Moment?, facts: TodayFacts, morning: Boolean = true): List<TodaySlot> {
    val m = moment ?: if (morning) Moment.Waking else Moment.Midday
    val wide = { c: TodayCard -> TodaySlot(c, CardSize.Wide) }
    val strip = { c: TodayCard -> TodaySlot(c, CardSize.Strip) }
    val list = { c: TodayCard -> TodaySlot(c, CardSize.List) }
    fun pair(a: TodayCard, b: TodayCard) = listOf(TodaySlot(a, CardSize.Small), TodaySlot(b, CardSize.Small))
    // Energy, where the hero isn't, is always the screen's second glow: a level glow filling across to its value.
    val energy = TodaySlot(TodayCard.Charge, CardSize.Wide, secondGlow = true)
    val own = when (m) {
        // Last night is the Wide the morning is about; heart rate and steps shrink to a pair.
        Moment.Waking -> listOfNotNull(strip(TodayCard.CheckIn).takeIf { facts.isToday }, list(TodayCard.BodyMorning), wide(TodayCard.LastNight)) +
            pair(TodayCard.HeartRate, TodayCard.Activity)
        // Energy is the hero. The Right now list is gone: Stress has its card, HRV lives on Recovery.
        Moment.Midday -> pair(TodayCard.Recovery, TodayCard.LastNight) + wide(TodayCard.Activity) + pair(TodayCard.HeartRate, TodayCard.Stress)
        // Load and To recover as a pair in place of the "Before you start" list.
        Moment.WorkoutWindow -> listOf(energy) + pair(TodayCard.LoadToday, TodayCard.Recover) + wide(TodayCard.Activity) + strip(TodayCard.LastNight)
        // The hero is the workout and its live heart rate, so Heart rate and Stress leave.
        Moment.During -> listOf(wide(TodayCard.Activity)) + pair(TodayCard.Recovery, TodayCard.LastNight)
        Moment.After -> listOfNotNull(wide(TodayCard.Workout).takeIf { facts.workout }) + pair(TodayCard.LoadToday, TodayCard.Recover) +
            energy + pair(TodayCard.Activity, TodayCard.LastNight)
        Moment.Evening, Moment.Night -> listOf(energy) + pair(TodayCard.Activity, TodayCard.HeartRate) + strip(TodayCard.LastNight)
    }
    // Things that need an answer come straight under the hero; the week and Habit Age close the list, then Start a
    // workout, except where the hero is the workout or has Start of its own, and at night (spec 4.2).
    return fitRows(
        listOfNotNull(
            TodaySlot(TodayCard.Hero, CardSize.Large),
            strip(TodayCard.Offer).takeIf { facts.offer && facts.isToday },
            strip(TodayCard.Suggestion).takeIf { facts.suggestion && facts.isToday },
            strip(TodayCard.Alerts).takeIf { facts.alerts },
        ) + own + listOfNotNull(
            wide(TodayCard.Week),
            wide(TodayCard.HabitAge).takeIf { facts.healthspan },
            strip(TodayCard.StartWorkout).takeIf { facts.isToday && m != Moment.During && m != Moment.WorkoutWindow && m != Moment.Night },
            strip(TodayCard.EditToday),
        ),
    )
}

/**
 * [slots] with Today's row rules kept (spec 2.4, 2.5): a lone Small becomes a Wide, and a pair never follows a pair:
 * the later pair's two become Wides. Each Small pairs with the Small right after it. A slot whose size changes loses
 * the look it had.
 */
fun fitRows(slots: List<TodaySlot>): List<TodaySlot> {
    val out = slots.toMutableList()
    var afterPair = false
    var i = 0
    while (i < out.size) {
        if (out[i].size == CardSize.Small) {
            if (!afterPair && out.getOrNull(i + 1)?.size == CardSize.Small) {
                afterPair = true
                i += 2
                continue
            }
            out[i] = out[i].copy(size = CardSize.Wide, look = null)
        }
        afterPair = false
        i++
    }
    return out
}

/** The card the hero of [moment] already is, so pinning it there would show it twice. */
fun heroSubject(moment: Moment?): TodayCard? = when (moment) {
    Moment.Waking -> TodayCard.Recovery
    Moment.Midday -> TodayCard.Charge
    else -> null
}

/**
 * The slots a pinned card brings in a moment that doesn't have it: Load with To recover as their Small pair, Energy as
 * the second glow, any other as a Wide.
 */
private fun pinnedSlots(card: TodayCard): List<TodaySlot> = when (card) {
    TodayCard.LoadToday -> listOf(TodaySlot(TodayCard.LoadToday, CardSize.Small), TodaySlot(TodayCard.Recover, CardSize.Small))
    TodayCard.Charge -> listOf(TodaySlot(card, CardSize.Wide, secondGlow = true))
    else -> listOf(TodaySlot(card, CardSize.Wide))
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
 * answer), in pin order, never splitting a pair. A pinned card the moment doesn't have is added, unless it's
 * [unavailable] (Habit Age before its first estimate); one the hero already shows is left where it is. A card's
 * partner ([TodayCard.Recover] with Load) goes where it goes. Then the row rules hold again ([fitRows]): a pinned Small
 * pairs only with its own partner, and one left alone, pinned or not, becomes a Wide.
 */
fun applyEdits(slots: List<TodaySlot>, edits: TodayEdits, moment: Moment?, unavailable: Set<TodayCard> = emptySet()): List<TodaySlot> {
    val pins = edits.pinned.filter { it != heroSubject(moment) && it !in unavailable }
    val pinned = pins.flatMap { card ->
        slots.filter { it.card.owner() == card }
            .ifEmpty { pinnedSlots(card) }.map { it.copy(pinned = true) }.let(::fitRows)
    }
    val rest = slots.filter { it.card.owner() !in edits.hidden && it.card.owner() !in pins }
    val urgent = setOf(TodayCard.Hero, TodayCard.Offer, TodayCard.Suggestion, TodayCard.Alerts)
    val at = rest.indexOfFirst { it.card !in urgent }.let { if (it < 0) rest.size else it }
    return fitRows(rest.take(at) + pinned + fitRows(rest.drop(at)))
}
