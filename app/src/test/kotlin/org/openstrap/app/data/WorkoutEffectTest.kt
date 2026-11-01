package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class WorkoutEffectTest {
    private val start = 1_800_000_000L
    private val rest = 60f
    private val max = 190f

    /** The HR at reserve fraction [hrr] for [rest] and [max]. */
    private fun bpm(hrr: Double) = Math.round(rest + hrr * (max - rest)).toInt()

    /** One reading a second through (minutes, reserve fraction) stretches, with ±1 bpm of jitter. */
    private fun trace(vararg parts: Pair<Int, Double>): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        var t = start
        for ((minutes, hrr) in parts) repeat(minutes * 60) { out += t to bpm(hrr) + (if (t % 2 == 0L) 1 else -1); t++ }
        return out
    }

    private fun effect(hr: List<Pair<Long, Int>>, sport: Int = 1, route: List<Route.Point> = emptyList(), load28: Float = 0f) =
        WorkoutEffect.compute(sport, hr, start, hr.last().first + 1, emptyList(), route, rest, max, load28, null)

    private val easy = trace(30 to 0.6)
    private val intervals = trace(10 to 0.55, *Array(6) { listOf(3 to 0.92, 2 to 0.5) }.flatMap { it }.toTypedArray(), 10 to 0.5)

    @Test
    fun easyHalfHourIsALowTrainingEffect() {
        val r = effect(easy)
        assertTrue("TE ${r.aerobicTe}", r.aerobicTe!! in 2.0f..2.8f)
        assertTrue("EPOC ${r.epoc}", r.epoc!! in 20f..40f)
    }

    @Test
    fun anEasyWalkLandsNearTheStrapsOwnAndKeepsAdding() {
        // The strap's own record of a walk: 37 min at 117 bpm, resting 70 and max 187 (HRR 0.4), EPOC 16.3.
        fun walk(minutes: Int) = List(minutes * 60) { (start + it) to 117 }
        fun epoc(minutes: Int) = WorkoutEffect.compute(3, walk(minutes), start, start + minutes * 60L, emptyList(), emptyList(), 70f, 187f, 0f, null).epoc!!
        assertTrue("EPOC ${epoc(37)}", epoc(37) in 12f..20f)
        // Nothing clears during it: the same pace for 106 minutes gives near three times as much.
        assertEquals(106f / 37f, epoc(106) / epoc(37), 0.05f)
    }

    @Test
    fun hardIntervalsBuildFarMoreEpocAndEffect() {
        val e = effect(easy)
        val i = effect(intervals)
        assertTrue("EPOC ${i.epoc} vs ${e.epoc}", i.epoc!! > 3 * e.epoc!!)
        assertTrue("TE ${i.aerobicTe}", i.aerobicTe!! >= 3.3f && i.aerobicTe < 5f)
        assertTrue(i.recoveryMinutes!! > e.recoveryMinutes!!)
    }

    @Test
    fun aFitterClassGetsLessEffectFromTheSameSession() {
        val novice = effect(intervals, load28 = 300f)
        val fit = effect(intervals, load28 = 2000f)
        assertEquals(40, WorkoutEffect.activityClass(300f))
        assertEquals(95, WorkoutEffect.activityClass(2000f))
        assertTrue(fit.aerobicTe!! < novice.aerobicTe!!)
    }

    @Test
    fun teFollowsZeppsBreakpointsAndGates() {
        // Class 50: TE 3 at EPOC 49.7, 5 at 199.2 and beyond (native 04 §4.4).
        assertEquals(3.0f, WorkoutEffect.aerobicTe(49.7f, 100, 50), 0.01f)
        assertEquals(5.0f, WorkoutEffect.aerobicTe(400f, 100, 50), 0f)
        // A minute or less, or under 1 ml/kg, is no effect.
        assertEquals(0f, WorkoutEffect.aerobicTe(30f, 11, 50), 0f)
        assertEquals(0f, WorkoutEffect.aerobicTe(0.9f, 100, 50), 0f)
    }

    @Test
    fun recoveryTimeRisesWithEpoc() {
        val minutes = listOf(5f, 15f, 40f, 80f, 120f, 180f).map { e -> WorkoutEffect.recoveryMinutes(WorkoutEffect.aerobicTe(e, 100, 50), null) }
        minutes.zipWithNext().forEach { (a, b) -> assertTrue("$minutes", b > a) }
        assertEquals(72 * 60, WorkoutEffect.recoveryMinutes(5f, null))
        // Near the strap's own 11 h for a walk at aerobic TE 2.1.
        assertEquals(11f * 60, WorkoutEffect.recoveryMinutes(2.1f, null).toFloat(), 30f)
    }

    @Test
    fun fatigueLengthensRecoveryAndLeftoverRecoveryCarries() {
        val fresh = WorkoutEffect.recoveryMinutes(3f, Recovery.LoadTrend(ctl = 20f, atl = 20f, form = 0f))
        val tired = WorkoutEffect.recoveryMinutes(3f, Recovery.LoadTrend(ctl = 20f, atl = 40f, form = 0f))
        assertEquals(20 * 60, fresh)
        assertEquals(25 * 60, tired)
        assertEquals(40 * 60, WorkoutEffect.recoveryMinutes(1f, null, carriedMinutes = 40 * 60))
    }

    @Test
    fun dropoutsHoldTwoEpochsThenGoMissing() {
        // 20 s of readings, a 30 s hole, 20 s more: the hole's first two epochs hold the last reading.
        val hr = (0L until 20L).map { start + it to 120 } + (50L until 70L).map { start + it to 130 }
        val eps = WorkoutEffect.epochs(hr, start, start + 70)
        assertEquals(listOf(0L, 5, 10, 15, 20, 25, 50, 55, 60, 65).map { start + it }, eps.map { it.t })
        assertEquals(120, eps[5].bpm)
    }

    /* VO2max */

    /** A straight run east at [speed] (m/s, or one per second) with a fix and its Doppler speed every second. */
    private fun run(seconds: Int, speed: (Int) -> Double): List<Route.Point> {
        val lat = 51.5
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        var x = 0.0
        return (0 until seconds).map { s ->
            val v = speed(s)
            Route.Point(start + s, lat, -0.1 + x / mPerDegLon, 4f, speed = v.toFloat(), altitude = 30.0, speedAccuracy = 0.3f).also { x += v }
        }
    }

    /** ACSM on the level at 3 m/s: VO2 = 3.5 + 0.2·180 = 39.5; at this reserve VO2max = 3.5 + 36 / HRR. */
    private val steadyHrr = 0.87
    private val expectedVo2 = 3.5 + 36 / ((bpm(steadyHrr) - rest) / (max - rest).toDouble())

    @Test
    fun steadyRunGivesTheAcsmValue() {
        val hr = trace(2 to 0.6, 28 to steadyHrr)
        val r = effect(hr, route = run(30 * 60) { 3.0 })
        assertNotNull(r.vo2max)
        assertEquals(expectedVo2, r.vo2max!!.toDouble(), 1.5)
    }

    @Test
    fun noVo2maxForAWalkOrAShortRun() {
        val hr = trace(2 to 0.6, 28 to steadyHrr)
        assertNull(effect(hr, sport = 3, route = run(30 * 60) { 3.0 }).vo2max)
        val short = trace(2 to 0.6, 7 to steadyHrr)
        assertNull(effect(short, route = run(9 * 60) { 3.0 }).vo2max)
        // Without GPS there's no speed.
        assertNull(effect(hr).vo2max)
    }

    @Test
    fun noVo2maxForANoisyRun() {
        // Heart rate jumping by up to 25 bpm every 15 s, as a loose strap's cadence lock does: the epoch medians
        // can't smooth it, so no window is steady. (Second-to-second jitter alone is what the medians are for.)
        val rnd = java.util.Random(7)
        val jumps = LongArray(200) { rnd.nextInt(26).toLong() }
        val noisy = trace(2 to 0.6, 28 to steadyHrr).map { (t, b) -> t to b - 25 + jumps[((t - start) / 15).toInt()].toInt() }
        assertNull(effect(noisy, route = run(30 * 60) { 3.0 }).vo2max)
        // Pace changing every 30 s between a jog and a fast run: no steady window.
        val surging = effect(trace(2 to 0.6, 28 to steadyHrr), route = run(30 * 60) { s -> if (s / 30 % 2 == 0) 2.2 else 4.2 })
        assertNull(surging.vo2max)
    }
}
