package org.openstrap.app.ui.glow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import org.openstrap.app.data.Activity
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.ui.components.rememberScrubHaptics
import org.openstrap.app.ui.components.scrub
import org.openstrap.app.ui.dashboard.ActivityCopy
import org.openstrap.app.ui.dashboard.CaloriesCopy
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.DetailCopy
import org.openstrap.app.ui.dashboard.InfoCopy
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.dashboard.Range
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.WeekStory
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * The Activity tab's details (spec 5.3 and 6.1) in the metric template: Heart points, active minutes, Load, VO2 max,
 * time to recover, Calories and distance, and the week story opened from Your week on Today. None has a per-day series
 * like a metric's, so each range stops where the app's history of it does: Heart points as far as the strap's daily
 * records go, active minutes the 14 days the day works out, Load the 30 days before it, the rest only the day shown.
 * The method behind every number is in its info sheet ([InfoCopy]).
 */

/** What every Activity detail is handed: the day, its result, the workouts and the ways out. */
internal class ActivityDetailContext(
    val state: DayUiState,
    val r: RecoveryResult?,
    val workouts: List<WorkoutAnalysis>,
    val paiHistory: PaiHistory?,
    val history: DailyHistory,
    val onOpen: (Detail) -> Unit,
    val onSwitch: (Detail) -> Unit,
    val onSelectDay: (LocalDate) -> Unit,
    val onEditProfile: () -> Unit,
    val contentPadding: PaddingValues,
    val onBack: () -> Unit,
    /** Each day's stored energy, for Calories' week, month and year. */
    val energyHistory: EnergyHistory? = null,
) {
    val today: LocalDate get() = LocalDate.now()
    val isToday: Boolean get() = state.date == today
}

/** The detail of an Activity [score]: Heart points, active minutes, Load, VO2 max, time to recover, energy or distance. */
@Composable
internal fun ActivityScoreDetail(score: Score, ctx: ActivityDetailContext) {
    when (score) {
        Score.HeartPoints -> HeartPointsDetail(ctx)
        Score.ActiveMinutes -> ActiveMinutesDetail(ctx)
        Score.Load -> LoadDetail(ctx)
        Score.Vo2Max -> Vo2Detail(ctx)
        Score.Recover -> RecoverDetail(ctx)
        Score.Energy -> CaloriesDetail(ctx)
        Score.Distance -> DistanceDetail(ctx)
        else -> Unit
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

private fun shortDay(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** The seven days ending on [end], oldest first. */
private fun weekOf(end: LocalDate): List<LocalDate> = (6 downTo 0).map { end.minusDays(it.toLong()) }

/** The strip under the value, with a dot under days that have steps. */
@Composable
private fun stepsStrip(ctx: ActivityDetailContext, metric: DailyMetric = DailyMetric.Steps): List<DetailCopy.StripDay> {
    val strip = DetailCopy.strip(ctx.state.date, ctx.today, emptySet())
    val withData = daysWithData(metric, strip.first().date.minusDays(1), strip.last().date, ctx.history)
    return DetailCopy.strip(ctx.state.date, ctx.today, withData)
}

/* -------------------------------------------- Heart points ------------------------------------------ */

/**
 * Heart points: the last 7 days as stacked day bars (chart kit), each day's points on top of the days before, so the
 * last bar is the total and the oldest day, the one that drops off tomorrow, sits at the foot. A month of the 7-day
 * total as dots. Tap or hold a day for its points.
 */
@Composable
private fun HeartPointsDetail(ctx: ActivityDetailContext) {
    val date = ctx.state.date
    val color = MetricColors.heart
    val ranges = listOf(Range.Week, Range.Month)
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val range = ranges[rangeIndex.coerceIn(ranges.indices)]
    var scrub by remember(range, date) { mutableStateOf<DetailHeadline?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    val stored by remember(date, ctx.paiHistory) {
        ctx.paiHistory?.invoke(date.minusDays(29), maxOf(date, ctx.today)) ?: flowOf(emptyMap())
    }.collectAsState(emptyMap())
    val week = weekOf(date)
    val daily = week.map { stored[it]?.daily }
    val total = (ctx.state.pai?.total ?: stored[date]?.total)?.let(Math::round)
    val month = (29 downTo 0).map { date.minusDays(it.toLong()) }
    val totals = month.map { stored[it]?.total?.let(Math::round) }

    val headline = scrub ?: when {
        range == Range.Month && totals.any { it != null } ->
            DetailHeadline(Math.round(totals.filterNotNull().average()).toString(), tr(R.string.activity_detail_points_day_total_on), null)
        range == Range.Month -> DetailHeadline.none(tr(R.string.activity_detail_no_points), tr(R.string.common_in_the_last_days_2))
        total != null -> DetailHeadline(total.toString(), tr(R.string.activity_detail_points_last_days), ActivityCopy.heartPointsWord(total))
        else -> DetailHeadline.none(tr(R.string.activity_detail_no_points_yet), tr(R.string.common_after_the_strap_s))
    }
    val strip = DetailCopy.strip(date, ctx.today, stored.keys)

    DetailTemplate(
        Score.HeartPoints.title, color, siblings(Detail.OfScore(Score.HeartPoints), ctx.onSwitch), onInfo = { info = true }, onBack = ctx.onBack,
        contentPadding = ctx.contentPadding, headline = headline, strip = strip, onDay = ctx.onSelectDay,
        ranges = ranges, range = range, onRange = { rangeIndex = ranges.indexOf(it) },
    ) {
        if (range == Range.Week) {
            item {
                if (daily.all { it == null }) DetailEmpty(tr(R.string.activity_detail_no_heart_points_from))
                else StackedDayBars(
                    daily.map { it ?: 0f }, ActivityCopy.HEART_POINTS_TARGET.toFloat(), color, week.map(::shortDay),
                    Modifier.padding(top = 8.dp),
                ) { i ->
                    scrub = i?.let { DetailHeadline(Math.round(daily[it] ?: 0f).toString(), ActivityCopy.earnedLabel(week[it].format(DAY)), null) }
                }
            }
            if (total != null) item {
                Column {
                    DetailSentence(ActivityCopy.heartPointsWeek(total))
                    if (ctx.isToday) ActivityCopy.dropsOff(week.first(), Math.round(daily.first() ?: 0f))?.let { CaveatLine(it, color) }
                }
            }
        } else {
            item {
                if (totals.all { it == null }) DetailEmpty(tr(R.string.activity_detail_no_heart_points_in))
                else {
                    val values = totals.map { it?.toFloat() }
                    NormalTrend(
                        values, DetailCopy.levels(values, null), color,
                        labels = listOf(0f, 1 / 3f, 2 / 3f, 1f).map { month[((month.size - 1) * it).toInt()].format(DateTimeFormatter.ofPattern("d MMM")) },
                        onScrub = { i -> scrub = i?.let { totals[it] }?.let { v -> DetailHeadline(v.toString(), tr(R.string.activity_detail_day_total, month[i].format(DAY)), ActivityCopy.heartPointsWord(v)) } },
                    )
                }
            }
            ActivityCopy.heartPointsMonth(totals)?.let { item { DetailSentence(it) } }
        }
        item { ActivityRelated(Score.HeartPoints, ctx, total) }
    }
    if (info) InfoSheet(InfoCopy.of(Score.HeartPoints), color, null, null, onDismiss = { info = false })
}

/**
 * Stacked day bars (chart kit): each day's [values] stacked on the days before it, so the last bar is the 7-day total.
 * The oldest day's part is lighter at the foot of every bar: it drops off tomorrow. A solid line at 40% marks the [target].
 * Tapping or holding picks a day ([onPick], null on release).
 */
@Composable
private fun StackedDayBars(
    values: List<Float>, target: Float, color: Color, labels: List<String>, modifier: Modifier = Modifier, height: Dp = 200.dp,
    onPick: (Int?) -> Unit,
) {
    val running = remember(values) { var sum = 0f; values.map { sum += it; sum } }
    var picked by remember(values) { mutableStateOf<Int?>(null) }
    var width by remember { mutableStateOf(0f) }
    val tick = rememberScrubHaptics()
    fun indexAt(x: Float) = if (width <= 0f || values.isEmpty()) null else (x / (width / values.size)).toInt().coerceIn(0, values.lastIndex)
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).onSizeChanged { width = it.width.toFloat() }
                .scrub(values) { x -> val i = x?.let(::indexAt); picked = i; tick(i, running, null, false); onPick(i) }
                .pointerInput(values) {
                    detectTapGestures { o ->
                        val i = indexAt(o.x)
                        picked = if (picked == i) null else i
                        tick(picked, running, null, false)
                        onPick(picked)
                    }
                },
        ) {
            val top = 18.dp.toPx()
            val hi = maxOf(target, running.maxOrNull() ?: 0f).coerceAtLeast(1f)
            fun yOf(v: Float) = top + (size.height - top) * (1f - v / hi)
            val slot = size.width / values.size.coerceAtLeast(1)
            val w = slot * 0.6f
            val r = CornerRadius(4.dp.toPx())
            val ty = yOf(target)
            drawLine(Color.White.copy(alpha = 0.4f), Offset(0f, ty), Offset(size.width, ty), 1.dp.toPx())
            val oldest = values.firstOrNull() ?: 0f
            running.forEachIndexed { i, sum ->
                if (sum <= 0f) return@forEachIndexed
                val x = slot * i + (slot - w) / 2
                val dim = picked != null && picked != i
                val main = if (i == values.lastIndex) lerp(color, Color.White, 0.3f) else color
                val a = if (dim) 0.35f else 1f
                drawRoundRect(main.copy(alpha = a), Offset(x, yOf(sum)), Size(w, size.height - yOf(sum)), r)
                // The oldest day's points, at the foot: the part that drops off tomorrow.
                if (oldest > 0f) drawRoundRect(Color.Black.copy(alpha = 0.35f * a), Offset(x, yOf(oldest)), Size(w, size.height - yOf(oldest)), r)
            }
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEach { l ->
                Text(
                    l, color = Color.White.copy(alpha = 0.55f), style = GlowType.secondary, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}

/* ------------------------------------------- Active minutes ----------------------------------------- */

/**
 * Active minutes: the last 7 days as dot bars against the 150 to aim for, a vigorous minute counted twice, or the day
 * shown with its moderate and vigorous minutes.
 */
@Composable
private fun ActiveMinutesDetail(ctx: ActivityDetailContext) {
    val date = ctx.state.date
    val color = MetricColors.steps
    val a = ctx.r?.activity
    // TODO(step 6): Month and Year need each day's active minutes further back; the engine works them out from the
    // minutes for only the 14 days ending on the day shown.
    val ranges = listOf(Range.Day, Range.Week)
    // The card's number is the week's, so the detail opens on the week.
    var rangeIndex by rememberSaveable { mutableIntStateOf(1) }
    val range = ranges[rangeIndex.coerceIn(ranges.indices)]
    var scrub by remember(range, date) { mutableStateOf<DetailHeadline?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    val week = weekOf(date)
    val byDay = a?.byDay.orEmpty()
    val day = byDay[date] ?: a?.mvpa
    val headline = scrub ?: when {
        a == null -> DetailHeadline.none(tr(R.string.common_no_reading), tr(R.string.activity_detail_needs_the_strap_s))
        range == Range.Week -> DetailHeadline(a.weekEquivalent.toString(), tr(R.string.activity_detail_minutes_last_days), ActivityCopy.activeWord(a.weekEquivalent))
        // The sentence says when ("So far today"), and which minutes.
        day != null -> DetailHeadline(day.equivalent.toString(), tr(R.string.missed_unit_minutes), null)
        else -> DetailHeadline.none(tr(R.string.common_no_reading), DetailCopy.noValue(Metric.Steps, Range.Day, ctx.isToday))
    }
    DetailTemplate(
        Score.ActiveMinutes.title, color, siblings(Detail.OfScore(Score.ActiveMinutes), ctx.onSwitch), onInfo = { info = true }, onBack = ctx.onBack,
        contentPadding = ctx.contentPadding, headline = headline, strip = stepsStrip(ctx), onDay = ctx.onSelectDay,
        ranges = ranges, range = range, onRange = { rangeIndex = ranges.indexOf(it) },
    ) {
        if (a != null && range == Range.Week) {
            val values = week.map { (byDay[it]?.equivalent ?: 0).toFloat() }
            item {
                GlowBars(
                    values, height = 200.dp, labels = week.map(::shortDay), accent = color, modifier = Modifier.padding(top = 8.dp),
                    onScrub = { i -> scrub = i?.let { DetailHeadline(Math.round(values[it]).toString(), tr(R.string.missed_minutes_on, week[it].format(DAY)), null) } },
                )
            }
            item { DetailSentence(ActivityCopy.activeWeek(a.weekEquivalent)) }
        } else if (a != null && day != null) {
            item { DetailSentence(ActivityCopy.activeDay(day, ctx.isToday)) }
        }
        item { ActivityRelated(Score.ActiveMinutes, ctx, null) }
    }
    if (info) InfoSheet(
        InfoCopy.of(Score.ActiveMinutes), color, null, null, onDismiss = { info = false },
        notes = InfoCopy.activeNotes(day?.fromHeartRate ?: 0, ctx.isToday),
    )
}

/* ------------------------------------------------ Load ---------------------------------------------- */

/**
 * Load: the day with its parts and Strain (spec 5.3, Strain lives here), and a week or a month of daily Loads against
 * your usual, a band as wide as Today's usual. The week's balance word and the strap's own weekly load under it.
 */
@Composable
private fun LoadDetail(ctx: ActivityDetailContext) {
    val date = ctx.state.date
    val r = ctx.r
    val color = MetricColors.load
    // TODO(step 6): Year needs a year of daily Loads; the engine reads 90 days and the result carries the last 30.
    val ranges = listOf(Range.Day, Range.Week, Range.Month)
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val range = ranges[rangeIndex.coerceIn(ranges.indices)]
    var scrub by remember(range, date) { mutableStateOf<DetailHeadline?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    val usual = r?.chronicLoad?.takeIf { it > 0f }
    val band = ActivityCopy.usualBand(usual)
    val byDay = r?.let { it.dailyLoads.toMap() + (date to it.load) }.orEmpty()
    val days = (range.days - 1 downTo 0).map { date.minusDays(it.toLong()) }
    val values = days.map { byDay[it] }
    val status = r?.let { TodayCopy.loadStatus(it.load, usual, endOfDay = !ctx.isToday) }
    val balance = TabCopy.loadBalance(r?.loadTrend)
    val known = values.filterNotNull()
    val headline = scrub ?: when {
        r == null -> DetailHeadline.none(tr(R.string.common_no_reading), tr(R.string.activity_detail_needs_a_day_of))
        // "So far today" and "learning your usual" are the sentence's to say, so the word is only a comparison.
        range == Range.Day -> DetailHeadline(Math.round(r.load).toString(), if (ctx.isToday) tr(R.string.missed_today_small) else tr(R.string.activity_detail_that_day), status?.takeIf { it != TodayCopy.LoadStatus.SoFar }?.text)
        known.isNotEmpty() -> DetailHeadline(
            Math.round(known.average()).toString(), tr(R.string.activity_detail_a_day_on_average, range.days), balance.takeIf { range == Range.Week },
        )
        else -> DetailHeadline.none(tr(R.string.common_no_reading), tr(R.string.activity_detail_in_the_last_days, range.days))
    }
    // The strap's own weekly load (its EPOC over 7 days) and the range it calls productive, from its latest workout.
    val strap = ctx.workouts.mapNotNull { it.effect }.filter { it.weeklyLoad != null }.maxByOrNull { it.end }
    // Load runs on heart rate, so the days with heart rate are the days with a Load.
    DetailTemplate(
        Score.Load.title, color, siblings(Detail.OfScore(Score.Load), ctx.onSwitch), onInfo = { info = true }, onBack = ctx.onBack,
        contentPadding = ctx.contentPadding, headline = headline, strip = stepsStrip(ctx, DailyMetric.HeartRate), onDay = ctx.onSelectDay,
        ranges = ranges, range = range, onRange = { rangeIndex = ranges.indexOf(it) },
    ) {
        if (r != null && range == Range.Day) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    DetailFigures(
                        listOf(
                            Math.round(r.workoutLoad).toString() to tr(R.string.activity_detail_from_workouts),
                            Math.round(r.everydayLoad).toString() to tr(R.string.activity_detail_moving_about),
                            "%.1f".format(r.strain) to tr(R.string.activity_detail_strain),
                        ),
                    )
                    DetailSentence(ActivityCopy.loadDay(r.strainPercentile, ctx.isToday))
                }
            }
        } else if (r != null) {
            item {
                if (known.isEmpty()) DetailEmpty(tr(R.string.activity_detail_no_load_in_the, range.days))
                else NormalTrend(
                    values, levels(values, band), color,
                    labels = if (range == Range.Week) days.map(::shortDay)
                    else listOf(0f, 1 / 3f, 2 / 3f, 1f).map { days[((days.size - 1) * it).toInt()].format(DateTimeFormatter.ofPattern("d MMM")) },
                    band = band, bandLabel = band?.let(ActivityCopy::usualBandLabel),
                    onScrub = { i -> scrub = i?.let { values[it] }?.let { v -> DetailHeadline(Math.round(v).toString(), days[i].format(DAY), loadWordOf(v, usual)) } },
                )
            }
            val sentence = if (range == Range.Week) ActivityCopy.balance(balance) ?: ActivityCopy.loadSpan(levels(values, band), range)
            else ActivityCopy.loadSpan(levels(values, band), range)
            sentence?.let { item { DetailSentence(it) } }
        }
        val t = r?.loadTrend
        if (t != null && t.ctl > 0f) item {
            // On Week the balance is the headline's word already.
            ListPanel(Modifier.padding(top = 8.dp), title = tr(R.string.common_this_week), note = balance.takeIf { range != Range.Week }) {
                ListRow(tr(R.string.common_your_usual), color, Math.round(t.ctl).toString(), tr(R.string.activity_detail_a_day_over_the), divider = false)
                ListRow(tr(R.string.common_last_days), color, Math.round(t.atl).toString(), tr(R.string.activity_detail_a_day_this_week))
                strap?.let { e ->
                    val low = e.optimalLoadMin
                    val high = e.optimalLoadMax
                    ListRow(
                        tr(R.string.activity_detail_the_strap_s_weekly), color, e.weeklyLoad?.let(Math::round)?.toString(),
                        if (low != null && high != null) ActivityCopy.strapRange(low, high) else tr(R.string.activity_detail_from_the_strap),
                    )
                }
            }
        }
        item { ActivityRelated(Score.Load, ctx, null) }
    }
    if (info) InfoSheet(
        InfoCopy.of(Score.Load), color, r?.let { ActivityCopy.loadBar(it.load, usual) },
        usual?.let { tr(R.string.activity_detail_you_are_here_your, Math.round(it)) }, onDismiss = { info = false },
        notes = r?.let { InfoCopy.loadNotes(it.restingHr, it.maxHr, it.maxHrTested, it.acwr) }.orEmpty(),
    )
}

/** Each day's place against your usual band: above it lit, inside or below dim (a low day isn't off, just easy). */
private fun levels(values: List<Float?>, band: ClosedFloatingPointRange<Float>?): List<DetailCopy.Level?> = values.map { v ->
    when {
        v == null -> null
        band != null && v > band.endInclusive -> DetailCopy.Level.Above
        else -> DetailCopy.Level.Inside
    }
}

private fun loadWordOf(v: Float, usual: Float?): String? = TodayCopy.loadStatus(v, usual, endOfDay = true)?.text

/* --------------------------------------------- VO2 max ---------------------------------------------- */

/** VO2 max: the strap's from your latest workout with one, else OpenStrap's estimate from your profile, with the workouts behind it. */
@Composable
private fun Vo2Detail(ctx: ActivityDetailContext) {
    val color = MetricColors.heart
    var info by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val readings = ctx.workouts.sortedByDescending { it.workout.start }
        .mapNotNull { w -> (w.workout.vo2max ?: w.effect?.vo2max?.let(Math::round))?.takeIf { it > 0 }?.let { w to it } }
    val latest = readings.firstOrNull()
    val est = ctx.r?.vo2max
    val band = est?.band?.label
    // The band is the profile estimate's, so it stands beside that number only.
    val headline = when {
        latest != null -> DetailHeadline(latest.second.toString(), "ml/kg/min", null)
        est != null -> DetailHeadline("${est.low}-${est.high}", "ml/kg/min", band?.let { tr(R.string.activity_detail_for_your_age, it) })
        else -> DetailHeadline.none(tr(R.string.activity_detail_no_estimate), tr(R.string.common_after_a_workout_the))
    }
    val withData = readings.map { Instant.ofEpochSecond(it.first.workout.start).atZone(zone).toLocalDate() }.toSet()
    DetailTemplate(
        Score.Vo2Max.title, color, siblings(Detail.OfScore(Score.Vo2Max), ctx.onSwitch), onInfo = { info = true }, onBack = ctx.onBack,
        contentPadding = ctx.contentPadding, headline = headline, strip = DetailCopy.strip(ctx.state.date, ctx.today, withData), onDay = ctx.onSelectDay,
        ranges = listOf(Range.Day), range = Range.Day, onRange = {},
    ) {
        if (latest != null || est != null) item { DetailSentence(ActivityCopy.vo2(latest != null)) }
        if (readings.isNotEmpty() || (latest != null && est != null)) item {
            ListPanel(Modifier.padding(top = 8.dp), title = tr(R.string.activity_detail_where_it_comes_from)) {
                var first = true
                readings.take(5).forEach { (w, v) ->
                    val start = Instant.ofEpochSecond(w.shownStart).atZone(zone)
                    ListRow(
                        w.sportName, color, v.toString(), start.format(DAY) + ", " + start.format(CLOCK), divider = !first,
                        onClick = { ctx.onOpen(Detail.Workout(w.workout.start)) }, icon = org.openstrap.app.ui.workout.SportIcons.of(w.workout.sportType), iconTint = SportIconTint,
                    )
                    first = false
                }
                if (latest != null && est != null) ListRow(tr(R.string.activity_detail_from_your_profile), color, "${est.low}-${est.high}", tr(R.string.activity_detail_estimated_weekly), divider = !first)
            }
        }
        item { ActivityRelated(Score.Vo2Max, ctx, null) }
    }
    if (info) InfoSheet(
        InfoCopy.of(Score.Vo2Max), color, null, band?.let { tr(R.string.activity_detail_you_are_here_for, it) }, onDismiss = { info = false },
        notes = InfoCopy.vo2Notes(est, ctx.r?.vo2maxWithheld),
    )
}

/* ------------------------------------------ Time to recover ----------------------------------------- */

/**
 * Time to recover: the hours left after your latest workout that says (never less than what earlier ones left), the
 * workout they count from ([Workouts.recoverySource]), and the recent workouts' own estimates.
 */
@Composable
private fun RecoverDetail(ctx: ActivityDetailContext) {
    val color = MetricColors.load
    var info by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis() / 1000
    val rated = ctx.workouts.filter { it.recoveryMinutes != null }.sortedByDescending { it.end }
    val last = rated.firstOrNull()
    val left = last?.let { TodayCopy.recoverLeft(it.end, it.recoveryMinutes ?: 0, now) }
    val shown = left?.let { TodayCopy.recoverShort(it, now, zone) }
    val source = org.openstrap.app.data.Workouts.recoverySource(rated)
    val ended = source?.let { w -> Instant.ofEpochSecond(w.end).atZone(zone).let { ActivityCopy.ended(it.toLocalDate(), it.format(CLOCK), ctx.today) } }
    val headline = when {
        last == null -> DetailHeadline.none(tr(R.string.activity_detail_no_estimate), tr(R.string.activity_detail_after_a_workout_the))
        shown?.first == null -> DetailHeadline.none(tr(R.string.common_recovered), tr(R.string.activity_detail_after_your, (source ?: last).sportName.lowercase(), ended))
        else -> DetailHeadline(shown.first!!, if (shown.first == "1") tr(R.string.activity_detail_hour_to_go) else tr(R.string.activity_detail_hours_to_go), shown.second)
    }
    val withData = rated.map { Instant.ofEpochSecond(it.end).atZone(zone).toLocalDate() }.toSet()
    DetailTemplate(
        Score.Recover.title, color, siblings(Detail.OfScore(Score.Recover), ctx.onSwitch), onInfo = { info = true }, onBack = ctx.onBack,
        contentPadding = ctx.contentPadding, headline = headline, strip = DetailCopy.strip(ctx.state.date, ctx.today, withData), onDay = ctx.onSelectDay,
        ranges = listOf(Range.Day), range = Range.Day, onRange = {},
    ) {
        // Recovered says which workout under the word already.
        if (source != null && shown?.first != null && ended != null) item { DetailSentence(ActivityCopy.recover(source.sportName, ended)) }
        if (rated.isNotEmpty()) item {
            ListPanel(Modifier.padding(top = 8.dp), title = tr(R.string.activity_detail_recent_workouts)) {
                rated.take(5).forEachIndexed { i, w ->
                    val start = Instant.ofEpochSecond(w.shownStart).atZone(zone)
                    // Its own, not what earlier workouts left on top ([SummaryCopy.carriedOver]).
                    val own = org.openstrap.app.ui.workout.SummaryCopy.recoverSpan(w.ownRecoveryMinutes ?: w.recoveryMinutes) ?: tr(R.string.common_none)
                    ListRow(
                        w.sportName, color, own, tr(R.string.activity_detail_on_its_own, start.format(DAY), start.format(CLOCK)),
                        divider = i > 0, onClick = { ctx.onOpen(Detail.Workout(w.workout.start)) }, icon = org.openstrap.app.ui.workout.SportIcons.of(w.workout.sportType), iconTint = SportIconTint,
                    )
                }
            }
        }
        item { ActivityRelated(Score.Recover, ctx, null) }
    }
    if (info) InfoSheet(InfoCopy.of(Score.Recover), color, null, null, onDismiss = { info = false })
}

/* ---------------------------------------------- Calories -------------------------------------------- */

/**
 * Calories (calories spec 4.5): active kcal as the headline with the goal under it. Day has the hours split by where the
 * kcal came from, the total and your use at rest, and a row per workout, walking and everyday moving. Week, Month and
 * Year read each day's stored row ([EnergyHistory]): the active kcal a day, the goal days, day bars split into
 * workouts and the rest against the goal (weekly over a year), and the change against the range before. The averages
 * read full days only; the total and the busiest day take today too ([CaloriesCopy.summary]). No profile: as before,
 * the way to add one.
 */
@Composable
private fun CaloriesDetail(ctx: ActivityDetailContext) {
    val color = MetricColors.calories
    val date = ctx.state.date
    val goal = org.openstrap.app.ui.dashboard.LocalGoals.current.calories
    val ranges = Range.entries
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val range = ranges[rangeIndex.coerceIn(ranges.indices)]
    var scrub by remember(range, date) { mutableStateOf<DetailHeadline?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    val e = ctx.r?.energy
    val active = e?.activeKcal?.let(Math::round)
    val start = date.minusDays(range.days - 1L)
    // A range reads twice its length, for the change against the one before.
    val stored by remember(range, date, ctx.energyHistory) {
        if (range == Range.Day) flowOf(null) else ctx.energyHistory?.invoke(start.minusDays(range.days.toLong()), date) ?: flowOf(emptyMap())
    }.collectAsState(null)
    // The day shown is worked out afresh, so it stands in for its row.
    val rows = stored?.let { m ->
        m.mapValues { (d, r) -> CaloriesCopy.DayKcal(d, r.activeKcal, r.totalKcal, r.workoutKcal, r.partial) } +
            (e?.let { mapOf(date to CaloriesCopy.DayKcal(date, it.activeKcal, it.totalKcal, it.workoutKcal, it.partial)) } ?: emptyMap())
    }
    val summary = rows?.let { CaloriesCopy.summary(it, start, date, goal, inProgress = date.takeIf { ctx.isToday }) }
    val headline = scrub ?: when {
        ctx.r == null -> DetailHeadline.none(tr(R.string.common_no_reading), DetailCopy.noValue(Metric.Steps, Range.Day, ctx.isToday))
        e == null || active == null -> DetailHeadline.none(tr(R.string.activity_detail_no_estimate), tr(R.string.activity_detail_needs_your_age_weight))
        range == Range.Day -> DetailHeadline("%,d".format(active), CaloriesCopy.dayLabel(ctx.isToday), tr(R.string.common_goal_met).takeIf { active >= goal })
        summary == null -> DetailHeadline.none(tr(R.string.common_loading), DetailCopy.noValue(Metric.Steps, range, ctx.isToday))
        summary.activeADay != null -> DetailHeadline("%,d".format(summary.activeADay), CaloriesCopy.rangeLabel(range), CaloriesCopy.goalDays(summary))
        else -> DetailHeadline.none(tr(R.string.activity_detail_no_full_days_yet), DetailCopy.noValue(Metric.Steps, range, ctx.isToday))
    }
    DetailTemplate(
        Score.Energy.title, color, siblings(Detail.OfScore(Score.Energy), ctx.onSwitch), onInfo = { info = true }, onBack = ctx.onBack,
        contentPadding = ctx.contentPadding, headline = headline, strip = stepsStrip(ctx, DailyMetric.HeartRate), onDay = ctx.onSelectDay,
        ranges = ranges, range = range, onRange = { rangeIndex = ranges.indexOf(it) },
    ) {
        if (e == null || active == null) {
            if (ctx.r != null) item {
                ListPanel(Modifier.padding(top = 8.dp)) { LinkRow(tr(R.string.activity_detail_add_age_weight_and), ctx.onEditProfile, divider = false) }
            }
        } else if (range == Range.Day) {
            item { GoalBar(CaloriesCopy.share(active, goal), CaloriesCopy.toGoal(active, goal), color) }
            item { HourlyKcal(e, color) { h -> scrub = h?.let { DetailHeadline("%,d".format(Math.round(e.hourly.total[it])), CaloriesCopy.hourLabel(it), null) } } }
            item {
                Column {
                    // So far today both are; the activity level only means something for a whole day, in the goal's place.
                    DetailFigures(
                        listOf(
                            "%,d".format(Math.round(e.totalKcal)) to tr(R.string.activity_detail_total_kcal),
                            "%,d".format(Math.round(e.totalKcal - e.activeKcal)) to tr(R.string.activity_detail_at_rest),
                            (e.pal?.takeIf { !ctx.isToday }?.let { "%.2f".format(it) to tr(R.string.activity_detail_activity_level) } ?: ("%,d".format(goal) to tr(R.string.activity_detail_goal))),
                        ),
                    )
                    DetailSentence(ActivityCopy.energy(Math.round(e.bmrKcal)))
                    InfoCopy.energyNotes(e).firstOrNull()?.let { note ->
                        androidx.compose.foundation.layout.Box(Modifier.clickable { info = true }) { CaveatLine(note, color) }
                    }
                }
            }
            item {
                val zone = ZoneId.systemDefault()
                val sources = remember(ctx.workouts, date, e) { CaloriesCopy.sources(ctx.workouts, date, e, e.bmrKcal / 1440f, zone) }
                ListPanel(Modifier.padding(top = 8.dp), title = tr(R.string.activity_detail_where_it_came_from)) {
                    sources.forEachIndexed { i, s ->
                        val start = s.workoutStart
                        if (start != null) ListRow(
                            s.name, color, "%,d".format(s.kcal), s.line, divider = i > 0, onClick = { ctx.onOpen(Detail.Workout(start)) },
                            icon = org.openstrap.app.ui.workout.SportIcons.of(s.sportType ?: 0), iconTint = SportIconTint,
                        ) else ListRow(
                            s.name, color, "%,d".format(s.kcal), s.line, divider = i > 0,
                            icon = if (s.name == tr(R.string.frame_walking)) Metric.Steps.rowIcon() else AppIcons.PersonArmsSpread,
                        )
                    }
                }
            }
        } else {
            item {
                when {
                    rows == null -> DetailEmpty(tr(R.string.common_loading))
                    summary == null || rows.keys.none { it in start..date } -> DetailEmpty(tr(R.string.activity_detail_no_calories_in_this))
                    else -> RangeKcal(rows, range, start, date, goal, color) { scrub = it }
                }
            }
            if (summary != null && summary.days > 0) {
                item {
                    DetailFigures(
                        listOfNotNull(
                            "%,d".format(summary.activeTotal) to tr(R.string.activity_detail_active_kcal_in_all),
                            summary.totalADay?.let { "%,d".format(it) to tr(R.string.activity_detail_total_a_day) },
                            summary.vsBefore?.let { CaloriesCopy.signed(it) to CaloriesCopy.vsLabel(range) },
                        ),
                        Modifier.padding(top = 18.dp),
                    )
                }
                CaloriesCopy.rangeSentence(summary)?.let { item { DetailSentence(it) } }
            }
        }
        // On Day the list of sources sits right above, so Related takes the neutral edge (spec 2.5).
        item {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalEdgeOverride provides if (range == Range.Day && e != null) EdgeOverride(edge = Edge.Bottom) else EdgeOverride(),
            ) { ActivityRelated(Score.Energy, ctx, null) }
        }
    }
    if (info) InfoSheet(InfoCopy.of(Score.Energy), color, null, null, onDismiss = { info = false }, notes = InfoCopy.energyNotes(e))
}

/** The day toward the goal: a 4 dp bar in [color] filled to [share], [text] under it ("102 to your goal of 450"). */
@Composable
private fun GoalBar(share: Float, text: String, color: Color) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
    Column(Modifier.padding(top = 8.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.08f), shape)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).background(color, shape))
        }
        Text(text, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The hours' colours: workouts in the ink, walking in its darker shade, everyday moving in white at 40%. */
private val WalkingKcal = MetricColors.caloriesShade
private val EverydayKcal = Color.White.copy(alpha = 0.4f)

/**
 * By the hour: the day's 24 hours as bars split by where the kcal came from (workouts at the foot, then walking, then
 * everyday moving), the busiest hour as the title's note, the legend under the bars. Usual light from below (spec 2.5).
 * Tapping or holding an hour puts its kcal in the headline ([onPick]).
 */
@Composable
private fun HourlyKcal(e: org.openstrap.app.data.EnergyExpenditure.Day, color: Color, onPick: (Int?) -> Unit) {
    val h = e.hourly
    val stacks = remember(h) { List(24) { listOf(h.workout[it], h.walking[it], h.everyday[it].coerceAtLeast(0f)) } }
    EdgeCard(color, Modifier.fillMaxWidth().padding(top = 8.dp)) {
        EdgeTitle(tr(R.string.activity_detail_by_the_hour), CaloriesCopy.peak(h.total))
        KcalBars(stacks, listOf(color, WalkingKcal, EverydayKcal), goal = null, labels = listOf("00", "06", "12", "18", "24"), spread = true, Modifier.padding(top = 16.dp), height = 120.dp, onPick = onPick)
        KcalLegend(listOf(tr(R.string.common_workouts) to color, tr(R.string.frame_walking) to WalkingKcal, tr(R.string.activity_detail_everyday) to EverydayKcal), Modifier.padding(top = 12.dp))
    }
}

/**
 * A week or a month of days, or a year of weeks (each a day's average), as bars split into workouts and the rest,
 * with the goal as a solid line at 40% (spec 2.7). Tapping or holding a bar puts it in the headline ([onScrub]).
 */
@Composable
private fun RangeKcal(
    rows: Map<LocalDate, CaloriesCopy.DayKcal>, range: Range, start: LocalDate, end: LocalDate, goal: Int, color: Color,
    onScrub: (DetailHeadline?) -> Unit,
) {
    val bars = remember(rows, range, start, end) {
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        if (range != Range.Year) days.map { d -> d to rows[d]?.let { listOf(it.workout, (it.active - it.workout).coerceAtLeast(0f)) } }
        else days.groupBy { it.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) }.map { (monday, week) ->
            val have = week.mapNotNull { rows[it] }
            monday to have.takeIf { it.isNotEmpty() }?.let { w ->
                val workout = w.sumOf { it.workout.toDouble() }.toFloat() / w.size
                listOf(workout, (w.sumOf { it.active.toDouble() }.toFloat() / w.size - workout).coerceAtLeast(0f))
            }
        }
    }
    val labels = when (range) {
        Range.Week -> bars.map { shortDay(it.first) }
        Range.Month -> listOf(0f, 1 / 3f, 2 / 3f, 1f).map { bars[((bars.size - 1) * it).toInt()].first.format(DateTimeFormatter.ofPattern("d MMM")) }
        else -> listOf(0f, 1 / 3f, 2 / 3f, 1f).map { bars[((bars.size - 1) * it).toInt()].first.format(DateTimeFormatter.ofPattern("MMM")) }
    }
    Column(Modifier.padding(top = 8.dp)) {
        KcalBars(
            bars.map { it.second ?: emptyList() }, listOf(color, WalkingKcal), goal.toFloat(), labels, spread = range != Range.Week, height = 200.dp,
            onPick = { i ->
                onScrub(i?.let { bars[it] }?.let { (d, parts) ->
                    parts?.let {
                        val label = if (range == Range.Year) tr(R.string.activity_detail_active_kcal_a_day, d.format(DateTimeFormatter.ofPattern("d MMM"))) else tr(R.string.activity_detail_active_kcal, d.format(DAY))
                        DetailHeadline("%,d".format(Math.round(it.sum())), label, null)
                    }
                })
            },
        )
        KcalLegend(listOf(tr(R.string.common_workouts) to color, tr(R.string.activity_detail_the_rest) to WalkingKcal), Modifier.padding(top = 12.dp))
    }
}

/**
 * Bars stacked from the foot in [colors], one list of parts a bar; an empty bar isn't drawn, only the hairline
 * baseline under them. [goal] draws a solid line at 40% white. Labels under it: one a bar, or [spread] across the
 * width. Tapping or holding picks a bar ([onPick], null on release); the others dim.
 */
@Composable
private fun KcalBars(
    stacks: List<List<Float>>, colors: List<Color>, goal: Float?, labels: List<String>, spread: Boolean,
    modifier: Modifier = Modifier, height: Dp = 200.dp, onPick: (Int?) -> Unit,
) {
    val sums = remember(stacks) { stacks.map { it.sum() } }
    var picked by remember(stacks) { mutableStateOf<Int?>(null) }
    var width by remember { mutableStateOf(0f) }
    val tick = rememberScrubHaptics()
    fun indexAt(x: Float) = if (width <= 0f || stacks.isEmpty()) null else (x / (width / stacks.size)).toInt().coerceIn(0, stacks.lastIndex)
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).onSizeChanged { width = it.width.toFloat() }
                .scrub(sums) { x -> val i = x?.let(::indexAt); picked = i; tick(i, sums, null, false); onPick(i) }
                .pointerInput(stacks) {
                    detectTapGestures { o ->
                        val i = indexAt(o.x)
                        picked = if (picked == i) null else i
                        tick(picked, sums, null, false)
                        onPick(picked)
                    }
                },
        ) {
            val top = 8.dp.toPx()
            val hi = maxOf(goal ?: 0f, sums.maxOrNull() ?: 0f).coerceAtLeast(1f)
            fun yOf(v: Float) = top + (size.height - top) * (1f - v / hi)
            val slot = size.width / stacks.size.coerceAtLeast(1)
            val w = (slot * 0.6f).coerceAtMost(14.dp.toPx())
            val r = CornerRadius(minOf(4.dp.toPx(), w / 2))
            drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
            stacks.forEachIndexed { i, parts ->
                val sum = sums[i]
                if (sum <= 0f) return@forEachIndexed
                val x = slot * i + (slot - w) / 2
                val a = if (picked != null && picked != i) 0.35f else 1f
                val bar = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(x, yOf(sum), x + w, size.height, r))
                }
                clipPath(bar) {
                    var below = 0f
                    parts.forEachIndexed { k, v ->
                        if (v <= 0f) return@forEachIndexed
                        val c = colors[k.coerceAtMost(colors.lastIndex)]
                        drawRect(c.copy(alpha = c.alpha * a), Offset(x, yOf(below + v)), Size(w, yOf(below) - yOf(below + v)))
                        below += v
                    }
                }
            }
            goal?.let { g -> val y = yOf(g); drawLine(Color.White.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
        }
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = if (spread) androidx.compose.foundation.layout.Arrangement.SpaceBetween else androidx.compose.foundation.layout.Arrangement.Start,
        ) {
            labels.forEach { l ->
                Text(
                    l, color = Color.White.copy(alpha = 0.55f), style = GlowType.secondary, maxLines = 1,
                    modifier = if (spread) Modifier else Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** What the bars' colours are: a small round swatch and its name, side by side. */
@Composable
private fun KcalLegend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
        items.forEach { (name, c) ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(c, androidx.compose.foundation.shape.CircleShape))
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                Text(name, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
            }
        }
    }
}

/* ---------------------------------------------- Distance -------------------------------------------- */

/** Distance: the day's kilometres from your steps and height, or the strap's own figure. */
@Composable
private fun DistanceDetail(ctx: ActivityDetailContext) {
    val color = MetricColors.steps
    var info by rememberSaveable { mutableStateOf(false) }
    // TODO(step 6): Week and longer need each day's distance, which the engine works out only for the day shown.
    val ours = ctx.r?.activity?.distanceMeters
    val meters = ours ?: ctx.state.distanceMeters?.toFloat()
    val km = meters?.div(1000f)
    val headline = if (km != null) DetailHeadline("%.1f".format(km), ActivityCopy.distanceSource(ours != null), null)
    else DetailHeadline.none(tr(R.string.common_no_reading), DetailCopy.noValue(Metric.Steps, Range.Day, ctx.isToday))
    DetailTemplate(
        Score.Distance.title, color, siblings(Detail.OfScore(Score.Distance), ctx.onSwitch), onInfo = { info = true }, onBack = ctx.onBack,
        contentPadding = ctx.contentPadding, headline = headline, strip = stepsStrip(ctx), onDay = ctx.onSelectDay,
        ranges = listOf(Range.Day), range = Range.Day, onRange = {},
    ) {
        if (km != null && ctx.state.steps > 0) item { DetailSentence(ActivityCopy.distance(ctx.state.steps, ctx.isToday)) }
        item { ActivityRelated(Score.Distance, ctx, null) }
    }
    if (info) InfoSheet(InfoCopy.of(Score.Distance), color, null, null, onDismiss = { info = false })
}

/* ---------------------------------------------- Related --------------------------------------------- */

/**
 * The rows under an Activity detail: the tab's other numbers about moving (steps, active minutes, Heart points and
 * Load) without the one showing, each with the day's value, opening its detail. [heartPoints] is the 7-day total when
 * the detail has it loaded.
 */
@Composable
private fun ActivityRelated(current: Score, ctx: ActivityDetailContext, heartPoints: Int?) {
    val a = ctx.r?.activity
    val goal = org.openstrap.app.ui.dashboard.LocalGoals.current.steps
    val points = heartPoints ?: ctx.state.pai?.total?.let(Math::round)
    ListPanel(Modifier.padding(top = 8.dp), title = tr(R.string.common_related)) {
        var first = true
        fun divider() = !first.also { first = false }
        ListRow(
            Metric.Steps.title, MetricColors.steps, ctx.state.steps.takeIf { it > 0 }?.let { "%,d".format(it) },
            ActivityCopy.goalShare(ctx.state.steps, goal), onClick = { ctx.onOpen(Detail.OfMetric(Metric.Steps)) }, divider = divider(),
            icon = Metric.Steps.rowIcon(),
        )
        if (current != Score.ActiveMinutes) ListRow(
            Score.ActiveMinutes.title, MetricColors.steps, a?.weekEquivalent?.toString(),
            a?.let { TabCopy.activeMinutes(it.weekEquivalent, Activity.MVPA_WEEKLY_TARGET) } ?: tr(R.string.common_nothing_for_this_day),
            onClick = { ctx.onOpen(Detail.OfScore(Score.ActiveMinutes)) }, divider = divider(), icon = Score.ActiveMinutes.rowIcon(),
        )
        if (current != Score.HeartPoints) ListRow(
            Score.HeartPoints.title, MetricColors.heart, points?.toString(),
            points?.let { TabCopy.heartPoints(it, ActivityCopy.HEART_POINTS_TARGET) } ?: tr(R.string.common_after_the_strap_s),
            onClick = { ctx.onOpen(Detail.OfScore(Score.HeartPoints)) }, divider = divider(), icon = Score.HeartPoints.rowIcon(),
        )
        if (current != Score.Load) ListRow(
            Score.Load.title, MetricColors.load, ctx.r?.load?.let(Math::round)?.toString(),
            ctx.r?.let { TabCopy.loadUsual(it.chronicLoad) } ?: tr(R.string.common_nothing_for_this_day),
            onClick = { ctx.onOpen(Detail.OfScore(Score.Load)) }, divider = divider(), icon = Score.Load.rowIcon(),
        )
    }
}

/* ----------------------------------------------- Week ----------------------------------------------- */

/**
 * The week story, opened from Your week on Today: last Monday to Sunday, steps a day as dot bars to scrub, the week in
 * a few numbers, a sentence comparing with the week before where it's kept, and each day's line. No strip: tapping a
 * day there would move the day shown, and with it the week.
 */
@Composable
internal fun WeekDetail(ctx: ActivityDetailContext) {
    val color = EdgeNeutral
    var info by rememberSaveable { mutableStateOf(false) }
    val week = TodayCopy.lastWeek(ctx.state.date)
    val start = week.start
    val end = week.endInclusive
    var scrub by remember(start) { mutableStateOf<DetailHeadline?>(null) }
    val steps by remember(start, ctx.history) { ctx.history(DailyMetric.Steps, start.minusDays(7), end) }.collectAsState(null)
    val zone = ZoneId.systemDefault()
    fun dayOf(w: WorkoutAnalysis) = Instant.ofEpochSecond(w.workout.start).atZone(zone).toLocalDate()
    val stepsByDay = steps.orEmpty().associate { it.date to Math.round(it.value) }
    val days = WeekStory.days(
        week, stepsByDay,
        ctx.r?.activity?.byDay.orEmpty().mapValues { it.value.equivalent },
        ctx.workouts.filter { dayOf(it) in start..end && it.workout.sportType != org.openstrap.app.data.Workouts.GENERIC_ACTIVITY }
            .sortedBy { it.workout.start }.groupBy(::dayOf).mapValues { (_, v) -> v.map { it.sportName } },
        ctx.state.nights.mapNotNull { n -> n.asleepSeconds?.let { n.date to it } }.toMap(),
    )
    val totals = WeekStory.totals(days)
    fun avg(from: LocalDate, to: LocalDate) = steps.orEmpty().filter { it.date in from..to && it.value > 0f }.map { it.value.toDouble() }.takeIf { it.isNotEmpty() }?.average()
    val change = TodayCopy.stepsChange(avg(start, end), avg(start.minusDays(7), start.minusDays(1)))
    // Workouts go back as far as the app keeps them; the week before counts only once the app has a workout before it.
    val before = start.minusDays(7)
    val workoutsBefore = if (ctx.workouts.any { dayOf(it) < start }) ctx.workouts.count {
        dayOf(it) in before..start.minusDays(1) && it.workout.sportType != org.openstrap.app.data.Workouts.GENERIC_ACTIVITY
    } else null
    val headline = scrub ?: when {
        totals.stepsADay != null -> DetailHeadline("%,d".format(totals.stepsADay), WeekStory.label(week), change)
        steps == null -> DetailHeadline.none(tr(R.string.common_loading), WeekStory.label(week).replaceFirstChar { it.uppercase() })
        else -> DetailHeadline.none(tr(R.string.activity_detail_no_steps), tr(R.string.activity_detail_monday_to_sunday_last))
    }
    DetailTemplate(
        tr(R.string.common_your_week), color, emptyList(), onInfo = { info = true }, onBack = ctx.onBack, contentPadding = ctx.contentPadding,
        headline = headline, strip = emptyList(), onDay = {}, ranges = listOf(Range.Week), range = Range.Week, onRange = {},
    ) {
        val values = days.map { (it.steps ?: 0).toFloat() }
        if (values.any { it > 0f }) item {
            GlowBars(
                values, height = 180.dp, labels = days.map { shortDay(it.date) }, accent = MetricColors.steps, modifier = Modifier.padding(top = 8.dp),
                onScrub = { i -> scrub = i?.let { DetailHeadline(Math.round(values[it]).toString(), tr(R.string.missed_steps_on, days[it].date.format(DAY)), null) } },
            )
        }
        item {
            DetailFigures(
                listOf(
                    totals.activeMinutes?.toString() to tr(R.string.common_active_minutes),
                    totals.workouts.toString() to tr(R.string.common_workouts),
                    totals.asleepANight?.let(::clockDuration) to tr(R.string.activity_detail_asleep_a_night),
                ).filter { it.first != null },
                Modifier.padding(top = 18.dp),
            )
        }
        // The steps against the week before are the headline's word already, so the sentence leaves them out.
        WeekStory.sentence(days, null, workoutsBefore)?.let { item { DetailSentence(it) } }
        item {
            ListPanel(Modifier.padding(top = 8.dp), title = tr(R.string.activity_detail_day_by_day)) {
                WeekStory.rows(days).forEachIndexed { i, r ->
                    ListRow(r.title, MetricColors.steps, r.steps?.let { "%,d".format(it) }, r.line, divider = i > 0, dash = false)
                }
            }
        }
    }
    if (info) InfoSheet(InfoCopy.WEEK, MetricColors.steps, null, null, onDismiss = { info = false })
}
