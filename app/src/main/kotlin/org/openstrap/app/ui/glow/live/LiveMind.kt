package org.openstrap.app.ui.glow.live

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import org.openstrap.app.ui.glow.LineDots
import org.openstrap.app.ui.glow.PlainLine
import org.openstrap.app.ui.glow.ProgressRing
import org.openstrap.app.ui.glow.activeSecondsState
import org.openstrap.app.ui.glow.workoutClock
import org.openstrap.app.ui.theme.MetricColors
import androidx.compose.foundation.gestures.detectTapGestures
import org.openstrap.app.presentation.WorkoutCopy
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R

/** The screen dims this long after the last touch. */
private const val DIM_AFTER_MS = 10_000L

/** Dimmed, the screen is covered by this much black, and the clock's digits by [CLOCK_VEIL], so the time still reads. */
private const val VEIL = 0.92f
private const val CLOCK_VEIL = 0.5f

/** The lighter spot reaches this far out, in the digits' width, and is at its lightest up to this share of it. */
private const val SPOT_WIDTH = 0.95f
private const val SPOT_LIT = 0.55f

/**
 * Mind and body (workouts spec 2.6, board LiveMind): dark by design. The time against the length, a soft heart line
 * that shows it settling, then Pause and the square to end. No zone colours, no kcal. The screen dims to near black
 * after ten seconds, all but the time, which stays at half, and lights again on a touch, which does nothing else.
 * One soft buzz at the end of the length; the workout keeps recording until you end it. With [split] the heart rate
 * is on the other half of a folded phone, so this half leaves it out, and [compact] (a short half) drops the ring
 * around the time.
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
    // Read only in the veil's draw, so dimming fades the screen without composing it again each frame.
    val veil = animateFloatAsState(if (dim) VEIL else 0f, tween(if (dim) 1500 else 200), label = "dim")
    // Where the clock's digits are, in the screen's root, for the lighter spot in the veil.
    val clock = remember { mutableStateOf(Rect.Zero) }
    val veilAt = remember { mutableStateOf(Offset.Zero) }
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
            MindRing({ active.value }, length, ring = !compact, onClock = { clock.value = it })
            Spacer(Modifier.weight(0.5f))
            if (split) {
                Spacer(Modifier.weight(0.6f))
                return@LiveFrame
            }
            val now = recent.lastOrNull()?.first ?: (System.currentTimeMillis() / 1000)
            val values = remember(recent) { bucketRecent(recent, now) }
            // Lower on a short screen (a phone on its side), so the heart rate line under it still fits beside the buttons.
            // The heart rate as one thin violet line edge to edge, with a dot at now: no axis, no numbers.
            PlainLine(
                values, MindColor.copy(alpha = 0.7f), if (compact) 40.dp else 64.dp, smooth = false, dots = LineDots.Last, stroke = 2.dp,
            )
            val first = values.firstOrNull { it != null }?.toInt()
            Text(
                bpm?.let { b ->
                    first?.minus(b)?.takeIf { it > 0 }?.let { stringResource(R.string.workout_live_mind_heart_down, b, it) }
                        ?: stringResource(R.string.workout_live_mind_heart, b)
                } ?: WorkoutCopy.noHeartRate(r.paused, connected),
                color = Color.White.copy(alpha = 0.8f), style = GlowType.body,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(0.6f))
        }
        // Dimmed, the first touch only lights the screen: the veil takes it.
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { veilAt.value = it.positionInRoot() }
                .drawBehind { if (veil.value > 0f) drawVeil(veil.value, clock.value.translate(-veilAt.value)) }
                .then(if (dim) Modifier.pointerInput(Unit) { detectTapGestures { touched = System.currentTimeMillis() } } else Modifier),
        )
    }
}

/**
 * The time in a thin ring that fills in violet towards the length, "of 45 min" under it; open, the ring stays a
 * faint track. Without the [ring], the time and its line alone.
 */
@Composable
internal fun MindRing(time: () -> Long, length: Long?, ring: Boolean = true, onClock: (Rect) -> Unit = {}) {
    // The time is read by the ring's draw and the clock's text, so the second composes only the text again.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // The strength rest ring's line and track, so the app's rings read as one.
        if (ring) ProgressRing({ if (length != null && length > 0) time().toFloat() / length else 0f }, MindColor, Modifier.size(280.dp))
        MindClock(time, length, onClock)
    }
}

/** The mind ring's time and its line under it, its own scope so only it reads the second. */
@Composable
private fun MindClock(time: () -> Long, length: Long?, onClock: (Rect) -> Unit) {
    val active = time()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            workoutClock(active), color = Color.White.copy(alpha = 0.9f), style = GlowType.mindClock, maxLines = 1,
            modifier = Modifier.onGloballyPositioned { onClock(it.boundsInRoot()) },
        )
        Text(
            length?.let {
                stringResource(if (active >= it) R.string.workout_live_mind_length_up else R.string.workout_live_mind_length, it / 60)
            } ?: stringResource(R.string.workout_live_mind_open),
            color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, textAlign = TextAlign.Center,
        )
    }
}

/**
 * The dimmed screen: [alpha] black everywhere, easing to [CLOCK_VEIL]'s share of it over the [clock]'s digits, a soft
 * oval with no edge to see. Before the clock is placed, the whole screen evenly.
 */
private fun DrawScope.drawVeil(alpha: Float, clock: Rect) {
    val black = Color.Black.copy(alpha = alpha)
    if (clock.isEmpty) {
        drawRect(black)
        return
    }
    val light = Color.Black.copy(alpha = alpha * CLOCK_VEIL / VEIL)
    // A circle stretched sideways to the digits' shape: the gradient is drawn round, under a scale.
    val radius = clock.width * SPOT_WIDTH
    val squash = (clock.height / radius).coerceAtMost(1f)
    scale(1f, squash, pivot = clock.center) {
        drawRect(
            Brush.radialGradient(SPOT_LIT to light, 1f to black, center = clock.center, radius = radius),
            topLeft = Offset(0f, clock.center.y - clock.center.y / squash),
            size = Size(size.width, size.height / squash),
        )
    }
}

/** Mind and body's colour: the evening violet, never a zone's. */
private val MindColor = MetricColors.evening
