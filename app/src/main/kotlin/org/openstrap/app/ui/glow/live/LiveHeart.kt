package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.components.HapticEvent
import org.openstrap.app.ui.components.rememberHaptics
import org.openstrap.app.ui.glow.*
import org.openstrap.app.ui.workout.WorkoutCopy
import org.openstrap.app.ui.workout.ZONE_COLORS

/* -------------------------------------------- Live screen -------------------------------------------- */

/**
 * The workout as it records, in the current zone's colour. The card beats at the live heart rate on its zone arc, the
 * last ten minutes behind the number; the zone, its range and the time spent in it sit under the arc. A GPS workout
 * swipes between that card and the map, which keeps the heart rate on a chip, and opens on the view used last
 * ([liveView], saved through [onLiveView]). Back tucks it away; ending takes a one-second hold on the square so a
 * pocket can't end it. With [split] the heart card is on the other half of a folded phone ([GlowWorkoutGlance]), so
 * this half keeps the kind's own screen, without the heart rate where that's its hero, and all its controls.
 */
@Composable
fun GlowWorkoutLiveScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    stats: WorkoutRecorder.Stats?,
    zoneSeconds: IntArray,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onMinimize: () -> Unit,
    onEndAtPrompt: () -> Unit,
    onKeepGoing: () -> Unit,
    route: List<Route.Point> = emptyList(),
    history: List<Pair<Long, Int>> = emptyList(),
    onStartSet: () -> Unit = {},
    onSetDone: () -> Unit = {},
    onExtendRest: () -> Unit = {},
    onLap: () -> Unit = {},
    split: Boolean = false,
    liveView: Int? = null,
    onLiveView: (Int) -> Unit = {},
    kcal: WorkoutRecorder.Kcal? = null,
    /** Strength: the exercises logged before, naming the exercise, and the log card's Save. */
    exercises: List<org.openstrap.app.data.db.ExerciseRow> = emptyList(),
    onExercise: (String?) -> Unit = {},
    onLogSet: (index: Int, exercise: String?, reps: Int, kg: Float) -> Unit = { _, _, _, _ -> },
    /** Rounds: Skip, Skip rest, Done or Start rounds; and +10 s on a rest. */
    onSkip: () -> Unit = {},
    onAddRest: () -> Unit = {},
) {
    // Kept across the once-a-second recompositions, so what takes the controls can skip them.
    val controls = remember(onPause, onResume, onEnd, onMinimize, onEndAtPrompt, onKeepGoing) {
        LiveControls(onPause, onResume, onEnd, onMinimize, onEndAtPrompt, onKeepGoing)
    }
    // Each kind of workout has its own screen (workouts spec 2.3), split across a fold too: Skip, +10 s, Half time and
    // the log card stay on the half under your hand. Only the screens whose hero is the heart rate leave it to the
    // other half.
    val family = Workouts.family(r.workout.sportType)
    when {
        family == Workouts.Family.Distance && r.workout.useGps ->
            LiveDistanceScreen(r, liveHeartRate, recent, stats, connection, contentPadding, controls, route, history, onLap, liveView, onLiveView, kcal, onSkip)
        family == Workouts.Family.Strength -> LiveStrengthScreen(
            r, liveHeartRate, WorkoutCopy.readings(history, recent), connection, contentPadding, controls, exercises,
            onStartSet, onSetDone, onExtendRest, onExercise, onLogSet,
        )
        family == Workouts.Family.Intervals && r.structure != null ->
            LiveIntervalsScreen(r, liveHeartRate, WorkoutCopy.readings(history, recent), connection, contentPadding, controls, onSkip, onAddRest)
        family == Workouts.Family.MindBody -> LiveMindScreen(r, liveHeartRate, recent, connection, contentPadding, controls)
        family == Workouts.Family.Games ->
            LiveGameScreen(r, liveHeartRate, WorkoutCopy.readings(history, recent), stats, zoneSeconds, connection, contentPadding, controls, kcal, onSkip, split)
        // Indoor cardio, a distance sport without GPS, and HIIT's Free, which has no timer: heart rate only, laps mark
        // the rounds.
        else -> LiveIndoorScreen(r, liveHeartRate, recent, stats, zoneSeconds, connection, contentPadding, controls, onLap, kcal, split)
    }
}

/**
 * "Km 3 in 5:42, avg 152" under the stats for ten seconds after each kilometre, with one tick. Kilometres done before
 * the screen opened have had theirs.
 */
@Composable
internal fun KmSplitLine(
    route: List<Route.Point>, readings: List<Pair<Long, Int>>, pauses: List<LongRange>,
    /** Its line's height is kept while there's none, so views filling the screen don't shrink when it shows. */
    keepRoom: Boolean = false,
) {
    val splits = remember(route.size, pauses) { Route.splits(route, pauses) }
    val now = nowTicking()
    val line = WorkoutCopy.splitLine(splits, readings, route.firstOrNull()?.t, now)
    val haptics = rememberHaptics()
    var ticked by remember { mutableIntStateOf(WorkoutCopy.wholeKms(splits)) }
    LaunchedEffect(line?.index) {
        if (line != null && WorkoutCopy.splitTick(line, ticked)) {
            haptics.perform(HapticEvent.KmSplit)
            ticked = line.index
        }
    }
    if (line != null || keepRoom) Text(
        line?.text.orEmpty(), color = Color.White, style = MediumBody.copy(fontFeatureSettings = "tnum"),
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    )
}

/** Where the zone arc starts (lower left, clockwise from three o'clock) and how far it goes, in degrees. */
private const val ARC_START = 150f
private const val ARC_SWEEP = 240f
private const val ARC_GAP = 8f

/**
 * The five zones as segments of an arc open at the bottom, only the current one lit, with a marker where [bpm] sits
 * (pattern D2). The number goes in its middle.
 */
@Composable
internal fun ZoneArc(bpm: Int?, maxHr: Int, modifier: Modifier) {
    val zone = WorkoutRecorder.liveZone(bpm, maxHr)
    Canvas(modifier) {
        val stroke = 6.dp.toPx()
        val halo = 9.dp.toPx()
        val r = min(size.width, size.height) / 2 - halo
        val topLeft = Offset(center.x - r, center.y - r)
        val box = Size(r * 2, r * 2)
        val seg = (ARC_SWEEP - ARC_GAP * (Workouts.ZONES - 1)) / Workouts.ZONES
        for (z in 0 until Workouts.ZONES) {
            val start = ARC_START + z * (seg + ARC_GAP)
            val on = z == zone
            // The lit segment glows: a wide soft stroke of itself under it.
            if (on) drawArc(ZONE_COLORS[z].copy(alpha = 0.3f), start, seg, false, topLeft, box, style = Stroke(stroke * 2.6f, cap = StrokeCap.Round))
            drawArc(ZONE_COLORS[z].copy(alpha = if (on) 1f else 0.22f), start, seg, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (bpm != null) {
            val a = Math.toRadians((ARC_START + WorkoutRecorder.zonePosition(bpm, maxHr) * ARC_SWEEP).toDouble())
            val at = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
            val dot = zone?.let { ZONE_COLORS[it] } ?: Color.White
            drawCircle(dot.copy(alpha = 0.3f), radius = halo, center = at)
            drawCircle(Color.White, radius = 5.dp.toPx(), center = at)
        }
    }
}

/**
 * The live heart card: the beating number on its zone arc over the last ten minutes, then the zone, its range and the
 * time in it. Its light reaches as high as the zone (spec 2.5); paused, it drops to an outline of the same layout
 * (pattern D1). With [fill] it takes all the height it's given and the number grows, for the standing half of a fold.
 */
@Composable
internal fun WorkoutHeartCard(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    zoneSeconds: IntArray,
    connected: Boolean,
    active: Long,
    modifier: Modifier,
    fill: Boolean = false,
    /** The live active kcal, for a kcal target's progress. */
    kcal: Float? = null,
    /** The time in each zone as a bar under the zone (indoor cardio, board Main). */
    zoneBar: Boolean = false,
) {
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    val zone = WorkoutRecorder.liveZone(bpm, r.maxHeartRate)
    val color = workoutColor(r, liveHeartRate, connected)
    val style = GlowStyle.chart(color).copy(
        motion = if (bpm != null) GlowMotion.Heartbeat(bpm) else GlowMotion.Still,
        lightLevel = WorkoutCopy.zoneLightLevel(zone),
        outline = r.paused,
    )
    GlowCard(style, modifier) {
        Column(if (fill) Modifier.fillMaxSize() else Modifier) {
            GlowHeader("Heart rate", trailing = bpm?.let { "${it * 100 / r.maxHeartRate}% of max" })
            BoxWithConstraints(Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f) else Modifier.height(220.dp))) {
                val now = recent.lastOrNull()?.first ?: (System.currentTimeMillis() / 1000)
                val values = remember(recent) { bucketRecent(recent, now) }
                if (values.any { it != null }) {
                    GlowLineChart(values, Modifier.align(Alignment.BottomCenter).alpha(0.45f), height = 120.dp, showAverage = false)
                }
                ZoneArc(bpm, r.maxHeartRate, Modifier.matchParentSize())
                // Sized for three digits inside the arc, whatever the reading, so the number never spills past it
                // and doesn't jump in size as the heart rate crosses 100.
                val inside = (minOf(maxWidth, maxHeight) - 18.dp) * 0.78f
                val scale = (inside / dotNumberWidth(3, DotSize.Live)).coerceAtMost(1f)
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bpm != null) {
                        DotMatrixText(bpm.toString(), dot = DotSize.Live, scale = scale)
                        Spacer(Modifier.height(8.dp))
                        Text("bpm, last 10 min behind", color = Color.White.copy(alpha = 0.75f), style = GlowType.secondary)
                    } else Text(
                        when { r.paused -> "Paused"; !connected -> "Waiting for the strap"; else -> "Waiting for heart rate" },
                        color = Color.White, style = GlowType.sentence,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (zone != null) {
                    // "Zone 3, aerobic": two text elements, the name a step quieter.
                    val (name, word) = WorkoutCopy.zoneTitle(zone)
                    Text(name, color = Color.White, style = GlowType.sentence)
                    Spacer(Modifier.width(6.dp))
                    Text(word, color = Color.White.copy(alpha = 0.75f), style = GlowType.sentence, modifier = Modifier.weight(1f))
                    Text(zoneRange(zone, r.maxHeartRate), color = Color.White.copy(alpha = 0.85f), style = GlowType.secondary)
                } else Text(if (r.paused) "No zone while paused" else "No zone yet", color = Color.White, style = GlowType.sentence)
            }
            if (zoneBar) {
                Spacer(Modifier.height(12.dp))
                ZoneTimeBar(zoneSeconds, zone)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                zone?.let { Note(WorkoutCopy.zoneTime(zoneSeconds[it])) }
                Spacer(Modifier.weight(1f))
                when (val t = WorkoutTarget.parse(r.workout.target)) {
                    is WorkoutTarget.Zones -> Note("Target ${t.label.removePrefix("Zone ")}, the strap buzzes if you drift")
                    is WorkoutTarget.Time -> Note("${workoutClock(active)} of ${t.label}")
                    is WorkoutTarget.Kcal -> Note(WorkoutCopy.kcalProgress(kcal, t))
                    is WorkoutTarget.Distance -> Note(WorkoutCopy.distanceProgress(r.workout.distanceMeters, t))
                    null -> Unit
                }
            }
        }
    }
}

/**
 * The standing half of a foldable half-open during a workout (on a table, or stood up like a book): the live heart
 * card as big as the half allows, readable from a step away. The controls stay on the other half, under your hand.
 */
@Composable
fun GlowWorkoutGlance(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    zoneSeconds: IntArray,
    connection: ConnectionState,
    modifier: Modifier = Modifier,
    kcal: WorkoutRecorder.Kcal? = null,
) {
    val connected = connection is ConnectionState.Connected
    Box(modifier.background(Color.Black).statusBarsPadding().padding(horizontal = GlowLayout.Gutter, vertical = 12.dp)) {
        WorkoutHeartCard(r, liveHeartRate, recent, zoneSeconds, connected, activeSeconds(r), Modifier.fillMaxSize(), fill = true, kcal = kcal?.active)
    }
}

private const val BUCKET_S = 5L
private const val WINDOW_S = 600L

/** The last ten minutes in five-second averages, oldest first; gaps stay null and break the line. */
internal fun bucketRecent(recent: List<Pair<Long, Int>>, now: Long): List<Float?> {
    val from = now - WINDOW_S + 1
    return org.openstrap.app.data.Summaries.bucketMeans(recent, (WINDOW_S / BUCKET_S).toInt(), { ((it.first - from) / BUCKET_S).toInt() }, { it.second.toFloat() })
}
