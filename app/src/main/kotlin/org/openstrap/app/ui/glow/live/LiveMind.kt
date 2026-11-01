package org.openstrap.app.ui.glow.live

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.activeSecondsState
import org.openstrap.app.ui.glow.workoutClock
import org.openstrap.app.ui.theme.MetricColors
import androidx.compose.foundation.gestures.detectTapGestures
import org.openstrap.app.ui.workout.WorkoutCopy

/** The screen dims this long after the last touch. */
private const val DIM_AFTER_MS = 10_000L

/**
 * Mind and body (workouts spec 2.6, board LiveMind): dark by design. The time against the length, a soft heart line
 * that shows it settling, then Pause and the square to end. No zone colours, no kcal. The screen dims to near black
 * after ten seconds and lights again on a touch, which does nothing else. One soft buzz at the end of the length; the
 * workout keeps recording until you end it. With [split] the heart rate is on the other half of a folded phone, so this
 * half leaves it out, and [compact] (a short half) drops the ring around the time.
 */
@Composable
internal fun LiveMindScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    controls: LiveControls,
    split: Boolean = false,
    compact: Boolean = false,
) {
    val connected = connection is ConnectionState.Connected
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    val active = activeSecondsState(r)
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
        LiveFrame(r, MindColor.copy(alpha = 0.5f), connected, contentPadding, controls, time = null, quiet = true, fill = true, tapHint = !compact) {
            Spacer(Modifier.weight(0.6f))
            MindRing({ active.value }, length, ring = !compact)
            Spacer(Modifier.weight(0.5f))
            if (split) {
                Spacer(Modifier.weight(0.6f))
                return@LiveFrame
            }
            val now = recent.lastOrNull()?.first ?: (System.currentTimeMillis() / 1000)
            val values = remember(recent) { bucketRecent(recent, now) }
            if (values.count { it != null } >= 2) MindHeartLine(values)
            val first = values.firstOrNull { it != null }?.toInt()
            Text(
                bpm?.let { b ->
                    "Heart rate $b, settling" + (first?.minus(b)?.takeIf { it > 0 }?.let { ", down $it since you started" } ?: "")
                } ?: WorkoutCopy.noHeartRate(r.paused, connected),
                color = Color.White.copy(alpha = 0.8f), style = GlowType.body,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(0.6f))
        }
        // Dimmed, the first touch only lights the screen: the veil takes it.
        if (veil > 0f) Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = veil))
                .then(if (dim) Modifier.pointerInput(Unit) { detectTapGestures { touched = System.currentTimeMillis() } } else Modifier),
        )
    }
}

/**
 * The time in a thin ring that fills in violet towards the length, "of 45 min" under it; open, the ring stays a
 * faint track. Without the [ring], the time and its line alone.
 */
@Composable
private fun MindRing(time: () -> Long, length: Long?, ring: Boolean = true) {
    // Read here, so the second composes the ring again and not the screen.
    val active = time()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (ring) Canvas(Modifier.size(280.dp)) {
            // The strength rest ring's line and track, so the app's rings read as one.
            val stroke = 8.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.1f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (length != null && length > 0) {
                val sweep = 360f * (active.toFloat() / length).coerceIn(0f, 1f)
                drawArc(MindColor, -90f, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(workoutClock(active), color = Color.White.copy(alpha = 0.9f), style = GlowType.mindClock, maxLines = 1)
            Text(
                length?.let { "of ${it / 60} min" + if (active >= it) ", the length is up" else "" } ?: "Open, no length",
                color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, textAlign = TextAlign.Center,
            )
        }
    }
}

/** The heart rate as one thin violet line edge to edge, with a dot at now: no axis, no numbers. */
@Composable
private fun MindHeartLine(values: List<Float?>) {
    val known = values.filterNotNull()
    val lo = known.min() - 4
    val hi = known.max() + 4
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        var last: Offset? = null
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val p = Offset(i * step, size.height * (1f - (v - lo) / (hi - lo)))
            if (last == null) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            last = p
        }
        drawPath(path, MindColor.copy(alpha = 0.7f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        last?.let { drawCircle(MindColor, 4.dp.toPx(), it) }
    }
}

/** Mind and body's colour: the evening violet, never a zone's. */
private val MindColor = MetricColors.evening
