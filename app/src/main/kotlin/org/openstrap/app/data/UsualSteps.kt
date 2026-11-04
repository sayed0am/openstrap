package org.openstrap.app.data

import java.time.LocalDate

/**
 * Your usual steps by this time of day (spec 2.7, "compare by pace"): from past days' steps hour by hour, how many
 * you've usually walked by the end of each hour. Plain statistics over stored minutes, no model: each worn day's
 * running total by the hour, and the median of those totals across the days, hour by hour. The median, not the mean,
 * so one long hike or one day mostly at a desk doesn't move it. Pure Kotlin, like [Activity].
 */
object UsualSteps {
    /** How many days before the day shown it looks back over. */
    const val DAYS = 28

    /** It needs this many worn days before there's a usual. */
    const val MIN_DAYS = 7

    /** A day with fewer steps than this counts as not worn and is left out. */
    const val MIN_DAY_STEPS = 500

    /** By this time of day you've usually walked fewer steps than this, too few for a comparison to mean much. */
    const val MIN_USUAL = 500f

    /** Within this share of your usual counts as about usual: the band Today uses for Load (`TodayCopy.LOAD_BAND`). */
    const val BAND = 0.25f

    /**
     * Your usual running total at the end of each hour (24 values, the first the end of 00:00 to 01:00), from [days]:
     * each past day's steps hour by hour. Days before [before] only, the last [DAYS] of them, and only worn ones (at
     * least [MIN_DAY_STEPS]). Null with fewer than [MIN_DAYS] such days. The result never goes down from one hour to the
     * next, since each day's running total doesn't.
     */
    fun byHour(days: Map<LocalDate, List<Float>>, before: LocalDate): List<Float>? {
        val worn = days.filterKeys { it < before && it >= before.minusDays(DAYS.toLong()) }.values
            .filter { it.size == 24 && it.sum() >= MIN_DAY_STEPS }
        if (worn.size < MIN_DAYS) return null
        val running = worn.map { hours -> var sum = 0f; hours.map { sum += it; sum } }
        return (0 until 24).map { h -> median(running.map { it[h] }) }
    }

    /** Your usual by [hour]:[minute], between the end of the hour before and the end of this one. */
    fun at(usual: List<Float>, hour: Int, minute: Int): Float {
        val h = hour.coerceIn(0, 23)
        val before = if (h == 0) 0f else usual[h - 1]
        return before + (usual[h] - before) * minute.coerceIn(0, 59) / 60f
    }

    /** Today's steps against your usual by now. */
    enum class Pace { More, About, Fewer }

    /** [steps] so far against [usualNow], your usual by now; null when your usual by now is too small to compare with. */
    fun pace(steps: Int, usualNow: Float): Pace? = when {
        usualNow < MIN_USUAL -> null
        steps > usualNow * (1 + BAND) -> Pace.More
        steps < usualNow * (1 - BAND) -> Pace.Fewer
        else -> Pace.About
    }

    private fun median(values: List<Float>): Float = Stats.median(values) ?: 0f
}
