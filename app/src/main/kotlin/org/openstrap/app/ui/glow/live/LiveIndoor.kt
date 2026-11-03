package org.openstrap.app.ui.glow.live

import org.openstrap.app.presentation.Units
import androidx.compose.foundation.layout.Arrangement
import org.openstrap.app.ui.glow.draw.chart
import org.openstrap.app.ui.glow.draw.GlowMotion
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.GlowCard
import org.openstrap.app.presentation.WorkoutCopy
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
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
import org.openstrap.app.ui.glow.activeSecondsState
import org.openstrap.app.ui.glow.workoutColor
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.workout.ZoneSeconds
import org.openstrap.app.ui.dashboard.LocalHeartZones

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
    zoneSeconds: ZoneSeconds,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    controls: LiveControls,
    onLap: () -> Unit,
    kcal: WorkoutRecorder.Kcal?,
    /** Split across a fold: the heart card is on the other half, so this one keeps the time in zones. */
    split: Boolean = false,
    /** A short screen: the total small on top, so the controls keep their labels, and the heart card on one row. */
    compact: Boolean = false,
) {
    val heartZones = LocalHeartZones.current
    val connected = connection is ConnectionState.Connected
    val color = workoutColor(r, liveHeartRate, connected, heartZones)
    ZoneTick(liveHeartRate.takeIf { connected && !r.paused }, r)
    val active = activeSecondsState(r)
    val figures = listOfNotNull(
        // "2.14 km from steps", or metres under a kilometre.
        r.workout.distanceMeters?.takeIf { it >= Units.SHOWN_FROM_METERS }?.let { m ->
            val km = m >= Units.METERS_PER_KM
            (if (km) Units.km(m, Units.Precision.Workout) else Units.count(m.toLong())) to
                (if (km) "km" else "m") + if (r.workout.distanceFromSteps) " from steps" else ""
        },
        stats?.average?.toString() to "Avg bpm",
        kcal?.active?.toInt()?.toString() to "Active kcal",
    )
    LiveFrame(
        r, color, connected, contentPadding, controls,
        lap = if (!r.paused) ({ LapButton(onLap, label = true) }) else null,
        time = { active.value }, smallTime = compact, battery = (connection as? ConnectionState.Connected)?.batteryPercent,
        paused = { LivePaused(r, liveHeartRate.takeIf { connected }, recent, figures) },
    ) {
        // Time reps on the treadmill side: the rep above the heart.
        if (r.structure is org.openstrap.app.data.Structure.Reps) RepCard(r, emptyList(), recent, liveHeartRate.takeIf { connected && !r.paused })
        val reading = liveHeartRate.takeIf { connected && !r.paused }
        if (split) ZoneTimeBar(zoneSeconds, WorkoutRecorder.liveZone(reading, r.maxHeartRate, heartZones))
        // A whole screen this short is a phone on its side: the arc can't fit beside the controls, the number can.
        else if (compact) CompactHeartCard(r, liveHeartRate, zoneSeconds, connected, { active.value }, kcal?.active)
        else WorkoutHeartCard(
            r, liveHeartRate, recent, zoneSeconds, connected, { active.value }, Modifier.fillMaxWidth(),
            kcal = kcal?.active, zoneBar = true,
        )
        LiveFigures(figures, Modifier.padding(top = 4.dp))
    }
}

/**
 * The time in each zone as one bar split into the five zones' colours, each as wide as its share; the zone you're in
 * is white so it stands out against its neighbours, and an empty zone keeps a sliver so the bar always shows all five.
 */
@Composable
internal fun ZoneTimeBar(zoneSeconds: ZoneSeconds, current: Int?) {
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

/**
 * The live heart card on a short screen (a phone on its side): the bpm in dots with the zone beside it, the time in
 * each zone as a bar, and the card's notes, lit as the full card is ([WorkoutHeartCard]), without the arc and the
 * last ten minutes that need its height.
 */
@Composable
private fun CompactHeartCard(
    r: WorkoutState.Recording, liveHeartRate: Int?, zoneSeconds: ZoneSeconds, connected: Boolean, active: () -> Long, kcal: Float?,
) {
    val heartZones = LocalHeartZones.current
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    val zone = WorkoutRecorder.liveZone(bpm, r.maxHeartRate, heartZones)
    val style = GlowStyle.chart(workoutColor(r, liveHeartRate, connected, heartZones)).copy(
        motion = if (bpm != null) GlowMotion.Heartbeat(bpm) else GlowMotion.Still,
        lightLevel = WorkoutCopy.zoneLightLevel(zone),
        outline = r.paused,
    )
    GlowCard(style, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (bpm != null) {
                    DotMatrixText("$bpm", dot = DotSize.Detail)
                    Spacer(Modifier.width(8.dp))
                    Text("bpm", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body)
                } else Text(WorkoutCopy.noHeartRate(r.paused, connected), color = Color.White, style = GlowType.sentence)
                Spacer(Modifier.weight(1f))
                Text(
                    zone?.let { WorkoutCopy.zoneLine(it) } ?: if (r.paused) "No zone while paused" else "No zone yet",
                    color = Color.White, style = GlowType.secondary, maxLines = 1,
                )
            }
            ZoneTimeBar(zoneSeconds, zone)
            HeartNotes(r, zone, zoneSeconds, active, kcal)
        }
    }
}
