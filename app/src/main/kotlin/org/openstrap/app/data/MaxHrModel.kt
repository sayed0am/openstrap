// The numbers are the model's published coefficients and curve shapes, each explained in the KDoc beside it and in
// docs/algorithms/recovery.md.
@file:Suppress("MagicNumber")

package org.openstrap.app.data

import kotlin.math.abs
import kotlin.math.min

// Max heart rate: the tested value, else an estimate raised by peaks two days agree on, and where it came from.
// The constants are in Recovery.kt.

/**
 * Maximum heart rate: a [tested] value the user entered wins. Otherwise Tanaka's 208 − 0.7·age (18,712 subjects)
 * when the age is known, else the strap's own value (from the Zepp profile), else 190. Raised to [observedPeak]
 * when the user has actually gone higher (an equation's individual SD is 7-11 bpm, so raising is sound and
 * lowering is not), by at most [MAX_HR_RAISE] bpm.
 */
fun Recovery.maxHeartRate(age: Int?, strapMax: Int? = null, observedPeak: Int? = null, tested: Int? = null): Float {
    if (tested != null && tested in TESTED_MAX_RANGE) return tested.toFloat()
    val estimate = when {
        age != null && age in 10..100 -> 208f - 0.7f * age
        strapMax != null && strapMax in 140..225 -> strapMax.toFloat()
        else -> 190f
    }
    return if (observedPeak != null && observedPeak > estimate) min(observedPeak.toFloat(), estimate + MAX_HR_RAISE) else estimate
}

/**
 * The highest heart rate the user has really reached, from each day's artefact-filtered maximum [dailyMaxima]: the
 * second of the first two days (from the top) within [MAX_HR_AGREE_BPM] of each other. One day alone, however
 * high, never sets it. Null when no two days agree.
 */
fun Recovery.observedPeak(dailyMaxima: List<Int>): Int? {
    val sorted = dailyMaxima.sortedDescending()
    for (i in 0 until sorted.size - 1) if (sorted[i] - sorted[i + 1] <= MAX_HR_AGREE_BPM) return sorted[i + 1]
    return null
}

/**
 * A day's artefact-filtered maximum 1-minute HR: the highest minute in [HR_MIN_BPM]..[HR_MAX_BPM] with a
 * neighbouring minute within [ARTEFACT_JUMP_BPM] of it (a lone spike isn't a heartbeat). [minutes] ascending.
 */
fun Recovery.filteredMax(minutes: List<Pair<Long, Int>>): Int? = minutes.indices.filter { i ->
    val (t, bpm) = minutes[i]
    bpm in HR_MIN_BPM..HR_MAX_BPM && listOfNotNull(minutes.getOrNull(i - 1), minutes.getOrNull(i + 1))
        .any { (u, v) -> abs(u - t) <= 60 && abs(v - bpm) <= ARTEFACT_JUMP_BPM }
}.maxOfOrNull { minutes[it].second }

/**
 * Each day's highest real heart rate, for [observedPeak]: the day's [filteredMax] from [minuteMax], raised by the
 * strap's own daily maximum from [strapMax] only when that is in [HR_MIN_BPM]..[HR_MAX_BPM] and within
 * [ARTEFACT_JUMP_BPM] of the filtered minute maximum. A strap value on a day without one has nothing to
 * corroborate it and is dropped, so the strap's unfiltered figure can't carry a spike in.
 */
fun <K> Recovery.dailyMaxima(minuteMax: Map<K, Int?>, strapMax: List<Pair<K, Int>>): List<Int> =
    dailyMaximaByDay(minuteMax, strapMax).values.toList()

/** [dailyMaxima] by day, for saying when max HR was learned ([observedPeakDay]). */
fun <K> Recovery.dailyMaximaByDay(minuteMax: Map<K, Int?>, strapMax: List<Pair<K, Int>>): Map<K, Int> {
    val strap = strapMax.filter { it.second in HR_MIN_BPM..HR_MAX_BPM }.groupBy({ it.first }, { it.second })
    return minuteMax.mapNotNull { (day, m) ->
        m ?: return@mapNotNull null
        day to maxOf(m, strap[day].orEmpty().filter { abs(it - m) <= ARTEFACT_JUMP_BPM }.maxOrNull() ?: m)
    }.toMap()
}

/** The day [observedPeak] comes from: the latest of the days whose maximum is that peak. Null with no peak. */
fun <K : Comparable<K>> Recovery.observedPeakDay(maxima: Map<K, Int>): K? {
    val peak = observedPeak(maxima.values.toList()) ?: return null
    return maxima.filterValues { it == peak }.keys.maxOrNull()
}

/**
 * Where [maxHeartRate] comes from, for the same inputs: your own value, a peak that raised the estimate, your age,
 * the strap's value, or the 190 used when there's nothing.
 */
fun Recovery.maxHeartRateSource(age: Int?, strapMax: Int? = null, observedPeak: Int? = null, tested: Int? = null): MaxHeartRate.Source =
    when {
        tested != null && tested in TESTED_MAX_RANGE -> MaxHeartRate.Source.Set
        observedPeak != null && observedPeak > maxHeartRate(age, strapMax) -> MaxHeartRate.Source.Learned
        age != null && age in 10..100 -> MaxHeartRate.Source.Age
        strapMax != null && strapMax in 140..225 -> MaxHeartRate.Source.Strap
        else -> MaxHeartRate.Source.Guess
    }
