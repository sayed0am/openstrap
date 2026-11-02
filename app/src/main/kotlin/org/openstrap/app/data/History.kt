package org.openstrap.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import org.openstrap.app.presentation.Weeks
import kotlin.math.sqrt

/** Pure helpers for week, month and year views of a daily series; free of Android types so they're unit-testable. */
object History {
    /** Days of history that make up "your normal". */
    const val NORMAL_DAYS = 30

    /** Days with data needed before a normal range means anything. */
    const val MIN_NORMAL_DAYS = 7

    /** The usual range of a metric: mean ± one standard deviation of its daily values. */
    data class Normal(val mean: Float, val low: Float, val high: Float, val days: Int)

    /**
     * Your normal as of [end]: the [NORMAL_DAYS] days up to and including it. Null until [MIN_NORMAL_DAYS] of them
     * have data.
     */
    fun normal(stats: List<DayStat>, end: LocalDate, days: Int = NORMAL_DAYS): Normal? {
        val from = end.minusDays(days - 1L)
        val values = stats.filter { it.date in from..end }.map { it.value.toDouble() }
        if (values.size < MIN_NORMAL_DAYS) return null
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        return Normal(mean.toFloat(), (mean - sd).toFloat(), (mean + sd).toFloat(), values.size)
    }

    /** Where [value] sits against [normal]: -1 below it, 0 inside, 1 above; null when either is missing. */
    fun status(value: Float?, normal: Normal?): Int? = when {
        value == null || normal == null -> null
        value > normal.high -> 1
        value < normal.low -> -1
        else -> 0
    }

    /**
     * Averages daily stats into weeks starting on [firstDay] ([Weeks]), for the year view. Each week keeps its lowest
     * and highest day. The week's date is its first day.
     */
    fun weekly(stats: List<DayStat>, firstDay: DayOfWeek = Weeks.firstDay()): List<DayStat> =
        stats.groupBy { Weeks.start(it.date, firstDay) }
            .toSortedMap()
            .map { (first, days) ->
                val mean = days.map { it.value }.average().toFloat()
                DayStat(first, mean, days.minOf { it.value }, days.maxOf { it.value }, days.sumOf { it.count })
            }

    /** Average, lowest and highest of the daily values in [from]..[to]; null when there are none. */
    data class Summary(val average: Float, val lowest: DayStat, val highest: DayStat, val total: Float, val days: Int)

    fun summary(stats: List<DayStat>, from: LocalDate, to: LocalDate): Summary? {
        val inRange = stats.filter { it.date in from..to }
        if (inRange.isEmpty()) return null
        return Summary(
            average = inRange.map { it.value }.average().toFloat(),
            lowest = inRange.minBy { it.value },
            highest = inRange.maxBy { it.value },
            total = inRange.sumOf { it.value.toDouble() }.toFloat(),
            days = inRange.size,
        )
    }
}
