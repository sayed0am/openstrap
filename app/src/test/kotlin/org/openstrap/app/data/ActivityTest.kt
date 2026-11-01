package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.Activity.Minute

class ActivityTest {
    private fun minutes(vararg steps: Int, hrr: Float? = null, start: Int = 0) =
        steps.mapIndexed { i, s -> Minute((start + i) * 60L, s, hrr) }

    @Test fun `cadence minutes count only in runs of two`() {
        // 110, 90, 110, 120, 135, 140, 60: the lone first 110 doesn't count; the run of four does.
        val m = Activity.mvpa(minutes(110, 90, 110, 120, 135, 140, 60))
        assertEquals(2, m.moderateMinutes)
        assertEquals(2, m.vigorousMinutes)
        assertEquals(6, m.equivalent)
        assertEquals(0, m.fromHeartRate)
    }

    @Test fun `a gap in the minutes breaks a run`() {
        val m = Activity.mvpa(listOf(Minute(0, 110, null), Minute(120, 110, null)))
        assertEquals(0, m.moderateMinutes)
    }

    @Test fun `heart rate counts effort without steps after ten minutes, never twice`() {
        // 12 minutes of cycling at 50 % of reserve with no steps: moderate, from HR.
        val bike = Activity.mvpa(minutes(*IntArray(12), hrr = 0.5f))
        assertEquals(12, bike.moderateMinutes)
        assertEquals(12, bike.fromHeartRate)
        // Nine minutes is too short.
        assertEquals(0, Activity.mvpa(minutes(*IntArray(9), hrr = 0.7f)).moderateMinutes)
        // At 65 % it's vigorous.
        assertEquals(12, Activity.mvpa(minutes(*IntArray(12), hrr = 0.65f)).vigorousMinutes)
        // A brisk walk with HR up counts once, from cadence.
        val walk = Activity.mvpa(minutes(*IntArray(15) { 115 }, hrr = 0.5f))
        assertEquals(15, walk.moderateMinutes)
        assertEquals(0, walk.fromHeartRate)
        // Asleep minutes never count.
        assertEquals(0, Activity.mvpa(minutes(*IntArray(12), hrr = 0.5f).map { it.copy(asleep = true) }).moderateMinutes)
    }

    @Test fun `moderate cadence is 100 at every age`() {
        assertEquals(100, Activity.MODERATE_CADENCE)
        assertEquals(3, Activity.mvpa(minutes(102, 102, 102)).moderateMinutes)
        assertEquals(0, Activity.mvpa(minutes(99, 99, 99)).moderateMinutes)
    }

    @Test fun `step length rises from walking to running`() {
        assertEquals(0.414f * 1.8f, Activity.stepLength(100, 180), 1e-4f)
        assertEquals((0.414f + 0.018f) * 1.8f, Activity.stepLength(125, 180), 1e-4f)
        assertEquals(0.45f * 1.8f, Activity.stepLength(150, 180), 1e-4f)
        assertEquals(100 * 0.414f * 1.8f, Activity.distanceMeters(minutes(100), 180)!!, 1e-2f)
        assertNull(Activity.distanceMeters(minutes(100), null))
    }

    @Test fun `step goal by age`() {
        assertEquals(7_000, Activity.defaultStepGoal(65))
        assertEquals(8_000, Activity.defaultStepGoal(40))
        assertEquals(8_000, Activity.defaultStepGoal(null))
    }
}
