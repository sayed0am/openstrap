package org.openstrap.app.ui.glow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.openstrap.app.data.db.SleepSession

/** The trim frame's sides, the handles. The strip is inset by as much so either side can reach its end. */
internal val TrimSide = 12.dp

/** The track's height, the frame's and the bars'. */
private val TrackHeight = 64.dp

/** Under the track, room for the line that marks what was recorded or detected. */
private val MarkRoom = 10.dp

/** The trim bar's test tag, so a test can drag along it. */
internal const val TRIM_BAR_TAG = "trimBar"

/**
 * The rules of the one trim bar that workouts, sleep and naps share ([TrimBar]): where its handles can go and how what
 * lies under it is shaded.
 */
internal object Trim {
    /** The handles never come closer than this. */
    const val MIN_SECONDS = 60L

    /** How far a sleep's or nap's handles reach past what the strap detected, either side. */
    const val SLEEP_REACH_SECONDS = 2 * 3600L

    /** A sleep's or nap's handles move a whole minute at a time. */
    const val SLEEP_STEP_SECONDS = 60L

    /**
     * [time] on the nearest [step] (seconds). A moment in [keep] on that same step is kept to the second instead, so
     * a handle dragged back onto the strap's own time leaves it the strap's. A step of 1 leaves [time] as it is.
     */
    fun snap(time: Long, step: Long, keep: List<Long> = emptyList()): Long {
        if (step <= 1) return time
        val snapped = Math.floorDiv(time + step / 2, step) * step
        return keep.firstOrNull { Math.floorDiv(it, step) * step == snapped } ?: snapped
    }

    /**
     * Where the handles of a sleep or nap can go: what the strap [detected] with [SLEEP_REACH_SECONDS] either side,
     * widened to take in times the user gave it before ([shownStart], [shownEnd]). It stops at [now] and at the
     * [others] (the other sleep and naps as shown) before and after it, so a handle is never dragged into either.
     */
    fun sleepReach(
        detected: SleepSession, shownStart: Long, shownEnd: Long, now: Long, others: List<SleepSession> = emptyList(),
    ): LongRange {
        val before = others.filter { it.end <= shownStart }.maxOfOrNull { it.end } ?: Long.MIN_VALUE
        val after = others.filter { it.start >= shownEnd }.minOfOrNull { it.start } ?: Long.MAX_VALUE
        val first = maxOf(minOf(detected.start - SLEEP_REACH_SECONDS, shownStart), before)
        val last = minOf(maxOf(detected.end + SLEEP_REACH_SECONDS, shownEnd), now, after)
        return first..last.coerceAtLeast(first + MIN_SECONDS)
    }

    private const val LEFT_OUT_ALPHA = 0.3f
    private const val CONTEXT_ALPHA = 0.15f

    /** How a stretch of the bar is shaded. */
    enum class Shade(val alpha: Float) {
        /** Inside the kept range: as recorded. */
        Kept(1f),
        /** Recorded or detected, but left out. */
        LeftOut(LEFT_OUT_ALPHA),
        /** Outside what was recorded or detected and left out: the strap's heart rate, there for context. */
        Context(CONTEXT_ALPHA),
    }

    /** The shade of the stretch at [time]: [kept] when it lies inside the handles, else by whether it's in the [core]. */
    fun shade(kept: Boolean, time: Long, core: LongRange): Shade = when {
        kept -> Shade.Kept
        time in core -> Shade.LeftOut
        else -> Shade.Context
    }

    /**
     * The handles after a drag to [at] (a time on the bar), the start one when [start]: on the [step] ([snap], with
     * [keep]), inside [reach], and at least [MIN_SECONDS] from the other.
     */
    fun drag(at: Long, start: Boolean, from: Long, to: Long, reach: LongRange, step: Long, keep: List<Long>): Pair<Long, Long> {
        val t = snap(at, step, keep).coerceIn(reach.first, reach.last)
        return if (start) t.coerceAtMost(to - MIN_SECONDS) to to else from to t.coerceAtLeast(from + MIN_SECONDS)
    }
}

/**
 * A video trimmer, one for workouts, sleep and naps: the heart rate as thin solid bars ([points], evenly spaced across
 * [reach]) in [colorOf]'s colours, inside one rounded frame whose thicker sides are the handles (Snapchat's,
 * Polarsteps'). Drag either side in, or out as far as [reach]. Outside [from] to [to] the bars fade back. What was
 * recorded or detected is the [core]: when the reach goes past it, a line under the track marks it, and the heart rate
 * past it is dimmer still, there for context, until the handles take it in. A workout passes a reach equal to its
 * core, so it only trims. The handles move a [step] at a time, landing on a moment in [keep] to the second. The clocks
 * of the reach's ends sit under it.
 */
// The readings and the moments to keep arrive whole with the workout or the sleep and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
@Composable
internal fun TrimBar(
    points: List<Float?>,
    core: LongRange,
    reach: LongRange,
    from: Long,
    to: Long,
    colorOf: (Float) -> Color?,
    onChange: (from: Long, to: Long) -> Unit,
    modifier: Modifier = Modifier,
    step: Long = 1,
    keep: List<Long> = emptyList(),
) {
    val first = reach.first
    val last = reach.last.coerceAtLeast(first + 1)
    // The drag reads the range as it stands when it starts and keeps its own copy while it moves, so nothing is
    // written back during composition (audit K6).
    val range by rememberUpdatedState(from to to)
    val change by rememberUpdatedState(onChange)
    val known = points.filterNotNull()
    val lo = (known.minOrNull() ?: 60f) - 10f
    val hi = (known.maxOrNull() ?: 180f) + 5f
    val marked = core != reach
    // The clocks sit as far under the bar as the workout's panel spaces its blocks.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(if (marked) TrackHeight + MarkRoom else TrackHeight).testTag(TRIM_BAR_TAG)
                .pointerInput(first, last, step, keep) {
                var start = true
                var f = 0L
                var t = 0L
                val inset = TrimSide.toPx()
                fun timeAt(x: Float) = (first + ((x - inset) / (size.width - 2 * inset) * (last - first)).toLong()).coerceIn(first, last)
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        f = range.first
                        t = range.second
                        val at = timeAt(o.x)
                        start = abs(at - f) <= abs(at - t)
                    },
                ) { c, _ ->
                    val (nf, nt) = Trim.drag(timeAt(c.position.x), start, f, t, first..last, step, keep)
                    f = nf
                    t = nt
                    change(f, t)
                }
            },
        ) {
            val inset = TrimSide.toPx()
            val w = size.width - 2 * inset
            val h = TrackHeight.toPx()
            val edge = 3.dp.toPx()
            fun x(time: Long) = inset + (time - first).toFloat() / (last - first) * w
            drawRoundRect(Raised, Offset(inset, 0f), Size(w, h), CornerRadius(8.dp.toPx()))
            // Solid rounded bars, as tall as the heart rate in that stretch (dots are only for numbers, spec 2.7). A
            // stretch with no reading is left empty.
            val pitch = 5.dp.toPx()
            val bar = 3.dp.toPx()
            val pad = edge + 5.dp.toPx()
            val cols = (w / pitch).toInt().coerceAtLeast(1)
            val fx = x(from)
            val tx = x(to)
            if (points.isNotEmpty()) for (c in 0 until cols) {
                val cx = inset + (c + 0.5f) * (w / cols)
                val i = ((c + 0.5f) / cols * points.size).toInt().coerceIn(0, points.size - 1)
                val v = points[i]
                val color = v?.let(colorOf)
                if (v == null || color == null) continue
                val barH = ((v - lo) / (hi - lo)).coerceIn(0.1f, 1f) * (h - 2 * pad)
                val at = first + ((c + 0.5f) / cols * (last - first)).toLong()
                // Kept by where the column sits on screen, as the frame is drawn, so a bar is never half in.
                val shade = Trim.shade(cx in fx..tx, at, core)
                drawRoundRect(
                    color.copy(alpha = color.alpha * shade.alpha),
                    Offset(cx - bar / 2, h - pad - barH), Size(bar, barH), CornerRadius(bar / 2),
                )
            }
            // The frame: one rounded outline, its sides thick enough to grab, each with a short grip.
            val frame = Path().apply {
                fillType = PathFillType.EvenOdd
                addRoundRect(RoundRect(fx - inset, 0f, tx + inset, h, CornerRadius(10.dp.toPx())))
                addRoundRect(RoundRect(fx, edge, tx, h - edge, CornerRadius(4.dp.toPx())))
            }
            drawPath(frame, Color.White)
            if (marked) {
                val y = h + MarkRoom.toPx() / 2
                val mark = 2.dp.toPx()
                drawLine(
                    Color.White.copy(alpha = 0.35f),
                    Offset(x(core.first.coerceIn(first, last)), y), Offset(x(core.last.coerceIn(first, last)), y),
                    strokeWidth = mark, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            val grip = 2.dp.toPx()
            listOf(fx - inset / 2, tx + inset / 2).forEach { gx ->
                drawRoundRect(
                    Color.Black.copy(alpha = 0.55f), Offset(gx - grip / 2, h / 2 - 8.dp.toPx()), Size(grip, 16.dp.toPx()),
                    CornerRadius(grip / 2),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Note(clock(first))
            Spacer(Modifier.weight(1f))
            Note(clock(last))
        }
    }
}
