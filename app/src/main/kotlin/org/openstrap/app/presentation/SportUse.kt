package org.openstrap.app.presentation

import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts

/** A sport someone has done, for the start card: how often and when last. */
data class SportUse(val sportType: Int, val count: Int, val lastStart: Long?)

/**
 * The 3 most-used sports across both kinds of workout, each as it starts again ([Workouts.startable]), else three
 * sensible defaults.
 */
fun topSports(workouts: List<WorkoutAnalysis>, n: Int = 3): List<SportUse> {
    // The strap's own "Activity" records and sports not offered aren't a sport to offer.
    val used = workouts.mapNotNull { w -> Workouts.startable(w.workout.sportType)?.let { it to w } }.groupBy({ it.first }, { it.second })
        .map { (type, ws) -> SportUse(type, ws.size, ws.maxOf { it.workout.start }) }
        .sortedWith(compareByDescending<SportUse> { it.count }.thenByDescending { it.lastStart })
        .take(n)
    return used + Workouts.DEFAULT_SPORTS.filter { d -> used.none { it.sportType == d } }.take(n - used.size).map { SportUse(it, 0, null) }
}

/**
 * The sport you usually do: the most frequent in [workouts] (sport, start), ties to the latest, as it starts again
 * ([Workouts.startable]). Null without any. Callers pass workouts you started, so a walk the strap noticed doesn't count.
 */
fun usualSport(workouts: List<Pair<Int, Long>>): Int? =
    workouts.mapNotNull { (type, start) -> Workouts.startable(type)?.let { it to start } }.groupBy { it.first }
        .maxWithOrNull(compareBy<Map.Entry<Int, List<Pair<Int, Long>>>> { it.value.size }.thenBy { e -> e.value.maxOf { it.second } })
        ?.key
