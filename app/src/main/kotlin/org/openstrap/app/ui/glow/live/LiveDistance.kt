package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.onSizeChanged
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
import org.openstrap.app.ui.glow.activeSeconds
import org.openstrap.app.ui.glow.workoutColor
import org.openstrap.app.ui.theme.MetricColors
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
 * ([liveView], saved through [onLiveView]). A ride shows its speed instead of a pace.
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
    val density = LocalDensity.current
    var pageHeight by remember { mutableStateOf(320.dp) }
    var routeOnly by rememberSaveable { mutableStateOf(false) }
    val connected = connection is ConnectionState.Connected
    val color = workoutColor(r, liveHeartRate, connected)
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    ZoneTick(bpm, r)
    val distance = r.workout.distanceMeters ?: 0f
    val splits = remember(route.size, r.workout.pauseRanges) { Route.splits(route, r.workout.pauseRanges) }
    val climb = remember(route.size) { Route.climb(route) }
    val speed = Workouts.tileType(r.workout.sportType) !in Workouts.ON_FOOT
    val pace = Route.currentPace(route)
    val active = activeSeconds(r)
    val averagePace = if (distance >= 50) active / (distance / 1000.0) else null
    val accuracy = route.lastOrNull()?.accuracy
    val reps = r.structure is org.openstrap.app.data.Structure.Reps
    val gps = WorkoutCopy.gpsPill(accuracy) to if (accuracy != null && accuracy <= Route.MAX_ACCURACY_M) TrendGood else TrendBad
    LiveFrame(
        r, color, connected, contentPadding, controls,
        // Reps skip instead of marking laps: the rounds are the laps.
        lap = when {
            r.paused -> null
            reps -> ({ LapButton(onSkip, label = true, text = "Skip") })
            else -> ({ LapButton(onLap, label = true) })
        },
        time = active, battery = (connection as? ConnectionState.Connected)?.batteryPercent, status = listOf(gps),
        paused = {
            LivePaused(r, liveHeartRate.takeIf { connected }, recent, listOf(
                Glance.kmFigure(distance) to "km",
                (if (speed) speedText(averagePace) else Glance.paceClock(averagePace)) to if (speed) "Avg km/h" else "Avg pace",
                stats?.average?.toString() to "Avg bpm",
            ))
        },
    ) {
        HorizontalPager(pager, pageSpacing = 16.dp, verticalAlignment = Alignment.Top, beyondViewportPageCount = 1) { page ->
            when (LiveView.entries[page]) {
                LiveView.Heart -> Column(
                    Modifier.fillMaxWidth().onSizeChanged { pageHeight = with(density) { it.height.toDp() } },
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (reps) RepCard(r, route, WorkoutCopy.readings(history, recent), bpm) else PaceCard(pace, averagePace, splits, speed)
                    HeartStrip(bpm, r.maxHeartRate, color, r.paused)
                    LiveFigures(listOf(
                        Glance.kmFigure(distance) to "km",
                        climb?.toInt()?.toString() to "Climb m",
                        kcal?.active?.toInt()?.toString() to "Active kcal",
                    ))
                }
                LiveView.Map -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LiveMapPage(route, history, r.maxHeartRate, routeOnly, bpm, color, r.paused, Modifier.height((pageHeight - 90.dp).coerceAtLeast(200.dp))) { routeOnly = it }
                    LiveFigures(listOf(
                        Glance.kmFigure(distance) to "km",
                        (if (speed) speedText(pace) else Glance.paceClock(pace)) to if (speed) "km/h" else "Pace /km",
                        stats?.average?.toString() to "Avg bpm",
                    ))
                }
            }
        }
        Box(Modifier.fillMaxWidth().semantics { contentDescription = "View ${pager.currentPage + 1} of 2" }) {
            PageDots(pager.currentPage, LiveView.entries.size) { scope.launch { pager.animateScrollToPage(it) } }
        }
        // Each kilometre gets a line for a few seconds, with one tick.
        KmSplitLine(route, WorkoutCopy.readings(history, recent), r.workout.pauseRanges)
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
private fun PaceCard(pace: Double?, averagePace: Double?, splits: List<Route.Split>, speed: Boolean) {
    EdgeCard(MetricColors.steps, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(if (speed) "Speed" else "Pace", color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
            val avg = if (speed) speedText(averagePace)?.let { "Avg $it" } else Glance.paceClock(averagePace)?.let { "Avg $it" }
            if (avg != null) Text(avg, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            val now = if (speed) speedText(pace) else Glance.paceClock(pace)
            if (now != null) DotMatrixText(now, dot = DotSize.Detail) else WaitingDash(Modifier.padding(vertical = 18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (speed) "km/h" else "/km", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body)
        }
        if (splits.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SplitBars(splits)
        }
    }
}

/**
 * The last five kilometres as bars, taller for faster, the fastest finished one bright and the rest dim, each with its
 * time under it; the one under way grows toward a full bar and says which kilometre it is.
 */
@Composable
private fun SplitBars(splits: List<Route.Split>) {
    val shown = splits.takeLast(5)
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
            // Faster is taller: from 40 % for the slowest to full for the fastest.
            val height = when {
                underWay -> (s.meters / 1000).toFloat().coerceIn(0.08f, 1f)
                slowest <= quickest -> 1f
                else -> (0.4f + 0.6f * ((slowest - s.pace) / (slowest - quickest)).toFloat()).coerceIn(0.4f, 1f)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val lime = MetricColors.steps
                Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                    val h = size.height * height
                    drawRoundRect(
                        if (s == fastest || underWay) lime else lime.copy(alpha = 0.35f),
                        Offset(0f, size.height - h), Size(size.width, h), CornerRadius(4.dp.toPx()),
                    )
                }
                Text(
                    if (underWay) "Km ${s.index + 1}" else workoutClock(s.seconds),
                    color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.caption, maxLines = 1,
                )
            }
        }
    }
}

/**
 * The heart rate on a strip in its zone's [color]: a dot beating at it, the bpm in small dots and the zone named, in
 * black on the colour. Paused or without a reading, it says so.
 */
@Composable
private fun HeartStrip(bpm: Int?, maxHr: Int, color: Color, paused: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bpm == null) {
            Text(if (paused) "Paused" else "Waiting for heart rate", color = Color.Black, style = MediumBody)
            return@Row
        }
        org.openstrap.app.ui.glow.PulseDot(Color.Black, bpm, 8.dp)
        Spacer(Modifier.width(10.dp))
        DotMatrixText("$bpm", dot = DotSize.Edge, color = Color.Black)
        Spacer(Modifier.width(6.dp))
        Text("bpm", color = Color.Black, style = MediumBody)
        Spacer(Modifier.weight(1f))
        WorkoutRecorder.liveZone(bpm, maxHr)?.let { z ->
            Text(WorkoutCopy.zoneTitle(z).let { (a, b) -> "$a $b" }, color = Color.Black, style = MediumBody, maxLines = 1)
        }
    }
}
