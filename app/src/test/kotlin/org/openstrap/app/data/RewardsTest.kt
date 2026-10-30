package org.openstrap.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.db.AppWorkoutSet
import org.openstrap.app.data.db.BadgeRow
import org.openstrap.app.data.db.WeekResult

class RewardsTest {
    private val utc = ZoneOffset.UTC
    private val monday = DayOfWeek.MONDAY

    /** 2026-10-26 is a Monday. */
    private fun at(date: String, hour: Int) = LocalDate.parse(date).atTime(hour, 0).toEpochSecond(utc)

    private fun workout(date: String, hour: Int, minutes: Int = 30, sport: Int = 52, mine: Boolean = true, gps: Float? = null, stretches: List<Rewards.Stretch> = emptyList()) =
        at(date, hour).let { s -> Rewards.Done(s, s, s + minutes * 60L, sport, minutes * 60L, mine, gps, gps, stretches) }

    @Test
    fun aWeekRunsMondayToSundayOrTheLocalesFirstDay() {
        assertEquals(LocalDate.parse("2026-10-26"), Rewards.weekStart(LocalDate.parse("2026-11-01"), monday))
        assertEquals(LocalDate.parse("2026-11-02"), Rewards.weekStart(LocalDate.parse("2026-11-02"), monday))
        assertEquals(LocalDate.parse("2026-11-01"), Rewards.weekStart(LocalDate.parse("2026-11-01"), DayOfWeek.SUNDAY))
    }

    @Test
    fun theWeekCountsAcrossItsBoundary() {
        // Sunday night belongs to the week before, Monday morning to the next.
        val ws = listOf(workout("2026-10-27", 7), workout("2026-10-29", 7), workout("2026-11-01", 23), workout("2026-11-02", 6))
        val weeks = Rewards.weeks(ws, emptyList(), 3, LocalDate.parse("2026-11-03"), monday, utc)
        assertEquals(listOf(LocalDate.parse("2026-10-26").toEpochDay(), LocalDate.parse("2026-11-02").toEpochDay()), weeks.map { it.weekStart })
        assertEquals(listOf(3, 1), weeks.map { it.count })
        assertEquals(listOf(true, false), weeks.map { it.met })
    }

    @Test
    fun onlyWorkoutsYouStartedOfTenMinutesCountAndTwoADay() {
        val ws = listOf(
            workout("2026-10-27", 7), workout("2026-10-27", 12), workout("2026-10-27", 18),
            workout("2026-10-28", 7, minutes = 9), workout("2026-10-29", 7, mine = false),
        )
        val week = Rewards.weeks(ws, emptyList(), 3, LocalDate.parse("2026-10-30"), monday, utc).single()
        assertEquals(2, week.count)
        assertFalse(week.met)
    }

    @Test
    fun aDeleteOrATrimWorksTheWeekOutAgain() {
        val ws = listOf(workout("2026-10-27", 7), workout("2026-10-28", 7), workout("2026-10-29", 7))
        val today = LocalDate.parse("2026-10-30")
        assertTrue(Rewards.weeks(ws, emptyList(), 3, today, monday, utc).single().met)
        // Deleted: two left.
        assertEquals(2, Rewards.weeks(ws.drop(1), emptyList(), 3, today, monday, utc).single().count)
        // Trimmed under ten minutes: it no longer counts.
        val trimmed = ws.toMutableList().also { it[2] = it[2].copy(activeSeconds = 8 * 60L) }
        assertFalse(Rewards.weeks(trimmed, emptyList(), 3, today, monday, utc).single().met)
    }

    @Test
    fun pastWeeksKeepTheirGoalAndThisWeekTakesTheNewOne() {
        val ws = listOf(workout("2026-10-20", 7), workout("2026-10-21", 7), workout("2026-10-27", 7), workout("2026-10-28", 7))
        val kept = listOf(WeekResult(LocalDate.parse("2026-10-19").toEpochDay(), 2, 2, true))
        val weeks = Rewards.weeks(ws, kept, 4, LocalDate.parse("2026-10-30"), monday, utc)
        assertEquals(listOf(2, 4), weeks.map { it.goal })
        assertEquals(listOf(true, false), weeks.map { it.met })
        // The goal lowered mid week applies to this week at once.
        assertTrue(Rewards.weeks(ws, kept, 2, LocalDate.parse("2026-10-30"), monday, utc).last().met)
    }

    @Test
    fun weeksInARowCountUpToThisWeekWithoutBreakingOnIt() {
        fun week(date: String, met: Boolean) = WeekResult(LocalDate.parse(date).toEpochDay(), 3, if (met) 3 else 1, met)
        val weeks = listOf(week("2026-09-28", true), week("2026-10-05", false), week("2026-10-12", true), week("2026-10-19", true), week("2026-10-26", false))
        // This week isn't met yet: the run is the two before it.
        assertEquals(2, Rewards.inARow(weeks, LocalDate.parse("2026-10-26")))
        assertEquals(3, Rewards.inARow(weeks.dropLast(1) + week("2026-10-26", true), LocalDate.parse("2026-10-26")))
        // A week missing altogether breaks the run.
        assertEquals(0, Rewards.inARow(weeks, LocalDate.parse("2026-11-09")))
        assertEquals(2, Rewards.bestRun(weeks))
    }

    @Test
    fun theWeekLineSaysWhichWorkoutMetTheGoal() {
        val ws = listOf(workout("2026-10-27", 7), workout("2026-10-28", 7))
        val third = workout("2026-10-29", 7)
        assertEquals(Rewards.WeekLine(3, 3, true), Rewards.weekLine(third, ws + third, 3, monday, utc))
        val fourth = workout("2026-10-30", 7)
        assertEquals(Rewards.WeekLine(4, 3, false), Rewards.weekLine(fourth, ws + third + fourth, 3, monday, utc))
    }

    @Test
    fun theFastestStretchComesFromTheRoute() {
        // 3 m/s for 2 km, then 4 m/s for 2 km, one fix a second.
        val points = (0..666).map { Route.Point(it.toLong(), 0.0, it * 3.0 / 111_195.0, 5f) } +
            (1..500).map { Route.Point(666L + it, 0.0, (1998 + it * 4.0) / 111_195.0, 5f) }
        val stretches = Rewards.stretches(points)
        val km = stretches.single { it.meters == 1000 }
        // The fastest kilometre is at 4 m/s: 250 s.
        assertTrue(km.seconds in 245..255)
        assertTrue(stretches.none { it.meters == 5000 })
        assertEquals(stretches, Rewards.Stretch.parse(Rewards.Stretch.format(stretches)))
    }

    @Test
    fun runBestsAreFromGpsOnlyAndATrimDropsAStretchOutsideIt() {
        val gps = workout("2026-10-27", 7, sport = 1, gps = 5200f, stretches = listOf(Rewards.Stretch(5000, 1600, at("2026-10-27", 7) + 60)))
        val steps = workout("2026-10-28", 7, minutes = 60, sport = 1).copy(meters = 9000f)
        val bests = Rewards.bests(listOf(gps, steps), emptyList(), utc).associateBy { it.kind }
        assertEquals(1600.0, bests[Rewards.Kind.fastest(5000)]!!.value, 0.0)
        assertEquals(5200.0, bests[Rewards.Kind.LONGEST_RUN]!!.value, 0.0)
        assertEquals(steps.start, bests[Rewards.Kind.LONGEST_WORKOUT]!!.workoutStart)
        val trimmed = gps.copy(shownStart = gps.start + 120)
        assertFalse(Rewards.bests(listOf(trimmed), emptyList(), utc).any { it.kind == Rewards.Kind.fastest(5000) })
    }

    @Test
    fun aNewBestBeatsAnOldOneAndAFirstRecordIsNotOne() {
        fun lift(date: String, kg: Float) = workout(date, 7).let { w -> w.copy(sets = listOf(AppWorkoutSet(w.start, 0, w.start, w.start + 60, null, "Bench press", 10, kg))) }
        val first = lift("2026-10-20", 60f)
        assertTrue(Rewards.newBests(first, listOf(first), emptyList(), utc).none { it.best.kind.startsWith("lift:") })
        val second = lift("2026-10-27", 65f)
        val new = Rewards.newBests(second, listOf(first, second), emptyList(), utc).single { it.best.kind.startsWith("lift:") }
        assertEquals("Bench press", Rewards.Kind.liftName(new.best.kind))
        assertEquals(60.0, new.before.value, 0.0)
        assertEquals(65.0, new.best.value, 0.0)
    }

    @Test
    fun firstLaunchEarnsBadgesForPastHistoryWhenTheyWereEarned() {
        // Three weeks of three workouts, a run of 5 km among them, and a big day of steps.
        val ws = (0 until 3).flatMap { wk ->
            val mon = LocalDate.parse("2026-10-05").plusWeeks(wk.toLong())
            (0 until 3).map { d -> workout(mon.plusDays(d.toLong()).toString(), 7) }
        } + workout("2026-10-15", 18, sport = 1, gps = 5100f)
        val days = listOf(Rewards.Day(LocalDate.parse("2026-10-10"), 12_000))
        val weeks = Rewards.weeks(ws, emptyList(), 3, LocalDate.parse("2026-10-30"), monday, utc)
        val badges = Rewards.badges(ws, days, weeks, monday, utc).associateBy { it.id }
        assertEquals(ws.first().start, badges["first_workout"]!!.workoutStart)
        assertEquals(ws[2].start, badges["goal_first"]!!.workoutStart)
        assertEquals(ws.last().start, badges["first_5k"]!!.workoutStart)
        assertEquals(ws.last().start, badges["first_run"]!!.workoutStart)
        assertEquals(LocalDate.parse("2026-10-10").toEpochDay(), badges["first_10k_steps"]!!.day)
        assertEquals(ws.sortedBy { it.start }[9].start, badges["workouts_10"]!!.workoutStart)
        assertFalse("goal_4" in badges)
        assertEquals(24, Rewards.BADGES.size)
        assertEquals(Rewards.BADGES.size, Rewards.BADGES.map { it.id }.toSet().size)
    }

    @Test
    fun aBadgeAlreadyEarnedIsNotNewAgain() {
        val old = workout("2026-10-20", 7, sport = 1)
        val again = workout("2026-10-27", 7, sport = 1)
        // The first run was deleted, but its badge stays stored.
        val stored = listOf(BadgeRow("first_run", old.end, old.start), BadgeRow("first_workout", old.end, old.start))
        assertTrue(Rewards.newBadges(again, listOf(again), emptyList(), emptyList(), stored, monday, utc).none { it.id == "first_run" })
        assertEquals(listOf("first_workout", "first_run"), Rewards.newBadges(again, listOf(again), emptyList(), emptyList(), emptyList(), monday, utc).map { it.id })
    }

    @Test
    fun aLockedBadgeSaysHowFarToGo() {
        val ws = listOf(workout("2026-10-27", 7, sport = 1, gps = 7200f))
        val km = Rewards.BADGES.single { it.id == "km_100" }
        assertEquals(7L to 100L, Rewards.progress(km, ws, emptyList(), emptyList(), LocalDate.parse("2026-10-26")))
        assertEquals(1L to 10L, Rewards.progress(Rewards.BADGES.single { it.id == "workouts_10" }, ws, emptyList(), emptyList(), LocalDate.parse("2026-10-26")))
    }
}
