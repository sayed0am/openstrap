package org.openstrap.app.export

import androidx.room.withTransaction
import org.openstrap.app.data.DayKey
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.DayKeyMerge
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.DismissedWorkout
import org.openstrap.app.data.db.OpenStrapDatabase
import java.io.InputStream
import java.time.ZoneId
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
 *
 * The big tables (a row a minute, a workout's heart rate) are read and written [batch] rows at a time, so a restore
 * never holds a whole table (data integrity item 23). Tables keyed by day, text or span are small and read whole.
 */
class CsvImporter(private val db: OpenStrapDatabase, private val batch: Int = CSV_PAGE) {

    /**
     * What one restore did: rows added per file, rows left out as the phone has them, and rows unreadable.
     * [settings] is the zip's [SettingsBackup.FILE] as is, for the caller to restore; null in a zip from before it.
     */
    data class Result(val rows: Map<String, Int>, val present: Int, val skipped: Int, val settings: String? = null) {
        val total get() = rows.values.sum()
    }

    private class Outcome(val stored: Int, val present: Int, val bad: Int)

    /**
     * One CSV row read: its time and, for a span, its end, its text key or key part ([Key.unique], [Key.part]), and the
     * row. A day's time is its [DayKey], and [legacy] its older local midnight when the file had no date.
     */
    private class Parsed<T>(val time: Long, val end: Long, val id: String?, val value: T, val legacy: Long? = null)

    /** A file's rows as read, waiting to be written, and how many were unreadable. */
    private class Pending<T>(val table: CsvTable<T>, val rows: List<Parsed<T>>, val bad: Int)

    suspend fun importZip(input: InputStream): Result = db.withTransaction {
        // Read before the zip's own: one zip is one snapshot, so a range it holds never covers a workout it holds.
        val restore = Restore(db.healthDao().dismissedWorkoutsAll())
        ZipInputStream(input).use { zip ->
            while (true) restore.file((zip.nextEntry ?: break).name.substringAfterLast('/'), zip)
        }
        restore.finish()
    }

    /** One restore's state as its files come in, and what it does once they are all read ([finish]). */
    private inner class Restore(private val dismissed: List<DismissedWorkout>) {
        private val tables = CSV_TABLES.associateBy { it.file }
        private val counts = linkedMapOf<String, Int>()

        // Starts of the sleeps and workouts this restore added: only their stages and heart rate come with them.
        private val added = mutableMapOf<String, Set<Long>>()

        // Child tables wait until every parent is in, whatever order the zip lists them in, and the tables a deleted
        // or discarded workout is left out of wait until every deleted workout is in.
        private val children = mutableListOf<Pending<*>>()
        private val deletable = mutableListOf<Pending<*>>()

        // The rows of each deletable parent the phone has no row for: whether a deleted workout leaves them out is only
        // known at the end, so their children are written for all of them and taken out again for those left out.
        private val candidates = mutableMapOf<String, Set<Long>>()
        private val early = mutableListOf<CsvTable<*>>()
        private var present = 0
        private var skipped = 0
        private var settings: String? = null

        private fun count(file: String, o: Outcome?) {
            if (o == null) return
            counts[file] = o.stored
            present += o.present
            skipped += o.bad
        }

        /** The zip's entry [name], read from [zip] as it streams: the reader is never closed, so the zip stays open. */
        suspend fun file(name: String, zip: ZipInputStream) {
            if (name == SettingsBackup.FILE) {
                settings = zip.readBytes().toString(Charsets.UTF_8)
                return
            }
            val table = tables[name] ?: return
            val records = CsvReader(zip.reader(Charsets.UTF_8))
            val key = table.key
            val parent = key.parent
            val starts = parent?.let { added[it] ?: candidates[it] }
            when {
                // Its parent's rows are in already (or are candidates): only theirs, a batch at a time.
                starts != null -> {
                    count(table.file, table.stream(records) { rows -> rows.filter { it.time in starts } })
                    if (parent !in added) early += table
                }
                parent != null -> table.read(records)?.let { children += it }
                key.table in DELETABLE -> table.read(records)?.let { pending ->
                    deletable += pending
                    candidates[key.table] = absent(key, pending.rows)
                }
                key.span == null && key.unique == null && key.day == null ->
                    count(table.file, table.stream(records) { rows -> missing(key, rows) })
                else -> count(table.file, table.read(records)?.backfill(added))
            }
        }

        /** Every file read: the tables that waited go in, and the children of parents left out come out again. */
        suspend fun finish(): Result {
            val deleted = db.healthDao().deletedWorkoutsAll()
            removeResent(deleted)
            val gone = mapOf(
                "workout" to deleted.mapTo(HashSet()) { it.start },
                "sport_effect" to deleted.mapNotNullTo(HashSet()) { it.effectEnd },
                "app_workout" to discarded(),
            )
            for (p in deletable) count(p.table.file, p.backfill(added, gone[p.table.key.table].orEmpty()))
            for (t in early) {
                val parent = t.key.parent ?: continue
                val removed = removeChildren(t.key, candidates[parent].orEmpty() - added[parent].orEmpty())
                if (removed > 0) {
                    counts[t.file] = (counts[t.file] ?: 0) - removed
                    present += removed
                }
            }
            for (p in children) count(p.table.file, p.backfill(added))
            // Throwing rolls back, so a zip that isn't ours leaves the database untouched.
            require(counts.isNotEmpty()) { "This isn't an unstrap export." }
            return Result(counts, present, skipped, settings)
        }

        /**
         * Starts of the phone workouts read that lie inside a range the phone had [dismissed]: a Discard deletes the
         * row and keeps its [Workouts.phoneRange] ([org.openstrap.app.device.SavedWorkoutEditor.discard]), so an older
         * zip would bring it back. Only inside, so a workout that merely touches a "Not a workout" range comes back.
         */
        private fun discarded(): Set<Long> =
            deletable.filter { it.table.key.table == "app_workout" }.flatMap { it.rows }.mapNotNullTo(HashSet()) { row ->
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

        /** The times (starts) of [rows] the phone has no row for by key: a deletable parent's candidates. */
        private fun absent(key: Key, rows: List<Parsed<*>>): Set<Long> {
            if (rows.isEmpty()) return emptySet()
            val sql = "SELECT ${key.column} FROM ${key.table} WHERE ${key.column} BETWEEN ? AND ?"
            val have = query(sql, rows.minOf { it.time }, rows.maxOf { it.time }) { it.getLong(0) }.toHashSet()
            return rows.mapNotNullTo(HashSet()) { it.time.takeIf { t -> t !in have } }
        }

        /**
         * Takes out a child table's rows of the parents at [starts], written early ([stream]) for a parent the restore
         * then left out (a deleted workout, or one that overlaps the phone's). How many went.
         */
        private fun removeChildren(key: Key, starts: Set<Long>): Int =
            starts.sumOf { db.openHelper.writableDatabase.delete(key.table, "${key.column} = ?", arrayOf<Any?>(it)) }
    }

    /** This table's rows from [records], the header first; null for an empty file. */
    private fun <T> CsvTable<T>.read(records: CsvReader): Pending<T>? {
        val index = header(records) ?: return null
        var bad = 0
        val rows = mutableListOf<Parsed<T>>()
        val zone = ZoneId.systemDefault()
        while (true) {
            val cells = records.next() ?: break
            parsed(index, cells, zone)?.let { rows += it } ?: bad++
        }
        return Pending(this, if (key.day != null) oneADay(key, rows, zone) else rows, bad)
    }

    /**
     * This table's rows from [records] (the header first), [batch] at a time: [keep] picks those to write from each. Null
     * for an empty file. Rows [keep] leaves out count as present, as [backfill] counts them.
     */
    private suspend fun <T> CsvTable<T>.stream(records: CsvReader, keep: (List<Parsed<T>>) -> List<Parsed<T>>): Outcome? {
        val index = header(records) ?: return null
        val zone = ZoneId.systemDefault()
        var stored = 0
        var present = 0
        var bad = 0
        val rows = ArrayList<Parsed<T>>(batch)
        suspend fun flush() {
            if (rows.isEmpty()) return
            val fresh = keep(rows)
            if (fresh.isNotEmpty()) write(db.healthDao(), fresh.map { it.value })
            stored += fresh.size
            present += rows.size - fresh.size
            rows.clear()
        }
        while (true) {
            val cells = records.next() ?: break
            parsed(index, cells, zone)?.let { rows += it } ?: bad++
            if (rows.size >= batch) flush()
        }
        flush()
        return Outcome(stored, present, bad)
    }

    /** A file's header as column name to position, or null for an empty file. */
    private fun header(records: CsvReader): Map<String, Int>? =
        records.next()?.withIndex()?.associate { (i, name) -> name to i }

    /** One record of this table, or null when it can't be read. */
    private fun <T> CsvTable<T>.parsed(index: Map<String, Int>, record: List<String>, zone: ZoneId): Parsed<T>? = runCatching {
        val cells = CsvRow(index, record, zone)
        val day = key.day
        val time = if (day != null) DayKey.of(cells.date(day)) else cells.long(key.epoch)
        val id = (key.unique ?: key.part)?.let { cells.string(it.csv) }
        Parsed(time, key.span?.let { cells.long(it.epoch) } ?: time, id, parse(cells), day?.let(cells::legacy))
    }.getOrNull()

    /**
     * A day file's rows, one per day (and tag): an older backup's local midnights from two zones can be one date
     * here, and the row kept is picked by the migration's rules ([DayKeyMerge]), not by the order of the file.
     */
    private fun <T> oneADay(key: Key, rows: List<Parsed<T>>, zone: ZoneId): List<Parsed<T>> {
        val rule = DayKeyMerge.TABLES.first { it.name == key.table }.rule
        return rows.groupBy { it.time to it.id }.values.map { same ->
            if (same.size == 1) return@map same.single()
            val midnight = DayKey.legacyMidnight(DayKey.date(same.first().time), zone)
            val old = { p: Parsed<T> -> p.legacy ?: midnight }
            val keep = if (rule == DayKeyMerge.Rule.UNION) DayKeyMerge.unionKeep(same.map(old))
            else DayKeyMerge.winner(rule, same.map { DayKeyMerge.Candidate(old(it), DayKeyMerge.valueOf(it.value)) }, midnight)
            same.first { old(it) == keep }
        }
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
