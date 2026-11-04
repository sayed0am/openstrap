package org.openstrap.app.ui.glow.pictures

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openstrap.app.presentation.StageSpan
import org.openstrap.app.ui.glow.Rim
import org.openstrap.app.ui.glow.draw.ChartInk
import org.openstrap.app.ui.glow.draw.stageColor
import org.openstrap.app.ui.glow.drawZoneLine
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * The line-like pictures (spec 2.7): Heart rate's day line through your resting band, Stress's day strip, Skin
 * temperature's dial, Sleep regularity's bed and wake bars and the day with the naps on it. Each draws in its metric's
 * ink to fill its modifier, solid, never dotted or dashed and with no digits, and each but the naps has a rim function
 * saying where on the card the off part sits (spec 2.5). The geometry is in plain functions so it is tested without a
 * screen.
 */

/* ------------------------------------------------------------------------------------------------ */
/* Where a picture sits on its card                                                                  */
/* ------------------------------------------------------------------------------------------------ */

/** A Small's picture area starts 20 dp in from either side of its 176 dp. */
internal const val SMALL_INSET_X = 20f / 176f

/** A Wide's picture area starts 20 dp in from either side of a 364 dp card. */
internal const val WIDE_INSET_X = 20f / 364f

/** The picture's band down the card: under the title row, over the number. */
internal const val PICTURE_TOP_Y = 0.33f
internal const val PICTURE_BOTTOM_Y = 0.62f

/** Where [f], a fraction across the picture, lands across the card at [size]. */
internal fun cardX(f: Float, size: PictureSize): Float {
    val inset = if (size == PictureSize.Small) SMALL_INSET_X else WIDE_INSET_X
    return inset + f.coerceIn(0f, 1f) * (1f - 2 * inset)
}

/** Where [f], a fraction down the picture, lands down the card. */
internal fun cardY(f: Float): Float = PICTURE_TOP_Y + f.coerceIn(0f, 1f) * (PICTURE_BOTTOM_Y - PICTURE_TOP_Y)

/** The least room a picture takes when its modifier gives it none. */
private fun Modifier.pictureArea(size: PictureSize): Modifier =
    if (size == PictureSize.Small) defaultMinSize(136.dp, 60.dp) else defaultMinSize(324.dp, 70.dp)

/** Your normal behind a picture: the ink at 12% (spec 2.7). */
private fun Color.normalBand(): Color = copy(alpha = ChartInk.NORMAL_BAND_ALPHA)

/** A level inside your normal is drawn a little dimmer than one outside it. */
internal const val INSIDE_ALPHA = 0.7f

/* ------------------------------------------------------------------------------------------------ */
/* Heart rate: the day's line through your resting band                                              */
/* ------------------------------------------------------------------------------------------------ */

/** The values a line spans top to bottom: its readings and the band, with a little room above and under. */
internal data class LineScale(val lo: Float, val hi: Float) {
    /** How far down the picture [v] sits, 0 at the top. */
    fun down(v: Float): Float = ((hi - v) / (hi - lo)).coerceIn(0f, 1f)
}

/** The scale for [values] and [band], or null with no reading. Never flatter than 10 units, so a still line sits mid. */
internal fun lineScale(values: List<Float?>, band: ClosedFloatingPointRange<Float>?): LineScale? {
    val known = values.filterNotNull()
    if (known.isEmpty()) return null
    var lo = minOf(known.min(), band?.start ?: Float.MAX_VALUE)
    var hi = maxOf(known.max(), band?.endInclusive ?: -Float.MAX_VALUE)
    if (hi - lo < 10f) {
        val mid = (hi + lo) / 2
        lo = mid - 5f
        hi = mid + 5f
    }
    val pad = (hi - lo) * 0.08f
    return LineScale(lo - pad, hi + pad)
}

/** The fraction across the day of reading [i] of [slots]. */
internal fun slotX(i: Int, slots: Int): Float = if (slots <= 1) 1f else i / (slots - 1f)

/** The index of the latest reading, or null with none. */
internal fun lastReading(values: List<Float?>): Int? = values.indexOfLast { it != null }.takeIf { it >= 0 }

/**
 * Heart rate's picture (spec 2.7, Line): the day's readings as a solid 2 dp line in [ink] through your resting [band],
 * drawn as the ink at 12% with no edge lines. [values] are the day's buckets in order, a null a gap that breaks the
 * line, over [slots] buckets for the whole day (more than the readings so far leaves the hours to come empty). Parts
 * inside the band are a little dimmer, the latest reading a dot.
 */
@Composable
// The readings arrive whole with each update and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
fun DayLine(
    values: List<Float?>,
    band: ClosedFloatingPointRange<Float>?,
    ink: Color,
    size: PictureSize,
    modifier: Modifier = Modifier,
    slots: Int = values.size,
) {
    val scale = lineScale(values, band)
    Canvas(modifier.pictureArea(size)) {
        if (scale == null) return@Canvas
        val pad = 4.dp.toPx()
        val h = this.size.height - 2 * pad
        val w = this.size.width - 2 * pad
        fun y(v: Float) = pad + scale.down(v) * h
        if (band != null) {
            val top = y(band.endInclusive)
            drawRoundRect(ink.normalBand(), Offset(0f, top), Size(this.size.width, (y(band.start) - top).coerceAtLeast(1f)), CornerRadius(4.dp.toPx()))
        }
        fun inside(v: Float) = band != null && v in band
        val at = { i: Int -> values.getOrNull(i)?.let { Offset(pad + slotX(i, slots) * w, y(it)) } }
        drawZoneLine(values.size, 2.dp.toPx(), at) { i ->
            val a = values[i]!!
            val b = values[i + 1]!!
            if (inside(a) && inside(b)) ink.copy(alpha = INSIDE_ALPHA) else ink
        }
        lastReading(values)?.let { i -> at(i)?.let { drawCircle(ink, 3.5.dp.toPx(), it) } }
    }
}

/**
 * Where Heart rate's card lights when off (spec 2.5): at the line's end, over the top of the card when [high] and the
 * foot when low. [values] and [slots] as given to [DayLine]. Null with no reading.
 */
fun dayLineRim(values: List<Float?>, high: Boolean, size: PictureSize, slots: Int = values.size): Rim? {
    val last = lastReading(values) ?: return null
    return Rim(cardX(slotX(last, slots), size), if (high) 0f else 1f, reachX = 0.7f, reachY = 0.85f)
}

/* ------------------------------------------------------------------------------------------------ */
/* Stress: the day as one strip, calm to high                                                         */
/* ------------------------------------------------------------------------------------------------ */

/** One stretch of the strip: from [x0] to [x1] as fractions across, its stress as the ink's [alpha]. */
@Immutable
internal data class Stretch(val x0: Float, val x1: Float, val alpha: Float)

/** The ink's strength for a stress [level] 0 to 1: 0.15 calm to 1 high. */
internal fun stressAlpha(level: Float): Float = 0.15f + 0.85f * level.coerceIn(0f, 1f)

/** The stretches drawn for [levels] across [slots]: one per known level, a null or an hour to come left out. */
internal fun stripStretches(levels: List<Float?>, slots: Int = levels.size): List<Stretch> {
    val n = slots.coerceAtLeast(levels.size).coerceAtLeast(1)
    return levels.mapIndexedNotNull { i, l -> l?.let { Stretch(i / n.toFloat(), (i + 1) / n.toFloat(), stressAlpha(it)) } }
}

/**
 * Stress's picture (spec 2.7, Day strip): the day as one rounded strip, each stretch's stress in [levels] (0 calm to 1
 * high, null with no reading) as the strength of [ink]. [slots] is the whole day's stretches: the ones past [levels]
 * are hours to come and not drawn.
 */
@Composable
// The levels arrive whole with each update and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
fun DayStrip(levels: List<Float?>, ink: Color, size: PictureSize, modifier: Modifier = Modifier, slots: Int = levels.size) {
    val stretches = stripStretches(levels, slots)
    Canvas(modifier.pictureArea(size)) {
        val h = min((if (size == PictureSize.Small) 18.dp else 20.dp).toPx(), this.size.height)
        val top = (this.size.height - h) / 2
        val clip = Path().apply { addRoundRect(RoundRect(Rect(0f, top, this@Canvas.size.width, top + h), CornerRadius(h / 2))) }
        clipPath(clip) {
            // Each stretch reaches a hair over the next so no seam shows between them.
            stretches.forEach { s ->
                drawRect(ink.copy(alpha = s.alpha), Offset(s.x0 * this.size.width, top), Size((s.x1 - s.x0) * this.size.width + 0.5f, h))
            }
        }
    }
}

/** Where Stress's card lights when above usual (spec 2.5): at the strip's end, the latest stretch. Null with none. */
fun dayStripRim(levels: List<Float?>, size: PictureSize, slots: Int = levels.size): Rim? {
    val last = stripStretches(levels, slots).lastOrNull() ?: return null
    return Rim(cardX(last.x1, size), cardY(0.5f), reachX = 0.6f, reachY = 1.1f)
}

/* ------------------------------------------------------------------------------------------------ */
/* Skin temperature: a half dial centred on your normal                                               */
/* ------------------------------------------------------------------------------------------------ */

/** How far either way the dial reaches, in °C. */
const val DIAL_RANGE = 1.5f

/**
 * The angle of [deviation] on the dial in degrees, maths style: 180 at the left end, 90 at the top for your normal, 0 at
 * the right end. Past [range] it stops at the end.
 */
internal fun dialAngle(deviation: Float, range: Float = DIAL_RANGE): Float = 90f - 90f * (deviation / range).coerceIn(-1f, 1f)

/** A half dial in a box: its [center] at the foot of the arc and its [radius]. */
internal data class DialFit(val center: Offset, val radius: Float)

/** The biggest half dial that fits [width] by [height] with [margin] all round, centred in the box. */
internal fun dialFit(width: Float, height: Float, margin: Float): DialFit {
    val r = min(width / 2 - margin, height - 2 * margin).coerceAtLeast(1f)
    return DialFit(Offset(width / 2, height / 2 + r / 2), r)
}

/** The point on a dial of [fit] at [angle] degrees, [inset] in from the arc. */
internal fun dialPoint(fit: DialFit, angle: Float, inset: Float = 0f): Offset {
    val a = Math.toRadians(angle.toDouble())
    val r = fit.radius - inset
    return Offset(fit.center.x + (r * cos(a)).toFloat(), fit.center.y - (r * sin(a)).toFloat())
}

/**
 * Skin temperature's picture (spec 2.7, Dial): a half dial reaching [DIAL_RANGE] either side of your normal, a white
 * track at 8%, your [normal] (a deviation range in °C, like -0.3..0.3) as a band of [ink] at the top, and a needle at
 * tonight's [deviation] in the ink. Centred in its modifier: on a Wide the card puts the number left and gives the dial
 * a square-ish box at the right ([WIDE_DIAL_CENTER_X]).
 */
@Composable
fun TempDial(
    deviation: Float?,
    normal: ClosedFloatingPointRange<Float>?,
    ink: Color,
    size: PictureSize,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.pictureArea(size)) {
        val track = 4.dp.toPx()
        val bandW = 9.dp.toPx()
        val dot = 5.dp.toPx()
        val fit = dialFit(this.size.width, this.size.height, maxOf(bandW / 2, dot) + 1.dp.toPx())
        val box = Size(fit.radius * 2, fit.radius * 2)
        val corner = Offset(fit.center.x - fit.radius, fit.center.y - fit.radius)
        drawArc(Color.White.copy(alpha = ChartInk.Grid.alpha), 180f, 180f, false, corner, box, style = Stroke(track, cap = StrokeCap.Round))
        if (normal != null) {
            // Compose sweeps clockwise from 3 o'clock with y down, so a maths angle a is at -a.
            val from = -dialAngle(normal.start)
            val to = -dialAngle(normal.endInclusive)
            drawArc(ink.normalBand(), from, to - from, false, corner, box, style = Stroke(bandW, cap = StrokeCap.Round))
        }
        if (deviation != null) {
            val angle = dialAngle(deviation)
            val tip = dialPoint(fit, angle)
            drawLine(ink, dialPoint(fit, angle, fit.radius * 0.55f), dialPoint(fit, angle, dot), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(ink, dot, tip)
        }
    }
}

/** Where a Wide's dial sits across the card: in a square-ish box at the right, after the number. */
const val WIDE_DIAL_CENTER_X = 0.78f

/** Where a dial's centre sits across the card at [size]: mid on a Small, [WIDE_DIAL_CENTER_X] on a Wide. */
fun dialCenterX(size: PictureSize): Float = if (size == PictureSize.Small) 0.5f else WIDE_DIAL_CENTER_X

/**
 * Where Skin temperature's card lights when off (spec 2.5): the top of the dial when [high], its foot when low. The
 * caller passes [centerX], the dial's centre as a fraction across the card, if it doesn't sit where [dialCenterX] says.
 */
fun tempDialRim(high: Boolean, size: PictureSize, centerX: Float = dialCenterX(size)): Rim {
    val reachX = if (size == PictureSize.Small) 0.7f else 0.45f
    return Rim(centerX, if (high) 0f else 1f, reachX = reachX, reachY = if (size == PictureSize.Small) 0.8f else 0.6f)
}

/* ------------------------------------------------------------------------------------------------ */
/* Sleep regularity: the week's bed and wake bars                                                     */
/* ------------------------------------------------------------------------------------------------ */

/** A night's [bed] and [wake] times, or the usual ones. */
@Immutable
data class BedWake(val bed: LocalTime, val wake: LocalTime)

/** Minutes since the noon before the night, so a night never wraps: bed and wake. Wake is always after bed. */
internal fun sinceNoon(night: BedWake): Pair<Int, Int> {
    fun m(t: LocalTime) = Math.floorMod(t.hour * 60 + t.minute - 12 * 60, 24 * 60)
    val bed = m(night.bed)
    val wake = m(night.wake).let { if (it <= bed) it + 24 * 60 else it }
    return bed to wake
}

/** How far either way of your usual a bed or wake time still counts as usual, in minutes. */
const val USUAL_BAND_MINUTES = 45

/**
 * Which [nights] are off [usual]: bed or wake more than [band] minutes from the usual time, either way. A night with no
 * record, or no usual yet, is never off.
 */
internal fun offNights(nights: List<BedWake?>, usual: BedWake?, band: Int = USUAL_BAND_MINUTES): List<Boolean> {
    if (usual == null) return nights.map { false }
    val (ub, uw) = sinceNoon(usual)
    return nights.map { n -> n != null && sinceNoon(n).let { (b, w) -> abs(b - ub) > band || abs(w - uw) > band } }
}

/** The span of minutes since noon a bed and wake picture covers: every night and the usual bands, with room. */
internal fun bedWakeSpan(nights: List<BedWake?>, usual: BedWake?, band: Int = USUAL_BAND_MINUTES): IntRange {
    val known = nights.filterNotNull().map(::sinceNoon)
    val u = usual?.let(::sinceNoon)
    val top = listOfNotNull(known.minOfOrNull { it.first }, u?.first?.minus(band)).minOrNull() ?: (10 * 60)
    val bottom = listOfNotNull(known.maxOfOrNull { it.second }, u?.second?.plus(band)).maxOrNull() ?: (20 * 60)
    return (top - 20)..(bottom + 20)
}

/** The fraction across the picture of night [i]'s bar centre, of [count]. */
internal fun nightX(i: Int, count: Int): Float = (i + 0.5f) / count.coerceAtLeast(1)

/**
 * Sleep regularity's picture (spec 2.7, Bed and wake bars): a night a column, oldest first, each a vertical bar from
 * bedtime down to wake, against bands of [ink] at 12% at your [usual] bed and wake times ([band] minutes either way).
 * Off nights ([offNights]) are in full ink, usual ones dimmer. A night with no record is left empty.
 */
@Composable
// The week arrives whole with each update and never changes in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
fun BedWakeBars(
    nights: List<BedWake?>,
    usual: BedWake?,
    ink: Color,
    size: PictureSize,
    modifier: Modifier = Modifier,
    band: Int = USUAL_BAND_MINUTES,
) {
    val off = offNights(nights, usual, band)
    val span = bedWakeSpan(nights, usual, band)
    Canvas(modifier.pictureArea(size)) {
        val total = (span.last - span.first).coerceAtLeast(1).toFloat()
        fun y(m: Int) = (m - span.first) / total * this.size.height
        usual?.let(::sinceNoon)?.toList()?.forEach { m ->
            val top = y(m - band)
            drawRect(ink.normalBand(), Offset(0f, top), Size(this.size.width, y(m + band) - top))
        }
        val slot = this.size.width / nights.size.coerceAtLeast(1)
        val w = min(slot * 0.45f, (if (size == PictureSize.Small) 8.dp else 10.dp).toPx())
        nights.forEachIndexed { i, n ->
            if (n == null) return@forEachIndexed
            val (b, k) = sinceNoon(n)
            val cx = nightX(i, nights.size) * this.size.width
            val top = y(b)
            drawRoundRect(
                if (off[i]) ink else ink.copy(alpha = 0.45f),
                Offset(cx - w / 2, top), Size(w, (y(k) - top).coerceAtLeast(w)), CornerRadius(w / 2),
            )
        }
    }
}

/**
 * Where Sleep regularity's card lights when off (spec 2.5): next to the off nights, across at their middle, at the foot
 * when they went to bed late and the top when early. Null when no night is off.
 */
fun bedWakeRim(nights: List<BedWake?>, usual: BedWake?, size: PictureSize, band: Int = USUAL_BAND_MINUTES): Rim? {
    if (usual == null) return null
    val off = offNights(nights, usual, band)
    val idx = off.indices.filter { off[it] }
    if (idx.isEmpty()) return null
    val x = idx.map { nightX(it, nights.size) }.average().toFloat()
    val ub = sinceNoon(usual).first
    val late = idx.sumOf { sinceNoon(nights[it]!!).first - ub } >= 0
    return Rim(cardX(x, size), if (late) 1f else 0f, reachX = 0.5f, reachY = 0.8f)
}

/* ------------------------------------------------------------------------------------------------ */
/* Naps: the day with the nap on it                                                                   */
/* ------------------------------------------------------------------------------------------------ */

/** A nap from [start] to [end] (epoch seconds) with its [stages], empty when the strap gave none. */
@Immutable
data class Nap(val start: Long, val end: Long, val stages: List<StageSpan> = emptyList())

/** One part of a nap on the day: [stage] (null for a nap with no stages) from [x0] to [x1] as fractions across. */
internal data class NapPart(val stage: Int?, val x0: Float, val x1: Float)

/** A nap placed on the day: its pill from [x0] to [x1] and its [parts], fractions across. */
internal data class NapPlace(val x0: Float, val x1: Float, val parts: List<NapPart>)

/**
 * Each of [naps] placed on the day from [dayStart] to [dayEnd], as fractions across. A pill is never narrower than
 * [least], a fraction too, so a short nap still shows: it grows about its middle and its stages with it.
 */
internal fun napPlaces(naps: List<Nap>, dayStart: Long, dayEnd: Long, least: Float): List<NapPlace> {
    val day = (dayEnd - dayStart).coerceAtLeast(1).toFloat()
    fun f(t: Long) = ((t - dayStart) / day).coerceIn(0f, 1f)
    return naps.filter { it.end > dayStart && it.start < dayEnd }.map { nap ->
        val a = f(nap.start)
        val b = f(nap.end)
        val w = maxOf(b - a, least)
        val x0 = ((a + b) / 2 - w / 2).coerceIn(0f, (1f - w).coerceAtLeast(0f))
        val len = (nap.end - nap.start).coerceAtLeast(1).toFloat()
        val parts = if (nap.stages.isEmpty()) listOf(NapPart(null, x0, x0 + w)) else nap.stages.sortedBy { it.start }.map { s ->
            NapPart(s.stage, x0 + w * ((s.start - nap.start) / len).coerceIn(0f, 1f), x0 + w * ((s.end - nap.start) / len).coerceIn(0f, 1f))
        }
        NapPlace(x0, x0 + w, parts)
    }
}

/** The nap strip's height on the day's hairline. */
private val NapStrip: Dp = 10.dp

/**
 * Naps' picture (spec 2.7, The day with the nap on it): the day from [dayStart] to [dayEnd] (epoch seconds, like 06:00
 * to 22:00) as a white hairline at 8%, each of [naps] as its stage strip in the stage colours at its time. A nap with no
 * stages is drawn in [ink]. No rim: naps have no usual.
 */
@Composable
// The naps arrive whole with each update and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
fun NapDay(naps: List<Nap>, dayStart: Long, dayEnd: Long, ink: Color, size: PictureSize, modifier: Modifier = Modifier) {
    Canvas(modifier.pictureArea(size)) {
        val h = min(NapStrip.toPx(), this.size.height)
        val mid = this.size.height / 2
        drawLine(ChartInk.Grid, Offset(0f, mid), Offset(this.size.width, mid), ChartInk.HAIRLINE_DP.dp.toPx())
        napPlaces(naps, dayStart, dayEnd, h / this.size.width.coerceAtLeast(1f)).forEach { p ->
            val clip = Path().apply {
                addRoundRect(RoundRect(Rect(p.x0 * this@Canvas.size.width, mid - h / 2, p.x1 * this@Canvas.size.width, mid + h / 2), CornerRadius(h / 2)))
            }
            clipPath(clip) {
                p.parts.forEach { part ->
                    drawRect(
                        part.stage?.let(::stageColor) ?: ink,
                        Offset(part.x0 * this.size.width, mid - h / 2), Size((part.x1 - part.x0) * this.size.width + 0.5f, h),
                    )
                }
            }
        }
    }
}
