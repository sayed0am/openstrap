package org.openstrap.app.data

/**
 * Games and sports played with the phone in a bag (workouts spec 2.7): how hard it has been, from the time in each
 * zone, and the hard efforts in it. Pure, like [Strength].
 */
object Games {
    /** A spell in zone 5 counts as a hard effort from this long. */
    const val HARD_EFFORT_S = 10L

    /**
     * Effort 1 to 5 from [zoneSeconds], the seconds in each zone so far: the time-weighted average zone, rounded, at
     * least 1. Null with no time in any zone yet.
     */
    fun effort(zoneSeconds: IntArray): Int? {
        val total = zoneSeconds.sum()
        if (total <= 0) return null
        val mean = zoneSeconds.withIndex().sumOf { (z, s) -> (z + 1) * s.toDouble() } / total
        return Math.round(mean).toInt().coerceIn(1, Workouts.ZONES)
    }

    /**
     * Spells of at least [HARD_EFFORT_S] in zone 5 in [readings] (time, bpm, ascending): one effort per spell, however
     * long. A gap of more than a few seconds between readings ends a spell.
     */
    fun hardEfforts(readings: List<Pair<Long, Int>>, maxHr: Int): Int {
        var count = 0
        var since: Long? = null
        var last: Long? = null
        var counted = false
        for ((t, bpm) in readings) {
            val top = WorkoutRecorder.liveZone(bpm, maxHr) == Workouts.ZONES - 1
            val gap = last != null && t - last > WorkoutRecorder.MAX_STEP_S
            if (!top || gap) { since = null; counted = false }
            if (top) {
                if (since == null) since = t
                if (!counted && t - since >= HARD_EFFORT_S) { count++; counted = true }
            }
            last = t
        }
        return count
    }
}
