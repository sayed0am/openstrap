package org.openstrap.app.data

/**
 * Groups of Zepp's sport type ids ([Workouts.sportName] names each one), declared once so the engines, screens and
 * icons agree on what counts as a run or strength work.
 */
object SportTypes {
    const val OUTDOOR_RUN = 1
    const val TREADMILL = 2
    const val WALK = 3
    const val OUTDOOR_CYCLING = 4
    const val POOL_SWIM = 6
    const val OPEN_WATER_SWIM = 7
    const val INDOOR_CYCLING = 8
    const val TRAIL_RUN = 11
    const val HIKING = 15
    const val ROWING_MACHINE = 23
    const val INDOOR_FITNESS = 24
    const val INDOOR_WALK = 40
    const val CORE_TRAINING = 50
    const val STRENGTH_TRAINING = 52
    const val SPINNING = 143
    const val DOG_WALKING = 215
    const val HYROX_TRAINING = 222
    const val MOUNTAIN_HIKE = 224
    const val RUCKING = 225
    const val HYBRID_TRAINING = 226

    /** Runs, where distance comes with a pace. */
    val RUNS: Set<Int> = setOf(OUTDOOR_RUN, TREADMILL, TRAIL_RUN)

    /** Everything done on foot, where steps can stand in for distance. */
    val ON_FOOT: Set<Int> = RUNS + setOf(WALK, HIKING, INDOOR_WALK, DOG_WALKING, MOUNTAIN_HIKE, RUCKING)

    /** On foot but not running: walks, hikes and rucking, where distance alone says it. */
    val WALKS: Set<Int> = ON_FOOT - RUNS

    /** Hikes, a kind of walk with a noun and an icon of their own. */
    val HIKES: Set<Int> = setOf(HIKING, MOUNTAIN_HIKE)

    val RIDES: Set<Int> = setOf(OUTDOOR_CYCLING, INDOOR_CYCLING, SPINNING)
    val SWIMS: Set<Int> = setOf(POOL_SWIM, OPEN_WATER_SWIM)

    /** Sports counted in sets rather than distance ([Strength]). */
    val STRENGTH: Set<Int> = setOf(INDOOR_FITNESS, CORE_TRAINING, STRENGTH_TRAINING, HYROX_TRAINING, HYBRID_TRAINING)
}
