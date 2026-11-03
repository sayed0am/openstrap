package org.openstrap.app.ui.glow

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.Rewards
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.testing.workout

/** The summary's week dots fill a day by the weekly goal's rule, as "N workouts this week" counts. */
class WeekDotsTest {
    private val day = 1_792_000_000L

    private fun analysis(start: Long, minutes: Int, phone: Boolean = true, subtype: Int? = null, sport: Int = 1): WorkoutAnalysis {
        val w = workout(start = start, minutes = minutes, sportType = sport, subtype = subtype)
        return WorkoutAnalysis(w, w.end, emptyList(), null, null, null, null, List(5) { 0 }, 190, fromPhone = phone)
    }

    @Test fun `only workouts that count toward the goal fill a day`() {
        val run = analysis(day, 30)
        val short = analysis(day + 86_400, 5)
        val atTheLine = analysis(day + 2 * 86_400, Rewards.MIN_SECONDS.toInt() / 60)
        val noticed = analysis(day + 3 * 86_400, 40, phone = false, subtype = Workouts.SUBTYPE_AUTO_DETECTED)
        val activity = analysis(day + 4 * 86_400, 40, phone = false, sport = Workouts.GENERIC_ACTIVITY)
        val all = listOf(run, short, atTheLine, noticed, activity)

        assertEquals(listOf(run.shownStart, atTheLine.shownStart), goalWeekStarts(all))
        // The same workouts the weekly count takes.
        val counted = all.filter { Rewards.Done(it.workout.start, it.shownStart, it.end, it.workout.sportType, it.activeSeconds, it.startedByYou).counts }
        assertEquals(counted.map { it.shownStart }, goalWeekStarts(all))
    }
}
