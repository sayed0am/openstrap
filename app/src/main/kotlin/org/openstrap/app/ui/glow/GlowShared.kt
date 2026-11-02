package org.openstrap.app.ui.glow

import org.openstrap.app.data.Recovery
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.presentation.Formatters
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.dotFormat
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.components.ChartPoint
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/*
 * Words, date labels and small helpers the glow screens share, each written once. The date labels here are the ones
 * DayWords has no form for yet (a weekday alone, a day and month), kept beside the screens until presentation/ takes
 * them.
 */

/** Shown under a row's name when the day has no value for it. */
internal const val NO_VALUE = "Nothing for this day"

/** [NO_VALUE] for a night's row. */
internal const val NO_NIGHT_VALUE = "Nothing for this night"

/** A card or row whose number hasn't come in yet today. */
internal const val NO_READING = "No reading yet"

/** Heart points and the like, which come with the strap's daily records. */
internal const val AFTER_NEXT_SYNC = "After the strap's next sync"

/** What Calories needs from the profile before it can be worked out. */
internal const val ADD_PROFILE = "Add age, weight and height"

/** A weekday's short name, for a week's strip of bars: "Tue". */
internal fun weekdayShort(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

/** A day and month with no weekday, for chart axes and ranges: "21 Oct". */
internal fun dayMonth(date: LocalDate, locale: Locale = Locale.getDefault()): String = date.format(Formatters.of("d MMM", locale))

/** A month's short name, for a year's chart axis: "Oct". */
internal fun monthShort(date: LocalDate, locale: Locale = Locale.getDefault()): String = date.format(Formatters.of("MMM", locale))

/**
 * The latest workout the strap gave a time to recover, the one Time to recover counts down from on Today, Activity and
 * its detail. Null when none has one.
 */
internal fun latestRecoveryEstimate(workouts: List<WorkoutAnalysis>): WorkoutAnalysis? =
    workouts.filter { it.recoveryMinutes != null }.maxByOrNull { it.end }

/** Averages timed points into evenly spaced buckets across [range]; empty buckets are null (gaps). */
internal fun bucket(points: List<ChartPoint>, range: LongRange, seconds: Long): List<Float?> =
    Summaries.evenly(points, range, seconds, { it.x }, { it.y })

/** [bucket] for Charge's samples. */
@JvmName("bucketCharge")
internal fun bucket(points: List<Recovery.ChargePoint>, range: LongRange, seconds: Long): List<Float?> =
    Summaries.evenly(points, range, seconds, { it.t }, { it.value })

/** [Units.count] for an Int. */
internal fun Units.count(n: Int): String = count(n.toLong())

/**
 * [Metric.format] for a headline drawn in the dot font: ASCII digits and a dot decimal mark whatever the phone's
 * language, since the font has neither another script's digits nor a comma that reads as a decimal.
 */
internal fun Metric.dotValue(v: Float): String =
    if (this == Metric.Steps) dotFormat("%,d", Math.round(v)) else dotFormat("%.${decimals}f", v)
