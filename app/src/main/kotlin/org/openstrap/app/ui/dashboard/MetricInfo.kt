package org.openstrap.app.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.data.DayStat
import org.openstrap.app.ui.theme.MetricColors
import java.time.LocalDate
import org.openstrap.app.presentation.DaySource
import org.openstrap.app.presentation.Metric
import org.openstrap.app.presentation.daySource

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
 * max, time to recover, calories and distance.
 */
internal val ACTIVITY_GROUP: List<Detail> = listOf(
    Detail.OfMetric(Metric.Steps), Detail.OfScore(Score.ActiveMinutes), Detail.OfScore(Score.HeartPoints), Detail.OfScore(Score.Load),
    Detail.OfMetric(Metric.HeartRate), Detail.OfScore(Score.Vo2Max), Detail.OfScore(Score.Recover), Detail.OfScore(Score.Calories),
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
    listOf(Score.Recovery, Score.Energy).map(Detail::OfScore),
    listOf(Score.Sleep, Score.Regularity, Score.Naps).map(Detail::OfScore),
    NIGHT_VITALS.map { Detail.OfMetric(it, night = true) },
)

/** [detail]'s group, itself included, in order; alone when it's in none. */
internal fun siblingsOf(detail: Detail): List<Detail> = DETAIL_GROUPS.firstOrNull { detail in it } ?: listOf(detail)

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
