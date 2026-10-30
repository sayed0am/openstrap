package org.openstrap.app.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutTarget

class LiveGlanceTest {
    private val now = 1_000_000L

    /** A run 24:51 in, at 4.32 km and 5:42 /km, 142 bpm (zone 3 of 190). */
    private fun run(target: WorkoutTarget? = WorkoutTarget.Distance(5000), pausedAt: Long? = null, stoppedSince: Long? = null) = GlanceInput(
        sportType = 1, now = now, activeSeconds = 24 * 60 + 51L, pausedAt = pausedAt, bpm = 142, connected = true, maxHr = 190,
        gps = true, distanceMeters = 4320f, pace = 342.0, averagePace = 345.0, target = target, activeKcal = 312f,
        stoppedSince = stoppedSince,
    )

    @Test fun `a run with a goal shows its distance, pace and what's left`() {
        val g = Glance.of(run())
        assertEquals("4.32", g.figure)
        assertEquals("km", g.unit)
        assertEquals("4.32 km, 5:42 /km", g.line1)
        assertEquals("24:51, 680 m to go", g.line2)
        assertEquals(0.864f, g.progress!!.fraction, 1e-3f)
        assertFalse(g.progress!!.drain)
        assertEquals(GlanceAction.Pause, g.action)
        assertEquals(142, g.bpm)
        assertEquals(GlanceTint.Zone, g.tint)
        assertEquals(2, g.zone)
        assertEquals("Goal 5 km", g.goal)
        // 680 m at 5:42 /km is 3.9 min: about 4.
        assertEquals("About 4 min to go", g.toGo)
        assertEquals(listOf("5:42" to "Pace /km", "142" to "bpm, zone 3", "312" to "Active kcal"), g.figures)
        assertEquals("24:51", g.clock)
    }

    @Test fun `without a goal there's no bar and the zone takes the place of what's left`() {
        val g = Glance.of(run(target = null))
        assertNull(g.progress)
        assertEquals("24:51, zone 3", g.line2)
        assertNull(g.goal)
    }

    @Test fun `a time goal counts the minutes down, and a met goal says so`() {
        val g = Glance.of(run(target = WorkoutTarget.Time(30 * 60)))
        assertEquals("24:51, 6 min to go", g.line2)
        val met = Glance.of(run(target = WorkoutTarget.Distance(4000)))
        assertEquals("Reached", met.toGo)
        assertEquals("24:51, zone 3", met.line2)
    }

    @Test fun `indoor cardio leads with the heart rate`() {
        val g = Glance.of(run(target = null).copy(sportType = 2, gps = false, distanceMeters = 2140f, distanceFromSteps = true, averageBpm = 128))
        assertEquals("142", g.figure)
        assertEquals("bpm", g.unit)
        assertEquals("Treadmill, 24:51", g.line1)
        assertEquals("Zone 3, tempo", g.line2)
        assertEquals(listOf("2.14" to "km from steps", "128" to "Avg bpm", "312" to "Active kcal"), g.figures)
    }

    @Test fun `paused shows how long, what's done, and Resume`() {
        val g = Glance.of(run(pausedAt = now - 72))
        assertEquals("Paused for 1:12", g.line1)
        assertEquals("Outdoor run, 4.32 km so far", g.line2)
        assertEquals(GlanceAction.Resume, g.action)
        assertEquals(GlanceTint.Paused, g.tint)
        assertNull(g.bpm)
    }

    @Test fun `stopped moving asks, with End beside Keep going`() {
        val g = Glance.of(run(stoppedSince = now - 200))
        assertEquals("Still going?", g.line1)
        assertEquals("No movement for 3 min", g.line2)
        assertEquals(GlanceAction.KeepGoing, g.action)
        assertEquals(GlanceAction.End, g.second)
        assertEquals("End", g.secondLabel)
        assertEquals(GlanceTint.Asking, g.tint)
    }

    @Test fun `the strap away says so instead of a zone`() {
        val g = Glance.of(run(target = null).copy(sportType = 2, gps = false, connected = false))
        assertEquals("Strap reconnecting", g.line2)
        assertNull(g.figure)
    }

    @Test fun `the hero's split strip names the last kilometre and the fastest`() {
        val g = Glance.of(run().copy(lastSplit = Route.Split(3, now - 30, 355, 1000.0), lastSplitBpm = 146, fastestSplit = 2))
        assertEquals("Km 4 in 5:55" to "Avg 146 bpm. Fastest so far was km 3.", g.split)
        assertNull(Glance.of(run()).split)
    }

    private fun lift(m: StrengthMoment) = run(target = null).copy(sportType = 52, gps = false, distanceMeters = null, strength = m)

    @Test fun `strength in a set offers Set done`() {
        val g = Glance.of(lift(StrengthMoment(inSet = true, setNumber = 3, setsTarget = 5, done = 2, setSeconds = 42, exercise = "Bench press")))
        assertEquals("Set 3 of 5, 0:42", g.line1)
        assertEquals("Bench press, 142 bpm", g.line2)
        assertEquals(GlanceAction.SetDone, g.action)
        assertEquals("Set done", g.actionLabel)
        assertEquals("0:42", g.figure)
        assertNull(g.progress)
    }

    @Test fun `strength resting drains in green with Start set and 30 s more`() {
        val g = Glance.of(lift(StrengthMoment(
            inSet = false, setNumber = 4, setsTarget = 5, done = 3, restLeft = 58, restTotal = 90, restBelow = 110, drop = 28,
            exercise = "Bench press", nextLoad = "10 × 60 kg",
        )).copy(bpm = 118))
        assertEquals("0:58 rest left", g.line1)
        assertEquals("118 bpm, down 28, set 4 next", g.line2)
        assertEquals(GlanceTint.Rest, g.tint)
        assertEquals(58f / 90f, g.progress!!.fraction, 1e-4f)
        assertEquals(true, g.progress!!.drain)
        assertEquals(GlanceAction.StartSet, g.action)
        assertEquals("Start set 4", g.actionLabel)
        assertEquals("Set 4", g.shortLabel)
        assertEquals(GlanceAction.AddRest, g.second)
        assertEquals("+30 s", g.secondLabel)
        assertEquals("118 bpm, down 28. Ends sooner once you stay under 110.", g.goal)
        assertEquals("Set 4, bench press 10 × 60 kg", g.next)
    }

    @Test fun `strength before the first set offers Start set 1`() {
        val g = Glance.of(lift(StrengthMoment(inSet = false, setNumber = 1, setsTarget = null, done = 0)))
        assertEquals("Ready for set 1", g.line1)
        assertEquals("Set 1", g.shortLabel)
    }

    private fun hiit(m: IntervalMoment) = run(target = null).copy(sportType = 49, gps = false, distanceMeters = null, interval = m)

    @Test fun `intervals work in red with the seconds left, rest in green with Skip rest`() {
        val work = Glance.of(hiit(IntervalMoment(org.openstrap.app.data.Phase.Work(2, now - 6, 26), 3, 8, left = 14, nextRest = 10)))
        assertEquals("14", work.figure)
        assertEquals("Work, round 3 of 8", work.line1)
        assertEquals("Then 10 s rest, 142 bpm", work.line2)
        assertEquals(GlanceTint.Work, work.tint)
        assertEquals(GlanceAction.Pause, work.action)
        val rest = Glance.of(hiit(IntervalMoment(org.openstrap.app.data.Phase.Rest(2, now - 3, 36), 4, 8, left = 7, drop = 19)))
        assertEquals("Rest, round 4 next", rest.line1)
        assertEquals("142 bpm, down 19", rest.line2)
        assertEquals(GlanceTint.Rest, rest.tint)
        assertEquals(GlanceAction.Skip, rest.action)
        assertEquals("Skip rest", rest.actionLabel)
        assertEquals("+10 s", rest.secondLabel)
        val emom = Glance.of(hiit(IntervalMoment(org.openstrap.app.data.Phase.Work(0, now, null), 1, 12, left = 40, everyMinute = true)))
        assertEquals("Done", emom.actionLabel)
        val warm = Glance.of(hiit(IntervalMoment(org.openstrap.app.data.Phase.WarmUp, 1, 8)))
        assertEquals("Start rounds", warm.actionLabel)
    }

    @Test fun `paces read as clocks`() {
        assertEquals("5:42", Glance.paceClock(342.0))
        assertNull(Glance.paceClock(null))
        assertNull(Glance.paceClock(4000.0))
        assertEquals("680 m", Glance.kmWithUnit(680f))
        assertEquals("4.32 km", Glance.kmWithUnit(4320f))
    }

    @Test fun `no glance line uses banned punctuation`() {
        val all = listOf(run(), run(target = null), run(pausedAt = now - 5), run(stoppedSince = now - 60)).map(Glance::of)
        all.flatMap { listOfNotNull(it.line1, it.line2, it.goal, it.toGo, it.actionLabel) }.forEach { s ->
            assertFalse(s, s.any { it in "·;—–" })
        }
    }
}
