package org.openstrap.app.ui.glow.pictures

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.glow.Rim
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * The level and count pictures (spec 2.7): a week of dots through your band, a level fill over your usual band, a
 * countdown ring, blocks, day bars, a range fill, a split bar, week rings and the age scale. Each draws in its metric's
 * ink and fills its modifier, about 136 by 60 dp on a Small and 324 by 70 on a Wide. Solid only: no dashes, and no dots
 * but the week's own.
 *
 * The geometry is in plain functions below each picture, so it can be tested without drawing. Fractions run 0 to 1
 * across the picture, y from the top. The rim functions turn those into fractions of the card ([Rim]), assuming the
 * picture sits under the title row of a [GlowGrid.UnitHeight] card inside its 20 dp inset ([cardX], [cardY]).
 */

// ---- Shared ----------------------------------------------------------------------------------------------------

/** Your normal: a band of the ink at 12% (spec 2.7). */
internal const val BAND_ALPHA = 0.12f

/** A part inside your normal, or not the latest: the ink dimmed. */
internal const val DIM_ALPHA = 0.55f

/** A track under a fill: white at 8%. */
private val Track = Color.White.copy(alpha = 0.08f)

/** A ring's track: white at 10%. */
private val RingTrack = Color.White.copy(alpha = 0.10f)

/** A target or goal: solid white at 40% (spec 2.7). */
private val Target = Color.White.copy(alpha = 0.40f)

/** Your own age on the age scale: a white hairline. */
private val AgeLine = Color.White.copy(alpha = 0.6f)

/** Where the picture's top and foot sit down a 160 dp card: under the 20 dp inset, the title row and its 12 dp gap. */
internal const val PICTURE_TOP = 0.33f
internal const val PICTURE_BOTTOM = 0.75f

/** The card's 20 dp inset as a share of its width: a Small is 176 dp across, a Wide 364. */
internal fun insetShare(size: PictureSize): Float = when (size) {
    PictureSize.Small -> 20f / 176f
    PictureSize.Wide -> 20f / 364f
}

/** A picture's x fraction [f] as a fraction of the card's width at [size]. */
internal fun cardX(f: Float, size: PictureSize): Float {
    val inset = insetShare(size)
    return inset + f.coerceIn(0f, 1f) * (1f - 2 * inset)
}

/** A picture's y fraction [f] as a fraction of the card's height, the picture under the title row. */
internal fun cardY(f: Float): Float = PICTURE_TOP + f.coerceIn(0f, 1f) * (PICTURE_BOTTOM - PICTURE_TOP)

/** [v]'s place between [from] and [to], 0 to 1. */
internal fun share(v: Float, from: Float, to: Float): Float =
    if (to <= from) 0f else ((v - from) / (to - from)).coerceIn(0f, 1f)

private fun ClosedFloatingPointRange<Float>.holds(v: Float) = v in this

/** A bar [h] high centred at [cy] from [x0] to [x1], each end rounded fully or to [inner] when [roundStart]/[roundEnd] say not. */
private fun DrawScope.bar(color: Color, x0: Float, x1: Float, cy: Float, h: Float, roundStart: Boolean = true, roundEnd: Boolean = true, inner: Float = 0f) {
    if (x1 - x0 <= 0f) return
    val r = min(h / 2, (x1 - x0) / 2)
    val s = CornerRadius(if (roundStart) r else min(inner, r))
    val e = CornerRadius(if (roundEnd) r else min(inner, r))
    val path = Path().apply { addRoundRect(RoundRect(x0, cy - h / 2, x1, cy + h / 2, s, e, e, s)) }
    drawPath(path, color)
}

// ---- 1. Week of dots through your band ---------------------------------------------------------------------------

/** The week of dots laid out: each dot's x and y (null for a night with no reading), and the band's top and foot. */
internal data class WeekDotsGeometry(val xs: List<Float>, val ys: List<Float?>, val bandTop: Float?, val bandBottom: Float?)

/** The dots' radius as a share of the picture's height, and the latest's. */
private const val DOT_R = 3.5f / 40f
private const val TODAY_R = 5f / 40f

/**
 * Lays out [values], oldest first, against [usual]. The scale spans the values and the band with a little room, so the
 * latest dot sits clear of the edges. X runs from the first dot's centre to the last's, kept a dot inside the ends.
 */
internal fun weekDotsGeometry(values: List<Float?>, usual: ClosedFloatingPointRange<Float>?, aspect: Float = 136f / 60f): WeekDotsGeometry {
    val known = values.filterNotNull() + listOfNotNull(usual?.start, usual?.endInclusive)
    if (known.isEmpty()) return WeekDotsGeometry(xs(values.size, aspect), values.map { null }, null, null)
    var lo = known.min()
    var hi = known.max()
    if (hi - lo < 1e-3f) {
        lo -= 1f
        hi += 1f
    }
    val room = (hi - lo) * 0.15f
    lo -= room
    hi += room
    // The dots keep a radius of room top and foot.
    fun y(v: Float) = TODAY_R + (1f - share(v, lo, hi)) * (1f - 2 * TODAY_R)
    return WeekDotsGeometry(
        xs = xs(values.size, aspect),
        ys = values.map { v -> v?.let(::y) },
        bandTop = usual?.let { y(it.endInclusive) },
        bandBottom = usual?.let { y(it.start) },
    )
}

private fun xs(n: Int, aspect: Float): List<Float> {
    val edge = TODAY_R / aspect
    return if (n <= 1) List(n) { 1f - edge } else List(n) { i -> edge + i * (1f - 2 * edge) / (n - 1) }
}

/** Whether [v] is off [usual]: outside its band. Nothing is off without a band. */
internal fun isOff(v: Float?, usual: ClosedFloatingPointRange<Float>?): Boolean = v != null && usual != null && !usual.holds(v)

/** The rim for the week of dots: at the right end, at the latest dot's height, when the latest is outside [usual]. */
fun weekOfDotsRim(values: List<Float?>, usual: ClosedFloatingPointRange<Float>?, size: PictureSize = PictureSize.Small): Rim? {
    val latest = values.lastOrNull()
    if (!isOff(latest, usual)) return null
    val g = weekDotsGeometry(values, usual, aspectOf(size))
    return Rim(1f, cardY(g.ys.last()!!))
}

private fun aspectOf(size: PictureSize) = if (size == PictureSize.Small) 136f / 60f else 324f / 70f

/**
 * A week of dots through your band (PictureKind.WeekDots): [values] a night or a day each, oldest first, the last
 * today's (null for one with no reading). [usual] is drawn as a band of the ink at 12%, null while your normal is
 * still being learnt. Dots inside the band are dimmer, outside it in full ink, today's larger. The Recovery Small
 * (scores) and Breathing disturbances (per hour).
 */
// The lists arrive whole with each update and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
@Composable
fun WeekOfDots(values: List<Float?>, usual: ClosedFloatingPointRange<Float>?, ink: Color, size: PictureSize, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val g = weekDotsGeometry(values, usual, this.size.width / this.size.height.coerceAtLeast(1f))
        val h = this.size.height
        val w = this.size.width
        if (g.bandTop != null && g.bandBottom != null) {
            val top = g.bandTop * h
            val bottom = max(g.bandBottom * h, top + 2.dp.toPx())
            drawRoundRect(ink.copy(alpha = BAND_ALPHA), Offset(0f, top), Size(w, bottom - top), CornerRadius(4.dp.toPx()))
        }
        val last = values.lastIndex
        values.forEachIndexed { i, v ->
            val y = g.ys[i] ?: return@forEachIndexed
            val inBand = usual == null || usual.holds(v!!)
            val alpha = if (i == last || !inBand) 1f else DIM_ALPHA
            val r = (if (i == last) TODAY_R else DOT_R) * h
            drawCircle(ink.copy(alpha = alpha), r, Offset(g.xs[i] * w, y * h))
        }
    }
}

// ---- 2. Level fill over your usual band -------------------------------------------------------------------------

/** The level fill laid out on 0 to 1: the band's ends and the value's. */
internal data class LevelFillGeometry(val bandStart: Float?, val bandEnd: Float?, val value: Float)

/**
 * Lays out [value] against [usual] on a scale from 0. The scale reaches 1.6 times the band's top, so the band sits a
 * little past the middle as in the mockup, and stretches to keep a value past it inside the bar.
 */
internal fun levelFillGeometry(value: Float, usual: ClosedFloatingPointRange<Float>?): LevelFillGeometry {
    val top = max(usual?.endInclusive?.times(1.6f) ?: 0f, value * 1.1f).takeIf { it > 0f } ?: 1f
    return LevelFillGeometry(usual?.start?.let { share(it, 0f, top) }, usual?.endInclusive?.let { share(it, 0f, top) }, share(value, 0f, top))
}

/** The rim for the level fill: at the value's end, halfway down the picture, when [value] is outside [usual]. */
fun levelFillRim(value: Float, usual: ClosedFloatingPointRange<Float>?, size: PictureSize = PictureSize.Small): Rim? {
    if (!isOff(value, usual)) return null
    val g = levelFillGeometry(value, usual)
    return Rim(cardX(g.value, size), cardY(0.5f))
}

/**
 * A level filling over your usual band (PictureKind.LevelFill): Load. A bar fills from the left to [value], over a
 * dim band of [usual] (`LoadBands.usualRange`), with a dot at the value. Inside the band the fill is dimmer, outside
 * it the fill is full ink.
 */
@Composable
fun LevelFill(value: Float, usual: ClosedFloatingPointRange<Float>?, ink: Color, size: PictureSize, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val g = levelFillGeometry(value, usual)
        val dotR = 5.dp.toPx()
        val w = this.size.width - 2 * dotR
        val cy = this.size.height / 2
        val barH = 8.dp.toPx()
        fun x(f: Float) = dotR + f * w
        bar(Track, x(0f), x(1f), cy, barH)
        if (g.bandStart != null && g.bandEnd != null) {
            val bandH = 16.dp.toPx()
            drawRoundRect(ink.copy(alpha = BAND_ALPHA), Offset(x(g.bandStart), cy - bandH / 2), Size(x(g.bandEnd) - x(g.bandStart), bandH), CornerRadius(5.dp.toPx()))
        }
        val off = isOff(value, usual)
        bar(ink.copy(alpha = if (off) 1f else 0.5f), x(0f), max(x(g.value), x(0f) + barH), cy, barH)
        drawCircle(ink, dotR, Offset(x(g.value), cy))
    }
}

// ---- 3. Countdown ring ------------------------------------------------------------------------------------------

/** The ring's sweep in degrees for a [share] of the way to Ready. */
internal fun countdownSweep(share: Float): Float = 360f * share.coerceIn(0f, 1f)

/**
 * A ring filling toward Ready (PictureKind.CountdownRing): Time to recover. [share] is how far recovered, 0 to 1, full
 * when clear. Centred on a Small, at the start of a Wide. Never off, so no rim.
 */
@Composable
fun CountdownRing(share: Float, ink: Color, size: PictureSize, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 5.dp.toPx()
        val r = (min(this.size.height, this.size.width) - stroke) / 2 * 0.85f
        val cx = if (size == PictureSize.Small) this.size.width / 2 else r + stroke / 2
        val c = Offset(cx, this.size.height / 2)
        drawCircle(RingTrack, r, c, style = Stroke(stroke))
        val sweep = countdownSweep(share)
        if (sweep > 0f) drawArc(
            ink.copy(alpha = if (sweep >= 360f) 1f else 0.75f), -90f, sweep, false,
            Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

// ---- 4. Blocks --------------------------------------------------------------------------------------------------

/** How many blocks the target makes, one per [perBlock] minutes. */
internal fun blockCount(target: Int, perBlock: Int): Int = if (perBlock <= 0 || target <= 0) 0 else ceil(target / perBlock.toFloat()).toInt()

/** How many of them [minutes] fill, whole blocks only, at most all. */
internal fun blocksFilled(minutes: Int, target: Int, perBlock: Int): Int =
    if (perBlock <= 0) 0 else min(floor(max(minutes, 0) / perBlock.toFloat()).toInt(), blockCount(target, perBlock))

/** The rows the blocks take: three on a Small, two on a Wide, fewer when there are few. */
internal fun blockRows(count: Int, size: PictureSize): Int {
    val rows = if (size == PictureSize.Small) 3 else 2
    return rows.coerceAtMost(max(count, 1))
}

/**
 * A block per stretch toward the week's target (PictureKind.Blocks): Active minutes. [minutes] this week against
 * [target] (150), one block per [perBlock] minutes, filled blocks in the ink and the rest dim, read row by row.
 * Never off, so no rim.
 */
@Composable
fun Blocks(minutes: Int, ink: Color, size: PictureSize, modifier: Modifier = Modifier, target: Int = 150, perBlock: Int = 5) {
    Canvas(modifier) {
        val count = blockCount(target, perBlock)
        if (count == 0) return@Canvas
        val filled = blocksFilled(minutes, target, perBlock)
        val rows = blockRows(count, size)
        val cols = ceil(count / rows.toFloat()).toInt()
        val gap = 3.dp.toPx()
        val bw = (this.size.width - gap * (cols - 1)) / cols
        val bh = min((this.size.height - gap * (rows - 1)) / rows, 10.dp.toPx())
        val top = (this.size.height - (bh * rows + gap * (rows - 1))) / 2
        for (i in 0 until count) {
            val col = i % cols
            val row = i / cols
            drawRoundRect(
                if (i < filled) ink else Track,
                Offset(col * (bw + gap), top + row * (bh + gap)), Size(bw, bh), CornerRadius(2.dp.toPx()),
            )
        }
    }
}

// ---- 5. Day bars ------------------------------------------------------------------------------------------------

/** Each day's bar height and the target's, as shares of the picture's height. The top keeps a tenth spare. */
internal data class DayBarsGeometry(val heights: List<Float>, val target: Float?)

internal fun dayBarsGeometry(values: List<Float?>, target: Float?): DayBarsGeometry {
    val top = (listOfNotNull(target) + values.filterNotNull()).maxOrNull()?.takeIf { it > 0f }?.times(1.1f) ?: 1f
    return DayBarsGeometry(values.map { share(it ?: 0f, 0f, top) }, target?.let { share(it, 0f, top) })
}

/**
 * The last 7 days as bars (PictureKind.DayBars): Heart points, until a zone split is stored (open decision 3). [values]
 * oldest first, the last today's, drawn as rounded bars from the foot. Today's in full ink, the others dimmer. [target]
 * a solid hairline in white at 40%. Never off, so no rim.
 */
// The lists arrive whole with each update and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
@Composable
fun DayBars(values: List<Float?>, ink: Color, size: PictureSize, modifier: Modifier = Modifier, target: Float? = null) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val g = dayBarsGeometry(values, target)
        val n = values.size
        val gap = (if (size == PictureSize.Small) 6.dp else 10.dp).toPx()
        val bw = (this.size.width - gap * (n - 1)) / n
        val h = this.size.height
        val minH = 3.dp.toPx()
        g.heights.forEachIndexed { i, f ->
            if (values[i] == null) return@forEachIndexed
            val bh = max(f * h, minH)
            drawRoundRect(
                ink.copy(alpha = if (i == n - 1) 1f else DIM_ALPHA),
                Offset(i * (bw + gap), h - bh), Size(bw, bh), CornerRadius(min(bw / 2, 4.dp.toPx())),
            )
        }
        g.target?.let { t ->
            val y = h - t * h
            drawLine(Target, Offset(0f, y), Offset(this.size.width, y), 1.dp.toPx())
        }
    }
}

// ---- 6. Range fill ----------------------------------------------------------------------------------------------

/** Where the start and end sit on 0 to 100, as shares, and whether it's a gain. */
internal data class RangeFillGeometry(val start: Float, val end: Float) {
    val gain: Boolean get() = end > start
}

internal fun rangeFillGeometry(start: Float, end: Float): RangeFillGeometry = RangeFillGeometry(share(start, 0f, 100f), share(end, 0f, 100f))

/**
 * One bar from where the night began to where it ended (PictureKind.FillBar): Recharged, [start] to [end] on 0 to 100.
 * The start part is dim, the gain in the ink. A night that lost energy shows the end alone, dim. No rim ever: no
 * Energy history is stored, so it has no usual. The numbers ("34 to 72") are the card's status line, not drawn here.
 */
@Composable
fun RangeFill(start: Float, end: Float, ink: Color, size: PictureSize, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val g = rangeFillGeometry(start, end)
        val w = this.size.width
        val cy = this.size.height / 2
        val h = 10.dp.toPx()
        bar(Track, 0f, w, cy, h)
        bar(Color.White.copy(alpha = 0.2f), 0f, min(g.start, g.end) * w, cy, h)
        if (g.gain) bar(ink, max(0f, g.start * w - h / 2), g.end * w, cy, h)
    }
}

// ---- 7. Split bar -----------------------------------------------------------------------------------------------

/** One part of the split bar: its ends in px and its rank by size, 0 the biggest. */
internal data class SplitPart(val index: Int, val start: Float, val end: Float, val rank: Int)

/** The ink's strength by rank: the biggest part full, the others fainter. */
internal fun splitAlpha(rank: Int): Float = when (rank) {
    0 -> 1f
    1 -> 0.55f
    2 -> 0.32f
    else -> 0.2f
}

/**
 * Lays [parts] along [width] in their order with [gap] between them, each as wide as its share. Parts of zero or less
 * are left out. Ranks go by size, ties to the earlier part.
 */
internal fun splitBarParts(parts: List<Float>, width: Float, gap: Float): List<SplitPart> {
    val shown = parts.withIndex().filter { it.value > 0f }
    if (shown.isEmpty()) return emptyList()
    val total = shown.sumOf { it.value.toDouble() }.toFloat()
    val room = (width - gap * (shown.size - 1)).coerceAtLeast(0f)
    val ranks = shown.sortedWith(compareByDescending<IndexedValue<Float>> { it.value }.thenBy { it.index }).map { it.index }
    var x = 0f
    return shown.map { (i, v) ->
        val w = room * v / total
        SplitPart(i, x, x + w, ranks.indexOf(i)).also { x += w + gap }
    }
}

/**
 * One bar split by what took the energy (PictureKind.SplitBar): Drained, [parts] such as exercise, stress and living,
 * in that order, with gaps. The biggest in full ink, the others fainter by size. No rim ever.
 */
// The lists arrive whole with each update and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
@Composable
fun SplitBar(parts: List<Float>, ink: Color, size: PictureSize, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val h = 12.dp.toPx()
        val cy = this.size.height / 2
        val laid = splitBarParts(parts, this.size.width, 2.dp.toPx())
        laid.forEachIndexed { k, p ->
            bar(ink.copy(alpha = splitAlpha(p.rank)), p.start, p.end, cy, h, roundStart = k == 0, roundEnd = k == laid.lastIndex, inner = 3.dp.toPx())
        }
    }
}

// ---- 8. Week rings ----------------------------------------------------------------------------------------------

/** The rings laid out across [width] by [height]: each centre's x, and the radius at the stroke's middle. */
internal data class WeekRingsGeometry(val centres: List<Float>, val radius: Float)

internal fun weekRingsGeometry(width: Float, height: Float, stroke: Float, count: Int = 7): WeekRingsGeometry {
    if (count <= 0) return WeekRingsGeometry(emptyList(), 0f)
    val slot = width / count
    val d = min(slot * 0.82f, height)
    return WeekRingsGeometry(List(count) { i -> slot * (i + 0.5f) }, ((d - stroke) / 2).coerceAtLeast(0f))
}

/**
 * Your week (PictureKind.WeekRings): Monday to Sunday, a ring a day filling to that day's share of the goal, [shares]
 * in that order, null for a day to come, which shows its faint track alone. A full ring in full ink at goal, a part
 * ring dimmer. Steps' lime ([org.openstrap.app.ui.theme.MetricColors.steps]). No rim.
 */
// The lists arrive whole with each update and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
@Composable
fun WeekRings(shares: List<Float?>, ink: Color, size: PictureSize, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = (if (size == PictureSize.Small) 3.dp else 4.dp).toPx()
        val g = weekRingsGeometry(this.size.width, this.size.height, stroke, shares.size)
        val cy = this.size.height / 2
        val r = g.radius
        shares.forEachIndexed { i, s ->
            val c = Offset(g.centres[i], cy)
            drawCircle(RingTrack, r, c, style = Stroke(stroke))
            val sweep = countdownSweep(s ?: 0f)
            if (s != null && sweep > 0f) drawArc(
                ink.copy(alpha = if (sweep >= 360f) 1f else DIM_ALPHA), -90f, max(sweep, 4f), false,
                Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

// ---- 9. Age scale -----------------------------------------------------------------------------------------------

/** The ages the scale spans: your age 7 years either way, wider when the range reaches past. */
internal fun ageScaleSpan(low: Float, high: Float, age: Float): ClosedFloatingPointRange<Float> =
    min(age - 7f, low - 2f)..max(age + 7f, high + 2f)

/**
 * Where age [a] sits across the age scale for [low], [high] and [age], 0 to 1. The card places its labels with it,
 * "27 to 31" under the band's middle and "You, 34" over the hairline: the picture draws no text.
 */
fun ageScaleX(a: Float, low: Float, high: Float, age: Float): Float {
    val span = ageScaleSpan(low, high, age)
    return share(a, span.start, span.endInclusive)
}

/**
 * The rim for the age scale: at the band's end past your [age], the right end when the whole range is older, the left
 * when it's younger. Null when your age is inside the range. The picture sits at the card's foot (spec 5.4).
 */
fun ageScaleRim(low: Float, high: Float, age: Float, size: PictureSize = PictureSize.Wide): Rim? = when {
    low > age -> Rim(cardX(ageScaleX(high, low, high, age), size), 1f, reachX = 0.45f, reachY = 0.6f)
    high < age -> Rim(cardX(ageScaleX(low, low, high, age), size), 1f, reachX = 0.45f, reachY = 0.6f)
    else -> null
}

/**
 * The age scale (PictureKind.AgeScale): Habit Age's range [low] to [high] as a band in the ink on a faint track, a
 * white dot at the [estimate], a hairline at your own [age]. No labels: the card draws them, placed by [ageScaleX].
 */
@Composable
fun AgeScalePicture(low: Float, high: Float, estimate: Float, age: Float, ink: Color, size: PictureSize, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val dotR = 6.dp.toPx()
        val w = this.size.width - 2 * dotR
        fun x(a: Float) = dotR + ageScaleX(a, low, high, age) * w
        val cy = this.size.height / 2
        bar(Track, dotR, dotR + w, cy, 3.dp.toPx())
        bar(ink.copy(alpha = 0.8f), x(low) - 4.dp.toPx(), x(high) + 4.dp.toPx(), cy, 8.dp.toPx())
        val lineH = min(this.size.height, 28.dp.toPx())
        drawLine(AgeLine, Offset(x(age), cy - lineH / 2), Offset(x(age), cy + lineH / 2), 1.5.dp.toPx())
        drawCircle(Color.White, dotR, Offset(x(estimate), cy))
    }
}
