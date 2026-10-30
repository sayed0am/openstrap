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
import org.openstrap.app.ui.workout.LiveGlance
import org.openstrap.app.ui.workout.GlanceTint
import org.openstrap.app.ui.workout.GlanceAction
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.theme.MetricColors
import androidx.compose.ui.text.style.TextOverflow

/* ------------------------------------- Rest of the app meanwhile ------------------------------------- */

/** The colour a [LiveGlance] is lit in ([GlanceTint]). */
internal fun glanceColor(g: LiveGlance): Color = when (g.tint) {
    GlanceTint.Zone -> g.zone?.let { ZONE_COLORS[it] } ?: PausedColor
    GlanceTint.Work -> MetricColors.heart
    GlanceTint.Rest -> ZONE_COLORS[1]
    GlanceTint.Mind -> MetricColors.evening
    GlanceTint.Paused -> PausedColor
    GlanceTint.Asking -> MetricColors.warn
}

/** An action's icon on the bar and the hero. */
private fun actionIcon(a: GlanceAction) = when (a) {
    GlanceAction.Pause -> AppIcons.PauseFill
    GlanceAction.Resume, GlanceAction.Open, GlanceAction.KeepGoing -> AppIcons.PlayFill
    GlanceAction.End -> AppIcons.StopFill
    GlanceAction.SetDone -> AppIcons.Tick02
    GlanceAction.StartSet -> AppIcons.PlayFill
    GlanceAction.AddRest -> AppIcons.Plus
    GlanceAction.Skip -> AppIcons.PlayFill
}

/**
 * The workout tucked away (workouts spec 2.10, board CapsuleStates): a 64 dp bar above the tab bar ringed in the
 * glance's colour, a dot pulsing at the heart rate, the glance's two lines, and its next action. Where that is Pause it
 * stays one round tap, beside the bpm in dots; otherwise it's a white pill named for it ("Resume", "Keep going"),
 * with End beside it while the stop question is open. A goal fills a thin bar along the bottom edge. Tapping the bar
 * brings the live screen back.
 */
@Composable
fun WorkoutCapsule(glance: LiveGlance, onClick: () -> Unit, onAction: (GlanceAction) -> Unit, modifier: Modifier = Modifier) {
    val color = glanceColor(glance)
    val shape = RoundedCornerShape(50)
    Box(
        modifier.fillMaxWidth().height(64.dp).shadow(24.dp, shape, ambientColor = color, spotColor = color)
            .clip(shape).background(Raised).border(BorderStroke(1.5.dp, color), shape).pressClickable(onClick, shape),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseDot(color, glance.bpm, 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(glance.line1, color = Color.White, style = MediumBody.copy(fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                glance.line2?.let {
                    Text(it, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (glance.action == GlanceAction.Pause) {
                glance.bpm?.let {
                    DotMatrixText("$it", dot = DotSize.Edge, color = color)
                    Spacer(Modifier.width(12.dp))
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(color).pressClickable({ onAction(GlanceAction.Pause) }, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(AppIcons.PauseFill, "Pause", tint = Color.Black, modifier = Modifier.size(18.dp)) }
            } else {
                glance.second?.let { second ->
                    BarPill(glance.secondLabel ?: "", false, endDot = second == GlanceAction.End) { onAction(second) }
                    Spacer(Modifier.width(8.dp))
                }
                BarPill(glance.shortLabel ?: glance.actionLabel, true, icon = actionIcon(glance.action)) { onAction(glance.action) }
            }
        }
        // A goal fills along the bottom edge, inside the bar's curve.
        glance.progress?.let { p ->
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).padding(horizontal = 28.dp)) {
                drawRect(Color.White.copy(alpha = 0.12f))
                drawRect(color, size = size.copy(width = size.width * p.fraction))
            }
        }
    }
}

/** A pill on the bar: white with its [icon] for the next action ([primary]), else dark, End with its red dot. */
@Composable
private fun BarPill(label: String, primary: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, endDot: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(44.dp).clip(shape).background(if (primary) Color.White else ControlSurface).pressClickable(onClick, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (endDot) {
            Canvas(Modifier.size(8.dp)) { drawCircle(MetricColors.heart) }
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (primary) Color.Black else Color.White, style = MediumBody, maxLines = 1)
    }
}

/**
 * The Today hero while a workout records and its live screen is tucked away (workouts spec 2.10, board HomeLiveRun):
 * a glow card in the glance's colour lit from below; the sport and the total time; the glance's figure in the card's
 * one dotted number; the goal's bar with the goal and what's left under it; three figures; then the buttons: Pause (or
 * the glance's second action) as a dark round button and its action as the white one, "Open run" while it runs.
 * Tapping the card elsewhere opens the live screen.
 */
@Composable
internal fun LiveHero(glance: LiveGlance, sportType: Int, clock: String, onOpen: () -> Unit, onAction: (GlanceAction) -> Unit, modifier: Modifier) {
    val color = glanceColor(glance)
    val style = heroStyle(color, LightAngle.Below).let { s -> glance.bpm?.let { s.copy(motion = GlowMotion.Heartbeat(it)) } ?: s }
    GlowCard(style, modifier, onClick = onOpen) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                Spacer(Modifier.width(8.dp))
                Text(Workouts.sportName(sportType), color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
                Text(clock, color = Color.White, style = GlowType.sentence.copy(fontFeatureSettings = "tnum"), maxLines = 1)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                if (glance.figure != null) DotMatrixText(glance.figure, dot = DotSize.Hero) else WaitingDash(Modifier.padding(vertical = 12.dp))
                glance.unit?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.sentence)
                }
            }
            if (glance.line1.isNotEmpty() && glance.tint in setOf(GlanceTint.Paused, GlanceTint.Asking)) {
                Spacer(Modifier.height(8.dp))
                Text(glance.line1 + (glance.line2?.let { ". $it" } ?: ""), color = Color.White, style = GlowType.body)
            }
            glance.progress?.let { p ->
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.4f))) {
                    Box(Modifier.fillMaxWidth(p.fraction).height(6.dp).clip(RoundedCornerShape(50)).background(Color.White))
                }
            }
            if (glance.goal != null || glance.toGo != null) {
                Spacer(Modifier.height(8.dp))
                Row {
                    glance.goal?.let { Text(it, color = Color.White, style = GlowType.secondary, modifier = Modifier.weight(1f)) }
                    glance.toGo?.let { Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary) }
                }
            }
            if (glance.figures.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    glance.figures.forEach { (value, label) ->
                        Column(Modifier.weight(1f)) {
                            if (value != null) Text(value, color = Color.White, style = GlowType.sentence.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                            else WaitingDash(Modifier.padding(vertical = 8.dp))
                            Text(label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
                        }
                    }
                }
            }
            glance.next?.let { next ->
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(14.dp)) {
                    Text("Next", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
                    Text(next, color = Color.White, style = GlowType.body)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Pause dark and round, or the second action as a dark pill; the next action white beside it.
                val dark = glance.second ?: GlanceAction.Pause.takeIf { glance.action == GlanceAction.Pause }
                dark?.let { a ->
                    val label = glance.secondLabel.takeIf { a == glance.second }
                    Box(
                        Modifier.height(52.dp).then(if (label == null) Modifier.size(52.dp) else Modifier).clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f)).pressClickable({ onAction(a) }, RoundedCornerShape(50))
                            .padding(horizontal = if (label == null) 0.dp else 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (label != null) Text(label, color = Color.White, style = GlowType.body, maxLines = 1)
                        else Icon(actionIcon(a), "Pause", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
                val white = if (glance.action == GlanceAction.Pause) GlanceAction.Open else glance.action
                val label = if (white == GlanceAction.Open) WorkoutCopy.openLabel(sportType) else glance.actionLabel
                PageButton(label, { onAction(white) }, Modifier.weight(1f), primary = true, icon = actionIcon(white))
            }
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
 * gets the modifier that places it; [capsuleBottom] is its distance from the bottom edge. [capsuleHidden] keeps it
 * away while the Today hero shows the workout, so the two are never on screen together (workouts spec 2.10).
 */
@Composable
fun WorkoutMorph(
    minimized: Boolean,
    capsuleBottom: androidx.compose.ui.unit.Dp,
    full: @Composable () -> Unit,
    capsule: @Composable (Modifier) -> Unit,
    capsuleHidden: Boolean = false,
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
            minimized && !capsuleHidden, Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(move) { it / 2 } + fadeIn,
            exit = androidx.compose.animation.slideOutVertically(move) { it / 2 } + fadeOut,
        ) { capsule(Modifier.widthIn(max = 440.dp).padding(start = 16.dp, end = 16.dp, bottom = capsuleBottom)) }
    }
}
