package org.openstrap.app.ui.glow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import org.openstrap.app.ui.dashboard.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.CalmDown
import org.openstrap.app.device.WorkoutSession
import org.openstrap.app.ui.components.HapticEvent
import org.openstrap.app.ui.components.rememberAnimationsOn
import org.openstrap.app.ui.components.rememberHaptics
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.BestCandidate
import org.openstrap.app.ui.workout.CalmDownLine
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.workout.SummaryCopy
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.workout.ZONE_NAMES
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * The summary right after a workout (spec 6.3, "Right after"): it leads with the calm down, the heart rate drop drawing
 * as it happens on a toned-down glow, then the workout, its Load against your usual, time in zones and the splits. A
 * personal best is one line on the Load card and a short swell of the glow, no modal. Past workouts open their own
 * page ([GlowWorkoutDetailScreen]), which shares the Load card and the lists.
 */

/** How much higher the hero's light reaches at the top of a personal best's swell. */
private const val SwellLevel = 0.25f

/** The drop line shows the last minute of the workout before the end. */
private const val BeforeS = 60L

/**
 * The summary right after [summary]'s workout ended. [calmDown] is the heart rate the strap sent since, for this
 * workout or another (then ignored), null when nothing is live (a debug preview). [liveRoute] is the route still in
 * memory, so a GPS workout whose route wasn't kept still gets its splits. [workouts] are all of them, for your usual
 * Load and a personal best; [usualDrop] your usual heart rate drop.
 */
@Composable
fun GlowWorkoutSummaryScreen(
    summary: PhoneSummary?,
    calmDown: CalmDown?,
    liveRoute: List<Route.Point>,
    workouts: List<WorkoutAnalysis>,
    usualDrop: Int?,
    contentPadding: PaddingValues,
    onDone: () -> Unit,
    onDelete: () -> Unit,
    onTrim: (from: Long, to: Long) -> Unit,
    onUndoTrim: () -> Unit,
) {
    BackHandler(onBack = onDone)
    val a = summary?.analysis
    var info by rememberSaveable { mutableStateOf(false) }
    // Done stays on the bottom edge, Delete as quiet text under it; nothing to delete until the workout has loaded.
    WorkoutPage(
        Color.Transparent, contentPadding,
        pinned = { if (a != null) SummaryActions(justEnded = true, onDone = onDone, onDelete = onDelete) else WorkoutButton(tr(R.string.common_done), Color.White, Color.Black, onDone) },
    ) {
        // The page title, "Run saved": the sport's short name (spec 6.3).
        Text(
            SummaryCopy.savedTitle(a?.workout?.sportType), color = Color.White, style = GlowType.pageTitle, maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
        )
        if (summary == null || a == null) {
            Note(tr(R.string.missed_loading))
            return@WorkoutPage
        }
        val w = a.workout
        val others = remember(workouts, w.start) { workouts.filter { it.workout.start != w.start }.sortedByDescending { it.workout.start } }
        val usualLoad = remember(others) { Workouts.usualLoad(others.map { it.workout.trainingLoad }) }
        val best = remember(a, others) {
            fun of(x: WorkoutAnalysis) = BestCandidate(
                x.workout.start, x.workout.sportType, x.activeSeconds,
                x.distanceMeters?.takeIf { !x.distanceFromSteps },
            )
            SummaryCopy.personalBest(of(a), others.map(::of))
        }
        // A personal best ticks once and swells the hero's glow once; with animations off, only the tick.
        val haptics = rememberHaptics()
        val animationsOn = rememberAnimationsOn()
        val swell = remember { Animatable(0f) }
        var swelled by rememberSaveable(w.start) { mutableStateOf(false) }
        LaunchedEffect(best) {
            if (best == null || swelled) return@LaunchedEffect
            swelled = true
            haptics.perform(HapticEvent.PersonalBest)
            if (animationsOn) {
                swell.animateTo(1f, tween(450))
                swell.animateTo(0f, tween(900))
            }
        }
        CalmDownHero(summary, calmDown?.takeIf { it.start == w.start }, usualDrop, Workouts.effort(w.trainingLoad, usualLoad), swell.value) { info = true }
        WorkoutCard(summary)
        LoadCard(a, usualLoad, best)
        val zones = remember(a) { WorkoutRecorder.liveZoneSeconds(a.heartRate.filter { it.timestamp <= a.end }.map { it.timestamp to it.bpm }, a.maxHeartRate) }
        if (zones.sum() > 0) ZonesList(zones, a.maxHeartRate)
        // Right under the zones it would look the same, so it sits on a neutral edge card instead (spec 2.4).
        CompositionLocalProvider(LocalEdgeOverride provides if (zones.sum() > 0) EdgeOverride(edge = Edge.Bottom) else EdgeOverride()) {
            SplitsList(summary, liveRoute)
        }
        TrimSection(summary, onTrim, onUndoTrim)
    }
    if (info) InfoSheet(
        Info(tr(R.string.common_heart_rate_drop), SummaryCopy.DROP_MEANING, emptyList(), "", "", ""), MetricColors.heart, null, null,
        onDismiss = { info = false },
    )
}

/**
 * The calm down, the hero right after a workout (spec 6.3): a level glow in heart red lit from below as high as the
 * workout's [effort] (spec 2.1, Levels), "Heart rate drop" with its (i) ([onInfo]), what the drop says
 * ([SummaryCopy.calmDown]), the heart rate now in dots while it's live, and the line of the last minute and the two
 * after the end, growing as readings arrive. [swell] lifts the light for a personal best, then lets it settle.
 */
@Composable
private fun CalmDownHero(summary: PhoneSummary, live: CalmDown?, usualDrop: Int?, effort: Float, swell: Float, onInfo: () -> Unit) {
    val a = summary.analysis
    val now by produceState(System.currentTimeMillis() / 1000, live?.until) {
        // Ticks only while the calm down is still live, for the line and the minute.
        while (live != null && value <= live.until) {
            delay(1000 - System.currentTimeMillis() % 1000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val readings = remember(summary, live?.readings) {
        (summary.recorded.map { it.timestamp to it.bpm } + live?.readings.orEmpty()).distinctBy { it.first }.sortedBy { it.first }
    }
    val line: CalmDownLine = SummaryCopy.calmDown(readings, a.end, now, live != null && now <= live.until, a.maxHeartRate, usualDrop)
    val bpmNow = live?.readings?.lastOrNull()?.takeIf { now <= live.until && now - it.first <= 5 }?.second
    // A level glow is always at full: its light is the value (spec 2.1).
    val style = GlowStyle.chart(MetricColors.heart).copy(
        lightAngle = LightAngle.Below, lightLevel = (effort + SwellLevel * swell).coerceAtMost(1f),
    )
    GlowCard(style, Modifier.fillMaxWidth()) {
        Column {
            GlowHeader(
                tr(R.string.common_heart_rate_drop), trailing = tr(R.string.glow_workout_summary_since, clockAt(a.end)),
                trailingContent = { Spacer(Modifier.width(10.dp)); RoundButton(AppIcons.Info, tr(R.string.glow_workout_summary_about_the_heart_rate), onInfo, size = 32.dp) },
            )
            HeroSentence(line.text)
            if (bpmNow != null) Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Bottom) {
                DotMatrixText(bpmNow.toString(), dot = DotSize.Hero)
                // The word after a number (spec 2.3).
                Text(tr(R.string.common_bpm_now), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.padding(start = 8.dp))
            }
            val shown = readings.filter { it.first in (a.end - BeforeS)..(a.end + WorkoutSession.CALM_S) }
            if (shown.size >= 2) DropLine(shown, a.end, Modifier.padding(top = 16.dp))
        }
    }
}

/**
 * The heart rate from a minute before the [end] to two after: the workout's last minute dim, the drop bright, a solid
 * hairline mark at the end. Gaps of more than five seconds break the line.
 */
@Composable
private fun DropLine(readings: List<Pair<Long, Int>>, end: Long, modifier: Modifier = Modifier) {
    val first = end - BeforeS
    val span = (BeforeS + WorkoutSession.CALM_S).toFloat()
    val lo = readings.minOf { it.second } - 5f
    val hi = readings.maxOf { it.second } + 5f
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(84.dp)) {
            fun x(t: Long) = (t - first) / span * size.width
            fun y(v: Int) = size.height - (v - lo) / (hi - lo) * size.height
            val endX = x(end)
            drawLine(Color.White.copy(alpha = 0.35f), Offset(endX, 0f), Offset(endX, size.height), strokeWidth = 1.dp.toPx())
            fun stroke(part: List<Pair<Long, Int>>, color: Color) {
                if (part.size < 2) return
                val path = Path()
                part.forEachIndexed { i, (t, v) ->
                    if (i == 0 || t - part[i - 1].first > 5) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
                }
                drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
            stroke(readings.filter { it.first <= end }, Color.White.copy(alpha = 0.45f))
            stroke(readings.filter { it.first >= end }, Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { Note(tr(R.string.glow_workout_summary_last_minute)) }
            Box(Modifier.weight(1f)) { Note(tr(R.string.common_end)) }
            Box(Modifier.weight(1f)) {
                Note(tr(R.string.glow_workout_summary_min))
                Box(Modifier.align(Alignment.CenterEnd)) { Note(tr(R.string.glow_workout_summary_min_2)) }
            }
        }
    }
}

/** The workout: active time, distance and the average, its calories and kcal target, and the map when the route was kept. Lit from the left, about earlier. */
@Composable
private fun WorkoutCard(summary: PhoneSummary) {
    val a = summary.analysis
    val w = a.workout
    val active = a.activeSeconds
    EdgeCard(MetricColors.heart, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        EdgeTitle(a.sportName, "${clockAt(a.shownStart)}–${clockAt(a.end)}")
        EdgeValue(
            workoutClock(active), null,
            SummaryCopy.workoutLine(summary.distanceMeters, summary.distanceFromSteps, w.averageHeartRate, (a.durationSeconds - active).coerceAtLeast(0)),
        )
        // Its calories, active first, under the line (calories spec 4.2).
        SummaryCopy.kcalLine(a.activeKcal?.let(Math::round), w.calories)?.let {
            Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 4.dp))
        }
        // A kcal target says whether it was reached, and when (calories spec 4.2).
        SummaryCopy.targetLine(a.target, a.targetReachedSeconds, a.activeKcal?.let(Math::round))?.let {
            Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 4.dp))
        }
        RouteMap(summary, Modifier.padding(top = 14.dp))
    }
}

/** The kept route in its zones' colours; nothing without a route. */
@Composable
internal fun RouteMap(summary: PhoneSummary, modifier: Modifier = Modifier) {
    if (summary.route.size < 2) return
    val stretches = remember(summary) { Route.byZone(summary.route, summary.history, summary.analysis.maxHeartRate) }
    Box(modifier.fillMaxWidth().height(170.dp).clip(GlowLayout.TileShape).background(Raised)) {
        WorkoutMap(stretches, routeOnly = false, Modifier.fillMaxSize())
    }
}

/**
 * Load from this workout against your [usual] workout Load: lit from the top when above it, stronger from the bottom
 * when below (spec 2.4). Time to recover under it, then the heart rate [drop] line when there is one, and a personal
 * [best] as its last line. [onInfo] puts an (i) beside the title, for training effect and EPOC.
 */
@Composable
internal fun LoadCard(a: WorkoutAnalysis, usual: Float?, best: String?, drop: String? = null, onInfo: (() -> Unit)? = null) {
    val load = a.workout.trainingLoad
    val status = load?.let { TodayCopy.loadStatus(it.toFloat(), usual, endOfDay = true) }
    val edge = when (status) {
        TodayCopy.LoadStatus.Above -> EdgeStatus.Above
        TodayCopy.LoadStatus.Below -> EdgeStatus.Below
        else -> EdgeStatus.Usual
    }
    EdgeCard(MetricColors.load, Modifier.fillMaxWidth(), status = edge) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { EdgeTitle(tr(R.string.common_load), SummaryCopy.loadTitle(a.workout.sportType)) }
            if (onInfo != null) {
                Spacer(Modifier.width(10.dp))
                RoundButton(AppIcons.Info, tr(R.string.glow_workout_summary_about_load_and_training), onInfo, size = 30.dp)
            }
        }
        if (load != null) EdgeValue(
            load.toString(), null, SummaryCopy.loadLine(load, usual),
            if (edge != EdgeStatus.Usual) MetricColors.load else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
        ) else EdgeValue(null, null, tr(R.string.glow_workout_summary_not_enough_heart_rate), waiting = false)
        SummaryCopy.timeToRecover(a.recoveryMinutes, a.ownRecoveryMinutes, a.workout.sportType)?.let {
            Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 4.dp))
        }
        drop?.let { Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 4.dp)) }
        best?.let { Text(it, color = Color.White, style = GlowType.secondary, modifier = Modifier.padding(top = 12.dp)) }
    }
}

/** Time in zones: the zone bar, then each zone with time in it, hardest first. */
@Composable
internal fun ZonesList(zones: IntArray, maxHr: Int) {
    ListPanel(title = tr(R.string.glow_workout_summary_time_in_zones), note = tr(R.string.glow_workout_summary_max_bpm, maxHr)) {
        ZoneBar(zones.toList(), ZONE_COLORS, Modifier.padding(top = 2.dp, bottom = 6.dp))
        var first = true
        for (z in Workouts.ZONES - 1 downTo 0) {
            if (zones[z] <= 0) continue
            // The heart in its zone's colour, so the rows key the bar above.
            ListRow(
                tr(R.string.glow_workout_summary_zone, z + 1), ZONE_COLORS[z], workoutClock(zones[z].toLong()), "${ZONE_NAMES[z]}, ${zoneRange(z, maxHr)}", divider = !first,
                icon = AppIcons.Heart,
            )
            first = false
        }
    }
}

/**
 * Splits for a GPS workout (each kilometre's time and average heart rate), with the laps under them when any were
 * marked; laps alone without GPS; sets for strength. Nothing without any of them.
 */
@Composable
internal fun SplitsList(summary: PhoneSummary, liveRoute: List<Route.Point>) {
    val a = summary.analysis
    val maxHr = a.maxHeartRate
    val route = summary.route.ifEmpty { liveRoute.filter { it.t in a.shownStart..a.end } }
    val rows = remember(route, summary) { SummaryCopy.splitRows(
        Route.splits(route, summary.pauses), summary.history, route.firstOrNull()?.t, summary.distanceMeters?.takeIf { !summary.distanceFromSteps },
    ) }
    val laps = summary.laps.takeIf { it.size > 1 }.orEmpty()
    val sets = summary.sets.filter { it.end != null }
    // Each row's heart takes the zone its average heart rate sat in.
    fun dot(bpm: Int?) = bpm?.let { zoneColor(it, maxHr) } ?: EdgeNeutral
    when {
        rows.isNotEmpty() || laps.isNotEmpty() -> ListPanel(
            title = if (rows.isNotEmpty()) tr(R.string.glow_workout_summary_splits) else tr(R.string.glow_workout_summary_laps),
            note = if (rows.isNotEmpty()) summary.distanceMeters?.let { km(it) + if (it >= 1000) " km" else "" } else "${laps.size}",
        ) {
            rows.forEachIndexed { i, r -> ListRow(r.name, dot(r.averageBpm), r.time, r.averageBpm?.let { tr(R.string.common_average_bpm, it) }, divider = i > 0, icon = AppIcons.Heart) }
            if (rows.isNotEmpty() && laps.isNotEmpty()) WorkoutDayHeader(tr(R.string.glow_workout_summary_laps))
            laps.forEachIndexed { i, lap ->
                ListRow(
                    tr(R.string.glow_workout_summary_lap, lap.index + 1), dot(lap.averageHeartRate), workoutClock(lap.end - lap.start), SummaryCopy.lapLine(lap.meters, lap.averageHeartRate),
                    divider = i > 0, icon = AppIcons.Heart,
                )
            }
        }
        sets.isNotEmpty() -> ListPanel(title = tr(R.string.common_sets), note = tr(R.string.missed_n_done, sets.size)) {
            sets.forEachIndexed { i, set ->
                ListRow(
                    tr(R.string.glow_workout_summary_set, set.index + 1), MetricColors.heart, workoutClock(set.end!! - set.start),
                    set.restEnd?.let { tr(R.string.glow_workout_summary_rest, workoutClock(it - set.end)) }, divider = i > 0, icon = Score.Load.rowIcon(),
                )
            }
        }
    }
}
