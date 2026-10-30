package org.openstrap.app.ui.glow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openstrap.app.ui.components.rememberScrubHaptics
import org.openstrap.app.ui.components.scrub
import org.openstrap.app.ui.theme.FunnelSans
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.glow.draw.BAR_MAX_WIDTH_DP
import org.openstrap.app.ui.glow.draw.ChartInk
import org.openstrap.app.ui.glow.draw.chartHairline
import org.openstrap.app.ui.glow.draw.drawGhostLine
import org.openstrap.app.ui.glow.draw.drawHypnogramBands
import org.openstrap.app.ui.glow.draw.drawLineChartBody
import org.openstrap.app.ui.glow.draw.drawLineChartEnd
import org.openstrap.app.ui.glow.draw.drawPillBar
import org.openstrap.app.ui.glow.draw.ghostLineGeometry
import org.openstrap.app.ui.glow.draw.hypnogramGeometry
import org.openstrap.app.ui.glow.draw.lineChartGeometry
import org.openstrap.app.ui.glow.draw.pillBar
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Charts for glow cards. The card's colour carries the mood, so the marks are white: lines get a soft halo, and grids,
 * guides and bars are solid (spec 2.7). Only the digits are made of dots.
 */

private val Ink = Color.White
private val Muted = Color.White.copy(alpha = 0.55f)
/** Grid rows and stage guides: solid, white at 8%, 1 dp. */
private val Grid = ChartInk.Grid
/** A target, goal or average across a chart: solid, white at 40%, 1 dp. */
private val Target = ChartInk.Target

private val axisText = TextStyle(color = Muted, fontSize = 10.sp, fontFamily = FunnelSans)
private val valueText = TextStyle(color = Ink, fontSize = 11.sp, fontFamily = FunnelSans)
private val readoutText = TextStyle(color = Ink, fontSize = 12.sp, fontFamily = FunnelSans)

/** A solid horizontal hairline across the chart: a grid row by default, or a target in [color]. */
private fun DrawScope.gridLine(y: Float, color: Color = Grid, x0: Float = 0f, x1: Float = size.width) = chartHairline(y, color, x0, x1)

/** The widest a bar gets (spec 2.7, pill bars): a wide card spaces its bars out but keeps them thin. */
internal val BarMaxWidth = BAR_MAX_WIDTH_DP.dp

/**
 * One pill bar in a column [pitch] wide starting at [x], standing on [bottom], [height] tall for its value. At most
 * [BarMaxWidth] wide, and never shorter than twice its width, so a small value still reads as a bar, not a dot.
 */
internal fun DrawScope.pillBar(color: Color, x: Float, pitch: Float, bottom: Float, height: Float) =
    drawPillBar(pillBar(x, pitch, bottom, height, BarMaxWidth.toPx()), color)

private fun DrawScope.text(m: TextMeasurer, s: String, style: TextStyle, x: Float, y: Float, align: Float = 0f) {
    val r = m.measure(s, style)
    drawText(r, topLeft = Offset((x - r.size.width * align).coerceIn(0f, size.width - r.size.width), y))
}

/** The touch readout: a dark glass pill with white text, above [anchorY] when there's room, else below. */
private fun DrawScope.readout(m: TextMeasurer, s: String, x: Float, anchorY: Float) {
    val r = m.measure(s, readoutText)
    val bw = r.size.width + 18.dp.toPx()
    val bh = r.size.height + 10.dp.toPx()
    val bx = (x - bw / 2).coerceIn(0f, (size.width - bw).coerceAtLeast(0f))
    val by = (anchorY - bh - 12.dp.toPx()).let { if (it < 0f) anchorY + 12.dp.toPx() else it }
    drawRoundRect(Color.Black.copy(alpha = 0.72f), Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2))
    drawRoundRect(Ink.copy(alpha = 0.18f), Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2), style = Stroke(1.dp.toPx()))
    drawText(r, topLeft = Offset(bx + 9.dp.toPx(), by + 5.dp.toPx()))
}

/** A solid vertical guide under the finger. */
private fun DrawScope.guide(x: Float, bottom: Float) = drawLine(Target, Offset(x, 0f), Offset(x, bottom), strokeWidth = 1.dp.toPx())

/** Bottom axis labels spread evenly from the left edge to the right edge. */
private fun DrawScope.axis(m: TextMeasurer, labels: List<String>, y: Float) {
    if (labels.isEmpty()) return
    labels.forEachIndexed { i, l ->
        val f = if (labels.size == 1) 0f else i / (labels.size - 1f)
        text(m, l, axisText, size.width * f, y, align = f)
    }
}

/* ------------------------------------------------------------------------------------------------ */

/**
 * A day of readings as one smooth white line with a faint halo, a soft fill, three hairline grid rows, a solid
 * average line and a glowing dot on the latest value. [values] are evenly spaced; nulls break the line.
 */
@Composable
fun GlowLineChart(
    values: List<Float?>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    labels: List<String> = emptyList(),
    showAverage: Boolean = true,
    /** The line's colour; white suits glow cards, a metric colour can suit a black panel. */
    color: Color = Ink,
    /** A fixed value axis (e.g. 0..100 for scores); null fits the data. */
    yRange: ClosedFloatingPointRange<Float>? = null,
    /** A usual range drawn as a band of the colour at 12%, no edge lines (e.g. your normal). */
    band: ClosedFloatingPointRange<Float>? = null,
    /** How the average label prints a value. */
    format: (Float) -> String = { it.roundToInt().toString() },
    /**
     * Scrubbing that moves the card's headline instead of a readout (spec 2.7): the reading under the finger, or null
     * on release. Used without [describe].
     */
    onScrub: ((Int?) -> Unit)? = null,
    /**
     * A chosen stretch of the x range, as fractions: shaded in [spanColor] with its two edges drawn in white (a sleep's
     * times picked against its heart rate).
     */
    span: ClosedFloatingPointRange<Float>? = null,
    /** The shading of [span]; the line's colour by default. */
    spanColor: Color = color,
    /** Text for the touch readout at a reading; null turns scrubbing off. */
    describe: ((index: Int, value: Float) -> String)? = null,
) {
    val m = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(0f) }
    val inset = with(androidx.compose.ui.platform.LocalDensity.current) { 7.dp.toPx() }
    val scrubbing = describe != null || onScrub != null
    val tick = rememberScrubHaptics()
    Canvas(
        modifier.fillMaxWidth().height(height)
            .onSizeChanged { width = it.width.toFloat() }
            .let {
                if (!scrubbing) it else it.scrub(values) { x ->
                    touchX = x
                    val i = x?.let { tx -> nearestReading(values, tx, width, inset) }
                    tick(i, values, band, true)
                    onScrub?.invoke(i)
                }
            },
    ) {
        // The shared line geometry ([lineChartGeometry]), as a widget's chart draws it.
        val g = lineChartGeometry(values, size.width, size.height, density, inset, axis = labels.isNotEmpty(), yRange = yRange, band = band) ?: return@Canvas
        val h = g.plotBottom
        if (span != null) {
            fun x(f: Float) = inset + f.coerceIn(0f, 1f) * (size.width - inset * 2)
            val a = x(span.start)
            val b = x(span.endInclusive)
            drawRect(spanColor.copy(alpha = 0.18f), Offset(a, 0f), Size(b - a, h))
            listOf(a, b).forEach { drawLine(Color.White, Offset(it, 0f), Offset(it, h), 1.5.dp.toPx()) }
        }
        drawLineChartBody(g, color)

        if (showAverage) {
            val y = g.averageY
            gridLine(y, Target)
            text(m, "avg ${format(g.average)}", axisText.copy(color = Ink.copy(alpha = 0.8f)), size.width, y - 16.dp.toPx(), align = 1f)
        }

        drawLineChartEnd(g, color)

        axis(m, labels, h + 4.dp.toPx())

        // Scrubbing: the reading nearest the finger (gaps are skipped, not bridged).
        val tx = touchX
        if (scrubbing && tx != null) {
            val i = nearestReading(values, tx, size.width, inset)
            if (i != null) {
                val p = Offset(g.xOf(i), g.yOf(values[i]!!))
                guide(p.x, h)
                drawCircle(Color.Black.copy(alpha = 0.6f), 6.dp.toPx(), p)
                drawCircle(color, 4.dp.toPx(), p)
                if (describe != null) readout(m, describe(i, values[i]!!), p.x, p.y)
            }
        }
    }
}

/** Your normal behind a chart: a band of the ink at 12% (spec 2.7). */
private const val NormalBandAlpha = ChartInk.NORMAL_BAND_ALPHA

/** The reading nearest [x] on a line chart [width] wide, its ends [inset] in; gaps are skipped, not bridged. */
internal fun nearestReading(values: List<Float?>, x: Float, width: Float, inset: Float): Int? {
    if (values.isEmpty() || width <= inset * 2) return null
    val near = ((x - inset) / (width - inset * 2) * (values.size - 1)).roundToInt().coerceIn(0, values.size - 1)
    return (0..values.size).asSequence().flatMap { d -> sequenceOf(near - d, near + d) }
        .firstOrNull { it in values.indices && values[it] != null && kotlin.math.abs(it - near) <= values.size / 48 + 1 }
}

/* ------------------------------------------------------------------------------------------------ */

/**
 * Solid rounded bars (e.g. steps per hour, active minutes a day), each as tall as its value, on a hairline baseline.
 * An empty column isn't drawn. The [highlight] column (default: the last one with data) is drawn in [accent].
 */
@Composable
fun GlowBars(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    labels: List<String> = emptyList(),
    highlight: Int = values.indexOfLast { it > 0f },
    accent: Color = Ink,
    /** Text for the touch readout on a column; null turns scrubbing off. */
    describe: ((index: Int, value: Float) -> String)? = null,
    /** Scrubbing that moves the headline instead of a readout (spec 2.7): the column under the finger, or null on release. */
    onScrub: ((Int?) -> Unit)? = null,
) {
    val m = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(0f) }
    val scrubbing = describe != null || onScrub != null
    val tick = rememberScrubHaptics()
    Canvas(
        modifier.fillMaxWidth().height(height).onSizeChanged { width = it.width.toFloat() }.let {
            if (!scrubbing) it else it.scrub(values) { x ->
                touchX = x
                val i = x?.takeIf { values.isNotEmpty() && width > 0f }?.let { tx -> (tx / (width / values.size)).toInt().coerceIn(0, values.size - 1) }
                // Many hours sit at zero, so only the busiest is marked.
                tick(i, values, null, false)
                onScrub?.invoke(i)
            }
        },
    ) {
        if (values.isEmpty()) return@Canvas
        val axisH = if (labels.isEmpty()) 0f else 18.dp.toPx()
        val top = 4.dp.toPx()
        val h = size.height - axisH
        val pitchX = size.width / values.size
        val max = values.max().coerceAtLeast(1f)
        val picked = touchX?.takeIf { scrubbing }?.let { (it / pitchX).toInt().coerceIn(0, values.size - 1) }
        gridLine(h - 0.5.dp.toPx())
        values.forEachIndexed { i, v ->
            if (v <= 0f) return@forEachIndexed
            // While scrubbing, the other columns dim so the picked one stands out.
            val dim = if (picked == null || picked == i) 1f else 0.35f
            val color = if (i == highlight || i == picked) accent else Ink.copy(alpha = 0.6f)
            pillBar(color.copy(alpha = color.alpha * dim), pitchX * i, pitchX, h, (h - top) * v / max)
        }
        axis(m, labels, h + 4.dp.toPx())
        if (picked != null && describe != null) readout(m, describe(picked, values[picked]), pitchX * picked + pitchX / 2, 0f)
    }
}

/* ------------------------------------------------------------------------------------------------ */

/** A day on a [GlowTrend]. */
data class GlowDay(val label: String, val value: Float?)

/**
 * A week as solid stems rising to each day's value, the value printed above. Today (the last day) gets a glowing
 * ring. A day with no value is left empty. [average] draws a solid line across.
 */
@Composable
fun GlowTrend(
    days: List<GlowDay>,
    modifier: Modifier = Modifier,
    height: Dp = 130.dp,
    average: Float? = null,
) {
    val m = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (days.isEmpty()) return@Canvas
        val axisH = 18.dp.toPx()
        val top = 22.dp.toPx()
        val h = size.height - axisH
        val slot = size.width / days.size
        val known = days.mapNotNull { it.value } + listOfNotNull(average)
        val lo = (known.minOrNull() ?: 0f) * 0.85f
        val hi = (known.maxOrNull() ?: 1f).coerceAtLeast(lo + 1f)
        fun yOf(v: Float) = top + (h - top - 6.dp.toPx()) * (1f - (v - lo) / (hi - lo))

        average?.let { gridLine(yOf(it), Target) }
        days.forEachIndexed { i, d ->
            val cx = slot * i + slot / 2
            val today = i == days.lastIndex
            text(m, d.label, if (today) axisText.copy(color = Ink) else axisText, cx, h + 4.dp.toPx(), align = 0.5f)
            val v = d.value ?: return@forEachIndexed
            val y = yOf(v)
            drawLine(
                Ink.copy(alpha = if (today) 0.9f else 0.45f), Offset(cx, h - 3.dp.toPx()), Offset(cx, y),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
            if (today) {
                drawCircle(Ink.copy(alpha = 0.18f), 11.dp.toPx(), Offset(cx, y))
                drawCircle(Ink, 6.dp.toPx(), Offset(cx, y))
            } else {
                drawCircle(Ink, 4.dp.toPx(), Offset(cx, y))
            }
            text(m, v.roundToInt().toString(), valueText.copy(color = if (today) Ink else Ink.copy(alpha = 0.75f)), cx, y - 20.dp.toPx(), align = 0.5f)
        }
    }
}

/* ------------------------------------------------------------------------------------------------ */

/**
 * The arc gauge (chart kit, spec 2.7), on the Recovery detail's header: a half circle open at the foot, round caps. A
 * track of white at [ARC_TRACK_ALPHA] the whole way over and, from the left foot clockwise to [progress] (0..1), the
 * value in a light tint of the metric's [color]. [content], the score in dots, sits centred inside with its foot a
 * little above the arc's ends. A change of [progress] fills from the value shown to the new one, never from zero;
 * a new [key] (another day) draws the new value where it lands.
 */
@Composable
fun ArcGauge(
    progress: Float,
    color: Color,
    key: Any?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shown = rememberGaugeProgress(progress, key)
    val tint = lerp(Ink, color, ARC_TINT)
    BoxWithConstraints(modifier) {
        // Scaled down only when the header is narrower than the gauge.
        val k = (maxWidth / (ArcRadius * 2 + ArcStroke)).coerceAtMost(1f)
        val r = ArcRadius * k
        val stroke = ArcStroke * k
        // The circle's centre is on the line through the ends; the round caps reach half a stroke under it.
        val centreY = stroke / 2 + r
        Box(Modifier.size(r * 2 + stroke, centreY + stroke / 2)) {
            Canvas(Modifier.matchParentSize()) {
                val w = stroke.toPx()
                val rad = r.toPx()
                val topLeft = Offset(size.width / 2 - rad, centreY.toPx() - rad)
                val box = Size(rad * 2, rad * 2)
                drawArc(Ink.copy(alpha = ARC_TRACK_ALPHA), 180f, 180f, false, topLeft, box, style = Stroke(w, cap = StrokeCap.Round))
                val sweep = 180f * shown.coerceIn(0f, 1f)
                if (sweep > 0f) drawArc(tint, 180f, sweep, false, topLeft, box, style = Stroke(w, cap = StrokeCap.Round))
            }
            Box(Modifier.fillMaxWidth().height(centreY - r * ARC_NUMBER_LIFT), contentAlignment = Alignment.BottomCenter, content = content)
        }
    }
}

/** The arc's radius to the middle of its stroke, and the stroke: about a tenth of the radius, as in the design. */
internal val ArcRadius = 124.dp
internal val ArcStroke = 13.dp
/** The track's white, and how far the value's colour is from white: a near white with the metric's hue. */
internal const val ARC_TRACK_ALPHA = 0.11f
internal const val ARC_TINT = 0.16f
/** How far above the arc's ends the number's foot sits, as a share of the radius. */
private const val ARC_NUMBER_LIFT = 0.11f

/**
 * The horizon arc (chart kit, spec 2.7), on the Sleep score detail's header: a night sky rising from the horizon. A
 * wide, shallow hill (half a sine wave, [HorizonRise] high over [HorizonWidth]) with round caps, its ends low at both
 * sides. Under it a flatter track over the same ends in white at [HORIZON_TRACK_ALPHA], and over that the value from
 * the left end to [progress] (0..1) of the hill's length in a light tint of [color]. [content], the score in dots,
 * sits centred under the crest. Fills as [ArcGauge] does, never from zero.
 */
@Composable
fun HorizonArc(
    progress: Float,
    color: Color,
    key: Any?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shown = rememberGaugeProgress(progress, key)
    val tint = lerp(Ink, color, HORIZON_TINT)
    BoxWithConstraints(modifier) {
        val k = (maxWidth / (HorizonWidth + HorizonStroke)).coerceAtMost(1f)
        val span = HorizonWidth * k
        val rise = HorizonRise * k
        val stroke = HorizonStroke * k
        Box(Modifier.size(span + stroke, rise + stroke)) {
            Canvas(Modifier.matchParentSize()) {
                val w = stroke.toPx()
                val x0 = w / 2
                val base = size.height - w / 2
                val width = span.toPx()
                fun hill(height: Float) = List(HORIZON_STEPS + 1) { i ->
                    val u = i / HORIZON_STEPS.toFloat()
                    Offset(x0 + u * width, base - height * sin(Math.PI * u).toFloat())
                }
                fun path(points: List<Offset>) = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                val value = hill(rise.toPx())
                drawPath(
                    path(hill(rise.toPx() * HORIZON_TRACK_RISE)), Ink.copy(alpha = HORIZON_TRACK_ALPHA),
                    style = Stroke(w * HORIZON_TRACK_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                val upTo = upToLength(value, shown.coerceIn(0f, 1f))
                if (upTo.size > 1) drawPath(path(upTo), tint, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Box(
                Modifier.matchParentSize().padding(bottom = stroke / 2 + span * HORIZON_NUMBER_LIFT),
                contentAlignment = Alignment.BottomCenter, content = content,
            )
        }
    }
}

/** The hill's width between the middles of its end caps, its height, and its stroke, in the design's proportions. */
internal val HorizonWidth = 300.dp
internal val HorizonRise = 124.dp
internal val HorizonStroke = 12.dp
/** The track: white at 12%, a touch thinner, its crest at 81% of the value's so it shows just under it. */
internal const val HORIZON_TRACK_ALPHA = 0.12f
private const val HORIZON_TRACK_STROKE = 0.85f
private const val HORIZON_TRACK_RISE = 0.81f
/** How far the value's colour is from white: a light lavender with the sleep ink. */
internal const val HORIZON_TINT = 0.32f
/** How far above the ends the number's foot sits, as a share of the width. */
private const val HORIZON_NUMBER_LIFT = 0.035f
private const val HORIZON_STEPS = 96

/** [points] cut to [fraction] of their length along the line, the last point placed between two samples. */
private fun upToLength(points: List<Offset>, fraction: Float): List<Offset> {
    if (fraction <= 0f) return emptyList()
    val lengths = points.zipWithNext { a, b -> (b - a).getDistance() }
    var left = lengths.sum() * fraction
    val out = mutableListOf(points[0])
    lengths.forEachIndexed { i, l ->
        if (left >= l) { out += points[i + 1]; left -= l }
        else { if (l > 0f) out += lerp(points[i], points[i + 1], left / l); return out }
    }
    return out
}

/**
 * The value a score gauge draws: [target] at once on open and for each new [key], and after that a change animates
 * from the value shown to the new one over [GAUGE_FILL_MS] (spec 3.5, Value change). Never from zero. With the
 * system's animations off it jumps.
 */
@Composable
private fun rememberGaugeProgress(target: Float, key: Any?): Float {
    val animate = org.openstrap.app.ui.components.rememberAnimationsOn()
    val shown = remember(key) { Animatable(target) }
    LaunchedEffect(shown, target, animate) {
        if (animate) shown.animateTo(target, tween(GAUGE_FILL_MS, easing = FastOutSlowInEasing)) else shown.snapTo(target)
    }
    return shown.value
}

private const val GAUGE_FILL_MS = 450

/* ------------------------------------------------------------------------------------------------ */

/** A stretch of sleep on a [GlowHypnogram]: start and end as fractions of the night, and its row. */
data class GlowStage(val from: Float, val to: Float, val row: Int)

/**
 * Sleep stages as white pills on hairline rows (row 0 at the top, e.g. Awake), joined by thin connectors, deeper rows
 * drawn brighter so deep sleep stands out on the dark core; or, with [rowColors], as solid bands in the stage colours.
 */
@Composable
fun GlowHypnogram(
    stages: List<GlowStage>,
    rowNames: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 130.dp,
    labels: List<String> = emptyList(),
    /** The stage names down the left; too small a chart has no room for them, so it leaves them out. */
    showNames: Boolean = true,
    /**
     * Each row's stage colour. Given, every stage is a solid band in its colour with nothing joining them (the sleep
     * hero, spec 2.7); null keeps the white pills, brighter the deeper, joined by thin connectors.
     */
    rowColors: List<Color>? = null,
    /** The part kept, as fractions of the chart: what's either side is dimmed (a sleep whose times the user moved). */
    kept: ClosedFloatingPointRange<Float>? = null,
    /** Text for the touch readout on a stage; null turns scrubbing off. */
    describe: ((GlowStage) -> String)? = null,
) {
    val m = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(0f) }
    val nameWidth = with(androidx.compose.ui.platform.LocalDensity.current) { if (showNames) 46.dp.toPx() else 0f }
    val haptics = org.openstrap.app.ui.components.rememberHaptics()
    var lastStage by remember { mutableStateOf<Int?>(null) }
    Canvas(
        modifier.fillMaxWidth().height(height).onSizeChanged { width = it.width.toFloat() }.let {
            if (describe != null) it.scrub(stages) { x ->
                touchX = x
                // A tick each time the finger crosses into another stage (spec 3.6).
                val stage = x?.takeIf { width > nameWidth }?.let { tx ->
                    val f = (tx - nameWidth) / (width - nameWidth)
                    stages.indexOfFirst { f >= it.from && f <= it.to }.takeIf { it >= 0 }
                }
                if (stage != null && lastStage != null && stage != lastStage) haptics.perform(org.openstrap.app.ui.components.HapticEvent.ScrubStep)
                lastStage = if (x == null) null else stage ?: lastStage
            } else it
        },
    ) {
        val nameW = if (showNames) 46.dp.toPx() else 0f
        // The shared hypnogram geometry ([hypnogramGeometry]), as a widget's chart draws it.
        val g = hypnogramGeometry(stages, rowNames.size, size.width, size.height, density, nameW, axis = labels.isNotEmpty()) ?: return@Canvas
        val h = g.plotBottom

        rowNames.forEachIndexed { row, name ->
            gridLine(g.rowY[row], x0 = nameW)
            if (showNames) text(m, name, axisText, 0f, g.rowY[row] - 7.dp.toPx())
        }
        drawHypnogramBands(g, rowColors)
        if (kept != null) {
            val dim = Color.Black.copy(alpha = 0.65f)
            val a = g.xOf(kept.start.coerceIn(0f, 1f))
            val b = g.xOf(kept.endInclusive.coerceIn(0f, 1f))
            if (a > nameW) drawRect(dim, Offset(nameW, 0f), Size(a - nameW, h))
            if (b < size.width) drawRect(dim, Offset(b, 0f), Size(size.width - b, h))
        }
        if (labels.isNotEmpty()) {
            labels.forEachIndexed { i, l ->
                val f = if (labels.size == 1) 0f else i / (labels.size - 1f)
                text(m, l, axisText, g.xOf(f), h + 4.dp.toPx(), align = f)
            }
        }
        val tx = touchX
        if (describe != null && tx != null) {
            val x = tx.coerceIn(nameW, size.width)
            val f = (x - nameW) / (size.width - nameW)
            // The stage under the finger, or the nearest one when the finger is in a gap.
            val band = g.bands.firstOrNull { f >= it.stage.from && f < it.stage.to }
                ?: g.bands.minBy { minOf(kotlin.math.abs(it.stage.from - f), kotlin.math.abs(it.stage.to - f)) }
            guide(x, h)
            readout(m, describe(band.stage), x, band.topLeft.y)
        }
    }
}

/* ------------------------------------------------------------------------------------------------ */

/**
 * The ghost line of your usual (chart kit): [today] so far as a solid line in [color] ending in a dot, and [usual]
 * by this time of day as a solid ghost line at 35% white behind it, when there is one. Both are evenly spaced over the same span
 * (a day, say) on a value axis from 0 to [max]; nulls in [today] are the hours still to come.
 */
@Composable
fun GhostLine(today: List<Float?>, color: Color, max: Float, modifier: Modifier = Modifier, usual: List<Float>? = null, height: Dp = 56.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        // The shared ghost line geometry ([ghostLineGeometry]), as a widget's chart draws it.
        ghostLineGeometry(today, usual, max, size.width, size.height, density)?.let { drawGhostLine(it, color) }
    }
}

/* ------------------------------------------------------------------------------------------------ */

/**
 * Trend dots (chart kit): a few days as dots joined by a faint line, the last (today) the only full dot, with a ring.
 * Days with no value are left out of the line. With a [band] (your normal) it's drawn dim behind the dots, and days
 * outside it are lit up while those inside stay dim.
 */
@Composable
fun TrendDots(values: List<Int?>, modifier: Modifier = Modifier, height: Dp = 52.dp, band: ClosedFloatingPointRange<Float>? = null) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val known = values.filterNotNull()
        if (known.isEmpty()) return@Canvas
        val ring = 11.dp.toPx()
        val lo = minOf(known.min().toFloat(), band?.start ?: Float.MAX_VALUE) - 5f
        val hi = maxOf(known.max().toFloat(), band?.endInclusive ?: -Float.MAX_VALUE).plus(5f).coerceAtLeast(lo + 1f)
        fun xOf(i: Int) = ring + i / (values.size - 1f).coerceAtLeast(1f) * (size.width - ring * 2)
        fun yOf(v: Float) = ring + (size.height - ring * 2) * (1f - (v - lo) / (hi - lo))
        band?.let { b ->
            val y0 = yOf(b.endInclusive)
            drawRoundRect(Ink.copy(alpha = 0.10f), Offset(0f, y0), Size(size.width, yOf(b.start) - y0), CornerRadius(6.dp.toPx()))
        }
        val pts = values.mapIndexedNotNull { i, v -> v?.let { i to Offset(xOf(i), yOf(it.toFloat())) } }
        pts.zipWithNext { (_, a), (_, b) -> drawLine(Ink.copy(alpha = 0.25f), a, b, 1.5.dp.toPx()) }
        pts.forEach { (i, p) ->
            val outside = band != null && values[i]!!.toFloat() !in band
            if (i == values.lastIndex) {
                drawCircle(Ink.copy(alpha = 0.5f), ring, p, style = Stroke(1.dp.toPx()))
                drawCircle(Ink, 6.dp.toPx(), p)
            } else drawCircle(Ink.copy(alpha = if (outside) 0.95f else 0.45f), 4.dp.toPx(), p)
        }
    }
}

/**
 * A detail's chart over days (chart kit, trend dots on a detail): each day's value as a dot on a line in [color], your
 * normal as a dim band behind them with its [bandLabel], days inside it dim and days outside it lit with a halo
 * ([levels]). At a gap in the days the line stops and starts again, nothing joins it. [labels] go under each dot when there's one a day,
 * otherwise spread across. Holding scrubs: [onScrub] gets the point under the finger, or null on release, so the
 * headline can follow it (spec 2.7).
 */
@Composable
internal fun NormalTrend(
    values: List<Float?>,
    levels: List<org.openstrap.app.ui.dashboard.DetailCopy.Level?>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 230.dp,
    labels: List<String> = emptyList(),
    band: ClosedFloatingPointRange<Float>? = null,
    bandLabel: String? = null,
    onScrub: ((Int?) -> Unit)? = null,
) {
    val m = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(0f) }
    val inset = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
    val tick = rememberScrubHaptics()
    Canvas(
        modifier.fillMaxWidth().height(height)
            .onSizeChanged { width = it.width.toFloat() }
            .let {
                if (onScrub == null) it else it.scrub(values) { x ->
                    touchX = x
                    val i = x?.let { tx -> nearestReading(values, tx, width, inset) }
                    tick(i, values, band, true)
                    onScrub(i)
                }
            },
    ) {
        val known = values.filterNotNull()
        if (known.isEmpty()) return@Canvas
        val axisH = if (labels.isEmpty()) 0f else 24.dp.toPx()
        // Fewer, bigger dots for a week; a month or a year of them get smaller.
        val scale = when {
            values.size <= 7 -> 1f
            values.size <= 31 -> 0.55f
            else -> 0.4f
        }
        // The band's label has a row of its own above the plot, clear of the biggest halo, so it never sits on a point
        // or the line wherever the data goes. A swatch of the band's colour ties it to the band.
        val labelStyle = axisText.copy(fontSize = 12.sp)
        val labelH = bandLabel?.takeIf { band != null }?.let { m.measure(it, labelStyle).size.height.toFloat() }
        val top = (labelH?.plus(6.dp.toPx()) ?: 0f) + 14.dp.toPx() * scale + 4.dp.toPx()
        val h = size.height - axisH - 16.dp.toPx()
        val lo0 = minOf(known.min(), band?.start ?: Float.MAX_VALUE)
        val hi0 = maxOf(known.max(), band?.endInclusive ?: -Float.MAX_VALUE)
        val pad = ((hi0 - lo0) * 0.12f).coerceAtLeast(0.5f)
        val lo = lo0 - pad
        val hi = hi0 + pad
        fun xOf(i: Int) = inset + i / (values.size - 1f).coerceAtLeast(1f) * (size.width - inset * 2)
        fun yOf(v: Float) = top + (h - top) * (1f - (v - lo) / (hi - lo))
        band?.let { b ->
            val y0 = yOf(b.endInclusive)
            drawRoundRect(color.copy(alpha = NormalBandAlpha), Offset(0f, y0), Size(size.width, yOf(b.start) - y0), CornerRadius(12.dp.toPx()))
            if (bandLabel != null && labelH != null) {
                val w = m.measure(bandLabel, labelStyle).size.width
                val sw = 14.dp.toPx()
                val x = size.width - w
                drawRoundRect(color.copy(alpha = 0.35f), Offset(x - sw - 6.dp.toPx(), labelH / 2 - 4.dp.toPx()), Size(sw, 8.dp.toPx()), CornerRadius(3.dp.toPx()))
                text(m, bandLabel, labelStyle, size.width, 0f, align = 1f)
            }
        }

        val pts = values.mapIndexedNotNull { i, v -> v?.let { i to Offset(xOf(i), yOf(it)) } }
        val stroke = 2.4.dp.toPx() * (0.6f + 0.4f * scale)
        pts.zipWithNext { (i, a), (j, b) ->
            // A missing day between two dots: the line stops there.
            if (j - i == 1) drawLine(color.copy(alpha = 0.85f), a, b, stroke, StrokeCap.Round)
        }
        val dim = lerp(color, Color.Black, 0.5f)
        pts.forEach { (i, p) ->
            when (levels.getOrNull(i)) {
                org.openstrap.app.ui.dashboard.DetailCopy.Level.Above, org.openstrap.app.ui.dashboard.DetailCopy.Level.Below -> {
                    val lit = lerp(color, Color.White, 0.3f)
                    drawCircle(lit.copy(alpha = 0.35f), 14.dp.toPx() * scale, p)
                    drawCircle(lit, 7.dp.toPx() * scale, p)
                }
                else -> {
                    drawCircle(dim, 5.dp.toPx() * scale, p)
                    drawCircle(color, 5.dp.toPx() * scale, p, style = Stroke(2.dp.toPx() * scale.coerceAtLeast(0.6f)))
                }
            }
        }

        if (labels.size == values.size) labels.forEachIndexed { i, l -> text(m, l, axisText.copy(fontSize = 12.sp), xOf(i), size.height - axisH + 6.dp.toPx(), align = 0.5f) }
        else axis(m, labels, size.height - axisH + 6.dp.toPx())

        val tx = touchX
        if (onScrub != null && tx != null) {
            nearestReading(values, tx, size.width, inset)?.let { i ->
                val p = Offset(xOf(i), yOf(values[i]!!))
                guide(p.x, h)
                drawCircle(Color.Black.copy(alpha = 0.6f), 8.dp.toPx(), p)
                drawCircle(Ink, 5.dp.toPx(), p)
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------------ */

/**
 * The zone bar (chart kit): how a workout's time was spent, a thin bar split by the seconds in each heart rate zone,
 * easy to max, in the zone colours. Zones with no time are left out; nothing draws without any.
 */
@Composable
fun ZoneBar(zoneSeconds: List<Int>, colors: List<Color>, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    if (zoneSeconds.sum() <= 0) return
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().height(height).clip(androidx.compose.foundation.shape.RoundedCornerShape(height / 2)),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        zoneSeconds.forEachIndexed { z, secs ->
            if (secs > 0) Box(Modifier.weight(secs.toFloat()).fillMaxHeight().background(colors[z % colors.size]))
        }
    }
}

/* ------------------------------------------------------------------------------------------------ */

/**
 * A night or nap's stages laid flat (the nap hypnogram strip): one line from start to end, each stretch in its
 * stage's colour.
 */
@Composable
fun StageStrip(spans: List<org.openstrap.app.ui.components.StageSpan>, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        // The same geometry a widget's strip is drawn from (widgets spec 6.2).
        org.openstrap.app.ui.glow.draw.stageStripSegments(spans, size.width, size.height).forEach { s ->
            drawLine(stageColor(s.stage), Offset(s.x0, size.height / 2), Offset(s.x1, size.height / 2), size.height, StrokeCap.Round)
        }
    }
}

/** A sleep stage's colour, as on the strap's own charts. */
internal fun stageColor(stage: Int): Color = when (stage) {
    org.openstrap.app.data.db.SleepStage.DEEP -> MetricColors.sleepDeep
    org.openstrap.app.data.db.SleepStage.REM -> MetricColors.sleepRem
    org.openstrap.app.data.db.SleepStage.AWAKE -> MetricColors.sleepAwake
    else -> MetricColors.sleepLight
}
