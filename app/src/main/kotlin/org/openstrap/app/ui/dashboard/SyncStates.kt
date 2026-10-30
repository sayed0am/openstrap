package org.openstrap.app.ui.dashboard

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.SyncRecord
import org.openstrap.app.device.SyncState
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * Sync and strap states (spec 4.3, patterns E1, E2 and E5): quiet until they matter. A stopped sync turns the chip
 * amber and marks the cards whose data is old with "Up to 09:40". Only a sync still failing after hours, or in the
 * morning with last night on the strap, takes Today's hero. Plain functions, so every boundary is tested.
 */

/**
 * Data older than this, while syncing has stopped, is stale. Connected, the strap syncs at least hourly by itself, so
 * an hour old is normal; the half hour on top covers a sync that's slow to start.
 */
const val STALE_AFTER_S = 90 * 60L

/** A sync still failing after this long takes Today's hero (spec 4.3). To be tuned on hardware (spec 9). */
const val STUCK_AFTER_S = 3 * 3600L

/** The morning for the stuck rule starts when the night moment ends (spec 4.1). */
val MORNING_START: LocalTime = LocalTime.of(4, 0)

/** And ends at noon, where the rest of Today stops calling it the morning. */
val MORNING_END: LocalTime = LocalTime.NOON

/**
 * The app writes times in 24 h everywhere ("HH:mm"), whatever the phone is set to. This is the one switch for the
 * times these states show, so they change with the rest of the app when it follows the phone's setting.
 */
const val APP_24_HOUR = true

/** A clock time: "09:40", or "9:40 AM" in 12 h. */
fun clockText(epoch: Long, zone: ZoneId, use24Hour: Boolean = APP_24_HOUR, locale: Locale = Locale.getDefault()): String =
    Instant.ofEpochSecond(epoch).atZone(zone).format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale))

/**
 * Whether syncing has stopped: the last sync failed, or the strap isn't connected so nothing can sync. A sync
 * running or done, connected, is fine. With no strap paired there's nothing to stop.
 */
fun syncStopped(paired: Boolean, connection: ConnectionState, sync: SyncState): Boolean =
    paired && connection != ConnectionState.NotPaired && (sync is SyncState.Failed || connection !is ConnectionState.Connected)

/**
 * The marker next to a stale card's title (spec 4.3): "Up to 09:40", the time of the card's [newest] reading, only on
 * today, while syncing has [stopped], and once that reading is [staleAfter] seconds old or more. Null otherwise.
 */
fun upToMarker(
    newest: Long?, now: Long, stopped: Boolean, isToday: Boolean, zone: ZoneId,
    staleAfter: Long = STALE_AFTER_S, use24Hour: Boolean = APP_24_HOUR,
): String? =
    if (!isToday || !stopped || newest == null || now - newest < staleAfter) null
    else tr(R.string.sync_states_up_to, clockText(newest, zone, use24Hour))

/**
 * Whether a failing sync takes Today's hero (spec 4.3): on today, still failing [STUCK_AFTER_S] after the first
 * failure ([failingSince], null while syncing works), or failing in the morning while last night isn't in yet.
 */
fun syncStuck(isToday: Boolean, hasLastNight: Boolean, failingSince: Long?, now: Long, zone: ZoneId): Boolean {
    if (!isToday || failingSince == null) return false
    if (now - failingSince >= STUCK_AFTER_S) return true
    val time = Instant.ofEpochSecond(now).atZone(zone).toLocalTime()
    return !hasLastNight && time >= MORNING_START && time < MORNING_END
}

/** What the stuck hero says: its title, the sentence and the line under it. */
data class StuckCopy(val title: String, val sentence: String, val line: String)

/**
 * The stuck hero's words. Without last night it's about the night. With last night in, a sync failing for hours is
 * about the day since [upTo] (a clock time, null when nothing came today).
 */
fun stuckCopy(hasLastNight: Boolean, upTo: String?): StuckCopy =
    if (!hasLastNight) StuckCopy(tr(R.string.common_last_night_2), tr(R.string.sync_states_last_night_is_still), tr(R.string.sync_states_keep_it_near_your))
    else StuckCopy(
        tr(R.string.common_not_synced),
        upTo?.let { tr(R.string.sync_states_your_data_since_is, it) } ?: DATA_ON_STRAP,
        tr(R.string.sync_states_keep_it_near_your),
    )

/** "Last tried 07:42", for the stuck hero; null when no attempt was seen. */
fun lastTried(record: SyncRecord, zone: ZoneId, use24Hour: Boolean = APP_24_HOUR): String? =
    record.lastAttempt?.let { tr(R.string.sync_states_last_tried, clockText(it, zone, use24Hour)) }

/** Recovery couldn't be worked out (spec 4.3, E5): yesterday's score stays, dimmed, with this line and [TRY_AGAIN]. */
val RECOVERY_FAILED: String get() = tr(R.string.sync_states_couldn_t_work_out)

/** Charge is worked out with Recovery, so it fails with it. */
val CHARGE_FAILED: String get() = tr(R.string.sync_states_couldn_t_work_out_2)

/** The same on a small card, whose status line has room for a few words: Load, Charge's parts. */
val FAILED_SHORT: String get() = tr(R.string.sync_states_couldn_t_work_it)

val TRY_AGAIN: String get() = tr(R.string.common_try_again)

/** What a card waiting on a stuck night says in place of its value. */
val WAITING_LAST_NIGHT: String get() = tr(R.string.sync_states_waiting_for_last_night)

/** Where a failed sync or one that can't start is told: the data is safe. */
val DATA_ON_STRAP: String get() = tr(R.string.sync_states_your_data_is_still)

/**
 * Debug only: a strap state to show on a phone whose strap is fine, from the `strap_state` extra. It changes what the
 * screens are shown and nothing else: no Bluetooth, no sync, nothing written.
 */
enum class StrapPreview(val extra: String) {
    Stale("stale"),
    Stuck("stuck"),
    Disconnected("disconnected"),
    Charging("charging"),
    NotPaired("notpaired"),
    RecoveryFail("recoveryfail");

    /** Stale and stuck mark every card with data, however fresh, so the markers can be seen at once. */
    val staleAfter: Long get() = if (this == Stale || this == Stuck) 0L else STALE_AFTER_S

    companion object {
        fun of(extra: String): StrapPreview? = entries.firstOrNull { it.extra.equals(extra, ignoreCase = true) }
    }
}

/** What the screens are shown of the strap. */
data class StrapView(val paired: Boolean, val connection: ConnectionState, val sync: SyncState, val record: SyncRecord, val lastSeen: Long?)

/** This view as the debug [preview] shows it at [now]; the real one with no preview. */
fun StrapView.previewed(preview: StrapPreview?, now: Long): StrapView {
    val battery = (connection as? ConnectionState.Connected)?.batteryPercent ?: PREVIEW_BATTERY
    val connected = connection as? ConnectionState.Connected ?: ConnectionState.Connected(battery, null)
    return when (preview) {
        null, StrapPreview.RecoveryFail -> this
        StrapPreview.Stale -> copy(
            paired = true, connection = connected.copy(charging = false), sync = SyncState.Failed("Preview"),
            record = SyncRecord(lastSuccess = now - 2 * 3600, lastAttempt = now - 5 * 60, failingSince = now - 30 * 60),
        )
        StrapPreview.Stuck -> copy(
            paired = true, connection = connected.copy(charging = false), sync = SyncState.Failed("Preview"),
            record = SyncRecord(lastSuccess = now - STUCK_AFTER_S - 3600, lastAttempt = now - 10 * 60, failingSince = now - STUCK_AFTER_S - 60),
        )
        StrapPreview.Disconnected -> copy(paired = true, connection = ConnectionState.Disconnected, sync = SyncState.Idle, record = SyncRecord(), lastSeen = now - 18 * 60)
        StrapPreview.Charging -> copy(paired = true, connection = connected.copy(charging = true), sync = SyncState.Idle, record = SyncRecord())
        StrapPreview.NotPaired -> copy(paired = false, connection = ConnectionState.NotPaired, sync = SyncState.Idle, record = SyncRecord(), lastSeen = null)
    }
}

/** The battery a preview shows when the real strap isn't connected to give one. */
private const val PREVIEW_BATTERY = 64
