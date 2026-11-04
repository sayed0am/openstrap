package org.openstrap.app.data

/**
 * A workout noticed as it happens, so nobody has to remember Start: the same rule as [WorkoutSuggestions] (at least
 * [WorkoutSuggestions.MIN_MINUTES] minutes at [WorkoutSuggestions.MIN_HRR] of heart-rate reserve), but on live
 * readings, and only for a stretch still going on. The recording it offers begins where the effort began.
 * Pure Kotlin, like [WorkoutSuggestions].
 */
object AutoStart {
    /** While idle, live heart rate is sampled for [PROBE_S] every [PROBE_EVERY_S], unless effort keeps it streaming. */
    const val PROBE_EVERY_S = 180L
    const val PROBE_S = 30L
    /** Steps a minute that mean walking with purpose: worth streaming heart rate at once. */
    const val BRISK_STEPS = 90
    const val RUN_STEPS = 140
    /** A stretch whose last qualifying minute is older than this has ended; it's for the after-the-fact suggestion. */
    const val ONGOING_S = 2 * 60L

    /** "Looks like a workout": a stretch from [start] that is still going, its average and a sport to record it as. */
    data class Offer(val start: Long, val averageHeartRate: Int, val sportType: Int)

    /** The heart rate at which a probe keeps streaming: the bottom of the effort the rule counts. */
    fun threshold(restingHr: Int, maxHr: Int): Int = Math.round(restingHr + WorkoutSuggestions.MIN_HRR * (maxHr - restingHr))

    /** Live readings (epoch seconds, bpm) as one average a minute, at minute starts, for [WorkoutSuggestions.find]. */
    fun minutes(readings: List<Pair<Long, Int>>): List<Pair<Long, Int>> =
        readings.groupBy { it.first / 60 * 60 }.map { (t, r) -> t to Math.round(r.map { it.second }.average()).toInt() }.sortedBy { it.first }

    /**
     * The effort under way at [now] in [readings], or null. [stepsPerMinute] is the cadence of the last minutes,
     * which names the sport; [notBefore] hides what came before it (a workout's end, a "Not now").
     */
    fun offer(readings: List<Pair<Long, Int>>, restingHr: Int, maxHr: Int, stepsPerMinute: Int?, now: Long, notBefore: Long = 0): Offer? {
        val shown = readings.filter { it.first >= notBefore }
        // The offer reads only the stretch's times and average, never its zone, so the standard zones do.
        val s = WorkoutSuggestions.find(minutes(shown), restingHr, maxHr, emptyList(), HeartZones.STANDARD).firstOrNull() ?: return null
        if (now - s.end > ONGOING_S) return null
        return Offer(s.start, s.averageHeartRate, sport(stepsPerMinute))
    }

    /** Outdoor run, walk, or free training when the feet aren't what's moving (a bike, weights). */
    fun sport(stepsPerMinute: Int?): Int = when {
        stepsPerMinute == null -> 5
        stepsPerMinute >= RUN_STEPS -> 1
        stepsPerMinute >= BRISK_STEPS -> 3
        else -> 5
    }

    /** Whether streaming should go on after a probe: the heart rate is up, or the feet are busy. */
    fun keepStreaming(recent: List<Pair<Long, Int>>, threshold: Int, stepsPerMinute: Int?): Boolean =
        (stepsPerMinute ?: 0) >= BRISK_STEPS || recent.takeLast(10).let { it.isNotEmpty() && it.map { r -> r.second }.average() >= threshold }
}
