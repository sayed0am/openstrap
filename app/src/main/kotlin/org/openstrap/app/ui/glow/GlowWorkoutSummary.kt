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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import org.openstrap.app.ui.workout.SplitRow
import org.openstrap.app.ui.workout.familyColor
import org.openstrap.app.ui.components.pressClickable
import kotlinx.coroutines.delay
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutAnalysis
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
import org.openstrap.app.ui.workout.CalmDownLine
import org.openstrap.app.presentation.EarnedLine
import org.openstrap.app.presentation.SummaryRewards
import org.openstrap.app.ui.workout.FamilySummary
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.workout.SummaryCopy
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.workout.ZONE_NAMES

/*
 * The summary right after a workout (workouts spec 2.8): the sport and when, a hero with the one number that matters for
 * that kind of workout and three figures, then its own section ([FamilySection]), then what it earned. Under them the
 * heart rate drop, drawing as it happens, and Load against your usual. A personal best ticks and swells the hero's
 * glow once, no modal. Past workouts open their own page ([GlowWorkoutDetailScreen]), which shares the Load card and
 * the lists.
 */

/** Where Done has got to: the summary, a personal best's card, the goal moment. */
private enum class SummaryStep { Summary, Best, Moment }

/** How much higher the hero's light reaches at the top of a personal best's swell. */
private const val SwellLevel = 0.25f

/** The drop line shows the last minute of the workout before the end. */
private const val BeforeS = 60L

/**
 * The summary right after [summary]'s workout ended. [calmDown] is the heart rate the strap sent since, for this
 * workout or another (then ignored), null when nothing is live (a debug preview). [liveRoute] is the route still in
 * memory, so a GPS workout whose route wasn't kept still gets its splits. [workouts] are all of them, for your usual
 * Load, the last time and this week; [usualDrop] your usual heart rate drop.
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
    /** What it earned ([RewardsCopy.summary]), null while it's worked out. */
    rewards: SummaryRewards? = null,
    celebrations: Celebrations? = null,
) {
    val a = summary?.analysis
    // Done goes through the celebrations it earned (workouts spec 3.4): a personal best's card, then the goal moment
    // once a week, then away.
    var step by rememberSaveable { mutableStateOf(SummaryStep.Summary) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val moment = rewards != null && celebrations != null && rewards.goalMet && celebrations.goalMoment && celebrations.momentWeek != rewards.weekStart
    fun next(from: SummaryStep) {
        step = when {
            from < SummaryStep.Best && rewards?.best != null -> SummaryStep.Best
            from < SummaryStep.Moment && moment -> SummaryStep.Moment.also { celebrations.markMoment(rewards.weekStart) }
            else -> { onDone(); return }
        }
    }
    val done = { next(step) }
    if (step == SummaryStep.Best && rewards?.best != null && a != null) {
        BestCardScreen(rewards.best, a.sportName, contentPadding, onContinue = done)
        return
    }
    if (step == SummaryStep.Moment && rewards != null) {
        val starts = remember(workouts) { workouts.filter { it.startedByYou }.map { it.shownStart } }
        GoalMomentScreen(rewards.week.count, rewards.week.goal, rewards.inARow, starts, contentPadding, onSettings = { settings = true }, onContinue = done)
        if (settings && celebrations != null) CelebrationsSheet(
            celebrations.goalMoment, celebrations.weeklyRecap, celebrations.setGoalMoment, celebrations.setWeeklyRecap, onDismiss = { settings = false },
        )
        return
    }
    BackHandler(onBack = done)
    var info by rememberSaveable { mutableStateOf(false) }
    val hero = remember(summary, workouts) {
        if (summary == null || a == null) null
        else summaryHero(FamilySummary.familyOf(a.workout.sportType, summary.structure), summary, Workouts.usualLoad(workouts.filter { it.workout.start != a.workout.start }.map { it.workout.trainingLoad }))
    }
    // Share opens the share screen (workouts spec 2.9) in the summary's place; close comes back here.
    var sharing by rememberSaveable { mutableStateOf(false) }
    if (sharing && summary != null && hero != null) {
        WorkoutShareScreen(summary, hero, rewards?.earned.orEmpty(), liveRoute, workouts, contentPadding, onClose = { sharing = false })
        return
    }
    // Share and Done stay on the bottom edge; Delete waits at the end of the page. Nothing to share until the workout
    // has loaded.
    WorkoutPage(
        Color.Transparent, contentPadding,
        pinned = {
            if (a != null && hero != null) SummaryButtons(onShare = { sharing = true }, onDone = done)
            else WorkoutButton("Done", Color.White, Color.Black, onDone)
        },
    ) {
        // The sport, then when, with Done beside them (board SummaryRun).
        Row(Modifier.padding(start = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a?.sportName ?: "Workout", color = Color.White, style = GlowType.pageTitle, maxLines = 1)
                if (a != null) Text(
                    FamilySummary.whenLine(a.shownStart, System.currentTimeMillis() / 1000), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.secondary, modifier = Modifier.padding(top = 2.dp),
                )
            }
            TopDone(done)
        }
        if (summary == null || a == null) {
            Note("Loading…")
            return@WorkoutPage
        }
        val w = a.workout
        val others = remember(workouts, w.start) { workouts.filter { it.workout.start != w.start }.sortedByDescending { it.workout.start } }
        val usualLoad = remember(others) { Workouts.usualLoad(others.map { it.workout.trainingLoad }) }
        val earned = rewards?.earned.orEmpty()
        // A personal best ticks once and swells the hero's glow once; with animations off, only the tick.
        val haptics = rememberHaptics()
        val animationsOn = rememberAnimationsOn()
        val swell = remember { Animatable(0f) }
        var swelled by rememberSaveable(w.start) { mutableStateOf(false) }
        val anyBest = earned.any { it.kind == EarnedLine.Kind.Best }
        LaunchedEffect(anyBest) {
            if (!anyBest || swelled) return@LaunchedEffect
            swelled = true
            haptics.perform(HapticEvent.PersonalBest)
            if (animationsOn) {
                swell.animateTo(1f, tween(450))
                swell.animateTo(0f, tween(900))
            }
        }
        val family = FamilySummary.familyOf(w.sportType, summary.structure)
        val effort = Workouts.effort(w.trainingLoad, usualLoad)
        SummaryHeroCard(hero ?: summaryHero(family, summary, usualLoad), familyColor(family), effort) { swell.value }
        val weekStarts = remember(workouts) { workouts.filter { it.startedByYou }.map { it.shownStart } }
        val sectionOnEdge = FamilySection(family, summary, liveRoute, others, weekStarts, week = rewards?.week)
        EarnedChips(earned)
        // The drop only with heart rate to show: recorded in the workout, or arriving live after it.
        val live = calmDown?.takeIf { it.start == w.start }
        val dropShown = summary.recorded.isNotEmpty() || !live?.readings.isNullOrEmpty()
        if (dropShown) CalmDownHero(summary, live, usualDrop, effort, 0f) { info = true }
        // Load is lit from the left: what comes before it (the hero, the drop) is lit from below, so two cards in a row
        // never look the same. Under the section's own edge card, lit from the left or right, Load takes the bottom.
        CompositionLocalProvider(LocalEdgeOverride provides EdgeOverride(edge = if (sectionOnEdge && !dropShown) Edge.Bottom else Edge.Left)) {
            LoadCard(a, usualLoad, best = null)
        }
        TrimSection(summary, onTrim, onUndoTrim)
        DeleteWorkout(PHONE_DELETE, onDelete)
    }
    if (info) InfoSheet(
        Info("Heart rate drop", SummaryCopy.DROP_MEANING, emptyList(), "", "", ""), MetricColors.heart, null, null,
        onDismiss = { info = false },
    )
}

/**
 * The summary's hero (workouts spec 2.8): a glow in the workout's [color] lit from below as high as its [effort], the
 * one number in dots, then a sentence or three figures in text. [swell] lifts the light for a personal best.
 */
@Composable
internal fun SummaryHeroCard(hero: SummaryHero, color: Color, effort: Float, swell: () -> Float = { 0f }) {
    // The swell is drawn over the card's own light ([GlowSwell]), so the light at rest stays the cache key and a best's
    // swell neither composes the hero again nor renders a new glow each frame.
    val style = GlowStyle.chart(color).copy(lightAngle = LightAngle.Below, lightLevel = effort.coerceAtMost(1f))
    val lift = remember(effort, swell) { GlowSwell((effort + SwellLevel).coerceAtMost(1f), swell) }
    GlowCard(style, Modifier.fillMaxWidth(), swell = lift) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                DotMatrixText(hero.value, dot = DotSize.Hero)
                // The word after a number (spec 2.3).
                Text(hero.unit, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.padding(start = 8.dp))
            }
            hero.sentence?.let { HeroSentence(it) }
            hero.line?.let { Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 6.dp)) }
            if (hero.figures.isNotEmpty()) SummaryFigures(hero.figures, Modifier.padding(top = 18.dp))
        }
    }
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
                "Heart rate drop", trailing = "Since ${clockAt(a.end)}",
                trailingContent = { Spacer(Modifier.width(10.dp)); RoundButton(AppIcons.Info, "About the heart rate drop", onInfo, size = 32.dp) },
            )
            HeroSentence(line.text)
            if (bpmNow != null) Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Bottom) {
                DotMatrixText(bpmNow.toString(), dot = DotSize.Hero)
                // The word after a number (spec 2.3).
                Text("bpm now", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.padding(start = 8.dp))
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
            Box(Modifier.weight(1f)) { Note("Last minute") }
            Box(Modifier.weight(1f)) { Note("End") }
            Box(Modifier.weight(1f)) {
                Note("1 min")
                Box(Modifier.align(Alignment.CenterEnd)) { Note("2 min") }
            }
        }
    }
}

/** The kept route in its zones' colours; nothing without a route that goes somewhere. */
@Composable
internal fun RouteMap(summary: PhoneSummary, modifier: Modifier = Modifier) {
    if (!Route.mappable(summary.route)) return
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
            Box(Modifier.weight(1f)) { EdgeTitle("Load", SummaryCopy.loadTitle(a.workout.sportType)) }
            if (onInfo != null) {
                Spacer(Modifier.width(10.dp))
                RoundButton(AppIcons.Info, "About Load and training effect", onInfo, size = 30.dp)
            }
        }
        if (load != null) EdgeValue(
            load.toString(), null, SummaryCopy.loadLine(load, usual),
            if (edge != EdgeStatus.Usual) MetricColors.load else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
        ) else EdgeValue(null, null, "Not enough heart rate to work out Load.", waiting = false)
        SummaryCopy.timeToRecover(a.recoveryMinutes, a.ownRecoveryMinutes, a.workout.sportType)?.let {
            Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 4.dp))
        }
        drop?.let { Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 4.dp)) }
        best?.let { Text(it, color = Color.White, style = GlowType.secondary, modifier = Modifier.padding(top = 12.dp)) }
    }
}

/** Time in zones: the zone bar, then each zone with time in it, hardest first. */
@Composable
internal fun ZonesList(zones: IntArray, maxHr: Int, note: String = "Max $maxHr bpm") {
    ListPanel(title = "Time in zones", note = note) {
        ZoneBar(zones.toList(), ZONE_COLORS, Modifier.padding(top = 2.dp, bottom = 6.dp))
        var first = true
        for (z in Workouts.ZONES - 1 downTo 0) {
            if (zones[z] <= 0) continue
            // The heart in its zone's colour, so the rows key the bar above.
            ListRow(
                "Zone ${z + 1}", ZONE_COLORS[z], workoutClock(zones[z].toLong()), "${ZONE_NAMES[z]}, ${zoneRange(z, maxHr)}", divider = !first,
                icon = AppIcons.Heart,
            )
            first = false
        }
    }
}

/**
 * Splits for a GPS workout (each kilometre's time and average heart rate), with the laps under them when any were
 * marked; laps alone without GPS; sets for strength. Nothing without any of them. With [markFastest] the fastest
 * kilometre is named in the note and its row lit (workouts spec 2.8). Returns whether it showed anything.
 */
@Composable
internal fun SplitsList(summary: PhoneSummary, liveRoute: List<Route.Point>, markFastest: Boolean = false): Boolean {
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
    val fastest = if (markFastest) FamilySummary.fastestSplit(rows) else null
    when {
        rows.isNotEmpty() || laps.isNotEmpty() -> ListPanel(
            title = if (rows.isNotEmpty()) "Splits" else "Laps",
            note = if (rows.isNotEmpty()) fastest?.let { "Fastest: ${rows[it].name.lowercase()}" } ?: summary.distanceMeters?.let { km(it) + if (it >= 1000) " km" else "" } else "${laps.size}",
        ) {
            if (markFastest) SplitBars(rows, fastest) else rows.forEachIndexed { i, r ->
                ListRow(
                    r.name, dot(r.averageBpm), r.time, if (i == fastest) "Fastest" + (r.averageBpm?.let { ", average $it bpm" } ?: "") else r.averageBpm?.let { "Average $it bpm" },
                    divider = i > 0, icon = AppIcons.Heart, offNormal = i == fastest,
                )
            }
            if (rows.isNotEmpty() && laps.isNotEmpty()) WorkoutDayHeader("Laps")
            laps.forEachIndexed { i, lap ->
                ListRow(
                    "Lap ${lap.index + 1}", dot(lap.averageHeartRate), workoutClock(lap.end - lap.start), SummaryCopy.lapLine(lap.meters, lap.averageHeartRate),
                    divider = i > 0, icon = AppIcons.Heart,
                )
            }
        }
        sets.isNotEmpty() -> ListPanel(title = "Sets", note = "${sets.size} done") {
            sets.forEachIndexed { i, set ->
                ListRow(
                    "Set ${set.index + 1}", MetricColors.heart, workoutClock(set.end!! - set.start),
                    set.restEnd?.let { "Rest ${workoutClock(it - set.end)}" }, divider = i > 0, icon = Score.Load.rowIcon(),
                )
            }
        }
        else -> return false
    }
    return true
}

/**
 * Each kilometre as a bar as long as its speed against the [fastest] one's (board SummaryRun): the distance's lime,
 * the fastest white and its row bold. A part kilometre at the end has its time and no bar.
 */
@Composable
private fun SplitBars(rows: List<SplitRow>, fastest: Int?) {
    val seconds = rows.map { it.seconds }
    val best = rows.indices.filter { rows[it].kind == SplitRow.Kind.Whole && seconds[it] > 0 }.minOfOrNull { seconds[it] }
    val bar = familyColor(Workouts.Family.Distance).copy(alpha = 0.5f)
    rows.forEachIndexed { i, r ->
        val lit = i == fastest
        val text = if (lit) Color.White else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA)
        val style = if (lit) GlowType.secondary.copy(fontWeight = FontWeight.SemiBold) else GlowType.secondary
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, color = text, style = style, maxLines = 1, modifier = Modifier.widthIn(min = 56.dp).padding(end = 8.dp))
            Box(Modifier.weight(1f).height(10.dp)) {
                if (best != null && r.name.startsWith("Km ") && seconds[i] > 0) Box(
                    Modifier.fillMaxWidth(best.toFloat() / seconds[i]).fillMaxHeight().clip(CircleShape).background(if (lit) Color.White else bar),
                )
            }
            Text(r.time, color = Color.White, style = GlowType.secondary.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.padding(start = 14.dp))
        }
    }
}

/** The Share and Done pair on a summary's bottom edge (boards SummaryRun to SummaryGame): Done is the way on, in white. */
@Composable
internal fun SummaryButtons(onShare: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkoutButton("Share", Raised, Color.White, onShare, Modifier.weight(1f), icon = AppIcons.UploadSimple)
        WorkoutButton("Done", Color.White, Color.Black, onDone, Modifier.weight(1f), icon = AppIcons.Tick02)
    }
}

/** Done as a small pill beside the title, to leave without scrolling to the bottom. */
@Composable
internal fun TopDone(onDone: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(CircleShape).background(Raised).pressClickable(onDone, CircleShape).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text("Done", color = Color.White, style = GlowType.secondary.copy(fontWeight = FontWeight.SemiBold), maxLines = 1) }
}
