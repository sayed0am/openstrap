package org.openstrap.app.ui.dashboard

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.data.Moment
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.SyncState
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.StrapBands
import org.openstrap.app.ui.glow.draw.LightAngle

/*
 * The frame around every tab (spec 3): what the header says about the day and the strap, and how the tabs answer
 * taps and back. Plain functions, so the rules are tested without a screen.
 */

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
fun strapChip(
    paired: Boolean, connection: ConnectionState, sync: SyncState, lastSeen: Long?, now: Long, sinceStart: Long = Long.MAX_VALUE,
    strings: Strings = Strings.app,
): StrapChip {
    if (!paired || connection == ConnectionState.NotPaired) {
        return StrapChip(
            strings.get(R.string.day_chip_not_paired), StrapChip.Dot.Quiet, strings.get(R.string.day_chip_not_paired_description),
        )
    }
    val connecting = { StrapChip("", StrapChip.Dot.Quiet, strings.get(R.string.day_chip_connecting_description)) }
    if (connection !is ConnectionState.Connected && sinceStart < STARTUP_GRACE_S) return connecting()
    val ago = lastSeen?.let { now - it }
    val unseen = if (ago != null) {
        val text = agoText(ago, strings)
        StrapChip(text, StrapChip.Dot.Warn, strings.format(R.string.day_chip_last_seen_description, text))
    } else {
        StrapChip(
            strings.get(R.string.day_chip_not_connected), StrapChip.Dot.Warn, strings.get(R.string.day_chip_not_connected_description),
        )
    }
    return when (connection) {
        is ConnectionState.Connected -> {
            val battery = connection.batteryPercent
            when {
                connection.charging && battery != null -> StrapChip(
                    strings.format(R.string.day_chip_charging, battery), StrapChip.Dot.Charging,
                    strings.plural(R.plurals.day_chip_charging_description, battery, battery),
                )
                sync is SyncState.Failed -> StrapChip(
                    strings.get(R.string.day_chip_not_synced), StrapChip.Dot.Warn, strings.get(R.string.day_chip_not_synced_description),
                )
                battery == null -> StrapChip("", StrapChip.Dot.Fine, strings.get(R.string.day_chip_connected_description))
                StrapBands.lowBattery(battery, connection.charging) -> StrapChip(
                    strings.format(R.string.day_chip_battery, battery), StrapChip.Dot.Low,
                    strings.plural(R.plurals.day_chip_battery_low_description, battery, battery),
                )
                else -> StrapChip(
                    strings.format(R.string.day_chip_battery, battery), StrapChip.Dot.Fine,
                    strings.plural(R.plurals.day_chip_battery_description, battery, battery),
                )
            }
        }
        // A moment's connecting (the app coming to the front) isn't news; a strap gone for minutes is.
        ConnectionState.Connecting, ConnectionState.Authenticating ->
            if (ago != null && ago >= CONNECTING_GRACE_S) unseen else connecting()
        else -> unseen
    }
}

/** "Just now", "18 min ago", "3 h ago", "2 days ago". */
fun agoText(seconds: Long, strings: Strings = Strings.app): String {
    val minutes = seconds.coerceAtLeast(0) / 60
    return when {
        minutes < 1 -> strings.get(R.string.day_ago_now)
        minutes < 60 -> strings.format(R.string.day_ago_minutes, minutes)
        minutes < 24 * 60 -> strings.format(R.string.day_ago_hours, minutes / 60)
        else -> (minutes / (24 * 60)).toInt().let { strings.plural(R.plurals.day_ago_days, it, it) }
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
