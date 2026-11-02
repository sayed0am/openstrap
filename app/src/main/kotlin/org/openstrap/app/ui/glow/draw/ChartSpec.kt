package org.openstrap.app.ui.glow.draw

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import kotlin.math.sign
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowStage
import org.openstrap.app.ui.theme.MetricColors

/*
 * The charts widgets share with their cards (widgets spec 6.2), as plain geometry: the points of a line and its end
 * dot, the bands of a hypnogram, the segments of the stage bar, the pill bars. Nothing here draws or measures text:
 * GlowCharts draws these in Compose with its labels, CanvasDraw on an android.graphics.Canvas. Sizes are in px from a
 * density in px per dp, worked out the way the charts always did so they land on the same pixels.
 */

/** Solid chart marks (spec 2.7): grid rows and guides white at 8%, a target or goal at 40%. */
object ChartInk {
    val Grid = Color.White.copy(alpha = GlowLayout.HairlineAlpha)
    val Target = Color.White.copy(alpha = 0.40f)

    /** Your normal behind a chart: a band of the ink at 12%. */
    const val NORMAL_BAND_ALPHA = 0.12f

    /** Grid rows, guides and baselines, dp. */
    const val HAIRLINE_DP = 1f
}

/* ------------------------------------------------------------------------------------------------ */
/* Paths                                                                                             */
/* ------------------------------------------------------------------------------------------------ */

/** One step of a path, so Compose and Canvas build the same one. */
sealed interface PathOp {
    data class Move(val x: Float, val y: Float) : PathOp
    data class Line(val x: Float, val y: Float) : PathOp
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) : PathOp
}

/** Straight lines through [pts]. */
fun polylineOps(pts: List<Offset>): List<PathOp> =
    pts.mapIndexed { i, p -> if (i == 0) PathOp.Move(p.x, p.y) else PathOp.Line(p.x, p.y) }

/**
 * A smooth path through [pts] using monotone cubic interpolation (Fritsch–Carlson): curves like a spline but never
 * overshoots the data, so a smoothed heart rate can't show a peak or dip that didn't happen.
 */
fun smoothOps(pts: List<Offset>): List<PathOp> {
    if (pts.isEmpty()) return emptyList()
    val ops = ArrayList<PathOp>(pts.size)
    ops += PathOp.Move(pts[0].x, pts[0].y)
    if (pts.size == 2) { ops += PathOp.Line(pts[1].x, pts[1].y); return ops }
    val n = pts.size
    val d = FloatArray(n - 1) { i -> (pts[i + 1].y - pts[i].y) / (pts[i + 1].x - pts[i].x).coerceAtLeast(0.001f) }
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
    for (i in 0 until n - 1) {
        if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
        val a = m[i] / d[i]
        val b = m[i + 1] / d[i]
        val s = a * a + b * b
        if (s > 9f) {
            val t = 3f / kotlin.math.sqrt(s)
            m[i] = t * a * d[i]
            m[i + 1] = t * b * d[i]
        }
    }
    for (i in 0 until n - 1) {
        val dx = (pts[i + 1].x - pts[i].x) / 3f
        ops += PathOp.Cubic(pts[i].x + dx, pts[i].y + m[i] * dx, pts[i + 1].x - dx, pts[i + 1].y - m[i + 1] * dx, pts[i + 1].x, pts[i + 1].y)
    }
    return ops
}

/* ------------------------------------------------------------------------------------------------ */
/* A day's line (heart rate day, GlowLineChart)                                                      */
/* ------------------------------------------------------------------------------------------------ */

/** How a [LineChartGeometry] is drawn, in dp: a smooth line with a halo and a soft fill, a dot on the latest. */
object LineChartLook {
    const val LINE_DP = 1.8f
    const val HALO_DP = 4f
    const val HALO_ALPHA = 0.08f
    const val FILL_ALPHA = 0.14f
    /** A lone reading between gaps, so a sparse week still shows its days. */
    const val LONE_DOT_DP = 2.6f
    const val END_HALO_DP = 6.5f
    const val END_HALO_ALPHA = 0.2f
    const val END_DOT_DP = 3.5f
    /** Room above the line, dp. */
    const val TOP_DP = 10f
    /** Room for the axis labels under it when there are any, dp. */
    const val AXIS_DP = 18f
}

/**
 * A day of readings laid out on a chart: three grid rows, your normal's band, the runs of readings between gaps (a
 * lone reading a dot), the latest reading, and the average. [plotBottom] is the foot of the plot, over any axis.
 */
@Immutable
class LineChartGeometry(
    val width: Float,
    val top: Float,
    val plotBottom: Float,
    /** The three grid rows' y. */
    val grid: List<Float>,
    /** Your normal: its top left and size, or null. */
    val bandTopLeft: Offset?,
    val bandSize: Size?,
    /** Lone readings between gaps. */
    val dots: List<Offset>,
    /** Runs of two readings or more, each drawn as one smooth line. */
    val runs: List<List<Offset>>,
    /** The latest reading. */
    val last: Offset,
    /** The average of every reading, and its y. */
    val average: Float,
    val averageY: Float,
    private val count: Int,
    private val inset: Float,
    private val lo: Float,
    private val hi: Float,
) {
    fun xOf(i: Int) = inset + i / (count - 1f).coerceAtLeast(1f) * (width - inset * 2)
    fun yOf(v: Float) = top + (plotBottom - top) * (1f - (v - lo) / (hi - lo))
}

/**
 * [values] (evenly spaced, nulls breaking the line) on a chart [width] by [height] px at [density], the ends [inset] px
 * in so a reading at the edge keeps its dot. [axis] leaves room for labels under it. [yRange] fixes the value axis,
 * else it fits the data and [band]. Null when there are no readings.
 */
fun lineChartGeometry(
    values: List<Float?>,
    width: Float,
    height: Float,
    density: Float,
    inset: Float,
    axis: Boolean = false,
    yRange: ClosedFloatingPointRange<Float>? = null,
    band: ClosedFloatingPointRange<Float>? = null,
): LineChartGeometry? {
    val known = values.filterNotNull()
    if (known.isEmpty()) return null
    val axisH = if (!axis) 0f else LineChartLook.AXIS_DP * density
    val top = LineChartLook.TOP_DP * density
    val h = height - axisH
    val lo = yRange?.start ?: minOf(known.min(), band?.start ?: Float.MAX_VALUE)
    val hi = (yRange?.endInclusive ?: maxOf(known.max(), band?.endInclusive ?: -Float.MAX_VALUE)).let { if (it - lo < 1e-3f) lo + 1f else it }
    fun xOf(i: Int) = inset + i / (values.size - 1f).coerceAtLeast(1f) * (width - inset * 2)
    fun yOf(v: Float) = top + (h - top) * (1f - (v - lo) / (hi - lo))
    val grid = listOf(0f, 0.5f, 1f).map { top + (h - top) * it }
    var bandTopLeft: Offset? = null
    var bandSize: Size? = null
    band?.let { b ->
        val y0 = yOf(b.endInclusive)
        val y1 = yOf(b.start)
        bandTopLeft = Offset(0f, y0)
        bandSize = Size(width, y1 - y0)
    }
    // Runs of consecutive readings; a gap starts a new run.
    val runs = mutableListOf<MutableList<Offset>>(mutableListOf())
    values.forEachIndexed { i, v ->
        if (v == null) { if (runs.last().isNotEmpty()) runs += mutableListOf<Offset>() } else runs.last() += Offset(xOf(i), yOf(v))
    }
    val avg = known.average().toFloat()
    val lastIndex = values.indexOfLast { it != null }
    return LineChartGeometry(
        width = width, top = top, plotBottom = h, grid = grid, bandTopLeft = bandTopLeft, bandSize = bandSize,
        dots = runs.filter { it.size == 1 }.map { it.first() },
        runs = runs.filter { it.size >= 2 },
        last = Offset(xOf(lastIndex), yOf(values[lastIndex]!!)),
        average = avg, averageY = yOf(avg),
        count = values.size, inset = inset, lo = lo, hi = hi,
    )
}

/* ------------------------------------------------------------------------------------------------ */
/* Ghost line (steps so far, Energy so far)                                                          */
/* ------------------------------------------------------------------------------------------------ */

/** How a [GhostLineGeometry] is drawn, in dp. */
object GhostLineLook {
    const val LINE_DP = 2.6f
    const val USUAL_DP = 1.5f
    /** Your usual by now: solid, white at 35% (spec 2.7). */
    const val USUAL_ALPHA = 0.35f
    /** The dot the line ends in, its radius. */
    const val END_DOT_DP = 4.5f
}

/**
 * Today so far as a line ending in a dot, your usual by now as the ghost line behind it. [today] is empty when no hour
 * has a value yet; [usual] null when there's no usual.
 */
@Immutable
class GhostLineGeometry(val usual: List<Offset>?, val today: List<Offset>, val endDot: Float)

/**
 * [today] so far (evenly spaced, nulls the hours to come) and [usual] by then, on a value axis from 0 to [max], in a
 * chart [width] by [height] px at [density]. Null when there are fewer than two hours to draw over.
 */
fun ghostLineGeometry(today: List<Float?>, usual: List<Float>?, max: Float, width: Float, height: Float, density: Float): GhostLineGeometry? {
    if (today.size < 2) return null
    val r = GhostLineLook.END_DOT_DP * density
    val top = r
    val h = height - r
    val hi = max.coerceAtLeast(1f)
    fun xOf(i: Int, n: Int) = r + i / (n - 1f) * (width - r * 2)
    fun yOf(v: Float) = top + (h - top) * (1f - (v / hi).coerceIn(0f, 1f))
    val ghost = usual?.takeIf { it.size >= 2 }?.let { u -> u.mapIndexed { i, v -> Offset(xOf(i, u.size), yOf(v)) } }
    val pts = today.mapIndexedNotNull { i, v -> v?.let { Offset(xOf(i, today.size), yOf(it)) } }
    return GhostLineGeometry(ghost, pts, r)
}

/* ------------------------------------------------------------------------------------------------ */
/* Hypnogram                                                                                         */
/* ------------------------------------------------------------------------------------------------ */

/** One stage on a hypnogram: its round band's top left and size, and the white it takes without stage colours. */
@Immutable
class HypnogramBand(val stage: GlowStage, val topLeft: Offset, val size: Size, val corner: Float, val whiteAlpha: Float)

/** A thin line joining two stages on different rows, for the white hypnogram. */
@Immutable
class HypnogramJoin(val from: Offset, val to: Offset)

/**
 * A night laid out as a hypnogram: a hairline per row from [gridX0] across, the stages as round bands on them, deepest
 * row last, and the joins between rows for the white version. [plotBottom] is the foot of the rows, over any axis.
 */
@Immutable
class HypnogramGeometry(
    val width: Float,
    val rowY: List<Float>,
    val gridX0: Float,
    val plotBottom: Float,
    val bands: List<HypnogramBand>,
    val joins: List<HypnogramJoin>,
    private val nameWidth: Float,
    private val plotWidth: Float,
) {
    /** The x of [f], a fraction of the night. */
    fun xOf(f: Float) = nameWidth + f * plotWidth

    /** The stages in order of time. */
    val sorted: List<GlowStage> get() = bands.map { it.stage }
}

/** How a hypnogram is drawn. */
object HypnogramLook {
    /** A band's height as a share of its row. */
    const val BAND_SHARE = 0.46f
    /** The stage names' column down the left, dp, when there's room for them. */
    const val NAMES_DP = 46f
    const val AXIS_DP = 18f
    /** The joins of the white hypnogram, white at 30%. */
    const val JOIN_ALPHA = 0.3f
}

/**
 * [stages] on [rows] rows (row 0 at the top, Awake), in a chart [width] by [height] px at [density]. [nameWidth] px
 * down the left holds the names, [axis] leaves room for times under it. Null with no stages.
 */
fun hypnogramGeometry(stages: List<GlowStage>, rows: Int, width: Float, height: Float, density: Float, nameWidth: Float, axis: Boolean): HypnogramGeometry? {
    if (stages.isEmpty()) return null
    val axisH = if (!axis) 0f else HypnogramLook.AXIS_DP * density
    val h = height - axisH
    val w = width - nameWidth
    val rowH = h / rows
    val pillH = rowH * HypnogramLook.BAND_SHARE
    fun yOf(row: Int) = rowH * row + rowH / 2
    fun xOf(f: Float) = nameWidth + f * w
    val sorted = stages.sortedBy { it.from }
    val joins = (1 until sorted.size).mapNotNull { i ->
        val a = sorted[i - 1]
        val b = sorted[i]
        if (a.row != b.row) HypnogramJoin(Offset(xOf(b.from), yOf(a.row)), Offset(xOf(b.from), yOf(b.row))) else null
    }
    val bands = sorted.map { s ->
        HypnogramBand(
            stage = s,
            topLeft = Offset(xOf(s.from), yOf(s.row) - pillH / 2),
            size = Size((xOf(s.to) - xOf(s.from)).coerceAtLeast(pillH), pillH),
            corner = pillH / 2,
            // Deeper rows brighter, so deep sleep stands out on the dark core.
            whiteAlpha = 0.55f + 0.45f * s.row / (rows - 1).coerceAtLeast(1),
        )
    }
    return HypnogramGeometry(width, (0 until rows).map(::yOf), nameWidth, h, bands, joins, nameWidth, w)
}

/* ------------------------------------------------------------------------------------------------ */
/* Stage bar                                                                                         */
/* ------------------------------------------------------------------------------------------------ */

/** The stages in the order the sleep hero adds them up: deepest first, awake last. */
val SleepStageOrder = listOf(SleepStage.DEEP, SleepStage.REM, SleepStage.LIGHT, SleepStage.AWAKE)

/** The stage bar (chart kit): one bar split by stage, 2 dp gaps, 3 dp corners. */
object StageBarLook {
    const val HEIGHT_DP = 8f
    const val GAP_DP = 2f
    const val CORNER_DP = 3f
}

/**
 * The stage bar's segments from a night's seconds [byStage], in [SleepStageOrder]: a stage under [minSeconds] leaves
 * its segment (and legend) out.
 */
fun stageTotals(byStage: Map<Int, Long>, minSeconds: Long = 60): List<Pair<Int, Long>> =
    SleepStageOrder.mapNotNull { st -> (byStage[st] ?: 0L).takeIf { it >= minSeconds }?.let { st to it } }

/** A stage bar segment: its stage and where it sits along the bar, in whole px. */
@Immutable
data class StageSegment(val stage: Int, val left: Int, val width: Int)

/**
 * The stage bar's [totals] across [width] px with [gap] px between segments, each as wide as its share, laid out in
 * whole pixels the way a Row of weighted boxes is: the space left after the gaps split by weight, rounded, the
 * rounding remainder handed out one px at a time from the first.
 */
fun stageBarSegments(totals: List<Pair<Int, Long>>, width: Int, gap: Int): List<StageSegment> {
    if (totals.isEmpty()) return emptyList()
    val weights = totals.map { it.second.toFloat() }
    val total = weights.sum()
    val space = (width - gap * (totals.size - 1)).coerceAtLeast(0)
    val unit = if (total > 0f) space / total else 0f
    var remainder = space - weights.sumOf { (unit * it).roundToInt() }
    var x = 0
    return totals.mapIndexed { i, (stage, _) ->
        val extra = remainder.sign
        remainder -= extra
        val w = ((unit * weights[i]).roundToInt() + extra).coerceAtLeast(0)
        StageSegment(stage, x, w).also { x += w + gap }
    }
}

/* ------------------------------------------------------------------------------------------------ */
/* Pill bars                                                                                         */
/* ------------------------------------------------------------------------------------------------ */

/** The widest a bar gets (spec 2.7, pill bars), dp: a wide card spaces its bars out but keeps them thin. */
const val BAR_MAX_WIDTH_DP = 6f

/** A pill bar: its top left, size and corner. */
@Immutable
class PillBar(val topLeft: Offset, val size: Size, val corner: Float)

/**
 * One pill bar in a column [pitch] wide starting at [x], standing on [bottom], [height] tall for its value. At most
 * [maxWidth] wide, and never shorter than twice its width, so a small value still reads as a bar, not a dot.
 */
fun pillBar(x: Float, pitch: Float, bottom: Float, height: Float, maxWidth: Float): PillBar {
    val w = (pitch * 0.56f).coerceAtMost(maxWidth)
    val h = height.coerceAtLeast(w * 2)
    return PillBar(Offset(x + (pitch - w) / 2, bottom - h), Size(w, h), w / 2)
}

/** How a week of daily bars is drawn (the Recovery widget's last seven days): solid, the day shown in the ink. */
object WeekBarsLook {
    /** Each day's column at its widest and narrowest, and the gap between columns, dp. Each day's letter sits under its column. */
    const val COLUMN_DP = 30f
    const val MIN_COLUMN_DP = 18f
    const val GAP_DP = 2f

    /** A full (100) day's bar, dp. */
    const val HEIGHT_DP = 40f

    /** The widest a bar gets, and its corner, dp: a solid bar with soft corners, so a low day still reads lower. */
    const val BAR_DP = 16f
    const val CORNER_DP = 4f

    /** The other days, white at this. */
    const val DIM_ALPHA = 0.30f

    /** A day still to come (the Steps widget's week): a stub this high, its corner, and white at this. */
    const val STUB_DP = 4f
    const val STUB_CORNER_DP = 2f
    const val STUB_ALPHA = 0.12f

    /** The width of [days] columns [column] dp wide, dp. */
    fun width(days: Int, column: Float = COLUMN_DP): Float = days * column + (days - 1).coerceAtLeast(0) * GAP_DP

    /** The widest column, from [MIN_COLUMN_DP] to [COLUMN_DP], that fits [days] into [room] dp. */
    fun column(days: Int, room: Float): Float {
        if (days <= 0) return COLUMN_DP
        return ((room - (days - 1) * GAP_DP) / days).coerceIn(MIN_COLUMN_DP, COLUMN_DP)
    }
}

/**
 * A week of daily [values] (0..1, null for a day without one, which gets no bar) as bars in columns [column] dp wide,
 * standing on the bottom of a [WeekBarsLook.width] by [WeekBarsLook.HEIGHT_DP] box at [density], each as high as its
 * share of a full day. Sized like a [pillBar] in its column, but with [WeekBarsLook.CORNER_DP] corners and only as
 * short as its corners allow: wide bars this short would all look alike if they kept a pill's twice-their-width.
 */
fun weekBars(values: List<Float?>, density: Float, column: Float = WeekBarsLook.COLUMN_DP): List<PillBar?> {
    val pitch = column * density
    val step = (column + WeekBarsLook.GAP_DP) * density
    val height = WeekBarsLook.HEIGHT_DP * density
    val corner = WeekBarsLook.CORNER_DP * density
    return values.mapIndexed { i, v ->
        v?.let {
            val pill = pillBar(i * step, pitch, height, 0f, WeekBarsLook.BAR_DP * density)
            val h = (height * it.coerceIn(0f, 1f)).coerceAtLeast(corner * 2)
            PillBar(Offset(pill.topLeft.x, height - h), Size(pill.size.width, h), corner)
        }
    }
}

/** A [WeekBarsLook.STUB_DP] stub where day [index]'s bar will be, placed as [weekBars] places its bars. */
fun weekStub(index: Int, density: Float, column: Float = WeekBarsLook.COLUMN_DP): PillBar {
    val pitch = column * density
    val height = WeekBarsLook.HEIGHT_DP * density
    val h = WeekBarsLook.STUB_DP * density
    val pill = pillBar(index * (column + WeekBarsLook.GAP_DP) * density, pitch, height, 0f, WeekBarsLook.BAR_DP * density)
    return PillBar(Offset(pill.topLeft.x, height - h), Size(pill.size.width, h), WeekBarsLook.STUB_CORNER_DP * density)
}

/** The hour pills' bars, lit or not, and the baseline under them. */
@Immutable
class HourPillsGeometry(val baselineY: Float, val bars: List<Pair<PillBar, Boolean>>)

/** How hour pills are drawn: the latest (or picked) hour white, the rest white at 45%. */
object HourPillsLook {
    const val DIM_ALPHA = 0.45f
}

/**
 * A day's steps as 24 pills (spec 2.7, hourly pill bars) in [width] by [height] px at [density]: as high as the hour's
 * share of the busiest, the [picked] hour, or else the latest with steps, lit. Hours with none aren't drawn, only the
 * hairline baseline under the bars.
 */
fun hourPills(hourly: List<Float>, picked: Int?, width: Float, height: Float, density: Float): HourPillsGeometry? {
    if (hourly.isEmpty()) return null
    val top = hourly.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val last = picked ?: hourly.indexOfLast { it > 0f }
    val slot = width / hourly.size
    val maxW = BAR_MAX_WIDTH_DP * density
    val bars = hourly.mapIndexedNotNull { i, v ->
        if (v <= 0f) null else pillBar(slot * i, slot, height, height * v / top, maxW) to (i == last)
    }
    return HourPillsGeometry(height - 0.5f * density, bars)
}

/* ------------------------------------------------------------------------------------------------ */
/* Stage strip                                                                                       */
/* ------------------------------------------------------------------------------------------------ */

/** One stretch of a stage strip: from [x0] to [x1] px along its middle, in [stage]'s colour. */
@Immutable
data class StageStripSegment(val stage: Int, val x0: Float, val x1: Float)

/**
 * A night or nap's stages laid flat ([org.openstrap.app.ui.glow.StageStrip]): one round-capped line [height] px thick
 * across [width] px from the first stage's start to the last's end, each stretch its stage's. The ends sit half the
 * thickness in, so the round caps stay inside. Empty without stages.
 */
fun stageStripSegments(spans: List<org.openstrap.app.ui.components.StageSpan>, width: Float, height: Float): List<StageStripSegment> {
    if (spans.isEmpty()) return emptyList()
    val start = spans.minOf { it.start }
    val end = spans.maxOf { it.end }.coerceAtLeast(start + 1)
    return spans.sortedBy { it.start }.map { s ->
        val x0 = height / 2 + (width - height) * (s.start - start) / (end - start)
        val x1 = height / 2 + (width - height) * (s.end - start) / (end - start)
        StageStripSegment(s.stage, x0, x1.coerceAtLeast(x0 + 0.5f))
    }
}

/** A sleep stage's colour, as on the strap's own charts. */
internal fun stageColor(stage: Int): Color = when (stage) {
    org.openstrap.app.data.db.SleepStage.DEEP -> MetricColors.sleepDeep
    org.openstrap.app.data.db.SleepStage.REM -> MetricColors.sleepRem
    org.openstrap.app.data.db.SleepStage.AWAKE -> MetricColors.sleepAwake
    else -> MetricColors.sleepLight
}
