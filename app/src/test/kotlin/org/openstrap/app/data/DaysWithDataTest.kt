package org.openstrap.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The calendar's days with data ([HealthRepository.daysWithData]) watch only the recent days as they change, and
 * still show every older one, read again when older data comes in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaysWithDataTest {
    private val zone = ZoneOffset.UTC

    @get:Rule val zoneRule = ZoneRule(zone)

    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    private val today = LocalDate.of(2026, 3, 1)
    private val now = today.atTime(12, 0).toEpochSecond(zone)

    private fun noon(d: LocalDate) = d.atTime(12, 0).toEpochSecond(zone)

    @Test fun `old and recent days show, and both follow new data`() = runBlocking {
        val old = today.minusDays(60)
        val recent = today.minusDays(2)
        dao.insertHeartRate(listOf(HeartRateSample(noon(old), 60), HeartRateSample(noon(recent), 60)))
        val days = HealthRepository(dao).daysWithData(zone, now)
        suspend fun until(expected: Set<LocalDate>) = withTimeout(5_000) { days.first { it == expected } }
        assertEquals(setOf(old, recent), days.first())

        // A sync: today's heart rate and last night's sleep.
        dao.insertHeartRate(listOf(HeartRateSample(noon(today), 62)))
        dao.insertSleepSessions(listOf(SleepSession(noon(today.minusDays(1)) - 3600, noon(today.minusDays(1)))))
        until(setOf(old, recent, today.minusDays(1), today))

        // A restore of older data than any there was.
        val older = today.minusDays(400)
        dao.insertHeartRate(listOf(HeartRateSample(noon(older), 58)))
        until(setOf(older, old, recent, today.minusDays(1), today))
        Unit
    }
}
