package org.openstrap.app.ui.glow.draw

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.ui.glow.Edge
import org.openstrap.app.ui.glow.EdgeKind
import org.openstrap.app.ui.glow.EdgeStatus
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowMood
import org.openstrap.app.ui.glow.GlowPalette
import org.openstrap.app.ui.glow.GlowStyle
import org.openstrap.app.ui.glow.LightAngle
import org.openstrap.app.ui.glow.SoftGlow
import org.openstrap.app.ui.glow.StrongGlow
import org.openstrap.app.ui.glow.edgeLight
import org.openstrap.app.ui.glow.level
import org.openstrap.app.ui.glow.levelEdge
import org.openstrap.app.ui.theme.MetricColors

/** The glow and edge light's shared spec (widgets spec 6.2): what the cards and a widget's background are drawn with. */
class LightSpecTest {
    private val density = 2.75f
    private val w = 358f * density
    private val h = 176f * density

    @Test fun `a hero lit from below fades from the light to the body by 45 percent`() {
        val style = GlowStyle(MetricColors.readiness, lightAngle = LightAngle.Below)
        val p = glowPaint(style, w, h, density)
        val palette = GlowPalette(style)
        assertEquals(listOf(0f, 0.16f, 0.45f, 1f), p.fill.stops)
        assertEquals(palette.light, p.fill.colors.first())
        assertEquals(palette.mid, p.fill.colors[2])
        assertEquals(palette.mid, p.fill.colors.last())
        // From the bottom edge up the middle.
        assertEquals(w / 2, p.fill.x0, 1e-3f)
        assertEquals(w / 2, p.fill.x1, 1e-3f)
        assertEquals(h, p.fill.y0, 1e-3f)
        assertEquals(0f, p.fill.y1, 1e-3f)
        // One rim at full, leaning to the light: the hole moves up, away from it.
        assertEquals(1, p.rims.size)
        assertEquals(1f, p.rims[0].color.alpha, 1f / 255)
        assertTrue(p.rims[0].dy < 0f)
        assertNull(p.rimClip)
        assertTrue(p.core is GlowCore.Shapes)
        assertEquals(GlowLayout.CardCorner.value * density, p.radius, 1e-3f)
    }

    @Test fun `the second glow starts at 70 percent and reaches the body by 28 percent`() {
        val p = glowPaint(GlowStyle(MetricColors.readiness, lightAngle = LightAngle.Below, second = true), w, h, density)
        assertEquals(listOf(0f, 0.28f, 1f), p.fill.stops)
        assertEquals(0.7f, p.rims[0].color.alpha, 1f / 255)
    }

    @Test fun `a level's rim and core stop at the level`() {
        val style = GlowStyle.level(MetricColors.steps, 0.3f)
        val p = glowPaint(style, w, h, density)
        val clip = p.rimClip
        assertNotNull(clip)
        clip!!
        assertEquals(levelEdge(0.3f) * h - 22f * density, clip.top, 1e-3f)
        assertEquals(h, clip.bottom, 1e-3f)
        val core = p.core as GlowCore.Level
        // Dark from above the card, transparent below it, across the layer's margin.
        assertEquals(-p.coreMargin, core.gradient.y0, 1e-3f)
        assertEquals(h + p.coreMargin, core.gradient.y1, 1e-3f)
        assertEquals(1f, core.gradient.colors.first().alpha, 1f / 255)
        assertEquals(0f, core.gradient.colors.last().alpha, 1f / 255)
        // Glossy along the lit (bottom) edge.
        assertEquals(0.35f, p.gloss.stops.last().second.alpha, 1f / 255)
    }

    @Test fun `a level from the left fills across`() {
        val p = glowPaint(GlowStyle.Ember.copy(lightLevel = 0.6f, lightAngle = LightAngle.Left, second = true), w, h, density)
        val core = p.core as GlowCore.Level
        assertEquals(0f, core.gradient.y0, 0f)
        assertEquals(w + p.coreMargin, core.gradient.x0, 1e-3f)
        assertTrue(p.gloss.across)
        // A level ignores second: always at full.
        assertEquals(1f, p.rims[0].color.alpha, 1f / 255)
        assertEquals(listOf(0f, 0.35f * 0.6f, 0.85f * 0.6f, 1f), p.fill.stops)
    }

    @Test fun `the night mood lights from below and dims`() {
        val day = glowPaint(GlowStyle(MetricColors.readiness, lightAngle = LightAngle.UpperRight), w, h, density)
        val night = glowPaint(GlowStyle(MetricColors.readiness, lightAngle = LightAngle.UpperRight, mood = GlowMood.Night), w, h, density)
        assertTrue(day.fill.y0 < h / 2)
        assertEquals(h, night.fill.y0, 1e-3f)
        assertTrue(night.fill.colors.first().red + night.fill.colors.first().green < day.fill.colors.first().red + day.fill.colors.first().green)
    }

    @Test fun `on hold only the outline`() {
        val p = glowPaint(GlowStyle(MetricColors.heart, outline = true), w, h, density)
        assertEquals(MetricColors.heart.copy(alpha = 0.7f), p.outline)
        assertEquals(1.5f * density, p.outlineWidth, 1e-4f)
        assertNull(glowPaint(GlowStyle(MetricColors.heart), w, h, density).outline)
    }

    @Test fun `grain and hairline`() {
        val p = glowPaint(GlowStyle(MetricColors.heart), w, h, density)
        assertEquals((0.06f * 255).toInt(), p.grainAlpha)
        assertEquals(Color.White.copy(alpha = GlowLayout.HairlineAlpha), p.hairline)
        assertEquals(0, glowPaint(GlowStyle(MetricColors.heart, grain = 0f), w, h, density).grainAlpha)
    }

    @Test fun `a usual edge card is lit softly from the bottom`() {
        val e = edgePaint(edgeLight(EdgeStatus.Usual), MetricColors.steps, null, GlowMood.Day, w, h, density)
        assertEquals(h, e.glow.y0, 0f)
        assertEquals(h - SoftGlow.depth.value * density, e.glow.y1, 1e-3f)
        assertEquals(0.70f, e.glow.colors.first().alpha, 1f / 255)
        assertEquals(0f, e.glow.colors.last().alpha, 1f / 255)
        assertEquals(EdgeSurface, e.surface)
        assertEquals(1f * density, e.hairlineWidth, 0f)
    }

    @Test fun `off your normal glows stronger, and the mood softens it`() {
        val strong = edgePaint(edgeLight(EdgeStatus.Above), MetricColors.heart, null, GlowMood.Day, w, h, density)
        assertEquals(0f, strong.glow.y0, 0f)
        assertEquals(StrongGlow.depth.value * density, strong.glow.y1, 1e-3f)
        assertEquals(0.90f, strong.glow.colors.first().alpha, 1f / 255)
        val night = edgePaint(edgeLight(EdgeStatus.Above), MetricColors.heart, null, GlowMood.Night, w, h, density)
        assertEquals(0.90f * 0.8f, night.glow.colors.first().alpha, 1f / 255)
    }

    @Test fun `each edge runs from its side`() {
        val right = edgePaint(edgeLight(EdgeStatus.Usual, EdgeKind.Live), MetricColors.heart, null, GlowMood.Day, w, h, density)
        assertEquals(w, right.glow.x0, 0f)
        assertTrue(right.glow.x1 < w)
        val left = edgePaint(edgeLight(EdgeStatus.Usual, EdgeKind.Earlier), MetricColors.readiness, null, GlowMood.Day, w, h, density)
        assertEquals(Edge.Left, edgeLight(EdgeStatus.Usual, EdgeKind.Earlier).edge)
        assertEquals(0f, left.glow.x0, 0f)
        assertTrue(left.glow.x1 > 0f)
    }

    @Test fun `a level edge card fills across to its value`() {
        val level = edgeLevel(0.5f)!!
        val e = edgePaint(edgeLight(EdgeStatus.Usual, EdgeKind.Level), MetricColors.load, level, GlowMood.Day, w, h, density)
        assertEquals(0f, e.glow.x0, 0f)
        assertEquals(w, e.glow.x1, 0f)
        assertEquals(0.5f, e.glow.stops!![1], 1e-6f)
        assertEquals(0.46f, e.glow.colors[0].alpha, 1f / 255)
        assertEquals(0.30f, e.glow.colors[1].alpha, 1f / 255)
        assertEquals(0f, e.glow.colors.last().alpha, 1f / 255)
        // An empty level still shows where it fills from.
        assertEquals(EDGE_MIN_LEVEL, edgeLevel(0f))
        assertNull(edgeLevel(null))
    }
}
