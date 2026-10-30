package org.openstrap.app.ui.glow.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.components.HapticEvent
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.components.rememberHaptics
import org.openstrap.app.ui.glow.*
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.SportIcons
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The live stats row's figures, unchanged from before spec 6.3 ("Stats row unchanged"): the moving time large in the
 * middle and the two figures either side, in Display Light. The only two sizes on the workout pages no role has; a
 * live clock read at arm's length isn't one of spec 2.3's.
 */
internal val LiveClock = GlowType.sentence.copy(fontSize = 46.sp, lineHeight = 50.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum")
internal val LiveFigure = GlowType.sentence.copy(fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum")

/** What every live screen can do to the workout, passed down as one. */
internal class LiveControls(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onEnd: () -> Unit,
    val onMinimize: () -> Unit,
    val onEndAtPrompt: () -> Unit,
    val onKeepGoing: () -> Unit,
)

/**
 * The frame every live screen shares (workouts spec 2.3, 6): minimize, the sport in the workout's [color] and the
 * strap's state along the top, then the moving time large ([time], on the screens built for each sport), the screen's
 * own [content], what any workout may need to say (the strap away, "Looks like you've stopped"), and the controls row
 * at the bottom in the same place on every screen: [lap] on the left (Lap or Skip, or nothing), Pause or Resume in the
 * middle and largest, and the square to hold to end. Back tucks it away.
 *
 * With [paused] given, a paused workout shows it in place of [content], and the controls become End workout (held, so
 * ending still takes a hold) and Resume. [status] adds lines to the strap's on the top right (GPS and its accuracy).
 */
@Composable
internal fun LiveFrame(
    r: WorkoutState.Recording,
    color: Color,
    connected: Boolean,
    contentPadding: PaddingValues,
    controls: LiveControls,
    lap: (@Composable () -> Unit)? = null,
    time: Long? = null,
    /** The total time small on the top right instead of large under it (strength, intervals). */
    smallTime: Boolean = false,
    battery: Int? = null,
    status: List<Pair<String, Color>> = emptyList(),
    paused: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = controls.onMinimize)
    // The screens built for each sport label their buttons; the older one says to hold the square under them.
    val labelled = time != null
    WorkoutPage(color, contentPadding, scroll = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Raised).pressClickable(controls.onMinimize, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.CaretDown, "Minimize", tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Pill(Workouts.sportName(r.workout.sportType), color)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val strap = when {
                    r.paused -> "Paused"
                    !connected -> "Reconnecting"
                    battery != null && labelled -> "Strap $battery%"
                    else -> "Strap"
                }
                if (time != null && smallTime) {
                    // The total, small, where a set or a round's clock is the screen's number.
                    Text(workoutClock(time), color = Color.White, style = GlowType.sentence.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Total", color = Color.White.copy(alpha = 0.65f), style = GlowType.secondary, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Canvas(Modifier.size(6.dp)) { drawCircle(if (connected) TrendGood else TrendBad) }
                    }
                } else (status.takeIf { !r.paused }.orEmpty() + (strap to if (connected) TrendGood else TrendBad)).forEach { (text, dot) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text, color = Color.White.copy(alpha = 0.65f), style = GlowType.secondary, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Canvas(Modifier.size(6.dp)) { drawCircle(dot) }
                    }
                }
            }
        }
        if (time != null && !smallTime) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Plain digits: the screen's one dot-matrix number is further down.
            Text(workoutClock(time), color = Color.White, style = LiveClock)
            Text("Moving time", color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary)
        }
        if (r.paused && paused != null) paused() else content()
        if (!connected && !r.paused) {
            Note("The strap isn't connected. Recording carries on, and the minutes missed are filled from its own history after it syncs.")
        }
        r.endPrompt?.let { at -> StoppedPrompt(r, at, controls.onEndAtPrompt, controls.onKeepGoing) }
        Spacer(Modifier.weight(1f))
        if (r.paused && paused != null) {
            Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 420.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldPill("End workout", Modifier.weight(1f), controls.onEnd)
                WorkoutButton("Resume", Color.White, Color.Black, controls.onResume, Modifier.weight(1f), icon = AppIcons.PlayFill, height = 64.dp)
            }
            Text(
                "Hold End workout to finish. Resume carries on where you stopped.", color = Color.White.copy(alpha = 0.6f),
                style = GlowType.secondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            return@WorkoutPage
        }
        // At most a thumb's reach wide, so on a wide screen (an unfolded or half-folded phone) Lap and End stay by Pause.
        Row(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val pauseLabel = if (r.paused) "Resume" else "Pause"
            // Pause stays centred with or without the left button.
            // The left button labels itself ([LapButton]).
            if (lap != null) lap() else Spacer(Modifier.size(64.dp))
            Spacer(Modifier.weight(1f))
            Labelled(pauseLabel, labelled) {
                FlatButton(Color.White, 88.dp, onClick = if (r.paused) controls.onResume else controls.onPause) {
                    Icon(if (r.paused) AppIcons.PlayFill else AppIcons.PauseFill, pauseLabel, tint = Color.Black, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Labelled("Hold to end", labelled) { HoldToEnd(controls.onEnd) }
        }
        if (!labelled) Text(
            "Hold the square to end", color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        // A little room under the controls, so they sit up off the screen's edge.
        Spacer(Modifier.height(20.dp))
    }
}

/** A control on the controls row, with its [label] under it on the screens that label them ([show]). */
@Composable
private fun Labelled(label: String, show: Boolean, button: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Every button sits in Pause's height, so the row's buttons stay centred on one line and the labels on another.
        Box(Modifier.height(88.dp), contentAlignment = Alignment.Center) { button() }
        if (show) Text(label, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, maxLines = 1)
    }
}

/** The controls row's left button: Lap, which marks a lap, with its label on the labelled screens. */
@Composable
internal fun LapButton(onLap: () -> Unit, label: Boolean = false) = Labelled("Lap", label) {
    FlatButton(Raised, 64.dp, onClick = onLap) {
        Icon(SportIcons.Lap, "Lap", tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * End workout on the paused screen: a pill that ends only once held for [HOLD_MS], filling with red as it's held, so
 * a pocket still can't end a workout.
 */
@Composable
private fun HoldPill(label: String, modifier: Modifier, onEnd: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val haptics = rememberHaptics()
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Row(
        modifier.height(64.dp).clip(shape).background(Raised)
            .drawBehind { drawRect(EndRed.copy(alpha = 0.35f), size = size.copy(width = size.width * progress.value)) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    coroutineScope {
                        val hold = launch {
                            progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                            haptics.perform(HapticEvent.WorkoutEnd)
                            onEnd()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            hold.cancel()
                            progress.animateTo(0f, tween(200))
                        }
                    }
                })
            }
            .semantics { contentDescription = "Hold to end the workout" },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp)) { drawCircle(EndRed) }
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, style = GlowType.body, maxLines = 1)
    }
}

private fun clock(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/** Two small dots for the live views, the current one white; tapping one goes there (the map keeps its own drags). */
@Composable
internal fun PageDots(current: Int, count: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier.size(28.dp).clip(CircleShape).pressClickable({ onSelect(i) }, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == current) 1f else 0.3f)))
            }
        }
    }
}

@Composable
internal fun LiveStat(label: String, modifier: Modifier, value: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        value()
        Text(label, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary)
    }
}

/** The thin rule between the live figures. */
@Composable
internal fun StatRule() = Box(Modifier.padding(vertical = 6.dp).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.12f)))

/** Avg and Max in the same face and size as the moving time beside them, or the dim dash at the same height. */
@Composable
internal fun LiveValue(text: String?) =
    if (text == null) WaitingDash(Modifier.padding(vertical = 13.5.dp))
    else Text(text, color = Color.White, style = LiveFigure)

/**
 * A round button in the glow language: a small glow card lit from its rim in [color], its icon on the dark core.
 * [onClick] null leaves the touch handling to [modifier] (the end square's hold).
 */
@Composable
internal fun FlatButton(container: Color, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    // Flat, like the app's Start and Done: the live screen's card already carries the glow.
    Box(
        modifier.size(size).clip(CircleShape).background(container)
            .then(if (onClick != null) Modifier.pressClickable(onClick, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** "Looks like you've stopped": end where the wearer stopped, or keep going. The strap has buzzed already. */
@Composable
internal fun StoppedPrompt(r: WorkoutState.Recording, at: Long, onEnd: () -> Unit, onKeep: () -> Unit) {
    QuietPanel {
        PanelTitle(if (r.inCar) "Looks like you're in a car" else "Looks like you've stopped")
        Note(
            if (r.inCar) "You last moved at ${clock(at)}. End the ${Workouts.sportName(r.workout.sportType).lowercase()} there, so the drive isn't counted?"
            else "Your heart rate has been back near resting since ${clock(at)}. End the ${Workouts.sportName(r.workout.sportType).lowercase()} at ${clock(at)}?",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { WorkoutButton("End at ${clock(at)}", Color.White, Color.Black, onEnd) }
            Box(Modifier.weight(1f)) { WorkoutButton("Keep going", Raised, Color.White, onKeep) }
        }
    }
}

/** The end square needs a one-second hold: a ring fills around it, and letting go early cancels it. */
@Composable
internal fun HoldToEnd(onEnd: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val haptics = rememberHaptics()
    Box(contentAlignment = Alignment.Center) {
        FlatButton(
            Raised, 64.dp,
            Modifier.pointerInput(Unit) {
                detectTapGestures(onPress = {
                    coroutineScope {
                        val hold = launch {
                            progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                            haptics.perform(HapticEvent.WorkoutEnd)
                            onEnd()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            hold.cancel()
                            progress.animateTo(0f, tween(200))
                        }
                    }
                })
            },
        ) {
            Icon(AppIcons.StopFill, "Hold to end the workout", tint = EndRed, modifier = Modifier.size(20.dp))
        }
        Canvas(Modifier.size(64.dp).padding(2.dp)) {
            if (progress.value > 0f) drawArc(EndRed, -90f, 360f * progress.value, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

private val EndRed = MetricColors.heart
private const val HOLD_MS = 2000
