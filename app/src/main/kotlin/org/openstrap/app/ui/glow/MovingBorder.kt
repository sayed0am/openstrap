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
import kotlinx.coroutines.flow.first

/**
 * A light travelling round a card's border, once every [periodMillis], brightest at its head and fading along its
 * tail, with a soft halo outside it. The one moving light on a screen, kept for the action it offers (Start a workout,
 * spec 2.5). It holds still while the list scrolls, the page is off screen or the workout covers it ([LocalGlowPaused],
 * [LocalGlowPagePaused]), as the glows do, and with the system's animations off the light rests at the top.
 */
@Composable
fun Modifier.movingBorder(color: Color, periodMillis: Int = MOVING_BORDER_PERIOD): Modifier {
    val context = LocalContext.current
    val animate = remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    // Read through state, so the running loop follows a newly provided lambda rather than the first one it saw.
    val listPaused by rememberUpdatedState(LocalGlowPaused.current)
    val pagePaused by rememberUpdatedState(LocalGlowPagePaused.current)
    // Degrees round the border; only the draw phase reads it, so a turn never recomposes the card.
    val angle = remember { mutableFloatStateOf(0f) }
    if (animate) LaunchedEffect(periodMillis) {
        val paused = { listPaused() || pagePaused() }
        var last = withFrameNanos { it }
        while (true) {
            if (paused()) {
                snapshotFlow { paused() }.first { !it }
                last = withFrameNanos { it }
            }
            withFrameNanos { now ->
                angle.floatValue = (angle.floatValue + (now - last) / 1e6f / periodMillis * 360f) % 360f
                last = now
            }
        }
    }
    return drawWithCache {
        val corner = GlowLayout.CardCorner.toPx()
        val thin = 1.5.dp.toPx()
        val halo = 7.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        // The tail fades in over the last third of the turn to the white-hot head; the rest of the border is dark.
        val head = lerp(color, Color.White, 0.7f)
        val stops = floatArrayOf(0f, 0.68f, 0.88f, 0.97f, 0.995f, 1f)
        val colors = intArrayOf(
            Color.Transparent.toArgb(), color.copy(alpha = 0f).toArgb(), color.copy(alpha = 0.55f).toArgb(),
            color.toArgb(), head.toArgb(), Color.Transparent.toArgb(),
        )
        val shader = SweepGradient(cx, cy, colors, stops)
        val matrix = Matrix()
        val brush = ShaderBrush(shader)
        val inset = thin / 2f
        onDrawWithContent {
            drawContent()
            // Starting at the top: the sweep's zero is at three o'clock.
            matrix.setRotate(angle.floatValue - 90f, cx, cy)
            shader.setLocalMatrix(matrix)
            drawRoundRect(brush, Offset(inset, inset), Size(size.width - thin, size.height - thin), CornerRadius(corner), alpha = 0.28f, style = Stroke(halo))
            drawRoundRect(brush, Offset(inset, inset), Size(size.width - thin, size.height - thin), CornerRadius(corner - inset), style = Stroke(thin))
        }
    }
}

/** One turn of [movingBorder] in milliseconds: slow enough to read as a light, not a spinner. */
const val MOVING_BORDER_PERIOD = 7_000
