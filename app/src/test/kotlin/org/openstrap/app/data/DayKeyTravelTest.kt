package org.openstrap.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.DailyEnergyRow
import org.openstrap.app.data.db.DayTagRow
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.TimeZone

/**
 * What the user saved on a day stays on that day after a flight (item 17): a day's rows are keyed by its date, so the
 * phone's zone when they are read doesn't move them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayKeyTravelTest {
    @get:Rule val zoneRule = ZoneRule()

    private lateinit var db: OpenStrapDatabase
    private val dao get() = db.healthDao()
    private lateinit var repo: HealthRepository
    private val d = LocalDate.of(2026, 1, 15)

    @Before fun open() {
        db = inMemoryDb()
        repo = HealthRepository(dao)
    }

    @After fun close() = db.close()

    private fun inZone(id: String) = TimeZone.setDefault(TimeZone.getTimeZone(id))

    @Test fun `a check-in saved in Dubai is that day's in New York`() = runTest {
        inZone("Asia/Dubai")
        repo.saveCheckIn(d, 4)
        dao.insertDayTags(listOf(DayTagRow(d, "travel")))
        inZone("America/New_York")
        assertEquals(4, repo.checkIn(d).first()?.feeling)
        assertEquals(null, repo.checkIn(d.minusDays(1)).first())
        assertEquals(listOf(DayTagRow(d, "travel")), dao.dayTagsOnce(d, d))
    }

    @Test fun `resting heart rate and energy keep their dates after a flight`() = runTest {
        inZone("Pacific/Auckland")
        dao.insertRestingHr(listOf(RestingHeartRate(d, 52), RestingHeartRate(d.plusDays(1), 54)))
        dao.insertDailyEnergy(listOf(DailyEnergyRow(d, 1000, 2100f, 450f, 1650f, 1440, false, 0f, 200f, "")))
        inZone("Europe/London")
        val stats = repo.daily(DailyMetric.RestingHr, d, d.plusDays(1)).first()
        assertEquals(listOf(d to 52f, d.plusDays(1) to 54f), stats.map { it.date to it.value })
        assertEquals(setOf(d), repo.dailyEnergy(d.minusDays(1), d.plusDays(1)).first().keys)
        assertEquals(listOf(52), repo.restingHr(d, d).first().map { it.bpm })
    }
}
