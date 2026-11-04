package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/** Training load, max HR, daytime resting HR, CTL/ATL/Form and Strain (evidence-review.md, "Training load and Strain"). */
class LoadTest {
    private val rest = 60f
    private val max = 190f
    private fun bpmAt(x: Float) = Math.round(rest + x * (max - rest))

    /** A slow shuffle: steps, but under the walking cadence, so only the 35 % line applies. */
    private val walking = { _: Long -> 30 }
    private val still = { _: Long -> 0 }

    @Test fun `isolated spikes don't count, sustained effort does`() {
        // A spike every 10 minutes over an hour: never 3 of 5.
        val spikes = (0 until 60).map { it * 60L to if (it % 10 == 0) bpmAt(0.7f) else 70 }
        assertEquals(0f, Recovery.dailyLoad(spikes, rest, max, steps = walking).total, 1e-6f)
        // Nine minutes at 60 % of reserve: the first two minutes wait for the run, the other seven count.
        val x = Recovery.hrr(bpmAt(0.6f), rest, max)
        val short = (0 until 9).map { it * 60L to bpmAt(0.6f) }
        assertEquals(7 * Recovery.trimpMinute(x), Recovery.dailyLoad(short, rest, max, steps = walking).everyday, 1e-3f)
        // Ten are a sustained run, so all of them count, as Charge drains all of them as Exercise.
        val bout = (0 until 10).map { it * 60L to bpmAt(0.6f) }
        assertEquals(10 * Recovery.trimpMinute(x), Recovery.dailyLoad(bout, rest, max, steps = walking).everyday, 1e-3f)
        // Two minutes on, one off, still counts: 3 of the last 5 minutes are above.
        val broken = listOf(0, 1, 3, 4).map { it * 60L to bpmAt(0.6f) }
        assertEquals(2 * Recovery.trimpMinute(x), Recovery.dailyLoad(broken, rest, max, steps = walking).total, 1e-3f)
        // Asleep minutes never count outside a workout.
        assertEquals(0f, Recovery.dailyLoad(bout, rest, max, asleep = { true }, steps = walking).total, 1e-6f)
    }

    @Test fun `sitting with a raised heart rate and no steps is no everyday load`() {
        val seated = (0 until 9).map { it * 60L to bpmAt(0.5f) }
        assertEquals(0f, Recovery.dailyLoad(seated, rest, max, steps = still).total, 1e-6f)
        // Under 30 % of reserve no length of it counts.
        val warm = (0 until 60).map { it * 60L to bpmAt(0.28f) }
        assertEquals(0f, Recovery.dailyLoad(warm, rest, max, steps = still).total, 1e-6f)
        // The strap's step count is the signal: none, or a sentinel, is not movement.
        assertTrue(!Recovery.moving(0) && !Recovery.moving(null) && !Recovery.moving(255))
        assertTrue(Recovery.moving(1) && Recovery.moving(120))
    }

    @Test fun `a brisk walk outside a workout counts only at 35 percent of reserve or more`() {
        // Nine minutes, short of a sustained run, so the 35 % line alone decides.
        val walk = { x: Float -> (0 until 9).map { it * 60L to bpmAt(x) } }
        assertEquals(0f, Recovery.dailyLoad(walk(0.34f), rest, max, steps = walking).total, 1e-6f)
        val brisk = Recovery.dailyLoad(walk(0.40f), rest, max, steps = walking)
        assertEquals(7 * Recovery.trimpMinute(Recovery.hrr(bpmAt(0.40f), rest, max)), brisk.everyday, 1e-3f)
        assertEquals(0f, brisk.workout, 1e-6f)
    }

    @Test fun `movement without steps counts as Charge counts it, a sustained run`() {
        // Half an hour on an unrecorded bike at 50 % of reserve, no steps: Exercise for Charge, so load for the day.
        val ride = (0 until 30).map { it * 60L to bpmAt(0.5f) }
        val x = Recovery.hrr(bpmAt(0.5f), rest, max)
        assertEquals(30 * Recovery.trimpMinute(x), Recovery.dailyLoad(ride, rest, max, steps = still).everyday, 1e-3f)
        // The same rule decides both: the minutes Charge's run marks are the minutes load counts.
        val minutes = ride.map { it.first }
        val marked = Recovery.sustainedRuns(minutes) { true }
        assertTrue(marked.all { it })
        assertTrue(Recovery.sustainedRuns(minutes.take(9)) { true }.none { it })
        // Asleep it isn't effort, and inside the 20 minutes after a workout it's the workout's.
        assertEquals(0f, Recovery.dailyLoad(ride, rest, max, asleep = { true }, steps = still).total, 1e-6f)
        val after = Recovery.dailyLoad(ride.take(15), rest, max, workouts = listOf(-1200L until 0L), steps = still)
        assertEquals(0f, after.total, 1e-6f)
    }

    @Test fun `a short day counts in the load history only with a workout in it`() {
        val worn = Recovery.LOAD_MIN_WORN_MINUTES
        assertTrue(Recovery.loadDayCounts(worn, Recovery.DailyLoad(0f, 0f)))
        assertTrue(!Recovery.loadDayCounts(worn - 1, Recovery.DailyLoad(0f, 12f)))
        // Two hours worn for a recorded run: the run is the day's load, so it counts.
        assertTrue(Recovery.loadDayCounts(120, Recovery.DailyLoad(80f, 3f)))
    }

    @Test fun `a walk at 100 steps a minute counts from 20 percent of reserve`() {
        val walk = { x: Float -> (0 until 30).map { it * 60L to bpmAt(x) } }
        val perMinute = Recovery.trimpMinute(Recovery.hrr(bpmAt(0.25f), rest, max))
        // 100 steps a minute at 25 %: counted at full TRIMP, less the run rule's first two minutes.
        assertEquals(28 * perMinute, Recovery.dailyLoad(walk(0.25f), rest, max) { 100 }.everyday, 1e-3f)
        // Sitting at 28 % with no steps, under a sustained run's 30 %: nothing.
        assertEquals(0f, Recovery.dailyLoad(walk(0.28f), rest, max) { 0 }.total, 1e-6f)
        // 30 steps a minute at 28 %: under the walking cadence and the 35 % line, so nothing.
        assertEquals(0f, Recovery.dailyLoad(walk(0.28f), rest, max) { 30 }.total, 1e-6f)
        // Below 20 % even a walk is everyday living.
        assertEquals(0f, Recovery.dailyLoad(walk(0.15f), rest, max) { 100 }.total, 1e-6f)
    }

    @Test fun `an easy warm-up inside a workout counts every minute`() {
        // 10 minutes at 25 % of reserve, no steps (on a bike), all inside a recorded workout.
        val warmUp = (0 until 10).map { it * 60L to bpmAt(0.25f) }
        val load = Recovery.dailyLoad(warmUp, rest, max, workouts = listOf(0L until 600L), steps = still)
        assertEquals(10 * Recovery.trimpMinute(Recovery.hrr(bpmAt(0.25f), rest, max)), load.workout, 1e-3f)
        assertEquals(0f, load.everyday, 1e-6f)
        // The same minutes outside the workout: below the line and not moving, so nothing.
        assertEquals(0f, Recovery.dailyLoad(warmUp, rest, max, steps = still).total, 1e-6f)
    }

    @Test fun `overlapping phone and strap workouts count each minute once`() {
        val ride = (0 until 30).map { it * 60L to bpmAt(0.6f) }
        val perMinute = Recovery.trimpMinute(Recovery.hrr(bpmAt(0.6f), rest, max))
        val phone = 0L until 1800L
        val strap = 300L until 1500L
        assertEquals(30 * perMinute, Recovery.dailyLoad(ride, rest, max, workouts = listOf(phone, strap), steps = still).workout, 1e-3f)
        // The workouts' own recordings replace the all-day minutes, once per minute, the higher where they disagree.
        val phoneHr = (0 until 1800 step 5).map { it.toLong() to bpmAt(0.6f) }
        val strapHr = (300 until 1500 step 1).map { it.toLong() to bpmAt(0.6f) + 2 } + (1500L until 1680L).map { it to 190 }
        val merged = Recovery.withWorkoutHeartRate(ride, listOf(phone to phoneHr, strap to strapHr))
        assertEquals(30, merged.size)
        assertEquals(bpmAt(0.6f) + 2, merged.first { it.first == 600L }.second)
        // The strap's recovery recording after its end is outside its range, so the phone's value stands.
        assertEquals(bpmAt(0.6f), merged.first { it.first == 1560L }.second)
    }

    @Test fun `paused time inside a phone workout is not workout load`() {
        // 30 minutes, paused from minute 10 to minute 20, sitting still at 30 % of reserve throughout.
        val minutes = (0 until 30).map { it * 60L to bpmAt(0.3f) }
        val active = Recovery.activeRanges(0L, 1800L, listOf(600L..1199L))
        assertEquals(listOf(0L until 600L, 1200L until 1800L), active)
        val load = Recovery.dailyLoad(minutes, rest, max, workouts = active, steps = still)
        assertEquals(20 * Recovery.trimpMinute(Recovery.hrr(bpmAt(0.3f), rest, max)), load.workout, 1e-3f)
        assertEquals(0f, load.everyday, 1e-6f)
        // A pause running past the end, or several, leave only what's active; none leaves the whole workout.
        assertEquals(listOf(0L until 600L), Recovery.activeRanges(0L, 1800L, listOf(600L..2000L)))
        assertEquals(listOf(0L until 100L, 201L until 300L, 401L until 500L), Recovery.activeRanges(0L, 500L, listOf(100L..200L, 300L..400L)))
        assertEquals(listOf(0L until 1800L), Recovery.activeRanges(0L, 1800L, emptyList()))
    }

    @Test fun `workout and everyday load sum to the total`() {
        // A walk to the gym, a workout, a walk home, and an hour at a desk with a raised heart rate.
        val minutes = (0 until 180).map { m ->
            m * 60L to when (m) {
                in 0 until 15, in 75 until 90 -> bpmAt(0.45f)
                in 15 until 75 -> bpmAt(0.7f)
                else -> bpmAt(0.25f)
            }
        }
        val load = Recovery.dailyLoad(minutes, rest, max, workouts = listOf(900L until 4500L)) { t -> if (t < 5400) 30 else 0 }
        assertTrue(load.workout > 0f && load.everyday > 0f)
        assertEquals(load.workout + load.everyday, load.total, 1e-6f)
        assertEquals(60 * Recovery.trimpMinute(Recovery.hrr(bpmAt(0.7f), rest, max)), load.workout, 1e-2f)
        // The walk there is a sustained run, so all 15 minutes count. The walk home is in the 20 minutes after the
        // workout, where a raised heart rate is the workout's, so it loses its first two to the run rule. The desk
        // hour, under 30 % of reserve, adds nothing.
        assertEquals(28 * Recovery.trimpMinute(Recovery.hrr(bpmAt(0.45f), rest, max)), load.everyday, 1e-2f)
    }

    @Test fun `max HR needs two days that agree`() {
        assertEquals(183, Recovery.observedPeak(listOf(200, 185, 183, 150)))
        assertEquals(188, Recovery.observedPeak(listOf(190, 188)))
        assertNull(Recovery.observedPeak(listOf(200, 190, 180)))
        assertNull(Recovery.observedPeak(listOf(200)))
        // A tested value wins over everything.
        assertEquals(176f, Recovery.maxHeartRate(30, observedPeak = 200, tested = 176), 1e-6f)
        assertEquals(187f, Recovery.maxHeartRate(30, observedPeak = 180), 1e-6f) // never lowered
    }

    @Test fun `a lone 1-minute spike isn't a day's maximum`() {
        val minutes = listOf(0L to 120, 60L to 122, 120L to 210, 180L to 121)
        assertEquals(122, Recovery.filteredMax(minutes))
        assertNull(Recovery.filteredMax(listOf(0L to 240)))
    }

    @Test fun `daytime resting HR prefers the strap's daily values, else nightly plus an offset`() {
        val d = 86_400L
        val daily = (1..20).associate { it * d to 62f }
        val nightly = (1..20).associate { it * d to 56f }
        assertEquals(62f, Recovery.daytimeRestingHr(daily, nightly, 20 * d)!!, 1e-6f)
        // No daily values in the last 14 days: nightly lowest + the learnt 6 bpm offset.
        val nightlyLater = nightly + (30..40).associate { it * d to 54f }
        assertEquals(60f, Recovery.daytimeRestingHr(daily, nightlyLater, 40 * d)!!, 1e-6f)
        // Never seen both: + 3 bpm.
        assertEquals(57f, Recovery.daytimeRestingHr(emptyMap(), nightlyLater, 40 * d)!!, 1e-6f)
        assertNull(Recovery.daytimeRestingHr(emptyMap(), emptyMap(), 40 * d))
    }

    @Test fun `ctl atl and form`() {
        val flat = Recovery.fitnessFatigue(List(60) { 50f })
        assertEquals(50f, flat.last().ctl, 1e-3f)
        assertEquals(0f, flat.last().form, 1e-3f)
        // One step of the recursion after a week of 50: TRIMP 200.
        val t = Recovery.fitnessFatigue(List(7) { 50f } + 200f).last()
        assertEquals(50f + 150f * (1 - exp(-1f / 42)), t.ctl, 1e-3f)
        assertEquals(50f + 150f * (1 - exp(-1f / 7)), t.atl, 1e-3f)
        // Form uses yesterday's values, so a hard day today doesn't change today's form.
        assertEquals(0f, t.form, 1e-3f)
        val next = Recovery.fitnessFatigue(List(7) { 50f } + 200f + 50f).last()
        assertTrue(next.form < 0)
    }

    @Test fun `strain curve`() {
        val m = Recovery.trimpMinute(0.6f) * 60 // 1 h at 60 % HRR, male curve
        assertEquals(7f, Recovery.strain(m), 0.2f)
        assertEquals(15.5f, Recovery.strain(Recovery.trimpMinute(0.75f) * 120), 0.3f) // 2 h at 75 %
        assertEquals(0f, Recovery.strain(0f), 1e-6f)
        assertTrue(Recovery.strain(10_000f) <= Recovery.STRAIN_MAX)
        assertTrue(Recovery.strain(500f) < Recovery.STRAIN_MAX)
        assertEquals(50, Recovery.loadPercentile(50f, List(10) { it * 10f + 5f }))
        assertNull(Recovery.loadPercentile(50f, List(6) { 1f }))
    }

    @Test fun `acwr is uncoupled`() {
        assertEquals(1f, Recovery.acwr(List(35) { 60f })!!, 1e-4f)
        assertNull(Recovery.acwr(List(20) { 60f }))
        // Last week doubled: the chronic part ends before it, so the ratio sees the whole jump.
        val r = Recovery.acwr(List(28) { 60f } + List(7) { 120f })!!
        assertTrue("acwr $r", r > 1.6f)
    }

    @Test fun `the indexed workout lookup matches the plain scan`() {
        // Overlapping, nested, touching and empty ranges, against minutes on and off the minute grid around them.
        val ranges = listOf(600L until 1800L, 900L..1000L, 1799L..1799L, 5000L until 5000L, 7230L..7300L, 0L..30L)
        val index = Recovery.MinuteRanges(ranges)
        for (t in -120L..9000L step 15) assertEquals("t=$t", Recovery.inWorkout(t, ranges), index(t))
        assertEquals(false, Recovery.MinuteRanges(emptyList())(0L))
    }

    @Test fun `reducing a workout to minutes first gives the same merged series`() {
        val ride = (0 until 30).map { it * 60L to bpmAt(0.6f) }
        val phone = 0L until 1800L
        val strap = 330L until 1500L
        val phoneHr = (0 until 1800 step 7).map { it.toLong() to bpmAt(0.6f) + it % 3 }
        val strapHr = (300 until 1700).map { it.toLong() to bpmAt(0.6f) + 2 + it % 5 } + listOf(400L to 10, 401L to 250)
        val workouts = listOf(phone to phoneHr, strap to strapHr)
        assertEquals(
            Recovery.withWorkoutHeartRate(ride, workouts),
            Recovery.withWorkoutMinutes(ride, workouts.map { (r, hr) -> Recovery.workoutMinutes(r, hr) }),
        )
    }
}

