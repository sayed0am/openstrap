package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.components.HapticEvent
import org.openstrap.app.ui.components.rememberHaptics
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.DotSize
import org.openstrap.app.ui.glow.EdgeCard
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.MediumBody
import org.openstrap.app.ui.glow.TrendBad
import org.openstrap.app.ui.glow.TrendGood
import org.openstrap.app.ui.glow.activeSecondsState
import org.openstrap.app.ui.glow.workoutColor
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.glow.draw.dotLayout
import org.openstrap.app.ui.workout.Glance
import org.openstrap.app.ui.workout.LiveView
import org.openstrap.app.ui.workout.WorkoutCopy
import org.openstrap.app.ui.glow.WaitingDash
import org.openstrap.app.ui.glow.workoutClock

/**
 * A distance workout with GPS (workouts spec 2.3, boards Run and Map): two views, swiped, under the moving time. Pace:
 * the pace now large on a card lit in the distance lime, the average beside its title and a bar per kilometre, the one
 * under way growing; the heart rate on a strip in its zone's colour; then distance, climb and active kcal. Map: the
 * route in zone colours with the heart rate on it, then distance, pace and the last split. Opens on the view used last
 * ([liveView], saved through [onLiveView]). A ride shows its speed instead of a pace. With [split] the heart rate is on
 * the other half of a folded phone, so the map leaves out its own; [compact] (a short half) keeps the moving time small
 * on top and the distance, pace and kcal in one row.
 */
@Composable
internal fun LiveDistanceScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    stats: WorkoutRecorder.Stats?,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    controls: LiveControls,
    route: List<Route.Point>,
    history: List<Pair<Long, Int>>,
    onLap: () -> Unit,
    liveView: Int?,
    onLiveView: (Int) -> Unit,
    kcal: WorkoutRecorder.Kcal?,
    /** Run reps: Skip the rep or the rest, or start the reps after a warm up. */
    onSkip: () -> Unit = {},
    split: Boolean = false,
    compact: Boolean = false,
) {
    val pager = rememberPagerState(initialPage = LiveView.of(liveView).ordinal) { LiveView.entries.size }
    var viewApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(liveView) {
        if (!viewApplied && liveView != null) {
            pager.scrollToPage(LiveView.of(liveView).ordinal)
            viewApplied = true
        }
    }
    // Only a swipe or a dot saves the view, never the page the screen opened on.
    val saveView by rememberUpdatedState(onLiveView)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect { saveView(it) } }
    val scope = rememberCoroutineScope()
    val connected = connection is ConnectionState.Connected
    val color = workoutColor(r, liveHeartRate, connected)
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    ZoneTick(bpm, r)
    val distance = r.workout.distanceMeters ?: 0f
    val splits = remember(route.size, r.workout.pauseRanges) { Route.splits(route, r.workout.pauseRanges) }
    val climb = remember(route.size) { Route.climb(route) }
    val speed = Workouts.tileType(r.workout.sportType) !in Workouts.ON_FOOT
    val pace = remember(route) { Route.currentPace(route) }
    val active = activeSecondsState(r)
    // Read where it shows, so the screen holds still between seconds.
    val averagePace = { if (distance >= 50) active.value / (distance / 1000.0) else null }
    val accuracy = route.lastOrNull()?.accuracy
    val reps = r.structure is org.openstrap.app.data.Structure.Reps
    val gps = WorkoutCopy.gpsPill(accuracy) to if (accuracy != null && accuracy <= Route.MAX_ACCURACY_M) TrendGood else TrendBad
    val readings = WorkoutCopy.readings(history, recent)
    // Reps skip instead of marking laps: the rounds are the laps.
    val lap: (@Composable () -> Unit)? = when {
        r.paused -> null
        reps -> ({ LapButton(onSkip, label = true, text = "Skip") })
        else -> ({ LapButton(onLap, label = true) })
    }
    val battery = (connection as? ConnectionState.Connected)?.batteryPercent
    if (r.paused) {
        LiveFrame(
            r, color, connected, contentPadding, controls, lap, time = { active.value }, battery = battery, status = listOf(gps),
            paused = {
                LivePaused(r, liveHeartRate.takeIf { connected }, recent, listOf(
                    Glance.kmFigure(distance) to "km",
                    (if (speed) speedText(averagePace()) else Glance.paceClock(averagePace())) to if (speed) "Avg km/h" else "Avg pace",
                    stats?.average?.toString() to "Avg bpm",
                ))
            },
        ) {}
        return
    }
    val onPage: (Int) -> Unit = { scope.launch { pager.animateScrollToPage(it) } }
    // Only what's above the dots slides: the note, the dots and the controls stay put under the pager, the dots
    // following the swipe. A drag on the map pans the map instead.
    val density = LocalDensity.current
    var bar by remember { mutableStateOf(240.dp) }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top, beyondViewportPageCount = 1) { page ->
            when (LiveView.entries[page]) {
                // Reps (board RunIntervals): the total small on top, the rep the screen's number.
                LiveView.Heart -> if (reps) LiveFrame(
                    r, color, connected, contentPadding, controls, lap, time = { active.value }, smallTime = true, battery = battery, status = listOf(gps),
                    fill = true, title = "${Workouts.sportName(r.workout.sportType)}, intervals", bottomRoom = bar,
                ) {
                    RepPage(r, route, readings, bpm, color, Modifier.weight(1f))
                } else LiveFrame(
                    r, color, connected, contentPadding, controls, lap, time = { active.value }, smallTime = compact, battery = battery,
                    status = listOf(gps), fill = true, bottomRoom = bar,
                ) {
                    if (compact) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        LiveFigures(listOf(
                            Glance.kmFigure(distance) to "km",
                            (if (speed) speedText(pace) else Glance.paceClock(pace)) to if (speed) "km/h" else "Pace /km",
                            kcal?.active?.toInt()?.toString() to "Active kcal",
                        ))
                    } else Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PaceCard(pace, averagePace(), splits, speed, Modifier.weight(1f))
                        LitHeartPill(bpm, r.maxHeartRate, color, r.paused, 56.dp, Modifier.fillMaxWidth())
                        LiveFigures(listOf(
                            Glance.kmFigure(distance) to "km",
                            climb?.toInt()?.toString() to "Climb m",
                            kcal?.active?.toInt()?.toString() to "Active kcal",
                        ))
                    }
                    // Each kilometre gets a line for a few seconds, with one tick. This page stays composed beside the
                    // map, so the tick comes on either.
                    KmSplitLine(route, readings, r.workout.pauseRanges, keepRoom = !compact)
                }
                LiveView.Map -> LiveMapScreen(
                    r, route, readings, bpm, color, contentPadding, controls,
                    figures = listOf(
                        Glance.kmFigure(distance) to "km",
                        (if (speed) speedText(pace) else Glance.paceClock(pace)) to if (speed) "km/h" else "Pace /km",
                    ),
                    time = { active.value }, bottomRoom = bar, heartPill = !split,
                )
            }
        }
        LiveBottomBar(
            r, connected, contentPadding, controls, lap, { pager.currentPage + pager.currentPageOffsetFraction }, LiveView.entries.size, onPage,
            onHeight = { if (it != bar) bar = it }, modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * A run's reps (workouts spec 2.5, board RunIntervals): the plan and a bar of the reps, rep N of M and the rest after
 * it, the metres to go in the screen's one dotted number (the seconds of a time rep) with the rep's own pace, the heart
 * rate strip, then each rep done with its time. A rest shows its seconds left and the rep after it.
 */
@Composable
private fun RepPage(r: WorkoutState.Recording, route: List<Route.Point>, readings: List<Pair<Long, Int>>, bpm: Int?, color: Color, modifier: Modifier) {
    val now = nowTicking()
    val m = org.openstrap.app.ui.workout.intervalMoment(r, now, bpm, readings, route) ?: return
    val structure = r.structure as? org.openstrap.app.data.Structure.Reps ?: return
    val repName = org.openstrap.app.ui.workout.StartCopy.repParts(structure).let { (n, unit) -> "$n $unit" }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "${org.openstrap.app.ui.workout.StartCopy.structureName(structure)}, ${org.openstrap.app.ui.workout.StartCopy.restText(structure.rest)} rest",
            color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary, maxLines = 1,
        )
        val underWay = structure.meters?.let { meters -> m.metersLeft?.let { 1f - it / meters } }
        RoundBar(m, structure.seconds, underWay)
        Row(Modifier.fillMaxWidth()) {
            Text(
                when (m.phase) {
                    is org.openstrap.app.data.Phase.Work -> "Rep ${m.round} of ${m.rounds}"
                    is org.openstrap.app.data.Phase.Rest -> "${m.round - 1} of ${m.rounds} done"
                    org.openstrap.app.data.Phase.WarmUp -> "Warm up"
                    org.openstrap.app.data.Phase.Done -> "All ${m.rounds} reps done"
                },
                color = Color.White, style = MediumBody, maxLines = 1, modifier = Modifier.weight(1f),
            )
            val next = when (m.phase) {
                is org.openstrap.app.data.Phase.Work -> m.nextRest?.let { "Then rest ${org.openstrap.app.ui.workout.StartCopy.restText(it)}" } ?: "Last rep"
                is org.openstrap.app.data.Phase.Rest -> "Rep ${m.round} next"
                org.openstrap.app.data.Phase.WarmUp -> "${m.rounds} reps after it"
                org.openstrap.app.data.Phase.Done -> ""
            }
            Text(next, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            val work = m.phase is org.openstrap.app.data.Phase.Work
            val rest = m.phase is org.openstrap.app.data.Phase.Rest
            Text(
                when { work -> "$repName rep"; rest -> "Rest"; m.phase == org.openstrap.app.data.Phase.WarmUp -> "Warming up"; else -> "Done" },
                color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
            )
            val (value, unit) = when {
                work && m.metersLeft != null -> "${m.metersLeft.toInt()}" to "m to go"
                m.left != null -> workoutClock(m.left) to if (rest) "rest left" else "left"
                else -> null to ""
            }
            if (value != null) {
                DotMatrixText(value, dot = DotSize.Live, scale = 1.2f)
                Text(unit, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body)
            }
            val line = when {
                work -> Route.currentPace(route)?.let { pace ->
                    structure.meters?.let { "Pace ${workoutClock((pace * it / 1000).toLong())} per $repName" }
                        ?: org.openstrap.app.ui.workout.Glance.paceClock(pace)?.let { "Pace $it /km" }
                }
                m.phase == org.openstrap.app.data.Phase.WarmUp -> "Skip starts the reps when you're ready"
                m.phase == org.openstrap.app.data.Phase.Done -> "Hold the square to end, or keep going"
                else -> null
            }
            if (line != null) Text(line, color = Color.White, style = GlowType.sentence, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        LitHeartPill(bpm, r.maxHeartRate, color, r.paused, 56.dp, Modifier.fillMaxWidth())
        val done = r.rounds.filter { it.workEnd != null }
        if (done.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(Int.MAX_VALUE)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            done.forEach { round ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(org.openstrap.app.ui.glow.Raised).padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Rep ${round.index + 1}", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text(workoutClock(round.workEnd!! - round.workStart), color = Color.White, style = MediumBody, maxLines = 1)
                }
            }
        }
    }
}

/** A tick when the zone changes (spec 3.6); a pause forgets the zone, so resuming in another doesn't tick. */
@Composable
internal fun ZoneTick(bpm: Int?, r: WorkoutState.Recording) {
    val haptics = rememberHaptics()
    val zone = WorkoutRecorder.liveZone(bpm, r.maxHeartRate)
    var lastZone by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(zone, r.paused) {
        if (WorkoutCopy.zoneTick(lastZone, zone)) haptics.perform(HapticEvent.ZoneChange)
        lastZone = if (r.paused) null else zone ?: lastZone
    }
}

/** km/h from seconds per kilometre, one decimal; null with no pace. */
internal fun speedText(secondsPerKm: Double?): String? =
    secondsPerKm?.takeIf { it > 0 && it < 3600 }?.let { String.format(java.util.Locale.ROOT, "%.1f", 3600 / it) }

/**
 * The Pace card: "Pace" with the average beside it, the pace now in the card's one dotted number, and a bar per
 * kilometre under it ([SplitBars]). Lit in the distance lime from its edge, so it never looks like the heart strip
 * under it.
 */
@Composable
private fun PaceCard(pace: Double?, averagePace: Double?, splits: List<Route.Split>, speed: Boolean, modifier: Modifier = Modifier) {
    EdgeCard(MetricColors.steps, modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(if (speed) "Speed" else "Pace", color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
            val avg = if (speed) speedText(averagePace)?.let { "Avg $it" } else Glance.paceClock(averagePace)?.let { "Avg $it" }
            if (avg != null) Text(avg, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        // The pace sits in the middle of whatever height the card is given, the splits on its floor.
        Spacer(Modifier.height(12.dp).weight(1f))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val room = maxWidth.value
            // Centred, as the board has it: the one number the card is for.
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.Bottom) {
                val now = if (speed) speedText(pace) else Glance.paceClock(pace)
                // The live size, shrunk only as far as a walk's 12:30 needs to fit beside its unit.
                val fit = now?.let { ((room - UNIT_ROOM) / dotLayout(it, DotSize.Live, 1f).width).coerceIn(0.5f, 1f) } ?: 1f
                if (now != null) DotMatrixText(now, dot = DotSize.Live, scale = fit) else WaitingDash(Modifier.padding(vertical = 18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (speed) "km/h" else "/km", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, maxLines = 1)
            }
        }
        Spacer(Modifier.height(16.dp).weight(1f))
        if (splits.isNotEmpty()) {
            SplitBars(splits)
        }
    }
}

/**
 * The last five kilometres as bars, taller for faster, the fastest finished one bright with its time in white and bold,
 * the rest dim, each with its time under it; the one under way is an outlined full bar filling from the bottom, named
 * by its kilometre.
 */
@Composable
private fun SplitBars(splits: List<Route.Split>) {
    val shown = splits.takeLast(SPLIT_SLOTS)
    val done = splits.filter { it.meters >= 1000 }
    val fastest = done.minByOrNull { it.pace }
    val slowest = done.maxOfOrNull { it.pace } ?: 0.0
    val quickest = fastest?.pace ?: 0.0
    val described = done.takeLast(4).joinToString(", ") { workoutClock(it.seconds) }
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "Kilometre splits: $described" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.forEach { s ->
            val underWay = s.meters < 1000
            // Faster is taller: from 60 % for the slowest to full for the fastest.
            val height = when {
                underWay -> 1f
                slowest <= quickest -> 1f
                else -> (0.6f + 0.4f * ((slowest - s.pace) / (slowest - quickest)).toFloat()).coerceIn(0.6f, 1f)
            }
            val best = s == fastest
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val lime = MetricColors.steps
                Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                    val h = size.height * height
                    val corner = CornerRadius(5.dp.toPx())
                    if (underWay) {
                        val filled = size.height * (s.meters / 1000).toFloat().coerceIn(0f, 1f)
                        drawRoundRect(lime.copy(alpha = 0.35f), Offset(0f, size.height - filled), Size(size.width, filled), corner)
                        val w = 1.5.dp.toPx()
                        drawRoundRect(
                            lime.copy(alpha = 0.6f), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), corner,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                        )
                    } else drawRoundRect(if (best) lime else lime.copy(alpha = 0.35f), Offset(0f, size.height - h), Size(size.width, h), corner)
                }
                Text(
                    if (underWay) "Km ${s.index + 1}" else workoutClock(s.seconds),
                    color = if (best) Color.White else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.caption.copy(fontWeight = if (best) FontWeight.SemiBold else null), maxLines = 1,
                )
            }
        }
        // Empty slots keep each bar a fifth of the card, so the first kilometre isn't a line across it.
        repeat(SPLIT_SLOTS - shown.size) { Spacer(Modifier.weight(1f)) }
    }
}

private const val SPLIT_SLOTS = 5

/** Room left beside the pace for "km/h" and its gap, in dp. */
private const val UNIT_ROOM = 56f
