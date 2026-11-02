package org.openstrap.app.widget

import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.StrapBands
import org.openstrap.app.presentation.TimeOfDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The strap status line a widget shows in its top row (widgets spec 5); none while all is well. */
sealed interface StrapStatus {
    /** Opens the Strap page. */
    data object NotPaired : StrapStatus

    /** Opens the Strap page. */
    data class LowBattery(val percent: Int) : StrapStatus

    /** [at] is the last sync's end, epoch seconds. A tap syncs. */
    data class StaleSync(val at: Long) : StrapStatus

    /** The stale line's sync, under way. */
    data object Syncing : StrapStatus
}

/**
 * The status line, first match wins: not [paired], then [battery] at [StrapBands.LOW_BATTERY] or less and not [charging],
 * then [lastSync] (epoch seconds) more than [StrapBands.STALE_AFTER_S] before [now]. While [syncing] the stale line reads
 * Syncing instead; a sync while all is well shows nothing, so the background syncs don't flash a line. Before the
 * first sync is known there's no time to give, so no line.
 */
fun strapStatus(paired: Boolean, battery: Int?, charging: Boolean, lastSync: Long?, now: Long, syncing: Boolean = false): StrapStatus? = when {
    !paired -> StrapStatus.NotPaired
    battery != null && StrapBands.lowBattery(battery, charging) -> StrapStatus.LowBattery(battery)
    syncStale(lastSync, now) -> if (syncing) StrapStatus.Syncing else StrapStatus.StaleSync(lastSync!!)
    else -> null
}

/** True when the last sync ended more than [StrapBands.STALE_AFTER_S] before [now] ([StrapBands.stale]). */
fun syncStale(lastSync: Long?, now: Long): Boolean = StrapBands.stale(lastSync, now)

/** True when the line for the strap's state could read otherwise: pairing, the low battery edge or charging changed. */
fun strapStateChanged(paired: Boolean, battery: Int?, charging: Boolean, wasPaired: Boolean, wasBattery: Int?, wasCharging: Boolean): Boolean {
    fun low(b: Int?) = b != null && b <= StrapBands.LOW_BATTERY
    return paired != wasPaired || charging != wasCharging || low(battery) != low(wasBattery)
}

/**
 * The line's words: "Pair your strap", "Strap at 12%", "Syncing", and "Synced 09:10" today, then by [DayWords]
 * "Synced yesterday", the weekday within the week ("Synced Tuesday") and the date before that ("Synced Tue 21 Oct").
 */
fun strapStatusLine(status: StrapStatus, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String = when (status) {
    StrapStatus.NotPaired -> "Pair your strap"
    is StrapStatus.LowBattery -> "Strap at ${status.percent}%"
    StrapStatus.Syncing -> "Syncing"
    is StrapStatus.StaleSync -> {
        val then = Instant.ofEpochSecond(status.at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        "Synced " + if (then >= today) TimeOfDay.format(status.at, zone, locale = locale) else DayWords.inSentence(then, today, locale)
    }
}

/**
 * [date] as a widget says when something was, seen from [today], by [DayWords]: "Today", "Yesterday", the weekday
 * within the week ("Tuesday") and the date before that ("Tue 21 Oct"). A day after [today] reads "Today".
 */
fun recentDay(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String =
    DayWords.relative(minOf(date, today), today, locale)
