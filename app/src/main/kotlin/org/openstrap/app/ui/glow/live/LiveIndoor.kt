package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.glow.activeSeconds
import org.openstrap.app.ui.glow.workoutColor
import org.openstrap.app.ui.workout.Glance
import org.openstrap.app.ui.workout.ZONE_COLORS

/**
 * Indoor cardio, and a distance sport without GPS (workouts spec 2.3, board Main): one view under the moving time.
 * Heart rate is the hero, on its zone arc with the time in each zone as a bar and the target if one is set; then
 * the distance from steps when there is one, the average heart rate and active kcal.
 */
@Composable
internal fun LiveIndoorScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    stats: WorkoutRecorder.Stats?,
    zoneSeconds: IntArray,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    controls: LiveControls,
    onLap: () -> Unit,
    kcal: WorkoutRecorder.Kcal?,
) {
    val connected = connection is ConnectionState.Connected
    val color = workoutColor(r, liveHeartRate, connected)
    ZoneTick(liveHeartRate.takeIf { connected && !r.paused }, r)
    val active = activeSeconds(r)
    val figures = listOfNotNull(
        // "2.14 km from steps", or metres under a kilometre.
        r.workout.distanceMeters?.takeIf { it >= 10 }?.let { m ->
            (if (m >= 1000) Glance.kmFigure(m) else "${m.toInt()}") to
                (if (m >= 1000) "km" else "m") + if (r.workout.distanceFromSteps) " from steps" else ""
        },
        stats?.average?.toString() to "Avg bpm",
        kcal?.active?.toInt()?.toString() to "Active kcal",
    )
    LiveFrame(
        r, color, connected, contentPadding, controls,
        lap = if (!r.paused) ({ LapButton(onLap, label = true) }) else null,
        time = active, battery = (connection as? ConnectionState.Connected)?.batteryPercent,
        paused = { LivePaused(r, liveHeartRate.takeIf { connected }, recent, figures) },
    ) {
        // Time reps on the treadmill side: the rep above the heart.
        if (r.structure is org.openstrap.app.data.Structure.Reps) RepCard(r, emptyList(), recent, liveHeartRate.takeIf { connected && !r.paused })
        WorkoutHeartCard(r, liveHeartRate, recent, zoneSeconds, connected, active, Modifier.fillMaxWidth(), kcal = kcal?.active, zoneBar = true)
        LiveFigures(figures, Modifier.padding(top = 4.dp))
    }
}

/**
 * The time in each zone as one bar split into the five zones' colours, each as wide as its share; the zone you're in
 * is white so it stands out against its neighbours, and an empty zone keeps a sliver so the bar always shows all five.
 */
@Composable
internal fun ZoneTimeBar(zoneSeconds: IntArray, current: Int?) {
    val total = zoneSeconds.sum().coerceAtLeast(1)
    val described = zoneSeconds.withIndex().joinToString(", ") { (z, s) -> "zone ${z + 1} ${if (s < 60) "none" else "${s / 60} min"}" }
    Row(
        Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = "Time in each zone: $described" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        zoneSeconds.forEachIndexed { z, s ->
            val color = when {
                z == current -> Color.White
                s == 0 -> Color.White.copy(alpha = 0.18f)
                else -> ZONE_COLORS[z]
            }
            Box(Modifier.weight(maxOf(s.toFloat() / total, 0.04f)).height(8.dp).clip(RoundedCornerShape(50)).background(color))
        }
    }
}
