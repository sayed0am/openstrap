package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openstrap.app.data.Strength
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.glow.*

/**
 * Strength: the sets as dots against the target, and the one control that matters now: "Set done" during a set;
 * during rest a countdown with "Start set N" and "+30 s"; otherwise "Start set N".
 */
@Composable
internal fun SetsPanel(r: WorkoutState.Recording, color: Color, onStartSet: () -> Unit, onSetDone: () -> Unit, onExtend: () -> Unit) {
    val now by produceState(System.currentTimeMillis() / 1000) { while (true) { value = System.currentTimeMillis() / 1000; delay(1000 - System.currentTimeMillis() % 1000) } }
    val done = r.sets.count { it.end != null }
    val target = r.workout.setsTarget
    QuietPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PanelTitle(
                    when {
                        r.inSet -> "Set ${r.sets.size}, ${workoutClock(now - r.sets.last().start)}"
                        r.resting -> "Resting after set $done, ${workoutClock((r.restUntil!! - now).coerceAtLeast(0))}"
                        done == 0 -> "Ready for set 1"
                        else -> "Rest over. Set ${done + 1} next."
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(maxOf(target ?: 0, done + if (r.inSet) 1 else 0)) { i ->
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (i < done) color else if (i == done && r.inSet) color.copy(alpha = 0.5f) else Raised))
                }
            }
        }
        r.restUntil?.let { until ->
            val start = r.sets.lastOrNull()?.end ?: until
            GlowBar(((now - start).toFloat() / (until - start).coerceAtLeast(1)).coerceIn(0f, 1f), color)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (r.inSet) Box(Modifier.weight(1f)) { WorkoutButton("Set done", Color.White, Color.Black, onSetDone, height = 52.dp) }
            else Box(Modifier.weight(1f)) { WorkoutButton("Start set ${r.sets.size + 1}", Color.White, Color.Black, onStartSet, height = 52.dp) }
            if (r.resting) Box(Modifier.width(96.dp)) { WorkoutButton("+30 s", Raised, Color.White, onExtend, height = 52.dp) }
        }
        if (target != null && done >= target && !r.inSet) Note("All $target sets done. Hold the square to end, or keep going.")
    }
}
