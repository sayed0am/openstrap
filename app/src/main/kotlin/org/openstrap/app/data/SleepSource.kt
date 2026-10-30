package org.openstrap.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage

/**
 * Sleep as shown: the strap's sessions through the user's edits ([SleepEdits]), deleted ones left out, moved ones at
 * the user's times with their stages clipped and their totals worked out again ([SleepEdits.restage]). The only way
 * the app reads sleep for display or maths, so every screen, widget and model counts the same sleep: apart from the
 * sync that writes them and the backup that copies them as they are, nothing reads the DAO's sleep sessions or stages
 * directly (SleepReadersTest checks).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepSource(private val dao: HealthDao) {
    /**
     * Each strap session with its edit, deleted ones too, whose end as shown falls in [from, to]. The strap's are read
     * from further out, since a moved end can be up to [SleepEdits.MAX_SHIFT_SECONDS] from the strap's.
     */
    suspend fun resolvedOnce(from: Long, to: Long): List<SleepEdits.Resolved> =
        resolvedIn(dao.sleepSessionsOnce(widen(from, -1), widen(to, 1)), dao.sleepEditsOnce(), from, to)

    fun resolved(from: Long, to: Long): Flow<List<SleepEdits.Resolved>> =
        combine(dao.sleepSessions(widen(from, -1), widen(to, 1)), dao.sleepEdits()) { s, e -> resolvedIn(s, e, from, to) }

    /** Sessions as shown whose end falls in [from, to], by start. */
    suspend fun sessionsOnce(from: Long, to: Long): List<SleepSession> = shown(resolvedOnce(from, to))

    fun sessions(from: Long, to: Long): Flow<List<SleepSession>> = resolved(from, to).mapLatest { shown(it) }

    /** The stages of a session as shown ([sessionsOnce]): the strap's, clipped to its times. */
    suspend fun stagesOnce(session: SleepSession): List<SleepStage> {
        val strap = movedFrom(session) ?: return dao.sleepStagesOnce(session.start)
        return SleepEdits.clip(dao.sleepStagesOnce(strap), session.start, session.end)
    }

    /** The stages of [r] as shown, as they change: the strap's, clipped to the user's times when moved. */
    fun stages(r: SleepEdits.Resolved): Flow<List<SleepStage>> =
        dao.sleepStages(r.strap.start).map { if (r.moved) SleepEdits.clip(it, r.start, r.end) else it }

    /** The strap's own stages for its session [strap], unclipped: for showing what a move left out. */
    fun strapStages(strap: SleepSession): Flow<List<SleepStage>> = dao.sleepStages(strap.start)

    /** The stages of a session as shown ([sessions]), as they change. */
    fun stages(session: SleepSession): Flow<List<SleepStage>> =
        kotlinx.coroutines.flow.flow { emit(movedFrom(session)) }.flatMapLatest { strap ->
            if (strap == null) dao.sleepStages(session.start) else dao.sleepStages(strap).map { SleepEdits.clip(it, session.start, session.end) }
        }

    /**
     * The sleep and naps deleted less than [SleepEdits.KEEP_DAYS] before [now] whose sessions are still there, newest
     * delete first: the Deleted sleep list.
     */
    suspend fun deletedOnce(now: Long): List<SleepEdits.Resolved> {
        val edits = dao.sleepEditsOnce()
        return edits.filter { it.deleted && !SleepEdits.expired(it, now) }.sortedByDescending { it.editedAt }.mapNotNull { e -> strapOf(e, edits) }
    }

    fun deleted(now: () -> Long): Flow<List<SleepEdits.Resolved>> = dao.sleepEdits().mapLatest { deletedOnce(now()) }

    /**
     * Removes for good the session and stages of every delete older than [SleepEdits.KEEP_DAYS] at [now], and keeps
     * its edit as a marker without times ([SleepEdits.marker]), so a resync of that sleep is dropped again. Run after
     * every sync, which also takes away a resynced copy. Returns the sessions removed.
     */
    suspend fun purgeExpired(now: Long): Int {
        val edits = dao.sleepEditsOnce()
        var removed = 0
        for (e in edits.filter { SleepEdits.expired(it, now) }) {
            strapOf(e, edits)?.let { r ->
                dao.deleteSleepStagesOf(r.strap.start)
                dao.deleteSleepSession(r.strap.start)
                removed++
            }
            if (e.moved) dao.upsertSleepEdit(SleepEdits.marker(e))
        }
        return removed
    }

    /** The strap's session [edit] resolves to among [edits], when it's still there. */
    private suspend fun strapOf(edit: org.openstrap.app.data.db.SleepEdit, edits: List<org.openstrap.app.data.db.SleepEdit>): SleepEdits.Resolved? {
        val near = dao.sleepSessionsStartingOnce(edit.strapStart - SleepEdits.MATCH_SECONDS, edit.strapStart + SleepEdits.MATCH_SECONDS)
        return SleepEdits.resolve(near, edits).firstOrNull { it.edit == edit }
    }

    /** Moved sessions take their totals from their clipped stages. */
    private suspend fun shown(resolved: List<SleepEdits.Resolved>): List<SleepSession> = resolved.mapNotNull { r ->
        val s = r.shown ?: return@mapNotNull null
        if (!r.moved) s else SleepEdits.restage(s, r.strap, SleepEdits.clip(dao.sleepStagesOnce(r.strap.start), s.start, s.end))
    }

    /**
     * The strap's start for a session as shown that the user moved: the strap session the moved edit with exactly its
     * times resolves to (a resync may have left the edit's key a few minutes off). Null when it isn't moved.
     */
    private suspend fun movedFrom(session: SleepSession): Long? {
        val edits = dao.sleepEditsOnce()
        val e = edits.firstOrNull { !it.deleted && it.nap == session.nap && it.start == session.start && it.end == session.end } ?: return null
        return strapOf(e, edits)?.strap?.start ?: e.strapStart
    }

    private fun resolvedIn(sessions: List<SleepSession>, edits: List<org.openstrap.app.data.db.SleepEdit>, from: Long, to: Long) =
        SleepEdits.resolve(sessions, edits).filter { it.end in from..to }

    /** [t] moved by [SleepEdits.MAX_SHIFT_SECONDS] in [sign]'s direction, without running past Long's ends. */
    private fun widen(t: Long, sign: Int): Long = when {
        sign > 0 && t > Long.MAX_VALUE - SleepEdits.MAX_SHIFT_SECONDS -> Long.MAX_VALUE
        sign < 0 && t < Long.MIN_VALUE + SleepEdits.MAX_SHIFT_SECONDS -> Long.MIN_VALUE
        else -> t + sign * SleepEdits.MAX_SHIFT_SECONDS
    }
}
