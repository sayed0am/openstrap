package org.openstrap.app.ui.glow

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.openstrap.app.R
import org.openstrap.app.tr

/** Shape of the dark core: a rounded rectangle that follows the card, or a soft oval. */
enum class CoreShape { Rounded, Radial }

/** Which edge the rim light is strongest on. [GlowStyle.lightAngle], when set, takes over from it. */
enum class LightFrom { Top, Bottom, All }

/**
 * Where the light sits for [GlowStyle.lightAngle], in degrees counterclockwise from the right edge: 90 above, 270
 * below. The dark core sits on the opposite side.
 */
object LightAngle {
    const val Right = 0f
    const val UpperRight = 45f
    const val Above = 90f
    const val UpperLeft = 135f
    const val Left = 180f
    const val LowerLeft = 225f
    const val Below = 270f
    const val LowerRight = 315f
}

/**
 * How a card's dark core moves. Nothing moves when idle: only live data does, so a card is [Still] unless it shows
 * the live heart rate.
 */
sealed interface GlowMotion {
    /** A lub-dub pulse at [bpm]: the core kicks and tightens on each beat, then relaxes. Live heart rate only. */
    data class Heartbeat(val bpm: Int = 62) : GlowMotion

    /** No motion. */
    data object Still : GlowMotion
}

/**
 * A screen-level light on top of every card's own style (see [LocalGlowMood], spec 2.1 Moods). [glow] scales the
 * brightness of a glow card's whole lit area (rim, fill, body and band), [edge] scales edge light, [tone] keeps the
 * second-tone band and [belowOnly] turns every directional light to come from below.
 */
enum class GlowMood(val glow: Float, val edge: Float, val tone: Boolean, val belowOnly: Boolean) {
    /** The default: every card as its style says. */
    Day(glow = 1f, edge = 1f, tone = true, belowOnly = false),

    /** The evening moment: softer light, no tone band. */
    Evening(glow = 0.8f, edge = 0.9f, tone = false, belowOnly = false),

    /** The night moment, and the Sleep tab always: calmer still, and every light comes from below. */
    Night(glow = 0.65f, edge = 0.8f, tone = true, belowOnly = true),
}

/**
 * The mood for the cards on this screen; every [GlowCard] and [EdgeCard] under it follows. Only Today (by its moment)
 * and the Sleep tab provide one; everywhere else it's [GlowMood.Day].
 */
val LocalGlowMood = staticCompositionLocalOf { GlowMood.Day }

/**
 * The "glow" design language: a card lit from its rim, with a dark core floating inside it and an
 * optional second-tone band between the two. Everything is derived from one [color].
 *
 * Core position and size are fractions of the card (0..1, may go past the edges). A [toneWidth] of 0
 * turns the second tone off; a [glowStrength] of 0 turns the rim light off.
 *
 * The light can carry meaning: [lightAngle] says where it comes from (and places the core opposite), [lightLevel]
 * how high it reaches (Charge), and [second] marks the second glow card on a screen, so the hero clearly leads.
 */
@Immutable
data class GlowStyle(
    val color: Color,
    val radius: Dp = GlowLayout.CardCorner,
    val coreShape: CoreShape = CoreShape.Rounded,
    val coreX: Float = 0.5f,
    val coreY: Float = 0.64f,
    val coreWidth: Float = 0.74f,
    val coreHeight: Float = 0.6f,
    val coreBlur: Dp = 22.dp,
    val coreOpacity: Float = 1f,
    /** HSL lightness of the core, 0..1. 0.09 is the reference's deep plum; ~0.02 reads as black. */
    val coreLightness: Float = 0.09f,
    /** Hue rotation of the second tone, in degrees (-40 turns pink into violet). */
    val toneShift: Float = -40f,
    val toneWidth: Dp = 24.dp,
    val lightFrom: LightFrom = LightFrom.Top,
    val glowSize: Dp = 30.dp,
    /** The rim light at the lit edge, 0..1. Full on every glow card (spec 2.1), except where it shows a live swell. */
    val glowStrength: Float = 1f,
    /** A thick band of rim light all the way round, like a glossy bevel, 0..1 (0 = none). */
    val bevel: Float = 0f,
    /** How far the card's slab edge shows below it, for a 3D look (0 = flat). */
    val depth: Dp = 0.dp,
    /** Film grain opacity, 0..1. */
    val grain: Float = 0.06f,
    val motion: GlowMotion = GlowMotion.Still,
    /**
     * Where the light comes from, in degrees (see [LightAngle]). Set, it replaces [lightFrom] and the core moves to
     * the opposite side, keeping [coreWidth] and [coreHeight]. Null keeps [lightFrom] and the core where it's set.
     */
    val lightAngle: Float? = null,
    /**
     * How high the light reaches, 0..1, for levels like Charge: lit at full up to this share of the card, then a soft
     * [LEVEL_STEP] into the dark (see [levelEdge]). Never under [MIN_LIGHT_LEVEL], so an empty card still glows
     * faintly. Light from below unless [lightAngle] puts it above, or from the left ([LightAngle.Left]) on a wide
     * card, filling across to the value like a battery on its side.
     */
    val lightLevel: Float? = null,
    /**
     * HSL lightness the body starts from before the luminance cap darkens it. Raised only where a hue must read as
     * itself on a hero, like the evening's violet, which at the usual 0.32 sinks into the night's blue.
     */
    val bodyLightness: Float = 0.32f,
    /**
     * The second glow card on a screen, L3 (spec 2.1): the light colour at 70% at the lit edge, reaching the body by
     * 28% of the card, the rim at 70%. A level card ignores it: its light is the value, so it's always at full.
     */
    val second: Boolean = false,
    /**
     * Only the card's outline in [color], on black, with no light at all: the same card on hold, like a paused workout
     * (pattern D1). Nothing is rendered for it but the edge.
     */
    val outline: Boolean = false,
    /** Set by [GlowCard] from [LocalGlowMood]; leave it to the screen. */
    val mood: GlowMood = GlowMood.Day,
) {
    /** The same look in another colour. */
    fun tinted(color: Color) = copy(color = color)

    companion object {
        /** Pink rim, violet band, plum core that follows the card ("Last test"). */
        val Bloom = GlowStyle(color = Color(0xFFD65AA0), bevel = 1f)

        /** Near-black core in the upper left, navy light from below ("Training"). */
        val Midnight = GlowStyle(
            color = Color(0xFF2A44B0),
            coreShape = CoreShape.Radial,
            coreX = 0.30f, coreY = 0.20f, coreWidth = 0.80f, coreHeight = 1.5f,
            coreBlur = 32.dp, coreLightness = 0.03f,
            toneWidth = 0.dp,
            lightFrom = LightFrom.Bottom, glowSize = 26.dp,
        )

        /** Black top melting into orange from below ("Breath Volume"): Charge's glow, in its one ink. */
        val Ember = GlowStyle(
            color = org.openstrap.app.ui.theme.MetricColors.charge,
            coreShape = CoreShape.Radial,
            coreX = 0.5f, coreY = 0.05f, coreWidth = 1.4f, coreHeight = 1.2f,
            coreBlur = 32.dp, coreLightness = 0.02f,
            toneShift = -15f, toneWidth = 26.dp,
            lightFrom = LightFrom.Bottom, glowSize = 34.dp,
        )

        val presets = listOf(tr(R.string.glow_style_bloom) to Bloom, tr(R.string.glow_style_midnight) to Midnight, tr(R.string.glow_style_ember) to Ember)
    }
}

/** A glow style tuned for charts: the dark core sits low and wide, behind the plot. */
fun GlowStyle.Companion.chart(color: Color) = Bloom.copy(
    color = color,
    coreY = 0.64f, coreWidth = 0.88f, coreHeight = 0.66f,
    coreBlur = 24.dp, toneWidth = 16.dp,
    // Sleep's quiet recipe: a near-same-hue band. The rim at full and no bevel, as on every glow card (spec 2.1).
    toneShift = -10f, bevel = 0f,
)

/**
 * A level glow in [color] (spec 2.1, Levels): Charge's recipe in any ink, lit from below to [level] (0..1) with the
 * near-black core above it and no band, so the unlit part reads as dark, not as a dim wash of the colour.
 */
fun GlowStyle.Companion.level(color: Color, level: Float) = Ember.copy(color = color, lightLevel = level.coerceIn(0f, 1f))

/** The lowest [GlowStyle.lightLevel] drawn: an empty card still glows faintly. */
const val MIN_LIGHT_LEVEL = 0.12f

/** The light level to draw for [level]: within [MIN_LIGHT_LEVEL]..1. Null (no level) stays null. */
fun glowLevel(level: Float?): Float? = when {
    level == null -> null
    level.isNaN() -> MIN_LIGHT_LEVEL
    else -> level.coerceIn(MIN_LIGHT_LEVEL, 1f)
}

/** A unit vector from the card's centre toward the light at [angle] degrees, in screen coordinates (y down). */
internal fun lightVector(angle: Float): Pair<Float, Float> {
    val r = angle / 180f * PI.toFloat()
    // Snapped, so 90 and 270 give exactly the vertical light the old Top and Bottom did.
    fun clean(v: Float) = if (abs(v) < 1e-6f) 0f else v
    return clean(cos(r)) to clean(-sin(r))
}

/** Where the dark core sits and how big it is, as fractions of the card. */
internal data class CoreBox(val x: Float, val y: Float, val width: Float, val height: Float)

/** The core for light from [angle]: pushed toward the opposite side by a little over a third of the card. */
internal fun angleCore(angle: Float, width: Float, height: Float): CoreBox {
    val (vx, vy) = lightVector(angle)
    return CoreBox(0.5f - CORE_PUSH * vx, 0.5f - CORE_PUSH * vy, width, height)
}

/**
 * Where the lit part of a card with light [level] (already through [glowLevel]) ends, as a fraction of the card
 * measured from the unlit side: the level's height, so 0.3 lights the bottom 30%. The soft [LEVEL_STEP] into the
 * dark lies past it, on the unlit side, so a full card is lit all the way.
 */
fun levelEdge(level: Float): Float = 1f - level

/** The soft step from the lit part of a level card into the dark, past the value (spec 2.1, Levels). */
val LEVEL_STEP = 22.dp

/** How far up a level card the fill reaches the body colour, as a share of the level: the light holds below it. */
internal const val LEVEL_FILL_REACH = 0.85f

/** The core box for a light [level]: the dark part of the card, from the unlit side to [levelEdge]. */
internal fun levelCore(level: Float, fromAbove: Boolean, across: Boolean = false): CoreBox {
    val edge = levelEdge(level).coerceAtLeast(0f)
    if (across) return CoreBox(1f - edge / 2f, 0.5f, edge, 1f)
    return CoreBox(0.5f, if (fromAbove) 1f - edge / 2f else edge / 2f, 1f, edge)
}

private const val CORE_PUSH = 0.38f

/**
 * A point on a glow card's fill: [at] along the light (0 at the lit edge, 1 at the far one), how much of the light
 * colour over the body there is there, [light] 0..1. The fill runs linearly between points (spec 2.1).
 */
internal data class FillStop(val at: Float, val light: Float)

/** How a [GlowStyle] is lit once its angle, level, [GlowStyle.second] and mood are applied. */
internal class GlowLight(
    /** Where the light comes from, or null for light all the way round. */
    val angle: Float?,
    /** Multiplier on the rim light. */
    val rim: Float,
    /** The fill from the lit edge in, ending in the body colour. */
    val fillStops: List<FillStop>,
    val core: CoreBox,
    val tone: Boolean,
    /** Set for a level card: the light level the dark part is drawn down to (or up to, [fromAbove]). */
    val level: Float?,
    val fromAbove: Boolean,
    /** A level lit from the left, filling across the card to the value instead of up it. */
    val across: Boolean = false,
    /** How bright the whole lit area is (rim, fill, body and band), 0..1: the mood. */
    val brightness: Float,
) {
    /** How much of the light colour the fill starts with at the lit edge, 0..1. */
    val fill: Float get() = fillStops.first().light

    /** How far into the card the fill reaches before it's all body colour, 0..1. */
    val reach: Float get() = fillStops.last().at
}

internal fun GlowStyle.light(): GlowLight {
    val own = lightAngle ?: if (lightLevel != null) LightAngle.Below else when (lightFrom) {
        LightFrom.Top -> LightAngle.Above
        LightFrom.Bottom -> LightAngle.Below
        LightFrom.All -> null
    }
    val angle = if (mood.belowOnly && own != null) LightAngle.Below else own
    val level = glowLevel(lightLevel)
    val fromAbove = angle != null && lightVector(angle).second < 0f
    val across = level != null && angle == LightAngle.Left
    val core = when {
        level != null -> levelCore(level, fromAbove, across)
        // An angle of the card's own, or the mood moved the light: the core goes opposite it.
        angle != null && (lightAngle != null || angle != own) -> angleCore(angle, coreWidth, coreHeight)
        else -> CoreBox(coreX, coreY, coreWidth, coreHeight)
    }
    // A level is always at full, second card or not: its light is the value (spec 2.1, Levels).
    val l3 = second && level == null
    val fill = when {
        // Lit at full most of the way up to the value, then the body; the soft step into the dark follows.
        level != null -> listOf(FillStop(0f, 1f), FillStop(0.35f * level, 0.55f), FillStop(LEVEL_FILL_REACH * level, 0f))
        l3 -> listOf(FillStop(0f, SECOND_FILL), FillStop(SECOND_REACH, 0f))
        // L4: the light colour at the edge, half way to the body by 16%, all body by 45% (the mockup's gradient).
        else -> listOf(FillStop(0f, 1f), FillStop(0.16f, 0.5f), FillStop(HERO_REACH, 0f))
    }
    return GlowLight(
        angle = angle,
        rim = glowStrength * if (l3) SECOND_RIM else 1f,
        fillStops = fill,
        core = core,
        tone = mood.tone,
        level = level,
        fromAbove = fromAbove,
        across = across,
        brightness = mood.glow,
    )
}

/** L4: how far into the card the hero's fill reaches the body. */
private const val HERO_REACH = 0.45f

/** L3, the second glow against the hero: the light colour and the rim at 70%, the body reached by 28%. */
private const val SECOND_RIM = 0.7f
private const val SECOND_FILL = 0.7f
private const val SECOND_REACH = 0.28f

/** The handful of colours a [GlowStyle] is drawn with, all derived from its main colour. */
@Immutable
internal class GlowPalette(style: GlowStyle) {
    var light: Color
        private set
    var mid: Color
        private set
    var tone: Color
        private set
    val core: Color

    init {
        val (h, s0, l) = hslOf(style.color)
        // Near-grey colours stay neutral (a dark grey glow); anything coloured gets enough saturation to glow.
        val s = if (s0 < 0.12f) s0 else s0.coerceIn(0.35f, 0.9f)
        // A rim not far above the body in brightness, so the light melts in rather than sitting on top.
        light = hsl(h, s * 0.9f, (l + 0.03f).coerceIn(0.45f, 0.6f))
        // Same lightness looks far brighter in yellow-green than in red or blue, so the body and band
        // are darkened until their actual brightness keeps white text readable, whatever the hue.
        // Only a small hue step from the rim, so the card reads as one colour in different light.
        mid = capped(h - 4f, s * 0.85f, style.bodyLightness, MID_MAX_LUMINANCE)
        tone = capped(h + style.toneShift, s * 0.8f, style.bodyLightness - 0.04f, TONE_MAX_LUMINANCE)
        core = hsl(h, 0.7f, style.coreLightness)
        // A dimmer mood darkens the whole lit area, like turning the light down.
        val k = style.light().brightness.coerceIn(0f, 1f)
        if (k < 1f) {
            light = lerp(Color.Black, light, k)
            mid = lerp(Color.Black, mid, k)
            tone = lerp(Color.Black, tone, k)
        }
    }

    companion object {
        /** Relative luminance; white on it has a contrast of at least 1.05 / (0.18 + 0.05) = 4.5:1 (spec 2.1). */
        const val MID_MAX_LUMINANCE = 0.18f
        /** The band round the core stays a step darker than the body. */
        const val TONE_MAX_LUMINANCE = 0.14f
    }

    /** The colour at lightness [l], or darker until its actual brightness is at most [max]. */
    private fun capped(h: Float, s: Float, l: Float, max: Float) =
        generateSequence(l) { it - 0.01f }.takeWhile { it > 0.08f }
            .map { hsl(h, s, it) }
            .firstOrNull { it.luminance() <= max } ?: hsl(h, s, 0.08f)

    private fun hsl(h: Float, s: Float, l: Float) =
        Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

/** [c] as hue (degrees), saturation and lightness (0..1), in plain Kotlin so the palette can be checked off device. */
private fun hslOf(c: Color): Triple<Float, Float, Float> {
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return Triple(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (max) {
        c.red -> ((c.green - c.blue) / d).mod(6f)
        c.green -> (c.blue - c.red) / d + 2f
        else -> (c.red - c.green) / d + 4f
    } * 60f
    return Triple(h, s, l)
}
