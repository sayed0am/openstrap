package org.openstrap.app.ui.glow

import org.openstrap.app.R
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.ChartPoint

/*
 * Words and small helpers the glow screens share, each written once. Date labels and numbers are in presentation's
 * DayWords and Units. The words are in res/values/strings_glow_common.xml, read through getters named as the constants
 * they were, so the tests that read them stay the same.
 */

/** Shown under a row's name when the day has no value for it. */
internal val NO_VALUE: String get() = Strings.app.get(R.string.glow_no_value)

/** [NO_VALUE] for a night's row. */
internal val NO_NIGHT_VALUE: String get() = Strings.app.get(R.string.glow_no_night_value)

/** A card or row whose number hasn't come in yet today. */
internal val NO_READING: String get() = Strings.app.get(R.string.glow_no_reading_yet)

/** Heart points and the like, which come with the strap's daily records. */
internal val AFTER_NEXT_SYNC: String get() = Strings.app.get(R.string.glow_after_next_sync)

/** What Calories needs from the profile before it can be worked out. */
internal val ADD_PROFILE: String get() = Strings.app.get(R.string.glow_add_profile)

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


