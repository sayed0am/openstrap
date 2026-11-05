package org.openstrap.app.ui.glow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.ui.glow.draw.EdgeStatus
import org.openstrap.app.ui.glow.draw.EdgeKind
import org.openstrap.app.ui.glow.draw.EdgeLight
import org.openstrap.app.ui.glow.draw.edgeLight
import org.openstrap.app.ui.glow.draw.Edge
import org.openstrap.app.ui.glow.draw.LevelFade
import org.openstrap.app.ui.glow.draw.SoftGlow
import org.openstrap.app.ui.glow.draw.StrongGlow

/** The widgets' edge light, which the app's cards dropped on 2026-11-04 (spec 2.10). */
class EdgeLightTest {
    @Test fun `usual value lights the bottom, soft`() {
        assertEquals(EdgeLight(Edge.Bottom, strong = false, progressBar = false), edgeLight(EdgeStatus.Usual, EdgeKind.Value))
    }

    @Test fun `above your normal lights the top, stronger`() {
        assertEquals(EdgeLight(Edge.Top, strong = true, progressBar = false), edgeLight(EdgeStatus.Above, EdgeKind.Value))
    }

    @Test fun `below your normal lights the bottom, stronger`() {
        assertEquals(EdgeLight(Edge.Bottom, strong = true, progressBar = false), edgeLight(EdgeStatus.Below, EdgeKind.Value))
    }

    @Test fun `progress lights the bottom, soft, with a bar`() {
        assertEquals(EdgeLight(Edge.Bottom, strong = false, progressBar = true), edgeLight(EdgeStatus.Usual, EdgeKind.Progress))
    }

    @Test fun `live lights the right`() {
        assertEquals(Edge.Right, edgeLight(EdgeStatus.Usual, EdgeKind.Live).edge)
        assertFalse(edgeLight(EdgeStatus.Usual, EdgeKind.Live).strong)
    }

    @Test fun `earlier lights the left`() {
        assertEquals(Edge.Left, edgeLight(EdgeStatus.Usual, EdgeKind.Earlier).edge)
    }

    @Test fun `sleep lights the top like a night sky`() {
        assertEquals(Edge.Top, edgeLight(EdgeStatus.Usual, EdgeKind.Sleep).edge)
        assertFalse(edgeLight(EdgeStatus.Usual, EdgeKind.Sleep).strong)
    }

    @Test fun `a value is a plain value by default`() {
        assertEquals(edgeLight(EdgeStatus.Above, EdgeKind.Value), edgeLight(EdgeStatus.Above))
    }

    @Test fun `kinds with their own edge keep it off your normal, and glow stronger`() {
        for (status in listOf(EdgeStatus.Above, EdgeStatus.Below)) {
            assertEquals(Edge.Right, edgeLight(status, EdgeKind.Live).edge)
            assertEquals(Edge.Left, edgeLight(status, EdgeKind.Earlier).edge)
            assertEquals(Edge.Top, edgeLight(status, EdgeKind.Sleep).edge)
            assertEquals(Edge.Bottom, edgeLight(status, EdgeKind.Progress).edge)
            for (kind in EdgeKind.entries) {
                val light = edgeLight(status, kind)
                assertTrue(light.strong)
            }
        }
    }

    @Test fun `L1 is the ink at 70 percent over 48 dp, L2 at 90 percent over 72 dp`() {
        for ((glow, alpha, depth) in listOf(Triple(SoftGlow, 0.7f, 48f), Triple(StrongGlow, 0.9f, 72f))) {
            assertEquals(depth, glow.depth.value, 1e-3f)
            val stops = glow.stops(1f)
            assertEquals(alpha, stops.first().second, 1e-4f)
            assertEquals(0f, stops.last().second, 1e-6f)
            stops.zipWithNext().forEach { (a, b) -> assertTrue(b.second <= a.second) }
        }
        // A mood scales it.
        assertEquals(0.7f * 0.8f, SoftGlow.stops(0.8f).first().second, 1e-3f)
        assertEquals(40f, LevelFade.value)
    }

    @Test fun `only progress cards get a bar`() {
        for (status in EdgeStatus.entries) for (kind in EdgeKind.entries) {
            assertEquals(kind == EdgeKind.Progress, edgeLight(status, kind).progressBar)
        }
    }
}
