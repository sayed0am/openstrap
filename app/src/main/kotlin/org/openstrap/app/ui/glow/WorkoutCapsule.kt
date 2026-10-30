package org.openstrap.app.ui.glow

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.workout.WorkoutCopy

/* ------------------------------------- Rest of the app meanwhile ------------------------------------- */

/**
 * The workout tucked away: a bar above the tab bar ringed in the zone's colour, with a dot pulsing at the heart
 * rate, the sport and time (and the active kcal after it when there's room), the zone, the bpm and Pause. Tap it to
 * bring the live screen back.
 */
@Composable
fun WorkoutCapsule(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    connected: Boolean,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    kcal: WorkoutRecorder.Kcal? = null,
) {
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    val zone = WorkoutRecorder.liveZone(bpm, r.maxHeartRate)
    val color = workoutColor(r, liveHeartRate, connected)
    val active = activeSeconds(r)
    val shape = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().height(64.dp).shadow(24.dp, shape, ambientColor = color, spotColor = color)
            .clip(shape).background(Raised).border(BorderStroke(1.5.dp, color), shape)
            .pressClickable(onClick, shape).padding(start = 18.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(color, bpm, 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AfterIfRoom(
                { Text("${Workouts.sportName(r.workout.sportType)}, ${workoutClock(active)}", color = Color.White, style = MediumBody, maxLines = 1) },
                WorkoutCopy.kcalText(kcal?.active)?.let { k ->
                    { Text(k, color = Color.White.copy(alpha = 0.6f), style = MediumBody.copy(fontFeatureSettings = "tnum"), maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
                },
            )
            Text(
                when {
                    r.endPrompt != null -> "Stopped? Tap to answer"
                    r.paused -> "Paused"
                    zone != null -> WorkoutCopy.zoneTitle(zone).let { (name, word) -> "$name $word" }
                    connected -> "Waiting for heart rate"
                    else -> "Strap reconnecting"
                },
                color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, maxLines = 1,
            )
        }
        if (bpm != null) {
            DotMatrixText("$bpm", dot = DotSize.Edge, color = color)
            Spacer(Modifier.width(12.dp))
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(color).pressClickable(if (r.paused) onResume else onPause, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (r.paused) AppIcons.PlayFill else AppIcons.PauseFill, if (r.paused) "Resume" else "Pause", tint = Color.Black, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * [first], then [after] beside it on the same line only if all of it fits in the width left; otherwise
 * [after] is left out rather than cut short.
 */
@Composable
private fun AfterIfRoom(first: @Composable () -> Unit, after: (@Composable () -> Unit)?) {
    androidx.compose.ui.layout.Layout(content = { first(); after?.invoke() }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0)
        val a = measurables[0].measure(loose)
        val b = measurables.getOrNull(1)?.measure(loose.copy(maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
            ?.takeIf { a.width + it.width <= constraints.maxWidth }
        val height = maxOf(a.height, b?.height ?: 0)
        layout((a.width + (b?.width ?: 0)).coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            a.placeRelative(0, 0)
            b?.placeRelative(a.width, 0)
        }
    }
}

/** A dot in [color] that beats at [bpm]; still without one. */
@Composable
fun PulseDot(color: Color, bpm: Int?, size: Dp) {
    val beat = if (bpm != null) {
        val period = (60_000 / bpm).coerceIn(250, 2000)
        rememberInfiniteTransition(label = "beat").animateFloat(
            1f, 1f,
            infiniteRepeatable(keyframes { durationMillis = period; 1.5f at period * 15 / 100; 1f at period * 40 / 100 }, RepeatMode.Restart),
            label = "dot",
        ).value
    } else 1f
    Canvas(Modifier.size(size).scale(beat)) { drawCircle(color) }
}

/**
 * The live screen tucking away into its capsule above the tab bar, and back, in the app's screen motion: the screen
 * drops a quarter of its height and fades while the capsule rises a little and fades in, and the reverse. [capsule]
 * gets the modifier that places it; [capsuleBottom] is its distance from the bottom edge.
 */
@Composable
fun WorkoutMorph(
    minimized: Boolean,
    capsuleBottom: androidx.compose.ui.unit.Dp,
    full: @Composable () -> Unit,
    capsule: @Composable (Modifier) -> Unit,
) {
    val swift = org.openstrap.app.ui.components.Motion.SWIFT
    val move = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(swift, easing = org.openstrap.app.ui.components.Motion.easing)
    val fadeIn = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(swift))
    val fadeOut = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(org.openstrap.app.ui.components.Motion.QUICK))
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            !minimized, Modifier.fillMaxSize(),
            enter = androidx.compose.animation.slideInVertically(move) { it / 4 } + fadeIn,
            exit = androidx.compose.animation.slideOutVertically(move) { it / 4 } + fadeOut,
        ) { Box(Modifier.fillMaxSize().background(Color.Black)) { full() } }
        androidx.compose.animation.AnimatedVisibility(
            minimized, Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(move) { it / 2 } + fadeIn,
            exit = androidx.compose.animation.slideOutVertically(move) { it / 2 } + fadeOut,
        ) { capsule(Modifier.widthIn(max = 440.dp).padding(start = 16.dp, end = 16.dp, bottom = capsuleBottom)) }
    }
}

/**
 * Where Today's Start workout pill stands while a workout records, in the same white pill: running, the sport and its
 * clock with a live dot, opening the live screen; paused, "Resume" with a play icon, resuming at once.
 */
@Composable
fun WorkoutShortcut(r: WorkoutState.Recording, liveHeartRate: Int?, onOpen: () -> Unit, onResume: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val sport = Workouts.sportName(r.workout.sportType)
    val clock = workoutClock(activeSeconds(r))
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(Color.White).pressClickable(if (r.paused) onResume else onOpen, shape),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (r.paused) Icon(AppIcons.PlayFill, null, tint = Color.Black, modifier = Modifier.size(16.dp))
        else PulseDot(Color.Black, liveHeartRate, 10.dp)
        Spacer(Modifier.width(10.dp))
        Text(if (r.paused) "Resume ${sport.lowercase()}" else sport, color = Color.Black, style = MediumBody)
        Spacer(Modifier.width(8.dp))
        Text(clock, color = Color.Black, style = MediumBody.copy(fontFeatureSettings = "tnum"))
    }
}
