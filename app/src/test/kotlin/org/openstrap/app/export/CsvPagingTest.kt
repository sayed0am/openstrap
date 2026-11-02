package org.openstrap.app.export

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * The backup reads its biggest tables by keyset pages ([Paged]): page by page, the rows come out the same and in the
 * same order as one read of the range, none skipped or repeated across a page's edge, and the range's ends kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CsvPagingTest {
    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    private fun table(file: String) = CSV_TABLES.single { it.file == file }

    private suspend fun pages(file: String, from: Long, to: Long, size: Int): List<List<Any?>> {
        val out = mutableListOf<List<Any?>>()
        (table(file).read as Paged<*>).forEachPage(dao, from, to, size) { out += it }
        return out
    }

    @Test fun `a timestamp table's pages are its range in order`() = runTest {
        dao.insertHeartRate((1..10).map { HeartRateSample(it * 60L, 60 + it) })
        val pages = pages("heart_rate.csv", 120, 540, 3)
        assertEquals(listOf(3, 3, 2), pages.map { it.size })
        assertEquals(dao.heartRateOnce(120, 540), pages.flatten())
        // A range that ends on a page's edge ends with an empty read, not a repeat.
        assertEquals(listOf(3, 3), pages("heart_rate.csv", 60, 360, 3).map { it.size })
    }

    @Test fun `a workout's samples page across workouts without a gap`() = runTest {
        // Three workouts whose samples share timestamps, so the key has to be the pair.
        val samples = listOf(1000L, 2000L, 3000L, 4000L).flatMap { start -> (0 until 4).map { WorkoutHeartRate(start, 5000L + it, 120 + it) } }
        dao.insertWorkoutHeartRate(samples)
        val pages = pages("workout_hr.csv", 2000, 3000, 3)
        assertEquals(samples.filter { it.workoutStart in 2000..3000 }, pages.flatten())
        dao.insertAppWorkoutHeartRate(samples.map { AppWorkoutHeartRate(it.workoutStart, it.timestamp, it.bpm) })
        assertEquals(dao.appWorkoutHeartRateAll(), pages("app_workout_hr.csv", 0, Long.MAX_VALUE / 2, 5).flatten())
    }

    @Test fun `the export writes every row of a table bigger than a page`() = runTest {
        val n = CSV_PAGE * 2 + 1
        dao.insertHeartRate((0 until n).map { HeartRateSample(it * 60L, 70) })
        val zip = ByteArrayOutputStream().also { CsvExporter(dao).exportZip(it) }.toByteArray()
        val lines = ZipInputStream(zip.inputStream()).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "heart_rate.csv" }
            z.bufferedReader().readLines()
        }
        assertEquals(n + 1, lines.size)
        assertEquals((0 until n).map { it * 60L }, lines.drop(1).map { it.split(',')[1].toLong() })
    }
}
