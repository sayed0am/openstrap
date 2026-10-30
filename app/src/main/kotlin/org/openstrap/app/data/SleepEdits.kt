package org.openstrap.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import org.openstrap.app.data.db.SleepEdit
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage

/**
 * The user's own times for a sleep or nap the strap detected, or its removal ([SleepEdit]). The strap's rows and
 * stages stay as synced, keyed by its start; everything that reads sleep goes through [apply] (by way of
 * [SleepSource]), and a session's stages through [clip]. Main sleep and naps share every rule but their lengths.
 */
object SleepEdits {
    /** A nap shorter than this isn't kept: the strap itself doesn't report one shorter. */
    const val NAP_MIN_SECONDS = 5 * 60L
    /** Longer than this it's no nap. */
    const val NAP_MAX_SECONDS = 4 * 3600L
    const val SLEEP_MIN_SECONDS = 3600L
    const val SLEEP_MAX_SECONDS = 16 * 3600L
    /** How far either end may move from what the strap saw. */
    const val MAX_SHIFT_SECONDS = 3 * 3600L
    /** A resync can move the strap's start a little; an edit still finds its session this close, of the same kind. */
    const val MATCH_SECONDS = 15 * 60L
    /** Both ends moved less than this in all keeps the strap's own score. */
    const val KEEP_SCORE_SECONDS = 15 * 60L
    /** Days a deleted session can be restored; after them it goes for good ([expired]). */
    const val KEEP_DAYS = 7

    /** The strap's session [strap] with its [edit], when it has one. */
    data class Resolved(val strap: SleepSession, val edit: SleepEdit?) {
        val deleted: Boolean get() = edit?.deleted == true
        val moved: Boolean get() = edit?.moved == true

        /** The session as shown, or null when deleted. */
        val shown: SleepSession? get() = when {
            edit == null -> strap
            edit.deleted -> null
            edit.moved -> moved(strap, edit.start!!, edit.end!!)
            else -> strap
        }

        /** The times to show: the user's when moved, else the strap's; also for a deleted one. */
        val start: Long get() = edit?.start ?: strap.start
        val end: Long get() = edit?.end ?: strap.end
    }

    /**
     * Each of [sessions] with its edit: the one keyed by its exact start first, else an unused one of the same kind
     * that's within [MATCH_SECONDS], the nearest. An edit goes to one session at most, and never across kinds.
     */
    fun resolve(sessions: List<SleepSession>, edits: List<SleepEdit>): List<Resolved> {
        if (edits.isEmpty()) return sessions.map { Resolved(it, null) }
        val byStart = edits.associateBy { it.strapStart }
        val exact = sessions.associateWith { s -> byStart[s.start]?.takeIf { it.nap == s.nap } }
        val used = exact.values.filterNotNull().mapTo(HashSet()) { it.strapStart }
        return sessions.map { s ->
            val e = exact[s] ?: edits.filter { it.strapStart !in used && it.nap == s.nap && abs(it.strapStart - s.start) <= MATCH_SECONDS }
                .minByOrNull { abs(it.strapStart - s.start) }
                ?.also { used += it.strapStart }
            Resolved(s, e)
        }
    }

    /** [sessions] as shown: deleted ones left out, moved ones at the user's times ([moved]). */
    fun apply(sessions: List<SleepSession>, edits: List<SleepEdit>): List<SleepSession> =
        if (edits.isEmpty()) sessions else resolve(sessions, edits).mapNotNull { it.shown }

    /**
     * [strap] at [start] to [end]. Its stage totals are the strap's for its own span, so they go (the time asleep is
     * then the span, until [restage] works them out from the clipped stages). Its score stays when the move is small
     * ([keepsScore]); a main sleep's otherwise is worked out again from the span, a nap's goes.
     */
    fun moved(strap: SleepSession, start: Long, end: Long): SleepSession {
        val s = strap.copy(start = start, end = end, deepMinutes = null, lightMinutes = null, remMinutes = null, awakeMinutes = null, wakeCount = null)
        return s.copy(score = score(strap, s, ((end - start) / 60).toInt(), null))
    }

    /**
     * A moved [shown] with its totals worked out from [stages], already [clip]ped to its times: what [moved] left
     * out. [strap] is the strap's own session, for the score rule.
     */
    fun restage(shown: SleepSession, strap: SleepSession, stages: List<SleepStage>): SleepSession {
        if (stages.isEmpty()) return shown
        fun minutes(stage: Int) = (stages.filter { it.stage == stage }.sumOf { it.end - it.start } / 60).toInt()
        val deep = minutes(SleepStage.DEEP)
        val light = minutes(SleepStage.LIGHT)
        val rem = minutes(SleepStage.REM)
        val waso = Recovery.wasoMinutes(stages.map { Triple(it.start, it.end, it.stage) })
        return shown.copy(
            deepMinutes = deep, lightMinutes = light, remMinutes = rem, awakeMinutes = minutes(SleepStage.AWAKE),
            score = score(strap, shown, deep + light + rem, waso),
        )
    }

    /** The strap's score when [shown] keeps it, else OpenStrap's own for a main sleep the strap scored; a nap has none. */
    private fun score(strap: SleepSession, shown: SleepSession, asleepMinutes: Int, wasoMinutes: Int?): Int? = when {
        keepsScore(strap, shown.start, shown.end) -> strap.score
        strap.nap || strap.score == null -> null
        else -> Recovery.sleepScore(Recovery.Night(shown.day, shown.start, shown.end, asleepMinutes, wasoMinutes = wasoMinutes))
    }

    /** True when [start] to [end] is close enough to [strap]'s own times to keep its score. */
    fun keepsScore(strap: SleepSession, start: Long, end: Long): Boolean =
        abs(start - strap.start) + abs(end - strap.end) < KEEP_SCORE_SECONDS

    /** The strap's [stages] for a session, trimmed to [start] and [end]; stages outside go. */
    fun clip(stages: List<SleepStage>, start: Long, end: Long): List<SleepStage> = stages.mapNotNull {
        val from = maxOf(it.start, start)
        val to = minOf(it.end, end)
        if (to <= from) null else it.copy(start = from, end = to)
    }

    /**
     * Why [start] to [end] can't be saved for the strap's session [strap], or null when it can. [others] are the
     * other sleep and naps as shown, which it mustn't run into.
     */
    fun problem(strap: SleepSession, start: Long, end: Long, now: Long, others: List<SleepSession> = emptyList(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val nap = strap.nap
        val overlap = others.filter { start < it.end && end > it.start }.minByOrNull { it.start }
        return when {
            end <= start -> "Waking up has to come after falling asleep."
            nap && end - start < NAP_MIN_SECONDS -> "A nap needs at least 5 minutes."
            !nap && end - start < SLEEP_MIN_SECONDS -> "Sleep needs at least an hour."
            nap && end - start > NAP_MAX_SECONDS -> "A nap can be 4 hours at most."
            !nap && end - start > SLEEP_MAX_SECONDS -> "Sleep can be 16 hours at most."
            end > now -> "That's later than now."
            start < strap.start - MAX_SHIFT_SECONDS || end > strap.end + MAX_SHIFT_SECONDS -> "Keep it within 3 hours of what the strap saw."
            overlap != null -> "That runs into your ${if (overlap.nap) "nap" else "sleep"} at ${CLOCK.format(Instant.ofEpochSecond(overlap.start).atZone(zone))}."
            else -> null
        }
    }

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * The moment at [minuteOfDay] (after local midnight) closest to [near]: a clock picked for a session's end is read
     * on its own day, or the next or previous one when it crosses midnight.
     */
    fun atNear(minuteOfDay: Int, near: Long, zone: ZoneId): Long {
        val day = Instant.ofEpochSecond(near).atZone(zone).toLocalDate()
        return (-1L..1L).map { day.plusDays(it).atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toEpochSecond() }
            .minBy { abs(it - near) }
    }

    /**
     * The row to save for [strap] moved to [start] and [end], given its [current] edit: null when the times are the
     * strap's own again and it isn't deleted (the edit goes then). It keeps the current edit's key, which a resync may
     * have left a few minutes off the strap's start.
     */
    fun edit(strap: SleepSession, start: Long, end: Long, current: SleepEdit?, now: Long): SleepEdit? {
        val own = start == strap.start && end == strap.end
        val deleted = current?.deleted == true
        if (own && !deleted) return null
        return SleepEdit(current?.strapStart ?: strap.start, strap.nap, start.takeUnless { own }, end.takeUnless { own }, deleted, now)
    }

    /** [strap] deleted, keeping any times the user had given it, so a restore brings them back too. */
    fun delete(strap: SleepSession, current: SleepEdit?, now: Long): SleepEdit =
        SleepEdit(current?.strapStart ?: strap.start, strap.nap, current?.start, current?.end, deleted = true, editedAt = now)

    /** [edit] restored: its times stay; null when it had none (the edit goes then). */
    fun restore(edit: SleepEdit, now: Long): SleepEdit? = if (edit.moved) edit.copy(deleted = false, editedAt = now) else null

    /** True for a delete older than [KEEP_DAYS] at [now], whose session goes for good. */
    fun expired(edit: SleepEdit, now: Long): Boolean = edit.deleted && now - edit.editedAt >= KEEP_DAYS * 86_400L

    /** Whole days left at [now] before a deleted [edit]'s session goes for good, at least 1 while it's still there. */
    fun daysLeft(edit: SleepEdit, now: Long): Int =
        ((KEEP_DAYS * 86_400L - (now - edit.editedAt) + 86_399) / 86_400).toInt().coerceIn(1, KEEP_DAYS)

    /**
     * What's left of a delete [edit] once its session has gone for good: a marker with no times, so a later resync of
     * that sleep from the strap is dropped again instead of coming back.
     */
    fun marker(edit: SleepEdit): SleepEdit = edit.copy(start = null, end = null)
}
