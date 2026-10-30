package org.openstrap.app.ui.glow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.data.History
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.ui.components.detailNumber
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.DetailCopy
import org.openstrap.app.ui.dashboard.InfoCopy
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.dashboard.Narrative
import org.openstrap.app.ui.dashboard.Range
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.colorOf
import org.openstrap.app.ui.theme.MetricColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * Recovery's and Charge's details (spec 6.1) in the same template as a metric's, and the way to the Sleep tab's
 * ([SleepScoreDetail] and its siblings). Neither has a per-day series like a
 * metric: Recovery keeps its last 7 days, Charge only the day shown, so their ranges stop where their history does.
 */

/**
 * The detail of [score] for the day shown. [recovery] is the day's result (another day's is ignored), [strapScore] the
 * strap's own Recovery for the day, for the info sheet. The rest as [GlowMetricDetailScreen].
 */
@Composable
fun GlowScoreDetailScreen(
    score: Score,
    state: DayUiState,
    recovery: RecoveryResult?,
    strapScore: Int?,
    history: DailyHistory,
    onOpen: (Detail) -> Unit,
    onSwitch: (Detail) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /** For the Activity details: the workouts, each day's Heart points, and the way to the profile. */
    workouts: List<org.openstrap.app.data.WorkoutAnalysis> = emptyList(),
    paiHistory: PaiHistory? = null,
    onEditProfile: () -> Unit = {},
    energyHistory: EnergyHistory? = null,
    /** For the Naps detail: keeps a nap's own times, the strap's nap first. */
    onSetNapTimes: (org.openstrap.app.data.db.SleepSession, Long, Long) -> Unit = { _, _, _ -> },
) {
    val r = recovery?.takeIf { it.date == state.date }
    when (score) {
        Score.Recovery -> RecoveryDetail(state, r, strapScore, history, onOpen, onSwitch, onSelectDay, contentPadding, onBack)
        Score.Charge -> ChargeDetail(state, r, history, onOpen, onSwitch, onSelectDay, contentPadding, onBack)
        Score.Sleep -> SleepScoreDetail(state, r, history, onOpen, onSwitch, onSelectDay, contentPadding, onBack)
        Score.Regularity -> RegularityDetail(state, r, history, onOpen, onSwitch, onSelectDay, contentPadding, onBack)
        Score.Naps -> NapsDetail(state, r, onOpen, onSwitch, onSelectDay, contentPadding, onBack, onSetNapTimes)
        Score.HeartPoints, Score.ActiveMinutes, Score.Load, Score.Vo2Max, Score.Recover, Score.Energy, Score.Distance -> ActivityScoreDetail(
            score, ActivityDetailContext(state, r, workouts, paiHistory, history, onOpen, onSwitch, onSelectDay, onEditProfile, contentPadding, onBack, energyHistory),
        )
    }
}

/** The days a metric has data for, from the day after [from] to [to]; none when [from] isn't before [to]. */
@Composable
internal fun daysWithData(metric: DailyMetric, from: LocalDate, to: LocalDate, history: DailyHistory): Set<LocalDate> {
    val stats by remember(metric, from, to) { if (from < to) history(metric, from.plusDays(1), to) else flowOf(emptyList()) }.collectAsState(emptyList())
    return stats.map { it.date }.toSet()
}

/* ---------------------------------------------- Recovery -------------------------------------------- */

/**
 * The Recovery detail: the score in its arc, every contributor with the reading behind it (the tab shows the top
 * three), and the last 7 days. The method and the strap's own score are in its info sheet.
 */
@Composable
private fun RecoveryDetail(
    state: DayUiState, r: RecoveryResult?, strapScore: Int?, history: DailyHistory,
    onOpen: (Detail) -> Unit, onSwitch: (Detail) -> Unit, onSelectDay: (LocalDate) -> Unit, contentPadding: PaddingValues, onBack: () -> Unit,
) {
    val ready = r?.readiness
    val score = debugRecoveryScore.value ?: ready?.score
    // While OpenStrap's own score is withheld, the strap's stands in, as on Today and the Recovery tab.
    val estimate = if (score == null) TodayCopy.recoveryShown(ready, strapScore, "").takeIf { it.estimate }?.value else null
    val alert = ready?.strainAlert == true
    val color = MetricColors.readiness
    // TODO(step 6): Month and Year wait for a stored Recovery history; the app keeps only the last 7 days of it.
    val ranges = listOf(Range.Day, Range.Week)
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val range = ranges[rangeIndex.coerceIn(ranges.indices)]
    var scrub by remember(range, state.date) { mutableStateOf<DetailHeadline?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    val isToday = state.date == today
    val week = r?.readinessTrend.orEmpty()
    // The trend covers the week up to the day shown; for the days after it, a night's resting heart rate stands in.
    val later = daysWithData(DailyMetric.RestingHr, state.date, today, history)
    val withData = week.filter { it.second != null }.map { it.first }.toSet() + later

    val known = week.mapNotNull { it.second }
    val headline = scrub ?: when {
        range == Range.Week && known.isNotEmpty() -> DetailHeadline(Math.round(known.average()).toString(), tr(R.string.score_detail_average_of_the_last), null)
        range == Range.Week -> DetailHeadline.none(tr(R.string.common_no_scores), tr(R.string.common_in_the_last_days))
        score != null -> DetailHeadline(score.toString(), if (isToday) tr(R.string.score_detail_this_morning) else tr(R.string.score_detail_that_morning), TodayCopy.recoveryWord(score, alert))
        estimate != null -> DetailHeadline(estimate.toString(), tr(R.string.common_strap_s_estimate), null)
        else -> DetailCopy.recoveryState(ready, isToday).let { (word, why) -> DetailHeadline.none(word, why) }
    }

    DetailTemplate(
        Score.Recovery.title, color, siblings(Detail.OfScore(Score.Recovery), onSwitch), onInfo = { info = true }, onBack = onBack,
        contentPadding = contentPadding, headline = headline,
        strip = DetailCopy.strip(state.date, today, withData), onDay = onSelectDay,
        ranges = ranges, range = range, onRange = { rangeIndex = ranges.indexOf(it) },
        // The arc gauge (chart kit, spec 2.7) holds the day's score, centred, the number inside the half circle.
        heroValue = (score ?: estimate)?.takeIf { range == Range.Day && scrub == null }?.let { shown ->
            { ArcGauge(shown / 100f, color, key = state.date) { DetailScoreNumber(shown) } }
        },
    ) {
        if (range == Range.Day) {
            item {
                Column {
                    DetailSentence(
                        when {
                            ready == null -> TabCopy.RECOVERY_WAITING
                            score != null -> TabCopy.recovery(score, alert, ready.contributors)
                            ready.phase == Recovery.Phase.Withheld -> TodayCopy.noScoreLine(TodayCopy.NoScore.Learning(ready.baselineNights, Recovery.SCORED_NIGHTS))
                            ready.withheld -> TodayCopy.noScoreLine(TodayCopy.NoScore.NoHeartData)
                            else -> tr(R.string.common_recovery_is_scored_each)
                        },
                    )
                    if (score != null && ready != null) TabCopy.learning(ready.phase, ready.baselineNights)?.let { CaveatLine(it, color) }
                }
            }
            val all = ready?.contributors.orEmpty().sortedByDescending { it.factor.weight }
            if (all.isNotEmpty()) item {
                ListPanel(Modifier.padding(top = 8.dp), title = tr(R.string.common_what_drove_it)) {
                    all.forEachIndexed { i, c ->
                        ContributorRow(
                            TabCopy.factorName(c.factor), MetricColors.readiness, TabCopy.contributorWord(c.score), c.score / 100f,
                            offNormal = TabCopy.contributorOff(c.score), divider = i > 0, detail = readable(c.detail),
                        )
                    }
                }
            }
        } else {
            item {
                if (known.isEmpty()) DetailEmpty(tr(R.string.score_detail_no_recovery_in_the))
                else {
                    val values = week.map { it.second?.toFloat() }
                    // TODO(step 6): your normal as a band, once the app keeps a normal of the score; every day stays dim until then.
                    NormalTrend(
                        values, DetailCopy.levels(values, null), color, labels = week.map { shortDay(it.first) },
                        onScrub = { i ->
                            scrub = i?.let { week[it] }?.let { (d, v) -> v?.let { DetailHeadline(it.toString(), d.format(DateTimeFormatter.ofPattern("EEE d MMM")), TodayCopy.recoveryWord(it, false)) } }
                        },
                    )
                }
            }
            DetailCopy.scoreWeek(week.map { it.second })?.let { item { DetailSentence(it) } }
        }
        item { ScoreRelated(Score.Recovery, state, r, history, onOpen) }
    }

    if (info) InfoSheet(
        InfoCopy.of(Score.Recovery), color, score?.let(InfoCopy::recoveryBar), score?.let { InfoCopy.HERE },
        onDismiss = { info = false }, notes = InfoCopy.recoveryNotes(ready, strapScore),
    )
}

/** A contributor's reading as the engine words it ("52 ms, -8% vs usual"). */
private fun readable(detail: String): String? = detail.takeIf { it.isNotBlank() }

private fun shortDay(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

/* ----------------------------------------------- Charge --------------------------------------------- */

private val HOURS = listOf("00", "06", "12", "18", "24")

/** A Charge curve over fewer hours than this gets the short chart. */
private const val SHORT_CURVE_HOURS = 3f

/**
 * The Charge detail: the day's curve to scrub, where it started and how high and low it went, what filled and drained
 * it, and the sentence for the time of day.
 */
@Composable
private fun ChargeDetail(
    state: DayUiState, r: RecoveryResult?, history: DailyHistory,
    onOpen: (Detail) -> Unit, onSwitch: (Detail) -> Unit, onSelectDay: (LocalDate) -> Unit, contentPadding: PaddingValues, onBack: () -> Unit,
) {
    val c = r?.charge
    val now = c?.latest?.let(::chargeShown)
    val atWake = c?.atWake?.toInt()
    val color = MetricColors.charge
    val today = LocalDate.now()
    val isToday = state.date == today
    var scrub by remember(state.date) { mutableStateOf<DetailHeadline?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    // Charge runs on heart rate, so the days with heart rate are the days with a Charge.
    val strip = DetailCopy.strip(state.date, today, emptySet())
    val withData = daysWithData(DailyMetric.HeartRate, strip.first().date.minusDays(1), strip.last().date, history)
    // The latest Charge is where the curve ends, which can be well before now while the strap catches up.
    val lastAt = c?.points?.lastOrNull { it.state != Recovery.State.NoData }?.t
    val stale = isToday && lastAt != null && System.currentTimeMillis() / 1000 - lastAt > 30 * 60
    val headline = scrub ?: when {
        c == null || !c.anchored -> DetailHeadline.none(tr(R.string.common_waiting), tr(R.string.common_after_your_first_night))
        now == null -> DetailHeadline.none(tr(R.string.common_no_reading), tr(R.string.score_detail_needs_a_few_hours))
        else -> DetailHeadline(
            now.toString(),
            when {
                stale -> tr(R.string.missed_at_time, clockAt(lastAt!!))
                isToday -> tr(R.string.missed_now)
                else -> tr(R.string.score_detail_at_the_end_of)
            },
            Narrative.chargeHeadline(now),
        )
    }
    val day = state.dayStart..state.dayEnd
    val step = 15 * 60L
    val values = remember(c?.points, day) { c?.points?.let { bucket(it, day, step) }.orEmpty() }

    // TODO(step 6): Week and longer wait for a stored Charge history; the app works out only the day shown, so there are no pills.
    DetailTemplate(
        Score.Charge.title, color, siblings(Detail.OfScore(Score.Charge), onSwitch), onInfo = { info = true }, onBack = onBack,
        contentPadding = contentPadding, headline = headline,
        strip = DetailCopy.strip(state.date, today, withData), onDay = onSelectDay,
        ranges = listOf(Range.Day), range = Range.Day, onRange = {},
    ) {
        item {
            if (c == null || now == null || c.points.size < 2) DetailEmpty(TabCopy.chargeLine(now, atWake, c?.anchored == true))
            else Column(Modifier.padding(top = 8.dp)) {
                // The Charge curve (chart kit): sleep fills it, the day drains it. Scrubbing moves the number.
                // A curve covering only a few hours gets a short chart, so it doesn't stand tall and empty; the axis stays
                // the whole day, so the gap reads as time without a Charge.
                val known = values.indices.filter { values[it] != null }
                val hours = if (known.isEmpty()) 0f else (known.last() - known.first()) * step / 3600f
                GlowLineChart(
                    values, height = if (hours < SHORT_CURVE_HOURS) 110.dp else 200.dp, labels = HOURS, color = color, showAverage = false, yRange = 0f..100f,
                    onScrub = { i ->
                        scrub = i?.let { values[it] }?.let { v -> DetailHeadline(chargeShown(v).toString(), tr(R.string.missed_at_time, clockAt(day.first + i * step)), Narrative.chargeHeadline(chargeShown(v))) }
                    },
                )
                // A figure the day doesn't have (no waking without a night's sleep) is left out, the others share the row.
                val figures = listOf(
                    atWake?.toString() to if (c.strapOffOvernight) tr(R.string.score_detail_at_start) else tr(R.string.score_detail_at_waking),
                    c.highest?.let(::chargeShown)?.toString() to tr(R.string.score_detail_highest),
                    c.lowest?.let(::chargeShown)?.toString() to tr(R.string.score_detail_lowest),
                ).filter { it.first != null }
                if (figures.isNotEmpty()) DetailFigures(figures, Modifier.padding(top = 18.dp))
            }
        }
        if (now != null) item {
            Column {
                DetailSentence(DetailCopy.charge(now, atWake, TabCopy.drainedAmount(c.drainedExercise + c.drainedStress + c.drainedLiving).toInt(), c.anchored, isToday, LocalTime.now().hour))
                InfoCopy.chargeNotes(c).firstOrNull()?.let { note ->
                    Box(Modifier.clickable { info = true }) { CaveatLine(note, color) }
                }
            }
        }
        if (c != null && c.anchored && now != null) item {
            ListPanel(Modifier.padding(top = 8.dp), title = tr(R.string.score_detail_where_it_went)) {
                TabCopy.chargeRows(c.recharged, c.drainedExercise, c.drainedStress, c.drainedLiving).forEachIndexed { i, row ->
                    ListRow(row.name, color, row.value, row.status, divider = i > 0)
                }
            }
        }
        item { ScoreRelated(Score.Charge, state, r, history, onOpen) }
    }

    if (info) InfoSheet(
        InfoCopy.of(Score.Charge), color, now?.let(InfoCopy::chargeBar), now?.let { InfoCopy.HERE },
        onDismiss = { info = false }, notes = InfoCopy.chargeNotes(c),
    )
}

/** Averages charge samples into evenly spaced buckets across [range]; empty buckets are gaps. */
private fun bucket(points: List<Recovery.ChargePoint>, range: LongRange, seconds: Long): List<Float?> {
    val n = ((range.last - range.first + 1) / seconds).toInt().coerceAtLeast(2)
    val sums = FloatArray(n)
    val counts = IntArray(n)
    points.forEach {
        val i = ((it.t - range.first) / seconds).toInt()
        if (i in 0 until n) { sums[i] += it.value; counts[i]++ }
    }
    return List(n) { if (counts[it] == 0) null else sums[it] / counts[it] }
}

/* ---------------------------------------------- Related --------------------------------------------- */

/**
 * The rows under a score: the other score, last night, and the body signals that move it most, each with the day's
 * value, opening its detail.
 */
@Composable
private fun ScoreRelated(score: Score, state: DayUiState, r: RecoveryResult?, history: DailyHistory, onOpen: (Detail) -> Unit) {
    val ready = r?.readiness
    // Before the first night Charge is only the simulation's starting guess: a dash, as on the Recovery tab's battery.
    val charge = r?.charge?.takeIf { it.anchored }?.latest?.let(::chargeShown)
    val chargeWaiting = if (r?.charge?.anchored == false) org.openstrap.app.ui.dashboard.AFTER_FIRST_NIGHT else tr(R.string.common_nothing_for_this_day)
    val asleep = state.asleepSeconds
    val metrics = if (score == Score.Recovery) listOf(Metric.Hrv, Metric.RestingHr) else listOf(Metric.Stress)
    ListPanel(Modifier.padding(top = 8.dp), title = tr(R.string.common_related)) {
        if (score == Score.Recovery) ListRow(
            tr(R.string.common_energy), MetricColors.charge, charge?.toString(), charge?.let(Narrative::chargeHeadline) ?: chargeWaiting,
            onClick = { onOpen(Detail.OfScore(Score.Charge)) }, divider = false, icon = Score.Charge.rowIcon(),
        ) else ListRow(
            tr(R.string.common_recovery), MetricColors.readiness, ready?.score?.toString(),
            ready?.score?.let { TodayCopy.recoveryWord(it, ready.strainAlert) } ?: tr(R.string.common_nothing_for_this_day),
            onClick = { onOpen(Detail.OfScore(Score.Recovery)) }, divider = false, icon = Score.Recovery.rowIcon(),
        )
        ListRow(
            tr(R.string.common_last_night_2), MetricColors.sleepDeep, asleep?.let(::clockDuration), if (asleep == null) tr(R.string.common_no_sleep_recorded) else tr(R.string.common_asleep),
            onClick = { onOpen(Detail.OfScore(Score.Sleep)) }, icon = Score.Sleep.rowIcon(),
        )
        metrics.forEach { m ->
            val normal = rememberNormal(m, state.date, history)
            val v = dayValue(m, state)
            val status = History.status(v, normal)
            ListRow(
                m.title, colorOf(m), v?.let(m::format), if (v == null) tr(R.string.common_nothing_for_this_day) else DetailCopy.statusWord(status) ?: m.unit.ifEmpty { null },
                offNormal = status != null && status != 0, onClick = { onOpen(Detail.OfMetric(m)) }, icon = m.rowIcon(),
            )
        }
    }
}

/**
 * Debug only: a Recovery score the detail shows in place of the real one, so the arc gauge can be checked on a phone
 * whose Recovery is still learning. Set from MainActivity's `recovery_score` extra; null is the real score.
 */
internal val debugRecoveryScore = androidx.compose.runtime.mutableStateOf<Int?>(null)

/** The score in a score gauge on a detail header ([ArcGauge], [HorizonArc]): the header's one dotted number. */
@Composable
internal fun DetailScoreNumber(score: Int) =
    DotMatrixText(score.toString(), Modifier.detailNumber(score.toString()), dot = DotSize.Detail)
