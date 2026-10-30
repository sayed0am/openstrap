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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openstrap.app.data.Phase
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.db.AppWorkoutRound
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.DotSize
import org.openstrap.app.ui.glow.EdgeCard
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.Raised
import org.openstrap.app.ui.glow.WorkoutButton
import org.openstrap.app.ui.glow.activeSeconds
import org.openstrap.app.ui.glow.workoutClock
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.IntervalMoment
import org.openstrap.app.ui.workout.StartCopy
import org.openstrap.app.ui.workout.WorkoutCopy
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.workout.intervalMoment

/** Work red and rest green, the intervals' two colours. */
private val WorkRed = MetricColors.heart
private val RestGreen = ZONE_COLORS[1]

/** The seconds a phase's clock is ticked from, for the screen. */
@Composable
private fun nowTicking(): Long = produceState(System.currentTimeMillis() / 1000) {
    while (true) {
        value = System.currentTimeMillis() / 1000
        delay(1000 - System.currentTimeMillis() % 1000)
    }
}.value

/**
 * HIIT (workouts spec 2.5, boards HiitWork and HiitRest), with the total small on top and the screen in the phase's
 * colour. Work: round N of M as dots, what's next, the seconds left large in dots, the heart rate and zone; Skip on the
 * left moves to rest (Done, every minute). Rest: what's done and next, the seconds left, the heart rate and its drop
 * since work ended, each round's peak so far, and +10 s. A warm up waits for Start rounds.
 */
@Composable
internal fun LiveIntervalsScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    readings: List<Pair<Long, Int>>,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    controls: LiveControls,
    onSkip: () -> Unit,
    onAddRest: () -> Unit,
) {
    val now = nowTicking()
    val connected = connection is ConnectionState.Connected
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    ZoneTick(bpm, r)
    val m = intervalMoment(r, now, bpm, readings, emptyList()) ?: return
    val color = when {
        r.paused -> org.openstrap.app.ui.glow.PausedColor
        m.phase is Phase.Work -> WorkRed
        m.phase is Phase.Rest -> RestGreen
        else -> org.openstrap.app.ui.glow.workoutColor(r, liveHeartRate, connected)
    }
    val skipLabel = when (m.phase) {
        is Phase.Work -> if (m.everyMinute) "Done" else "Skip"
        is Phase.Rest -> "Skip rest"
        else -> null
    }
    LiveFrame(
        r, color, connected, contentPadding, controls, time = activeSeconds(r), smallTime = true,
        lap = skipLabel?.takeIf { !r.paused }?.let { label -> { SkipButton(label, onSkip) } },
        paused = { LivePaused(r, liveHeartRate.takeIf { connected }, readings, listOf(
            "${org.openstrap.app.data.Phases.roundsDone(r.rounds)}" to "Rounds done",
            r.rounds.mapNotNull { it.peakBpm }.maxOrNull()?.toString() to "Peak bpm",
        )) },
    ) {
        RoundDots(m)
        when (m.phase) {
            is Phase.Work -> PhaseFigure(
                "Round ${m.round} of ${m.rounds}", m.nextRest?.let { "Then rest ${StartCopy.restText(it)}" } ?: "Last round",
                "Work", m.left?.let { "%02d".format(it) }, if (m.everyMinute) "seconds left in the minute" else "seconds left",
            )
            is Phase.Rest -> PhaseFigure(
                "${m.round - 1} of ${m.rounds} done", "Round ${m.round} next", "Rest", m.left?.let { "%02d".format(it) },
                bpm?.let { b -> "Heart rate $b" + (m.drop?.takeIf { it > 0 }?.let { ", down $it" } ?: "") } ?: "seconds left",
            )
            Phase.WarmUp -> PhaseFigure("Warm up", "${m.rounds} rounds after it", "Warming up", null, "Start the rounds when you're ready")
            Phase.Done -> PhaseFigure("All ${m.rounds} rounds done", "Hold the square to end, or keep going", "Done", null, "")
        }
        if (m.phase !is Phase.Rest) HeartStripDark(bpm, r.maxHeartRate)
        if (m.phase is Phase.Rest || m.phase == Phase.Done) RoundPeaks(r.rounds, m.rounds)
        Spacer(Modifier.weight(1f))
        when (m.phase) {
            Phase.WarmUp -> WorkoutButton("Start rounds", Color.White, Color.Black, onSkip, icon = AppIcons.PlayFill, height = 64.dp)
            is Phase.Rest -> Box(Modifier.align(Alignment.CenterHorizontally).width(140.dp)) { WorkoutButton("+10 s", Raised, Color.White, onAddRest, height = 52.dp) }
            is Phase.Work -> if (m.everyMinute) WorkoutButton("Done", Color.White, Color.Black, onSkip, icon = AppIcons.Tick02, height = 64.dp)
            Phase.Done -> Unit
        }
    }
}

/** Skip, Done or Skip rest on the controls row's left, labelled under it. */
@Composable
private fun SkipButton(label: String, onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.height(88.dp), contentAlignment = Alignment.Center) {
            FlatButton(Color.Black.copy(alpha = 0.55f), 64.dp, onClick = onSkip) {
                Icon(AppIcons.PlayFill, label, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Text(label, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, maxLines = 1)
    }
}

/** The rounds as dots: done white, the one under way half lit, those to go faint. */
@Composable
private fun RoundDots(m: IntervalMoment) {
    val done = when (m.phase) {
        is Phase.Work -> m.round - 1
        is Phase.Rest -> m.round - 1
        Phase.Done -> m.rounds
        Phase.WarmUp -> 0
    }
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "$done of ${m.rounds} rounds done" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(m.rounds.coerceAtMost(20)) { i ->
            val alpha = when {
                i < done -> 1f
                i == done && m.phase is Phase.Work -> 0.6f
                else -> 0.25f
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = alpha)))
        }
    }
}

/** A phase: where you are and what's next, its word large, the seconds in the screen's one dotted number, a line under. */
@Composable
private fun PhaseFigure(where: String, next: String, word: String, seconds: String?, line: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(where, color = Color.White, style = GlowType.body, modifier = Modifier.weight(1f))
        Text(next, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(word, color = Color.White, style = GlowType.pageTitle)
        if (seconds != null) DotMatrixText(seconds, dot = DotSize.Live, scale = 1.4f)
        if (line.isNotEmpty()) Text(line, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, textAlign = TextAlign.Center)
    }
}

/** The heart rate on a black strip with the zone's dot and name: the phase's colour is all around it. */
@Composable
private fun HeartStripDark(bpm: Int?, maxHr: Int) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(Color.Black).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val zone = WorkoutRecorder.liveZone(bpm, maxHr)
        Canvas(Modifier.size(8.dp)) { drawCircle(zone?.let { ZONE_COLORS[it] } ?: Color.White) }
        Spacer(Modifier.width(10.dp))
        Text(bpm?.let { "$it bpm" } ?: "No heart rate yet", color = Color.White, style = GlowType.body)
        Spacer(Modifier.weight(1f))
        zone?.let { Text(WorkoutCopy.zoneTitle(it).let { (a, b) -> "$a $b" }, color = Color.White, style = GlowType.secondary, maxLines = 1) }
    }
}

/**
 * Each round's peak heart rate so far as bars in the peak's zone colour, rounds to come as faint stubs, with how far
 * the heart rate came down in each rest.
 */
@Composable
private fun RoundPeaks(rounds: List<AppWorkoutRound>, total: Int) {
    val peaks = rounds.filter { it.workEnd != null }.map { it.peakBpm }
    if (peaks.isEmpty()) return
    val top = peaks.filterNotNull().maxOrNull() ?: return
    val low = (peaks.filterNotNull().minOrNull() ?: top) - 15
    EdgeCard(WorkRed, Modifier.fillMaxWidth()) {
        Text("Peak each round", color = Color.White, style = GlowType.cardTitle, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "Peak heart rate per round: " + peaks.filterNotNull().joinToString(", ") },
        ) {
            val n = total.coerceAtLeast(peaks.size)
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            for (i in 0 until n) {
                val p = peaks.getOrNull(i)
                val h = if (p == null) 4.dp.toPx() else ((p - low).toFloat() / (top - low).coerceAtLeast(1)).coerceIn(0.15f, 1f) * size.height
                val c = if (p == null) Color.White.copy(alpha = 0.12f) else MetricColors.heart
                drawRoundRect(c, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(3.dp.toPx()))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(peaks.filterNotNull().joinToString(", ") + " bpm", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
    }
}

/**
 * A run rep (workouts spec 2.5, board RunIntervals), in place of the Pace card: rep N of M, the metres to go (or the
 * seconds of a time rep) in dots, the rep's pace now, and the reps done with their times. Rest shows its time left.
 */
@Composable
internal fun RepCard(r: WorkoutState.Recording, route: List<Route.Point>, readings: List<Pair<Long, Int>>, bpm: Int?) {
    val now = nowTicking()
    val m = intervalMoment(r, now, bpm, readings, route) ?: return
    val resting = m.phase is Phase.Rest
    EdgeCard(if (resting) RestGreen else MetricColors.steps, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                when (m.phase) {
                    is Phase.Work -> "Rep ${m.round} of ${m.rounds}"
                    is Phase.Rest -> "Rest, rep ${m.round} next"
                    Phase.WarmUp -> "Warm up"
                    Phase.Done -> "All ${m.rounds} reps done"
                },
                color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f),
            )
            val pace = if (m.phase is Phase.Work) org.openstrap.app.ui.workout.Glance.paceClock(Route.currentPace(route)) else null
            if (pace != null) Text("$pace /km now", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            val (value, unit) = when {
                m.metersLeft != null && m.phase is Phase.Work -> "${m.metersLeft.toInt()}" to "m to go"
                m.left != null -> workoutClock(m.left) to if (resting) "rest left" else "left"
                else -> null to ""
            }
            if (value != null) {
                DotMatrixText(value, dot = DotSize.Detail)
                Spacer(Modifier.width(8.dp))
                Text(unit, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body)
            } else Text(if (m.phase == Phase.WarmUp) "Start the reps when you're ready" else "", color = Color.White, style = GlowType.body)
        }
        val done = r.rounds.filter { it.workEnd != null }
        if (done.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                done.takeLast(6).joinToString(", ") { "${it.index + 1}: ${workoutClock(it.workEnd!! - it.workStart)}" },
                color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
            )
        }
    }
}
