package org.openstrap.app.data

/** Order statistics the engines share, so a median is one median everywhere. */
object Stats {
    /** The median of [values]: the middle one, or the mean of the two middle ones. Null when empty. */
    fun median(values: List<Float>): Float? =
        if (values.isEmpty()) null else values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2f }

    /** [median] of whole numbers, which may fall on a half. */
    @JvmName("medianOfInts")
    fun median(values: List<Int>): Float? = median(values.map { it.toFloat() })

    /**
     * The upper of the two middle values for an even count, the middle one otherwise. For a reading that must be one
     * that was taken, such as a heart rate at a point in time.
     */
    fun upperMiddle(values: List<Int>): Int? = if (values.isEmpty()) null else values.sorted()[values.size / 2]

    /** The [p] quantile (0 to 1, clamped) of [values] by linear interpolation between neighbours, null when empty. */
    fun quantile(values: List<Float>, p: Float): Float? {
        if (values.isEmpty()) return null
        return quantileOfSorted(values.sorted().map { it.toDouble() }, p.toDouble()).toFloat()
    }

    /** [quantile] of values already sorted ascending, which must not be empty. */
    fun quantileOfSorted(sorted: List<Double>, p: Double): Double {
        require(sorted.isNotEmpty()) { "no values" }
        val pos = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.lastIndex)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
