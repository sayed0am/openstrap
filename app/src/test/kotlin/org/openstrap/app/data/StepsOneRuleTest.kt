package org.openstrap.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * One rule for a minute's steps (data integrity item 23.1): a minute over 250 steps is bad data everywhere, so Today,
 * the hourly chart, the steps charts and Habit Age add up the same minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StepsOneRuleTest {
    private val zone = ZoneId.of("Asia/Kathmandu")

    @get:Rule val zoneRule = ZoneRule(zone)

    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    @Test fun `a minute over 250 steps counts nowhere`() = runTest {
        val day = LocalDate.of(2026, 1, 15)
        val from = day.atStartOfDay(zone).toEpochSecond()
        val to = from + 86_399
        // A real minute, one past the limit, the strap's 0xFFFF sentinel and a negative one.
        val minutes = listOf(120, 251, 65_535, -1).mapIndexed { i, steps -> ActivityMinute(from + 3600 + i * 60L, steps, 0, 0, null) }
        dao.insertActivity(minutes)

        assertEquals(120, Summaries.totalSteps(minutes))
        assertEquals(120f, Summaries.hourlySteps(minutes, from, zone).sum())
        // Habit Age reads this, and the charts read the Flow: both agree with Today.
        assertEquals(120.0, dao.dailyStepsOnce(from, to).single().value, 0.0)
        assertEquals(120.0, dao.dailySteps(from, to).first().single().value, 0.0)
    }
}
