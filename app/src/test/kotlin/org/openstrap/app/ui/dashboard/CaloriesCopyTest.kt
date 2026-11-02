package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.EnergyExpenditure
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.Workout
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.openstrap.app.presentation.ActivityCopy
import org.openstrap.app.presentation.Range

/** Calories' words and sums (calories spec 4.4 to 4.6). */
class CaloriesCopyTest {
    private val zone = ZoneOffset.UTC
    private val date = LocalDate.of(2026, 10, 27)
    private val midnight = date.atStartOfDay(zone).toEpochSecond()
    private val b = 1.2f

    private fun strap(start: Long, minutes: Int, kcal: Int?, sport: Int = 1) = WorkoutAnalysis(
        Workout(start, start + minutes * 60L, sport, minutes * 60, kcal, 140, 160, 90, null, null, null, null, null),
        start + minutes * 60L, emptyList(), null, null, null, null, List(5) { 0 }, 190,
    )

    private fun phone(start: Long, minutes: Int, active: Float, total: Float, sport: Int = 1) = WorkoutAnalysis(
        Workout(start, start + minutes * 60L, sport, minutes * 60, Math.round(total), 140, 160, 90, null, null, null, null, null),
        start + minutes * 60L, emptyList(), null, null, null, null, List(5) { 0 }, 190, fromPhone = true, activeKcal = active,
    )

    private val day = EnergyExpenditure.Day(
        totalKcal = 2140f, bmrKcal = 1728f, minutesWithData = 1440, partial = false, pal = 1.24f, palImplausible = false, hrAssistedMinutes = 0,
        activeKcal = 702f, workoutKcal = 486f, walkingKcal = 150f, walkingMinutes = 38,
    )

    @Test fun `the row leads with active and says the goal and the total`() {
        assertEquals("Active, of 450. Total 1,468", CaloriesCopy.rowLine(348, 450, 1468, Locale.UK))
        assertEquals("Active, goal met. Total 2,140", CaloriesCopy.rowLine(450, 450, 2140, Locale.UK))
        assertEquals(0.5f, CaloriesCopy.share(225, 450), 1e-6f)
        assertEquals(1f, CaloriesCopy.share(900, 450), 1e-6f)
    }

    @Test fun `the day says how far to the goal and its busiest hour`() {
        assertEquals("102 to your goal of 450", CaloriesCopy.toGoal(348, 450, Locale.UK))
        assertEquals("Goal of 450 met", CaloriesCopy.toGoal(451, 450, Locale.UK))
        assertEquals("active kcal so far today", CaloriesCopy.dayLabel(true))
        assertEquals("active kcal", CaloriesCopy.dayLabel(false))
        val hours = List(24) { if (it == 7) 228.4f else it.toFloat() }
        assertEquals("Peak 07:00, 228", CaloriesCopy.peak(hours, Locale.UK))
        assertNull(CaloriesCopy.peak(List(24) { 0.2f }))
        assertEquals("active kcal, 23:00 to 00:00", CaloriesCopy.hourLabel(23))
    }

    @Test fun `hours follow the phone's clock`() {
        val was = org.openstrap.app.presentation.ClockSetting.use24Hour
        try {
            org.openstrap.app.presentation.ClockSetting.use24Hour = false
            val hours = List(24) { if (it == 7) 228.4f else it.toFloat() }
            assertEquals("Peak 7:00 AM, 228", CaloriesCopy.peak(hours, Locale.US))
            assertEquals("active kcal, 11:00 PM to 12:00 AM", CaloriesCopy.hourLabel(23, Locale.US))
            assertEquals("steps, 9:00 AM to 10:00 AM", ActivityCopy.hourLabel(9, Locale.US))
        } finally {
            org.openstrap.app.presentation.ClockSetting.use24Hour = was
        }
    }

    @Test fun `a strap workout's active kcal is its kcal less rest for its minutes`() {
        // 300 kcal over 50 min, b = 1.2 a minute: 300 - 60 = 240.
        assertEquals(240, Workouts.activeKcal(strap(midnight + 7 * 3600, 50, 300), b))
        // No profile: the strap's own figure stands.
        assertEquals(300, Workouts.activeKcal(strap(midnight, 50, 300), null))
        // Never below zero, and nothing without kcal.
        assertEquals(0, Workouts.activeKcal(strap(midnight, 50, 30), b))
        assertNull(Workouts.activeKcal(strap(midnight, 50, null), b))
        // A phone workout keeps its saved active figure.
        assertEquals(486, Workouts.activeKcal(phone(midnight, 40, 486.2f, 534f), b))
    }

    @Test fun `where it came from lists the day's workouts, then walking and everyday moving`() {
        val run = phone(midnight + 7 * 3600 + 600, 42, 400f, 450f)
        val walk = strap(midnight + 18 * 3600, 30, 122, sport = 6)
        // The strap's own record of the run covers the same time, so the phone's counts.
        val twin = strap(midnight + 7 * 3600 + 660, 40, 500)
        val yesterday = strap(midnight - 3600, 30, 200)
        val rows = CaloriesCopy.sources(listOf(walk, twin, run, yesterday), date, day, b, zone, Locale.UK)
        assertEquals(listOf(Workouts.sportName(1), Workouts.sportName(6), "Walking", "Everyday moving"), rows.map { it.name })
        assertEquals(listOf(400, 86, 150, 66), rows.map { it.kcal })
        assertEquals("07:10, 42 min", rows[0].line)
        assertEquals(run.workout.start, rows[0].workoutStart)
        assertEquals("38 min walking or running, outside workouts", rows[2].line)
        assertNull(rows[2].workoutStart)
        assertEquals("1 h 05", CaloriesCopy.minutes(65 * 60L))
        // On the hour it reads as every length of time does, and to the nearest minute.
        assertEquals("1 h", CaloriesCopy.minutes(60 * 60L - 20))
        assertEquals("42 min", CaloriesCopy.minutes(41 * 60L + 30))
        assertEquals("No walking outside workouts", CaloriesCopy.walkingLine(0))
    }

    private fun d(day: Int, active: Float, total: Float = active + 1700f, workout: Float = 0f, partial: Boolean = false) =
        LocalDate.of(2026, 10, day).let { it to CaloriesCopy.DayKcal(it, active, total, workout, partial) }

    @Test fun `a range averages its days, counts the goal days and compares with the range before`() {
        val rows = mapOf(
            d(14, 300f), d(15, 300f), d(16, 300f), d(17, 300f), d(18, 300f), d(19, 300f), d(20, 300f),
            d(21, 500f, workout = 200f), d(22, 400f), d(23, 460f), d(24, 300f), d(25, 600f, workout = 300f), d(26, 440f), d(27, 120f),
        )
        val s = CaloriesCopy.summary(rows, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 27), 450, inProgress = LocalDate.of(2026, 10, 27))
        // The day in progress is out of the averages (six days, 450 a day) and, short of its goal, of the goal days,
        // but in the total: 2,700 + 120.
        assertEquals(6, s.days)
        assertEquals(450, s.activeADay)
        assertEquals(2820, s.activeTotal)
        assertEquals(2150, s.totalADay)
        assertEquals(3, s.goalMet)
        assertEquals(150, s.vsBefore)
        assertEquals(18, s.workoutShare)
        assertEquals(LocalDate.of(2026, 10, 25), s.busiest?.date)
        assertEquals("Goal met on 3 of 6 days", CaloriesCopy.goalDays(s))
        assertEquals("+150", CaloriesCopy.signed(150))
        assertEquals("−18", CaloriesCopy.signed(-18))
        assertEquals("0", CaloriesCopy.signed(0))
        assertEquals("vs the 7 days before", CaloriesCopy.vsLabel(Range.Week))
        assertEquals("vs the year before", CaloriesCopy.vsLabel(Range.Year))
        assertEquals("active kcal a day, last 30 days", CaloriesCopy.rangeLabel(Range.Month))
        assertEquals("Workouts made 18% of your active kcal, the rest came from walking and moving about.", CaloriesCopy.rangeSentence(s, Locale.UK))
    }

    @Test fun `a range without days before it has no change, and no days says nothing`() {
        val rows = mapOf(d(26, 500f), d(27, 300f))
        val s = CaloriesCopy.summary(rows, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 27), 450, inProgress = null)
        assertEquals(2, s.days)
        assertNull(s.vsBefore)
        assertEquals(0, s.workoutShare)
        assertEquals("Your busiest day was Mon 26 Oct, with 500 active kcal.", CaloriesCopy.rangeSentence(s, Locale.UK))
        val none = CaloriesCopy.summary(emptyMap(), LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 27), 450, inProgress = null)
        assertNull(none.activeADay)
        assertNull(CaloriesCopy.goalDays(none))
        assertNull(CaloriesCopy.rangeSentence(none))
    }

    @Test fun `today counts once it is the busiest or has met the goal, and a barely worn day stays out of the averages`() {
        // As on the phone: Tue barely worn, Wed short of the goal, today past it with a walk.
        val rows = mapOf(d(27, 41f, total = 762f, partial = true), d(28, 246f, total = 1958f), d(29, 667f, total = 2358f, workout = 455f))
        val s = CaloriesCopy.summary(rows, LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 29), 300, inProgress = LocalDate.of(2026, 10, 29))
        assertEquals(246, s.activeADay)
        assertEquals(1958, s.totalADay)
        assertEquals(954, s.activeTotal)
        assertEquals("Goal met on 1 of 2 days", CaloriesCopy.goalDays(s))
        assertEquals(LocalDate.of(2026, 10, 29), s.busiest?.date)
        assertEquals(48, s.workoutShare)
        // Short of its goal so far, today isn't one of the goal days yet.
        val morning = CaloriesCopy.summary(rows + d(29, 120f), LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 29), 300, inProgress = LocalDate.of(2026, 10, 29))
        assertEquals("Goal met on 0 of 1 day", CaloriesCopy.goalDays(morning))
    }

    @Test fun `a workout says its share of its day`() {
        assertEquals(
            "This run is 486 of today's 702 active kcal. The day counts it exactly as shown here.",
            CaloriesCopy.inYourDay("run", 486, 702, CaloriesCopy.dayWord(date, date), fromPhone = true, Locale.UK),
        )
        assertEquals(
            "This walk is 86 of yesterday's 702 active kcal. The day counts it from the strap's own figure.",
            CaloriesCopy.inYourDay("walk", 86, 702, CaloriesCopy.dayWord(date.minusDays(1), date), fromPhone = false, Locale.UK),
        )
        assertEquals("that day's", CaloriesCopy.dayWord(date.minusDays(3), date))
        // A day row behind its workout, or none yet: nothing to say.
        assertNull(CaloriesCopy.inYourDay("run", 486, 300, "today's", true))
        assertNull(CaloriesCopy.inYourDay("run", 486, null, "today's", true))
    }

    @Test fun `no Calories string uses an interpunct, semicolon or em dash`() {
        val s = CaloriesCopy.summary(mapOf(d(26, 500f)), LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 27), 450, null)
        val all = listOf(
            CaloriesCopy.rowLine(1, 2, 3), CaloriesCopy.toGoal(1, 2), CaloriesCopy.walkingLine(3), CaloriesCopy.goalDays(s)!!,
            CaloriesCopy.rangeSentence(s)!!, CaloriesCopy.inYourDay("run", 1, 2, "today's", false)!!,
        )
        all.forEach { t -> assertTrue(t, t.none { it in "·;—" }) }
    }
}
