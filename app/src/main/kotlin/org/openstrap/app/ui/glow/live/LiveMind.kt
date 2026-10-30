package org.openstrap.app.ui.glow.live

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.glow.GlowLineChart
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.activeSeconds
import org.openstrap.app.ui.glow.workoutClock
import org.openstrap.app.ui.theme.MetricColors
import androidx.compose.foundation.gestures.detectTapGestures

/** The screen dims this long after the last touch. */
private const val DIM_AFTER_MS = 10_000L

/**
 * Mind and body (workouts spec 2.6, board LiveMind): dark by design. The time against the length, a soft heart line
 * that shows it settling, then Pause and the square to end. No zone colours, no kcal. The screen dims to near black
 * after ten seconds and lights again on a touch, which does nothing else. One soft buzz at the end of the length; the
 * workout keeps recording until you end it.
 */
@Composable
internal fun LiveMindScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    controls: LiveControls,
) {
    val connected = connection is ConnectionState.Connected
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    val active = activeSeconds(r)
    val length = (WorkoutTarget.parse(r.workout.target) as? WorkoutTarget.Time)?.seconds
    var touched by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dim by remember { mutableStateOf(false) }
    LaunchedEffect(touched, r.paused) {
        dim = false
        if (r.paused) return@LaunchedEffect
        delay(DIM_AFTER_MS)
        dim = true
    }
    val veil by animateFloatAsState(if (dim) 0.92f else 0f, tween(if (dim) 1500 else 200), label = "dim")
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Seen before anything under it, and never taken from it: a touch lights the screen and still presses.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touched = System.currentTimeMillis()
            }
        },
    ) {
        LiveFrame(r, MindColor.copy(alpha = 0.5f), connected, contentPadding, controls, time = null) {
            Column(
                Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(workoutClock(active), color = Color.White, style = LiveClock)
                Text(
                    length?.let { "of ${it / 60} min" + if (active >= it) ", the length is up" else "" } ?: "Open, no length",
                    color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, textAlign = TextAlign.Center,
                )
            }
            val now = recent.lastOrNull()?.first ?: (System.currentTimeMillis() / 1000)
            val values = remember(recent) { bucketRecent(recent, now) }
            if (values.any { it != null }) Box(Modifier.fillMaxWidth().padding(top = 24.dp).alpha(0.6f)) {
                GlowLineChart(values, Modifier.fillMaxWidth(), height = 120.dp, showAverage = false)
            }
            Text(
                bpm?.let { "$it bpm, settling" } ?: if (r.paused) "Paused" else "Waiting for heart rate",
                color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
        }
        // Dimmed, the first touch only lights the screen: the veil takes it.
        if (veil > 0f) Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = veil))
                .then(if (dim) Modifier.pointerInput(Unit) { detectTapGestures { touched = System.currentTimeMillis() } } else Modifier),
        )
    }
}

/** Mind and body's colour: the evening violet, never a zone's. */
private val MindColor = MetricColors.evening
