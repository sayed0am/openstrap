package org.openstrap.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.export.CsvExporter.Companion.writeCsv
import java.io.StringWriter

class CsvReaderTest {
    private fun read(csv: String) = CsvReader(csv.reader()).all()

    @Test fun `a quoted cell keeps its line breaks, commas and quotes`() {
        val csv = "id,note\n1,\"first line\nsecond, with \"\"quotes\"\"\"\n2,plain\n"
        assertEquals(listOf(listOf("id", "note"), listOf("1", "first line\nsecond, with \"quotes\""), listOf("2", "plain")), read(csv))
    }

    @Test fun `CRLF and CR end a record, blank lines are skipped, and an empty last cell is kept`() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", ""), listOf("2", "x")), read("a,b\r\n\r\n1,\r2,x"))
        assertEquals(emptyList<List<String>>(), read(""))
    }

    @Test fun `whatever the exporter writes reads back cell for cell`() {
        val awkward = listOf("", "plain", "a,b", "say \"hi\"", "two\nlines", "cr\rhere", "crlf\r\nhere", "\"", ",", "\n")
        val rows = awkward.map { listOf<Any?>(it, 7, null) }
        val csv = StringWriter().also { it.writeCsv("text,n,none", rows) }.toString()
        val back = read(csv)
        assertEquals(listOf("text", "n", "none"), back.first())
        // A row of empty cells is still a record: only an empty line is skipped.
        assertEquals(awkward.map { listOf(it, "7", "") }, back.drop(1))
    }

    @Test fun `a phone workout from a backup before the strap's effect columns gets the entity's defaults`() {
        val header = "start,start_epoch,end_epoch,sport_type,source,aerobic_te,recovery_minutes"
        val records = read("$header\n2026-10-17T19:20:00Z,1792264800,1792268400,1,0,3.1,600\n")
        val index = records[0].withIndex().associate { (i, n) -> n to i }
        val w = CSV_TABLES.first { it.file == "app_workout.csv" }.parse(CsvRow(index, records[1])) as AppWorkout
        assertEquals(3.1f, w.aerobicTrainingEffect)
        assertEquals(null, w.anaerobicTrainingEffect)
        assertFalse(w.effectFromStrap)
        assertEquals(null, w.ownRecoveryMinutes)
    }
}
