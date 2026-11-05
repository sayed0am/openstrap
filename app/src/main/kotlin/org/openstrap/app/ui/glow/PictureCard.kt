package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.components.LocalCardNumbers
import org.openstrap.app.ui.components.cardOpening
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.glow.draw.EdgeSurface

/*
 * The middle tier (spec 2.5, Picture card): a dark card with a hairline that draws its metric's picture in the ink.
 * Unlit when usual (L0). Off your normal it takes the rim (L2, spec 2.1): a 2 dp rim in the ink lit next to the part of
 * the picture that is off, fading out across the card, with a faint wash of the ink inside. Static: the rim only
 * changes when the data does.
 */

/** A cool soft white for things that speak for no one metric: info sheets, the zone dots with no reading. */
val EdgeNeutral = Color.hsl(220f, 0.10f, 0.55f)

/**
 * Where a [PictureCard]'s rim is lit (spec 2.5, Picture card): the point [x], [y] as fractions of the card (0,0 top
 * left), next to the part of the picture that is off. The rim fades out over [reachX] of the card's width and [reachY]
 * of its height from there.
 */
@Immutable
data class Rim(val x: Float, val y: Float, val reachX: Float = 0.6f, val reachY: Float = 0.7f) {
    companion object {
        /** The right end, halfway down: the latest part of a line or a level past its band. */
        val End = Rim(1f, 0.5f, reachY = 1.1f)

        /** The top edge, centred: a dial's top. */
        val Top = Rim(0.5f, 0f, reachX = 0.7f, reachY = 0.8f)

        /** The bottom right corner: the latest of a week drawn along the foot. */
        val Corner = Rim(1f, 1f)
    }
}

/**
 * A screen's say over the next picture card: [plain] draws it as a list panel, a dark panel with a list's hairline,
 * for a card [varyLooks] moved to another look.
 */
@Immutable
data class CardOverride(val plain: Boolean = false)

val LocalCardOverride = staticCompositionLocalOf { CardOverride() }

private val CardShape = GlowLayout.CardShape

/** The rim's stroke, and how strong the ink is at its point, halfway out and at the end. */
private val RimWidth = 2.dp
private const val RIM_HALF_ALPHA = 0.35f
private const val RIM_HALF_AT = 0.55f

/** The wash inside the rim: the ink at 13%, over 80% of the rim's reach across and 70% down. */
private const val WASH_ALPHA = 0.13f
private const val WASH_REACH_X = 0.8f
private const val WASH_REACH_Y = 0.7f

/**
 * A picture card in [ink], its metric's colour (spec 2.5). With a [rim] it's off your normal and lights there; the
 * screen's [LocalGlowMood] softens the rim. [content] lays out the title row, the picture, the number and its tag.
 */
@Composable
fun PictureCard(
    ink: Color,
    modifier: Modifier = Modifier,
    rim: Rim? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = GlowLayout.CardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val plain = LocalCardOverride.current.plain
    val mood = LocalGlowMood.current
    // Tapped, the card grows into what it opens (spec 3.5).
    val opening = cardOpening(onClick, CardShape)
    Column(
        modifier
            .then(opening?.modifier ?: Modifier)
            .let { if (onClick != null) it.pressClickable(opening?.onClick ?: onClick, CardShape) else it }
            .let {
                if (plain) it.clip(CardShape).background(ListSurface, CardShape).border(1.dp, CardHairline, CardShape)
                else it.clip(CardShape).background(EdgeSurface, CardShape).border(1.dp, CardHairline, CardShape)
                    .let { m -> if (rim != null) m.rimLight(rim, ink, mood.edge) else m }
            }
            .padding(contentPadding),
    ) {
        CompositionLocalProvider(LocalCardNumbers provides opening?.numbers, LocalCardOpens provides (onClick != null)) { content() }
    }
}

/** The edge round a card or list panel ([GlowLayout.CardEdgeAlpha]). */
internal val CardHairline = Color.White.copy(alpha = GlowLayout.CardEdgeAlpha)

/** An elliptical radial gradient centred on [c] with radii [rx] and [ry], [colors] at [stops]. */
private fun ellipse(c: Offset, rx: Float, ry: Float, colors: List<Color>, stops: List<Float>) = object : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        RadialGradientShader(c, rx, colors, stops).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, ry / rx, c.x, c.y) })
        }
}

/** The L2 rim (spec 2.1) at [rim] in [ink], scaled by the mood's [strength], over the card's surface. */
private fun Modifier.rimLight(rim: Rim, ink: Color, strength: Float): Modifier = drawWithCache {
    val c = Offset(rim.x * size.width, rim.y * size.height)
    val rx = (rim.reachX * size.width).coerceAtLeast(1f)
    val ry = (rim.reachY * size.height).coerceAtLeast(1f)
    val wash = ellipse(c, rx * WASH_REACH_X, ry * WASH_REACH_Y, listOf(ink.copy(alpha = WASH_ALPHA * strength), Color.Transparent), listOf(0f, 1f))
    val edge = ellipse(
        c, rx, ry,
        listOf(ink.copy(alpha = strength), ink.copy(alpha = RIM_HALF_ALPHA * strength), Color.Transparent),
        listOf(0f, RIM_HALF_AT, 1f),
    )
    val w = RimWidth.toPx()
    val corner = GlowLayout.CardCorner.toPx()
    onDrawBehind {
        drawRect(wash)
        drawRoundRect(
            edge, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(corner - w / 2), style = Stroke(w),
        )
    }
}
