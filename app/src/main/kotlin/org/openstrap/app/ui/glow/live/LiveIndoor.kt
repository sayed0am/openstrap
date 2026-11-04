package org.openstrap.app.ui.glow.live

import org.openstrap.app.presentation.Units
import androidx.compose.foundation.layout.Arrangement
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.HeroValue
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.GlowCard
import org.openstrap.app.ui.glow.Segment
import org.openstrap.app.ui.glow.SegmentBar
import org.openstrap.app.presentation.WorkoutCopy
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R

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
            val label = when {
                !r.workout.distanceFromSteps -> if (km) R.string.workout_live_figure_km else R.string.workout_live_figure_m
                km -> R.string.workout_live_figure_km_from_steps
                else -> R.string.workout_live_figure_m_from_steps
            }
            (if (km) Units.km(m, Units.Precision.Workout) else Units.count(m.toLong())) to stringResource(label)
        },
        stats?.average?.toString() to stringResource(R.string.workout_live_figure_avg_bpm),
        kcal?.active?.toInt()?.toString() to stringResource(R.string.workout_live_figure_active_kcal),
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
    val res = LocalResources.current
    val each = zoneSeconds.withIndex().joinToString(", ") { (z, s) ->
        if (s < 60) res.getString(R.string.workout_live_zone_time_none, z + 1)
        else res.getString(R.string.workout_live_zone_time_minutes, z + 1, s / 60)
    }
    val described = stringResource(R.string.workout_live_zone_times_description, each)
    val segments = zoneSeconds.mapIndexed { z, s ->
        val color = when {
            z == current -> Color.White
            s == 0 -> Color.White.copy(alpha = 0.18f)
            else -> ZONE_COLORS[z]
        }
        Segment(maxOf(s.toFloat() / total, 0.04f), color)
    }
    SegmentBar(segments, Modifier.semantics { contentDescription = described }, height = 8.dp, gap = 3.dp)
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
    val style = liveHeartStyle(workoutColor(r, liveHeartRate, connected, heartZones), bpm, zone, r.paused)
    GlowCard(style, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (bpm != null) HeroValue("$bpm", stringResource(R.string.workout_live_unit_bpm), dot = DotSize.Detail)
                else Text(WorkoutCopy.noHeartRate(r.paused, connected), color = Color.White, style = GlowType.sentence)
                Spacer(Modifier.weight(1f))
                Text(
                    zone?.let { WorkoutCopy.zoneLine(it) } ?: WorkoutCopy.noZone(r.paused),
                    color = Color.White, style = GlowType.secondary, maxLines = 1,
                )
            }
            ZoneTimeBar(zoneSeconds, zone)
            HeartNotes(r, zone, zoneSeconds, active, kcal)
        }
    }
}
