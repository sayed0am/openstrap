package org.openstrap.app.presentation

/** A point on a time axis: x is epoch seconds, y is the value. */
data class ChartPoint(val x: Long, val y: Float)

/** A day on a weekly trend chart. */
data class TrendPoint(val label: String, val value: Float?)

/** A sleep stage interval for the hypnogram. */
data class StageSpan(val start: Long, val end: Long, val stage: Int)
