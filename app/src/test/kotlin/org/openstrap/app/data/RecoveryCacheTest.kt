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
import org.openstrap.app.data.db.DailyEnergyRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.NightMetricsRow
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recovery is worked out once per day, profile and data version (wave 4 Decisions 4): a hit writes nothing, new data
 * works it out again, a failure is never kept, and at most eight are kept.
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

            override suspend fun insertDailyEnergy(rows: List<DailyEnergyRow>) {
                writes.incrementAndGet()
                real.insertDailyEnergy(rows)
            }

            override suspend fun insertNightMetrics(rows: List<NightMetricsRow>) {
                writes.incrementAndGet()
                real.insertNightMetrics(rows)
            }
        }
    }

    private val date = LocalDate.of(2026, 1, 15)

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
    private fun started() = HealthRepository(dao).also { repo ->
        repo.start(scope)
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

    @Test fun `new data works it out again`() = runBlocking {
        val repo = started()
        val before = repo.recovery(date, profile())
        db.healthDao().insertHeartRate(listOf(HeartRateSample(DayWindow.of(date).from + 7200, 90)))
        withTimeout(WAIT_MS) {
            while (repo.recovery(date, profile()) === before) delay(POLL_MS)
        }
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
        val repo = HealthRepository(dao)
        val first = repo.recovery(date, profile())
        assertNotSame(first, repo.recovery(date, profile()))
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
