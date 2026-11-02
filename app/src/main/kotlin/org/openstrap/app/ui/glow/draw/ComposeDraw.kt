package org.openstrap.app.ui.glow.draw

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

/*
 * The shared drawing in Compose: the specs in LightSpec, DotSpec and ChartSpec painted on a DrawScope, as the cards
 * always drew them. CanvasDraw paints the same specs on an android.graphics.Canvas for the widgets.
 */

/** The Compose path for [ops]. */
fun composePath(ops: List<PathOp>): Path = Path().apply {
    ops.forEach { op ->
        when (op) {
            is PathOp.Move -> moveTo(op.x, op.y)
            is PathOp.Line -> lineTo(op.x, op.y)
            is PathOp.Cubic -> cubicTo(op.x1, op.y1, op.x2, op.y2, op.x3, op.y3)
        }
    }
}

/** A gradient spec as a Compose brush. */
fun LinearGradientSpec.brush(): Brush = Brush.linearGradient(*colorStops(), start = Offset(x0, y0), end = Offset(x1, y1))

/** The glow hairline's gloss as a brush across the card or down it. */
fun GlossSpec.brush(): Brush =
    if (across) Brush.horizontalGradient(*stops.toTypedArray()) else Brush.verticalGradient(*stops.toTypedArray())

/** An edge card's background: the surface, the light from [glow] (its [EdgePaint.glow] as a brush), the hairline. */
fun DrawScope.drawEdge(paint: EdgePaint, glow: Brush) {
    val radius = CornerRadius(paint.radius)
    val stroke = paint.hairlineWidth
    drawRoundRect(paint.surface, cornerRadius = radius)
    drawRoundRect(glow, cornerRadius = radius)
    drawRoundRect(
        paint.hairline,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.x - stroke / 2),
        style = Stroke(stroke),
    )
}

/** [layout]'s dots in [color], from the top left. */
fun DrawScope.drawDots(layout: DotLayout, color: Color) {
    layout.centers.forEach { drawCircle(color, layout.radius, it) }
}

/** A solid hairline across at [y], from [x0] to [x1]. */
fun DrawScope.chartHairline(y: Float, color: Color = ChartInk.Grid, x0: Float = 0f, x1: Float = size.width) =
    drawLine(color, Offset(x0, y), Offset(x1, y), strokeWidth = ChartInk.HAIRLINE_DP * density)

/**
 * A line chart's runs as paths, each smooth line with the fill under it, and the fill's brush in [color]: built once
 * per size and data (in a drawWithCache block) so a redraw, a scrub say, allocates none of them.
 */
class LineChartPaths(val lines: List<Path>, val fills: List<Path>, val fill: Brush, val halo: Stroke, val stroke: Stroke)

/** [g]'s runs as [LineChartPaths] in [color], at [density]. */
fun lineChartPaths(g: LineChartGeometry, color: Color, density: Float): LineChartPaths {
    val lines = g.runs.map { pts -> composePath(smoothOps(pts)) }
    val fills = g.runs.mapIndexed { i, pts ->
        Path().apply { addPath(lines[i]); lineTo(pts.last().x, g.plotBottom); lineTo(pts.first().x, g.plotBottom); close() }
    }
    return LineChartPaths(
        lines, fills,
        Brush.verticalGradient(
            listOf(color.copy(alpha = LineChartLook.FILL_ALPHA), Color.Transparent), startY = g.top, endY = g.plotBottom,
        ),
        Stroke(LineChartLook.HALO_DP * density, cap = StrokeCap.Round, join = StrokeJoin.Round),
        Stroke(LineChartLook.LINE_DP * density, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * A day's line, under its average and labels: the grid rows, your normal's band, lone readings as dots, and each run
 * as a smooth line with a halo over a soft fill, in [color], from its [paths].
 */
fun DrawScope.drawLineChartBody(g: LineChartGeometry, paths: LineChartPaths, color: Color) {
    g.grid.forEach { chartHairline(it) }
    if (g.bandTopLeft != null && g.bandSize != null) drawRect(color.copy(alpha = ChartInk.NORMAL_BAND_ALPHA), g.bandTopLeft, g.bandSize)
    g.dots.forEach { drawCircle(color, LineChartLook.LONE_DOT_DP * density, it) }
    paths.lines.forEachIndexed { i, line ->
        drawPath(paths.fills[i], paths.fill)
        // A faint halo just wider than the line.
        drawPath(line, color.copy(alpha = LineChartLook.HALO_ALPHA), style = paths.halo)
        drawPath(line, color, style = paths.stroke)
    }
}

/** A day's line's glowing dot on the latest reading. */
fun DrawScope.drawLineChartEnd(g: LineChartGeometry, color: Color) {
    drawCircle(color.copy(alpha = LineChartLook.END_HALO_ALPHA), LineChartLook.END_HALO_DP * density, g.last)
    drawCircle(color, LineChartLook.END_DOT_DP * density, g.last)
}

/** The ghost line's two paths, built once per size and data (in a drawWithCache block), not on every draw. */
class GhostLinePaths(val usual: Path?, val today: Path?, val usualStroke: Stroke, val todayStroke: Stroke)

/** [g]'s lines as [GhostLinePaths], at [density]. */
fun ghostLinePaths(g: GhostLineGeometry, density: Float) = GhostLinePaths(
    g.usual?.let { composePath(polylineOps(it)) },
    g.today.takeIf { it.size >= 2 }?.let { composePath(polylineOps(it)) },
    Stroke(GhostLineLook.USUAL_DP * density, cap = StrokeCap.Round, join = StrokeJoin.Round),
    Stroke(GhostLineLook.LINE_DP * density, cap = StrokeCap.Round, join = StrokeJoin.Round),
)

/** The ghost line: your usual by now in white at 35%, today in [color] ending in a dot, from its [paths]. */
fun DrawScope.drawGhostLine(g: GhostLineGeometry, paths: GhostLinePaths, color: Color) {
    paths.usual?.let { drawPath(it, Color.White.copy(alpha = GhostLineLook.USUAL_ALPHA), style = paths.usualStroke) }
    if (g.today.isEmpty()) return
    paths.today?.let { drawPath(it, color, style = paths.todayStroke) }
    drawCircle(color, g.endDot, g.today.last())
}

/**
 * The hypnogram's stages over its rows: each a round band in its row's colour from [rowColors], or white brighter the
 * deeper, joined by thin lines, when it's null.
 */
fun DrawScope.drawHypnogramBands(g: HypnogramGeometry, rowColors: List<Color>?) {
    if (rowColors == null) g.joins.forEach { j -> drawLine(Color.White.copy(alpha = HypnogramLook.JOIN_ALPHA), j.from, j.to, ChartInk.HAIRLINE_DP * density) }
    g.bands.forEach { b ->
        drawRoundRect(
            rowColors?.getOrNull(b.stage.row) ?: Color.White.copy(alpha = b.whiteAlpha),
            topLeft = b.topLeft,
            size = b.size,
            cornerRadius = CornerRadius(b.corner),
        )
    }
}

/** One pill bar in [color]. */
fun DrawScope.drawPillBar(bar: PillBar, color: Color) =
    drawRoundRect(color, topLeft = bar.topLeft, size = bar.size, cornerRadius = CornerRadius(bar.corner))

/** Hour pills: the baseline, then each bar, the lit one white, the rest white at 45%. */
fun DrawScope.drawHourPills(g: HourPillsGeometry) {
    chartHairline(g.baselineY)
    g.bars.forEach { (bar, lit) -> drawPillBar(bar, if (lit) Color.White else Color.White.copy(alpha = HourPillsLook.DIM_ALPHA)) }
}
