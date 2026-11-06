package org.openstrap.app.ui.glow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.AppWorkoutSet
import org.openstrap.app.presentation.EarnedLine
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.data.HeartZones

/** The share card's route hides its ends and fits its box, and each kind of card says what its board does. */
class ShareCardsTest {
    private val start = 1_792_000_000L

    /** A straight run north, a fix every [stepM] metres for [meters]. */
    private fun north(meters: Int, stepM: Int = 50): List<Route.Point> = (0..meters / stepM).map { i ->
        Route.Point(start + i * 10L, 51.5 + i * stepM / 111_195.0, -0.1, 5f)
    }

    private fun length(points: List<Route.Point>) = points.zipWithNext().sumOf { (a, b) -> Route.meters(a, b) }

    @Test fun `hiding the ends cuts 200 m from each`() {
        val route = north(1000)
        val trimmed = StoryRouteShape.trimEnds(route)
        assertEquals(600.0, length(trimmed), 1.0)
        assertEquals(200.0, Route.meters(route.first(), trimmed.first()), 1.0)
        assertEquals(200.0, Route.meters(route.last(), trimmed.last()), 1.0)
    }

    @Test fun `a route under 400 m has nothing left once its ends are hidden`() {
        assertTrue(StoryRouteShape.trimEnds(north(350)).isEmpty())
        assertTrue(StoryRouteShape.trimEnds(north(0)).isEmpty())
    }

    @Test fun `the route fits its box to scale, centred`() {
        val fitted = StoryRouteShape.fit(north(1000), 300f, 200f)
        // A line north is all height: it runs top to bottom down the middle.
        assertEquals(0f, fitted.minOf { it.y }, 0.01f)
        assertEquals(200f, fitted.maxOf { it.y }, 0.01f)
        fitted.forEach { assertEquals(150f, it.x, 0.01f) }
        assertTrue("north is up", fitted.first().y > fitted.last().y)
    }

    private fun summary(seconds: Long, sport: Int, bpm: Int? = null, sets: List<AppWorkoutSet> = emptyList()): PhoneSummary {
        val w = AppWorkout(start, start + seconds, sport, averageHeartRate = bpm)
        val hr = if (bpm == null) emptyList() else (0L until seconds step 5).map { AppWorkoutHeartRate(start, start + it, bpm, false) }
        return PhoneSummary(WorkoutRecorder.analyse(w, hr, 190, HeartZones.STANDARD), emptyList(), start, start + seconds, null, sets = sets)
    }

    @Test fun `a strength card lists each exercise's sets and top weight, and its time and sets as figures`() {
        val sets = listOf(
            AppWorkoutSet(start, 0, start, start + 60, exercise = "Bench press", reps = 10, weightKg = 60f),
            AppWorkoutSet(start, 1, start + 120, start + 180, exercise = "Bench press", reps = 10, weightKg = 65f),
            AppWorkoutSet(start, 2, start + 240, start + 300, exercise = "Squat", reps = 8, weightKg = 90f),
            AppWorkoutSet(start, 3, start + 360, start + 420, exercise = "squat ", reps = 6, weightKg = 80f),
        )
        val s = summary(2040, 52, sets = sets)
        val card = storyCard(Workouts.Family.Strength, s, summaryHero(Workouts.Family.Strength, s, null), emptyList()) as StoryCard.Lift
        assertEquals("kg", card.unit)
        assertEquals(listOf("Bench press" to "2 × 10, top 65 kg", "Squat" to "2 sets, top 90 kg"), card.rows)
        assertEquals(listOf("34 min Time", "4 Sets"), card.stats.map { listOfNotNull(it.value, it.unit, it.label).joinToString(" ") })
    }

    @Test fun `a card leads with a best over the weekly goal`() {
        val earned = listOf(
            EarnedLine(EarnedLine.Kind.Goal, "Weekly goal", "3 of 4"),
            EarnedLine(EarnedLine.Kind.Best, "Fastest 5 km", "26:41"),
        )
        val s = summary(1925, 2, 141)
        val indoor = storyCard(Workouts.Family.IndoorCardio, s, summaryHero(Workouts.Family.IndoorCardio, s, null), earned.take(1)) as StoryCard.Indoor
        assertEquals(listOf("Weekly goal, 3 of 4"), indoor.chips.map { it.text })
        assertEquals("32:05", indoor.value)
        assertEquals("141" to "Avg bpm", indoor.stats.first().let { it.value to it.label })
        val run = storyCard(Workouts.Family.Distance, s, summaryHero(Workouts.Family.Distance, s, null), earned) as StoryCard.Run
        assertEquals(listOf("Fastest 5 km"), run.chips.map { it.text })
    }

    @Test fun `a calm card and a game card say what their summaries say, as figures`() {
        // Half an hour coming down from 96 to 66.
        val w = AppWorkout(start, start + 1800, 74, averageHeartRate = 80)
        val hr = (0L..1800L step 5).map { AppWorkoutHeartRate(start, start + it, (96 - it * 30 / 1800).toInt(), false) }
        val recorded = hr.map { org.openstrap.app.data.db.WorkoutHeartRate(start, it.timestamp, it.bpm) }
        val calm = PhoneSummary(WorkoutRecorder.analyse(w, hr, 190, HeartZones.STANDARD), recorded, start, start + 1800, null)
        val readings = recorded.map { it.timestamp to it.bpm }
        assertEquals(readings, org.openstrap.app.ui.workout.FamilySummary.readings(calm))
        val (from, to) = org.openstrap.app.ui.workout.FamilySummary.settled(readings, start, start + 1800)!!
        val card = storyCard(Workouts.Family.MindBody, calm, summaryHero(Workouts.Family.MindBody, calm, null), emptyList()) as StoryCard.Calm
        assertEquals(listOf("$from", "$to"), card.stats.map { it.value })
        assertEquals("Your heart settled from $from to $to.", summaryHero(Workouts.Family.MindBody, calm, null).sentence)

        val played = PhoneSummary(WorkoutRecorder.analyse(w.copy(epoc = 130f), hr, 190, HeartZones.STANDARD), recorded, start, start + 1800, null)
        val game = storyCard(Workouts.Family.Games, played, summaryHero(Workouts.Family.Games, played, 100f), emptyList()) as StoryCard.Game
        assertEquals(listOf("130" to "Load", "96" to "Peak bpm"), game.stats.take(2).map { it.value to it.label })
    }

    @Test fun `no card text uses an interpunct, semicolon or dash`() {
        val s = summary(1800, 2, 120)
        Workouts.Family.entries.forEach { f ->
            val card = storyCard(f, s, summaryHero(f, s, null), emptyList())
            val texts = listOf(card.date, card.title, card.value, card.unit) +
                card.stats.flatMap { listOfNotNull(it.value, it.unit, it.label) } + card.chips.map { it.text } +
                ((card as? StoryCard.Lift)?.rows?.flatMap { it.toList() } ?: emptyList())
            texts.forEach { t -> assertTrue("\"$t\"", t.none { it in "·;—–" }) }
        }
    }
}
