package org.openstrap.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.SleepEdit
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.StrapReadinessRow
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day rule for sleep (data integrity item 14, user 2026-11-04): a sleep belongs to the day it ended on, by the
 * times shown, on the Sleep tab, Today, Recovery and the nights alike, over the strap's own day. The strap's nightly
 * rows still follow the strap's session, so a night that changes day keeps them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepDayRuleTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kathmandu")

    @get:Rule val zoneRule = ZoneRule(zone)

    private val db = inMemoryDb()
    private val dao = db.healthDao()
    private val health = HealthRepository(dao)

    @After fun close() = db.close()

    private val date = LocalDate.of(2026, 1, 15)

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toEpochSecond()

    private fun day(d: LocalDate): DayData = runBlocking {
        withTimeout(10_000) { RoomDayRepository(health, LiveSteps()).day(d, isToday = false).first() }
    }

    private fun compute(d: LocalDate) = runBlocking { RecoveryEngine(dao).compute(d, profile(), zone, at(date.plusDays(3), 12)) }

    @Test
    fun `a sleep ending between 6 PM and midnight belongs to that day`() = runBlocking {
        // A day sleep after a night shift, 10:00 to 19:30, which the strap gave the next day.
        val sleep = SleepSession(at(date, 10), at(date, 19, 30), Days.start(date.plusDays(1), zone), score = 75)
        dao.insertSleepSessions(listOf(sleep))

        assertEquals(sleep, day(date).sleep.main)
        assertNull(day(date.plusDays(1)).sleep.main)
        assertEquals(sleep.end, compute(date).tonight?.end)
        assertNull(compute(date.plusDays(1)).tonight)
        assertEquals(mapOf(date to sleep), SleepHistory.mainSleeps(listOf(sleep), zone))
        assertEquals(date, SleepHistory.nightOf(sleep, zone))
    }

    @Test
    fun `a night edited to end after midnight moves to the new day with the strap's rows`() = runBlocking {
        // The strap saw 21:00 to 23:45 and gave it that day. The user says it ran to 02:30.
        val strapDay = Days.start(date.minusDays(1), zone)
        val strap = SleepSession(at(date.minusDays(1), 21), at(date.minusDays(1), 23, 45), strapDay, score = 60)
        dao.insertSleepSessions(listOf(strap))
        dao.insertStrapReadiness(listOf(StrapReadinessRow(strapDay, 1, 70, null, null, null, null, sleepHrv = 77, sleepRestingHr = 51, algorithmVersion = "t")))
        // The sleep-apnea summary keyed by the strap's day: its belongTime + 2 h falls on it.
        dao.insertOsaResults(listOf(OsaResultRow(strapDay - 2 * 3600 + 600, 0, 0, 90, 0, eventCount = 3, version = 1)))
        assertEquals(strap.end, compute(date.minusDays(1)).tonight?.end)

        val moved = at(date, 2, 30)
        dao.upsertSleepEdit(SleepEdit(strap.start, nap = false, start = strap.start, end = moved, editedAt = 1))

        val tonight = compute(date).tonight
        assertEquals(moved, tonight?.end)
        assertEquals(77f, tonight?.strapHrv)
        assertEquals(51f, tonight?.strapRestingHr)
        assertNull(compute(date.minusDays(1)).tonight)

        assertEquals(moved, day(date).sleep.main?.end)
        assertEquals(strapDay - 2 * 3600 + 600, day(date).osaResult?.belongTime)
        val before = day(date.minusDays(1))
        assertNull(before.sleep.main)
        // Not a deleted night: it moved, so the No sleep recorded card has nothing to restore.
        assertNull(before.sleep.deletedMain)
        assertNull(before.osaResult)
        val w = DayWindow.of(date.minusDays(1), zone)
        assertFalse(health.sleep.nightDeleted(w.from, w.to))
    }

    @Test
    fun `an edit that moves a night across midnight refreshes both days' Recovery`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = HealthRepository(dao) { at(date.plusDays(3), 12) }
            repo.start(scope, HealthRepository.recoveryInputs(db))
            withTimeout(10_000) { while (repo.recoveryVersion == 0L) delay(20) }
            val strap = SleepSession(at(date.minusDays(1), 21), at(date.minusDays(1), 23, 45), Days.start(date.minusDays(1), zone), score = 60)
            dao.insertSleepSessions(listOf(strap))
            withTimeout(10_000) { while (repo.recovery(date.minusDays(1), profile()).tonight == null) delay(20) }
            assertNull(repo.recovery(date, profile()).tonight)

            dao.upsertSleepEdit(SleepEdit(strap.start, nap = false, start = strap.start, end = at(date, 2, 30), editedAt = 1))
            withTimeout(10_000) { while (repo.recovery(date, profile()).tonight == null) delay(20) }
            assertNull(repo.recovery(date.minusDays(1), profile()).tonight)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a night ending just after midnight in a non-whole-hour zone is that morning's`() {
        // 00:10 in Kathmandu is 18:25 UTC the day before.
        val night = SleepSession(at(date.minusDays(1), 16), at(date, 0, 10))
        assertEquals(mapOf(date to night), SleepHistory.mainSleeps(listOf(night), zone))
        val evening = SleepSession(at(date.minusDays(1), 15), at(date.minusDays(1), 23, 50))
        assertEquals(mapOf(date.minusDays(1) to evening), SleepHistory.mainSleeps(listOf(evening), zone))
    }

    @Test
    fun `on the days the clocks change a night belongs to the day it ended`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val autumn = LocalDate.of(2026, 10, 25)
        fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(berlin).toEpochSecond()
        // 25 hours that day: 23:30 is still in it, and 00:10 the next morning is not.
        val late = SleepSession(at(autumn, 15), at(autumn, 23, 30))
        val next = SleepSession(at(autumn, 22), at(autumn.plusDays(1), 0, 10))
        assertEquals(autumn, SleepHistory.nightOf(late, berlin))
        assertEquals(autumn.plusDays(1), SleepHistory.nightOf(next, berlin))
        val spring = LocalDate.of(2026, 3, 29)
        val short = SleepSession(at(spring.minusDays(1), 23), at(spring, 7))
        assertEquals(mapOf(spring to short), SleepHistory.mainSleeps(listOf(short), berlin))
    }
}
