package org.openstrap.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.DailyCaloriesRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.NightMetricsRow
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.testing.FixedClock
import org.openstrap.app.testing.SourceTree
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recovery is worked out once per day, profile and data version (wave 4 Decisions 4): a hit writes nothing, new data
 * in any table Recovery reads works it out again, a day whose Charge runs to the present is kept for the minute only,
 * a failure is never kept, and at most eight are kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryCacheTest {
    @get:Rule val zone = ZoneRule()

    private lateinit var db: OpenStrapDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Reads of the day's minutes, which every Recovery worked out makes. */
    private val computes = AtomicInteger()
    private val writes = AtomicInteger()
    @Volatile private var failing = false

    private val dao: HealthDao by lazy {
        val real = db.healthDao()
        object : HealthDao by real {
            override suspend fun activityOnce(from: Long, to: Long): List<ActivityMinute> {
                computes.incrementAndGet()
                check(!failing) { "no minutes" }
                return real.activityOnce(from, to)
            }

            override suspend fun insertDailyCalories(rows: List<DailyCaloriesRow>) {
                writes.incrementAndGet()
                real.insertDailyCalories(rows)
            }

            override suspend fun insertNightMetrics(rows: List<NightMetricsRow>) {
                writes.incrementAndGet()
                real.insertNightMetrics(rows)
            }
        }
    }

    private val date = LocalDate.of(2026, 1, 15)

    /** Five days after [date], so [date] is long past unless a test moves it. */
    private val clock = FixedClock(Instant.parse("2026-01-20T12:00:00Z"))

    @Before fun setUp() {
        db = inMemoryDb()
        // Heart rate and minutes enough for the day's energy, so a compute writes its row.
        runBlocking {
            val from = DayWindow.of(date).from
            db.healthDao().insertActivity((0 until 120).map { ActivityMinute(from + it * 60L, 20, 40, 1, 80) })
            db.healthDao().insertHeartRate((0 until 120).map { HeartRateSample(from + it * 60L, 80) })
        }
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** A repository following the data, once it has read the data's first state. */
    private fun started() = HealthRepository(dao) { clock.now }.also { repo ->
        repo.start(scope, HealthRepository.recoveryInputs(db))
        runBlocking { withTimeout(WAIT_MS) { while (repo.recoveryVersion == 0L) delay(POLL_MS) } }
    }

    @Test fun `the same day and profile is worked out once, and a hit writes nothing`() = runBlocking {
        val repo = started()
        val first = repo.recovery(date, profile())
        val computed = computes.get()
        val written = writes.get()
        assertTrue(written > 0)
        assertSame(first, repo.recovery(date, profile()))
        assertEquals(computed, computes.get())
        assertEquals(written, writes.get())
    }

    @Test fun `another profile is worked out on its own`() = runBlocking {
        val repo = started()
        val light = repo.recovery(date, profile(weightKg = 60f))
        assertNotSame(light, repo.recovery(date, profile(weightKg = 90f)))
        assertSame(light, repo.recovery(date, profile(weightKg = 60f)))
    }

    @Test fun `new data works it out again`() = recomputesAfter {
        db.healthDao().insertHeartRate(listOf(HeartRateSample(DayWindow.of(date).from + 7200, 90)))
    }

    @Test fun `new activity minutes alone work it out again`() = recomputesAfter {
        db.healthDao().insertActivity(listOf(ActivityMinute(DayWindow.of(date).from + 3 * 3600, 30, 40, 1, null)))
    }

    @Test fun `new HRV alone works it out again`() = recomputesAfter {
        db.healthDao().insertHrv(listOf(HrvSample(DayWindow.of(date).from + 3 * 3600, 55)))
    }

    private fun recomputesAfter(write: suspend () -> Unit) = runBlocking {
        val repo = started()
        val before = repo.recovery(date, profile())
        assertSame(before, repo.recovery(date, profile()))
        write()
        withTimeout(WAIT_MS) {
            while (repo.recovery(date, profile()) === before) delay(POLL_MS)
        }
    }

    @Test fun `today is kept for the minute only`() = runBlocking {
        clock.instant = Instant.parse("2026-01-15T12:00:10Z")
        val repo = started()
        val first = repo.recovery(date, profile())
        clock.advance(Duration.ofSeconds(30))
        assertSame(first, repo.recovery(date, profile()))
        clock.advance(Duration.ofSeconds(30))
        assertNotSame(first, repo.recovery(date, profile()))
    }

    @Test fun `yesterday is kept for the minute only until its lookahead has passed`() = runBlocking {
        val yesterday = date.minusDays(1)
        clock.instant = Instant.parse("2026-01-15T12:00:00Z")
        val repo = started()
        val noon = repo.recovery(yesterday, profile())
        clock.advance(Duration.ofMinutes(1))
        assertNotSame(noon, repo.recovery(yesterday, profile()))
        // Past 14:00 the run stops at its lookahead, so a later minute changes nothing.
        clock.instant = Instant.parse("2026-01-15T15:00:00Z")
        val later = repo.recovery(yesterday, profile())
        clock.advance(Duration.ofMinutes(5))
        assertSame(later, repo.recovery(yesterday, profile()))
    }

    @Test fun `another zone is worked out on its own`() = runBlocking {
        val repo = started()
        val home = repo.recovery(date, profile())
        // After a flight the day's moments are others, so the result kept from before it isn't this day's.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Dubai"))
        val away = repo.recovery(date, profile())
        assertNotSame(home, away)
        assertSame(away, repo.recovery(date, profile()))
    }

    @Test fun `a past day stays kept as the clock moves`() = runBlocking {
        val repo = started()
        val first = repo.recovery(date, profile())
        clock.advance(Duration.ofHours(3))
        assertSame(first, repo.recovery(date, profile()))
    }

    @Test fun `the cache watches every table Recovery reads`() {
        val daos = File(SourceTree.kotlin, "data/db/Daos.kt").readText()
        // A read of the day tables by moments ([org.openstrap.app.data.db.restingHrIn] and the like) is the query it calls.
        val dayReads = Regex("""fun HealthDao\.(\w+)\([^=]*=\s*DayKey\.datesIn\([^)]*\)\.let \{ (\w+)\(""")
            .findAll(File(SourceTree.kotlin, "data/db/DayReads.kt").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue("found no day reads", dayReads.isNotEmpty())
        val read = listOf("data/RecoveryEngine.kt", "data/SleepSource.kt").flatMap { f ->
            Regex("""dao\.(\w+)\(""").findAll(File(SourceTree.kotlin, f).readText()).map { dayReads[it.groupValues[1]] ?: it.groupValues[1] }.toList()
        }.toSet()
        val tables = read.flatMap { m ->
            val query = Regex("""@Query\("((?:[^"\\]|\\.)*)"\)\s*(?:@\w+\s*)*(?:suspend\s+)?fun $m\(""").find(daos)?.groupValues?.get(1).orEmpty()
            Regex("""(?:FROM|JOIN)\s+(\w+)""").findAll(query).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue("found no tables in $tables", "activity_minute" in tables && "sleep_session" in tables)
        assertEquals(emptySet<String>(), tables - HealthRepository.RECOVERY_TABLES.toSet())
    }

    @Test fun `a failure is never kept`() = runBlocking {
        val repo = started()
        failing = true
        assertTrue(runCatching { repo.recovery(date, profile()) }.isFailure)
        failing = false
        val after = computes.get()
        val r = repo.recovery(date, profile())
        assertTrue(computes.get() > after)
        assertSame(r, repo.recovery(date, profile()))
    }

    @Test fun `at most eight are kept, the oldest going first`() = runBlocking {
        val repo = started()
        val days = (0L..8L).map { date.minusDays(it) }
        days.forEach { repo.recovery(it, profile()) }
        val computed = computes.get()
        repo.recovery(days.last(), profile())
        assertEquals(computed, computes.get())
        repo.recovery(days.first(), profile())
        assertTrue(computes.get() > computed)
    }

    @Test fun `without start nothing is kept`() = runBlocking {
        val repo = HealthRepository(dao) { clock.now }
        val first = repo.recovery(date, profile())
        assertNotSame(first, repo.recovery(date, profile()))
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
