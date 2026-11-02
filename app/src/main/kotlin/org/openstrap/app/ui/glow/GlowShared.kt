package org.openstrap.app.ui.glow

import org.openstrap.app.data.Recovery
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.ui.components.ChartPoint

/*
 * Words and small helpers the glow screens share, each written once. Date labels and numbers are in presentation's
 * DayWords and Units.
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


