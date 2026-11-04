package org.openstrap.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.Workout
import java.io.StringWriter
import org.openstrap.app.export.CsvExporter.Companion.writeCsv

class CsvImportTest {
    private fun table(file: String) = CSV_TABLES.first { it.file == file }

    @Suppress("UNCHECKED_CAST")
    private fun <T> parse(file: String, header: String, line: String): T {
        val index = CsvImporter.parseLine(header).withIndex().associate { (i, n) -> n to i }
        return table(file).parse(CsvRow(index, CsvImporter.parseLine(line))) as T
    }

    @Test fun `quoted cells keep their commas and quotes`() {
        assertEquals(listOf("a", "b,c", "say \"hi\"", ""), CsvImporter.parseLine("a,\"b,c\",\"say \"\"hi\"\"\","))
    }

    @Test fun `every table's export header parses its own rows back`() {
        for (t in CSV_TABLES) {
            val cells = CsvImporter.parseLine(t.header)
            assertEquals("${t.file} has duplicate columns", cells.size, cells.toSet().size)
        }
    }

    @Test fun `sleep from an older export without day_epoch gets the day you woke up`() {
        val s: SleepSession = parse(
            "sleep_session.csv",
            "start,end,start_epoch,end_epoch,nap,score,avg_hr,deep_min,light_min,rem_min,awake_min,wake_count",
            "2026-10-17T19:20:00Z,2026-10-18T03:13:00Z,1792264800,1792293180,false,80,58,90,200,60,20,4",
        )
        assertEquals(1792264800L, s.start)
        assertEquals(80, s.score)
        assertTrue(s.day > 0)
        assertTrue(s.day <= s.end && s.end - s.day < 86_400)
    }

    @Test fun `sleep stages read their ISO times and stage names`() {
        val st: SleepStage = parse("sleep_stage.csv", "session_start_epoch,start,end,stage", "1792264800,2026-10-17T19:20:00Z,2026-10-17T19:45:00Z,deep")
        assertEquals(1792264800L, st.start)
        assertEquals(1792266300L, st.end)
        assertEquals(SleepStage.DEEP, st.stage)
    }

    @Test fun `PAI's tomorrow total round-trips, and is null from an older export`() {
        val t = table("pai.csv")
        for (p in listOf(PaiSample(1792300000, 12.5f, 87.25f, 58, 188, tomorrowTotal = 80.75f), PaiSample(1792300060, 3f, 90f, 57, 187))) {
            @Suppress("UNCHECKED_CAST")
            val row = (t as CsvTable<PaiSample>).row(p)
            val out = StringWriter().also { it.writeCsv(t.header, listOf(row)) }.toString().lines()
            assertEquals(p, parse<PaiSample>("pai.csv", out[0], out[1]))
        }
        val old: PaiSample = parse("pai.csv", "time,epoch,daily,total,resting_hr,max_hr", "2026-10-17T19:20:00Z,1792264800,1.5,40,60,185")
        assertNull(old.tomorrowTotal)
    }

    @Test fun `a workout round-trips through its row`() {
        val w = Workout(1792300000, 1792303000, 1, 3000, 550, 140, 165, 98, 3.4f, 1.6f, 120, 48, null, subtype = 1)
        val t = table("workout.csv")
        @Suppress("UNCHECKED_CAST")
        val row = (t as CsvTable<Workout>).row(w)
        val out = StringWriter().also { it.writeCsv(t.header, listOf(row)) }.toString().lines()
        val back: Workout = parse("workout.csv", out[0], out[1])
        assertEquals(w, back)
        assertNull(back.steps)
    }

    @Test fun `every table's key column is in its header`() {
        for (t in CSV_TABLES) for (column in listOfNotNull(t.key.epoch, t.key.span?.epoch, t.key.unique?.csv, t.key.part?.csv)) {
            assertTrue("${t.file} lacks $column", column in CsvImporter.parseLine(t.header))
        }
    }
}
