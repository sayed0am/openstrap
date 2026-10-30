package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.db.AppWorkoutRound
import org.openstrap.app.ui.workout.StartCopy

class GamesTest {
    @Test
    fun effortIsTheTimeWeightedZone() {
        assertNull(Games.effort(IntArray(5)))
        assertEquals(1, Games.effort(intArrayOf(600, 0, 0, 0, 0)))
        // Half in zone 2, half in zone 4: effort 3.
        assertEquals(3, Games.effort(intArrayOf(0, 300, 0, 300, 0)))
        assertEquals(5, Games.effort(intArrayOf(0, 0, 0, 0, 60)))
    }

    @Test
    fun hardEffortsAreSpellsOfTenSecondsInZoneFive() {
        val max = 190
        val top = 180 // 94 %: zone 5.
        fun spell(from: Long, seconds: Int, bpm: Int) = (0 until seconds).map { from + it to bpm }
        val readings = spell(0, 15, top) + spell(15, 20, 140) + spell(35, 8, top) + spell(43, 5, 140) + spell(48, 30, top)
        // 15 s and 30 s count, the 8 s spell doesn't; a long spell counts once.
        assertEquals(2, Games.hardEfforts(readings, max))
        // A gap in the readings breaks a spell.
        assertEquals(0, Games.hardEfforts(spell(0, 6, top) + spell(20, 6, top), max))
    }

    @Test
    fun periodsRunLikeRoundsAndHalfTimeCanComeEarly() {
        val s = Structure.Periods(2, 2700, 900)
        assertEquals(s, Structure.parse("p:2x2700/900"))
        assertEquals("p:2x2700/900", s.code)
        val start = 0L
        val active = { t: Long -> t - start }
        var r = Phases.tick(s, emptyList(), start, start, active).rounds
        // Half time tapped at 40 min.
        val ht = Phases.skip(s, r, start, 2400, active)
        assertEquals(Switch.RestStarts, ht.switch)
        r = ht.rounds
        // The break runs its 15 min, then the second half starts on its own.
        assertNull(Phases.tick(s, r, start, 3299, active).switch)
        val second = Phases.tick(s, r, start, 3300, active)
        assertEquals(Switch.WorkStarts, second.switch)
        assertEquals(listOf(AppWorkoutRound(0, 0, 0, 2400, 3300), AppWorkoutRound(0, 1, 3300)), second.rounds)
        // A game's period ending buzzes long, a break ending as work starts.
        assertEquals(WorkoutBuzz.LONG, WorkoutBuzz.of(Switch.RestStarts, game = true))
        assertEquals(WorkoutBuzz.WORK_STARTS, WorkoutBuzz.of(Switch.WorkStarts, game = true))
    }

    @Test
    fun periodsAreNamedByTheirPlace() {
        assertEquals("1st half", StartCopy.periodName(0, 2))
        assertEquals("2nd half", StartCopy.periodName(1, 2))
        assertEquals("3rd quarter", StartCopy.periodName(2, 4))
        assertEquals("Period 2", StartCopy.periodName(1, 3))
        assertEquals("Half time", StartCopy.breakName(2))
        assertEquals("Break", StartCopy.breakName(4))
        assertEquals("Halves", StartCopy.structureName(Structure.HALVES))
    }
}
