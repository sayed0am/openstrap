package org.openstrap.app.export

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.PersonalBest
import org.openstrap.app.data.db.WeekResult
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * A backup of a range picks weeks and personal bests the way it picks every other row (n-data2 item 13): a week by its
 * first day's local midnight, a best by the moment it was set, not either as a UTC midnight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CsvRangeDaysTest {
    private val zone = ZoneId.of("Asia/Kathmandu")

    @get:Rule val zoneRule = ZoneRule(zone)

    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    private val monday = LocalDate.of(2026, 1, 12)

    private fun rows(zip: ByteArray, file: String): List<String> = ZipInputStream(zip.inputStream()).use { z ->
        generateSequence { z.nextEntry }.first { it.name == file }
        z.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.drop(1)
    }

    private suspend fun export(from: Long, to: Long) = ByteArrayOutputStream().also { CsvExporter(dao).exportZip(it, from, to) }.toByteArray()

    @Test fun `a week is in a range by its first day's local midnight`() = runBlocking {
        dao.upsertWeekResults(listOf(WeekResult(monday.minusDays(7).toEpochDay(), 3, 4, true), WeekResult(monday.toEpochDay(), 3, 1, false)))
        // Monday's first hour in Kathmandu: its midnight is in, the UTC midnight of the same date (05:45 there) isn't.
        val midnight = monday.atStartOfDay(zone).toEpochSecond()
        assertEquals(listOf("${monday.toEpochDay()},3,1,false"), rows(export(midnight, midnight + 3600), "week_result.csv"))
        // The hour before it holds no week's first day, though the week before's UTC midnight was long gone.
        assertEquals(emptyList<String>(), rows(export(midnight - 3600, midnight - 1), "week_result.csv"))
    }

    @Test fun `a personal best is in a range by the moment it was set`() = runBlocking {
        val set = monday.atTime(10, 0).atZone(zone).toEpochSecond()
        dao.insertPersonalBests(listOf(PersonalBest("run_5k", 1500.0, set, set)))
        val midnight = monday.atStartOfDay(zone).toEpochSecond()
        assertEquals(1, rows(export(midnight, midnight + 86_399), "personal_best.csv").size)
        assertEquals(0, rows(export(midnight + 11 * 3600, midnight + 86_399), "personal_best.csv").size)
    }
}
