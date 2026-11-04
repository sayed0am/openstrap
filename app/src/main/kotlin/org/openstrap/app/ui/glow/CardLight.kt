package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.glow.draw.EdgeSurface
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.draw.LightAngle
import org.openstrap.app.ui.glow.draw.chart

/**
 * A card lit by a soft light off its edge, as Ready's heart and the share cards are from below and the Heart rate zones
 * hero from the left: an ellipse [radiusX] across and [radiusY] tall around [center] in [color], through [stops]
 * (distance out from the centre, 0 to 1, to the colour's alpha there) to nothing at its rim. Written once so the three
 * lights stay one light.
 */
internal fun DrawScope.drawCardLight(color: Color, center: Offset, radiusX: Float, radiusY: Float, stops: List<Pair<Float, Float>>) {
    val colors = stops.map { (at, alpha) -> at to color.copy(alpha = alpha) } + (1f to Color.Transparent)
    // A circle as tall as the ellipse, stretched across to its width: the gradient stretches with it.
    scale(radiusX / radiusY, 1f, center) {
        @Suppress("SpreadOperator") // Brush takes its stops only as varargs, and there are three or four of them.
        val brush = Brush.radialGradient(*colors.toTypedArray(), center = center, radius = radiusY)
        drawCircle(brush, radiusY, center)
    }
}

/**
 * A card on the edge surface lit by one [drawCardLight] in [color]: centred at [lightCenter] and [reach] across and
 * tall, both worked out from the card's size, through [stops]. The rim is [color] at 30%. Not a [GlowCard]: its light
 * is one soft radial, as Ready's heart and the max heart rate's hero have it, not the glow's rendered layers.
 */
// Callers pass a fixed list of three or four stops and never change it in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
@Composable
internal fun LitPanel(
    color: Color,
    stops: List<Pair<Float, Float>>,
    lightCenter: (Size) -> Offset,
    reach: (Size) -> Size,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(GlowLayout.CardShape).background(EdgeSurface)
            .drawBehind {
                val r = reach(size)
                drawCardLight(color, lightCenter(size), r.width, r.height, stops)
            }
            .border(1.dp, color.copy(alpha = 0.3f), GlowLayout.CardShape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * A workout's hero glow (spec 2.5): [color] lit from below as high as the workout's [effort] (0..1, more is held at
 * full), so an easy walk leaves most of the card dark and a hard run floods it. The past workout's hero, the summary's
 * and its heart rate drop all light this way.
 */
internal fun effortGlow(color: Color, effort: Float) =
    GlowStyle.chart(color).copy(lightAngle = LightAngle.Below, lightLevel = effort.coerceAtMost(1f))
