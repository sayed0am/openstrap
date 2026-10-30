package org.openstrap.app.ui.workout

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Rewards
import org.openstrap.app.data.db.PersonalBest
import org.openstrap.app.data.db.WeekResult

class RewardsCopyTest {
    private val utc = ZoneOffset.UTC

    private fun at(date: String, hour: Int) = LocalDate.parse(date).atTime(hour, 0).toEpochSecond(utc)

    private fun run(date: String, fiveK: Long? = null) = at(date, 7).let { s ->
        Rewards.Done(s, s, s + 1800, 1, 1800, true, 5200f, 5200f, listOfNotNull(fiveK?.let { Rewards.Stretch(5000, it, s + 10) }))
    }

    @Test
    fun bestsReadByTheirKind() {
        assertEquals("Fastest 5 km", RewardsCopy.bestTitle(Rewards.Kind.fastest(5000)))
        assertEquals("Fastest half marathon", RewardsCopy.bestTitle(Rewards.Kind.fastest(21097)))
        assertEquals("Heaviest bench press", RewardsCopy.bestTitle(Rewards.Kind.lift("Bench press"), Locale.UK))
        assertEquals("26:41", RewardsCopy.bestValue(Rewards.Kind.fastest(5000), 1601.0))
        assertEquals("14.2 km", RewardsCopy.bestValue(Rewards.Kind.LONGEST_RUN, 14_200.0))
        assertEquals("62.5 kg", RewardsCopy.bestValue(Rewards.Kind.lift("Squat"), 62.5))
        assertEquals("5K", RewardsCopy.bestShort(Rewards.Kind.fastest(5000)))
    }

    @Test
    fun aNewBestSaysHowFarItBeatTheLast() {
        val before = PersonalBest(Rewards.Kind.fastest(5000), 1623.0, at("2026-08-12", 7))
        val now = PersonalBest(Rewards.Kind.fastest(5000), 1601.0, at("2026-10-30", 7))
        assertEquals("22 s faster than your best from August.", RewardsCopy.beatLine(Rewards.NewBest(now, before), at("2026-10-30", 9), utc, Locale.UK))
        val lastYear = before.copy(date = at("2025-08-12", 7))
        assertEquals("22 s faster than your best from August 2025.", RewardsCopy.beatLine(Rewards.NewBest(now, lastYear), at("2026-10-30", 9), utc, Locale.UK))
        assertEquals("2.5 kg more", RewardsCopy.beatBy(PersonalBest("lift:Bench press", 65.0, 0), PersonalBest("lift:Bench press", 62.5, 0)))
    }

    @Test
    fun theGoalLineIsAlwaysLastAndSaysWhenThisWorkoutMetIt() {
        val earlier = listOf(run("2026-10-20", 1650), run("2026-10-27"), run("2026-10-28"))
        val third = run("2026-10-29", 1601)
        val r = RewardsCopy.summary(third, earlier + third, emptyList(), emptyList(), emptyList(), 3, DayOfWeek.MONDAY, utc, at("2026-10-29", 9))
        assertTrue(r.goalMet)
        assertEquals(EarnedLine.Kind.Goal, r.earned.last().kind)
        assertEquals("Weekly goal met", r.earned.last().text)
        assertEquals("3 of 3", r.earned.last().value)
        // The 5 km best beat October 20th's.
        assertEquals("Fastest 5 km", r.best?.let { RewardsCopy.bestTitle(it.best.kind) })
        assertEquals(LocalDate.parse("2026-10-26").toEpochDay(), r.weekStart)
        // A fourth the same week meets nothing new.
        val fourth = run("2026-10-30")
        val r4 = RewardsCopy.summary(fourth, earlier + third + fourth, emptyList(), emptyList(), emptyList(), 3, DayOfWeek.MONDAY, utc, at("2026-10-30", 9))
        assertFalse(r4.goalMet)
        assertNull(r4.best)
        assertEquals(listOf(EarnedLine.Kind.Goal), r4.earned.map { it.kind })
    }

    @Test
    fun goalLineSaysWhatsLeft() {
        assertEquals("2 more to go this week", RewardsCopy.goalLine(Rewards.WeekLine(1, 3, false), 0).note)
        assertEquals("6 weeks in a row", RewardsCopy.goalLine(Rewards.WeekLine(4, 4, true), 6).note)
        assertEquals("Weekly goal", RewardsCopy.goalLine(Rewards.WeekLine(5, 4, false), 6).text)
    }

    @Test
    fun runsOfWeeksAndProgressRead() {
        assertEquals("Goal met 6 weeks in a row. Best is 9.", RewardsCopy.runLine(6, 9))
        assertEquals("Goal met 1 week in a row, your best yet.", RewardsCopy.runLine(1, 1))
        assertEquals("712 of 1,000 km", RewardsCopy.progressLine(712, 1000, "km"))
        assertEquals("4.2 of 5 km", RewardsCopy.progressLine(4200, 5000, "m"))
        assertEquals("27 October to 2 November", RewardsCopy.weekRange(LocalDate.parse("2026-10-27"), Locale.UK))
        assertEquals("5 of 4 workouts, goal met. 6 weeks in a row.", RewardsCopy.recapText(WeekResult(0, 4, 5, true), 6))
        assertEquals("2 of 4 workouts.", RewardsCopy.recapText(WeekResult(0, 4, 2, false), 0))
    }

    @Test
    fun theLongestWorkoutReadsAsADurationAndTheGoalBadgeAsItsGoal() {
        assertEquals("50 min", RewardsCopy.bestValue(Rewards.Kind.LONGEST_WORKOUT, 3000.0))
        assertEquals("1 h 12", RewardsCopy.bestValue(Rewards.Kind.LONGEST_WORKOUT, 4320.0))
        val goal = Rewards.BADGES.single { it.id == "goal_first" }
        assertEquals("3/3", RewardsCopy.face(goal, 3))
        assertEquals("✓", RewardsCopy.face(goal, null))
        assertEquals("10K", RewardsCopy.face(Rewards.BADGES.single { it.id == "first_10k" }, 3))
    }
}
