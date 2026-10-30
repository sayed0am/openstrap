package org.openstrap.app.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import org.openstrap.app.R
import org.openstrap.app.data.db.NapEdit
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.tr

/**
 * The user's own start and end for a nap the strap detected ([NapEdit]). The strap's row and stages stay as synced,
 * keyed by its start; everything that counts a nap reads it through [apply], and its stages through [clip].
 */
object NapTimes {
    /** A nap shorter than this isn't kept: the strap itself doesn't report one shorter. */
    const val MIN_SECONDS = 5 * 60L
    /** Longer than this it's no nap. */
    const val MAX_SECONDS = 4 * 3600L
    /** How far either end may move from what the strap saw. */
    const val MAX_SHIFT_SECONDS = 3 * 3600L

    /** [s] with the user's times when it's a nap they changed; anything else as it is. */
    fun apply(s: SleepSession, edits: Map<Long, NapEdit>): SleepSession {
        if (!s.nap) return s
        val e = edits[s.start] ?: return s
        return s.copy(start = e.start, end = e.end)
    }

    fun applyAll(sessions: List<SleepSession>, edits: List<NapEdit>): List<SleepSession> {
        if (edits.isEmpty()) return sessions
        val byStart = edits.associateBy { it.strapStart }
        return sessions.map { apply(it, byStart) }
    }

    /** The strap's [stages] for a nap, trimmed to [start] and [end]; stages outside go. */
    fun clip(stages: List<SleepStage>, start: Long, end: Long): List<SleepStage> = stages.mapNotNull {
        val from = maxOf(it.start, start)
        val to = minOf(it.end, end)
        if (to <= from) null else it.copy(start = from, end = to)
    }

    /** Why [start] to [end] can't be saved for the strap's nap [strap], or null when it can. */
    fun problem(strap: SleepSession, start: Long, end: Long, now: Long): String? = when {
        end <= start -> tr(R.string.nap_times_waking_up_has_to)
        end - start < MIN_SECONDS -> tr(R.string.nap_times_a_nap_needs_at)
        end - start > MAX_SECONDS -> tr(R.string.nap_times_a_nap_can_be)
        end > now -> tr(R.string.nap_times_thats_later_than_now)
        start < strap.start - MAX_SHIFT_SECONDS || end > strap.end + MAX_SHIFT_SECONDS -> tr(R.string.nap_times_keep_it_within_3)
        else -> null
    }

    /**
     * The moment at [minuteOfDay] (after local midnight) closest to [near]: a clock picked for a nap's end is read
     * on the nap's own day, or the next or previous one when the nap crosses midnight.
     */
    fun atNear(minuteOfDay: Int, near: Long, zone: ZoneId): Long {
        val day = Instant.ofEpochSecond(near).atZone(zone).toLocalDate()
        return (-1L..1L).map { day.plusDays(it).atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toEpochSecond() }
            .minBy { abs(it - near) }
    }

    /** The row to save for [strap], or null when the times are the strap's own again (the edit goes then). */
    fun edit(strap: SleepSession, start: Long, end: Long): NapEdit? =
        if (start == strap.start && end == strap.end) null else NapEdit(strap.start, start, end)
}
