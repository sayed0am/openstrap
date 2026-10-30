package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class RouteTest {
    // Along the equator a degree of longitude is 111.195 km, so 0.001° is about 111 m.
    private fun east(t: Long, metres: Double, accuracy: Float = 5f) = Route.Point(t, 0.0, metres / 111_195.0, accuracy)

    /** A steady 3 m/s run east for [seconds], one fix a second. */
    private fun run(seconds: Int, from: Long = 0) = (0..seconds).map { east(from + it, it * 3.0) }

    @Test
    fun distanceAlongTheEquator() {
        assertEquals(300.0, Route.distance(run(100)), 1.0)
    }

    @Test
    fun poorFixesAndJumpsDoNotCount() {
        val points = run(100).toMutableList()
        points[50] = east(50, 5_000.0) // a jump
        points[60] = east(60, 180.0, accuracy = 80f)
        // Measured along fixes about 10 m apart, so the last few metres may not be in yet.
        assertEquals(300.0, Route.distance(points), 10.0)
    }

    @Test
    fun theFirstFixesBeforeGpsSettlesDoNotCount() {
        // A cold start: a fix 150 m off, a couple still poor, then good ones where you actually are.
        val start = listOf(east(0, 150.0, 15f), east(1, 60.0, 25f), east(2, 20.0, 40f))
        assertEquals(300.0, Route.distance(start + run(100, from = 3)), 10.0)
    }

    @Test
    fun standingStillAddsNothing() {
        // Ten minutes at home: fixes claiming ±5 m, each somewhere within 5 m of where you are.
        val home = (0L..600L).map { t -> Route.Point(t, kotlin.math.sin(t * 0.7) * 5 / 111_195.0, kotlin.math.cos(t * 0.7) * 5 / 111_195.0, 5f) }
        assertEquals(0.0, Route.distance(home), 0.0)
        assertTrue(Route.track(home).size == 1)
    }

    @Test
    fun noRouteUntilGpsSettles() {
        assertTrue(Route.distance(run(3)).let { it == 0.0 })
        assertTrue(Route.clean(run(100).map { it.copy(accuracy = 25f) }).isEmpty())
    }

    @Test
    fun pausedStretchesDoNotCount() {
        assertEquals(240.0, Route.distance(run(100), listOf(40L..60L)), 7.0)
    }

    @Test
    fun splitsEveryKilometre() {
        val s = Route.splits(run(1000))
        assertEquals(3, s.size)
        assertEquals(1000.0, s[0].meters, 12.0)
        // 3 m/s is 5′33″ a kilometre.
        assertEquals(333.0, s[0].pace, 2.0)
        assertTrue(s[2].meters < 1000)
    }

    @Test
    fun paceAndItsFormat() {
        assertEquals(333.0, Route.currentPace(run(100))!!, 2.0)
        assertEquals("5′33″", Route.formatPace(333.0))
        assertNull(Route.formatPace(null))
    }

    /**
     * Fixes as a phone gives them. Accuracy is a 68 % radius, so each axis is off by about accuracy / 1.5 in all; most
     * of that error drifts slowly (multipath, satellites moving: [drift] of it carried over each second), the rest is
     * fresh each fix. Drifting error is what lets skipping fixes cut corners rather than zigzag.
     */
    private class Gps(seed: Long, val accuracy: Float = 5f, val drift: Double = 0.9) {
        val rnd = Random(seed)
        private val sigma = accuracy / 1.5
        private var e = rnd.nextGaussian() * sigma * 0.8
        private var n = rnd.nextGaussian() * sigma * 0.8

        fun fix(t: Long, east: Double, north: Double): Route.Point {
            val step = sigma * 0.8 * sqrt(1 - drift * drift)
            e = drift * e + rnd.nextGaussian() * step
            n = drift * n + rnd.nextGaussian() * step
            val white = sigma * 0.6
            return Route.Point(t, (north + n + rnd.nextGaussian() * white) / 111_195.0, (east + e + rnd.nextGaussian() * white) / 111_195.0, accuracy)
        }
    }

    /** A walk at [speed] m/s, one fix a second: [legs] metres east, then north, then west, then south. */
    private fun walk(legs: List<Double>, speed: Double, seed: Long, doppler: Boolean = false): Pair<List<Route.Point>, Double> {
        val gps = Gps(seed)
        val rnd = gps.rnd
        val headings = listOf(1.0 to 0.0, 0.0 to 1.0, -1.0 to 0.0, 0.0 to -1.0)
        val seconds = (legs.sum() / speed).toLong()
        val points = (0..seconds).map { t ->
            var left = t * speed
            var e = 0.0
            var n = 0.0
            var heading = headings[0]
            for ((i, leg) in legs.withIndex()) {
                heading = headings[i % 4]
                val d = minOf(left, leg)
                e += heading.first * d
                n += heading.second * d
                left -= d
                if (left <= 0) break
            }
            gps.fix(t, e, n).let { p ->
                if (!doppler) p
                else p.copy(
                    speed = (speed + rnd.nextGaussian() * 0.3).toFloat(), speedAccuracy = 0.5f,
                    bearing = Math.toDegrees(kotlin.math.atan2(heading.first, heading.second)).toFloat(), bearingAccuracy = 10f,
                )
            }
        }
        return points to seconds * speed
    }

    /** The rule [Route.track] had before smoothing: a fix counts once it's further than both fixes' error from the last. */
    private fun skippingDistance(points: List<Route.Point>): Double {
        val kept = mutableListOf<Route.Point>()
        for (p in Route.clean(points)) {
            val last = kept.lastOrNull()
            if (last == null || Route.meters(last, p) >= maxOf(Route.MIN_MOVE_M, (last.accuracy + p.accuracy).toDouble())) kept += p
        }
        return kept.zipWithNext().sumOf { (a, b) -> Route.meters(a, b) }
    }

    /** Mean absolute error in metres over ten noisy walks, smoothed and by the old rule. */
    private fun errors(legs: List<Double>, speed: Double, doppler: Boolean = false): Pair<Double, Double> {
        val runs = (1L..10L).map { walk(legs, speed, it, doppler) }
        fun error(measure: (List<Route.Point>) -> Double) = runs.map { (p, truth) -> abs(measure(p) - truth) }.average()
        return error { Route.distance(it) } to error(::skippingDistance)
    }

    @Test
    fun aNoisyStraightLineMeasuresTrue() {
        val (smoothed, skipping) = errors(listOf(400.0), 1.4)
        assertTrue("smoothed off by $smoothed m", smoothed < 10.0)
        assertTrue("smoothed $smoothed m, skipping $skipping m", smoothed < skipping / 2)
    }

    @Test
    fun cornersAreNotCut() {
        // 100 m east then 100 m north at walking pace.
        val (smoothed, skipping) = errors(listOf(100.0, 100.0), 1.4)
        assertTrue("smoothed off by $smoothed m", smoothed < 8.0)
        assertTrue("smoothed $smoothed m, skipping $skipping m", smoothed < skipping)
    }

    @Test
    fun dopplerFollowsARunnersCorners() {
        // A 400 m square at 3.5 m/s: positions alone round the corners off; speed and bearing hold them.
        val (withDoppler, _) = errors(listOf(100.0, 100.0, 100.0, 100.0), 3.5, doppler = true)
        val (without, _) = errors(listOf(100.0, 100.0, 100.0, 100.0), 3.5)
        assertTrue("with Doppler off by $withDoppler m", withDoppler < 8.0)
        assertTrue("with $withDoppler m, without $without m", withDoppler < without)
    }

    @Test
    fun standingStillWithRandomJitterAddsNothing() {
        // Fresh error each fix, the hardest case: a slow drift moves a bench less per second.
        val gps = Gps(7, drift = 0.0)
        val rnd = gps.rnd
        val bench = (0L..600L).map { gps.fix(it, 0.0, 0.0) }
        assertEquals(0.0, Route.distance(bench), 2.0)
        // And with the Doppler nought a still phone reports, no bearing.
        val withSpeed = bench.map { it.copy(speed = abs(rnd.nextGaussian() * 0.1).toFloat()) }
        assertEquals(0.0, Route.distance(withSpeed), 2.0)
    }

    @Test
    fun aStopAtTheLightsAddsLittle() {
        // 100 m, two minutes standing, 100 m more. Drifting error looks like a slow shuffle, so some gets in, and more
        // around the stop than with Doppler; skipping fixes zigzags along the drift for far more.
        val runs = (11L..15L).map { seed ->
            val gps = Gps(seed)
            (0L..262L).map { t ->
                val along = when {
                    t <= 71 -> t * 1.4
                    t <= 191 -> 100.0
                    else -> 100.0 + (t - 191) * 1.4
                }
                gps.fix(t, minOf(along, 100.0), maxOf(0.0, along - 100.0))
            }
        }
        val smoothed = runs.map { abs(Route.distance(it) - 199.0) }.average()
        val skipping = runs.map { abs(skippingDistance(it) - 199.0) }.average()
        assertTrue("smoothed off by $smoothed m", smoothed < 15.0)
        assertTrue("smoothed $smoothed m, skipping $skipping m", smoothed < skipping / 3)
    }

    @Test
    fun aJumpIsRejected() {
        val (points, _) = walk(listOf(300.0), 1.4, seed = 3)
        val clean = Route.distance(points)
        val jumped = points.toMutableList().also { it[100] = it[100].copy(lat = it[100].lat + 200 / 111_195.0) }
        assertEquals(clean, Route.distance(jumped), 1.0)
    }

    @Test
    fun aPauseOrTunnelRestartsTheFilter() {
        // Twenty seconds without fixes mid-run: the straight line across the gap still counts.
        val points = run(100).filter { it.t !in 40L..59L }
        assertEquals(300.0, Route.distance(points), 2.0)
    }

    @Test
    fun theRecordedWalk() {
        // native 01 §5.1: the stored route measured 176.0 m by skipping fixes; Zepp's live filter says 180.0 m. Its three
        // legs measure about 57 + 44 + 88 m corner to corner, so a little under 189 m once the corners are rounded.
        val lines = javaClass.getResource("/routes/walk-2026-10-22.csv")!!.readText().lines()
        val points = lines.filter { it.isNotBlank() && it[0].isDigit() }.map { l ->
            val (t, e, n, acc) = l.split(',')
            Route.Point(t.toLong(), n.toDouble() / 111_195.0, e.toDouble() / 111_195.0, acc.toFloat())
        }
        assertEquals(134, points.size)
        assertEquals(176.0, skippingDistance(points), 0.5)
        val smoothed = Route.distance(points)
        assertTrue("smoothed $smoothed m", smoothed in 178.0..188.0)
    }

    @Test
    fun climbCountsRisesPastTheNoise() {
        fun at(t: Long, h: Double?, acc: Float = 5f) = Route.Point(t, 51.5, -0.1, acc, altitude = h)
        // Up 10 m, down 6, up 8: 18 m climbed. The 2 m wobbles along the way don't count.
        val hill = listOf(100.0, 102.0, 101.0, 105.0, 110.0, 108.0, 104.0, 106.0, 104.0, 112.0).mapIndexed { i, h -> at(i.toLong(), h) }
        assertEquals(18.0, Route.climb(hill)!!, 1e-6)
        // Flat with noise under the step: nothing.
        assertEquals(0.0, Route.climb(listOf(100.0, 102.0, 100.0, 103.0, 101.0).mapIndexed { i, h -> at(i.toLong(), h) })!!, 1e-6)
        // A bad fix's height is left out; no heights at all is no climb.
        assertEquals(0.0, Route.climb(listOf(at(0, 100.0), at(1, 150.0, acc = 80f), at(2, 101.0)))!!, 1e-6)
        assertNull(Route.climb(listOf(at(0, null), at(1, null))))
    }
}
