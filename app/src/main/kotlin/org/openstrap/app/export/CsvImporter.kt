package org.openstrap.app.export

import androidx.room.withTransaction
import org.openstrap.app.data.db.OpenStrapDatabase
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * Restores a zip made by [CsvExporter] into what the phone is missing. Heart rate and activity minutes ([MINUTES]) come
 * back wherever the phone has no row for that minute, and a minute it has keeps the strap's value, so a day the strap
 * only partly re-sent is filled in. Every other table only fills the local days before the first day the phone has a
 * row on: that day and every day after it are the strap's, gaps included. A sleep or workout that overlaps one on the phone is
 * skipped too, and stages and workout heart rate come back only with their sleep or workout. A strap workout deleted in
 * OpenStrap, on the phone or by the backup, stays deleted: its row and the sport effect deleted with it are left out, so
 * its heart rate is too. Restoring twice changes nothing. Runs as one transaction, so a zip that fails part way changes
 * nothing. Files this version doesn't know are skipped, and a backup from before deleted workouts were kept restores as
 * it did. The settings file isn't written here: [Result.settings] carries it.
 */
class CsvImporter(private val db: OpenStrapDatabase, private val zone: ZoneId = ZoneId.systemDefault()) {

    /**
     * What one restore did: rows added per file, rows left out as the strap's days have them, and rows unreadable.
     * [settings] is the zip's [SettingsBackup.FILE] as is, for the caller to restore; null in a zip from before it.
     */
    data class Result(val rows: Map<String, Int>, val present: Int, val skipped: Int, val settings: String? = null) {
        val total get() = rows.values.sum()
    }

    private class Outcome(val stored: Int, val present: Int, val bad: Int)

    suspend fun importZip(input: InputStream): Result = db.withTransaction {
        val tables = CSV_TABLES.associateBy { it.file }
        val counts = linkedMapOf<String, Int>()
        // Starts of the sleeps and workouts this restore added: only their stages and heart rate come with them.
        val added = mutableMapOf<String, Set<Long>>()
        // Child tables wait until every parent is in, whatever order the zip lists them in, and the tables a deleted
        // workout is left out of wait until every deleted workout is in.
        val children = mutableListOf<Pair<CsvTable<*>, List<String>>>()
        val deletable = mutableListOf<Pair<CsvTable<*>, List<String>>>()
        var present = 0
        var skipped = 0
        var settings: String? = null
        fun count(table: CsvTable<*>, o: Outcome) {
            counts[table.file] = o.stored; present += o.present; skipped += o.bad
        }
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.substringAfterLast('/') == SettingsBackup.FILE) {
                    settings = zip.readBytes().toString(Charsets.UTF_8)
                    continue
                }
                val table = tables[entry.name.substringAfterLast('/')] ?: continue
                // Only this entry's bytes, without closing the zip (a Reader's readLines would close it).
                val lines = zip.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
                if (lines.isEmpty()) continue
                when {
                    table.key.parent != null -> children += table to lines
                    table.key.table in DELETABLE -> deletable += table to lines
                    else -> count(table, table.backfill(lines, added))
                }
            }
        }
        val deleted = db.healthDao().deletedWorkoutsAll()
        val gone = mapOf(
            "workout" to deleted.mapTo(HashSet()) { it.start },
            "sport_effect" to deleted.mapNotNullTo(HashSet()) { it.effectEnd },
        )
        for ((table, lines) in deletable) count(table, table.backfill(lines, added, gone[table.key.table].orEmpty()))
        for ((table, lines) in children) count(table, table.backfill(lines, added))
        // Throwing rolls back, so a zip that isn't ours leaves the database untouched.
        if (counts.isEmpty()) throw IllegalArgumentException(tr(R.string.csv_importer_this_isn_t_an))
        Result(counts, present, skipped, settings)
    }

    /** Writes the rows of [lines] that fall before the phone's first day for this table, less those timed at [gone]. */
    private suspend fun <T> CsvTable<T>.backfill(lines: List<String>, added: MutableMap<String, Set<Long>>, gone: Set<Long> = emptySet()): Outcome {
        val index = parseLine(lines.first()).withIndex().associate { (i, name) -> name to i }
        var bad = 0
        val rows = mutableListOf<Triple<Long, Long, T>>()
        for (line in lines.drop(1)) {
            runCatching {
                val cells = CsvRow(index, parseLine(line))
                val time = cells.long(key.epoch)
                if (time in gone) return@runCatching
                rows += Triple(time, key.span?.let { cells.long(it.epoch) } ?: time, parse(cells))
            }.onFailure { bad++ }
        }
        val fresh = if (rows.isEmpty()) rows else {
            val from = rows.minOf { it.first }
            val to = rows.maxOf { it.second }
            val parent = key.parent
            val span = key.span
            when {
                parent != null -> added[parent].orEmpty().let { starts -> rows.filter { it.first in starts } }
                else -> {
                    val first = query("SELECT MIN(${key.column}) FROM ${key.table}") { c -> if (c.isNull(0)) null else c.getLong(0) }.single()
                    val cutoff = first?.let { dayStart(it) } ?: Long.MAX_VALUE
                    // A deletion is the user's, not the strap's history: every one comes back, less those already here.
                    val before = when (key.table) {
                        in WHOLE -> {
                            val have = query("SELECT ${key.column} FROM ${key.table}") { it.getLong(0) }.toHashSet()
                            rows.filter { it.first !in have }
                        }
                        // One row a minute, keyed by it: a minute the phone has keeps the strap's value, any other
                        // minute comes back, so a day the strap only partly re-sent is filled in too.
                        in MINUTES -> {
                            val have = query("SELECT ${key.column} FROM ${key.table} WHERE ${key.column} BETWEEN ? AND ?", from, to) { it.getLong(0) }.toHashSet()
                            rows.filter { it.first !in have }
                        }
                        else -> rows.filter { it.first < cutoff }
                    }
                    if (span == null) before else {
                        // A night that starts the evening before the strap's first day can still be one it has.
                        val have = query("SELECT ${key.column}, ${span.column} FROM ${key.table} WHERE ${key.column} <= ? AND ${span.column} >= ?", to, from) {
                            it.getLong(0) to it.getLong(1)
                        }
                        before.filter { (start, end) -> have.none { (s, e) -> s == start || (s < end && e > start) } }
                    }
                }
            }
        }
        if (key.span != null) added[key.table] = fresh.mapTo(HashSet()) { it.first }
        fresh.map { it.third }.chunked(2_000).forEach { write(db.healthDao(), it) }
        return Outcome(fresh.size, rows.size - fresh.size, bad)
    }

    private fun dayStart(epoch: Long): Long = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()

    private fun <R> query(sql: String, vararg args: Any, read: (android.database.Cursor) -> R): List<R> =
        db.openHelper.writableDatabase.query(sql, args).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    companion object {
        /** Tables whose rows a deleted strap workout takes with it, by its start (workout) or its effect's end. */
        private val DELETABLE = setOf("workout", "sport_effect")

        /** Tables restored whole rather than only before the phone's first day: what the user did, which the strap can't re-send. */
        private val WHOLE = setOf("workout_deleted")

        /** Minute tables, keyed by the minute: restored wherever the phone has no row for it, not only before its first day. */
        private val MINUTES = setOf("heart_rate", "activity_minute", "activity_extended")

        /** One CSV line into cells: commas separate, double quotes wrap a cell and "" inside is a quote. */
        fun parseLine(line: String): List<String> {
            val cells = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { cells += cell.toString(); cell.clear() }
                    else -> cell.append(c)
                }
                i++
            }
            cells += cell.toString()
            return cells
        }
    }
}
