package org.openstrap.app.health

import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.db.SleepEdit
import kotlin.math.abs

/**
 * Which sleep read back from Health Connect the import leaves out. The import fills gaps by start, but a sleep the
 * user moved was exported at the user's times, so its start isn't the strap's and it would come back as a second
 * night. A sleep that overlaps one the phone has, at the strap's times or the user's, or one the user deleted, stays
 * out.
 *
 * [phone] is the phone's sleep near what is read ([org.openstrap.app.data.SleepSource.resolvedOnce], deleted ones
 * too), [edits] every edit, a deleted sleep's marker whose session has gone for good included.
 */
internal class SleepImport(phone: List<SleepEdits.Resolved>, edits: List<SleepEdit>) {
    /** Every span the phone has a sleep in: each session at the strap's times and at the user's. */
    private val spans = phone.flatMap { listOf(it.strap.start to it.strap.end, it.start to it.end) } +
        edits.mapNotNull { e -> e.start?.let { start -> e.end?.let { start to it } } }

    /** The strap's starts the phone knows a sleep by. */
    private val starts = phone.mapTo(HashSet()) { it.strap.start } + edits.map { it.strapStart }

    /** The strap's starts of the sleep the user deleted, kept as markers after their sessions go. */
    private val deleted = edits.filter { it.deleted }.map { it.strapStart }

    /** True when the sleep [start] to [end], exported for the strap's session at [strapStart] when known, stays out. */
    fun leavesOut(start: Long, end: Long, strapStart: Long?): Boolean =
        spans.any { start < it.second && end > it.first } ||
            strapStart != null && strapStart in starts ||
            deleted.any { abs(it - (strapStart ?: start)) <= SleepEdits.MATCH_SECONDS }

    companion object {
        /** How far around what is read the phone's sleep is looked for: past the longest sleep and the furthest move. */
        const val REACH_SECONDS = 2 * 86_400L

        private const val PREFIX = "openstrap:sleep:"

        /** The strap's start an exported sleep carries in its id ("openstrap:sleep:<start>"), or null for another id. */
        fun strapStart(clientRecordId: String?): Long? =
            clientRecordId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()
    }
}
