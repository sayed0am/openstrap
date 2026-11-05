package org.openstrap.app.ui.glow.draw

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import org.openstrap.app.ui.glow.GlowLayout
/*
 * The light of the glow and edge cards as plain numbers: where each gradient runs, its colours and stops, the rim
 * shadows, the core and the hairline. Nothing here draws. A card in the app and a widget's bitmap both read these, so
 * the two can't drift apart (widgets spec 6.2): GlowCard and EdgeCard paint them in Compose, CanvasDraw paints them on
 * an android.graphics.Canvas. Sizes are in px, from a [density] in px per dp, worked out the way the cards always did.
 */

/** A linear gradient from (x0, y0) to (x1, y1), clamped past its ends. Null [stops] spreads the colours evenly. */
@Immutable
data class LinearGradientSpec(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val colors: List<Color>,
    val stops: List<Float>?,
) {
    /** The stops as Compose colour stops, for `Brush.linearGradient`. */
    fun colorStops(): Array<Pair<Float, Color>> = colors.indices.map { i -> (stops?.get(i) ?: 0f) to colors[i] }.toTypedArray()
}

/**
 * The equivalent of CSS `box-shadow: inset [dx] [dy] [blur] [spread] [color]`: everything outside the card's shape
 * (shifted by [dx], [dy], shrunk by [spread]), blurred and clipped to the card. [blur] is CSS's, twice the standard
 * deviation.
 */
@Immutable
data class InsetShadow(val dx: Float, val dy: Float, val blur: Float, val spread: Float, val color: Color)

/** The dark core of a glow card, drawn on its own layer that covers the card and [GlowPaint.coreMargin] round it. */
sealed interface GlowCore {
    /** A level card's core: the core colour fading in past the level, a gradient over the whole layer. */
    @Immutable
    data class Level(val gradient: LinearGradientSpec) : GlowCore

    /** A blurred [core] in [coreColor], with the second-tone band [tone] round it when there is one. */
    @Immutable
    data class Shapes(
        val shape: CoreShape,
        val core: Rect,
        val coreRadius: Float,
        val coreColor: Color,
        val tone: Rect?,
        val toneRadius: Float,
        val toneColor: Color,
        /** The blur's standard deviation, px. */
        val blur: Float,
    ) : GlowCore
}

/** The hairline's gloss: a lighter line along the lit edge fading out along the sides, across the card or down it. */
@Immutable
data class GlossSpec(val across: Boolean, val stops: List<Pair<Float, Color>>)

/** A glow card's light, everything it's drawn with, for a card [width] by [height] px. */
@Immutable
data class GlowPaint(
    val width: Float,
    val height: Float,
    /** The card's corner, px. */
    val radius: Float,
    /** The base: the light colour at the lit edge melting into the body. */
    val fill: LinearGradientSpec,
    /** On a level card the rim lights only the lit part: the rims are clipped to this. Null: the whole card. */
    val rimClip: Rect?,
    /** The rim light and the bevel, back to front. */
    val rims: List<InsetShadow>,
    val core: GlowCore,
    /** How far past the card the core layer reaches, px, so the core can drift and swell into view. */
    val coreMargin: Float,
    val coreOpacity: Float,
    val gloss: GlossSpec,
    /** The 1 dp edge round the card, white at [GlowLayout.CardEdgeAlpha]. */
    val hairline: Color,
    val hairlineWidth: Float,
    /** Film grain, 0..255 of the grain tile, or 0 for none. */
    val grainAlpha: Int,
    /** On hold: only the card's edge in its colour, nothing else (pattern D1). */
    val outline: Color?,
    val outlineWidth: Float,
)

/** An outline card's edge. */
private const val OUTLINE_WIDTH_DP = 1.5f
private const val OUTLINE_ALPHA = 0.7f

/**
 * How [style] is drawn on a card [w] by [h] px at [density]. The style's mood must already be set, as GlowCard does
 * from LocalGlowMood.
 */
fun glowPaint(style: GlowStyle, w: Float, h: Float, density: Float): GlowPaint {
    val p = GlowPalette(style)
    val light = style.light()
    val r = style.radius.value * density

    // Rims: on a level card only over the lit part, clipped where the core is fully dark, so the unlit side keeps just
    // its hairline.
    var rimClip: Rect? = null
    val rims = mutableListOf<InsetShadow>()
    if (light.rim > 0f) {
        light.level?.let { level ->
            val dark = levelFade(level, if (light.across) w else h, density).first
            rimClip = when {
                light.across -> Rect(0f, 0f, w - dark, h)
                light.fromAbove -> Rect(0f, 0f, w, h - dark)
                else -> Rect(0f, dark, w, h)
            }
        }
        val k = light.rim
        // A rim past full (the workout window's 120%) can't get brighter at the edge, so it reaches further in.
        val g = style.glowSize.value * density * k.coerceAtLeast(1f)
        // Rim light: a CSS-style inset shadow leaning toward the lit edge, so the rim is full on one side (L4) and gone
        // on the other. Light all the way round only when the style has no side.
        if (light.angle != null) {
            // The hole shifts away from the light, so the lit side shows the most.
            val (vx, vy) = lightVector(light.angle)
            rims += InsetShadow(-vx * g * 0.6f, -vy * g * 0.6f, blur = g * 1.2f, spread = -g * 0.5f, p.light.copy(alpha = k.coerceAtMost(1f)))
        } else {
            rims += InsetShadow(0f, 0f, blur = g, spread = g * 0.1f, p.light.copy(alpha = (0.6f * k).coerceAtMost(1f)))
        }
        // Bevel: a thick band of light all the way round, like the edge of a glossy slab.
        if (style.bevel > 0f) {
            val b = minOf(w, h) * 0.075f * style.bevel
            rims += InsetShadow(0f, 0f, blur = b * 1.6f, spread = b * 0.45f, p.light.copy(alpha = (0.85f * k).coerceAtMost(1f)))
        }
    }

    // Just enough room round the card for the core to drift and swell into view.
    val margin = maxOf(w, h) * 0.08f
    val core = light.level?.let { level ->
        // A level card: the body holds up to near the value, then the core colour fades in across the value (a
        // smoothstep), like light rising in a glass and melting into the dark (design-system.html, Levels). Only the
        // core's alpha changes, so nothing is ever darker than the unlit part and there's never a hard edge.
        // Measured from the unlit side: the top, the bottom when lit from above, the right when lit from the left.
        val (dark, clear) = levelFade(level, if (light.across) w else h, density)
        val (y0, y1) = when {
            light.across -> w + margin to -margin
            light.fromAbove -> h + margin to -margin
            else -> -margin to h + margin
        }
        val span = abs(y1 - y0)
        fun at(fromUnlit: Float) = ((fromUnlit + margin) / span).coerceIn(0f, 1f)
        val steps = 10
        val colors = ArrayList<Color>(steps + 3)
        val stops = ArrayList<Float>(steps + 3)
        colors += p.core; stops += 0f
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val fade = t * t * (3f - 2f * t)
            colors += p.core.copy(alpha = 1f - fade)
            stops += at(dark + (clear - dark) * t)
        }
        colors += Color.Transparent; stops += 1f
        GlowCore.Level(
            if (light.across) LinearGradientSpec(y0, 0f, y1, 0f, colors, stops) else LinearGradientSpec(0f, y0, 0f, y1, colors, stops),
        )
    } ?: run {
        // Dark core and the second-tone band around it, blurred.
        val cw = w * light.core.width
        val ch = h * light.core.height
        val left = w * light.core.x - cw / 2
        val top = h * light.core.y - ch / 2
        val box = Rect(left, top, left + cw, top + ch)
        val coreRadius = (r - 8f * density).coerceAtLeast(0f)
        val band = if (light.tone) style.toneWidth.value * density else 0f
        GlowCore.Shapes(
            shape = style.coreShape,
            core = box,
            coreRadius = coreRadius,
            coreColor = p.core,
            tone = if (band > 0f) Rect(box.left - band, box.top - band, box.right + band, box.bottom + band) else null,
            toneRadius = coreRadius + band,
            toneColor = p.tone,
            blur = style.coreBlur.value * density,
        )
    }

    // A glossy edge: a lighter hairline along the top fading out down the sides. On a level card it follows the light
    // instead: glossy along the lit edge, plain hairline on the unlit side.
    val gloss = if (light.across) {
        GlossSpec(true, listOf(0f to Color.White.copy(alpha = 0.35f), 0.25f to Color.White.copy(alpha = 0.08f), 1f to Color.White.copy(alpha = 0.04f)))
    } else if (light.level != null && !light.fromAbove) {
        GlossSpec(false, listOf(0f to Color.White.copy(alpha = 0.04f), 0.75f to Color.White.copy(alpha = 0.08f), 1f to Color.White.copy(alpha = 0.35f)))
    } else {
        GlossSpec(false, listOf(0f to Color.White.copy(alpha = 0.35f), 0.25f to Color.White.copy(alpha = 0.08f), 1f to Color.White.copy(alpha = 0.04f)))
    }

    return GlowPaint(
        width = w,
        height = h,
        radius = r,
        fill = glowFill(light, p, w, h),
        rimClip = rimClip,
        rims = rims,
        core = core,
        coreMargin = margin,
        coreOpacity = style.coreOpacity,
        gloss = gloss,
        hairline = Color.White.copy(alpha = GlowLayout.CardEdgeAlpha),
        hairlineWidth = 1f * density,
        grainAlpha = if (style.grain > 0f) (style.grain.coerceIn(0f, 1f) * 255).toInt() else 0,
        outline = if (style.outline) style.color.copy(alpha = OUTLINE_ALPHA) else null,
        outlineWidth = OUTLINE_WIDTH_DP * density,
    )
}

/**
 * Where a level card's light fades into the dark, px from the unlit side of a card [length] px long: fully dark
 * [LEVEL_DARK_PAST] of the card past the value, clear where the fill reaches the body ([LEVEL_FILL_REACH] of the
 * level). Never shorter than [LEVEL_STEP], so a small card's fade stays soft too.
 */
internal fun levelFade(level: Float, length: Float, density: Float): Pair<Float, Float> {
    val clear = (1f - LEVEL_FILL_REACH * level) * length
    val dark = minOf((levelEdge(level) - LEVEL_DARK_PAST) * length, clear - LEVEL_STEP.value * density)
    return dark to clear
}

/**
 * The card's base fill: the light colour at the lit edge melting into the mid (body) colour along the light's angle,
 * linearly through the level's fill stops (spec 2.1): on L4 the light holds for the first part of the card and reaches
 * the body by 45%, on L3 it starts at 70% and is gone by 28%. Light all the way round: the body alone.
 */
private fun glowFill(light: org.openstrap.app.ui.glow.draw.GlowLight, p: GlowPalette, w: Float, h: Float): LinearGradientSpec {
    val angle = light.angle ?: return LinearGradientSpec(0f, 0f, 0f, h, listOf(p.mid, p.mid), null)
    val points = light.fillStops.map { it.at.coerceIn(0f, 1f) to it.light.coerceIn(0f, 1f) }
    val colors = points.map { (_, k) -> lerp(p.mid, p.light, k) } + p.mid
    val stops = points.map { it.first } + 1f
    // Like a CSS linear-gradient: the line runs through the centre, just long enough to reach both far corners.
    val (vx, vy) = lightVector(angle)
    val half = (abs(w * vx) + abs(h * vy)) / 2f
    return LinearGradientSpec(w / 2 + vx * half, h / 2 + vy * half, w / 2 - vx * half, h / 2 - vy * half, colors, stops)
}

/* ------------------------------------------------------------------------------------------------ */
/* Edge light                                                                                        */
/* ------------------------------------------------------------------------------------------------ */

/** The edge card's surface, under its light, and the one near-black every lit card and share card sits on. */
val EdgeSurface = Color(0xFF0A0A0B)

/** A level edge card's lowest light, so an empty one still shows where it fills from. */
const val EDGE_MIN_LEVEL = 0.04f

/** How far a level edge card's light reaches for [progress], 0..1, or null for a card that isn't a level. */
fun edgeLevel(progress: Float?): Float? = progress?.coerceIn(EDGE_MIN_LEVEL, 1f)

private const val LEVEL_FADE_STEPS = 6

/** An edge card's light, everything it's drawn with, for a card [width] by [height] px. */
@Immutable
data class EdgePaint(
    val width: Float,
    val height: Float,
    val surface: Color,
    /** The light coming in from the edge, or filling across to a level. */
    val glow: LinearGradientSpec,
    /** The card's corner, px. */
    val radius: Float,
    val hairline: Color,
    val hairlineWidth: Float,
)

/**
 * How an edge card lit by [light] in [tint] is drawn on a card [w] by [h] px at [density], softened by [mood]. A
 * [level] (from [edgeLevel]) fills the light from the left across to it in place of the edge's glow.
 */
fun edgePaint(light: EdgeLight, tint: Color, level: Float?, mood: GlowMood, w: Float, h: Float, density: Float): EdgePaint {
    val glow = if (light.strong) StrongGlow else SoftGlow
    val gradient = if (level == null) {
        val depth = glow.depth.value * density
        // From the lit edge inward, the colour falling off like the blurred edge of a CSS inset shadow.
        val (from, to) = when (light.edge) {
            Edge.Top -> Offset(0f, 0f) to Offset(0f, depth)
            Edge.Bottom -> Offset(0f, h) to Offset(0f, h - depth)
            Edge.Left -> Offset(0f, 0f) to Offset(depth, 0f)
            Edge.Right -> Offset(w, 0f) to Offset(w - depth, 0f)
        }
        val stops = glow.stops(mood.edge)
        LinearGradientSpec(from.x, from.y, to.x, to.y, stops.map { (_, alpha) -> tint.copy(alpha = alpha) }, stops.map { it.first })
    } else {
        // The light fills across to the level, 46% of the ink at the left and 30% at the value, then fades out over
        // LevelFade past it on a smoothstep, so it never stops in a line (spec 2.1, Levels).
        val end = w * level
        val fade = LevelFade.value * density
        val strength = mood.edge * (if (light.strong) 1.2f else 1f)
        val atValue = 0.30f * strength
        val past = List(LEVEL_FADE_STEPS) { i ->
            val t = (i + 1) / LEVEL_FADE_STEPS.toFloat()
            ((end + fade * t) / w).coerceIn(0f, 1f) to tint.copy(alpha = (atValue * (1f - t * t * (3f - 2f * t))).coerceIn(0f, 1f))
        }
        val stops = listOf(
            0f to tint.copy(alpha = (0.46f * strength).coerceIn(0f, 1f)),
            (end / w).coerceIn(0f, 1f) to tint.copy(alpha = atValue.coerceIn(0f, 1f)),
        ) + past
        LinearGradientSpec(0f, 0f, w, 0f, stops.map { it.second }, stops.map { it.first })
    }
    return EdgePaint(
        width = w,
        height = h,
        surface = EdgeSurface,
        glow = gradient,
        radius = GlowLayout.CardCorner.value * density,
        hairline = Color.White.copy(alpha = GlowLayout.CardEdgeAlpha),
        hairlineWidth = 1f * density,
    )
}

/* ------------------------------------------------------------------------------------------------ */
/* The widgets' edge glow. The app's cards dropped it on 2026-11-04 (spec 2.10); the home screen     */
/* widgets keep it until they're redrawn as a step of their own.                                     */
/* ------------------------------------------------------------------------------------------------ */

/** How far past its level a level widget's light fades out. */
internal val LevelFade = 40.dp

/**
 * An edge glow, after the CSS `box-shadow: inset` it was designed with: [alpha] of the colour, its hard edge [shift]
 * in from the card's edge, blurred with a standard deviation of [sigma].
 */
internal class EdgeGlow(val alpha: Float, val shift: Dp, val sigma: Dp) {
    /** How far in the glow reaches before it's gone. */
    val depth: Dp get() = shift + sigma * 2.5f

    /** Colour stops along [depth] (position 0..1 to alpha), scaled by the mood's [strength]. */
    fun stops(strength: Float): List<Pair<Float, Float>> = List(STEPS + 1) { i ->
        val at = i / STEPS.toFloat()
        val x = depth.value * at
        at to (alpha * strength * cover(x) / cover(0f) * (1f - at)).coerceIn(0f, 1f)
    }

    private fun cover(x: Float) = 1f / (1f + exp(-1.702f * (shift.value - x) / sigma.value))

    private companion object {
        const val STEPS = 8
    }
}

/** A usual widget: the ink at 70% at the edge, reaching 48 dp in. */
internal val SoftGlow = EdgeGlow(alpha = 0.70f, shift = 10.5.dp, sigma = 15.dp)

/** Off your normal: the ink at 90% at the edge, reaching 72 dp in. */
internal val StrongGlow = EdgeGlow(alpha = 0.90f, shift = 19.5.dp, sigma = 21.dp)
