package org.openstrap.app.ui.glow

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.ui.glow.draw.LightFrom
import org.openstrap.app.ui.glow.draw.LightAngle
import org.openstrap.app.ui.glow.draw.GlowMood
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.draw.MIN_LIGHT_LEVEL
import org.openstrap.app.ui.glow.draw.glowLevel
import org.openstrap.app.ui.glow.draw.lightVector
import org.openstrap.app.ui.glow.draw.CoreBox
import org.openstrap.app.ui.glow.draw.angleCore
import org.openstrap.app.ui.glow.draw.levelEdge
import org.openstrap.app.ui.glow.draw.LEVEL_STEP
import org.openstrap.app.ui.glow.draw.LEVEL_FILL_REACH
import org.openstrap.app.ui.glow.draw.levelCore
import org.openstrap.app.ui.glow.draw.levelFade
import org.openstrap.app.ui.glow.draw.FillStop
import org.openstrap.app.ui.glow.draw.light

class GlowLightTest {
    private val style = GlowStyle(color = Color(0xFF7CF2C9))

    @Test fun `light level is clamped, with a floor`() {
        assertNull(glowLevel(null))
        assertEquals(MIN_LIGHT_LEVEL, glowLevel(0f))
        assertEquals(MIN_LIGHT_LEVEL, glowLevel(-1f))
        assertEquals(MIN_LIGHT_LEVEL, glowLevel(Float.NaN))
        assertEquals(0.3f, glowLevel(0.3f))
        assertEquals(1f, glowLevel(1.4f))
    }

    @Test fun `the light vector points at the light`() {
        assertEquals(0f to -1f, lightVector(LightAngle.Above))
        assertEquals(0f to 1f, lightVector(LightAngle.Below))
        assertEquals(1f to 0f, lightVector(LightAngle.Right))
        val (x, y) = lightVector(LightAngle.LowerLeft)
        assertTrue(x < 0f && y > 0f)
    }

    @Test fun `the core sits opposite the light`() {
        val core = angleCore(LightAngle.LowerLeft, 0.9f, 0.8f)
        assertTrue(core.x > 0.5f && core.y < 0.5f)
        assertEquals(0.9f, core.width)
        val below = angleCore(LightAngle.Below, 1f, 1f)
        assertEquals(0.5f, below.x, 1e-6f)
        assertTrue(below.y < 0.5f)
    }

    @Test fun `the lit part is the level's height`() {
        // Measured from the unlit side: 0.3 leaves 70% past the light, the soft step in it.
        assertEquals(0.88f, levelEdge(MIN_LIGHT_LEVEL), 1e-6f)
        assertEquals(0.7f, levelEdge(0.3f), 1e-6f)
        assertEquals(0.3f, levelEdge(0.7f), 1e-6f)
        // Full: lit all the way.
        assertEquals(0f, levelEdge(1f), 1e-6f)
        assertEquals(22f, LEVEL_STEP.value)
    }

    @Test fun `a level's light fades into the dark across the value, never in a line`() {
        // A 1000 px card at density 1, 60%: clear where the fill reaches the body, fully dark 11% past the value.
        val (dark, clear) = levelFade(0.6f, 1000f, 1f)
        assertEquals(1000f * (1f - LEVEL_FILL_REACH * 0.6f), clear, 0.01f)
        assertEquals(1000f * (levelEdge(0.6f) - 0.11f), dark, 0.01f)
        assertTrue(dark < levelEdge(0.6f) * 1000f && clear > levelEdge(0.6f) * 1000f)
        // A short card still fades over at least LEVEL_STEP.
        val (d, c) = levelFade(1f, 100f, 1f)
        assertTrue(c - d >= LEVEL_STEP.value - 0.01f)
    }

    @Test fun `the level core covers the dark part`() {
        val c = levelCore(0.3f, fromAbove = false)
        assertEquals(0f, c.y - c.height / 2, 1e-6f)
        assertEquals(levelEdge(0.3f), c.y + c.height / 2, 1e-6f)
        assertEquals(1f - c.y, levelCore(0.3f, fromAbove = true).y, 1e-6f)
    }

    @Test fun `old light sides map onto angles and keep their core`() {
        assertEquals(LightAngle.Above, style.light().angle)
        assertEquals(LightAngle.Below, style.copy(lightFrom = LightFrom.Bottom).light().angle)
        assertNull(style.copy(lightFrom = LightFrom.All).light().angle)
        assertEquals(CoreBox(style.coreX, style.coreY, style.coreWidth, style.coreHeight), style.light().core)
        assertEquals(style.glowStrength, style.light().rim, 1e-6f)
        assertEquals(1f, style.light().fill, 1e-6f)
    }

    @Test fun `L4 is the light colour at full, melting linearly into the body by 45 percent`() {
        val hero = style.light()
        assertEquals(1f, style.glowStrength, 1e-6f)
        assertEquals(1f, hero.rim, 1e-6f)
        assertEquals(FillStop(0f, 1f), hero.fillStops.first())
        assertEquals(FillStop(0.45f, 0f), hero.fillStops.last())
        // Never brighter further in.
        hero.fillStops.zipWithNext().forEach { (a, b) -> assertTrue(b.at > a.at && b.light < a.light) }
    }

    @Test fun `an angle moves the core`() {
        val lit = style.copy(lightAngle = LightAngle.UpperRight).light()
        assertEquals(LightAngle.UpperRight, lit.angle)
        assertTrue(lit.core.x < 0.5f && lit.core.y > 0.5f)
    }

    @Test fun `a level lights from below`() {
        val lit = style.copy(lightLevel = 0f).light()
        assertEquals(LightAngle.Below, lit.angle)
        assertEquals(MIN_LIGHT_LEVEL, lit.level)
        assertFalse(lit.fromAbove)
        assertTrue(style.copy(lightLevel = 0.5f, lightAngle = LightAngle.Above).light().fromAbove)
        assertNull(style.light().level)
    }

    @Test fun `L3 starts at 70 percent and reaches the body by 28 percent`() {
        val second = style.copy(second = true).light()
        assertEquals(0.7f, second.rim, 1e-6f)
        assertEquals(0.7f, second.fill, 1e-6f)
        assertEquals(0.28f, second.reach, 1e-6f)
        // The body is the same as the hero's: only the light is weaker.
        assertEquals(1f, second.brightness, 1e-6f)
    }

    @Test fun `a level is always at full, even as the second glow`() {
        val level = style.copy(lightLevel = 0.6f, second = true).light()
        assertEquals(1f, level.rim, 1e-6f)
        assertEquals(1f, level.fill, 1e-6f)
        // The light holds most of the way up to the value.
        assertEquals(LEVEL_FILL_REACH * 0.6f, level.reach, 1e-6f)
    }

    @Test fun `moods dim the whole card`() {
        assertEquals(1f, style.copy(mood = GlowMood.Day).light().brightness, 1e-6f)
        val evening = style.copy(mood = GlowMood.Evening).light()
        assertEquals(0.8f, evening.brightness, 1e-6f)
        assertEquals(0.9f, GlowMood.Evening.edge, 1e-6f)
        assertFalse(evening.tone)
        assertNull(evening.level)
        val night = style.copy(lightAngle = LightAngle.UpperRight, mood = GlowMood.Night).light()
        assertEquals(LightAngle.Below, night.angle)
        assertEquals(0.65f, night.brightness, 1e-6f)
        assertEquals(0.8f, GlowMood.Night.edge, 1e-6f)
        // Lit from below, the core opposite, as an L4 from below.
        assertNull(night.level)
        assertFalse(night.fromAbove)
        assertTrue(night.core.y < 0.5f)
        // A level card keeps its level at night.
        assertEquals(0.7f, style.copy(lightLevel = 0.7f, mood = GlowMood.Night).light().level)
        // and its full brightness: its light is the value.
        assertEquals(1f, style.copy(lightLevel = 0.7f, mood = GlowMood.Evening).light().brightness, 1e-6f)
        assertNull(style.copy(lightFrom = LightFrom.All, mood = GlowMood.Night).light().angle)
    }
}
