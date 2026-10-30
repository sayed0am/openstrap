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
     * ([keepsScore]); a main sleep's otherwise moves from the strap's by what the span changes ([anchored]), a nap's
     * goes.
     */
    fun moved(strap: SleepSession, start: Long, end: Long): SleepSession {
        val s = strap.copy(start = start, end = end, deepMinutes = null, lightMinutes = null, remMinutes = null, awakeMinutes = null, wakeCount = null)
        // Without stages both sides are their spans, so only the change in length counts.
        return s.copy(score = score(strap, s, Part(((end - start) / 60).toInt(), null), Part(((strap.end - strap.start) / 60).toInt(), null)))
    }

    /**
     * A moved [shown] with its totals worked out from the strap's [stages] for its whole session, [clip]ped here to
     * the user's times: what [moved] left out. [strap] is the strap's own session, for the score rule.
     */
    fun restage(shown: SleepSession, strap: SleepSession, stages: List<SleepStage>): SleepSession {
        if (stages.isEmpty()) return shown
        val clipped = clip(stages, shown.start, shown.end)
        fun minutes(st: List<SleepStage>, stage: Int) = (st.filter { it.stage == stage }.sumOf { it.end - it.start } / 60).toInt()
        fun part(st: List<SleepStage>) = Part(
            listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM).sumOf { minutes(st, it) },
            Recovery.wasoMinutes(st.map { Triple(it.start, it.end, it.stage) }),
        )
        return shown.copy(
            deepMinutes = minutes(clipped, SleepStage.DEEP), lightMinutes = minutes(clipped, SleepStage.LIGHT),
            remMinutes = minutes(clipped, SleepStage.REM), awakeMinutes = minutes(clipped, SleepStage.AWAKE),
            score = score(strap, shown, part(clipped), part(stages)),
        )
    }

    /** Minutes asleep and awake after sleep onset (null without stages): what OpenStrap's own score reads. */
    private data class Part(val asleep: Int, val waso: Int?)

    /**
     * The strap's score when [shown] keeps it ([keepsScore]), else [anchored] to it for a main sleep the strap scored
     * ([user] the shown span, [own] the strap's); a nap has none.
     */
    private fun score(strap: SleepSession, shown: SleepSession, user: Part, own: Part): Int? = when {
        keepsScore(strap, shown.start, shown.end) -> strap.score
        strap.nap || strap.score == null -> null
        else -> anchored(
            strap.score,
            Recovery.sleepScore(Recovery.Night(shown.day, shown.start, shown.end, user.asleep, wasoMinutes = user.waso)),
            Recovery.sleepScore(Recovery.Night(strap.day, strap.start, strap.end, own.asleep, wasoMinutes = own.waso)),
            (shown.end - shown.start) - (strap.end - strap.start),
        )
    }

    /**
     * The strap's [strapScore] moved by what OpenStrap's own score says the new times change ([ownShown] less
     * [ownStrap]), so the strap's judgement of the night stays the anchor. A shorter night ([lengthChange], seconds,
     * below 0) never scores higher than the strap's, nor a longer one lower, whatever the clipped stages do to its
     * wake after sleep onset. 0 to 100.
     */
    fun anchored(strapScore: Int, ownShown: Int, ownStrap: Int, lengthChange: Long): Int {
        val moved = (strapScore + ownShown - ownStrap).coerceIn(0, 100)
        return when {
            lengthChange < 0 -> minOf(moved, strapScore)
            lengthChange > 0 -> maxOf(moved, strapScore)
            else -> moved
        }
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

    /** A nap's heart rate this close to the hour before it reads as sitting still, not sleep ([stillHeartRate]). */
    const val STILL_BPM = 5f

    /**
     * The nap's average heart rate when it looks like sitting still rather than a nap (edit spec 4.5): within
     * [STILL_BPM] of the hour before it, and no deep sleep. [heartRate] is (time, bpm) readings around the nap; null
     * when it doesn't look like that, or there's too little to tell. Nothing is removed by it, the nap only says so.
     */
    fun stillHeartRate(heartRate: List<Pair<Long, Float>>, start: Long, end: Long, deepSeconds: Long): Int? {
        if (deepSeconds > 0) return null
        val during = heartRate.filter { it.first in start..end }.map { it.second }
        val before = heartRate.filter { it.first in start - 3600 until start }.map { it.second }
        if (during.isEmpty() || before.isEmpty()) return null
        val avg = during.average().toFloat()
        return if (abs(avg - before.average().toFloat()) <= STILL_BPM) Math.round(avg) else null
    }

    /**
     * What's left of a delete [edit] once its session has gone for good: a marker with no times, so a later resync of
     * that sleep from the strap is dropped again instead of coming back.
     */
    fun marker(edit: SleepEdit): SleepEdit = edit.copy(start = null, end = null)
}
