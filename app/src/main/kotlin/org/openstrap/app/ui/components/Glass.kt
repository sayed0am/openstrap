package org.openstrap.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.graphics.graphicsLayer
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch

/** The nav bar's glass: near-black, see-through, with a lit top edge. Popups use it over a blurred screen. */
val GlassTint = Color(0xFF0B0B0D)
val GlassEdge = BorderStroke(
    1.dp, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.2f), 0.5f to Color.White.copy(alpha = 0.05f), 1f to Color.Black.copy(alpha = 0.4f)),
)

/** The screen's cards go OLED black with a faint hairline instead of grey fills, matching the glow tabs. */
@Composable
fun OledSurfaces(content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = c.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color(0xFF101012),
            surfaceContainerHighest = Color(0xFF1A1A1D),
            outline = Color.White.copy(alpha = 0.16f),
            outlineVariant = Color.White.copy(alpha = 0.12f),
        ),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

/* ------------------------------------------- In-app overlay ----------------------------------------- */

/**
 * Popups drawn inside the app's own window, over a blur of the page, like the nav bar. A popup in its own window
 * can only blur what's behind it when the phone allows cross-window blur (many don't); drawn here, the blur
 * always works. Popups declared anywhere register here and the root draws them on top of everything.
 */
class GlassHost {
    internal val layers = androidx.compose.runtime.mutableStateListOf<GlassLayer>()
}

internal class GlassLayer(
    val sheet: Boolean,
    val onDismiss: androidx.compose.runtime.State<() -> Unit>,
    val content: androidx.compose.runtime.State<@Composable () -> Unit>,
)

/** The overlay host, provided at the app's root. */
val LocalGlassHost = staticCompositionLocalOf<GlassHost> { error("No GlassHost provided") }

/** Shows [content] in the overlay for as long as this is in the composition. */
@Composable
private fun GlassPortal(host: GlassHost, sheet: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val d = rememberUpdatedState(onDismiss)
    val c = androidx.compose.runtime.rememberUpdatedState(content)
    DisposableEffect(host) {
        val layer = GlassLayer(sheet, d, c)
        host.layers += layer
        onDispose { host.layers -= layer }
    }
}

/** The page behind a popup: blurred and darkened, the nav bar's glass spread across the screen. */
private val BackdropBlur = dev.chrisbanes.haze.blur.HazeBlurStyle {
    blurRadius(28.dp)
    backgroundColor(Color.Black)
    colorEffects(listOf(dev.chrisbanes.haze.blur.HazeColorEffect.tint(Color.Black.copy(alpha = 0.45f))))
    fallbackColorEffect(dev.chrisbanes.haze.blur.HazeColorEffect.tint(Color.Black.copy(alpha = 0.8f)))
}

/** Draws the registered popups; place it last at the root, over everything, with the pages' [haze]. */
@Composable
fun GlassOverlay(host: GlassHost, haze: dev.chrisbanes.haze.HazeState) {
    host.layers.forEach { layer ->
        androidx.compose.runtime.key(layer) {
            androidx.activity.compose.BackHandler { layer.onDismiss.value() }
            val shown = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
            androidx.compose.runtime.LaunchedEffect(Unit) { shown.animateTo(1f, androidx.compose.animation.core.tween(220)) }
            val tap = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = shown.value }
                    .hazeBlur(HazeInput.Sources(haze), BackdropBlur)
                    .clickable(tap, indication = null) { layer.onDismiss.value() },
            )
            Box(
                Modifier.fillMaxSize().imePadding().let { if (layer.sheet) it else it.systemBarsPadding().padding(24.dp) }
                    .graphicsLayer {
                        alpha = shown.value
                        translationY = (1f - shown.value) * (if (layer.sheet) 120.dp.toPx() else 16.dp.toPx())
                    },
                contentAlignment = if (layer.sheet) Alignment.BottomCenter else Alignment.Center,
            ) {
                // The panel takes its own taps, so tapping it doesn't dismiss.
                Box(Modifier.clickable(androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}) {
                    layer.content.value()
                }
            }
        }
    }
}

/**
 * The glass panel a popup sits on: see-through dark over the blurred page, with a lit top edge. Android 11 can't blur
 * the page (Haze needs 12), so there it is near solid, or the page's text reads through the popup's.
 */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = org.openstrap.app.ui.glow.GlowLayout.CardShape, content: @Composable () -> Unit) {
    Surface(
        shape = shape, color = GlassTint.copy(alpha = GlassPanelAlpha), border = GlassEdge, contentColor = Color.White,
        modifier = modifier, content = content,
    )
}

private val GlassPanelAlpha = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) 0.72f else 0.96f

/**
 * A bottom sheet as glass in the overlay, at most [org.openstrap.app.ui.glow.GlowLayout.PageMaxWidth] wide and centred.
 * Pulled down by its handle, or past the top of its list, it follows the
 * finger; let go far enough down (or flung) and it closes, otherwise it springs back.
 */
@Composable
fun GlassSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    GlassPortal(LocalGlassHost.current, sheet = true, onDismiss = onDismiss) {
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val dismiss = rememberUpdatedState(onDismiss)
        val pull = remember { mutableFloatStateOf(0f) }
        val release: (Float) -> Unit = { velocity ->
            if (pull.floatValue > with(density) { 120.dp.toPx() } || velocity > with(density) { 800.dp.toPx() }) dismiss.value()
            else scope.launch {
                animate(pull.floatValue, 0f, animationSpec = tween(Motion.QUICK, easing = Motion.easing)) { v, _ -> pull.floatValue = v }
            }
        }
        val pullOnTop = remember {
            object : NestedScrollConnection {
                // Pushing back up takes the pull away before the list scrolls.
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0f || pull.floatValue <= 0f) return Offset.Zero
                    val used = maxOf(available.y, -pull.floatValue)
                    pull.floatValue += used
                    return Offset(0f, used)
                }

                // What the list can't scroll down any further pulls the sheet.
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                    pull.floatValue += available.y
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (pull.floatValue <= 0f) return Velocity.Zero
                    release(available.y)
                    return available
                }
            }
        }
        // At most a page's column wide, centred, on a wide screen (spec 2.4 Unfolded).
        GlassPanel(
            Modifier.widthIn(max = org.openstrap.app.ui.glow.GlowLayout.PageMaxWidth).fillMaxWidth()
                .graphicsLayer { translationY = pull.floatValue }
                .nestedScroll(pullOnTop)
                .draggable(
                    rememberDraggableState { d -> pull.floatValue = (pull.floatValue + d).coerceAtLeast(0f) },
                    Orientation.Vertical, onDragStopped = { v -> release(v) },
                ),
            org.openstrap.app.ui.glow.GlowLayout.SheetShape,
        ) {
            Column {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)))
                }
                content()
            }
        }
    }
}
