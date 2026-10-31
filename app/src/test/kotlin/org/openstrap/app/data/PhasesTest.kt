package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.db.AppWorkoutRound

class PhasesTest {
    private val start = 1_000L
    /** No pauses: active seconds are seconds since the start. */
    private val active = { t: Long -> t - start }

    /** Ticks [s] once a second from [from] to [to], collecting the switches. */
    private fun run(s: Structure, from: Long, to: Long, rounds: List<AppWorkoutRound> = emptyList(), meters: (Long) -> Float? = { null }): Pair<List<AppWorkoutRound>, List<Pair<Long, Switch>>> {
        var r = rounds
        val switches = mutableListOf<Pair<Long, Switch>>()
        for (t in from..to) {
            val step = Phases.tick(s, r, start, t, active, meters)
            r = step.rounds
            step.switch?.let { switches += t to it }
        }
        return r to switches
    }

    @Test
    fun structuresRoundTrip() {
        listOf(Structure.TABATA, Structure.FORTY_TWENTY, Structure.EVERY_MINUTE, Structure.Timed(30, 15, 5, warmUp = true),
            Structure.Reps(400, null, 90, 6), Structure.Reps(null, 120, 60, 8, warmUp = true)).forEach {
            assertEquals(it, Structure.parse(it.code))
        }
        assertEquals("t:20/10x8", Structure.TABATA.code)
        assertEquals("r:400m/90x6", Structure.Reps(400, null, 90, 6).code)
        assertEquals(Structure.Periods(2, 2700, 900), Structure.parse("p:2x2700/900"))
        assertNull(Structure.parse("t:0/10x8"))
        assertNull(Structure.parse(null))
    }

    @Test
    fun tabataRunsEightRoundsOfTwentyAndTenThenEnds() {
        val (rounds, switches) = run(Structure.TABATA, start, start + 300)
        assertEquals(8, rounds.size)
        assertTrue(rounds.all { it.workEnd != null && it.restEnd != null })
        rounds.forEach { r -> assertEquals(20L, r.workEnd!! - r.workStart) }
        rounds.dropLast(1).forEach { r -> assertEquals(10L, r.restEnd!! - r.workEnd!!) }
        // One rest and one work buzz per switch, seven of each, then the end: 4 minutes less the last rest.
        assertEquals(7, switches.count { it.second == Switch.RestStarts })
        assertEquals(7, switches.count { it.second == Switch.WorkStarts })
        assertEquals(start + 230 to Switch.Finished, switches.last())
        assertEquals(Phase.Done, Phases.phase(Structure.TABATA, rounds, active))
    }

    @Test
    fun skipEndsWorkOrRestNow() {
        var r = run(Structure.TABATA, start, start + 5).first
        assertTrue(Phases.phase(Structure.TABATA, r, active) is Phase.Work)
        var step = Phases.skip(Structure.TABATA, r, start, start + 6, active)
        assertEquals(Switch.RestStarts, step.switch)
        r = step.rounds
        assertEquals(Phase.Rest(0, start + 6, 16), Phases.phase(Structure.TABATA, r, active))
        step = Phases.skip(Structure.TABATA, r, start, start + 8, active)
        assertEquals(Switch.WorkStarts, step.switch)
        assertEquals(2, step.rounds.size)
    }

    @Test
    fun aRestCanBeLengthened() {
        val r = run(Structure.TABATA, start, start + 21).first
        val rest = Phases.phase(Structure.TABATA, r, active, extra = 10) as Phase.Rest
        assertEquals(40L, rest.endsAt)
        assertNull(Phases.tick(Structure.TABATA, r, start, start + 35, active, extra = 10).switch)
        assertEquals(Switch.WorkStarts, Phases.tick(Structure.TABATA, r, start, start + 40, active, extra = 10).switch)
    }

    @Test
    fun everyMinuteRestsForWhatsLeftOfTheMinute() {
        val s = Structure.EveryMinute(60, 3)
        var r = run(s, start, start + 1).first
        // Done after 40 s: rest until the minute is up.
        r = Phases.skip(s, r, start, start + 40, active).rounds
        assertEquals(Phase.Rest(0, start + 40, 60), Phases.phase(s, r, active))
        r = run(s, start + 41, start + 60, r).first
        assertEquals(2, r.size)
        // Never tapping Done: the next minute starts anyway, with no rest.
        val (late, switches) = run(s, start + 61, start + 125, r)
        assertEquals(3, late.size)
        assertEquals(late[1].workEnd, late[1].restEnd)
        assertTrue(switches.any { it.second == Switch.WorkStarts })
    }

    @Test
    fun runRepsEndOnTheirDistance() {
        val s = Structure.Reps(400, null, 90, 2)
        // No distance yet: the rep goes on, however long.
        val (first, _) = run(s, start, start + 200)
        assertTrue(Phases.phase(s, first, active) is Phase.Work)
        // 4 m a second since the rep began: 400 m at 100 s.
        assertNull(Phases.tick(s, first, start, start + 99, active, meters = { from -> 4f * (start + 99 - from) }).switch)
        assertEquals(Switch.RestStarts, Phases.tick(s, first, start, start + 100, active, meters = { from -> 4f * (start + 100 - from) }).switch)
    }

    @Test
    fun aWarmUpWaitsForStart() {
        val s = Structure.Timed(20, 10, 2, warmUp = true)
        val (r, switches) = run(s, start, start + 300)
        assertTrue(r.isEmpty())
        assertTrue(switches.isEmpty())
        assertEquals(Phase.WarmUp, Phases.phase(s, r, active))
        val go = Phases.skip(s, r, start, start + 301, active)
        assertEquals(Switch.WorkStarts, go.switch)
        assertEquals(start + 301, go.rounds.single().workStart)
    }

    @Test
    fun pausedTimeDoesntCount() {
        // Paused for 100 s from 10 s in: work that began at 0 ends at 120 s wall time, 20 s active.
        val paused = { t: Long -> (t - start) - (t - start - 10).coerceIn(0, 100) }
        val r = listOf(AppWorkoutRound(start, 0, start))
        assertNull(Phases.tick(Structure.TABATA, r, start, start + 115, paused).switch)
        assertEquals(Switch.RestStarts, Phases.tick(Structure.TABATA, r, start, start + 120, paused).switch)
    }

    @Test
    fun peaksAreKeptPerRound() {
        val r = listOf(AppWorkoutRound(start, 0, start))
        val step = Phases.tick(Structure.TABATA, r, start, start + 20, active, peak = { a, b -> if (a == start && b == start + 20) 171 else null })
        assertEquals(171, step.rounds.single().peakBpm)
        assertEquals(1, Phases.roundsDone(step.rounds))
    }
}
