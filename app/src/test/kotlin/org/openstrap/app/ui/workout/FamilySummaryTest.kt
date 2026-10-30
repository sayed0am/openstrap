package org.openstrap.app.ui.workout

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.Structure
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.AppWorkoutRound
import org.openstrap.app.data.db.AppWorkoutSet

class FamilySummaryTest {
    private val utc = ZoneOffset.UTC

    @Test
    fun whenLineNamesThePartOfTheDay() {
        val day = LocalDate.of(2026, 10, 30).atStartOfDay(utc).toEpochSecond()
        val now = day + 20 * 3600
        assertEquals("This morning, 07:12", FamilySummary.whenLine(day + 7 * 3600 + 12 * 60, now, utc, Locale.UK))
        assertEquals("Today, 14:00", FamilySummary.whenLine(day + 14 * 3600, now, utc, Locale.UK))
        assertEquals("This evening, 19:05", FamilySummary.whenLine(day + 19 * 3600 + 5 * 60, now, utc, Locale.UK))
        assertEquals("Yesterday, 10:00", FamilySummary.whenLine(day - 14 * 3600, now, utc, Locale.UK))
    }

    @Test
    fun fastestSplitIsTheQuickestWholeKilometre() {
        val rows = listOf(SplitRow("Km 1", "5:31", null), SplitRow("Km 2", "5:10", null), SplitRow("Km 3", "5:19", null), SplitRow("Last 210 m", "0:58", null))
        assertEquals(1, FamilySummary.fastestSplit(rows))
        assertNull(FamilySummary.fastestSplit(rows.take(1)))
    }

    @Test
    fun setsGroupByExerciseWithTheirPeaks() {
        val readings = (0L..400L).map { it to (100 + it / 4).toInt() }
        val sets = listOf(
            AppWorkoutSet(0, 0, 0, 40, 100, "Bench press", 10, 60f),
            AppWorkoutSet(0, 1, 100, 140, 200, "Squat", 8, 80f),
            AppWorkoutSet(0, 2, 200, 240, 300, "bench press", 10, 62.5f),
            AppWorkoutSet(0, 3, 300, null),
        )
        val groups = FamilySummary.exerciseSets(sets, readings)
        assertEquals(listOf("Bench press", "Squat"), groups.map { it.name })
        assertEquals(listOf("10 × 60 kg", "10 × 62.5 kg"), groups[0].sets.map { it.text })
        assertEquals(listOf(1, 2), groups[0].sets.map { it.number })
        assertEquals(110, groups[0].sets[0].peak)
        assertEquals(3, FamilySummary.setsDone(sets))
        assertEquals(60L, FamilySummary.averageRest(sets))
    }

    @Test
    fun restRecoveryIsTheTimeToFallUnderTheLevel() {
        // After each set the heart rate falls 1 bpm a second from 150.
        val sets = listOf(AppWorkoutSet(0, 0, 0, 100, 200), AppWorkoutSet(0, 1, 200, 300, 400))
        val readings = (0L..400L).map { t ->
            val since = if (t >= 300) t - 300 else if (t in 100..199) t - 100 else null
            t to (if (since == null) 150 else 150 - since.toInt())
        }
        assertEquals(41L, FamilySummary.restRecovery(sets, readings, 110))
        assertNull(FamilySummary.restRecovery(sets, readings, null))
        assertEquals("Your heart rate dropped under 110 in 41 s on average, 9 s faster than last time.", FamilySummary.restLine(41, 110, 50))
        assertEquals("Your heart rate dropped under 110 in 41 s on average, about the same as last time.", FamilySummary.restLine(41, 110, 43))
        assertEquals("Your heart rate dropped under 110 in 1:05 on average.", FamilySummary.restLine(65, 110, null))
    }

    @Test
    fun roundsShowPeakAndDropAndTheHardest() {
        val readings = (0L..90L).map { t -> t to listOf(150, 170, 160, 175, 150, 165)[(t / 15).toInt().coerceAtMost(5)] }
        val rounds = listOf(
            AppWorkoutRound(0, 0, 0, 15, 30, 170),
            AppWorkoutRound(0, 1, 30, 45, 60, 175),
            AppWorkoutRound(0, 2, 60, 75, 90, 168),
            AppWorkoutRound(0, 3, 90),
        )
        val lines = FamilySummary.rounds(rounds, readings)
        assertEquals(3, lines.size)
        assertEquals(listOf(10, 25, 3), lines.map { it.drop })
        assertEquals("You recovered 13 bpm in 10 s on average. Round 2 was your hardest.", FamilySummary.roundsLine(lines, 10))
        assertEquals(45L, FamilySummary.workSeconds(rounds))
    }

    @Test
    fun theHeartSettlesFromTheFirstMinutesToTheLast() {
        val readings = (0L..1800L step 5).map { t -> t to (96 - (t * 30 / 1800)).toInt() }
        val (from, to) = FamilySummary.settled(readings, 0, 1800)!!
        assertEquals(true, from in 94..96)
        assertEquals(true, to in 66..68)
        assertEquals("Your heart settled from 96 to 66.", FamilySummary.settledLine(96, 66))
        assertEquals("Your heart held near 72.", FamilySummary.settledLine(73, 72))
        assertNull(FamilySummary.settled(readings, 0, 300))
    }

    @Test
    fun aGameReadsAFadeOrAStrongFinish() {
        val end = 92 * 60L
        val faded = (0L..end step 10).map { t -> t to if (t < 70 * 60) 160 else 140 }
        assertEquals("You faded a little after 70 min.", FamilySummary.gameRead(faded, 0, end))
        val steady = (0L..end step 10).map { t -> t to 155 }
        assertEquals("You kept your effort up to the end.", FamilySummary.gameRead(steady, 0, end))
        val strong = (0L..end step 10).map { t -> t to if (t > 80 * 60) 175 else 150 }
        assertEquals("You finished stronger than you started.", FamilySummary.gameRead(strong, 0, end))
        assertNull(FamilySummary.gameRead(steady, 0, 20 * 60))
    }

    @Test
    fun periodsAreHalvesOrQuarters() {
        val readings = (0L..6000L step 5).map { it to 150 }
        val halves = listOf(AppWorkoutRound(0, 0, 0, 2400, 3000), AppWorkoutRound(0, 1, 3000, 5400, 5400))
        assertEquals(listOf("1st half", "2nd half"), FamilySummary.periods(halves, readings, 190).map { it.name })
        assertEquals(150, FamilySummary.periods(halves, readings, 190)[0].averageBpm)
    }

    @Test
    fun weekDaysStartOnTheGivenDay() {
        val thursday = LocalDate.of(2026, 10, 29)
        val tuesday = LocalDate.of(2026, 10, 27).atTime(18, 0).toEpochSecond(utc)
        val days = FamilySummary.weekDays(listOf(tuesday), thursday, DayOfWeek.MONDAY, utc)
        assertEquals(LocalDate.of(2026, 10, 26), days.first().first)
        assertEquals(listOf(false, true, false, false, false, false, false), days.map { it.second })
        assertEquals(LocalDate.of(2026, 10, 25), FamilySummary.weekDays(emptyList(), thursday, DayOfWeek.SUNDAY, utc).first().first)
    }

    @Test
    fun roundsMakeAnIntervalsSummaryAndPeriodsAGameOne() {
        assertEquals(Workouts.Family.Distance, FamilySummary.familyOf(1, null))
        assertEquals(Workouts.Family.Intervals, FamilySummary.familyOf(1, Structure.parse("r:400m/90x6")))
        assertEquals(Workouts.Family.Games, FamilySummary.familyOf(1, Structure.HALVES))
    }
}
