package org.openstrap.app.ui.glow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.dashboard.PARTIAL_DAY_INFO
import org.openstrap.app.ui.dashboard.PARTIAL_DAY_ROWS
import org.openstrap.app.ui.dashboard.PairStep
import org.openstrap.app.ui.dashboard.Wear
import org.openstrap.app.ui.dashboard.partialLine
import org.openstrap.app.ui.theme.MetricColors

/*
 * The pieces of the data states (spec 4.3): the pairing stage on Today's hero, and the partial day sheet a card's
 * "Partial day" tag opens.
 */

/**
 * The pairing stage (E3): this phone, a line across, and the strap, lit, on the right as on the Strap page. The line
 * is always a solid 1 dp hairline (spec 2.7), and each [step] moves in its own way: still while it waits; ripples out of
 * the phone while it searches; the strap answering with a pulse once it's in reach; sparks running along the line while
 * they connect; the line lighting up from the phone to the strap with a flash at the pod when connected; and the line
 * broken in the middle, amber, when it couldn't.
 */
@Composable
internal fun PairingStage(step: PairStep, modifier: Modifier = Modifier) {
    // The cycle runs only for the steps that move with it, so a stage that waits or failed costs no frames. It's read
    // only in draw, so a running cycle redraws the stage without composing it again.
    val moving = step == PairStep.Searching || step == PairStep.Found || step == PairStep.Connecting
    val cycle: State<Float> = if (moving) {
        rememberInfiniteTransition(label = "pairing").animateFloat(
            0f, 1f, infiniteRepeatable(tween(PAIR_CYCLE_MS, easing = LinearEasing)), label = "pairing cycle",
        )
    } else StillCycle
    // Connected: the line fills from the phone to the strap, then the pod flashes once.
    val fill = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(step) {
        if (step == PairStep.Connected) {
            fill.snapTo(0f); flash.snapTo(0f)
            fill.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            flash.animateTo(1f, tween(650, easing = LinearOutSlowInEasing))
        } else { fill.snapTo(0f); flash.snapTo(0f) }
    }
    // The phone on the left and the strap on the right, where the Strap page shows it. The line runs from the phone's
    // edge to the pod's, not to the drawing's box, which leaves room round the pod for the band. The stage is set in
    // from the text's edges on both sides, so it sits inside the card's words rather than on them.
    Box(modifier.fillMaxWidth().padding(horizontal = StageInset).height(StageHeight)) {
        val strapWidth = StageHeight * 0.8f
        Spacer(Modifier.matchParentSize().drawWithCache {
            val y = size.height / 2
            val gap = 8.dp.toPx()
            val hair = 1.dp.toPx()
            val phoneX = PhoneWidth.toPx() / 2
            val start = PhoneWidth.toPx() + gap
            val sw = strapWidth.toPx()
            val podX = size.width - sw + sw * POD_LEFT + sw / 2
            val end = size.width - sw * (1f - 2 * POD_LEFT) - gap
            val reach = 46.dp.toPx()
            val sparkGlow = 9.dp.toPx()
            // One glow for every spark, centred on the origin and moved to each: a gradient a spark a frame is garbage.
            val sparkBrush = Brush.radialGradient(
                listOf(StrapLight.copy(alpha = 0.55f), StrapLight.copy(alpha = 0f)), Offset.Zero, sparkGlow,
            )
            onDrawBehind {
            val t = cycle.value
            fun ripple(cx: Float, phase: Float, color: Color, from: Float) {
                val r = from + (reach - from) * phase
                drawCircle(color.copy(alpha = color.alpha * (1f - phase) * 0.7f), r, Offset(cx, y), style = Stroke(hair))
            }
            // The glow is the same at every spark (its colour's alpha is set, not scaled). Only the dot fades.
            fun spark(x: Float, alpha: Float = 1f) {
                translate(x, y) { drawCircle(sparkBrush, sparkGlow, Offset.Zero) }
                drawCircle(StrapLight.copy(alpha = alpha), 2.dp.toPx(), Offset(x, y))
            }
            when (step) {
                PairStep.Ready -> drawLine(Color.White.copy(alpha = 0.22f), Offset(start, y), Offset(end, y), hair, StrokeCap.Round)
                PairStep.Searching -> {
                    drawLine(Color.White.copy(alpha = 0.3f), Offset(start, y), Offset(end, y), hair, StrokeCap.Round)
                    // Two ripples out of the phone, half a cycle apart: it's calling.
                    for (k in 0..1) ripple(phoneX, (t + k / 2f) % 1f, Color.White, 14.dp.toPx())
                }
                PairStep.Found -> {
                    drawLine(StrapLight.copy(alpha = 0.6f), Offset(start, y), Offset(end, y), hair, StrokeCap.Round)
                    // The strap answers: one pulse out of the pod.
                    ripple(podX, t, StrapLight, 18.dp.toPx())
                }
                PairStep.Connecting -> {
                    drawLine(StrapLight.copy(alpha = 0.35f), Offset(start, y), Offset(end, y), hair, StrokeCap.Round)
                    // Three sparks running from the phone to the strap, a third of a cycle apart, eased in and out.
                    for (k in 0..2) {
                        val p = (t + k / 3f) % 1f
                        val e = p * p * (3f - 2f * p)
                        spark(start + (end - start) * e, sin(p * PI.toFloat()))
                    }
                }
                PairStep.Connected -> {
                    drawLine(StrapLight.copy(alpha = 0.2f), Offset(start, y), Offset(end, y), hair, StrokeCap.Round)
                    val head = start + (end - start) * fill.value
                    drawLine(StrapLight, Offset(start, y), Offset(head, y), hair, StrokeCap.Round)
                    if (fill.value < 1f) spark(head)
                    if (flash.value in 0.001f..0.999f) {
                        ripple(podX, flash.value, StrapLight, 20.dp.toPx())
                        drawCircle(StrapLight.copy(alpha = 0.35f * (1f - flash.value)), 30.dp.toPx(), Offset(podX, y))
                    }
                }
                PairStep.Failed, PairStep.KeyRejected -> {
                    // Broken in the middle.
                    val mid = (start + end) / 2
                    val broken = 7.dp.toPx()
                    val amber = MetricColors.warn.copy(alpha = 0.75f)
                    drawLine(amber, Offset(start, y), Offset(mid - broken, y), hair, StrokeCap.Round)
                    drawLine(amber, Offset(mid + broken, y), Offset(end, y), hair, StrokeCap.Round)
                }
            }
            }
        })
        PhoneDrawing(Modifier.align(Alignment.CenterStart).size(width = PhoneWidth, height = 52.dp))
        // Shifted out by the room right of the pod, so the pod sits as far from the card's edge as the phone does.
        StrapDrawing(
            StrapLight, lit = true, syncing = false,
            Modifier.align(Alignment.CenterEnd).offset(x = strapWidth * POD_LEFT).size(width = strapWidth, height = StageHeight),
        )
    }
}

/** The cycle's resting value, for the steps that don't move with it. */
private val StillCycle: State<Float> = mutableFloatStateOf(0f)

/** One turn of the pairing stage's moving parts: a ripple, a pulse, a spark's run along the line. */
private const val PAIR_CYCLE_MS = 1600

private val PhoneWidth = 30.dp

/** How far the phone and the pod sit in from the card's text on each side. */
private val StageInset = 16.dp

/** The room either side of the pod in the strap drawing (34 of its 120 grid): where the line meets it, and how far it's shifted. */
private const val POD_LEFT = 34f / 120f

/** The pairing stage's height: the strap drawing's, which sets the hero's. */
private val StageHeight = 92.dp

/** This phone, drawn in line like the strap: its outline and the speaker slot. */
@Composable
private fun PhoneDrawing(modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = 0.8f), Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
            CornerRadius(6.dp.toPx()), style = Stroke(stroke),
        )
        val slot = size.width * 0.3f
        drawLine(
            Color.White.copy(alpha = 0.45f), Offset((size.width - slot) / 2, 5.dp.toPx()), Offset((size.width + slot) / 2, 5.dp.toPx()),
            1.2.dp.toPx(), StrokeCap.Round,
        )
    }
}

/** What a "Partial day" tag opens: what it means, how much wear the figures cover, what it lowers and the rule. */
@Composable
internal fun PartialDaySheet(wear: Wear, isToday: Boolean, onDismiss: () -> Unit) =
    InfoSheet(PARTIAL_DAY_INFO, EdgeNeutral, null, partialLine(wear, isToday), onDismiss = onDismiss, rows = PARTIAL_DAY_ROWS)
