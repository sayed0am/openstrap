package org.openstrap.app.ui.glow

import android.graphics.Matrix
import android.graphics.SweepGradient
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import org.openstrap.app.ui.theme.MetricColors

/**
 * A chroma ring round a card's border: a faint ring always there, with three lights sliding round it at their own
 * speeds, each taking the cards' own colours ([Rainbow]) as it goes, and a fine shimmer along the ring. The one moving
 * light on a screen, kept for the action it offers (Start a workout, spec 2.5). It holds still while the list scrolls,
 * the page is off screen or the workout covers it ([LocalGlowPaused], [LocalGlowPagePaused]), as the glows do, and with
 * the system's animations off the ring rests where it starts.
 */
@Composable
fun Modifier.movingBorder(): Modifier {
    val context = LocalContext.current
    val animate = remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    // Read through state, so the running loop follows a newly provided lambda rather than the first one it saw.
    val listPaused by rememberUpdatedState(LocalGlowPaused.current)
    val pagePaused by rememberUpdatedState(LocalGlowPagePaused.current)
    // Seconds the ring has run; only the draw phase reads it, so a frame never recomposes the card.
    val time = remember { mutableFloatStateOf(0f) }
    if (animate) LaunchedEffect(Unit) {
        val paused = { listPaused() || pagePaused() }
        var last = withFrameNanos { it }
        while (true) {
            if (paused()) {
                snapshotFlow { paused() }.first { !it }
                last = withFrameNanos { it }
            }
            withFrameNanos { now ->
                // Wrapped well past every cycle in it, so the float keeps its precision.
                time.floatValue = (time.floatValue + (now - last) / 1e9f) % RING_WRAP_SECONDS
                last = now
            }
        }
    }
    return drawWithCache {
        val corner = GlowLayout.CardCorner.toPx()
        val ring = RingWidth.toPx()
        val halo = HaloWidth.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val inset = ring / 2f
        val colors = IntArray(RING_SAMPLES + 1)
        val glows = IntArray(RING_SAMPLES + 1)
        val stops = FloatArray(RING_SAMPLES + 1) { it.toFloat() / RING_SAMPLES }
        val matrix = Matrix().apply { setRotate(-90f, cx, cy) }
        onDrawWithContent {
            drawContent()
            chromaRing(time.floatValue, colors, glows)
            // Starting at the top: the sweep's zero is at three o'clock.
            val ringShader = SweepGradient(cx, cy, colors, stops).apply { setLocalMatrix(matrix) }
            val glowShader = SweepGradient(cx, cy, glows, stops).apply { setLocalMatrix(matrix) }
            val box = Size(size.width - ring, size.height - ring)
            drawRoundRect(ShaderBrush(glowShader), Offset(inset, inset), box, CornerRadius(corner), style = Stroke(halo))
            drawRoundRect(ShaderBrush(ringShader), Offset(inset, inset), box, CornerRadius(corner - inset), style = Stroke(ring))
        }
    }
}

/**
 * Fills [ring] and [glow] with the ring's colour at each of [RING_SAMPLES] steps round it, [t] seconds in: a faint grey
 * base, three lights each a quarter turn wide at their own speeds, their colour running through [Rainbow] twice round
 * the ring and drifting with time, and a shimmer of value noise. [glow] is the lights alone, for the soft halo.
 */
private fun chromaRing(t: Float, ring: IntArray, glow: IntArray) {
    for (i in ring.indices) {
        val a = i.toFloat() / RING_SAMPLES
        var lights = 0f
        for (l in LightSpeeds.indices) {
            val p = frac(a - t * LightSpeeds[l] + l / 3f)
            val v = (1f - abs(p - 0.5f) * 8f).coerceAtLeast(0f)
            lights += v * v
        }
        val sparkle = noise(a * 20f, t * 6f) * 0.5f + noise(a * 40f + 100f, t * 9f) * 0.3f
        val hue = rainbowAt(a * 2f + t * 1.5f)
        val light = lerp(RingGlow, hue, 0.6f)
        val r = RingBase.red * 1.3f + light.red * lights * 1.5f + RingGlow.red * sparkle * 0.3f
        val g = RingBase.green * 1.3f + light.green * lights * 1.5f + RingGlow.green * sparkle * 0.3f
        val b = RingBase.blue * 1.3f + light.blue * lights * 1.5f + RingGlow.blue * sparkle * 0.3f
        val alpha = 0.4f + lights * 0.6f + sparkle * 0.2f
        ring[i] = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f)).toArgb()
        glow[i] = hue.copy(alpha = (lights * HALO_ALPHA).coerceIn(0f, HALO_ALPHA)).toArgb()
    }
}

/** [Rainbow] as a loop: [x] in turns, one turn running through every colour and back to the first. */
private fun rainbowAt(x: Float): Color {
    val f = frac(x) * Rainbow.size
    val i = f.toInt() % Rainbow.size
    return lerp(Rainbow[i], Rainbow[(i + 1) % Rainbow.size], f - f.toInt())
}

private fun frac(x: Float) = x - floor(x)

private fun hash(x: Float, y: Float): Float = frac(sin(x * 127.1f + y * 311.7f) * 43758.547f)

/** Smooth value noise on a unit grid, 0 to 1. */
private fun noise(x: Float, y: Float): Float {
    val ix = floor(x)
    val iy = floor(y)
    var fx = x - ix
    var fy = y - iy
    fx = fx * fx * (3f - 2f * fx)
    fy = fy * fy * (3f - 2f * fy)
    val top = hash(ix, iy) + (hash(ix + 1f, iy) - hash(ix, iy)) * fx
    val bottom = hash(ix, iy + 1f) + (hash(ix + 1f, iy + 1f) - hash(ix, iy + 1f)) * fx
    return top + (bottom - top) * fy
}

/**
 * The lights' colours: the cards' own colours in spectrum order, Heart's red, Energy's orange, Calories' gold, Steps'
 * lime, Recovery's teal, Sleep's blue and the evening's violet, looping back to red.
 */
private val Rainbow = with(MetricColors) { listOf(heart, charge, calories, steps, readiness, sleepDeep, evening) }

/** The ring's dim base, and the white the lights are mixed with so they glow rather than shout. */
private val RingBase = Color(0xFF333340)
private val RingGlow = Color(0xFFC0C8E0)

/** Each light's speed in turns a second: a turn every 2 to 3 s, so they pass and gather. */
private val LightSpeeds = floatArrayOf(0.47f, 0.38f, 0.31f)

private val RingWidth = 2.dp
private val HaloWidth = 6.dp
private const val HALO_ALPHA = 0.3f

/** Steps round the ring: enough for the shimmer's 40 cells a turn. */
private const val RING_SAMPLES = 160

/** A whole number of every light's turns and the colours' drift, so wrapping the clock never jumps. */
private const val RING_WRAP_SECONDS = 2000f
