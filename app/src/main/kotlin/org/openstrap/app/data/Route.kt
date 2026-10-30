package org.openstrap.app.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A workout's GPS route, worked out in pure Kotlin like [Recovery]: distance, splits and the map's stretches.
 * The phone's fixes are filtered before they count: nothing until GPS has settled, poor accuracy and impossible jumps
 * are dropped, then the rest are smoothed both ways in time ([track]) and standing still adds no distance.
 */
object Route {
    /**
     * One GPS fix: epoch seconds, degrees, and the horizontal accuracy in metres. The rest is whatever the phone gave
     * (null when it gave none): Doppler [speed] in m/s, [bearing] in degrees clockwise from north, [altitude] in metres
     * above the WGS84 ellipsoid, and the 68 % accuracy of speed (m/s) and bearing (degrees). Speed and bearing let
     * [track] follow turns without trusting each position more (native 02 §3.7, §9).
     */
    data class Point(
        val t: Long,
        val lat: Double,
        val lon: Double,
        val accuracy: Float,
        val speed: Float? = null,
        val bearing: Float? = null,
        val altitude: Double? = null,
        val speedAccuracy: Float? = null,
        val bearingAccuracy: Float? = null,
    )

    /** Fixes worse than this are kept for the map's accuracy ring but not counted. */
    const val MAX_ACCURACY_M = 30f
    /** Faster than this between two fixes is a GPS jump, not a person (about 54 km/h, a fast descent on a bike). */
    const val MAX_SPEED_MS = 15.0
    /** The route begins once this many fixes in a row agree: the first after a cold start can be far off. */
    const val SETTLE_FIXES = 5
    /** How accurate those settling fixes must be. */
    const val SETTLED_ACCURACY_M = 20f
    /** Least disagreement allowed between settling fixes, however good they claim to be. */
    const val MIN_MOVE_M = 5.0
    /** Plausible on foot between two fixes while settling (a sprint), so a runner's start still settles. */
    private const val SETTLE_SPEED_MS = 8.0

    private const val EARTH_M = 6_371_000.0

    /** Great-circle distance in metres. */
    fun meters(a: Point, b: Point): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * The fixes that count, in time order: from the first of [SETTLE_FIXES] in a row that are within
     * [SETTLED_ACCURACY_M] and agree with each other, then accurate enough and no faster than [MAX_SPEED_MS] from the
     * last kept one.
     */
    fun clean(points: List<Point>): List<Point> {
        val good = points.filter { it.accuracy <= MAX_ACCURACY_M }.sortedBy { it.t }.distinctBy { it.t }
        var run = 0
        val start = good.indices.firstOrNull { i ->
            val p = good[i]
            val agrees = p.accuracy <= SETTLED_ACCURACY_M &&
                (run == 0 || meters(good[i - 1], p) <= noise(good[i - 1], p) + SETTLE_SPEED_MS * (p.t - good[i - 1].t))
            run = if (agrees) run + 1 else if (p.accuracy <= SETTLED_ACCURACY_M) 1 else 0
            run >= SETTLE_FIXES
        }?.let { it - SETTLE_FIXES + 1 } ?: return emptyList()
        val out = mutableListOf<Point>()
        for (p in good.subList(start, good.size)) {
            val last = out.lastOrNull()
            if (last != null && meters(last, p) / (p.t - last.t) > MAX_SPEED_MS) continue
            out += p
        }
        return out
    }

    /**
     * [clean], smoothed: what distance, pace, splits and the map's line are measured along. Every fix is kept and pulled
     * toward where its neighbours say it was ([smooth]), so bends aren't cut the way skipping fixes cut them (native 01
     * §5.1); a stretch of standing still ([still]) is left as its first fix, so a phone's wander adds nothing.
     */
    fun track(points: List<Point>): List<Point> {
        val smoothed = smooth(clean(points))
        val still = still(smoothed)
        return smoothed.filterIndexed { i, _ -> !still[i] || i == 0 || !still[i - 1] }
    }

    /**
     * Process noise (white acceleration, m/s^1.5) when fixes carry Doppler velocity: Zepp's q (native 02 §3.7), free
     * enough to follow a runner's corners, since the velocity measurement keeps it from chasing position noise.
     */
    private const val Q_DOPPLER = 2.0
    /**
     * Without Doppler, positions alone must tell motion from noise, so the filter is held steadier. Tuned on simulated
     * walks (RouteTest): higher follows the wander at walking pace, lower rounds off a runner's corners.
     */
    private const val Q_POSITION = 0.5
    /** Floor on a fix's claimed accuracy: phones claim 1–2 m that GPS doesn't deliver. */
    private const val MIN_SIGMA_M = 3.0
    /** Doppler velocity σ when the phone gives no speed accuracy, as Zepp assumes (native 02 §3.7). */
    private const val DOPPLER_SIGMA_MS = 2.0
    /** Floor on the velocity σ, and the most a velocity may be off and still be measured. */
    private const val MIN_DOPPLER_SIGMA_MS = 0.5
    private const val MAX_DOPPLER_SIGMA_MS = 2.0

    /**
     * Below this average speed over [STILL_WINDOW_S] of the smoothed track, or by the phone's Doppler, you're standing
     * still. Zepp drops < 0.2 m/s between raw fixes (native 01 §3.3); over a window of smoothed fixes the wander still
     * shows as a slow shuffle, so the line sits higher, well under the slowest walk (about 0.8 m/s).
     */
    const val STILL_SPEED_MS = 0.4
    /** Long enough that drifting GPS error averages out, short enough that a stop at the lights shows. */
    const val STILL_WINDOW_S = 20L

    /**
     * A constant-velocity Kalman filter and RTS smoother over [points] (already [clean]ed), as Zepp smooths a synced
     * route (native 02 §3.7): state is position and velocity in local metres east and north; each fix is measured with
     * its own accuracy as σ, and its Doppler speed and bearing, when the phone gave them and they're good, as a
     * velocity. The backward pass means a point is placed from the fixes after it as well as before, so there's no lag
     * and corners are rounded by metres, not cut. Only lat and lon change; the rest of each fix is as received. The
     * route is cut where fixes stop for more than 5 × the usual interval (a pause, a tunnel) and each piece is smoothed
     * alone. Positions are flat-earth about the first fix, as Zepp's live filter (native 01 §3.3); fine over any
     * distance run on foot.
     *
     * The live distance runs this too, over the route so far: its newest point is then the forward filter's estimate,
     * and earlier ones are refined as fixes come in, at the cost of one pass over the route per save, as before.
     */
    fun smooth(points: List<Point>): List<Point> {
        if (points.size < 2) return points
        val lat0 = Math.toRadians(points[0].lat)
        val perDegNorth = Math.toRadians(1.0) * EARTH_M
        val perDegEast = perDegNorth * cos(lat0)
        val east = DoubleArray(points.size) { (points[it].lon - points[0].lon) * perDegEast }
        val north = DoubleArray(points.size) { (points[it].lat - points[0].lat) * perDegNorth }
        val velocity = points.map(::velocity)
        val gaps = points.zipWithNext { a, b -> b.t - a.t }.sorted()
        val maxGap = 5 * maxOf(1L, gaps[gaps.size / 2])
        val outE = DoubleArray(points.size)
        val outN = DoubleArray(points.size)
        var from = 0
        for (i in 1..points.size) {
            if (i < points.size && points[i].t - points[i - 1].t <= maxGap) continue
            val q = if (velocity.subList(from, i).count { it != null } * 2 >= i - from) Q_DOPPLER else Q_POSITION
            smoothPiece(points, from, i, east, north, velocity, q, outE, outN)
            from = i
        }
        return points.mapIndexed { i, p -> p.copy(lat = points[0].lat + outN[i] / perDegNorth, lon = points[0].lon + outE[i] / perDegEast) }
    }

    /** A fix's Doppler velocity (east, north, σ), or null without one worth measuring. */
    private class Velocity(val east: Double, val north: Double, val sigma: Double)

    private fun velocity(p: Point): Velocity? {
        val speed = p.speed?.toDouble() ?: return null
        val sigma = maxOf(MIN_DOPPLER_SIGMA_MS, p.speedAccuracy?.toDouble() ?: DOPPLER_SIGMA_MS)
        val bearing = p.bearing
        // Standing still a phone often gives speed but no bearing; nought is a velocity either way.
        if (bearing == null) return if (speed < STILL_SPEED_MS) Velocity(0.0, 0.0, sigma).takeIf { sigma <= MAX_DOPPLER_SIGMA_MS } else null
        // A bearing off by θ puts the velocity speed·θ off sideways.
        val across = speed * Math.toRadians(p.bearingAccuracy?.toDouble() ?: 0.0)
        val s = maxOf(sigma, across)
        if (s > MAX_DOPPLER_SIGMA_MS) return null
        val b = Math.toRadians(bearing.toDouble())
        return Velocity(speed * sin(b), speed * cos(b), s)
    }

    /**
     * Forward filter then RTS pass over points[from until to]. East and north share one covariance: they're measured
     * with the same σ and moved by the same noise, so one 2 × 2 (position, velocity) matrix serves both axes.
     */
    private fun smoothPiece(
        points: List<Point>, from: Int, to: Int, east: DoubleArray, north: DoubleArray, velocity: List<Velocity?>, q: Double,
        outE: DoubleArray, outN: DoubleArray,
    ) {
        val n = to - from
        // Filtered and predicted state per point: [pE, vE, pN, vN] and covariance [pp, pv, vv].
        val xf = Array(n) { DoubleArray(4) }
        val xp = Array(n) { DoubleArray(4) }
        val pf = Array(n) { DoubleArray(3) }
        val pp = Array(n) { DoubleArray(3) }
        val dts = DoubleArray(n)
        val x = DoubleArray(4)
        // Unknown at first: anywhere, at any walking or running speed.
        var c = doubleArrayOf(1e6, 0.0, 100.0)
        x[0] = east[from]; x[2] = north[from]
        for (k in 0 until n) {
            val i = from + k
            if (k > 0) {
                val dt = (points[i].t - points[i - 1].t).toDouble()
                dts[k] = dt
                x[0] += x[1] * dt
                x[2] += x[3] * dt
                val q2 = q * q
                c = doubleArrayOf(
                    c[0] + 2 * dt * c[1] + dt * dt * c[2] + q2 * dt * dt * dt / 3,
                    c[1] + dt * c[2] + q2 * dt * dt / 2,
                    c[2] + q2 * dt,
                )
            }
            x.copyInto(xp[k])
            c.copyInto(pp[k])
            // Position: Android's accuracy is a 68 % radius, taken as σ on each axis as Zepp takes hacc (native 02 §3.7).
            val sigma = maxOf(MIN_SIGMA_M, points[i].accuracy.toDouble())
            c = update(x, c, sigma * sigma, east[i], north[i], position = true)
            velocity[i]?.let { v -> c = update(x, c, v.sigma * v.sigma, v.east, v.north, position = false) }
            x.copyInto(xf[k])
            c.copyInto(pf[k])
        }
        val xs = xf[n - 1].copyOf()
        outE[to - 1] = xs[0]; outN[to - 1] = xs[2]
        for (k in n - 2 downTo 0) {
            // Gain C = P_f · Fᵀ · P_p⁻¹ with F = [[1, dt], [0, 1]].
            val dt = dts[k + 1]
            val f = pf[k]
            // P_f · Fᵀ = [[a, b], [d, e]].
            val a = f[0] + dt * f[1]
            val b = f[1]
            val d = f[1] + dt * f[2]
            val e = f[2]
            val p = pp[k + 1]
            val det = p[0] * p[2] - p[1] * p[1]
            val i00 = p[2] / det; val i01 = -p[1] / det; val i11 = p[0] / det
            val c00 = a * i00 + b * i01; val c01 = a * i01 + b * i11
            val c10 = d * i00 + e * i01; val c11 = d * i01 + e * i11
            for (axis in 0..1) {
                val o = axis * 2
                val dp = xs[o] - xp[k + 1][o]
                val dv = xs[o + 1] - xp[k + 1][o + 1]
                xs[o] = xf[k][o] + c00 * dp + c01 * dv
                xs[o + 1] = xf[k][o + 1] + c10 * dp + c11 * dv
            }
            outE[from + k] = xs[0]; outN[from + k] = xs[2]
        }
    }

    /** One scalar measurement of position or velocity on both axes; [x] is updated in place, the covariance returned. */
    private fun update(x: DoubleArray, c: DoubleArray, r: Double, zE: Double, zN: Double, position: Boolean): DoubleArray {
        val o = if (position) 0 else 1
        val s = (if (position) c[0] else c[2]) + r
        // K = P·Hᵀ / S, H picking position or velocity.
        val kp = (if (position) c[0] else c[1]) / s
        val kv = (if (position) c[1] else c[2]) / s
        val innE = zE - x[o]
        val innN = zN - x[2 + o]
        x[0] += kp * innE; x[1] += kv * innE
        x[2] += kp * innN; x[3] += kv * innN
        val h = if (position) c[0] else c[1]
        val hv = if (position) c[1] else c[2]
        return doubleArrayOf(c[0] - kp * h, c[1] - kp * hv, c[2] - kv * hv)
    }

    /**
     * Whether each of [points] (smoothed) is standing still: the track moved less than [STILL_SPEED_MS] on average over
     * the [STILL_WINDOW_S] around it. Over a window, not between fixes, because a phone left on a bench still wanders
     * a metre or more a second, which is walking pace from one fix to the next. Near either end the window slides
     * inward rather than shrinking, since the smoother is least sure there.
     */
    fun still(points: List<Point>): BooleanArray {
        val out = BooleanArray(points.size)
        if (points.isEmpty()) return out
        val first = points.first().t
        val last = points.last().t
        var lo = 0
        var hi = 0
        for (i in points.indices) {
            val from = maxOf(first, minOf(points[i].t - STILL_WINDOW_S / 2, last - STILL_WINDOW_S))
            val to = minOf(last, from + STILL_WINDOW_S)
            while (points[lo].t < from) lo++
            while (hi + 1 < points.size && points[hi + 1].t <= to) hi++
            val span = points[hi].t - points[lo].t
            out[i] = span > 0 && meters(points[lo], points[hi]) < STILL_SPEED_MS * span || dopplerStill(points[i])
        }
        // A few seconds' "moving" between still stretches is the wander outrunning the window, not a walk.
        var i = 0
        while (i < points.size) {
            if (out[i]) { i++; continue }
            var j = i
            while (j + 1 < points.size && !out[j + 1]) j++
            val beside = (i > 0 && out[i - 1]) || (j + 1 < points.size && out[j + 1])
            if (beside && points[j].t - points[i].t < STILL_WINDOW_S / 2) for (k in i..j) out[k] = true
            i = j + 1
        }
        return out
    }

    /** The phone's own Doppler speed says still, which it measures far better than positions can (native 01 §3.3). */
    private fun dopplerStill(p: Point): Boolean {
        val speed = p.speed ?: return false
        return speed < STILL_SPEED_MS && (p.speedAccuracy ?: 0f) <= MAX_DOPPLER_SIGMA_MS
    }

    /** How far two fixes can lie apart from GPS error alone. */
    private fun noise(a: Point, b: Point): Double = maxOf(MIN_MOVE_M, (a.accuracy + b.accuracy).toDouble())

    /** Metres covered by [points] along [track], leaving out stretches that overlap [pauses]. */
    fun distance(points: List<Point>, pauses: List<LongRange> = emptyList()): Double =
        track(points).zipWithNext().sumOf { (a, b) -> if (paused(a, b, pauses)) 0.0 else meters(a, b) }

    /** Whether the stretch from [a] to [b] overlaps a pause; one that only ends or begins at a pause's edge counts. */
    private fun paused(a: Point, b: Point, pauses: List<LongRange>) = pauses.any { it.first < b.t && it.last > a.t }

    /** One kilometre (or the last part of one): when it ended, its time and metres. */
    data class Split(val index: Int, val endT: Long, val seconds: Long, val meters: Double) {
        /** Seconds per kilometre. */
        val pace: Double get() = if (meters > 0) seconds / (meters / 1000) else 0.0
    }

    /** Per-kilometre splits along [points]; the last one may be partial. Paused time doesn't count. */
    fun splits(points: List<Point>, pauses: List<LongRange> = emptyList(), km: Double = 1000.0): List<Split> {
        val clean = track(points)
        val out = mutableListOf<Split>()
        var startT = clean.firstOrNull()?.t ?: return out
        var meters = 0.0
        var seconds = 0L
        clean.zipWithNext().forEach { (a, b) ->
            if (paused(a, b, pauses)) return@forEach
            meters += meters(a, b)
            seconds += b.t - a.t
            if (meters >= km) {
                out += Split(out.size, b.t, seconds, meters)
                meters = 0.0
                seconds = 0
                startT = b.t
            }
        }
        if (meters > 0) out += Split(out.size, clean.last().t, seconds, meters)
        return out
    }

    /** A rise counts toward the climb only once it is this many metres above the last low: GPS height is noisy. */
    const val CLIMB_STEP_M = 4.0

    /**
     * Metres climbed along [points]: the rises in GPS altitude, each counted once it clears [CLIMB_STEP_M] above the
     * last low, from fixes good enough to count ([MAX_ACCURACY_M]). Null without heights.
     */
    fun climb(points: List<Point>): Double? {
        val heights = points.filter { it.accuracy <= MAX_ACCURACY_M }.mapNotNull { it.altitude }
        if (heights.size < 2) return null
        var total = 0.0
        var low = heights.first()
        var high = low
        for (h in heights) {
            if (h > high) high = h
            if (high - low >= CLIMB_STEP_M && h <= high - CLIMB_STEP_M) {
                // Past a peak: the rise to it is climbed, and the next one starts from here.
                total += high - low
                low = h
                high = h
            } else if (h < low) {
                low = h
                high = h
            }
        }
        if (high - low >= CLIMB_STEP_M) total += high - low
        return total
    }

    /** Seconds per kilometre over the last [windowS] seconds of [points], or null with too little to say. */
    fun currentPace(points: List<Point>, windowS: Long = 30): Double? {
        val clean = track(points)
        val last = clean.lastOrNull() ?: return null
        val recent = clean.filter { it.t >= last.t - windowS }
        val m = recent.zipWithNext().sumOf { (a, b) -> meters(a, b) }
        val s = recent.last().t - recent.first().t
        return if (m < 10 || s <= 0) null else s / (m / 1000)
    }

    /** "5′42″" per kilometre, or null with no pace to show. */
    fun formatPace(secondsPerKm: Double?): String? {
        if (secondsPerKm == null || secondsPerKm <= 0 || secondsPerKm > 3600) return null
        val s = Math.round(secondsPerKm)
        return "%d′%02d″".format(s / 60, s % 60)
    }

    /**
     * The route cut into stretches of one zone each, for drawing it in zone colours: each fix takes the zone of the
     * nearest heart rate within [MAX_HR_GAP_S] (time, bpm, ascending), else none (−1, drawn grey).
     */
    fun byZone(points: List<Point>, hr: List<Pair<Long, Int>>, maxHr: Int): List<Pair<List<Point>, Int>> {
        val clean = track(points)
        if (clean.size < 2) return emptyList()
        val times = hr.map { it.first }
        fun zoneAt(t: Long): Int {
            var i = times.binarySearch(t).let { if (it < 0) -it - 1 else it }
            val near = listOf(i - 1, i).filter { it in hr.indices }.minByOrNull { kotlin.math.abs(hr[it].first - t) } ?: return -1
            if (kotlin.math.abs(hr[near].first - t) > MAX_HR_GAP_S) return -1
            return WorkoutRecorder.liveZone(hr[near].second, maxHr) ?: -1
        }
        val out = mutableListOf<Pair<MutableList<Point>, Int>>()
        clean.forEach { p ->
            val z = zoneAt(p.t)
            val last = out.lastOrNull()
            if (last != null && last.second == z) last.first += p
            else {
                // Each stretch starts where the last ended, so the line has no gaps.
                out += (mutableListOf<Point>().also { s -> last?.first?.lastOrNull()?.let(s::add); s += p }) to z
            }
        }
        return out.filter { it.first.size >= 2 }
    }

    private const val MAX_HR_GAP_S = 30L
}
