package org.openstrap.app.data.db

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.openstrap.app.data.DayKey
import org.openstrap.app.data.Days
import java.time.ZoneId

/**
 * Version 27 to 28, item 17: every stored day becomes its calendar date's UTC midnight ([DayKey]) instead of the local
 * midnight of the zone it was written in, so a day keeps its date when the phone changes zone. Each old key becomes its
 * date in [zone] ([DayKey.fromLegacy]), the phone's zone when the database first opens after the upgrade (user,
 * 2026-11-03). Two old keys that become one date (rows written in two zones for one date) keep one row by
 * [DayKeyMerge]. Columns and indices stay as they are, so only values change. Moments are left alone. A class so a
 * test can give the zone, and so it can be renumbered on its own. Runs in Room's migration transaction: a failure
 * changes nothing.
 */
@Suppress("MagicNumber") // The versions it runs between are what the migration is.
class DayKeyMigration(private val zone: () -> ZoneId) : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val zone = zone()
        val notes = mutableListOf<String>()
        for (t in DayKeyMerge.TABLES) {
            val (moved, merged) = convertKeyed(db, t, zone)
            notes += "${t.name} $moved moved, $merged merged"
        }
        notes += "sleep_session ${convertSleep(db, zone)} moved"
        Log.i(TAG, "day keys in $zone: ${notes.joinToString()}")
    }

    /** Each distinct non-zero key of [column] in [table], with its new key. */
    private fun keyMap(db: SupportSQLiteDatabase, table: String, column: String, zone: ZoneId): Map<Long, Long> =
        db.query("SELECT DISTINCT `$column` FROM `$table` WHERE `$column` != 0").use { c ->
            buildMap { while (c.moveToNext()) c.getLong(0).let { put(it, DayKey.of(DayKey.fromLegacy(it, zone))) } }
        }

    /**
     * A table keyed by its day: rows that become one date are merged first, then every row whose key changes is taken
     * out and put back under its new key. Updating in place could clash for a moment with a row not yet moved. Returns
     * the rows moved and the rows merged away.
     */
    private fun convertKeyed(db: SupportSQLiteDatabase, t: DayKeyMerge.Table, zone: ZoneId): Pair<Int, Int> {
        val map = keyMap(db, t.name, t.column, zone)
        var merged = 0
        for ((new, olds) in map.keys.groupBy { map.getValue(it) }) {
            if (olds.size < 2) continue
            merged += if (t.rule == DayKeyMerge.Rule.UNION) mergeTags(db, t, olds) else mergeRows(db, t, olds, new, zone)
        }
        val changed = map.filter { (old, new) -> old != new }.keys
        if (changed.isEmpty()) return 0 to merged
        val cols = "`${t.column}`"
        db.execSQL("CREATE TEMP TABLE day_map (old INTEGER PRIMARY KEY NOT NULL, new INTEGER NOT NULL)")
        for (old in changed) db.execSQL("INSERT INTO day_map (old, new) VALUES (?, ?)", arrayOf<Any>(old, map.getValue(old)))
        db.execSQL("CREATE TEMP TABLE day_moved AS SELECT * FROM `${t.name}` WHERE $cols IN (SELECT old FROM day_map)")
        db.execSQL("DELETE FROM `${t.name}` WHERE $cols IN (SELECT old FROM day_map)")
        db.execSQL("UPDATE day_moved SET $cols = (SELECT new FROM day_map WHERE old = day_moved.$cols)")
        val moved = count(db, "day_moved")
        db.execSQL("INSERT INTO `${t.name}` SELECT * FROM day_moved")
        db.execSQL("DROP TABLE day_moved")
        db.execSQL("DROP TABLE day_map")
        return moved to merged
    }

    /** Keeps one of the rows at [olds] by the table's rule and deletes the rest. Returns how many went. */
    private fun mergeRows(db: SupportSQLiteDatabase, t: DayKeyMerge.Table, olds: List<Long>, new: Long, zone: ZoneId): Int {
        val candidates = olds.map { old ->
            val value = t.value?.let { v ->
                db.query("SELECT `$v` FROM `${t.name}` WHERE `${t.column}` = ?", arrayOf<Any>(old)).use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
            } ?: 0L
            DayKeyMerge.Candidate(old, value)
        }
        val keep = DayKeyMerge.winner(t.rule, candidates, Days.start(DayKey.date(new), zone))
        val gone = olds.filter { it != keep }
        for (old in gone) db.execSQL("DELETE FROM `${t.name}` WHERE `${t.column}` = ?", arrayOf<Any>(old))
        return gone.size
    }

    /** Day tags at [olds], one date: each tag keeps one row ([DayKeyMerge.unionKeep]). Returns how many went. */
    private fun mergeTags(db: SupportSQLiteDatabase, t: DayKeyMerge.Table, olds: List<Long>): Int {
        val holders = HashMap<String, MutableList<Long>>()
        for (old in olds) {
            db.query("SELECT tag FROM `${t.name}` WHERE `${t.column}` = ?", arrayOf<Any>(old)).use { c ->
                while (c.moveToNext()) holders.getOrPut(c.getString(0)) { mutableListOf() } += old
            }
        }
        var gone = 0
        for ((tag, at) in holders) {
            val keep = DayKeyMerge.unionKeep(at)
            for (old in at.filter { it != keep }) {
                db.execSQL("DELETE FROM `${t.name}` WHERE `${t.column}` = ? AND tag = ?", arrayOf<Any>(old, tag))
                gone++
            }
        }
        return gone
    }

    /**
     * Sleep sessions keep their rows: the day isn't unique, so one update moves every key at once (each row reads its
     * own old key). Rows from before v2 keep 0. Two strap days that become one date then share a key, which only
     * happens across 12 hours or more of travel, and only the strap's re-send of that date reads it.
     */
    private fun convertSleep(db: SupportSQLiteDatabase, zone: ZoneId): Int {
        val changed = keyMap(db, "sleep_session", "day", zone).filter { (old, new) -> old != new }
        if (changed.isEmpty()) return 0
        db.execSQL("CREATE TEMP TABLE day_map (old INTEGER PRIMARY KEY NOT NULL, new INTEGER NOT NULL)")
        for ((old, new) in changed) db.execSQL("INSERT INTO day_map (old, new) VALUES (?, ?)", arrayOf<Any>(old, new))
        val moved = "day IN (SELECT old FROM day_map)"
        val rows = db.query("SELECT COUNT(*) FROM sleep_session WHERE $moved").use { c -> c.moveToFirst(); c.getInt(0) }
        db.execSQL("UPDATE sleep_session SET day = (SELECT new FROM day_map WHERE old = sleep_session.day) WHERE $moved")
        db.execSQL("DROP TABLE day_map")
        return rows
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val TAG = "DayKeyMigration"
    }
}
