package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openstrap.app.data.Games
import org.openstrap.app.data.Phase
import org.openstrap.app.data.Structure
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.DotSize
import org.openstrap.app.ui.glow.GlowCard
import org.openstrap.app.ui.glow.GlowMotion
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.LightAngle
import org.openstrap.app.ui.glow.WorkoutButton
import org.openstrap.app.ui.glow.activeSeconds
import org.openstrap.app.ui.glow.heroStyle
import org.openstrap.app.ui.glow.workoutClock
import org.openstrap.app.ui.glow.workoutColor
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.workout.StartCopy
import org.openstrap.app.ui.workout.WorkoutCopy
import org.openstrap.app.ui.workout.ZONE_COLORS

/**
 * Games and sports (workouts spec 2.7, board LiveGame), made for the phone in a bag: the period and the time in it,
 * the heart rate in dots on a glow in its zone and the zone named, an effort bar (1 to 5 from the time in zones so
 * far), then active kcal, peak bpm and hard efforts (spells of 10 s or more in zone 5). Half time starts the break
 * early. The strap buzzes at the end of each period and break, so nothing needs the phone. Free play has no periods.
 */
@Composable
internal fun LiveGameScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    readings: List<Pair<Long, Int>>,
    stats: WorkoutRecorder.Stats?,
    zoneSeconds: IntArray,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    controls: LiveControls,
    kcal: WorkoutRecorder.Kcal?,
    onHalfTime: () -> Unit,
) {
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            value = System.currentTimeMillis() / 1000
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    val connected = connection is ConnectionState.Connected
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    ZoneTick(bpm, r)
    val color = workoutColor(r, liveHeartRate, connected)
    val periods = r.structure as? Structure.Periods
    val phase = r.phase(now)
    val active = activeSeconds(r)
    val effort = Games.effort(zoneSeconds)
    val hard = remember(readings.size / 10) { Games.hardEfforts(readings, r.maxHeartRate) }
    val figures = listOf(
        kcal?.active?.toInt()?.toString() to "Active kcal",
        stats?.max?.toString() to "Peak bpm",
        hard.toString() to "Hard efforts",
    )
    LiveFrame(
        r, color, connected, contentPadding, controls, time = active, smallTime = periods != null,
        paused = { LivePaused(r, liveHeartRate.takeIf { connected }, readings, figures) },
    ) {
        if (periods != null && phase != null) {
            val (title, clock) = when (phase) {
                is Phase.Work -> StartCopy.periodName(phase.round, periods.rounds) to
                    workoutClock((WorkoutRecorder.activeSeconds(r.workout, now) - WorkoutRecorder.activeSeconds(r.workout, phase.start)).coerceAtLeast(0))
                is Phase.Rest -> StartCopy.breakName(periods.rounds) to
                    (workoutClock((phase.endsAt - WorkoutRecorder.activeSeconds(r.workout, now)).coerceAtLeast(0)) + " left")
                else -> "Full time" to workoutClock(active)
            }
            Row(Modifier.fillMaxWidth()) {
                Text(title, color = Color.White, style = GlowType.pageTitle, modifier = Modifier.weight(1f))
                Text(clock, color = Color.White, style = GlowType.pageTitle.copy(fontFeatureSettings = "tnum"))
            }
        }
        val style = heroStyle(color, LightAngle.Below).let { s -> bpm?.let { s.copy(motion = GlowMotion.Heartbeat(it)) } ?: s }
        GlowCard(style, Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                    if (bpm != null) DotMatrixText("$bpm", dot = DotSize.Detail) else Text("Waiting for heart rate", color = Color.White, style = GlowType.sentence)
                    if (bpm != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("bpm", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body)
                    }
                    Spacer(Modifier.weight(1f))
                    WorkoutRecorder.liveZone(bpm, r.maxHeartRate)?.let { z ->
                        Text(WorkoutCopy.zoneTitle(z).let { (a, b) -> "$a $b" }, color = Color.White, style = GlowType.secondary)
                    }
                }
                EffortBar(effort)
            }
        }
        LiveFigures(figures)
        Spacer(Modifier.weight(1f))
        if (periods != null && phase is Phase.Work) WorkoutButton(StartCopy.breakName(periods.rounds), Color.White, Color.Black, onHalfTime, icon = AppIcons.PauseFill, height = 64.dp)
        if (periods != null && phase is Phase.Rest) WorkoutButton("Start ${StartCopy.periodName(phase.round + 1, periods.rounds).lowercase()}", Color.White, Color.Black, onHalfTime, icon = AppIcons.PlayFill, height = 64.dp)
    }
}

/** Effort 1 to 5 as five steps, lit up to it in the zones' colours, "Effort 3 of 5" beside them. */
@Composable
private fun EffortBar(effort: Int?) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).semantics { contentDescription = effort?.let { "Effort $it of 5" } ?: "No effort yet" },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(5) { i ->
                val on = effort != null && i < effort
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50)).background(if (on) ZONE_COLORS[i] else Color.Black.copy(alpha = 0.35f)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(effort?.let { "Effort $it of 5" } ?: "Effort", color = Color.White, style = GlowType.secondary)
    }
}
