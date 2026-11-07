package org.openstrap.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.protocol.history.ActivityMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Sleep from the strap's minute signal where its own result is missing or wrong ([MinuteSleep]). The nights are made
 * up, shaped like the ones firmware 3.27.0.1 got wrong on 5 to 7 November 2026: a night the strap placed over the
 * next afternoon, one it left out, and one it cut to its last 91 minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MinuteSleepTest {
    private val zone: ZoneId = ZoneId.of("Asia/Dubai")
    private val date: LocalDate = LocalDate.of(2026, 11, 5)
    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    private fun at(day: LocalDate, h: Int, m: Int) = day.atTime(LocalTime.of(h, m)).atZone(zone).toEpochSecond()
    private fun at(h: Int, m: Int) = at(if (h >= 18) date.minusDays(1) else date, h, m)

    /** Minutes from [from] to [to], asleep or awake, still or moving, with a heart rate. */
    private fun minutes(from: Long, to: Long, asleep: Boolean, intensity: Int = 0, hr: Int = 60) =
        (from until to step 60).map { ActivityMinute(it, 0, intensity, if (asleep) ActivityMode.DREAM else ActivityMode.SILENT, hr) }

    /**
     * Awake until 23:00, ten restless asleep minutes, asleep and still to 03:00, awake for 12 minutes, asleep to
     * 07:00, ten restless minutes, then awake and moving to 09:00.
     */
    private fun night(): List<ActivityMinute> =
        minutes(at(18, 0), at(23, 0), asleep = false, intensity = 30, hr = 80) +
            minutes(at(23, 0), at(23, 10), asleep = true, intensity = 30, hr = 75) +
            minutes(at(23, 10), at(3, 0), asleep = true) +
            minutes(at(3, 0), at(3, 12), asleep = false, intensity = 10, hr = 70) +
            minutes(at(3, 12), at(7, 0), asleep = true, hr = 64) +
            minutes(at(7, 0), at(7, 10), asleep = true, intensity = 30, hr = 75) +
            minutes(at(7, 10), at(9, 0), asleep = false, intensity = 40, hr = 85)

    private val windowStart get() = Days.start(date, zone) - MinuteSleep.WINDOW_BEFORE_MIDNIGHT
    private val windowEnd get() = Days.start(date, zone) + MinuteSleep.WINDOW_AFTER_MIDNIGHT

    @Test fun `a night takes the asleep minutes, joins short wakes and leaves the restless edges out`() {
        val n = MinuteSleep.night(night(), windowStart, windowEnd, latest = at(9, 0))!!
        // The edges stop where 15 minutes hold at most 2 restless ones: 2 restless minutes are left at each end.
        assertEquals(at(23, 8), n.start)
        assertEquals(at(7, 2), n.end)
        assertEquals(12, n.awakeMinutes)
        assertEquals(1, n.wakeCount)
        assertEquals((n.end - n.start).toInt() / 60 - 12, n.asleepMinutes)
    }

    @Test fun `a night still going on is left for a later sync`() {
        assertNull(MinuteSleep.night(night().filter { it.timestamp < at(7, 5) }, windowStart, windowEnd, latest = at(7, 4)))
    }

    @Test fun `under two hours asleep is no night`() {
        val short = minutes(at(1, 0), at(2, 30), asleep = true) + minutes(at(2, 30), at(4, 0), asleep = false, intensity = 30)
        assertNull(MinuteSleep.night(short, windowStart, windowEnd, latest = at(4, 0)))
    }

    private fun strap(start: Long, end: Long) =
        SleepSession(start, end, DayKey.of(date), score = 70, deepMinutes = 60, lightMinutes = ((end - start) / 60).toInt() - 90, remMinutes = 30)

    private fun minuteNight() = MinuteSleep.Night(at(23, 10), at(7, 0), 458, 12, 1, 62)

    @Test fun `the strap's sleep stays when it matches the minutes`() {
        val strap = strap(at(23, 20), at(6, 50))
        assertFalse(MinuteSleep.replaces(strap, strapAsleepMinutes = 440, night = minuteNight()))
    }

    @Test fun `no strap sleep, or one over awake minutes, or a fraction of the night is replaced`() {
        assertTrue(MinuteSleep.replaces(null, 0, minuteNight()))
        // Placed in the afternoon after, over minutes the strap itself marked awake (5 November).
        assertTrue(MinuteSleep.replaces(strap(at(9, 38), at(17, 10)), strapAsleepMinutes = 0, night = minuteNight()))
        // Only the last 91 minutes (7 November).
        assertTrue(MinuteSleep.replaces(strap(at(5, 29), at(7, 0)), strapAsleepMinutes = 91, night = minuteNight()))
    }

    @Test fun `after a sync a wrong strap night is replaced, and again after the strap sends it again`() = runBlocking {
        dao.insertActivity(night())
        val wrong = strap(at(9, 38), at(17, 10))
        dao.replaceSleepDay(date, nap = false, listOf(wrong), listOf(SleepStage(wrong.start, wrong.end, wrong.start, SleepStage.LIGHT)))
        val now = at(date, 17, 30)

        assertEquals(listOf(date), MinuteSleep.apply(dao, zone, now))
        val made = dao.sleepSessionsOnce(0, Long.MAX_VALUE).single()
        assertTrue(made.fromMinutes)
        assertEquals(at(23, 8), made.start)
        assertNull(made.score)
        assertTrue(dao.sleepStagesOnce(made.start).isEmpty())
        assertEquals((made.end - made.start) / 60 - 12, SleepHistory.asleepSeconds(made) / 60)

        // Nothing to do on the next sync, until the strap sends its wrong result again.
        assertEquals(emptyList<LocalDate>(), MinuteSleep.apply(dao, zone, now))
        dao.replaceSleepDay(date, nap = false, listOf(wrong), emptyList())
        assertEquals(listOf(date), MinuteSleep.apply(dao, zone, now))
        assertTrue(dao.sleepSessionsOnce(0, Long.MAX_VALUE).single().fromMinutes)
    }

    @Test fun `a right strap night is kept`() = runBlocking {
        dao.insertActivity(night())
        val right = strap(at(23, 20), at(6, 50))
        dao.replaceSleepDay(date, nap = false, listOf(right), emptyList())
        assertEquals(emptyList<LocalDate>(), MinuteSleep.apply(dao, zone, at(date, 17, 30)))
        assertEquals(listOf(right), dao.sleepSessionsOnce(0, Long.MAX_VALUE))
    }
}
