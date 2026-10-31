package org.openstrap.app.ui.workout

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.WorkoutTarget

/** The ongoing workout notification's card: what it says collapsed and expanded, its colour and its zone strip (spec 6.3). */
class NotificationCopyTest {
    private val zones = listOf(180, 420, 540, 260, 20)

    private fun card(
        paused: Boolean = false, connected: Boolean = true, bpm: Int? = 152, gps: Boolean = true, distance: Float? = 4210f,
        fromSteps: Boolean = false, target: WorkoutTarget? = null, confirming: Boolean = false,
    ) = NotificationCopy.card("Outdoor run", 1421, paused, connected, bpm, 190, gps, distance, fromSteps, target, zones, confirming)

    /** Every number the card shows: the time always, then the digits in its other parts. */
    private fun numbers(c: WorkoutCard) = 1 + listOfNotNull(c.bpm, c.detail).count { s -> s.any(Char::isDigit) }

    @Test fun `running with gps shows time, bpm and distance`() {
        val c = card()
        // Collapsed: sport, time (the chronometer), bpm.
        assertEquals("Outdoor run", c.sport)
        assertEquals(1421, c.activeSeconds)
        assertTrue(c.running)
        assertEquals("152 bpm", c.bpm)
        // Expanded adds the zone and the distance.
        assertEquals(3, c.zone)
        assertEquals("Zone 4, threshold", c.zoneLine)
        assertEquals("4.21 km", c.detail)
        assertEquals("152 bpm. Zone 4, threshold. 4.21 km", c.text)
        assertEquals(3, numbers(c))
        assertEquals("640 m", card(distance = 640f).detail)
        assertEquals("≈ 4.21 km", card(fromSteps = true).detail)
        assertNull(card(distance = 5f).detail)
    }

    @Test fun `paused says so, stops the clock and keeps the distance`() {
        val c = card(paused = true)
        assertFalse(c.running)
        assertEquals("Paused", c.bpm)
        assertNull(c.zone)
        assertNull(c.zoneLine)
        assertEquals("4.21 km", c.detail)
        assertEquals("Paused. 4.21 km", c.text)
        // Without GPS there's nothing more to say while paused.
        assertNull(card(paused = true, gps = false).detail)
    }

    @Test fun `without gps no distance, the time in this zone or the target instead`() {
        val c = card(gps = false)
        assertEquals("152 bpm", c.bpm)
        assertEquals("4 min in this zone", c.detail)
        assertEquals(3, numbers(c))
        assertEquals("16 min in Zone 2 to 3", card(gps = false, target = WorkoutTarget.Zones(1, 2)).detail)
        assertEquals("Goal 30 min", card(gps = false, target = WorkoutTarget.Time(1800)).detail)
        assertEquals("Goal 500 kcal", card(gps = false, target = WorkoutTarget.Kcal(500)).detail)
        // No reading, no zone time.
        assertNull(card(gps = false, bpm = null).detail)
    }

    @Test fun `no reading or no strap reads grey`() {
        val waiting = card(bpm = null)
        assertEquals("Waiting", waiting.bpm)
        assertNull(waiting.zone)
        assertNull(waiting.zoneLine)
        val away = card(connected = false)
        assertEquals("Reconnecting", away.bpm)
        assertNull(away.zone)
        assertEquals("Still recording", away.zoneLine)
        assertTrue(away.running)
    }

    @Test fun `the end question takes the zone line`() {
        assertEquals(NotificationCopy.END_QUESTION, card(confirming = true).zoneLine)
        assertEquals(NotificationCopy.END_QUESTION, card(confirming = true, paused = true).zoneLine)
    }

    @Test fun `bpm takes the zone colour, grey without one`() {
        ZONE_COLORS.forEachIndexed { z, color -> assertEquals(color.toArgb(), NotificationCopy.accent(z)) }
        assertEquals(NotificationCopy.MUTED.toArgb(), NotificationCopy.accent(null))
        assertEquals(ZONE_COLORS[3].toArgb(), NotificationCopy.accent(card().zone))
        assertEquals(NotificationCopy.MUTED.toArgb(), NotificationCopy.accent(card(paused = true).zone))
    }

    @Test fun `the strip shares its width by the time in each zone`() {
        assertArrayEquals(intArrayOf(50, 100, 0, 250, 200), NotificationCopy.stripWidths(listOf(60, 120, 0, 300, 240), 600))
        // Rounding never leaves a gap or overflows, and an empty zone stays empty.
        val w = NotificationCopy.stripWidths(listOf(1, 1, 1, 0, 0), 100)
        assertEquals(100, w.sum())
        assertEquals(0, w[3])
        assertEquals(0, w[4])
        assertEquals(600, NotificationCopy.stripWidths(zones, 600).sum())
        // Before any zone time, nothing to draw.
        assertArrayEquals(IntArray(5), NotificationCopy.stripWidths(listOf(0, 0, 0, 0, 0), 600))
        assertArrayEquals(IntArray(5), NotificationCopy.stripWidths(emptyList(), 600))
    }

    @Test fun `nothing the card says uses a banned separator`() {
        val all = listOf(card(), card(paused = true), card(gps = false), card(connected = false), card(bpm = null), card(confirming = true))
            .flatMap { listOfNotNull(it.sport, it.bpm, it.zoneLine, it.detail, it.text) }
        all.forEach { s -> assertFalse(s, s.contains('·') || s.contains(';') || s.contains('—')) }
    }

    @Test fun `kcal goes after the time where it fits`() {
        val c = NotificationCopy.card("Outdoor run", 1421, false, true, 152, 190, true, 4210f, false, null, zones, false, activeKcal = 286.4f)
        assertEquals("286 kcal", c.kcal)
        assertTrue(c.kcalCollapsed)
        assertTrue(c.kcalExpanded)
        // Its line stays the three figures it was.
        assertEquals("152 bpm. Zone 4, threshold. 4.21 km", c.text)
        // A long sport name leaves no room on the collapsed line; past the hour the expanded time row is full.
        assertFalse(NotificationCopy.card("Indoor cycling", 1421, false, true, 152, 190, false, null, false, null, zones, false, activeKcal = 286f).kcalCollapsed)
        assertFalse(NotificationCopy.card("Outdoor run", 3721, false, true, 152, 190, true, 4210f, false, null, zones, false, activeKcal = 1286f).kcalExpanded)
        // Without a profile, nothing.
        val none = card()
        assertNull(none.kcal)
        assertFalse(none.kcalCollapsed || none.kcalExpanded)
    }
}
