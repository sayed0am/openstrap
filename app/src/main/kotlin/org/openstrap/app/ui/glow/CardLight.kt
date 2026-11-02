package org.openstrap.app.ui.glow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale

/**
 * A card lit by a soft light off its edge, as Ready's heart and the share cards are from below and the Heart rate zones
 * hero from the left: an ellipse [radiusX] across and [radiusY] tall around [center] in [color], through [stops]
 * (distance out from the centre, 0 to 1, to the colour's alpha there) to nothing at its rim. Written once so the three
 * lights stay one light.
 */
internal fun DrawScope.drawCardLight(color: Color, center: Offset, radiusX: Float, radiusY: Float, vararg stops: Pair<Float, Float>) {
    val colors = stops.map { (at, alpha) -> at to color.copy(alpha = alpha) } + (1f to Color.Transparent)
    // A circle as tall as the ellipse, stretched across to its width: the gradient stretches with it.
    scale(radiusX / radiusY, 1f, center) {
        drawCircle(Brush.radialGradient(*colors.toTypedArray(), center = center, radius = radiusY), radiusY, center)
    }
}
