package org.openstrap.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import org.openstrap.app.R
import org.openstrap.app.tr

/** A point on a time axis: x is epoch seconds, y is the value. */
data class ChartPoint(val x: Long, val y: Float)

private val HOUR_FMT = DateTimeFormatter.ofPattern("HH:mm")
private fun hourLabel(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(HOUR_FMT)

@Composable
private fun axisStyle() = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = org.openstrap.app.ui.theme.FunnelSans)

private fun DrawScope.label(m: TextMeasurer, text: String, style: TextStyle, x: Float, y: Float, alignEnd: Boolean = false, center: Boolean = false) {
    val layout = m.measure(text, style)
    val dx = when {
        alignEnd -> -layout.size.width.toFloat()
        center -> -layout.size.width / 2f
        else -> 0f
    }
    drawText(layout, topLeft = Offset((x + dx).coerceIn(0f, size.width - layout.size.width), y))
}

/**
 * Scrubbing for every chart: drag sideways to read values straight away, or press and hold first (the
 * older gesture). Vertical drags still scroll the list and a plain tap still reaches the card.
 */
internal fun Modifier.scrub(key: Any?, onScrub: (Float?) -> Unit): Modifier = this
    .pointerInput(key) {
        detectHorizontalDragGestures(
            onDragStart = { onScrub(it.x) },
            onDragEnd = { onScrub(null) },
            onDragCancel = { onScrub(null) },
        ) { change, _ -> change.consume(); onScrub(change.position.x) }
    }
    .pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onScrub(it.x) },
            onDrag = { change, _ -> change.consume(); onScrub(change.position.x) },
            onDragEnd = { onScrub(null) },
            onDragCancel = { onScrub(null) },
        )
    }

/** "62 bpm", "97%", "36.4°C", or the bare value when there's no unit. */
fun withUnit(value: String, unit: String) = when {
    unit.isEmpty() -> value
    unit.startsWith("%") || unit.startsWith("°") -> value + unit
    else -> "$value $unit"
}

/** The touch readout: a pill with [text] centred over [x], above [anchorY] when there's room, else below. */
private fun DrawScope.readout(m: TextMeasurer, text: String, style: TextStyle, bubble: Color, x: Float, anchorY: Float) {
    val layout = m.measure(text, style)
    val bw = layout.size.width + 16.dp.toPx()
    val bh = layout.size.height + 8.dp.toPx()
    val bx = (x - bw / 2).coerceIn(0f, (size.width - bw).coerceAtLeast(0f))
    val by = (anchorY - bh - 12.dp.toPx()).let { if (it < 0f) anchorY + 12.dp.toPx() else it }
    drawRoundRect(bubble, Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2))
    drawText(layout, topLeft = Offset(bx + 8.dp.toPx(), by + 4.dp.toPx()))
}

@Composable
private fun readoutStyle() = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontFamily = org.openstrap.app.ui.theme.FunnelDisplay)

/** A coloured stretch of time drawn as a strip under a [LineChart] (e.g. what Charge was doing). */
data class ChartSegment(val from: Long, val to: Long, val color: Color)

/**
 * Time-series chart in the Oura/WHOOP style: a smooth (monotone, never overshooting) line over a soft
 * gradient, a faint solid grid with values on the right, a haloed dot at the latest reading and a
 * labelled average. Drag across it to read any point (see [scrub]). Gaps longer than [gapSeconds] break the line so
 * off-wrist periods aren't bridged. [segments] adds a Garmin-style state strip under the plot.
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    color: Color,
    xRange: LongRange,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    gapSeconds: Long = 15 * 60,
    showAverage: Boolean = true,
    markMin: Boolean = false,
    band: LongRange? = null,
    bandColor: Color = Color.Unspecified,
    /** 0..1: how much of the line is drawn, left to right (for the focus animation). */
    reveal: Float = 1f,
    /** A fixed value axis (e.g. 0..100 for scores); null fits the data. */
    yRange: ClosedFloatingPointRange<Float>? = null,
    segments: List<ChartSegment> = emptyList(),
    /** Formats a value for the average label and the touch readout. */
    format: (Float) -> String = { Math.round(it).toString() },
    /** Shown after the value in the touch readout, e.g. "bpm" or "%". */
    unit: String = "",
    /** Colours the line and fill by value instead of [color]: (value, colour) stops, lowest first. */
    valueGradient: List<Pair<Float, Color>>? = null,
    /** Labels the time axis and the touch readout; clock times by default. */
    xLabel: (Long) -> String = ::hourLabel,
    /** A horizontal band for the usual range of values (your normal), drawn behind the line. */
    normal: ClosedFloatingPointRange<Float>? = null,
) {
    val measurer = rememberTextMeasurer()
    val style = axisStyle()
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val onSurface = MaterialTheme.colorScheme.onSurface
    val bubble = MaterialTheme.colorScheme.surfaceContainerHighest
    val readoutStyle = readoutStyle()
    val bandFill = if (bandColor == Color.Unspecified) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f) else bandColor
    var touchX by remember { mutableStateOf<Float?>(null) }
    val stripH = if (segments.isEmpty()) 0.dp else 8.dp

    Canvas(
        modifier.fillMaxWidth().height(height + stripH)
            .scrub(points) { touchX = it },
    ) {
        if (xRange.last <= xRange.first) return@Canvas
        val axisW = 28.dp.toPx()
        val axisH = 18.dp.toPx()
        val strip = stripH.toPx()
        val w = size.width - axisW
        val h = size.height - axisH - strip
        val minV = yRange?.start ?: listOfNotNull(points.minOfOrNull { it.y }, normal?.start).minOrNull() ?: 0f
        val maxV = yRange?.endInclusive ?: listOfNotNull(points.maxOfOrNull { it.y }, normal?.endInclusive).maxOrNull() ?: 1f
        val step = niceStep((maxV - minV).coerceAtLeast(4f) / (if (yRange != null) 4f else 3f))
        val bottom = floor(minV / step) * step
        val top = (ceil(maxV / step) * step).coerceAtLeast(bottom + step)
        fun xOf(t: Long) = (t - xRange.first).toFloat() / (xRange.last - xRange.first) * w
        fun yOf(v: Float) = h - (v - bottom) / (top - bottom) * h

        normal?.let {
            val y0 = yOf(it.endInclusive).coerceIn(0f, h)
            val y1 = yOf(it.start).coerceIn(0f, h)
            if (y1 > y0) drawRoundRect(color.copy(alpha = 0.10f), Offset(0f, y0), Size(w, y1 - y0), CornerRadius(4.dp.toPx()))
        }
        band?.let {
            val x0 = xOf(it.first).coerceIn(0f, w)
            val x1 = xOf(it.last).coerceIn(0f, w)
            if (x1 > x0) drawRoundRect(bandFill, Offset(x0, 0f), Size(x1 - x0, h), CornerRadius(6.dp.toPx()))
        }

        var v = bottom
        while (v <= top + 0.001f) {
            val y = yOf(v)
            drawLine(grid, Offset(0f, y), Offset(w, y), 1.dp.toPx())
            label(measurer, v.toInt().toString(), style, size.width, y - 7.sp.toPx(), alignEnd = true)
            v += step
        }
        // Inclusive ranges like 00:00..23:59:59 label as 00:00, 06:00, … rather than 05:59.
        val span = (xRange.last - xRange.first + 1)
        for (i in 0..4) {
            val t = (xRange.first + span * i / 4).coerceAtMost(xRange.last)
            label(measurer, xLabel(t), style, xOf(t), h + strip + 4.dp.toPx(), center = i in 1..3, alignEnd = i == 4)
        }

        // State strip: rounded runs of colour under the plot.
        if (segments.isNotEmpty()) {
            val y0 = h + 3.dp.toPx()
            val sh = strip - 3.dp.toPx()
            drawRoundRect(grid.copy(alpha = 0.25f), Offset(0f, y0), Size(w, sh), CornerRadius(sh / 2))
            for (sgm in segments) {
                val x0 = xOf(sgm.from).coerceIn(0f, w)
                val x1 = xOf(sgm.to).coerceIn(0f, w)
                if (x1 - x0 >= 1f) drawRoundRect(sgm.color, Offset(x0, y0), Size(x1 - x0, sh), CornerRadius(sh / 2))
            }
        }
        if (points.isEmpty()) return@Canvas

        val runs = mutableListOf(mutableListOf(points.first()))
        for (i in 1 until points.size) {
            if (points[i].x - points[i - 1].x > gapSeconds) runs += mutableListOf<ChartPoint>()
            runs.last() += points[i]
        }
        clipRect(right = w * reveal.coerceIn(0f, 1f)) {
            for (run in runs) {
                if (run.size < 2) {
                    // A lone reading (e.g. a spot SpO2) gets a ringed dot so it's visible on its own.
                    val o = Offset(xOf(run[0].x), yOf(run[0].y))
                    drawCircle(color, 5.dp.toPx(), o)
                    drawCircle(surface, 2.dp.toPx(), o)
                    continue
                }
                val pts = run.map { Offset(xOf(it.x), yOf(it.y)) }
                val line = smoothPath(pts)
                val fill = Path().apply {
                    addPath(line)
                    lineTo(pts.last().x, h)
                    lineTo(pts.first().x, h)
                    close()
                }
                if (valueGradient != null) {
                    // Stops placed at their values' heights, so the line changes colour as it rises and falls.
                    val y0 = yOf(valueGradient.first().first)
                    val y1 = yOf(valueGradient.last().first)
                    val stops = valueGradient.map { (v, c) -> ((yOf(v) - y1) / (y0 - y1)).coerceIn(0f, 1f) to c }.reversed().toTypedArray()
                    val lineBrush = Brush.verticalGradient(*stops, startY = y1, endY = y0)
                    val fillBrush = Brush.verticalGradient(*stops.map { (f, c) -> f to c.copy(alpha = 0.16f) }.toTypedArray(), startY = y1, endY = y0)
                    drawPath(fill, fillBrush)
                    drawPath(line, lineBrush, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f)), startY = 0f, endY = h))
                    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }

            if (showAverage) {
                val avg = points.map { it.y }.average().toFloat()
                val avgY = yOf(avg)
                drawLine(onSurface.copy(alpha = 0.4f), Offset(0f, avgY), Offset(w, avgY), 1.dp.toPx())
                // A small pill so the label stays readable where it crosses the line.
                val text = measurer.measure(tr(R.string.missed_avg, format(avg)), style)
                val pw = text.size.width + 10.dp.toPx()
                val ph = text.size.height + 2.dp.toPx()
                val py = (avgY - ph / 2).coerceIn(0f, h - ph)
                drawRoundRect(bubble, Offset(2.dp.toPx(), py), Size(pw, ph), CornerRadius(ph / 2))
                drawText(text, topLeft = Offset(2.dp.toPx() + 5.dp.toPx(), py + 1.dp.toPx()))
            }
            if (markMin) {
                val p = points.minBy { it.y }
                val o = Offset(xOf(p.x), yOf(p.y))
                drawCircle(color, 6.dp.toPx(), o)
                drawCircle(surface, 3.dp.toPx(), o)
            }
            // The latest reading: a solid dot with a soft halo, like Oura's "now".
            val last = points.last()
            val lo = Offset(xOf(last.x), yOf(last.y))
            drawCircle(color.copy(alpha = 0.25f), 8.dp.toPx(), lo)
            drawCircle(color, 4.dp.toPx(), lo)
            drawCircle(surface, 1.5.dp.toPx(), lo)
        }

        // Touch readout: a guide line, a dot on the curve and a bubble with the value and time.
        touchX?.let { tx ->
            val x = tx.coerceIn(0f, w)
            val t = xRange.first + ((x / w) * (xRange.last - xRange.first)).toLong()
            val p = points.minBy { abs(it.x - t) }
            if (abs(p.x - t) > gapSeconds) return@let
            val px = xOf(p.x)
            val py = yOf(p.y)
            drawLine(onSurface.copy(alpha = 0.5f), Offset(px, 0f), Offset(px, h), 1.dp.toPx())
            drawCircle(surface, 6.dp.toPx(), Offset(px, py))
            drawCircle(color, 4.5.dp.toPx(), Offset(px, py))
            readout(measurer, tr(R.string.missed_value_at_time, withUnit(format(p.y), unit), xLabel(p.x)), readoutStyle, bubble, px, py)
        }
    }
}

/**
 * A smooth path through [pts] using monotone cubic interpolation (Fritsch–Carlson): curves like a spline
 * but never overshoots the data, so a smoothed heart rate can't show a peak or dip that didn't happen.
 */
internal fun smoothPath(pts: List<Offset>): Path = org.openstrap.app.ui.glow.draw.composePath(org.openstrap.app.ui.glow.draw.smoothOps(pts))

private fun niceStep(raw: Float): Float {
    val candidates = floatArrayOf(1f, 2f, 5f, 10f, 20f, 25f, 50f, 100f)
    return candidates.firstOrNull { it >= raw } ?: raw
}

/** A day on a weekly trend chart. */
data class TrendPoint(val label: String, val value: Float?)

/** A sleep stage interval for the hypnogram. */
data class StageSpan(val start: Long, val end: Long, val stage: Int)

