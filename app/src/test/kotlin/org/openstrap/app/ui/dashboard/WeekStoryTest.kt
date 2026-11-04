package org.openstrap.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** The week story's pure parts (spec 4.2): days, totals, each day's line and the comparisons that exist. */
class WeekStoryTest {
    private val monday = LocalDate.of(2026, 10, 12)
    private val week = monday..monday.plusDays(6)

    private fun days() = WeekStory.days(
        week,
        steps = mapOf(monday to 8000, monday.plusDays(3) to 12400, monday.plusDays(4) to 0),
        active = (0..6).associate { monday.plusDays(it.toLong()) to it * 10 },
        workouts = mapOf(monday.plusDays(3) to listOf("Run")),
        // Mon's night ends on Tuesday morning.
        asleepByMorning = mapOf(monday.plusDays(1) to 7 * 3600L + 40 * 60, monday.plusDays(8) to 6 * 3600L),
    )

    @Test fun `a day gets its steps, active minutes, workouts and the night after it`() {
        val d = days()
        assertEquals(7, d.size)
        assertEquals(monday, d.first().date)
        assertEquals(7 * 3600L + 40 * 60, d.first().asleepSeconds)
        // A day of no steps has none, not zero; Sunday's night ended on the Monday after the week.
        assertNull(d[4].steps)
        assertNull(d[6].asleepSeconds)
        assertEquals("30 active minutes, run", WeekStory.dayLine(d[3]))
        assertEquals("7 h 40 asleep", WeekStory.dayLine(d[0]))
        assertNull(WeekStory.dayLine(WeekStory.Day(monday, 100, null, emptyList(), null)))
    }

    @Test fun `the week in numbers averages only the days that have them`() {
        val t = WeekStory.totals(days())
        assertEquals(10200, t.stepsADay)
        assertEquals(210, t.activeMinutes)
        assertEquals(1, t.workouts)
        assertEquals(7 * 3600L + 40 * 60, t.asleepANight)
    }

    @Test fun `the sentence compares only what the week before has`() {
        assertEquals("Busiest on Thursday, 12,400 steps.", WeekStory.busiest(days(), Locale.UK))
        assertNull(WeekStory.workoutsChange(1, null))
        assertEquals("1 workout, 1 fewer than the week before.", WeekStory.workoutsChange(1, 2))
        assertEquals("3 workouts, 2 more than the week before.", WeekStory.workoutsChange(3, 1))
        assertEquals("2 workouts, as many as the week before.", WeekStory.workoutsChange(2, 2))
        assertEquals("1 workout, 1 more than the week before.", WeekStory.workoutsChange(1, 0))
        assertEquals("0 workouts, 2 fewer than the week before.", WeekStory.workoutsChange(0, 2))
        assertEquals(
            "Busiest on Thursday, 12,400 steps. Steps up 12% on the week before. 1 workout, as many as the week before.",
            WeekStory.sentence(days(), 12, 1, Locale.UK),
        )
        assertEquals("Steps down 3% on the week before.", WeekStory.stepsChange(-3))
        assertEquals("Steps held steady on the week before.", WeekStory.stepsChange(0))
        assertEquals("steps a day, 12 to 18 Oct", WeekStory.label(week, Locale.UK))
        assertEquals("steps a day, 28 Sep to 4 Oct", WeekStory.label(LocalDate.of(2026, 9, 28)..LocalDate.of(2026, 10, 4), Locale.US))
    }

    @Test fun `no week story line uses an interpunct, semicolon or em dash`() {
        val lines = days().mapNotNull(WeekStory::dayLine) + listOfNotNull(
            WeekStory.sentence(days(), -3, 0, Locale.UK), WeekStory.workoutsChange(0, 0), WeekStory.label(week, Locale.UK),
        ) + listOf(InfoCopy.WEEK.meaning, InfoCopy.WEEK.how, InfoCopy.WEEK.moves, InfoCopy.WEEK.off)
        lines.forEach { s -> assertFalse("\"$s\"", s.any { it == '·' || it == ';' || it == '—' }) }
    }

    @Test fun `empty days in a row fold into one row`() {
        val empty = { d: LocalDate -> WeekStory.Day(d, null, null, emptyList(), null) }
        val d = (0..3).map { empty(monday.plusDays(it.toLong())) } + WeekStory.Day(monday.plusDays(4), 4000, null, emptyList(), null) + empty(monday.plusDays(5))
        val rows = WeekStory.rows(d, Locale.UK)
        assertEquals(listOf("Monday to Thursday", "Friday", "Saturday"), rows.map { it.title })
        assertEquals("Nothing recorded", rows[0].line)
        assertEquals(4000, rows[1].steps)
    }
}
