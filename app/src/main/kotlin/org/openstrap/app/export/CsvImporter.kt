package org.openstrap.app.export

import androidx.room.withTransaction
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.DismissedWorkout
import org.openstrap.app.data.db.OpenStrapDatabase
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Restores a zip made by [CsvExporter] into what the phone is missing: every row whose key the phone has no row for
 * comes back, and a row it has keeps the phone's values, so a day the strap only partly re-sent is filled in, as are
 * days a newer backup or a Health Connect import holds. A sleep or workout that overlaps one on the phone is left out
 * too, as the strap can re-send a night with another start, and stages and workout heart rate come back only with their
 * sleep or workout. A strap workout deleted in OpenStrap, on the phone or by the backup, stays deleted: its row and the
 * sport effect deleted with it are left out, so its heart rate is too. A phone workout discarded since the backup was
 * made stays gone too, as its range is kept. Restoring twice changes nothing. Runs as one
 * transaction, so a zip that fails part way changes nothing. Files this version doesn't know are skipped, and a backup
 * from before deleted workouts were kept restores as it did. The settings file isn't written here: [Result.settings]
 * carries it.
 */
class CsvImporter(private val db: OpenStrapDatabase) {

    /**
     * What one restore did: rows added per file, rows left out as the phone has them, and rows unreadable.
     * [settings] is the zip's [SettingsBackup.FILE] as is, for the caller to restore; null in a zip from before it.
     */
    data class Result(val rows: Map<String, Int>, val present: Int, val skipped: Int, val settings: String? = null) {
        val total get() = rows.values.sum()
    }

    private class Outcome(val stored: Int, val present: Int, val bad: Int)

    /** One CSV row read: its time and, for a span, its end, its text key or key part ([Key.unique], [Key.part]), and the row. */
    private class Parsed<T>(val time: Long, val end: Long, val id: String?, val value: T)

    /** A file's rows as read, waiting to be written, and how many were unreadable. */
    private class Pending<T>(val table: CsvTable<T>, val rows: List<Parsed<T>>, val bad: Int)

    suspend fun importZip(input: InputStream): Result = db.withTransaction {
        val tables = CSV_TABLES.associateBy { it.file }
        val counts = linkedMapOf<String, Int>()
        // Read before the zip's own: one zip is one snapshot, so a range it holds never covers a workout it holds.
        val dismissed = db.healthDao().dismissedWorkoutsAll()
        // Starts of the sleeps and workouts this restore added: only their stages and heart rate come with them.
        val added = mutableMapOf<String, Set<Long>>()
        // Child tables wait until every parent is in, whatever order the zip lists them in, and the tables a deleted
        // or discarded workout is left out of wait until every deleted workout is in.
        val children = mutableListOf<Pending<*>>()
        val deletable = mutableListOf<Pending<*>>()
        var present = 0
        var skipped = 0
        var settings: String? = null
        fun count(file: String, o: Outcome) {
            counts[file] = o.stored; present += o.present; skipped += o.bad
        }
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.substringAfterLast('/') == SettingsBackup.FILE) {
                    settings = zip.readBytes().toString(Charsets.UTF_8)
                    continue
                }
                val table = tables[entry.name.substringAfterLast('/')] ?: continue
                // Only this entry, read as it streams: the reader is never closed, so the zip stays open.
                val pending = table.read(CsvReader(zip.reader(Charsets.UTF_8))) ?: continue
                when {
                    table.key.parent != null -> children += pending
                    table.key.table in DELETABLE -> deletable += pending
                    else -> count(table.file, pending.backfill(added))
                }
            }
        }
        val deleted = db.healthDao().deletedWorkoutsAll()
        removeResent(deleted)
        val gone = mapOf(
            "workout" to deleted.mapTo(HashSet()) { it.start },
            "sport_effect" to deleted.mapNotNullTo(HashSet()) { it.effectEnd },
            "app_workout" to discarded(deletable, dismissed),
        )
        for (p in deletable) count(p.table.file, p.backfill(added, gone[p.table.key.table].orEmpty()))
        for (p in children) count(p.table.file, p.backfill(added))
        // Throwing rolls back, so a zip that isn't ours leaves the database untouched.
        if (counts.isEmpty()) throw IllegalArgumentException("This isn't an OpenStrap export.")
        Result(counts, present, skipped, settings)
    }

    /**
     * Starts of the phone workouts in [pending] that lie inside a range the phone had [dismissed]: a Discard deletes the
     * row and keeps its [Workouts.phoneRange] ([org.openstrap.app.device.SavedWorkoutEditor.discard]), so an older zip
     * would bring it back. Only inside, so a workout that merely touches a "Not a workout" range comes back.
     */
    private fun discarded(pending: List<Pending<*>>, dismissed: List<DismissedWorkout>): Set<Long> =
        pending.filter { it.table.key.table == "app_workout" }.flatMap { it.rows }.mapNotNullTo(HashSet()) { row ->
            val w = row.value as? AppWorkout ?: return@mapNotNullTo null
            val range = Workouts.phoneRange(w, Long.MAX_VALUE)
            w.start.takeIf { dismissed.any { d -> d.start <= range.first && range.last <= d.end } }
        }

    /**
     * Workouts deleted on the old phone that the strap re-sent here before the restore: the restored marker keeps
     * them out of later syncs, and they go now, with their heart rate and their effect, as a delete would.
     */
    private suspend fun removeResent(deleted: List<org.openstrap.app.data.db.DeletedWorkout>) {
        val dao = db.healthDao()
        for (d in deleted.filter { dao.workoutOnce(it.start) != null }) {
            dao.deleteWorkoutHeartRate(d.start)
            dao.deleteRecoveryHeartRate(d.start)
            d.effectEnd?.let { dao.deleteSportEffect(it) }
            dao.deleteWorkoutRow(d.start)
        }
    }

    /** This table's rows from [records], the header first; null for an empty file. */
    private fun <T> CsvTable<T>.read(records: CsvReader): Pending<T>? {
        val index = (records.next() ?: return null).withIndex().associate { (i, name) -> name to i }
        var bad = 0
        val rows = mutableListOf<Parsed<T>>()
        while (true) {
            val cells = CsvRow(index, records.next() ?: break)
            runCatching {
                val time = cells.long(key.epoch)
                val id = (key.unique ?: key.part)?.let { cells.string(it.csv) }
                rows += Parsed(time, key.span?.let { cells.long(it.epoch) } ?: time, id, parse(cells))
            }.onFailure { bad++ }
        }
        return Pending(this, rows, bad)
    }

    /** Writes the rows the phone is missing ([missing]), or a child table's rows of the parents just added, less those timed at [gone]. */
    private suspend fun <T> Pending<T>.backfill(added: MutableMap<String, Set<Long>>, gone: Set<Long> = emptySet()): Outcome {
        val key = table.key
        val rows = rows.filter { it.time !in gone }
        val parent = key.parent
        val fresh = when {
            rows.isEmpty() -> rows
            parent != null -> added[parent].orEmpty().let { starts -> rows.filter { it.time in starts } }
            else -> missing(key, rows)
        }
        if (key.span != null) added[key.table] = fresh.mapTo(HashSet()) { it.time }
        fresh.map { it.value }.chunked(2_000).forEach { table.write(db.healthDao(), it) }
        return Outcome(fresh.size, rows.size - fresh.size, bad)
    }

    /**
     * The [rows] whose key the phone has no row for, whatever day they fall on: a row the phone has keeps its values, as
     * the strap's or the user's. A span also leaves out one that overlaps a span on the phone, still recording included,
     * since the strap can re-send a night with another start.
     */
    private fun <T> missing(key: Key, rows: List<Parsed<T>>): List<Parsed<T>> {
        val from = rows.minOf { it.time }
        val to = rows.maxOf { it.end }
        val unique = key.unique
        val part = key.part
        val inRange = "WHERE ${key.column} BETWEEN ? AND ?"
        val absent = when {
            unique != null -> {
                val have = query("SELECT ${unique.column} FROM ${key.table}") { it.getString(0) }.toHashSet()
                rows.filter { it.id !in have }
            }
            part != null -> {
                val sql = "SELECT ${key.column}, ${part.column} FROM ${key.table} $inRange"
                val have = query(sql, from, to) { it.getLong(0) to it.getString(1) }.toHashSet()
                rows.filter { (it.time to it.id) !in have }
            }
            else -> {
                val have = query("SELECT ${key.column} FROM ${key.table} $inRange", from, to) { it.getLong(0) }.toHashSet()
                rows.filter { it.time !in have }
            }
        }
        val span = key.span ?: return absent
        val end = "IFNULL(${span.column}, ${Long.MAX_VALUE})"
        val have = query("SELECT ${key.column}, $end FROM ${key.table} WHERE ${key.column} <= ? AND $end >= ?", to, from) {
            it.getLong(0) to it.getLong(1)
        }
        return absent.filter { row -> have.none { (s, e) -> s < row.end && e > row.time } }
    }

    private fun <R> query(sql: String, vararg args: Any, read: (android.database.Cursor) -> R): List<R> =
        db.openHelper.writableDatabase.query(sql, args).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    companion object {
        /**
         * Tables whose rows a deleted strap workout takes with it, by its start (workout) or its effect's end, and a
         * discarded phone workout by its start (app_workout).
         */
        private val DELETABLE = setOf("workout", "sport_effect", "app_workout")

        /** One CSV line into cells, as [CsvReader] reads it. */
        fun parseLine(line: String): List<String> = CsvReader(line.reader()).next() ?: listOf("")
    }
}
