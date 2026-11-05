package org.openstrap.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Recovery.State
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.StressSample
import org.openstrap.app.data.db.Workout
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneOffset

/** The strap's stress readings are ignored in and for 20 minutes after workouts ([Recovery.stressExcluded]). */
class StressExclusionTest {
    private val params = Recovery.ChargeParams(restingHr = 58f, maxHr = 188f, nightRestingHr = 52f)

    /** A slow strength workout over the first 40 minutes: 80 bpm (17 % of reserve), no steps. */
    private val workout = listOf(0L until 40 * 60L)

    private fun charge(minutes: Int, hr: (Int) -> Int, stressAt: (Int) -> Int?, workouts: List<LongRange>): Recovery.ChargeDay {
        val hrs = (0 until minutes).map { it * 60L to hr(it) }
        val stress = (0 until minutes).mapNotNull { m -> stressAt(m)?.let { m * 60L to it } }
        val ms = Recovery.minutes(0, minutes * 60L, hrs, emptyMap(), stress, emptyList(), workouts = workouts)
        return Recovery.charge(ms, params, 0L until minutes * 60L, start = 80f, sampleSeconds = 60, requireAnchor = false)
    }

    @Test fun `stress 80 during a slow strength workout drains nothing as stress`() {
        val r = charge(40, { 80 }, { 80 }, workout)
        assertEquals(0f, r.drainedStress, 1e-4f)
        assertTrue(r.points.none { it.state == State.Stress || it.state == State.Relax })
        // A recorded workout minute above resting is activity.
        assertTrue(r.points.all { it.state == State.Activity })
        // Without the workout stored, the same minutes would drain as stress.
        assertTrue(charge(40, { 80 }, { 80 }, emptyList()).drainedStress > 0f)
    }

    @Test fun `the same reading 30 minutes after the workout drains as stress`() {
        // Workout 0-40 min, then seated at 75 bpm (13 % of reserve) with stress 80 from minute 40 on.
        val r = charge(110, { if (it < 40) 80 else 75 }, { 80 }, workout)
        assertTrue(r.drainedStress > 0f)
        // Nothing in the workout or the 20 minutes after it; Stress from the third minute of the run at 60.
        assertTrue(r.points.filter { it.t < 60 * 60L }.none { it.state == State.Stress || it.state == State.Relax })
        assertEquals(State.ExerciseRecovery, r.points.first { it.t == 45 * 60L }.state)
        assertEquals(State.Stress, r.points.first { it.t == 70 * 60L }.state)
    }

    @Test fun `a reading inside the workout isn't held into the minutes after the exclusion`() {
        // One reading of 80 at minute 59, the last of the exclusion; nothing after it.
        val r = charge(70, { if (it < 40) 80 else 70 }, { if (it == 59) 80 else null }, workout)
        assertEquals(0f, r.drainedStress, 1e-4f)
    }

    @Test fun `the calm baseline leaves out workout minutes and the 20 after`() {
        val day = 86_400L
        // Readings every minute for two hours: a workout 0-30 min (still, low HR: a yoga session), calm after.
        val readings = (0 until 120).map { it * 60L + day to if (it < 50) 30 else 55 }
        val hr = readings.associate { it.first to 65 }
        val excluded = Recovery.stressExcluded(listOf(day until day + 30 * 60L))
        val calm = Recovery.calmStress(readings, hr, emptyMap(), 58f, 188f, excluded) { false }
        assertEquals(70, calm.size)
        assertTrue(calm.all { it.second == 55 && it.first >= day + 50 * 60L })
        // Without the workout every reading is calm.
        assertEquals(120, Recovery.calmStress(readings, hr, emptyMap(), 58f, 188f, emptyList()) { false }.size)
    }

    @Test fun `exclusion covers active parts of a paused phone workout and 20 minutes after its end`() {
        val w = AppWorkout(start = 0, end = 3600, sportType = 1, pauses = "1200-1799")
        val ranges = Recovery.workoutRanges(listOf(w), emptyList(), now = 10_000)
        assertEquals(listOf(0L until 1200L, 1800L until 3600L), ranges)
        val ex = Recovery.stressExcluded(ranges)
        assertTrue(!Recovery.stressCounts(3600 + 19 * 60, ex))
        assertTrue(Recovery.stressCounts(3600 + 20 * 60, ex))
        // The first 20 minutes of the 10-minute pause are covered; nothing is left of it.
        assertTrue(!Recovery.stressCounts(1500, ex))
    }

    @Test fun `a hard workout keeps stress out for an hour, a very hard one for 90 minutes`() {
        val easy = AppWorkout(start = 0, end = 3600, sportType = 1, aerobicTrainingEffect = 2.9f)
        val hard = easy.copy(aerobicTrainingEffect = 3f)
        val veryHard = easy.copy(aerobicTrainingEffect = 4.2f)
        val recording = easy.copy(end = null, aerobicTrainingEffect = 4.2f)
        assertTrue(Recovery.stressTails(listOf(easy, recording), emptyList()).isEmpty())
        val ranges = Recovery.workoutRanges(listOf(hard), emptyList(), now = 10_000)
        val ex = Recovery.stressExcluded(ranges, Recovery.stressTails(listOf(hard), emptyList()))
        assertTrue(!Recovery.stressCounts(3600 + 59 * 60, ex))
        assertTrue(Recovery.stressCounts(3600 + 61 * 60, ex))
        val strap = Workout(0, 3600, 1, 3600, null, null, null, null, aerobicTrainingEffect = 4f, null, null, null, null)
        val exStrap = Recovery.stressExcluded(listOf(0L until 3600L), Recovery.stressTails(listOf(veryHard), listOf(strap)))
        assertTrue(!Recovery.stressCounts(3600 + 89 * 60, exStrap))
        assertTrue(Recovery.stressCounts(3600 + 91 * 60, exStrap))
        // Charge drops the tail's stress readings but keeps the 20-minute workout phase.
        val ms = Recovery.minutes(
            0, 3 * 3600L, (0 until 180).map { it * 60L to 70 }, emptyMap(), (0 until 180).map { it * 60L to 80 }, emptyList(),
            workouts = ranges, stressTails = Recovery.stressTails(listOf(hard), emptyList()),
        )
        val tail = ms.first { it.t == 3600L + 50 * 60 }
        assertEquals(null, tail.stress)
        assertEquals(null, tail.workout)
        assertEquals(80, ms.first { it.t == 3600L + 70 * 60 }.stress)
    }

    @Test fun `the day's stress average and high ignore in-workout readings`() {
        val zone = ZoneOffset.UTC
        val date = LocalDate.of(2026, 10, 1)
        val noon = date.atTime(12, 0).toEpochSecond(zone)
        // 40 at 10:00 and 18:00; 90 during a strap workout at noon and 10 minutes after it; 80 just before a phone one.
        val samples = listOf(
            StressSample(noon - 7200, 40),
            StressSample(noon + 600, 90),
            StressSample(noon + 3600 + 600, 90),
            StressSample(noon + 6 * 3600, 40),
        )
        val strap = Workout(noon, noon + 3600, 1, 3600, null, null, null, null, null, null, null, null, null)
        val repo = HealthRepository(dao(samples, listOf(strap)))
        val stats = runBlocking { repo.daily(DailyMetric.Stress, date, date, zone).first() }
        assertEquals(1, stats.size)
        assertEquals(40f, stats[0].value, 1e-4f)
        assertEquals(40f, stats[0].max, 1e-4f)
        assertEquals(2, stats[0].count)
        // The day's series, for the chart and the Today tile, leaves a gap there too.
        val series = runBlocking { repo.stress(DayWindow.of(date, zone)).first() }
        assertEquals(listOf(40, 40), series.map { it.value })
    }

    private fun dao(stress: List<StressSample>, strap: List<Workout>): HealthDao =
        Proxy.newProxyInstance(HealthDao::class.java.classLoader, arrayOf(HealthDao::class.java)) { _, m, args ->
            when (m.name) {
                "stress" -> flowOf(stress.filter { it.timestamp in (args[0] as Long)..(args[1] as Long) })
                "workoutsIn" -> flowOf(strap)
                "appWorkoutsIn" -> flowOf(emptyList<AppWorkout>())
                else -> error("unexpected ${m.name}")
            }
        } as HealthDao
}
