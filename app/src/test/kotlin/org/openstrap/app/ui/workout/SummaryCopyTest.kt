package org.openstrap.app.ui.workout

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Route

/** The summary right after a workout: the heart rate drop, Load, splits and a personal best (spec 6.3). */
class SummaryCopyTest {
    private val end = 1_000_000L
    private val maxHr = 190

    /** A second a reading: [bpm] up to the end, then falling by [perSecond] for two minutes. */
    private fun readings(bpm: Int, perSecond: Double): List<Pair<Long, Int>> =
        (-60L..120L).map { s -> (end + s) to if (s <= 0) bpm else (bpm - perSecond * s).toInt() }

    @Test fun `while the first minute runs it says so, then the drop`() {
        val r = readings(160, 0.5).filter { it.first <= end + 20 }
        val watching = SummaryCopy.calmDown(r, end, now = end + 20, live = true, maxHr = maxHr, usualDrop = null)
        assertTrue(watching.watching)
        assertEquals(SummaryCopy.WATCHING, watching.text)
        // Two seconds past the minute the median there is complete.
        assertTrue(SummaryCopy.calmDown(r, end, end + 62, live = true, maxHr = maxHr, usualDrop = null).watching)
        val done = SummaryCopy.calmDown(readings(160, 0.5), end, end + 63, live = true, maxHr = maxHr, usualDrop = null)
        assertFalse(done.watching)
        assertEquals(30, done.drop)
        assertEquals("Down 30 in the first minute.", done.text)
    }

    @Test fun `stored readings are never watched`() {
        val done = SummaryCopy.calmDown(readings(160, 0.5), end, end + 10, live = false, maxHr = maxHr, usualDrop = null)
        assertFalse(done.watching)
        assertEquals(30, done.drop)
    }

    @Test fun `no readings after the end, no drop`() {
        val r = readings(160, 0.5).filter { it.first <= end }
        val c = SummaryCopy.calmDown(r, end, end + 300, live = true, maxHr = maxHr, usualDrop = 20)
        assertNull(c.drop)
        assertEquals(SummaryCopy.NO_DROP_DATA, c.text)
    }

    @Test fun `the drop against your usual`() {
        assertEquals("Down 34 in the first minute. A quick drop for you.", SummaryCopy.dropLine(34, 160, maxHr, 25))
        assertEquals("Down 28 in the first minute. About usual for you.", SummaryCopy.dropLine(28, 160, maxHr, 25))
        assertEquals("Down 16 in the first minute. A slow drop for you.", SummaryCopy.dropLine(16, 160, maxHr, 25))
        assertEquals("Down 16 in the first minute.", SummaryCopy.dropLine(16, 160, maxHr, null))
        // Ending close to resting there's little to come down from: no comparing.
        assertEquals("Down 12 in the first minute, from an easy finish.", SummaryCopy.dropLine(12, 100, maxHr, 25))
        assertEquals("No drop in the first minute.", SummaryCopy.dropLine(-3, 160, maxHr, 25))
        assertEquals(SummaryCopy.NO_DROP_DATA, SummaryCopy.dropLine(null, 160, maxHr, 25))
    }

    @Test fun `usual drop is a median once there are three`() {
        assertNull(SummaryCopy.usualDrop(listOf(20, 30)))
        assertEquals(25, SummaryCopy.usualDrop(listOf(20, 25, 40)))
        assertEquals(23, SummaryCopy.usualDrop(listOf(20, 25, 21, 40)))
        // Only the latest ten count.
        assertEquals(10, SummaryCopy.usualDrop(List(10) { 10 } + List(10) { 50 }))
    }

    @Test fun `load against your usual`() {
        assertEquals("Above your usual of 80", SummaryCopy.loadLine(120, 80f))
        assertEquals("About your usual", SummaryCopy.loadLine(90, 80f))
        assertEquals("Below your usual of 80", SummaryCopy.loadLine(40, 80f))
        assertEquals("Learning your usual", SummaryCopy.loadLine(40, null))
        assertEquals("From this run", SummaryCopy.loadTitle(1))
        assertEquals("From this workout", SummaryCopy.loadTitle(52))
    }

    @Test fun `time to recover and the workout line`() {
        assertEquals("Time to recover 40 min", SummaryCopy.timeToRecover(40))
        assertEquals("Time to recover 14 h", SummaryCopy.timeToRecover(14 * 60 + 10))
        assertNull(SummaryCopy.timeToRecover(0))
        assertEquals("5.21 km, average 142 bpm. Paused 4 min.", SummaryCopy.workoutLine(5210f, false, 142, 250))
        assertEquals("≈ 820 m.", SummaryCopy.workoutLine(820f, true, null, 30))
        assertEquals("Average 120 bpm.", SummaryCopy.workoutLine(null, false, 120, 0))
        assertNull(SummaryCopy.workoutLine(null, false, null, 0))
        assertEquals("18:02–18:40, 38:12", SummaryCopy.trimHeader("18:02", "18:40", 38 * 60 + 12))
        assertEquals("1.02 km, average 150 bpm", SummaryCopy.lapLine(1020.0, 150))
        assertEquals("Average 150 bpm", SummaryCopy.lapLine(null, 150))
        assertNull(SummaryCopy.lapLine(null, null))
    }

    @Test fun `splits list whole kilometres then a last part`() {
        val splits = listOf(
            Route.Split(0, 100L + 342, 342, 1000.0),
            Route.Split(1, 100L + 700, 358, 1000.0),
            Route.Split(2, 100L + 850, 150, 430.0),
        )
        val hr = (100L..950L).map { it to if (it < 442) 150 else 160 }
        val rows = SummaryCopy.splitRows(splits, hr, firstT = 100L)
        assertEquals(listOf("Km 1", "Km 2", "Last 430 m"), rows.map { it.name })
        assertEquals("5:42", rows[0].time)
        assertEquals(150, rows[0].averageBpm)
        assertEquals(160, rows[1].averageBpm)
        // A last bit under 100 m is left out.
        assertEquals(2, SummaryCopy.splitRows(splits.dropLast(1) + Route.Split(2, 900, 20, 60.0), hr, 100L).size)
        assertNull(SummaryCopy.splitRows(splits, emptyList(), 100L)[0].averageBpm)
    }

    @Test
    fun `the last part is what the workout's distance leaves after the whole kilometres`() {
        // The raw route runs to 2185 m, the workout's smoothed distance is 2176 m.
        val splits = listOf(Route.Split(0, 300, 300, 1000.0), Route.Split(1, 600, 300, 1000.0), Route.Split(2, 640, 40, 185.0))
        assertEquals("Last 176 m", SummaryCopy.splitRows(splits, emptyList(), 0L, totalMeters = 2176f).last().name)
        assertEquals("Last 185 m", SummaryCopy.splitRows(splits, emptyList(), 0L).last().name)
    }

    @Test fun `the workout card says whether its kcal target was reached`() {
        assertEquals("Target 400 kcal, reached at 38:10", SummaryCopy.targetLine("k:400", 2290, 431, Locale.UK))
        assertEquals("Target not reached, 212 of 300 kcal", SummaryCopy.targetLine("k:300", null, 212, Locale.UK))
        assertEquals("Target 300 kcal, reached", SummaryCopy.targetLine("k:300", null, 305, Locale.UK))
        assertEquals("Target 300 kcal, not reached", SummaryCopy.targetLine("k:300", null, null, Locale.UK))
        assertNull(SummaryCopy.targetLine("z:1-2", null, 300))
        assertNull(SummaryCopy.targetLine(null, null, 300))
        listOfNotNull(SummaryCopy.targetLine("k:400", 2290, 431), SummaryCopy.targetLine("k:300", null, 212))
            .forEach { l -> listOf("·", ";", "—").forEach { b -> assertFalse("$l has $b", l.contains(b)) } }
    }
}
