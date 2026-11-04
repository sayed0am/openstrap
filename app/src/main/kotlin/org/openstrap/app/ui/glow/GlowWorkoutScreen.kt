package org.openstrap.app.ui.glow

import org.openstrap.app.R
import org.openstrap.app.presentation.TimeOfDay
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.openstrap.app.presentation.Ranges
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
import org.openstrap.app.presentation.TabCopy
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.components.rememberScrubHaptics
import org.openstrap.app.ui.components.scrub
import org.openstrap.app.ui.dashboard.CaloriesCopy
import org.openstrap.app.presentation.Metric
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.EffectFigures
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.workout.ZoneSeconds
import org.openstrap.app.ui.workout.familyColor
import org.openstrap.app.ui.workout.WorkoutDetailCopy
import org.openstrap.app.ui.workout.WorkoutFact
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import org.openstrap.app.ui.glow.draw.EdgeKind
import org.openstrap.app.ui.glow.draw.Edge
import org.openstrap.app.ui.dashboard.LocalHeartZones

/*
 * The page of a past workout (spec 6.3, "Workout detail"), one for the strap's and the phone's: the sentence first on
 * a glow as big as the effort, the heart rate in its zones' colours with the drop after the end, the small facts and
 * the map, Load with time to recover and the heart rate drop, time in zones and the splits. Training effect and EPOC
 * sit behind the (i) on the Load card. A workout row grows into this page (spec 3.5).
 */

/** The heart rate line is drawn in ten-second steps: readable on a phone, and the card still says the time. */
private const val HeartStep = 10L

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
    // A workout recorded on the phone opens as its summary did, per kind (workouts spec 2.8), with a back arrow.
    if (a?.fromPhone == true && summary != null) {
        PastSummary(
            summary, workouts, contentPadding, onBack, onDelete, onTrim, onUndoTrim, dayActiveKcal, onOpenDay,
            foundTag = !a.startedByYou,
        )
        return
    }
    // A phone workout whose summary hasn't loaded yet shows only its header.
    val shown = a?.takeIf { !it.fromPhone }
    val list = rememberLazyListState()
    var info by rememberSaveable { mutableStateOf(false) }
    val usual = remember(workouts) { Workouts.usualLoad(workouts.map { it.workout.trainingLoad }) }
    val zones = shown?.zoneSeconds.orEmpty()
    CompositionLocalProvider(LocalGlowPaused provides { list.isScrollInProgress }) {
        ScreenList(contentPadding, list, gutter = GlowLayout.Gutter) {
            item {
                // The day and times under the name, as a phone workout's page has them.
                val day = shown?.let {
                    stringResource(
                        R.string.glow_workout_day_times, WorkoutDetailCopy.day(it.shownStart, LocalDate.now()),
                        Ranges.times(it.shownStart, it.end),
                    )
                }
                PageHeader(
                    (shown ?: a)?.sportName ?: stringResource(R.string.glow_workout_untitled), onBack, subtitle = day,
                    trailing = if ((shown ?: a)?.startedByYou == false) ({ OutlineTag(TabCopy.AUTO_DETECTED) }) else null,
                )
            }
            if (shown == null) return@ScreenList
            val w = shown.workout
            item {
                // The summary's hero with the length in dots: a strap workout has no kind of its own to show.
                val sentence = WorkoutDetailCopy.sentence(w.sportType, shown.activeSeconds, w.trainingLoad, usual, zones)
                val hero = SummaryHero(dotClock(shown.activeSeconds), "", sentence = sentence)
                SummaryHeroCard(hero, MetricColors.heart, Workouts.effort(w.trainingLoad, usual))
            }
            if (shown.heartRate.isNotEmpty() || w.averageHeartRate != null) item { HeartRateCard(shown) }
            val activeKcal = Workouts.activeKcal(shown, bmrPerMinute)
            val facts = WorkoutDetailCopy.facts(
                shown.distanceMeters, shown.distanceFromSteps,
                0L, w.steps, w.calories,
                activeKcal = activeKcal.takeIf { bmrPerMinute != null },
            ) + listOfNotNull(WorkoutDetailCopy.targetFact(shown.target, shown.targetReachedSeconds, shown.activeKcal?.roundToInt()))
            val factsShown = facts.isNotEmpty()
            if (factsShown) item { WorkoutFactsList(facts, null) }
            // The workout's share of its day, right under its kcal (calories spec 4.2).
            val today = LocalDate.now()
            val inDay = activeKcal?.let { k ->
                val date = Instant.ofEpochSecond(shown.shownStart).atZone(ZoneId.systemDefault()).toLocalDate()
                CaloriesCopy.inYourDay(WorkoutDetailCopy.sport(w.sportType), k, dayActiveKcal?.roundToInt(), date, today, false)
            }
            if (inDay != null) item { InYourDayCard(inDay, onOpenDay) }
            val drop = WorkoutDetailCopy.dropLine(shown.recovery60, shown.recoveryIsMeaningful, usualDrop)
            val loadShown = w.trainingLoad != null || (shown.recoveryMinutes ?: 0) > 0 || drop != null
            if (loadShown) item { WorkoutLoadCard(shown, usual, drop = drop, onInfo = { info = true }) }
            // A list right under another would look the same, so the later one sits on a neutral edge card (spec 2.4):
            // the zones when the facts are right above them, else the splits under a plain list.
            val zonesShown = zones.sum() > 0
            val factsRightAbove = factsShown && inDay == null && !loadShown
            val zonesOnEdge = zonesShown && factsRightAbove
            if (zonesShown) item {
                CompositionLocalProvider(LocalEdgeOverride provides if (zonesOnEdge) EdgeOverride(edge = Edge.Bottom) else EdgeOverride()) {
                    ZonesList(remember(zones) { ZoneSeconds(zones) }, shown.maxHeartRate)
                }
            }
            item { DeleteWorkout(stringResource(R.string.glow_workout_delete_strap), onDelete) }
        }
    }
    if (info && shown != null) EffectInfo(shown, usual, emptyList(), false, onDismiss = { info = false })
}

/**
 * A past phone workout (workouts spec 2.8, past): the summary's hero and the kind's own section, then everything this
 * page always had: the heart rate line with the drop after the end and Trim on it, the facts and the map, In your day,
 * Load with its (i), the zones and splits where the kind's section doesn't show them, and Delete. No Done and nothing
 * earned: that was said when it ended.
 */
@Composable
private fun PastSummary(
    summary: PhoneSummary,
    workouts: List<WorkoutAnalysis>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onTrim: (from: Long, to: Long) -> Unit,
    onUndoTrim: () -> Unit,
    dayActiveKcal: Float?,
    onOpenDay: (() -> Unit)?,
    foundTag: Boolean,
) {
    val heartZones = LocalHeartZones.current
    val a = summary.analysis
    val w = a.workout
    val list = rememberLazyListState()
    var info by rememberSaveable { mutableStateOf(false) }
    val others = remember(workouts, w.start) { workouts.filter { it.workout.start != w.start }.sortedByDescending { it.workout.start } }
    val usual = remember(others) { Workouts.usualLoad(others.map { it.workout.trainingLoad }) }
    val family = org.openstrap.app.ui.workout.FamilySummary.familyOf(w.sportType, summary.structure)
    val weekStarts = remember(workouts) { goalWeekStarts(workouts) }
    val trim = rememberWorkoutTrim(summary, onTrim, onUndoTrim)
    CompositionLocalProvider(LocalGlowPaused provides { list.isScrollInProgress }) {
        ScreenList(contentPadding, list, gutter = GlowLayout.Gutter) {
            item {
                PageHeader(
                    a.sportName, onBack,
                    subtitle = stringResource(
                        R.string.glow_workout_day_times, WorkoutDetailCopy.day(a.shownStart, LocalDate.now()),
                        Ranges.times(a.shownStart, a.end),
                    ),
                    trailing = if (foundTag) ({ OutlineTag(TabCopy.AUTO_DETECTED) }) else null,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val zones = remember(a, heartZones) {
                        val shown = a.heartRate.filter { it.timestamp <= a.end }.map { it.timestamp to it.bpm }
                        ZoneSeconds(WorkoutRecorder.liveZoneSeconds(shown, a.maxHeartRate, heartZones).toList())
                    }
                    // The page's sentence ("An easy 40 min walk") where the kind has none of its own.
                    val hero = summaryHero(family, summary, usual).let { h ->
                        if (h.sentence != null) h
                        else SummaryHero(h.value, h.unit, h.figures, WorkoutDetailCopy.sentence(w.sportType, a.activeSeconds, w.trainingLoad, usual, zones.toList()), h.line)
                    }
                    SummaryHeroCard(hero, familyColor(family), Workouts.effort(w.trainingLoad, usual))
                    val sectionOnEdge = FamilySection(family, summary, emptyList(), others, weekStarts, heartLine = false)
                    // The heart rate card is lit from the left; under the section's own edge card it takes the bottom.
                    // Always there, even before any heart rate, since it holds Trim.
                    val heartEdge = if (sectionOnEdge) EdgeOverride(edge = Edge.Bottom) else EdgeOverride()
                    CompositionLocalProvider(LocalEdgeOverride provides heartEdge) { HeartRateCard(a, trim) }
                    val facts = WorkoutDetailCopy.facts(
                        a.distanceMeters, a.distanceFromSteps, (a.durationSeconds - a.activeSeconds).coerceAtLeast(0), w.steps, w.calories,
                        activeKcal = Workouts.activeKcal(a, null),
                    ) + listOfNotNull(WorkoutDetailCopy.targetFact(a.target, a.targetReachedSeconds, a.activeKcal?.roundToInt()))
                    // The map is in the route card for a run; any other kind with a kept route has it here.
                    val map = family != Workouts.Family.Distance && Route.mappable(summary.route)
                    if (facts.isNotEmpty() || map) WorkoutFactsList(facts, summary.takeIf { map })
                    val inDay = Workouts.activeKcal(a, null)?.let { k ->
                        val date = Instant.ofEpochSecond(a.shownStart).atZone(ZoneId.systemDefault()).toLocalDate()
                        val sport = WorkoutDetailCopy.sport(w.sportType)
                        CaloriesCopy.inYourDay(sport, k, dayActiveKcal?.roundToInt(), date, LocalDate.now(), true)
                    }
                    if (inDay != null) InYourDayCard(inDay, onOpenDay)
                    val drop = WorkoutDetailCopy.dropLine(a.recovery60, a.recoveryIsMeaningful, null)
                    if (w.trainingLoad != null || (a.recoveryMinutes ?: 0) > 0 || drop != null) {
                        WorkoutLoadCard(a, usual, drop = drop, onInfo = { info = true })
                    }
                    // Zones and splits for the kinds whose section doesn't already show them.
                    val zonesShown = zones.sum() > 0 && family !in setOf(Workouts.Family.IndoorCardio, Workouts.Family.Games)
                    if (zonesShown) ZoneStripPanel(zones)
                    if (family != Workouts.Family.Distance && family != Workouts.Family.Strength) {
                        CompositionLocalProvider(LocalEdgeOverride provides if (zonesShown) EdgeOverride(edge = Edge.Bottom) else EdgeOverride()) {
                            SplitsList(summary, liveRoute = emptyList())
                        }
                    }
                    SummaryActions(justEnded = false, onDone = onBack, onDelete = onDelete)
                }
            }
        }
    }
    if (info) EffectInfo(a, usual, summary.strapWorkouts, summary.effectFromStrap, onDismiss = { info = false })
    TrimSheet(trim)
}

/**
 * Heart rate over the workout in its zones' colours, and the strap's minutes after the end, dimmer, as the drop. The
 * number is the average; hold the line to scrub and it follows the finger (spec 2.7), with the scrub ticks. Lit from
 * the left: about earlier. A phone workout's [trim] puts Trim in the title row and what was trimmed, with Undo, under
 * the chart, there even before any heart rate has arrived.
 */
@Composable
private fun HeartRateCard(a: WorkoutAnalysis, trim: WorkoutTrim? = null) {
    val w = a.workout
    val first = a.shownStart
    val last = (a.heartRate.maxOfOrNull { it.timestamp } ?: a.end).coerceIn(a.end, a.end + Workouts.RECOVERY_TAIL_S)
    val values = remember(a) {
        val n = ((last - first) / HeartStep + 1).toInt().coerceAtLeast(2)
        org.openstrap.app.data.Summaries.bucketMeans(a.heartRate, n, { ((it.timestamp - first) / HeartStep).toInt() }, { it.bpm.toFloat() })
    }
    var scrubbed by remember { mutableStateOf<Int?>(null) }
    val at = scrubbed?.let { i -> values.getOrNull(i)?.let { v -> first + i * HeartStep to v.roundToInt() } }
    EdgeCard(MetricColors.heart, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        val byZone = if (a.heartRate.isNotEmpty()) stringResource(R.string.glow_workout_heart_by_zone) else null
        EdgeTitle(
            stringResource(R.string.glow_summary_heart_rate), byZone,
            action = trim?.let { { TrimButton(it.open) } },
        )
        if (at != null) {
            EdgeValue(
                at.second.toString(), stringResource(R.string.glow_unit_bpm),
                stringResource(
                    if (at.first > a.end) R.string.glow_workout_heart_at_after else R.string.glow_workout_heart_at, clock(at.first),
                ),
            )
        } else {
            val max = w.maxHeartRate
            val min = w.minHeartRate
            val range = when {
                max != null && min != null -> stringResource(R.string.glow_workout_heart_max_lowest, max, min)
                max != null -> stringResource(R.string.glow_workout_heart_max, max)
                min != null -> stringResource(R.string.glow_workout_heart_lowest, min)
                else -> null
            }
            EdgeValue(w.averageHeartRate?.toString(), stringResource(R.string.glow_workout_unit_bpm_average), range, waiting = false)
        }
        if (a.heartRate.isEmpty()) Text(
            stringResource(R.string.glow_workout_heart_none), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
            style = GlowType.secondary, modifier = Modifier.padding(top = 10.dp),
        ) else {
            ZoneHeartLine(values, first, a.end, a.maxHeartRate, a.historyRanges, scrubbed, { scrubbed = it }, Modifier.padding(top = 16.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                PageNote(clock(first))
                Spacer(Modifier.weight(1f))
                val after = stringResource(R.string.glow_workout_heart_drop_after, clock(a.end))
                PageNote(if (last > a.end + HeartStep) after else clock(a.end))
            }
            if (a.historyRanges.isNotEmpty()) Text(
                stringResource(
                    R.string.glow_workout_heart_dimmed, a.historyRanges.joinToString(", ") { Ranges.times(it.first, it.last + 1) },
                ),
                color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        trim?.let { TrimNote(it.summary, it.undo, Modifier.padding(top = 12.dp)) }
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
    val heartZones = LocalHeartZones.current
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
        drawZoneLine(values.size, 2.5.dp.toPx(), at = { i -> values[i]?.let { Offset(i * dx, y(it)) } }) { i ->
            val v = values[i] ?: return@drawZoneLine null
            val t = first + i * HeartStep
            val color = if (i >= endIndex) Color.White.copy(alpha = 0.55f) else zoneColor(v.roundToInt(), maxHr, heartZones)
            // The strap's history, filled in after: the same line, dimmed (spec 2.7, no dashes).
            if (history.any { t in it }) color.copy(alpha = color.alpha * 0.45f) else color
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
        EdgeTitle(stringResource(R.string.glow_workout_in_your_day), null)
        Text(text, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The small facts ([WorkoutDetailCopy.facts]) as rows, and the map under them when the route was kept. */
@Composable
private fun WorkoutFactsList(facts: List<WorkoutFact>, summary: PhoneSummary?) {
    ListPanel(title = stringResource(R.string.glow_workout_facts_title)) {
        facts.forEachIndexed { i, f ->
            val color = when (f.kind) {
                WorkoutFact.Kind.Paused -> PausedColor
                WorkoutFact.Kind.Calories, WorkoutFact.Kind.Target -> MetricColors.calories
                else -> MetricColors.steps
            }
            val icon = when (f.kind) {
                WorkoutFact.Kind.Distance -> Score.Distance.rowIcon()
                WorkoutFact.Kind.Paused -> AppIcons.Hourglass
                WorkoutFact.Kind.Steps -> Metric.Steps.rowIcon()
                WorkoutFact.Kind.Calories -> Score.Calories.rowIcon()
                WorkoutFact.Kind.Target -> AppIcons.Target
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
                from = TimeOfDay.format(s.workout.start, ZoneId.systemDefault()),
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
        item {
            PageHeader(
                stringResource(R.string.glow_workout_list_title), onBack,
                subtitle = pluralStringResource(R.plurals.glow_workout_list_recorded, workouts.size, workouts.size),
            )
        }
        if (workouts.isEmpty()) {
            item {
                ListPanel {
                    ListRow(
                        stringResource(R.string.glow_workout_list_empty), MetricColors.heart, null,
                        stringResource(R.string.glow_workout_list_empty_text),
                        dash = false, divider = false,
                    )
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
