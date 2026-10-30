package org.openstrap.app.ui.glow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import org.openstrap.app.ui.dashboard.TabCopy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.components.rememberScrubHaptics
import org.openstrap.app.ui.components.scrub
import org.openstrap.app.ui.dashboard.CaloriesCopy
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.EffectFigures
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.workout.WorkoutDetailCopy
import org.openstrap.app.ui.workout.WorkoutFact
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * The page of a past workout (spec 6.3, "Workout detail"), one for the strap's and the phone's: the sentence first on
 * a glow as big as the effort, the heart rate in its zones' colours with the drop after the end, the small facts and
 * the map, Load with time to recover and the heart rate drop, time in zones and the splits. Training effect and EPOC
 * sit behind the (i) on the Load card. A workout row grows into this page (spec 3.5).
 */

/** The heart rate line is drawn in ten-second steps: readable on a phone, and the card still says the time. */
private const val HeartStep = 10L

/** What deleting a strap workout does. */
private val STRAP_DELETE: String get() =
    tr(R.string.glow_workout_screen_its_heart_rate_and)

/**
 * The workout [a]. A phone workout shows from its [summary] (route, laps, sets and the whole recording, for trimming),
 * a strap workout from [a] alone. [workouts] are all of them, for your usual Load, as the rows that open this page
 * use; [usualDrop] your usual heart rate drop. Trim and undo are for phone workouts.
 */
@Composable
fun GlowWorkoutDetailScreen(
    a: WorkoutAnalysis?,
    summary: PhoneSummary?,
    workouts: List<WorkoutAnalysis>,
    usualDrop: Int?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onTrim: (from: Long, to: Long) -> Unit = { _, _ -> },
    onUndoTrim: () -> Unit = {},
    /** Your BMR a minute, for a strap workout's active kcal; null without a profile. */
    bmrPerMinute: Float? = null,
    /** The active kcal of the workout's day, for In your day, and the way to that day's Calories. */
    dayActiveKcal: Float? = null,
    onOpenDay: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val shown = if (a?.fromPhone == true) summary?.analysis else a
    val phone = summary?.takeIf { a?.fromPhone == true }
    val list = rememberLazyListState()
    var info by remember { mutableStateOf(false) }
    val usual = remember(workouts) { Workouts.usualLoad(workouts.map { it.workout.trainingLoad }) }
    // Each kind keeps the zone figures its page always had: a phone workout's live zones, the strap's from its stream.
    val zones = remember(shown) {
        shown?.let { s ->
            if (s.fromPhone) WorkoutRecorder.liveZoneSeconds(s.heartRate.filter { it.timestamp <= s.end }.map { it.timestamp to it.bpm }, s.maxHeartRate).toList()
            else s.zoneSeconds
        }.orEmpty()
    }
    CompositionLocalProvider(LocalGlowPaused provides { list.isScrollInProgress }) {
        ScreenList(contentPadding, list, gutter = GlowLayout.Gutter) {
            item {
                PageHeader(
                    (shown ?: a)?.sportName ?: tr(R.string.common_workout), onBack,
                    trailing = if ((shown ?: a)?.startedByYou == false) ({ FoundTag() }) else null,
                )
            }
            if (shown == null) return@ScreenList
            val w = shown.workout
            item { WorkoutHero(shown, usual, zones) }
            if (shown.heartRate.isNotEmpty() || w.averageHeartRate != null) item { HeartRateCard(shown) }
            val activeKcal = Workouts.activeKcal(shown, bmrPerMinute)
            val facts = WorkoutDetailCopy.facts(
                shown.distanceMeters, shown.distanceFromSteps,
                if (shown.fromPhone) (shown.durationSeconds - shown.activeSeconds).coerceAtLeast(0) else 0L, w.steps, w.calories,
                activeKcal = activeKcal.takeIf { shown.fromPhone || bmrPerMinute != null },
            ) + listOfNotNull(WorkoutDetailCopy.targetFact(shown.target, shown.targetReachedSeconds, shown.activeKcal?.roundToInt()))
            val factsShown = facts.isNotEmpty() || (phone?.route?.size ?: 0) >= 2
            if (factsShown) item { FactsList(facts, phone) }
            // The workout's share of its day, right under its kcal (calories spec 4.2).
            val today = LocalDate.now()
            val inDay = activeKcal?.let { k ->
                val date = Instant.ofEpochSecond(shown.shownStart).atZone(ZoneId.systemDefault()).toLocalDate()
                CaloriesCopy.inYourDay(WorkoutDetailCopy.sport(w.sportType), k, dayActiveKcal?.roundToInt(), CaloriesCopy.dayWord(date, today), shown.fromPhone)
            }
            if (inDay != null) item { InYourDayCard(inDay, onOpenDay) }
            val drop = WorkoutDetailCopy.dropLine(shown.recovery60, shown.recoveryIsMeaningful, usualDrop)
            val loadShown = w.trainingLoad != null || (shown.recoveryMinutes ?: 0) > 0 || drop != null
            if (loadShown) item { LoadCard(shown, usual, best = null, drop = drop, onInfo = { info = true }) }
            // A list right under another would look the same, so the later one sits on a neutral edge card (spec 2.4):
            // the zones when the facts are right above them, else the splits under a plain list.
            val zonesShown = zones.sum() > 0
            val factsRightAbove = factsShown && inDay == null && !loadShown
            val zonesOnEdge = zonesShown && factsRightAbove
            val listAbove = if (zonesShown) !zonesOnEdge else factsRightAbove
            if (zonesShown) item {
                CompositionLocalProvider(LocalEdgeOverride provides if (zonesOnEdge) EdgeOverride(edge = Edge.Bottom) else EdgeOverride()) {
                    ZonesList(zones.toIntArray(), shown.maxHeartRate)
                }
            }
            if (phone != null) item {
                CompositionLocalProvider(LocalEdgeOverride provides if (listAbove) EdgeOverride(edge = Edge.Bottom) else EdgeOverride()) {
                    SplitsList(phone, liveRoute = emptyList())
                }
            }
            if (phone != null) item { TrimSection(phone, onTrim, onUndoTrim) }
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (phone != null) SummaryActions(justEnded = false, onDone = onBack, onDelete = onDelete)
                    else DeleteWorkout(STRAP_DELETE, onDelete, Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
    if (info && shown != null) EffectInfo(shown, usual, phone?.strapWorkouts.orEmpty(), phone?.effectFromStrap == true, onDismiss = { info = false })
}

/**
 * The sentence ([WorkoutDetailCopy.sentence]), the day and time range, and the length in dots. Lit from below, the
 * light as high as the effort (spec 2.5): an easy walk leaves most of the card dark, a hard run floods it.
 */
@Composable
private fun WorkoutHero(a: WorkoutAnalysis, usual: Float?, zones: List<Int>) {
    val w = a.workout
    val style = GlowStyle.chart(MetricColors.heart).copy(lightAngle = LightAngle.Below, lightLevel = Workouts.effort(w.trainingLoad, usual))
    GlowCard(style, Modifier.fillMaxWidth()) {
        Column {
            GlowHeader(WorkoutDetailCopy.day(a.shownStart, LocalDate.now()), trailing = "${clockAt(a.shownStart)}–${clockAt(a.end)}")
            HeroSentence(WorkoutDetailCopy.sentence(w.sportType, a.activeSeconds, w.trainingLoad, usual, zones))
            DotMatrixText(workoutClock(a.activeSeconds), Modifier.padding(top = 16.dp), dot = DotSize.Hero)
        }
    }
}

/**
 * Heart rate over the workout in its zones' colours, and the strap's minutes after the end, dimmer, as the drop. The
 * number is the average; hold the line to scrub and it follows the finger (spec 2.7), with the scrub ticks. Lit from
 * the left: about earlier.
 */
@Composable
private fun HeartRateCard(a: WorkoutAnalysis) {
    val w = a.workout
    val first = a.shownStart
    val last = (a.heartRate.maxOfOrNull { it.timestamp } ?: a.end).coerceIn(a.end, a.end + Workouts.RECOVERY_TAIL_S)
    val values = remember(a) {
        val n = ((last - first) / HeartStep + 1).toInt().coerceAtLeast(2)
        val sums = FloatArray(n)
        val counts = IntArray(n)
        a.heartRate.forEach { s ->
            val i = ((s.timestamp - first) / HeartStep).toInt()
            if (i in 0 until n) { sums[i] += s.bpm.toFloat(); counts[i]++ }
        }
        List(n) { if (counts[it] == 0) null else sums[it] / counts[it] }
    }
    var scrubbed by remember { mutableStateOf<Int?>(null) }
    val at = scrubbed?.let { i -> values.getOrNull(i)?.let { v -> first + i * HeartStep to v.roundToInt() } }
    EdgeCard(MetricColors.heart, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        EdgeTitle(tr(R.string.common_heart_rate), if (a.heartRate.isNotEmpty()) tr(R.string.glow_workout_screen_coloured_by_zone) else null)
        if (at != null) {
            EdgeValue(at.second.toString(), "bpm", if (at.first > a.end) tr(R.string.glow_workout_screen_at_after_the_end, clockAt(at.first)) else tr(R.string.glow_workout_screen_at, clockAt(at.first)))
        } else {
            val range = listOfNotNull(w.maxHeartRate?.let { tr(R.string.glow_workout_screen_max, it) }, w.minHeartRate?.let { tr(R.string.missed_lowest, it) }).joinToString(", ")
            EdgeValue(w.averageHeartRate?.toString(), tr(R.string.glow_workout_screen_bpm_average), range.ifEmpty { null }?.let { "$it bpm" }, waiting = false)
        }
        if (a.heartRate.isEmpty()) {
            Text(
                tr(R.string.glow_workout_screen_the_strap_hasn_t), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                style = GlowType.secondary, modifier = Modifier.padding(top = 10.dp),
            )
            return@EdgeCard
        }
        ZoneHeartLine(values, first, a.end, a.maxHeartRate, a.historyRanges, scrubbed, { scrubbed = it }, Modifier.padding(top = 16.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Note(clockAt(first))
            Spacer(Modifier.weight(1f))
            Note(if (last > a.end + HeartStep) tr(R.string.glow_workout_screen_drop_after, clockAt(a.end)) else clockAt(a.end))
        }
        if (a.historyRanges.isNotEmpty()) Text(
            tr(R.string.glow_workout_screen_dimmed) + a.historyRanges.joinToString(", ") { "${clockAt(it.first)}–${clockAt(it.last + 1)}" } + tr(R.string.glow_workout_screen_came_from_the_strap),
            color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The line itself: each ten-second step in its zone's colour up to the [end], dim white after it with a solid hairline
 * mark at the end; minutes from the strap's [history] dimmed. [scrubbed] is the step under the finger, [onScrub] follows it.
 */
@Composable
private fun ZoneHeartLine(
    values: List<Float?>, first: Long, end: Long, maxHr: Int, history: List<LongRange>,
    scrubbed: Int?, onScrub: (Int?) -> Unit, modifier: Modifier = Modifier,
) {
    var width by remember { mutableStateOf(0f) }
    val tick = rememberScrubHaptics()
    val known = values.filterNotNull()
    val lo = (known.minOrNull() ?: 60f) - 5f
    val hi = (known.maxOrNull() ?: 180f) + 5f
    val endIndex = ((end - first) / HeartStep).toInt()
    Canvas(
        modifier.fillMaxWidth().height(96.dp).onSizeChanged { width = it.width.toFloat() }.scrub(values) { x ->
            val i = x?.let { nearestReading(values, it, width, 0f) }
            tick(i, values, null, true)
            onScrub(i)
        },
    ) {
        val dx = size.width / (values.size - 1).coerceAtLeast(1)
        fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
        if (endIndex in 1 until values.lastIndex) drawLine(
            Color.White.copy(alpha = 0.35f), Offset(endIndex * dx, 0f), Offset(endIndex * dx, size.height), strokeWidth = 1.dp.toPx(),
        )
        for (i in 0 until values.lastIndex) {
            val v0 = values[i] ?: continue
            val v1 = values[i + 1] ?: continue
            val t = first + i * HeartStep
            val color = if (i >= endIndex) Color.White.copy(alpha = 0.55f) else zoneColor(v0.roundToInt(), maxHr)
            drawLine(
                // The strap's history, filled in after: the same line, dimmed (spec 2.7, no dashes).
                if (history.any { t in it }) color.copy(alpha = color.alpha * 0.45f) else color,
                Offset(i * dx, y(v0)), Offset((i + 1) * dx, y(v1)), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        scrubbed?.let { i ->
            val v = values.getOrNull(i) ?: return@let
            val p = Offset(i * dx, y(v))
            drawLine(Color.White.copy(alpha = 0.4f), Offset(p.x, 0f), Offset(p.x, size.height), strokeWidth = 1.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.6f), 6.dp.toPx(), p)
            drawCircle(Color.White, 4.dp.toPx(), p)
        }
    }
}

/**
 * In your day: what share of its day's active kcal the workout is ([CaloriesCopy.inYourDay]), lit from the left in
 * Calories' Gold (about the day it's part of). Opens that day's Calories.
 */
@Composable
private fun InYourDayCard(text: String, onOpen: (() -> Unit)?) {
    EdgeCard(MetricColors.calories, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier, onClick = onOpen) {
        EdgeTitle(tr(R.string.glow_workout_screen_in_your_day), null)
        Text(text, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The small facts ([WorkoutDetailCopy.facts]) as rows, and the map under them when the route was kept. */
@Composable
private fun FactsList(facts: List<WorkoutFact>, summary: PhoneSummary?) {
    ListPanel(title = tr(R.string.common_the_workout)) {
        facts.forEachIndexed { i, f ->
            val color = when (f.name) {
                tr(R.string.common_paused) -> PausedColor
                tr(R.string.common_calories), "Target" -> MetricColors.calories
                else -> MetricColors.steps
            }
            val icon = when (f.name) {
                tr(R.string.common_distance) -> Score.Distance.rowIcon()
                tr(R.string.common_paused) -> AppIcons.Hourglass
                tr(R.string.common_steps) -> Metric.Steps.rowIcon()
                tr(R.string.common_calories) -> Score.Energy.rowIcon()
                "Target" -> AppIcons.Target
                else -> null
            }
            ListRow(f.name, color, f.value, f.status, divider = i > 0, icon = icon)
        }
        summary?.let { RouteMap(it, Modifier.padding(top = 6.dp, bottom = 10.dp)) }
    }
}

/** The (i) on the Load card: Load, training effect, time to recover, the heart rate drop and VO2 max explained. */
@Composable
private fun EffectInfo(a: WorkoutAnalysis, usual: Float?, strap: List<WorkoutAnalysis>, fromStrap: Boolean, onDismiss: () -> Unit) {
    val w = a.workout
    val sheet = WorkoutDetailCopy.effectSheet(
        aerobic = w.aerobicTrainingEffect, anaerobic = w.anaerobicTrainingEffect, load = w.trainingLoad, usualLoad = usual,
        recoveryMinutes = a.recoveryMinutes, drop60 = a.recovery60, drop120 = a.recovery120, endBpm = a.endHeartRate,
        meaningfulDrop = a.recoveryIsMeaningful, vo2max = a.effect?.vo2max ?: w.vo2max?.toFloat(), fromPhone = a.fromPhone,
        strap = strap.map { s ->
            EffectFigures(
                s.workout.aerobicTrainingEffect, s.workout.anaerobicTrainingEffect, s.workout.trainingLoad, s.recoveryMinutes,
                from = Instant.ofEpochSecond(s.workout.start).atZone(ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            )
        },
        effectFromStrap = fromStrap, ownRecoveryMinutes = a.ownRecoveryMinutes, sportType = w.sportType,
    )
    InfoSheet(sheet.info, MetricColors.load, sheet.here, sheet.hereLine, onDismiss, rows = sheet.rows)
}

/**
 * Every workout, newest first, as one list under small day headers ("Today", "Yesterday", "Wed 21 Oct"), each a row
 * as on the Activity tab's Workouts card ([WorkoutRow]). One list rather than a panel a day, so no two cards in a row
 * look the same (spec 2.5); each day is still its own item, so a long history scrolls lazily.
 */
@Composable
fun GlowWorkoutsScreen(workouts: List<WorkoutAnalysis>, contentPadding: PaddingValues, onOpen: (Long) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val zone = ZoneId.systemDefault()
    val usual = remember(workouts) { Workouts.usualLoad(workouts.map { it.workout.trainingLoad }) }
    val days = remember(workouts) { workouts.groupBy { Instant.ofEpochSecond(it.workout.start).atZone(zone).toLocalDate() }.toList() }
    val today = LocalDate.now()
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.common_workouts), onBack, subtitle = tr(R.string.missed_n_recorded, workouts.size)) }
        if (workouts.isEmpty()) {
            item {
                ListPanel {
                    ListRow(tr(R.string.common_no_workouts_yet), MetricColors.heart, null, tr(R.string.glow_workout_screen_workouts_you_start_in), dash = false, divider = false)
                }
            }
            return@ScreenList
        }
        days.forEachIndexed { i, (date, day) ->
            item(key = date.toEpochDay()) {
                ListSegment(first = i == 0, last = i == days.lastIndex) {
                    WorkoutDayHeader(TabCopy.workoutDay(date, today))
                    day.forEachIndexed { j, a -> WorkoutRow(a, usual, divider = j > 0) { onOpen(a.workout.start) } }
                }
            }
        }
    }
}

/** The space [ScreenList] leaves between items, which a [ListSegment] fills to join the next. */
private val ListItemGap = 12.dp

/**
 * One stretch of a list split over several lazy items: the [ListPanel] surface and hairline, rounded at the top on
 * the [first] and at the bottom on the [last], and reaching down through the gap to the next stretch otherwise, so
 * the stretches read as one panel.
 */
@Composable
private fun ListSegment(first: Boolean, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .drawBehind {
                val r = GlowLayout.CardCorner.toPx()
                val stroke = 1.dp.toPx()
                val bottom = if (last) size.height else size.height + ListItemGap.toPx()
                // The rounded panel drawn past the ends that join another stretch, then cut back to this one's span.
                val top = if (first) 0f else -r
                val end = if (last) bottom else bottom + r
                clipRect(top = 0f, bottom = bottom) {
                    drawRoundRect(ListSurface, Offset(0f, top), Size(size.width, end - top), CornerRadius(r))
                    drawRoundRect(
                        RowHairline, Offset(stroke / 2, top + stroke / 2), Size(size.width - stroke, end - top - stroke),
                        CornerRadius(r - stroke / 2), style = Stroke(stroke),
                    )
                }
            }
            .padding(horizontal = GlowLayout.CardInset)
            .padding(top = if (first) GlowLayout.CardInset - GlowLayout.RowPadding else 0.dp, bottom = if (last) GlowLayout.CardInset - GlowLayout.RowPadding else 0.dp),
        content = content,
    )
}
