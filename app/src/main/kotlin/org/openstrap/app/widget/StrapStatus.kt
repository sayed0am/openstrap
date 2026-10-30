package org.openstrap.app.widget

import org.openstrap.app.ui.dashboard.clockText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.tr

/** At or under this battery level, while not charging, every widget says so (widgets spec 5). */
const val STRAP_LOW_PERCENT = 15

/** A last sync older than this takes the status line, and a tap syncs. */
const val SYNC_STALE_SECONDS = 3 * 3600L

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
 * The status line, first match wins: not [paired], then [battery] at [STRAP_LOW_PERCENT] or less and not [charging],
 * then [lastSync] (epoch seconds) more than [SYNC_STALE_SECONDS] before [now]. While [syncing] the stale line reads
 * Syncing instead; a sync while all is well shows nothing, so the background syncs don't flash a line. Before the
 * first sync is known there's no time to give, so no line.
 */
fun strapStatus(paired: Boolean, battery: Int?, charging: Boolean, lastSync: Long?, now: Long, syncing: Boolean = false): StrapStatus? = when {
    !paired -> StrapStatus.NotPaired
    battery != null && battery <= STRAP_LOW_PERCENT && !charging -> StrapStatus.LowBattery(battery)
    syncStale(lastSync, now) -> if (syncing) StrapStatus.Syncing else StrapStatus.StaleSync(lastSync!!)
    else -> null
}

/** True when the last sync ended more than [SYNC_STALE_SECONDS] before [now]. */
fun syncStale(lastSync: Long?, now: Long): Boolean = lastSync != null && now - lastSync > SYNC_STALE_SECONDS

/** True when the line for the strap's state could read otherwise: pairing, the low battery edge or charging changed. */
fun strapStateChanged(paired: Boolean, battery: Int?, charging: Boolean, wasPaired: Boolean, wasBattery: Int?, wasCharging: Boolean): Boolean {
    fun low(b: Int?) = b != null && b <= STRAP_LOW_PERCENT
    return paired != wasPaired || charging != wasCharging || low(battery) != low(wasBattery)
}

/**
 * The line's words: "Pair your strap", "Strap at 12%", "Syncing", and "Synced 09:10" today, "Synced yesterday", the
 * weekday within the week ("Synced Tuesday") and the date before that ("Synced 21 Oct").
 */
fun strapStatusLine(status: StrapStatus, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String = when (status) {
    StrapStatus.NotPaired -> tr(R.string.strap_status_pair_your_strap)
    is StrapStatus.LowBattery -> tr(R.string.strap_status_strap_at, status.percent)
    StrapStatus.Syncing -> tr(R.string.strap_status_syncing)
    is StrapStatus.StaleSync -> {
        val then = Instant.ofEpochSecond(status.at).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(then, Instant.ofEpochSecond(now).atZone(zone).toLocalDate())
        tr(R.string.strap_status_synced) + when {
            days <= 0 -> clockText(status.at, zone)
            days == 1L -> recentDay(then, then.plusDays(1), locale).lowercase(locale)
            else -> recentDay(then, then.plusDays(days), locale)
        }
    }
}

/**
 * [date] as a widget says when something was, seen from [today]: "Today", "Yesterday", the weekday within the week
 * ("Tuesday") and the date before that ("21 Oct").
 */
fun recentDay(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0 -> tr(R.string.common_today)
        days == 1L -> tr(R.string.common_yesterday)
        days < 7 -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> date.format(DateTimeFormatter.ofPattern("d MMM", locale))
    }
}
