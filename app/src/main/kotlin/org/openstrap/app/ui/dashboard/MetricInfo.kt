package org.openstrap.app.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.data.DayStat
import org.openstrap.app.ui.theme.MetricColors
import java.time.LocalDate
import org.openstrap.app.R
import org.openstrap.app.tr

/** How far back a metric detail looks: the selected day, or one value per day (per week, for a year). */
internal enum class Range(val label: String, val days: Int) { Day(tr(R.string.metric_info_day), 1), Week(tr(R.string.metric_info_week), 7), Month(tr(R.string.metric_info_month), 30), Year(tr(R.string.metric_info_year), 365) }

/** Loads one value per day of a metric between two dates; the app root passes the repository's. */
typealias DailyHistory = (DailyMetric, LocalDate, LocalDate) -> Flow<List<DayStat>>

internal fun colorOf(metric: Metric): Color = when (metric) {
    Metric.HeartRate, Metric.RestingHr -> MetricColors.heart
    Metric.Hrv -> MetricColors.hrv
    Metric.Breathing -> MetricColors.sleepLight
    Metric.Spo2 -> MetricColors.spo2
    Metric.Stress -> MetricColors.stress
    Metric.SkinTemperature -> MetricColors.temperature
    Metric.Steps -> MetricColors.steps
}

/**
 * The metrics the Sleep tab's night vitals open scoped to the night, in the list's order. Lowest and average heart
 * rate are both the night's heart rate, so it's here once.
 */
internal val NIGHT_VITALS: List<Metric> = listOf(Metric.HeartRate, Metric.Hrv, Metric.Breathing, Metric.Spo2, Metric.SkinTemperature)

/**
 * The Activity tab's details, in the tab's order: Steps, the Small pair, Load, Heart rate, then the Today list's VO2
 * max, time to recover, energy and distance.
 */
internal val ACTIVITY_GROUP: List<Detail> = listOf(
    Detail.OfMetric(Metric.Steps), Detail.OfScore(Score.ActiveMinutes), Detail.OfScore(Score.HeartPoints), Detail.OfScore(Score.Load),
    Detail.OfMetric(Metric.HeartRate), Detail.OfScore(Score.Vo2Max), Detail.OfScore(Score.Recover), Detail.OfScore(Score.Energy),
    Detail.OfScore(Score.Distance),
)

/**
 * The details a detail's switcher moves between (spec 6.1): its siblings in the same group. The body signals in
 * Recovery's list order, what the day does, the two scores of the Recovery tab, the Sleep tab's details, and the night
 * vitals, which switch only among themselves while scoped to the night. Every detail is in one group but the week story.
 */
internal val DETAIL_GROUPS: List<List<Detail>> = listOf(
    listOf(Metric.RestingHr, Metric.Hrv, Metric.SkinTemperature, Metric.Breathing, Metric.Spo2, Metric.Stress).map { Detail.OfMetric(it) },
    ACTIVITY_GROUP,
    listOf(Score.Recovery, Score.Charge).map(Detail::OfScore),
    listOf(Score.Sleep, Score.Regularity, Score.Naps).map(Detail::OfScore),
    NIGHT_VITALS.map { Detail.OfMetric(it, night = true) },
)

/** [detail]'s group, itself included, in order; alone when it's in none. */
internal fun siblingsOf(detail: Detail): List<Detail> = DETAIL_GROUPS.firstOrNull { detail in it } ?: listOf(detail)

/** Where a metric's number for a day comes from, which decides how its sentence talks about the day. */
internal enum class DaySource {
    /** From the night: breathing rate in sleep, and the strap's resting heart rate from your calmest stretch. */
    Night,

    /** The average of every reading dated that day: HRV, blood oxygen spot readings, skin temperature, stress. */
    Average,

    /** A running total or the latest reading: steps, heart rate. */
    SoFar,
}

internal fun daySource(metric: Metric): DaySource = when (metric) {
    Metric.Breathing, Metric.RestingHr -> DaySource.Night
    Metric.Hrv, Metric.Spo2, Metric.SkinTemperature, Metric.Stress -> DaySource.Average
    Metric.Steps, Metric.HeartRate -> DaySource.SoFar
}

/** Metrics with one value a night, so their words say "nights" and "last night". */
internal val NIGHTLY: Set<Metric> = Metric.entries.filter { daySource(it) == DaySource.Night }.toSet()

internal val RELATED = mapOf(
    Metric.HeartRate to listOf(Metric.RestingHr, Metric.Hrv, Metric.Stress),
    Metric.RestingHr to listOf(Metric.Hrv, Metric.HeartRate, Metric.SkinTemperature),
    Metric.Hrv to listOf(Metric.RestingHr, Metric.Stress, Metric.Breathing),
    Metric.Spo2 to listOf(Metric.Breathing, Metric.RestingHr),
    Metric.Stress to listOf(Metric.Hrv, Metric.HeartRate),
    Metric.SkinTemperature to listOf(Metric.RestingHr, Metric.Breathing),
    Metric.Breathing to listOf(Metric.Spo2, Metric.SkinTemperature, Metric.Hrv),
    Metric.Steps to listOf(Metric.HeartRate),
)
