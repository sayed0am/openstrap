package org.openstrap.app.data

import org.openstrap.app.data.db.AppWorkoutRound

/**
 * A structured workout's rounds (workouts spec 2.5, 4), stored as [code] on the workout
 * ([org.openstrap.app.data.db.AppWorkout.structure]):
 *
 * - [Timed]: "t:20/10x8", [work] seconds on, [rest] off, [rounds] times (Tabata, 40 / 20).
 * - [EveryMinute]: "e:60x12", a round starts every [period] seconds; you tap Done and rest for what's left of it.
 * - [Reps]: "r:400m/90x6" or "r:120s/60x8", run reps of a distance or a time with rest by time.
 *
 * A trailing ":w" warms up first, open until you start the rounds.
 */
sealed interface Structure {
    val rounds: Int
    val warmUp: Boolean
    val code: String

    /** The same rounds, warming up first ([on]) or not. */
    fun warm(on: Boolean): Structure

    data class Timed(val work: Long, val rest: Long, override val rounds: Int, override val warmUp: Boolean = false) : Structure {
        override val code get() = "t:$work/${rest}x$rounds" + if (warmUp) ":w" else ""
        override fun warm(on: Boolean) = copy(warmUp = on)
    }

    data class EveryMinute(val period: Long, override val rounds: Int, override val warmUp: Boolean = false) : Structure {
        override val code get() = "e:${period}x$rounds" + if (warmUp) ":w" else ""
        override fun warm(on: Boolean) = copy(warmUp = on)
    }

    /** A rep is [meters] of running, or [seconds] when there's no distance to count (the treadmill side). */
    data class Reps(val meters: Int?, val seconds: Long?, val rest: Long, override val rounds: Int, override val warmUp: Boolean = false) : Structure {
        override val code get() = "r:" + (meters?.let { "${it}m" } ?: "${seconds}s") + "/${rest}x$rounds" + if (warmUp) ":w" else ""
        override fun warm(on: Boolean) = copy(warmUp = on)
    }

    /**
     * A game's periods (workouts spec 2.7): [rounds] periods of [length] seconds with a [rest] break between, "p:2x2700/900".
     * Half time can come early (a tap), and the strap buzzes at the end of each period and break.
     */
    data class Periods(override val rounds: Int, val length: Long, val rest: Long) : Structure {
        override val warmUp get() = false
        override val code get() = "p:${rounds}x$length/$rest"
        override fun warm(on: Boolean) = this
    }

    companion object {
        private val PERIODS = Regex("""p:(\d+)x(\d+)/(\d+)""")
        private val TIMED = Regex("""t:(\d+)/(\d+)x(\d+)""")
        private val EVERY = Regex("""e:(\d+)x(\d+)""")
        private val REPS = Regex("""r:(\d+)([ms])/(\d+)x(\d+)""")

        /** [code] as written by [Structure.code]; null for none, or anything unreadable. */
        fun parse(code: String?): Structure? {
            code ?: return null
            PERIODS.matchEntire(code)?.destructured?.let { (n, l, r) ->
                return Periods(n.toInt(), l.toLong(), r.toLong()).takeIf { it.rounds > 0 && it.length > 0 }
            }
            val warm = code.endsWith(":w")
            val body = code.removeSuffix(":w")
            TIMED.matchEntire(body)?.destructured?.let { (w, r, n) ->
                return Timed(w.toLong(), r.toLong(), n.toInt(), warm).takeIf { it.work > 0 && it.rounds > 0 }
            }
            EVERY.matchEntire(body)?.destructured?.let { (p, n) -> return EveryMinute(p.toLong(), n.toInt(), warm).takeIf { it.period > 0 && it.rounds > 0 } }
            REPS.matchEntire(body)?.destructured?.let { (v, unit, r, n) ->
                val reps = if (unit == "m") Reps(v.toInt(), null, r.toLong(), n.toInt(), warm) else Reps(null, v.toLong(), r.toLong(), n.toInt(), warm)
                return reps.takeIf { it.rounds > 0 && (it.meters ?: it.seconds?.toInt() ?: 0) > 0 }
            }
            return null
        }

        /** Whether [s] fits a workout of [sport]: timed rounds for HIIT, reps for a run. */
        fun fits(s: Structure, sport: Int): Boolean = when (s) {
            is Reps -> sport in Workouts.RUNS
            is Periods -> Workouts.family(sport) == Workouts.Family.Games
            else -> Workouts.family(sport) == Workouts.Family.Intervals
        }

        /** A game's Halves and Quarters on Ready, before you change their length and break. */
        val HALVES = Periods(2, 40 * 60, 10 * 60)
        val QUARTERS = Periods(4, 12 * 60, 2 * 60)

        /** HIIT's presets (workouts spec 2.5): Tabata, 40 / 20 and Every minute. Free has none. */
        val TABATA = Timed(20, 10, 8)
        val FORTY_TWENTY = Timed(40, 20, 10)
        val EVERY_MINUTE = EveryMinute(60, 12)
    }
}

/** Where a structured workout is: warming up, working round [round] (from 0), resting after it, or done. */
sealed interface Phase {
    data object WarmUp : Phase
    data class Work(val round: Int, val start: Long, val endsAt: Long?) : Phase
    data class Rest(val round: Int, val start: Long, val endsAt: Long) : Phase
    data object Done : Phase
}

/** A buzz the phases ask for at a switch ([WorkoutBuzz]). */
enum class Switch { WorkStarts, RestStarts, Finished }

/**
 * The rounds as a state machine (workouts spec 6), pure so the same rules run with the screen off and are tested.
 * Its state is the rounds recorded so far ([AppWorkoutRound], one row a round: work start, work end, rest end), so a
 * workout picked up after the process died carries on where it was. Times are epoch seconds; [active] turns one into
 * the workout's active seconds, so a pause doesn't eat into a work or rest.
 */
object Phases {
    /** The phase [rounds] are in under [s], [extra] seconds added to the rest under way (+10 s). */
    fun phase(s: Structure, rounds: List<AppWorkoutRound>, active: (Long) -> Long, extra: Long = 0): Phase {
        val last = rounds.lastOrNull() ?: return if (s.warmUp) Phase.WarmUp else Phase.Work(0, 0, workEnd(s, 0, null))
        return when {
            last.workEnd == null -> Phase.Work(last.index, last.workStart, workEnd(s, last.workStart, active))
            last.restEnd == null -> Phase.Rest(last.index, last.workEnd, restEnd(s, last, active) + extra)
            last.index + 1 >= s.rounds -> Phase.Done
            else -> Phase.Work(last.index + 1, last.restEnd, null)
        }
    }

    /** When round work begun at [start] ends by the clock, in active seconds; null when it ends on a tap or a distance. */
    private fun workEnd(s: Structure, start: Long, active: ((Long) -> Long)?): Long? = when (s) {
        is Structure.Timed -> (active?.invoke(start) ?: 0) + s.work
        is Structure.EveryMinute -> null
        is Structure.Reps -> s.seconds?.let { (active?.invoke(start) ?: 0) + it }
        is Structure.Periods -> (active?.invoke(start) ?: 0) + s.length
    }

    /** When the rest after [r] ends, in active seconds. Every minute rests for what's left of the round's minute. */
    private fun restEnd(s: Structure, r: AppWorkoutRound, active: (Long) -> Long): Long = when (s) {
        is Structure.Timed -> active(r.workEnd!!) + s.rest
        is Structure.EveryMinute -> active(r.workStart) + s.period
        is Structure.Reps -> active(r.workEnd!!) + s.rest
        is Structure.Periods -> active(r.workEnd!!) + s.rest
    }

    /** What changed at a tick or a tap: the rounds to store (whole list) and the buzz for the switch, if one happened. */
    data class Step(val rounds: List<AppWorkoutRound>, val switch: Switch?)

    /**
     * The clock moved to [now]: a work or rest that is over ends, and the next begins. [meters] is the distance since a
     * time, for run reps (null without one); [peak] the highest heart rate between two times, kept with each round.
     * The first round starts at the first tick unless the workout warms up first.
     */
    fun tick(
        s: Structure, rounds: List<AppWorkoutRound>, workoutStart: Long, now: Long, active: (Long) -> Long,
        meters: (from: Long) -> Float? = { null }, peak: (Long, Long) -> Int? = { _, _ -> null }, extra: Long = 0,
    ): Step {
        if (rounds.isEmpty()) return if (s.warmUp) Step(rounds, null) else Step(listOf(AppWorkoutRound(workoutStart, 0, now)), null)
        return when (val p = phase(s, rounds, active, extra)) {
            is Phase.Work -> {
                if (p.start == 0L || rounds.last().workEnd != null) return Step(rounds + AppWorkoutRound(workoutStart, p.round, now), Switch.WorkStarts)
                val byClock = p.endsAt != null && active(now) >= p.endsAt
                val byDistance = s is Structure.Reps && s.meters != null && (meters(p.start) ?: 0f) >= s.meters
                // Every minute: the minute ran out before Done, so the next round starts with no rest.
                val minuteUp = s is Structure.EveryMinute && active(now) >= active(p.start) + s.period
                when {
                    minuteUp -> endWork(s, rounds, now, peak, restToo = true)
                    byClock || byDistance -> endWork(s, rounds, now, peak)
                    else -> Step(rounds, null)
                }
            }
            is Phase.Rest -> if (active(now) >= p.endsAt) endRest(s, rounds, now) else Step(rounds, null)
            Phase.WarmUp, Phase.Done -> Step(rounds, null)
        }
    }

    /** Skip, Done, or Start the rounds: whatever is under way ends now and the next phase begins. */
    fun skip(s: Structure, rounds: List<AppWorkoutRound>, workoutStart: Long, now: Long, active: (Long) -> Long, peak: (Long, Long) -> Int? = { _, _ -> null }): Step =
        when (val p = phase(s, rounds, active)) {
            Phase.WarmUp -> Step(listOf(AppWorkoutRound(workoutStart, 0, now)), Switch.WorkStarts)
            is Phase.Work -> if (rounds.lastOrNull()?.workEnd == null && rounds.isNotEmpty()) endWork(s, rounds, now, peak)
                else Step(rounds + AppWorkoutRound(workoutStart, p.round, now), Switch.WorkStarts)
            is Phase.Rest -> endRest(s, rounds, now)
            Phase.Done -> Step(rounds, null)
        }

    private fun endWork(s: Structure, rounds: List<AppWorkoutRound>, now: Long, peak: (Long, Long) -> Int?, restToo: Boolean = false): Step {
        val r = rounds.last()
        val ended = r.copy(workEnd = now, restEnd = if (restToo) now else null, peakBpm = peak(r.workStart, now))
        val done = rounds.dropLast(1) + ended
        // The last round has no rest after it: its work ending is the end.
        if (r.index + 1 >= s.rounds) return Step(done.closeLast(now), Switch.Finished)
        return if (restToo) Step(done + AppWorkoutRound(r.workoutStart, r.index + 1, now), Switch.WorkStarts) else Step(done, Switch.RestStarts)
    }

    private fun endRest(s: Structure, rounds: List<AppWorkoutRound>, now: Long): Step {
        val r = rounds.last()
        val closed = rounds.dropLast(1) + r.copy(restEnd = now)
        if (r.index + 1 >= s.rounds) return Step(closed, Switch.Finished)
        return Step(closed + AppWorkoutRound(r.workoutStart, r.index + 1, now), Switch.WorkStarts)
    }

    private fun List<AppWorkoutRound>.closeLast(now: Long) = dropLast(1) + last().let { it.copy(restEnd = it.restEnd ?: now) }

    /** Rounds whose work has ended, for "rounds done". */
    fun roundsDone(rounds: List<AppWorkoutRound>): Int = rounds.count { it.workEnd != null }
}
