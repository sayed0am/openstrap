package org.openstrap.app.ui.glow

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlin.math.roundToInt
import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.chargeShown
import org.openstrap.app.data.Healthspan
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.presentation.Metric
import org.openstrap.app.ui.dashboard.avg
import org.openstrap.app.ui.theme.MetricColors

/*
 * All metrics (decision 3): a map of every number and screen the app already has, grouped by what it's about, so
 * nothing is only reachable from one card. Not a new feature: each row opens a screen that already exists.
 */

/**
 * Where an index row goes. All metrics is a map, not a card: a row whose home is a tab goes to that tab ([Home]),
 * one with a detail of its own opens it on the current tab's stack ([Open]).
 */
sealed interface IndexTarget {
    data class Open(val detail: Detail) : IndexTarget

    /** The tab, at its root and top; [charge] scrolls the Recovery tab to its Charge card. */
    data class Home(val tab: HomeTab, val charge: Boolean = false) : IndexTarget
}

/** The tabs an index row can go to. */
enum class HomeTab { Recovery, Sleep }

/** The row icon for where an index row goes (spec 2.5): its metric's, or the tab's own for a tab. */
internal fun IndexTarget.rowIcon(): androidx.compose.ui.graphics.vector.ImageVector? = when (this) {
    is IndexTarget.Open -> detail.rowIcon()
    is IndexTarget.Home -> when {
        charge -> org.openstrap.app.ui.dashboard.Score.Energy.rowIcon()
        tab == HomeTab.Sleep -> org.openstrap.app.ui.dashboard.Score.Sleep.rowIcon()
        else -> org.openstrap.app.ui.dashboard.Score.Recovery.rowIcon()
    }
}

/** One row of the index: the metric's [name] and [color], the day's [value] (null when there's none) and where it goes. */
data class IndexRow(val name: String, val color: Color, val value: String?, val status: String, val target: IndexTarget)

data class IndexGroup(val title: String, val rows: List<IndexRow>)

/**
 * What the Recovery row says under its name: the learning state while the baselines fill, as on the Recovery card
 * ("Learning, 5 of 7 nights"); otherwise that it's the day's score, or null when there's nothing to say.
 */
internal fun recoveryStatus(r: org.openstrap.app.data.Recovery.Readiness?, strings: Strings = Strings.app): String? = when {
    r == null -> null
    r.score != null && r.phase == org.openstrap.app.data.Recovery.Phase.Learning ->
        strings.plural(
            R.plurals.glow_metrics_learning_nights, org.openstrap.app.data.Recovery.SETTLED_NIGHTS,
            r.baselineNights, org.openstrap.app.data.Recovery.SETTLED_NIGHTS,
        )
    r.score != null -> strings.get(R.string.glow_metrics_score_for_day)
    r.withheld && r.phase == org.openstrap.app.data.Recovery.Phase.Withheld ->
        strings.plural(
            R.plurals.glow_metrics_learning_nights, org.openstrap.app.data.Recovery.SCORED_NIGHTS,
            r.baselineNights, org.openstrap.app.data.Recovery.SCORED_NIGHTS,
        )
    else -> null
}

/**
 * The Habit Age row's value and status, the same as its page shows: the estimate once there is one, your age as
 * the starting point while it learns, otherwise no value and why.
 */
internal fun habitAgeRow(outcome: Healthspan.Outcome?, strings: Strings = Strings.app): Pair<String?, String> {
    val r = outcome?.result
    return when {
        r != null -> "${r.estimate.roundToInt()}" to strings.get(R.string.glow_metrics_updated_weekly)
        outcome?.withheld?.learning == true && outcome.age != null -> "${outcome.age}" to strings.get(R.string.glow_metrics_starting_point)
        outcome?.withheld?.profile == true -> null to strings.get(R.string.glow_metrics_needs_profile)
        else -> null to strings.get(R.string.glow_metrics_not_worked_out)
    }
}

/** The index for the day in [state]: every [Metric] once, plus Recovery, Charge, Habit Age, Sleep and the workouts. */
fun metricIndex(
    state: DayUiState,
    recovery: RecoveryResult?,
    healthspan: Healthspan.Outcome?,
    workouts: List<WorkoutAnalysis>,
    today: LocalDate = LocalDate.now(),
    /** The strap's own Recovery for the day, shown as its estimate while OpenStrap's score is withheld, as on Today. */
    strapScore: Int? = null,
    strings: Strings = Strings.app,
): List<IndexGroup> {
    val rec = recovery?.takeIf { it.date == state.date }
    val (habitValue, habitStatus) = habitAgeRow(healthspan, strings)
    val weekFrom = state.date.minusDays(6)
    // The 7 days up to the day shown, not Your week's Monday to Sunday, so the row says so. The strap's own
    // "Activity" records aren't workouts, as on Today and Your week.
    val week = workouts.count { a ->
        a.workout.sportType != org.openstrap.app.data.Workouts.GENERIC_ACTIVITY &&
            java.time.Instant.ofEpochSecond(a.workout.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate() in weekFrom..state.date
    }
    return listOf(
        IndexGroup(
            strings.get(R.string.glow_metrics_recovery),
            listOf(
                recoveryStatus(rec?.readiness, strings).let { status ->
                    val shown = org.openstrap.app.presentation.TodayCopy.recoveryShown(rec?.readiness, strapScore, NO_VALUE)
                    row(
                        strings.get(R.string.glow_metrics_recovery), MetricColors.readiness, shown.value?.toString(),
                        shown.word.takeIf { shown.estimate } ?: status ?: strings.get(R.string.glow_metrics_score_for_day),
                        IndexTarget.Home(HomeTab.Recovery), waiting = status,
                    )
                },
                // Before the first night Charge is only the simulation's starting guess: a dash, as on the Recovery tab's battery.
                row(
                    strings.get(R.string.glow_metrics_energy), MetricColors.charge,
                    rec?.charge?.takeIf { it.anchored }?.latest?.let(::chargeShown)?.toString(),
                    strings.get(if (state.date == today) R.string.glow_metrics_latest else R.string.glow_metrics_end_of_day),
                    IndexTarget.Home(HomeTab.Recovery, charge = true),
                    waiting = org.openstrap.app.presentation.AFTER_FIRST_NIGHT.takeIf { rec?.charge?.anchored == false },
                ),
                row(
                    strings.get(R.string.glow_metrics_habit_age), MetricColors.habitAge, habitValue, habitStatus,
                    IndexTarget.Open(Detail.Healthspan), waiting = habitStatus,
                ),
            ),
        ),
        IndexGroup(
            strings.get(R.string.glow_metrics_sleep),
            listOf(
                row(
                    strings.get(R.string.glow_metrics_sleep), MetricColors.sleepDeep, state.sleep?.score?.toString(),
                    strings.get(R.string.glow_metrics_score_for_night), IndexTarget.Home(HomeTab.Sleep),
                ),
            ),
        ),
    ) + signalGroups(state, strings) + listOf(
        IndexGroup(
            strings.get(R.string.glow_metrics_group_activity),
            listOf(
                metric(
                    strings, Metric.Steps, R.string.glow_metrics_steps, MetricColors.steps, state.steps.toFloat(),
                    R.string.glow_metrics_for_the_day,
                ),
                row(
                    strings.get(R.string.glow_metrics_workouts), MetricColors.heart, week.toString(),
                    strings.get(R.string.glow_metrics_last_7_days), IndexTarget.Open(Detail.AllWorkouts),
                ),
            ),
        ),
    )
}

// A row without a value says why when it knows ([waiting]), otherwise that the day has none.
private fun row(name: String, color: Color, value: String?, status: String, target: IndexTarget, waiting: String? = null) =
    IndexRow(name, color, value, if (value == null) waiting ?: NO_VALUE else status, target)

/** A metric's row by the ids of its name and status words. */
private fun metric(
    strings: Strings,
    m: Metric,
    @StringRes name: Int,
    color: Color,
    value: Float?,
    @StringRes status: Int = R.string.glow_metrics_day_average,
    suffix: String = "",
) = row(strings.get(name), color, value?.let { m.format(it) + suffix }, strings.get(status), IndexTarget.Open(Detail.OfMetric(m)))

/** The index's Heart and Body signals groups, a row for each of their metrics. */
private fun signalGroups(state: DayUiState, strings: Strings): List<IndexGroup> = listOf(
    IndexGroup(
        strings.get(R.string.glow_metrics_group_heart),
        listOf(
            metric(
                strings, Metric.HeartRate, R.string.glow_metrics_heart_rate, MetricColors.heart, state.latestHeartRate?.toFloat(),
                R.string.glow_metrics_latest_reading,
            ),
            metric(
                strings, Metric.RestingHr, R.string.glow_metrics_resting_heart_rate, MetricColors.heart,
                state.restingHeartRate?.toFloat(), R.string.glow_metrics_for_the_day,
            ),
            metric(strings, Metric.Hrv, R.string.glow_metrics_hrv, MetricColors.hrv, state.hrv.avg()?.toFloat()),
        ),
    ),
    IndexGroup(
        strings.get(R.string.glow_metrics_group_body),
        listOf(
            metric(strings, Metric.Spo2, R.string.glow_metrics_blood_oxygen, MetricColors.spo2, state.spo2.avg()?.toFloat(), suffix = "%"),
            metric(
                strings, Metric.SkinTemperature, R.string.glow_metrics_skin_temperature, MetricColors.temperature,
                state.skinTemperature.takeIf { it.isNotEmpty() }?.map { it.y }?.average()?.toFloat(),
            ),
            metric(
                strings, Metric.Breathing, R.string.glow_metrics_breathing_rate, MetricColors.sleepLight, state.breathRate.avg()?.toFloat(),
            ),
            metric(strings, Metric.Stress, R.string.glow_metrics_stress, MetricColors.stress, state.stress.avg()?.toFloat()),
        ),
    ),
)

/**
 * The index as a sheet over the tab (spec 3.2), from the grid button in the header: one grouped list per section.
 * [onOpen] gets the tapped row's target; the caller closes the sheet and goes there.
 */
@Composable
fun AllMetricsSheet(
    groups: List<IndexGroup>,
    date: LocalDate,
    onDismiss: () -> Unit,
    onOpen: (IndexTarget) -> Unit,
) {
    PageSheet(onDismiss, stringResource(R.string.glow_metrics_all_metrics), dayName(date), side = 16.dp) {
        groups.forEach { group ->
            ListPanel(title = group.title) {
                group.rows.forEachIndexed { i, r ->
                    ListRow(r.name, r.color, r.value, r.status, onClick = { onOpen(r.target) }, divider = i > 0, icon = r.target.rowIcon())
                }
            }
        }
    }
}
