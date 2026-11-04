package org.openstrap.app.data

/**
 * Workouts nobody started, found in the strap's heart rate after a sync: any awake stretch of at least
 * [MIN_MINUTES] minutes at [MIN_HRR] of heart-rate reserve or more, the rule [Activity.mvpa] counts as
 * heart-rate activity, that isn't already a workout. WHOOP needs about 15 minutes; this catches shorter sessions.
 * Pure Kotlin, like [Recovery].
 */
object WorkoutSuggestions {
    const val MIN_MINUTES = 10
    const val MIN_HRR = Activity.HR_MODERATE_HRR
    /** A dip this many minutes long (a rest between sets, a traffic light) doesn't split a session. */
    const val MAX_GAP_MINUTES = 3

    /** A stretch worth offering: [start] to [end] (epoch seconds), its average heart rate and the zone it was mostly in. */
    data class Suggestion(val start: Long, val end: Long, val averageHeartRate: Int, val zone: Int) {
        val minutes: Int get() = ((end - start) / 60).toInt()
    }

    /**
     * Stretches in [minuteHr] (minute starts, ascending, one value a minute) that qualify, newest first. Minutes in
     * [busy] (sleep, workouts already saved, stretches answered "Not a workout") never count and split a stretch.
     */
    fun find(minuteHr: List<Pair<Long, Int>>, restingHr: Int, maxHr: Int, busy: List<LongRange>, zones: HeartZones): List<Suggestion> {
        val threshold = restingHr + MIN_HRR * (maxHr - restingHr)
        fun isBusy(t: Long) = busy.any { t + 59 >= it.first && t <= it.last }
        val out = mutableListOf<Suggestion>()
        var run = mutableListOf<Pair<Long, Int>>()
        fun close() {
            if (run.size >= MIN_MINUTES) {
                val byZone = run.groupingBy { WorkoutRecorder.liveZoneOf(it.second, maxHr, zones) }.eachCount()
                val average = Math.round(run.map { it.second }.average()).toInt()
                out += Suggestion(run.first().first, run.last().first + 60, average, byZone.maxBy { it.value }.key)
            }
            run = mutableListOf()
        }
        for ((t, bpm) in minuteHr) {
            if (isBusy(t)) { close(); continue }
            if (bpm < threshold) {
                // Too long below the line since the last qualifying minute ends the stretch.
                if (run.isNotEmpty() && t - run.last().first > MAX_GAP_MINUTES * 60L) close()
                continue
            }
            if (run.isNotEmpty() && t - run.last().first > (MAX_GAP_MINUTES + 1) * 60L) close()
            run += t to bpm
        }
        close()
        return out.sortedByDescending { it.start }
    }
}
