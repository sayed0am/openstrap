package org.openstrap.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.testing.FixedClock
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Data integrity item 18: a day's energy is worked out by one rule, whether its page works it out or a sync writes its
 * row, so the page and the week's bar show the same kcal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyEnergyOneRuleTest {
    @get:Rule val zone = ZoneRule()

    private lateinit var db: OpenStrapDatabase

    /** A day in the year before today's, so the age, and with it max HR as of that day, differs from today's. */
    private val day = LocalDate.of(2025, 12, 30)
    private val clock = FixedClock(Instant.parse("2026-01-02T12:00:00Z"))
    private val you = profile(birthYear = 1990)

    @Before fun setUp() {
        db = inMemoryDb()
        // Two hours of raised heart rate with few steps: minutes the heart rate branch counts on their share of the
        // reserve, below its 6 MET cap, so max HR moves their kcal.
        runBlocking {
            val from = DayWindow.of(day).from + 9 * 3600
            db.healthDao().insertActivity((0 until 120).map { ActivityMinute(from + it * 60L, 5, 10, 1, 120) })
            db.healthDao().insertHeartRate((0 until 120).map { HeartRateSample(from + it * 60L, 120) })
        }
    }

    @After fun tearDown() = db.close()

    private suspend fun stored(d: LocalDate): Float {
        return checkNotNull(db.healthDao().dailyEnergyOnce(d, d).singleOrNull()) { "no row for $d" }.totalKcal
    }

    @Test fun `a past day's page shows what the sync wrote for it`() = runBlocking {
        DailyEnergyHistory(db.healthDao()).update(you, profileChanged = true, zone = ZoneOffset.UTC, now = clock.now)
        val synced = stored(day)

        val page = HealthRepository(db.healthDao()) { clock.now }.recovery(day, you).energy
        assertNotNull(page)
        assertEquals(synced, page!!.totalKcal, 0.01f)
        assertEquals(synced, stored(day), 0.01f)
    }

    @Test fun `today's page and the rows written after it agree`() = runBlocking {
        val today = LocalDate.of(2026, 1, 2)
        val from = DayWindow.of(today).from + 6 * 3600
        db.healthDao().insertActivity((0 until 60).map { ActivityMinute(from + it * 60L, 5, 10, 1, 120) })
        db.healthDao().insertHeartRate((0 until 60).map { HeartRateSample(from + it * 60L, 120) })

        val page = checkNotNull(HealthRepository(db.healthDao()) { clock.now }.recovery(today, you).energy)
        DailyEnergyHistory(db.healthDao()).update(you, profileChanged = true, zone = ZoneOffset.UTC, now = clock.now)
        assertEquals(page.totalKcal, stored(today), 0.01f)
    }
}
