package org.openstrap.app.export

import org.openstrap.app.data.db.HealthDao
import java.io.OutputStream
import java.io.Writer
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes each table of user data ([CSV_TABLES]) as a CSV file inside one zip. Timestamps are ISO-8601 UTC plus raw epoch seconds.
 * [settings], when given, goes in beside them as [SettingsBackup.FILE]. [CsvImporter] reads the zip back.
 */
class CsvExporter(private val dao: HealthDao) {

    suspend fun exportZip(out: OutputStream, from: Long = 0, to: Long = Long.MAX_VALUE / 2, settings: String? = null) {
        ZipOutputStream(out).use { zip ->
            val w = zip.writer()
            for (table in CSV_TABLES) {
                zip.putNextEntry(ZipEntry(table.file))
                table.writeTo(w, from, to)
                w.flush()
                zip.closeEntry()
            }
            if (settings != null) {
                zip.putNextEntry(ZipEntry(SettingsBackup.FILE))
                w.write(settings)
                w.flush()
                zip.closeEntry()
            }
        }
    }

    /** The table's file, written a page at a time as the rows are read ([CsvTable.forEachPage]). */
    private suspend fun <T> CsvTable<T>.writeTo(w: Writer, from: Long, to: Long) {
        w.append(header).append('\n')
        forEachPage(dao, from, to) { page -> w.writeRows(page.map(row)) }
    }

    companion object {
        fun iso(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds).toString()

        fun stageName(stage: Int) = when (stage) {
            0 -> "awake"; 1 -> "light"; 2 -> "deep"; 3 -> "rem"; else -> "unknown"
        }

        internal fun Writer.writeCsv(header: String, rows: List<List<Any?>>) {
            append(header).append('\n')
            writeRows(rows)
        }

        private fun Writer.writeRows(rows: List<List<Any?>>) {
            for (row in rows) {
                row.joinTo(this, ",") { v -> v?.toString()?.let(::escape) ?: "" }
                append('\n')
            }
        }

        /** Quotes a cell that holds a comma, a quote or a line break, as [CsvReader] reads it back. */
        private fun escape(s: String): String =
            if (s.any { it in SPECIAL }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        /** The characters that make a cell need quotes. */
        private const val SPECIAL = ",\"\n\r"
    }
}
