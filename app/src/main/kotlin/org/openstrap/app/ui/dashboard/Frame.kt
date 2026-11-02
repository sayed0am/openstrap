package org.openstrap.app.ui.dashboard

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.data.Moment
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.SyncState
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.StrapBands
import org.openstrap.app.ui.glow.GlowMood
import org.openstrap.app.ui.glow.LightAngle

/*
 * The frame around every tab (spec 3): what the header says about the day and the strap, and how the tabs answer
 * taps and back. Plain functions, so the rules are tested without a screen.
 */

/**
 * The header's title for [date] on Today, by [DayWords]: "Today", "Yesterday", the weekday within the week ("Tuesday"),
 * then the date ("Tue 21 Oct").
 */
fun dayTitle(date: LocalDate, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String =
    DayWords.relative(date, today, locale)

/**
 * The header's title on Today (spec 3.1): the [moment] for today, [dayTitle] for a past day, which keeps its name.
 * During a workout it's the sport ("Running"), after one it names it when it can ("After your ride"): [sportType] is
 * the one recording, or the last one done.
 */
fun todayTitle(moment: Moment, date: LocalDate, sportType: Int? = null, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String =
    if (date < today) dayTitle(date, today, locale) else when (moment) {
        Moment.During -> sportType?.let(::sportDoing) ?: "Workout"
        Moment.After -> "After your ${sportType?.let(::sportNoun) ?: "workout"}"
        Moment.WorkoutWindow -> "Workout time"
        Moment.Waking -> "Waking up"
        Moment.Night -> "Night"
        Moment.Evening -> "Evening"
        Moment.Midday -> "Midday"
    }

/** A sport as a short noun ("run", "ride"), for "After your run"; null where none reads well, so it says "workout". */
internal fun sportNoun(sportType: Int): String? = when (sportType) {
    1, 2, 11 -> "run"
    3, 40, 215 -> "walk"
    4, 8, 143 -> "ride"
    6, 7 -> "swim"
    15, 224 -> "hike"
    23 -> "row"
    else -> null
}

/** A sport as what you're doing ("Running"), for the header during it; its name where no verb reads well. */
internal fun sportDoing(sportType: Int): String = when (sportNoun(sportType)) {
    "run" -> "Running"
    "walk" -> "Walking"
    "ride" -> "Cycling"
    "swim" -> "Swimming"
    "hike" -> "Hiking"
    "row" -> "Rowing"
    else -> org.openstrap.app.data.Workouts.sportName(sportType)
}

/** Today's light for a moment (spec 2.1 Moods): softer in the evening, calmer and from below at night. */
fun momentMood(moment: Moment): GlowMood = when (moment) {
    Moment.Evening -> GlowMood.Evening
    Moment.Night -> GlowMood.Night
    else -> GlowMood.Day
}

/**
 * Where the Today hero's light comes from for a moment (spec 4.2): low on the left on waking, rising from below to
 * Charge's level at midday, up on the right before a workout, low on the right in the evening, and below during and
 * after a workout and at night.
 */
fun heroLightAngle(moment: Moment): Float = when (moment) {
    Moment.Waking -> LightAngle.LowerLeft
    Moment.WorkoutWindow -> LightAngle.UpperRight
    Moment.Evening -> LightAngle.LowerRight
    Moment.Midday, Moment.During, Moment.After, Moment.Night -> LightAngle.Below
}

/** The day under the header's title: "Thu 23 Oct". */
fun dateLine(date: LocalDate, locale: Locale = Locale.getDefault()): String = DayWords.short(date, locale)

/** A neighbouring day's short name, peeking in beside the title: "Wed". */
fun peekDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

/**
 * What the strap chip in the header shows: a [dot] and, only when they matter, a few words ([text] may be empty).
 * [description] is read out by a screen reader.
 */
data class StrapChip(val text: String, val dot: Dot, val description: String) {
    enum class Dot {
        /** Connected and all is well. */
        Fine,

        /** Connected, battery at or under [LOW_BATTERY]. */
        Low,

        /** Connected and charging. */
        Charging,

        /** Something needs a look: not synced, or not seen for a while. Amber. */
        Warn,

        /** Nothing to report yet (connecting), or nothing paired. Dim. */
        Quiet,
    }

    companion object {
        const val LOW_BATTERY = StrapBands.LOW_BATTERY
    }
}

/** Connecting for longer than this after the strap was last seen shows how long ago that was, instead of a quiet dot. */
private const val CONNECTING_GRACE_S = 120L

/** For this long after the app's process starts, the service is still reconnecting: no news yet either way. */
const val STARTUP_GRACE_S = 120L

/**
 * The chip for the strap's state (spec 3.1): the battery and a dot when all is well, words only when they matter.
 * [lastSeen] is when the strap was last connected (epoch seconds), null when this run hasn't seen it. [sinceStart] is
 * how long the app's process has been running (seconds): during its first [STARTUP_GRACE_S] a strap that isn't
 * connected yet is still being reconnected, so it shows a quiet dot like a brief connect.
 *
 * When several apply, the first wins: "Not paired", then not connected ("18 min ago", after the grace), then
 * "Charging 64%", then "Not synced", then the battery. A strap that isn't there can't sync, so how long it's been
 * gone is the news. A charging strap isn't worn and stops syncing in fast-charge mode, so charging explains a failed
 * sync rather than the other way round; the data waits on the strap.
 */
fun strapChip(paired: Boolean, connection: ConnectionState, sync: SyncState, lastSeen: Long?, now: Long, sinceStart: Long = Long.MAX_VALUE): StrapChip {
    if (!paired || connection == ConnectionState.NotPaired) return StrapChip("Not paired", StrapChip.Dot.Quiet, "No strap paired")
    if (connection !is ConnectionState.Connected && sinceStart < STARTUP_GRACE_S) return StrapChip("", StrapChip.Dot.Quiet, "Connecting to the strap")
    val ago = lastSeen?.let { now - it }
    val unseen = if (ago != null) {
        val text = agoText(ago)
        StrapChip(text, StrapChip.Dot.Warn, "Strap last seen $text")
    } else StrapChip("Not connected", StrapChip.Dot.Warn, "Strap not connected")
    return when (connection) {
        is ConnectionState.Connected -> {
            val battery = connection.batteryPercent
            when {
                connection.charging && battery != null -> StrapChip("Charging $battery%", StrapChip.Dot.Charging, "Strap charging, battery $battery percent")
                sync is SyncState.Failed -> StrapChip("Not synced", StrapChip.Dot.Warn, "Strap connected, not synced")
                battery == null -> StrapChip("", StrapChip.Dot.Fine, "Strap connected")
                StrapBands.lowBattery(battery, connection.charging) -> StrapChip("$battery%", StrapChip.Dot.Low, "Strap connected, battery low, $battery percent")
                else -> StrapChip("$battery%", StrapChip.Dot.Fine, "Strap connected, battery $battery percent")
            }
        }
        // A moment's connecting (the app coming to the front) isn't news; a strap gone for minutes is.
        ConnectionState.Connecting, ConnectionState.Authenticating ->
            if (ago != null && ago >= CONNECTING_GRACE_S) unseen else StrapChip("", StrapChip.Dot.Quiet, "Connecting to the strap")
        else -> unseen
    }
}

/** "Just now", "18 min ago", "3 h ago", "2 days ago". */
fun agoText(seconds: Long): String {
    val minutes = seconds.coerceAtLeast(0) / 60
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 48 * 60 -> "1 day ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** What tapping the tab that's already showing does (spec 3.3). */
enum class Reselect { ScrollToTop, PopToRoot }

/**
 * Which tab is showing, and the one before it for back (spec 3.3). [reselected] is set by a tap on the current tab
 * that scrolled it to the top, so the next tap pops its stack; anything else that moves clears it.
 */
data class TabNav(val current: Int = HOME, val previous: Int? = null, val reselected: Boolean = false) {
    /** Another tab: it becomes current, and back returns here. Every tab keeps its own stack. */
    fun select(tab: Int): TabNav = if (tab == current) this else TabNav(tab, current)

    /**
     * The pager came to rest on [page]. The tab is the one owner of what shows and the pager follows it, so only a
     * finished [swiped] gesture moves the tab; the pager's own animation to a tapped tab never writes back.
     */
    fun settled(page: Int, swiped: Boolean): TabNav = if (swiped) select(page) else this

    /** Back from the current tab's root: the tab before it, then Today. Null on Today: back leaves the app. */
    fun back(): TabNav? = when {
        current == HOME -> null
        previous != null && previous != HOME && previous != current -> TabNav(previous, null)
        else -> TabNav(HOME, null)
    }

    /**
     * A tap on the current tab, whose stack is [depth] screens deep: the first scrolls to the top, the next pops to
     * the root. With nothing pushed there's nothing to pop, so it scrolls again.
     */
    fun reselect(depth: Int): Pair<TabNav, Reselect> =
        if (reselected && depth > 0) copy(reselected = false) to Reselect.PopToRoot
        else copy(reselected = true) to Reselect.ScrollToTop

    /** Something else happened on the tab (a screen opened or closed): the next tap scrolls again first. */
    fun moved(): TabNav = if (reselected) copy(reselected = false) else this

    companion object {
        /** Today. */
        const val HOME = 0

        /** Keeps a [TabNav] across process death. */
        val Saver: androidx.compose.runtime.saveable.Saver<TabNav, IntArray> = androidx.compose.runtime.saveable.Saver(
            save = { intArrayOf(it.current, it.previous ?: -1) },
            restore = { TabNav(it[0], it[1].takeIf { p -> p >= 0 }) },
        )
    }
}

/**
 * A row on the You or Strap page opening its [page] on a wide screen (spec 2.4 Unfolded): beside the list, in place of
 * whatever was open there, so the stack is the list's root and that page. Deeper pages opened from the pane stack on
 * it as on a phone.
 */
fun <P> openBeside(pages: List<P>, page: P): List<P> = pages.take(1) + page
