package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.db.SleepSession
import java.time.LocalDate
import java.time.ZoneOffset

class SleepHistoryTest {
    private val zone = ZoneOffset.UTC
    private val end = LocalDate.of(2026, 10, 17)

    /** A night ending on [end] minus [nightsAgo], from [bed] to [wake] (minutes past midnight; bed may be negative). */
    private fun night(nightsAgo: Long, bed: Int, wake: Int, nap: Boolean = false): SleepSession {
        val midnight = end.minusDays(nightsAgo).atStartOfDay(zone).toEpochSecond()
        return SleepSession(start = midnight + bed * 60L, end = midnight + wake * 60L, nap = nap)
    }

    @Test
    fun sleepBelongsToTheDayItEnds() {
        assertEquals(end, SleepHistory.nightOf(night(0, -60, 7 * 60).end, zone))
        // A sleep ending at 19:00 counts towards the day it ended on, as recovery counts it.
        assertEquals(end, SleepHistory.nightOf(night(0, 13 * 60, 19 * 60).end, zone))
    }

    @Test
    fun mainSleepIsTheLongestNonNap() {
        val long = night(0, -60, 7 * 60)
        val short = night(0, 8 * 60, 9 * 60)
        val nap = night(0, -120, 9 * 60, nap = true)
        assertEquals(mapOf(end to long), SleepHistory.mainSleeps(listOf(short, nap, long), zone))
    }

    @Test
    fun asleepUsesStageTotals() {
        val s = night(0, -60, 7 * 60).copy(deepMinutes = 60, lightMinutes = 240, remMinutes = 90, awakeMinutes = 30)
        assertEquals(390 * 60L, SleepHistory.asleepSeconds(s))
        assertEquals(8 * 3600L, SleepHistory.asleepSeconds(night(0, -60, 7 * 60)))
    }

    @Test
    fun usualTimesNeedSomeSleep() {
        assertNull(SleepHistory.usualTimes(emptyMap(), end, zone))
        // Nights outside the 7-night window don't count.
        assertNull(SleepHistory.usualTimes(mapOf(end.minusDays(7) to night(7, -30, 7 * 60)), end, zone))
        val two = (0L..1L).associate { end.minusDays(it) to night(it, -30, 7 * 60) }
        assertEquals(2, SleepHistory.usualTimes(two, end, zone)!!.nights)
    }

    @Test
    fun usualTimesAverageAcrossMidnight() {
        // 23:30 to 07:00 every night: bedtime averages before midnight without wrapping.
        val same = (0L..6L).associate { end.minusDays(it) to night(it, -30, 7 * 60) }
        val u = SleepHistory.usualTimes(same, end, zone)!!
        assertEquals(23 * 60 + 30, SleepHistory.clockMinutes(u.bedtime))
        assertEquals(7 * 60, u.wake)
        assertEquals(7, u.nights)
        // 23:30 and 00:30 alternating average to midnight.
        val either = (0L..5L).associate { end.minusDays(it) to night(it, if (it % 2 == 0L) -30 else 30, 7 * 60) }
        assertEquals(0, SleepHistory.clockMinutes(SleepHistory.usualTimes(either, end, zone)!!.bedtime))
    }
}
