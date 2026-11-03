package org.openstrap.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.db.DailyMaxHr
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.settings.UserProfile
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

class RecoveryEngineTest {
    private val date = LocalDate.of(2026, 10, 1)
    private val zone = ZoneOffset.UTC
    private val forty = UserProfile(birthYear = LocalDate.now().year - 40)

    /** A DAO answering only the reads max HR needs; every other `…Once` read is empty. */
    private fun dao(hr: List<HeartRateSample> = emptyList(), dailyMax: List<DailyMaxHr> = emptyList()): HealthDao =
        Proxy.newProxyInstance(HealthDao::class.java.classLoader, arrayOf(HealthDao::class.java)) { _, m, _ ->
            when (m.name) {
                "heartRateOnce" -> hr
                "dailyMaxHrOnce" -> dailyMax
                else -> if (m.name.endsWith("Once")) emptyList<Any>() else error("unexpected ${m.name}")
            }
        } as HealthDao

    /** Ten minutes at [bpm] at noon, [daysBack] days before [date]. */
    private fun effort(daysBack: Long, bpm: Int) = (0 until 10).map { i ->
        HeartRateSample(date.minusDays(daysBack).atTime(12, 0).toEpochSecond(zone) + i * 60, bpm)
    }

    private fun maxHr(dao: HealthDao, profile: UserProfile = forty) = runBlocking { RecoveryEngine(dao).maxHeartRate(profile, date, zone) }

    @Test
    fun workoutMaxHrFollowsTheAppRule() {
        // Tanaka for 40, not 220 - age.
        assertEquals(180f, maxHr(dao()), 0.01f)
        // Two days that agree raise it.
        assertEquals(186f, maxHr(dao(effort(3, 188) + effort(10, 186))), 0.01f)
        // A tested value wins over both.
        assertEquals(183f, maxHr(dao(effort(3, 188) + effort(10, 186)), forty.copy(maxHeartRate = 183)), 0.01f)
    }

    @Test
    fun sharedMaxHrIsTheWorkoutRule() = runBlocking {
        // Efforts near today, so the shared one (always as of now) has peaks to learn from.
        fun near(daysBack: Long, bpm: Int) = (0 until 10).map { i ->
            HeartRateSample(LocalDate.now().minusDays(daysBack).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond() + i * 60, bpm)
        }
        val d = dao(near(3, 188) + near(10, 186))
        for (p in listOf(forty, forty.copy(maxHeartRate = 183), forty.copy(maxHeartRate = 250), UserProfile())) {
            val shared = MaxHeartRates(d, kotlinx.coroutines.flow.flowOf(p))
            val once = shared.now()
            assertEquals(RecoveryEngine(d).maxHeartRate(p).roundToInt(), once.bpm)
            assertEquals(once, kotlinx.coroutines.withTimeout(5_000) { shared.current.first { it != null } })
        }
        // A tested value wins and says so; one out of range is ignored.
        assertEquals(MaxHeartRate(183, MaxHeartRate.Source.Set), MaxHeartRates(d, kotlinx.coroutines.flow.flowOf(forty.copy(maxHeartRate = 183))).now())
        assertEquals(MaxHeartRate.Source.Learned, MaxHeartRates(d, kotlinx.coroutines.flow.flowOf(forty.copy(maxHeartRate = 250))).now().source)
    }

    @Test
    fun strapDailyMaxSpikesDontRaiseMaxHr() {
        val days = listOf(3L, 10L).map { date.minusDays(it) }
        val minutes = effort(3, 175) + effort(10, 176)
        // The strap's 205 on two days isn't backed by the minutes (175-176), so Tanaka stands.
        assertEquals(180f, maxHr(dao(minutes, days.map { DailyMaxHr(it, 205) })), 0.01f)
        // Within 10 bpm of the minutes it counts: 184 and 185 agree, raising max HR to 184.
        assertEquals(184f, maxHr(dao(minutes, listOf(DailyMaxHr(days[0], 184), DailyMaxHr(days[1], 185)))), 0.01f)
    }

    /** A main sleep from [from] to [to] (clock times) on each of the [nights] nights before [date]'s morning. */
    private fun sleeps(nights: Int, from: java.time.LocalTime, to: java.time.LocalTime) = (1..nights).map { back ->
        val d = date.minusDays(back.toLong() - 1)
        val start = (if (from > to) d.minusDays(1) else d).atTime(from).toEpochSecond(zone)
        SleepSession(start = start, end = d.atTime(to).toEpochSecond(zone))
    }

    @Test
    fun usualSleepIsTheMedianNight() {
        val nights = RecoveryEngine.usualSleep(sleeps(5, java.time.LocalTime.of(23, 0), java.time.LocalTime.of(7, 0)), date, zone)
        assertEquals(Recovery.WAKE_HISTORY + 2, nights.size)
        // The last one is the night into [date]'s morning.
        val last = date.minusDays(1).atTime(23, 0).toEpochSecond(zone)..date.atTime(7, 0).toEpochSecond(zone)
        assertEquals(last, nights.first())
        // Without any sleep: midnight to 06:00.
        assertEquals(date.atStartOfDay(zone).toEpochSecond()..date.atTime(6, 0).toEpochSecond(zone), RecoveryEngine.usualSleep(emptyList(), date, zone).first())
    }

    @Test
    fun usualSleepAfterANightShiftCrossesNoon() {
        // Asleep 08:00 to 16:00 after night shifts: the range runs through noon instead of coming out empty.
        val days = RecoveryEngine.usualSleep(sleeps(5, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(16, 0)), date, zone)
        assertEquals(date.atTime(8, 0).toEpochSecond(zone)..date.atTime(16, 0).toEpochSecond(zone), days.first())
    }

    @Test
    fun usualBedtimeIsTheUsualSleepsStart() {
        assertEquals(java.time.LocalTime.of(23, 0), RecoveryEngine.usualBedtime(sleeps(5, java.time.LocalTime.of(23, 0), java.time.LocalTime.of(7, 0)), date, zone))
        // No main sleep yet: unknown, not usualSleep's midnight stand-in.
        assertEquals(null, RecoveryEngine.usualBedtime(emptyList(), date, zone))
    }
}
