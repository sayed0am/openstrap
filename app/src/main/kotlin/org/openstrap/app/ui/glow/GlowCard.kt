package org.openstrap.app.ui.glow

import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.openstrap.app.ui.components.LocalCardNumbers
import org.openstrap.app.ui.components.cardOpening
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.glow.draw.brush
import org.openstrap.app.ui.glow.draw.drawGlowBase
import org.openstrap.app.ui.glow.draw.drawGlowCore
import org.openstrap.app.ui.glow.draw.glowPaint
import org.openstrap.app.ui.glow.draw.grainShader
import org.openstrap.app.ui.glow.draw.randomGrainSeed
import org.openstrap.app.ui.glow.draw.GlowMotion
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.draw.CoreBox
import org.openstrap.app.ui.glow.draw.GlowPalette
import org.openstrap.app.ui.glow.draw.light

/**
 * A card in the glow language (see [GlowStyle]). Layers, back to front: rim-coloured fill, rim light,
 * second-tone band, dark core, [content], film grain, hairline edge.
 *
 * The dark core holds still unless the style's [GlowMotion] is a [GlowMotion.Heartbeat] (live heart rate),
 * and always when the system's animations are off. The screen's [LocalGlowMood] is applied on top of the style.
 *
 * Built to stay cheap while scrolling: everything blurred is rendered once per style and size, off the
 * main thread, into GPU bitmaps that are cached across cards (see [GlowCache]). The core moves on its
 * own layers by changing their position, scale and opacity only, so a moving card never re-draws its
 * text or charts and never re-blurs anything.
 */
/**
 * Whether glow cards should hold still right now; a scrolling screen provides its scroll state, so
 * the cores pause while the list moves and the scroll gets every frame to itself.
 */
val LocalGlowPaused = staticCompositionLocalOf<() -> Boolean> { { false } }

/**
 * Whether the page holding these cards is off screen or the tab pager is moving; provided by the pager, apart from
 * [LocalGlowPaused] so a screen's own scroll state doesn't override it. Cores hold still then, so a swipe between
 * tabs gets every frame to itself and the pages kept ready either side don't animate unseen.
 */
val LocalGlowPagePaused = staticCompositionLocalOf<() -> Boolean> { { false } }

@Composable
fun GlowCard(
    style: GlowStyle,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = GlowLayout.CardPadding,
    /** A passing lift of the light (a personal best), faded over the card's own layers, so it never renders anew. */
    swell: GlowSwell? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val lit = style.copy(mood = LocalGlowMood.current)
    val shape = remember(lit.radius) { RoundedCornerShape(lit.radius) }
    val palette = remember(lit) { GlowPalette(lit) }
    val core = remember(lit) { lit.light().core }
    val slab = remember(palette) { lerp(palette.mid, Color.Black, 0.55f) }
    // Each card samples the grain tile from its own spot, so neighbouring cards never share a pattern.
    val grainSeed = remember { randomGrainSeed(Random) }
    val drift = remember { CoreDrift(Random) }
    val seconds = remember { mutableFloatStateOf(0f) }
    // Beats so far, counted frame by frame at the bpm of the moment, so a new reading changes the pace from here on
    // rather than jumping the heart to another point in its cycle.
    val beats = remember { mutableFloatStateOf(0f) }
    val bpm by rememberUpdatedState((lit.motion as? GlowMotion.Heartbeat)?.bpm ?: 0)
    val context = LocalContext.current
    val density = LocalDensity.current
    // When the system's animator scale is 0 the heart doesn't beat: the core sits at its resting state.
    val animate = lit.motion is GlowMotion.Heartbeat && remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val listPaused = LocalGlowPaused.current
    val pagePaused = LocalGlowPagePaused.current
    val paused = { listPaused() || pagePaused() }
    if (animate) LaunchedEffect(Unit) {
        // Time only advances while the card isn't paused, so the core picks up where it stopped.
        var last = withFrameNanos { it }
        while (true) {
            if (paused()) {
                snapshotFlow { paused() }.first { !it }
                last = withFrameNanos { it }
            }
            withFrameNanos { now ->
                val dt = (now - last) / 1e9f
                seconds.floatValue += dt
                beats.floatValue += dt * bpm / 60f
                last = now
            }
        }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    // The motion doesn't change how the card looks at rest, so it isn't part of the cache key.
    // A live heart rate changes the motion every reading; keyed on it, each new bpm would drop the drawn layers and
    // show the plain body colour until they're redrawn, a flash on every beat update.
    val still = remember(lit) { lit.copy(motion = GlowMotion.Still) }
    val key = GlowCache.Key(still, size, density.density)
    val bitmaps by produceState(GlowCache.peek(key) ?: GlowCache.latest(still, density.density), key) {
        // The layers for this key once there are some. Until then the last ones stay, or the last made for this look
        // at any size, stretched to the card's (a detail opening or closing beside the tab rebuilds and resizes the
        // cards): the plain body colour in between would flash.
        GlowCache.peek(key)?.let { value = it; return@produceState }
        if (value == null) value = GlowCache.latest(still, density.density)
        if (key.size == IntSize.Zero || lit.outline) return@produceState
        value = withContext(Dispatchers.Default) { GlowCache.get(key) }
    }
    // The swell's peak is one more cached look, rendered once: each frame of the swell only changes how much of it shows.
    val peak = swell?.peak?.takeIf { it != still.lightLevel && !lit.outline }
    val peakKey = peak?.let { GlowCache.Key(still.copy(lightLevel = it), size, density.density) }
    val peakBitmaps by produceState(peakKey?.let(GlowCache::peek), peakKey) {
        value = peakKey?.let(GlowCache::peek)
        if (peakKey == null || value != null || peakKey.size == IntSize.Zero) return@produceState
        value = withContext(Dispatchers.Default) { GlowCache.get(peakKey) }
    }
    val peakPaint = remember { Paint(FILTER) }

    // Tapped, the card grows into what it opens (spec 3.5).
    val opening = cardOpening(onClick, shape)
    Box(
        modifier
            .then(opening?.modifier ?: Modifier)
            .let { if (onClick != null) it.pressClickable(opening?.onClick ?: onClick, shape) else it }
            // The slab's edge: a darker copy of the card showing underneath, for a 3D look.
            .let {
                if (lit.depth > 0.dp) it.drawBehind {
                    val d = lit.depth.toPx()
                    drawRoundRect(slab, topLeft = Offset(0f, d), size = this.size, cornerRadius = CornerRadius(lit.radius.toPx()))
                } else it
            }
            .clip(shape)
            .onSizeChanged { size = it },
        // Content gets the card's full size, e.g. to centre itself in a fixed-height card.
        propagateMinConstraints = true,
    ) {
        val b = bitmaps.takeUnless { lit.outline }
        // Base: fill and rim light. Until its bitmap is ready, the plain mid colour; an outline card is black.
        Spacer(
            Modifier.matchParentSize().drawBehind {
                val w = this.size.width
                val h = this.size.height
                // A card built anew (a detail closing beside the tab) draws once before it knows its size: the layers
                // already made for the size it's drawn at stand in, core and all, for that frame.
                val first = if (b == null && !lit.outline) {
                    GlowCache.peek(GlowCache.Key(still, IntSize(w.roundToInt(), h.roundToInt()), density.density)) ?: GlowCache.latest(still, density.density)
                } else null
                when {
                    lit.outline -> drawRect(Color.Black)
                    first != null -> drawIntoCanvas {
                        it.nativeCanvas.drawBitmap(first.base, null, first.baseIn(w, h), FILTER)
                        it.nativeCanvas.drawBitmap(first.core, null, first.coreIn(w, h), Paint(FILTER).apply { alpha = (lit.coreOpacity * 255).roundToInt().coerceIn(0, 255) })
                    }
                    b == null -> drawRect(palette.mid)
                    else -> drawIntoCanvas { it.nativeCanvas.drawBitmap(b.base, null, b.baseIn(w, h), FILTER) }
                }
                // Read here, so the swell redraws this layer each frame and composes nothing.
                val lifted = peakBitmaps
                val lift = if (b != null && lifted != null) swell?.amount?.invoke() ?: 0f else 0f
                if (lifted != null && lift > 0f) drawIntoCanvas {
                    peakPaint.alpha = (lift.coerceIn(0f, 1f) * 255).roundToInt()
                    it.nativeCanvas.drawBitmap(lifted.base, null, lifted.baseIn(w, h), peakPaint)
                }
            },
        )
        if (b != null) {
            // The core on its own layer: moved and scaled, never redrawn.
            CoreLayer(b, lit, core) { drift.at(seconds.floatValue, beats.floatValue, lit.motion) }
        }
        CompositionLocalProvider(LocalCardNumbers provides opening?.numbers, LocalCardOpens provides (onClick != null)) {
            Box(Modifier.padding(contentPadding), content = content)
        }
        // Grain and the hairline edge, on top of everything; static.
        Spacer(Modifier.matchParentSize().drawWithCache { overlay(lit, grainSeed) })
    }
}

/**
 * A passing lift of a card's light to [peak] (its [GlowStyle.lightLevel] at the top), shown as much as [amount] says,
 * 0 to 1. [amount] is read only while drawing, so an animated swell recomposes nothing.
 */
@androidx.compose.runtime.Immutable
class GlowSwell(val peak: Float, val amount: () -> Float)

/** The pre-blurred core, moved by the motion through layer properties only. */
@Composable
private fun BoxScope.CoreLayer(b: GlowBitmaps, style: GlowStyle, core: CoreBox, motion: () -> Drift) {
    Spacer(
        Modifier
            .matchParentSize()
            .graphicsLayer {
                // Read here, so motion only updates this layer's properties; nothing is redrawn.
                val m = motion()
                translationX = m.dx * size.width
                translationY = m.dy * size.height
                // A squash widens the core a touch and flattens it as much: a squeeze, not a resize.
                scaleX = m.scale * (1f + m.squash)
                scaleY = m.scale * (1f - m.squash)
                transformOrigin = TransformOrigin(core.x, core.y)
                alpha = (style.coreOpacity * m.fade).coerceIn(0f, 1f)
                // Fade by scaling each pixel's alpha instead of rendering the layer offscreen first.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawBehind { drawIntoCanvas { it.nativeCanvas.drawBitmap(b.core, null, b.coreIn(size.width, size.height), FILTER) } },
    )
}

private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)

/** Grain and the hairline edge with its gloss, from the shared light ([glowPaint], as a widget's background draws them). */
private fun CacheDrawScope.overlay(style: GlowStyle, grainSeed: Int): DrawResult {
    val spec = glowPaint(style, size.width, size.height, density)
    val outline = RoundedCornerShape(style.radius).createOutline(size, layoutDirection, this)
    // On hold: only the edge, in the card's colour.
    spec.outline?.let { color -> return onDrawBehind { drawOutline(outline, color, style = Stroke(spec.outlineWidth)) } }
    val grainPaint = Paint().apply {
        shader = grainShader(grainSeed)
        alpha = spec.grainAlpha
    }
    val hairline = Stroke(spec.hairlineWidth)
    val gloss = spec.gloss.brush()
    return onDrawBehind {
        if (spec.grainAlpha > 0) drawIntoCanvas { it.nativeCanvas.drawPaint(grainPaint) }
        drawOutline(outline, spec.hairline, style = hairline)
        drawOutline(outline, gloss, style = hairline)
    }
}

/** A card's pre-rendered layers, and where to draw them (card coordinates). */
private class GlowBitmaps(
    val base: android.graphics.Bitmap,
    val core: android.graphics.Bitmap,
    val baseDst: RectF,
    val coreDst: RectF,
) {
    /** Where the base goes on a card [w] by [h]: its own place, or stretched over a card of another size. */
    fun baseIn(w: Float, h: Float): RectF = if (fits(w, h)) baseDst else RectF(0f, 0f, w, h)

    /** Where the core goes on a card [w] by [h], its margin stretched with the card. */
    fun coreIn(w: Float, h: Float): RectF {
        if (fits(w, h)) return coreDst
        val sx = w / baseDst.width()
        val sy = h / baseDst.height()
        return RectF(coreDst.left * sx, coreDst.top * sy, coreDst.right * sx, coreDst.bottom * sy)
    }

    private fun fits(w: Float, h: Float) = w == baseDst.width() && h == baseDst.height()
}

/**
 * Rendered glow layers, shared by every card with the same look and size. Rendering blurs is the
 * expensive part of a glow card; with the cache, a card scrolling back into view costs nothing.
 */
private object GlowCache {
    data class Key(val style: GlowStyle, val size: IntSize, val density: Float)

    private val entries = object : LinkedHashMap<Key, GlowBitmaps>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, GlowBitmaps>?) = size > MAX_ENTRIES
    }

    fun peek(key: Key): GlowBitmaps? = synchronized(entries) { entries[key] }

    /** The layers last used for [style] at any size, to stretch over a card until its own size's are made. */
    fun latest(style: GlowStyle, density: Float): GlowBitmaps? = synchronized(entries) {
        entries.entries.lastOrNull { it.key.style == style && it.key.density == density }?.value
    }

    fun get(key: Key): GlowBitmaps {
        peek(key)?.let { return it }
        val built = render(key.style, key.size.width.toFloat(), key.size.height.toFloat(), Density(key.density))
        return synchronized(entries) { entries.getOrPut(key) { built } }
    }

    /** Enough for every card on a screen and the tabs either side, plus the ones just scrolled past. */
    private const val MAX_ENTRIES = 16
}

/**
 * Renders the base and the core layer at half resolution (blur hides it), as GPU bitmaps, with the shared light
 * drawing ([glowPaint], [drawGlowBase], [drawGlowCore]) a widget's background uses too.
 */
private fun render(style: GlowStyle, w: Float, h: Float, density: Density): GlowBitmaps {
    val paint = glowPaint(style, w, h, density.density)
    val base = halfResBitmap(w, h, 0f) { canvas -> drawGlowBase(canvas, paint) }
    val margin = paint.coreMargin
    val coreLayer = halfResBitmap(w, h, margin) { canvas -> drawGlowCore(canvas, paint) }
    return GlowBitmaps(
        base = base,
        core = coreLayer,
        baseDst = RectF(0f, 0f, w, h),
        coreDst = RectF(-margin, -margin, w + margin, h + margin),
    )
}

/** A bitmap at half the card's resolution covering the card plus [margin] on every side; [draw] gets card coordinates. */
private fun halfResBitmap(w: Float, h: Float, margin: Float, draw: (android.graphics.Canvas) -> Unit): android.graphics.Bitmap {
    val scale = 0.5f
    val bmp = android.graphics.Bitmap.createBitmap(
        ((w + margin * 2) * scale).roundToInt().coerceAtLeast(1),
        ((h + margin * 2) * scale).roundToInt().coerceAtLeast(1),
        android.graphics.Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(bmp)
    canvas.scale(scale, scale)
    canvas.translate(margin, margin)
    draw(canvas)
    // Upload to the GPU now, on this background thread, rather than on the first frame that draws it.
    return bmp.copy(android.graphics.Bitmap.Config.HARDWARE, false)?.also { bmp.recycle() } ?: bmp
}

/** How the dark core sits: offset (fractions of the card), scale, and [fade] on top of the style's core opacity. */
private class Drift(val dx: Float, val dy: Float, val scale: Float, val fade: Float = 1f, val squash: Float = 0f)

/**
 * The core's motion over time. Under the heartbeat is a small never-repeating wander: each axis is two
 * sine waves with unrelated periods (10–24 s), and every card gets its own periods and phases.
 */
private class CoreDrift(private val random: Random) {
    private fun wave() = Wave(period = 10f + random.nextFloat() * 14f, phase = random.nextFloat() * 2f * PI.toFloat())
    private val x = wave() to wave()
    private val y = wave() to wave()
    private val breath = wave()
    /** Where in its cycle each card starts, so two heart cards don't beat in lockstep. */
    private val offset = random.nextFloat() * 10f

    fun at(time: Float, beatsSoFar: Float, motion: GlowMotion): Drift {
        val t = time + offset
        return when (motion) {
            GlowMotion.Still -> Drift(0f, 0f, 1f)
            is GlowMotion.Heartbeat -> {
                // The dark core is the heart: it squeezes on each beat (lub, then a smaller dub) and relaxes,
                // always as dark, so nothing flashes. Each beat also pushes it a little off centre, a new way each
                // time, and squashes it, so the colour around it surges past a different side, like blood.
                val base = wander(t, amount = 0.4f)
                val beats = beatsSoFar + offset
                val c = contraction(beats)
                val angle = beatAngle(floor(beats).toInt())
                Drift(
                    dx = base.dx + 0.022f * c * cos(angle),
                    dy = base.dy + 0.018f * c * sin(angle),
                    scale = base.scale * (1f - 0.075f * c),
                    squash = 0.025f * c * cos(2f * angle),
                )
            }
        }
    }

    private fun wander(t: Float, amount: Float) = Drift(
        dx = 0.035f * amount * (0.65f * x.first(t) + 0.35f * x.second(t)),
        dy = 0.03f * amount * (0.65f * y.first(t) + 0.35f * y.second(t)),
        // Breathing in: the core swells a little; breathing out, it settles.
        scale = 1f + 0.025f * amount * breath(t),
    )

    private class Wave(val period: Float, val phase: Float) {
        operator fun invoke(t: Float) = sin(2f * PI.toFloat() * t / period + phase)
    }
}

/**
 * How hard the heart is squeezing over one beat (phase in beats), 0..1: a quick lub, a smaller dub just after,
 * each tightening fast and letting go more slowly, then rest until the next beat.
 */
private fun contraction(phase: Float): Float {
    val f = phase - floor(phase)
    // Fast in, slow out: a skewed bump that rises over [rise] and decays over [fall].
    fun squeeze(at: Float, rise: Float, fall: Float): Float {
        val d = f - at
        val w = if (d < 0f) rise else fall
        return exp(-(d / w).let { it * it })
    }
    return (squeeze(0.08f, 0.035f, 0.09f) + 0.55f * squeeze(0.30f, 0.03f, 0.08f)).coerceAtMost(1f)
}

/** Which way beat [n] pushes the core: a fixed scramble of the beat number, so each beat goes its own way. */
private fun beatAngle(n: Int): Float {
    var h = n * 0x9E3779B1.toInt()
    h = h xor (h ushr 15)
    h *= 0x85EBCA6B.toInt()
    h = h xor (h ushr 13)
    return (h ushr 8) / (1 shl 24).toFloat() * 2f * PI.toFloat()
}
