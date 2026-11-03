package org.openstrap.app.device

import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.db.AppWorkout

/**
 * A phone workout's distance over the range it shows, pauses left out: GPS once its fixes settle, else the strap's
 * steps times a stride. An early End, a trim and its undo all work it out again, so what lies outside the range (a car
 * drive after a walk, say) never counts.
 */
internal object WorkoutDistance {

    /**
     * What a workout's distance is worked out from. [route] and [stepTotals] (the strap's running totals, oldest
     * first) begin at [routeSince] and [stepsSince]: the start, or where a restart picked the workout up. [before] is
     * the workout as stored at that restart, whose distance covers the time memory doesn't.
     */
    data class Track(
        val route: List<Route.Point>,
        val stepTotals: List<Pair<Long, Int>>,
        val heightCm: Int?,
        val routeSince: Long,
        val stepsSince: Long,
        val before: AppWorkout? = null,
    )

    /**
     * Metres over [w]'s shown range (to [now] while it records), and whether any came from steps. Null when nothing
     * says, so the stored figure stands.
     */
    fun shown(w: AppWorkout, now: Long, track: Track): Pair<Float, Boolean>? {
        val from = w.from
        val to = w.end ?: now
        val gps = Route.clean(track.route).isNotEmpty()
        val since = if (gps) track.routeSince else track.stepsSince
        val lo = maxOf(from, since)
        val memory = when {
            lo >= to -> null
            gps -> route(track.route, lo, to, w.pauseRanges) to false
            else -> steps(w, track, lo, to)?.let { it to true }
        }
        // Before a restart only the stored total is left, so the part of it the range keeps is its share of the time.
        val earlier = track.before?.takeIf { from < since }?.let { b ->
            b.distanceMeters?.let { m -> scaled(m, active(b, b.from, since), active(w, from, minOf(to, since))) to b.distanceFromSteps }
        }
        if (memory == null && earlier == null) return null
        return (memory?.first ?: 0f) + (earlier?.first ?: 0f) to (memory?.second == true || earlier?.second == true)
    }

    /** [w] with its distance over the range it shows ([shown]), or as it was when nothing says. */
    fun withShown(w: AppWorkout, now: Long, track: Track): AppWorkout =
        shown(w, now, track)?.let { (m, steps) -> w.copy(distanceMeters = m, distanceFromSteps = steps) } ?: w

    /**
     * [new]'s distance when nothing it was worked out from is left (a route that wasn't kept, after the app restarted):
     * [old]'s, by the share of moving time the new range keeps. Undo gives the old figure back.
     */
    fun scaledTo(old: AppWorkout, new: AppWorkout): Float? = old.distanceMeters?.let { m ->
        scaled(m, active(old, old.from, old.end ?: return@let m), active(new, new.from, new.end ?: return@let m))
    }

    /** Along the whole route's track, so a range cut from the middle settles no differently. */
    private fun route(points: List<Route.Point>, from: Long, to: Long, pauses: List<LongRange>): Float =
        Route.distance(points, pauses + listOf(Long.MIN_VALUE..from, to..Long.MAX_VALUE)).toFloat()

    /**
     * The strap's steps over [from] to [to] times a stride for their cadence and the wearer's height. Null without
     * either, and for sports not done on foot ([org.openstrap.app.data.SportTypes.ON_FOOT]).
     */
    private fun steps(w: AppWorkout, track: Track, from: Long, to: Long): Float? {
        val height = track.heightCm
        val taken = taken(track.stepTotals, from, to)
        if (height == null || taken <= 0 || w.sportType !in org.openstrap.app.data.SportTypes.ON_FOOT) return null
        val minutes = (active(w, from, to) / SECONDS_PER_MINUTE).coerceAtLeast(1f)
        return taken * org.openstrap.app.data.Activity.stepLength((taken / minutes).toInt(), height)
    }

    /**
     * Steps from the last total at or before [from] (else the first) to the last one by [to]. Only the rises count, so
     * the total going back to 0 at midnight costs nothing but the steps just before it.
     */
    fun taken(totals: List<Pair<Long, Int>>, from: Long, to: Long): Int {
        val first = totals.indexOfLast { it.first <= from }.coerceAtLeast(0)
        val window = totals.subList(first, totals.size).takeWhile { it.first <= to }
        return window.zipWithNext().sumOf { (a, b) -> (b.second - a.second).coerceAtLeast(0) }
    }

    /** [w]'s moving seconds from [from] to [to]. */
    private fun active(w: AppWorkout, from: Long, to: Long): Long =
        WorkoutRecorder.activeSeconds(w.copy(shownStart = from, end = to), to)

    private fun scaled(meters: Float, oldSeconds: Long, newSeconds: Long): Float =
        if (oldSeconds <= 0) meters else meters * newSeconds / oldSeconds

    private const val SECONDS_PER_MINUTE = 60f
}
