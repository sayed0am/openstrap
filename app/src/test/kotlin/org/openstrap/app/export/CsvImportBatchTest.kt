package org.openstrap.app.export

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Restore streams its big tables (data integrity item 23.6): a table is written a batch at a time as the zip is read,
 * not held whole in memory first, and a child table read before its parent still comes out the same.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CsvImportBatchTest {
    private val from = inMemoryDb()
    private val to = inMemoryDb()

    @After fun close() {
        from.close(); to.close()
    }

    /** [from]'s export, its entries in [order] first, stored without compression so reading it takes many small reads. */
    private suspend fun zip(order: List<String> = emptyList()): ByteArray {
        val packed = ByteArrayOutputStream().also { CsvExporter(from.healthDao()).exportZip(it) }.toByteArray()
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(packed.inputStream()).use { z -> while (true) entries[(z.nextEntry ?: break).name] = z.readBytes() }
        val sorted = order.filter { it in entries } + entries.keys.filter { it !in order }
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.setLevel(0)
                for (name in sorted) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(entries.getValue(name))
                    z.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun count(table: String): Long = to.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
        c.moveToFirst()
        c.getLong(0)
    }

    @Test fun `a big table is written a batch at a time as it is read`() = runBlocking {
        val n = 1000
        from.healthDao().insertHeartRate((0 until n).map { HeartRateSample(1_790_000_000L + it * 60L, 60 + it % 40) })
        val bytes = zip()
        // What the restore has written, each time it reads more of the zip. It reads on the thread that holds its
        // transaction, so its own rows are visible there.
        val seen = mutableListOf<Long>()
        val input: InputStream = object : FilterInputStream(bytes.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                seen += count("heart_rate")
                return super.read(b, off, len)
            }
        }
        val result = CsvImporter(to, batch = 100).importZip(input)
        assertEquals(n, result.rows["heart_rate.csv"])
        assertEquals(n.toLong(), count("heart_rate"))
        // Some rows were in while the rest were still being read: read whole first, the count would jump from 0 to all.
        assertTrue(seen.distinct().toString(), seen.any { it in 1 until n })
    }

    @Test fun `stages read before their sleep come in with it`() = runBlocking {
        val sleeps = (0 until 3).map { SleepSession(1_790_000_000L + it * 86_400L, 1_790_000_000L + it * 86_400L + 8 * 3600) }
        from.healthDao().insertSleepSessions(sleeps)
        from.healthDao().insertSleepStages(sleeps.map { SleepStage(it.start, it.end, it.start, SleepStage.LIGHT) })
        val result = CsvImporter(to, batch = 2).importZip(zip(order = listOf("sleep_stage.csv")).inputStream())
        assertEquals(3, result.rows["sleep_session.csv"])
        assertEquals(3, result.rows["sleep_stage.csv"])
        assertEquals(3L, count("sleep_stage"))
    }
}
