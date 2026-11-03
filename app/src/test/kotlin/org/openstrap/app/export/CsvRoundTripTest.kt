package org.openstrap.app.export

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.DayKeyMerge
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.export.CsvExporter.Companion.writeCsv
import org.openstrap.app.testing.SourceTree
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.lang.reflect.Modifier

/**
 * Every table in the backup, one row each with every column set, through CSV and back (audit test 1). The rows are
 * written from the newest Room schema, so a column added to an entity and not to its CSV table fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CsvRoundTripTest {
    private val source = inMemoryDb()
    private val target = inMemoryDb()

    @After fun close() {
        source.close()
        target.close()
    }

    private class Column(val field: String, val name: String, val affinity: String)

    /** The newest exported schema's columns, by table. */
    private val schema: Map<String, List<Column>> by lazy {
        val newest = SourceTree.schemas.listFiles()!!.filter { it.extension == "json" }.maxBy { it.nameWithoutExtension.toInt() }
        val entities = JSONObject(newest.readText()).getJSONObject("database").getJSONArray("entities")
        (0 until entities.length()).associate { i ->
            val e = entities.getJSONObject(i)
            val fields = e.getJSONArray("fields")
            e.getString("tableName") to (0 until fields.length()).map { j ->
                val f = fields.getJSONObject(j)
                Column(f.getString("fieldPath"), f.getString("columnName"), f.getString("affinity"))
            }
        }
    }

    /** Each day-keyed table's day column. */
    private val dayColumns = DayKeyMerge.TABLES.associate { it.name to it.column } + ("sleep_session" to "day")

    /** A value for every column, none of them a default: the CSV must carry each one for the row to come back equal. */
    private fun value(table: String, column: Column): Any = when (column.affinity) {
        // A deleted workout that names the row in workout, or its sport effect, would keep them out of the restore.
        "INTEGER" -> when {
            table == "workout_deleted" -> 3L
            // A stored day is a date's key, a whole number of days, and a sleep's 0 means none. Two days in, so its
            // local midnight is after 1970 began in every zone, the start of the read.
            column.name == dayColumns[table] -> 2 * 86_400L
            else -> 2L
        }
        "REAL" -> 2.5
        "BLOB" -> byteArrayOf(1, 0xab.toByte())
        // A comma, a quote and a line break: the cell has to be quoted and read back whole.
        else -> "a, \"b\"\nc"
    }

    private fun seed(db: OpenStrapDatabase) {
        for (t in CSV_TABLES) {
            val columns = schema.getValue(t.key.table)
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO ${t.key.table} (${columns.joinToString { "`${it.name}`" }}) VALUES (${columns.joinToString { "?" }})",
                columns.map { value(t.key.table, it) }.toTypedArray(),
            )
        }
    }

    private suspend fun rows(db: OpenStrapDatabase, t: CsvTable<*>): List<Any?> = t.read(db.healthDao(), 0, Long.MAX_VALUE / 2)

    /** An entity's fields by name, arrays as lists so they compare by content, less those left out of the CSV on purpose. */
    private fun fields(table: String, row: Any?): Map<String, Any?> = row!!.javaClass.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.name !in LEFT_OUT[table].orEmpty() }
        .associate { f ->
            f.isAccessible = true
            f.name to f.get(row).let { if (it is ByteArray) it.toList() else it }
        }

    /** Sets every Boolean field of [row] false, as the seeded row has them true. */
    private fun flipBooleans(row: Any) {
        for (f in row.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            when (f.type) {
                Boolean::class.javaPrimitiveType -> f.setBoolean(row, false)
                java.lang.Boolean::class.java -> f.set(row, false)
            }
        }
    }

    @Test fun `every table of the schema is backed up, or left out on purpose`() {
        val backedUp = CSV_TABLES.map { it.key.table }.toSet()
        assertEquals(schema.keys - NOT_BACKED_UP, backedUp)
        assertEquals(CSV_TABLES.size, backedUp.size)
    }

    @Test fun `each table's CSV columns carry every field of its entity`() = runTest {
        seed(source)
        for (t in CSV_TABLES) {
            @Suppress("UNCHECKED_CAST")
            val table = t as CsvTable<Any?>
            val row = rows(source, t).single()!!
            val entityFields = row.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
            for (column in schema.getValue(t.key.table)) assertTrue("${t.file}: ${column.field} isn't a field", column.field in entityFields)
            // Booleans both ways, so one whose default is true is caught too.
            for (pass in 0..1) {
                if (pass == 1) flipBooleans(row)
                val csv = StringWriter().also { it.writeCsv(t.header, listOf(table.row(row))) }.toString()
                val records = CsvReader(csv.reader()).all()
                assertEquals("${t.file}: one header and one row", 2, records.size)
                val index = records[0].withIndex().associate { (i, n) -> n to i }
                assertEquals(t.file, fields(t.key.table, row), fields(t.key.table, table.parse(CsvRow(index, records[1]))))
            }
        }
    }

    @Test fun `a backup of every table restores into an empty database row for row`() = runTest {
        seed(source)
        val zip = ByteArrayOutputStream().also { CsvExporter(source.healthDao()).exportZip(it) }.toByteArray()
        val result = CsvImporter(target).importZip(ByteArrayInputStream(zip))
        assertEquals(0, result.skipped)
        for (t in CSV_TABLES) {
            val before = rows(source, t)
            assertEquals("${t.file} rows", 1, before.size)
            assertEquals(t.file, before.map { fields(t.key.table, it) }, rows(target, t).map { fields(t.key.table, it) })
        }
    }

    companion object {
        /** Tables no backup carries: raw_record is undecoded strap records, kept to parse later and never read. */
        private val NOT_BACKED_UP = setOf("raw_record")

        /** Fields a table's CSV leaves out on purpose, by table. */
        private val LEFT_OUT = mapOf(
            // Room numbers saved workouts itself; created_epoch is their key in the backup.
            "saved_workout" to setOf("id"),
            // Only set while a workout records, and a recording workout isn't exported.
            "app_workout" to setOf("pausedAt"),
        )
    }
}
