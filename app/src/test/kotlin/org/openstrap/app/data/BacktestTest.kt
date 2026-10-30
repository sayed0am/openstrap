package org.openstrap.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.CheckInRow
import org.openstrap.app.data.db.DayTagRow
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.StrapReadinessRow
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.export.CsvImporter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** The backtest harness against a real database: one row per day, with the strap's score, check-in and tags beside ours. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BacktestTest {
    private val zone = ZoneId.of("UTC")
    private val first = LocalDate.of(2026, 9, 1)
    private lateinit var db: OpenStrapDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    /** [nights] nights of sleep 23:00-07:00 with heart rate and HRV samples through each. */
    private suspend fun fill(nights: Int) {
        val d = db.healthDao()
        for (n in 0 until nights) {
            val morning = first.plusDays(n.toLong()).atStartOfDay(zone).toEpochSecond()
            val start = morning - 3600
            val end = morning + 7 * 3600
            d.insertSleepSessions(listOf(SleepSession(start, end, morning, score = 80, deepMinutes = 90, lightMinutes = 270, remMinutes = 100, awakeMinutes = 20)))
            d.insertSleepStages(listOf(SleepStage(start, end, start, SleepStage.LIGHT)))
            d.insertHeartRate((0 until 8 * 60).map { HeartRateSample(start + it * 60L, 52 + (it / 30) % 4 + n % 3) })
            d.insertHrv((0 until 16).map { HrvSample(start + it * 1800L, 45 + (n * 7 + it) % 10) })
        }
    }

    @Test fun `replays every day with the strap score, check-in and tags beside ours`() = runTest {
        fill(20)
        val d = db.healthDao()
        val day10 = first.plusDays(10).atStartOfDay(zone).toEpochSecond()
        d.insertStrapReadiness(listOf(StrapReadinessRow(day10, day10, 88, 80, 75, 100, 100, 50, 54, "4.3")))
        d.insertCheckIns(listOf(CheckInRow(day10, 4, day10)))
        d.insertDayTags(listOf(DayTagRow(day10, "alcohol"), DayTagRow(day10, "travel")))

        val rows = Backtest(d, zone).run(first, first.plusDays(19), UserProfile(birthYear = 1990, sex = UserProfile.Sex.Male))
        assertEquals(20, rows.size)
        val r10 = rows[10]
        assertEquals(88, r10.strapScore)
        assertEquals(4, r10.checkIn)
        assertEquals(listOf("alcohol", "travel"), r10.tags.sorted())
        val n = r10.result.tonight!!
        assertTrue(n.hrvSamples >= 6)
        assertNotNull(n.rhrMeanSleep)
        assertNotNull(n.rhrP10)
        assertTrue(n.rhrP10!! <= n.rhrMeanSleep!!)

        val csv = Backtest.csv(rows).lines().filter { it.isNotBlank() }
        assertEquals(21, csv.size)
        val header = csv.first().split(',')
        val line = csv[11].split(',')
        assertEquals("2026-09-11", line[0])
        assertEquals("88", line[header.indexOf("strap_score")])
        assertEquals("4", line[header.indexOf("check_in")])
        assertEquals("alcohol;travel", line[header.indexOf("tags")])
        // Both fusions side by side, then the strap's own score for the same day.
        assertEquals(listOf("date", "readiness", "readiness_soft_min", "strap_score"), header.take(4))
        val scored = rows.indexOfFirst { it.result.readiness.score != null }
        assertTrue("no scored day", scored >= 0)
        val r = rows[scored].result.readiness
        val scoredLine = csv[scored + 1].split(',')
        assertEquals(r.score.toString(), scoredLine[header.indexOf("readiness")])
        assertNotNull(r.softMinScore)
        assertEquals(r.softMinScore.toString(), scoredLine[header.indexOf("readiness_soft_min")])
    }

    /**
     * Not a test: the harness itself, run on demand against a pulled database or a CSV backup.
     * `./gradlew testDebugUnitTest --tests '*BacktestTest*replay*' -Pbacktest=/path/openstrap.db`
     */
    @Test fun `replay a database given on the command line`() = runTest {
        val path = System.getProperty("openstrap.backtest")?.takeIf { it.isNotBlank() }
        assumeTrue("set -Pbacktest=<openstrap.db or backup .zip> to run", path != null)
        val source = File(path!!)
        val target = if (source.extension == "zip") {
            CsvImporter(db, ZoneId.systemDefault()).importZip(source.inputStream())
            db
        } else {
            // Work on a copy (with its WAL beside it), so Room's migration never touches the original.
            val dir = File(System.getProperty("java.io.tmpdir"), "openstrap-backtest-${System.nanoTime()}").apply { mkdirs() }
            listOf("", "-wal", "-shm").map { File(source.path + it) }.filter { it.exists() }
                .forEach { it.copyTo(File(dir, "openstrap.db" + it.name.removePrefix(source.name))) }
            Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, File(dir, "openstrap.db").absolutePath)
                .addMigrations(*org.openstrap.app.data.db.OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        }
        val profile = System.getProperty("openstrap.backtestProfile")?.split(',')?.let { p ->
            UserProfile(birthYear = p.getOrNull(0)?.toIntOrNull(), sex = p.getOrNull(1)?.let { s -> UserProfile.Sex.entries.firstOrNull { it.name.equals(s, true) } })
        } ?: UserProfile()
        val dao = target.healthDao()
        val sleeps = dao.sleepSessionsOnce(0, Long.MAX_VALUE / 2)
        assumeTrue("no sleep in the database", sleeps.isNotEmpty())
        val z = ZoneId.systemDefault()
        val from = java.time.Instant.ofEpochSecond(sleeps.first().end).atZone(z).toLocalDate()
        val to = java.time.Instant.ofEpochSecond(sleeps.last().end).atZone(z).toLocalDate()
        val rows = Backtest(dao, z).run(from, to, profile)
        val out = File(System.getProperty("openstrap.backtestOut")?.takeIf { it.isNotBlank() } ?: "build/backtest.csv")
        out.absoluteFile.parentFile?.mkdirs()
        out.writeText(Backtest.csv(rows))
        println("backtest: ${rows.size} days -> ${out.absolutePath}")
        if (target !== db) target.close()
    }
}
