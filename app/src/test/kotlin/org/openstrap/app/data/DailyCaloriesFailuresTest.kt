package org.openstrap.app.data

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * A day [DailyCaloriesHistory] can't work out (n-data2 item 12): the reason goes to the log, and a cancelled rewrite
 * stops rather than going on to the next day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyCaloriesFailuresTest {
    private val zone = ZoneOffset.UTC

    @get:Rule val zoneRule = ZoneRule(zone)

    private val db = inMemoryDb()

    @After fun close() = db.close()

    private val from = LocalDate.of(2026, 1, 10)
    private val days = 5

    /** The database, with the read of a day's resting HR for its calories ([DailyCaloriesHistory.calories]) replaced by [read]. */
    private fun dao(read: suspend () -> Nothing): HealthDao = object : HealthDao by db.healthDao() {
        override suspend fun restingHrOnce(from: LocalDate, to: LocalDate): List<RestingHeartRate> =
            if (ChronoUnit.DAYS.between(from, to) == Recovery.RHR_DAYS.toLong()) read() else db.healthDao().restingHrOnce(from, to)
    }

    private suspend fun update(dao: HealthDao) = DailyCaloriesHistory(dao).updateDays(
        from.atStartOfDay(zone).toEpochSecond(), from.plusDays(days - 1L).atStartOfDay(zone).toEpochSecond(),
        profile(birthYear = 1990), zone, now = from.plusDays(days.toLong()).atStartOfDay(zone).toEpochSecond(),
    )

    @Test fun `a day that fails is logged and the others go on`() = runBlocking {
        ShadowLog.clear()
        var reads = 0
        update(
            dao {
                reads++
                error("disk gone")
            },
        )
        assertEquals(days, reads)
        val lines = ShadowLog.getLogsForTag("OpenStrap").map { it.msg }
        assertEquals(lines.toString(), days, lines.count { it.startsWith("daily energy: energy for ") && "disk gone" in it })
    }

    @Test fun `a cancelled rewrite stops at the day it was on`() = runBlocking {
        var reads = 0
        val job = launch {
            update(
                dao {
                    reads++
                    currentCoroutineContext().cancel()
                    throw CancellationException("stopped")
                },
            )
        }
        job.join()
        assertTrue(job.isCancelled)
        // runCatching took the cancellation as the day's failure and went on to every other day.
        assertEquals(1, reads)
    }
}
