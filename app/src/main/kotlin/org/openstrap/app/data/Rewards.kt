package org.openstrap.app.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.openstrap.app.data.db.AppWorkoutSet
import org.openstrap.app.data.db.BadgeRow
import org.openstrap.app.data.db.PersonalBest
import org.openstrap.app.data.db.WeekResult

/**
 * The weekly goal, personal bests and badges (workouts spec 3, requests spec 7), worked out from the workouts and days
 * alone so they can be rebuilt at any time: on a new workout, a trim or a delete, and for past history on first launch.
 * Pure, like [Recovery]. What's stored ([WeekResult], [PersonalBest], [BadgeRow]) is only a cache of this, except
 * two things history can't say: the goal a past week had, and badges once earned, which are never taken back.
 */
object Rewards {
    /** A workout counts toward the weekly goal from this long ([WorkoutSuggestions.MIN_MINUTES]). */
    const val MIN_SECONDS = WorkoutSuggestions.MIN_MINUTES * 60L

    /** At most this many workouts a day count. */
    const val PER_DAY = 2

    const val DEFAULT_GOAL = 3
    val GOALS = 1..14

    /** The fastest stretches kept for each GPS run: 1 km, 5 km, 10 km and the half marathon. */
    val STRETCHES = listOf(1000, 5000, 10000, 21097)

    /**
     * The fastest [meters] of a run, in [seconds] of moving time, starting at [start] (epoch seconds), so a trim can
     * tell whether it's still inside. Stored as "5000:1601:1761800000", comma separated ([AppWorkout.stretches]).
     */
    data class Stretch(val meters: Int, val seconds: Long, val start: Long) {
        val code: String get() = "$meters:$seconds:$start"

        companion object {
            fun parse(code: String?): List<Stretch> = code.orEmpty().split(',').mapNotNull { part ->
                val (m, s, t) = part.split(':').takeIf { it.size == 3 } ?: return@mapNotNull null
                Stretch(m.toIntOrNull() ?: return@mapNotNull null, s.toLongOrNull() ?: return@mapNotNull null, t.toLongOrNull() ?: return@mapNotNull null)
            }

            fun format(stretches: List<Stretch>): String? = stretches.takeIf { it.isNotEmpty() }?.joinToString(",") { it.code }
        }
    }

    /**
     * The fastest stretch of each of [STRETCHES] along [points] ([Route.track], pauses left out), in moving time. The
     * time over the shortest run of fixes covering the distance is scaled down to the distance exactly.
     */
    fun stretches(points: List<Route.Point>, pauses: List<LongRange> = emptyList()): List<Stretch> {
        val track = Route.track(points)
        if (track.size < 2) return emptyList()
        // Distance and moving time from the first fix to each one.
        val dist = DoubleArray(track.size)
        val time = LongArray(track.size)
        for (i in 1 until track.size) {
            val a = track[i - 1]
            val b = track[i]
            val paused = pauses.any { it.first < b.t && it.last > a.t }
            dist[i] = dist[i - 1] + if (paused) 0.0 else Route.meters(a, b)
            time[i] = time[i - 1] + if (paused) 0 else b.t - a.t
        }
        return STRETCHES.mapNotNull { d ->
            if (dist.last() < d) return@mapNotNull null
            var best: Stretch? = null
            var i = 0
            for (j in 1 until track.size) {
                while (i + 1 < j && dist[j] - dist[i + 1] >= d) i++
                val covered = dist[j] - dist[i]
                if (covered < d) continue
                val secs = Math.round((time[j] - time[i]) * d / covered)
                if (best == null || secs < best.seconds) best = Stretch(d, secs, track[i].t)
            }
            best
        }
    }

    /** A workout as the rewards read it. */
    data class Done(
        val start: Long,
        val shownStart: Long = start,
        val end: Long,
        val sport: Int,
        val activeSeconds: Long,
        /** You started it, or kept it when it was noticed for you ([WorkoutAnalysis.startedByYou]). */
        val startedByYou: Boolean = true,
        /** Metres from GPS only; null without. */
        val gpsMeters: Float? = null,
        /** Metres from GPS or steps, for the distance in total. */
        val meters: Float? = null,
        val stretches: List<Stretch> = emptyList(),
        val structure: String? = null,
        val sets: List<AppWorkoutSet> = emptyList(),
        /** Active kcal, for the weekly recap; null when not worked out. */
        val activeKcal: Float? = null,
    ) {
        /** Counts toward the weekly goal (workouts spec 3.1). */
        val counts: Boolean get() = startedByYou && activeSeconds >= MIN_SECONDS

        /** A run measured by GPS. */
        val gpsRun: Boolean get() = sport in Workouts.RUNS && gpsMeters != null && gpsMeters > 0f

        /** Its stretches still inside it after a trim. */
        val stretchesInside: List<Stretch> get() = stretches.filter { it.start >= shownStart && it.start + it.seconds <= end }
    }

    /** A day's steps. */
    data class Day(val date: LocalDate, val steps: Int)

    /* -------------------------------------------- Weekly goal -------------------------------------------- */

    /** The first day of [date]'s week, weeks starting on [firstDay]. */
    fun weekStart(date: LocalDate, firstDay: DayOfWeek): LocalDate = date.minusDays(((date.dayOfWeek.value - firstDay.value + 7) % 7).toLong())

    /**
     * The workouts that count, in order, each with its week: at most [PER_DAY] a day, the first of the day by start.
     */
    fun counted(workouts: List<Done>, firstDay: DayOfWeek, zone: ZoneId): List<Pair<LocalDate, Done>> =
        workouts.filter { it.counts }.sortedBy { it.shownStart }
            .groupBy { day(it.shownStart, zone) }.values.flatMap { it.take(PER_DAY) }
            .sortedBy { it.shownStart }
            .map { weekStart(day(it.shownStart, zone), firstDay) to it }

    private fun day(t: Long, zone: ZoneId) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()

    /**
     * Every week from the first that counted a workout up to [today]'s, against its goal: a past week keeps the goal
     * [kept] says it had, this week and any week never stored take [goal] (workouts spec 3.1). Weeks with nothing
     * counted are left out unless stored or this week.
     */
    fun weeks(workouts: List<Done>, kept: List<WeekResult>, goal: Int, today: LocalDate, firstDay: DayOfWeek, zone: ZoneId): List<WeekResult> {
        val thisWeek = weekStart(today, firstDay)
        val counts = counted(workouts, firstDay, zone).groupingBy { it.first }.eachCount()
        val goals = kept.associate { it.weekStart to it.goal }
        val weeks = (counts.keys + kept.map { LocalDate.ofEpochDay(it.weekStart) } + thisWeek).filter { it <= thisWeek }.toSortedSet()
        return weeks.map { w ->
            val g = if (w == thisWeek) goal else goals[w.toEpochDay()] ?: goal
            val n = counts[w] ?: 0
            WeekResult(w.toEpochDay(), g, n, n >= g)
        }
    }

    /**
     * Weeks in a row with the goal met, up to [thisWeek]: counted from this week when it's met, else from the week
     * before, so a week still under way never breaks the run.
     */
    fun inARow(weeks: List<WeekResult>, thisWeek: LocalDate): Int {
        val met = weeks.filter { it.met }.map { it.weekStart }.toSet()
        var w = if (thisWeek.toEpochDay() in met) thisWeek else thisWeek.minusWeeks(1)
        var n = 0
        while (w.toEpochDay() in met) { n++; w = w.minusWeeks(1) }
        return n
    }

    /** The most weeks in a row the goal was ever met. */
    fun bestRun(weeks: List<WeekResult>): Int {
        val met = weeks.filter { it.met }.map { it.weekStart }.sorted()
        var best = 0
        var run = 0
        var last: Long? = null
        for (w in met) {
            run = if (last != null && w - last == 7L) run + 1 else 1
            best = maxOf(best, run)
            last = w
        }
        return best
    }

    /** This week's count and goal, and whether [w] is the workout that met it ([counted] with and without it). */
    data class WeekLine(val count: Int, val goal: Int, val metByThis: Boolean)

    fun weekLine(w: Done, workouts: List<Done>, goal: Int, firstDay: DayOfWeek, zone: ZoneId): WeekLine {
        val week = weekStart(day(w.shownStart, zone), firstDay)
        fun count(list: List<Done>) = counted(list, firstDay, zone).count { it.first == week }
        val with = count(workouts.filter { it.start != w.start } + w)
        val without = count(workouts.filter { it.start != w.start })
        return WeekLine(with, goal, with >= goal && without < goal)
    }

    /* -------------------------------------------- Personal bests -------------------------------------------- */

    /** The kinds of personal best (workouts spec 3.2); a lift's is [lift] of the exercise. */
    object Kind {
        const val LONGEST_RUN = "longest_run"
        const val LONGEST_WORKOUT = "longest_workout"
        const val MOST_STEPS = "most_steps"
        private const val FASTEST = "fastest_"
        private const val LIFT = "lift:"

        fun fastest(meters: Int) = "$FASTEST$meters"
        fun fastestMeters(kind: String): Int? = kind.removePrefix(FASTEST).takeIf { kind.startsWith(FASTEST) }?.toIntOrNull()
        fun lift(exercise: String) = LIFT + exercise.trim()
        fun liftName(kind: String): String? = kind.removePrefix(LIFT).takeIf { kind.startsWith(LIFT) }
        /** Lower is better only for the fastest stretches. */
        fun lowerIsBetter(kind: String) = fastestMeters(kind) != null
    }

    /**
     * Every personal best in [workouts] and [days] (workouts spec 3.2): the fastest stretches and the longest of GPS
     * runs only, the longest workout you started, the heaviest logged set of each exercise, the day with the most
     * steps. A tie keeps the first.
     */
    fun bests(workouts: List<Done>, days: List<Day>, zone: ZoneId): List<PersonalBest> {
        val out = mutableListOf<PersonalBest>()
        val ordered = workouts.sortedBy { it.shownStart }
        val runs = ordered.filter { it.gpsRun }
        for (d in STRETCHES) {
            runs.mapNotNull { r -> r.stretchesInside.firstOrNull { it.meters == d }?.let { r to it } }
                .minByOrNull { it.second.seconds }
                ?.let { (r, s) -> out += PersonalBest(Kind.fastest(d), s.seconds.toDouble(), r.shownStart, r.start) }
        }
        runs.maxByOrNull { it.gpsMeters!! }?.let { out += PersonalBest(Kind.LONGEST_RUN, it.gpsMeters!!.toDouble(), it.shownStart, it.start) }
        ordered.filter { it.startedByYou && it.activeSeconds > 0 }.maxByOrNull { it.activeSeconds }
            ?.let { out += PersonalBest(Kind.LONGEST_WORKOUT, it.activeSeconds.toDouble(), it.shownStart, it.start) }
        ordered.flatMap { w -> w.sets.filter { it.end != null && it.weightKg != null && (it.reps ?: 0) > 0 && !it.exercise.isNullOrBlank() }.map { w to it } }
            .groupBy { it.second.exercise!!.trim().lowercase() }.values
            .forEach { sets ->
                val (w, s) = sets.maxByOrNull { it.second.weightKg!! }!!
                out += PersonalBest(Kind.lift(s.exercise!!), s.weightKg!!.toDouble(), w.shownStart, w.start)
            }
        days.filter { it.steps > 0 }.maxByOrNull { it.steps }
            ?.let { out += PersonalBest(Kind.MOST_STEPS, it.steps.toDouble(), it.date.atStartOfDay(zone).toEpochSecond(), null) }
        return out
    }

    /** A best [w] set, and the one it beat. */
    data class NewBest(val best: PersonalBest, val before: PersonalBest)

    /**
     * The bests [w] set that beat one from before it (workouts spec 3.4): the first time something is measured is a
     * record, not a new best.
     */
    fun newBests(w: Done, workouts: List<Done>, days: List<Day>, zone: ZoneId): List<NewBest> {
        val others = workouts.filter { it.start != w.start }
        val before = bests(others, emptyList(), zone).associateBy { it.kind }
        return bests(others + w, emptyList(), zone).filter { it.workoutStart == w.start }
            .mapNotNull { b -> before[b.kind]?.let { NewBest(b, it) } }
    }

    /* -------------------------------------------- Badges -------------------------------------------- */

    enum class Group { Firsts, Milestones, Weekly, Kinds }

    /** A badge: its [id], [title], [group], the [short] text on its art, and for a milestone how much it [needs]. */
    data class Badge(val id: String, val title: String, val group: Group, val short: String, val needs: Long? = null, val unit: String = "")

    /** About 24 (workouts spec 3.3): the requests spec 7.1 set with weekly goal badges for streaks, and a first per kind. */
    val BADGES: List<Badge> = listOf(
        Badge("first_workout", "First workout", Group.Firsts, "1"),
        Badge("first_10k_steps", "First 10,000 step day", Group.Firsts, "10K", 10_000, "steps"),
        Badge("workouts_10", "10 workouts", Group.Milestones, "10", 10, "workouts"),
        Badge("workouts_50", "50 workouts", Group.Milestones, "50", 50, "workouts"),
        Badge("workouts_100", "100 workouts", Group.Milestones, "100", 100, "workouts"),
        Badge("workouts_500", "500 workouts", Group.Milestones, "500", 500, "workouts"),
        Badge("km_100", "100 km in all", Group.Milestones, "100", 100, "km"),
        Badge("km_500", "500 km in all", Group.Milestones, "500", 500, "km"),
        Badge("km_1000", "1,000 km in all", Group.Milestones, "1000", 1000, "km"),
        Badge("steps_1m", "A million steps", Group.Milestones, "1M", 1_000_000, "steps"),
        Badge("goal_first", "First weekly goal met", Group.Weekly, "✓", 1, "weeks"),
        Badge("goal_4", "4 weeks in a row", Group.Weekly, "4", 4, "weeks"),
        Badge("goal_12", "12 weeks in a row", Group.Weekly, "12", 12, "weeks"),
        Badge("goal_26", "26 weeks in a row", Group.Weekly, "26", 26, "weeks"),
        Badge("goal_52", "52 weeks in a row", Group.Weekly, "52", 52, "weeks"),
        Badge("first_run", "First run", Group.Kinds, "Run"),
        Badge("first_5k", "First 5 km run", Group.Kinds, "5K", 5000, "m"),
        Badge("first_10k", "First 10 km run", Group.Kinds, "10K", 10_000, "m"),
        Badge("first_indoor", "First indoor workout", Group.Kinds, "In"),
        Badge("first_log", "First logged strength session", Group.Kinds, "Kg"),
        Badge("first_tabata", "First Tabata", Group.Kinds, "Tab"),
        Badge("first_reps", "First run intervals", Group.Kinds, "Reps"),
        Badge("first_mind", "First yoga or calm session", Group.Kinds, "Om"),
        Badge("first_game", "First game", Group.Kinds, "Play"),
    )

    fun badge(id: String): Badge? = BADGES.firstOrNull { it.id == id }

    /**
     * The goal of the week a badge [row] was earned in, from [weeks]: the first weekly goal badge shows it ("3/3").
     * Null for a badge a day earned, or a week not stored.
     */
    fun goalAt(row: BadgeRow, weeks: List<WeekResult>, firstDay: DayOfWeek, zone: ZoneId): Int? {
        if (row.workoutStart == null) return null
        val week = weekStart(day(row.earnedAt, zone), firstDay).toEpochDay()
        return weeks.firstOrNull { it.weekStart == week }?.goal
    }

    /** What a badge was earned by: the time, and the workout or the day. */
    private data class Earn(val at: Long, val workout: Long? = null, val day: Long? = null)

    /**
     * Every badge [workouts], [days] and [weeks] have earned, each when it was first earned, with the workout or day
     * that earned it (requests spec 7.2). A week's badge is earned by the workout that met its goal.
     */
    fun badges(workouts: List<Done>, days: List<Day>, weeks: List<WeekResult>, firstDay: DayOfWeek, zone: ZoneId): List<BadgeRow> {
        val earned = linkedMapOf<String, Earn>()
        fun earn(id: String, e: Earn) { earned.putIfAbsent(id, e) }
        val mine = workouts.filter { it.startedByYou }.sortedBy { it.shownStart }
        // The distance in all counts every workout you started; the firsts and the workout counts only those that
        // count toward the weekly goal, so a mis-tap of a minute earns nothing.
        var km = 0.0
        var counted = 0
        mine.forEach { w ->
            val e = Earn(w.end, w.start)
            km += (w.meters ?: 0f) / 1000.0
            listOf(100, 500, 1000).forEach { n -> if (km >= n) earn("km_$n", e) }
            if (!w.counts) return@forEach
            counted++
            earn("first_workout", e)
            listOf(10, 50, 100, 500).forEach { n -> if (counted >= n) earn("workouts_$n", e) }
            val family = Workouts.family(w.sport)
            val structure = Structure.parse(w.structure)
            if (w.sport in Workouts.RUNS) earn("first_run", e)
            if (w.gpsRun && w.gpsMeters!! >= 5000f) earn("first_5k", e)
            if (w.gpsRun && w.gpsMeters!! >= 10_000f) earn("first_10k", e)
            if (family == Workouts.Family.IndoorCardio && w.sport != Workouts.GENERIC_ACTIVITY) earn("first_indoor", e)
            if (family == Workouts.Family.Strength && w.sets.any { it.reps != null && it.weightKg != null }) earn("first_log", e)
            if (structure is Structure.Timed && structure.work == Structure.TABATA.work && structure.rest == Structure.TABATA.rest) earn("first_tabata", e)
            if (structure is Structure.Reps) earn("first_reps", e)
            if (family == Workouts.Family.MindBody) earn("first_mind", e)
            if (family == Workouts.Family.Games) earn("first_game", e)
        }
        var steps = 0L
        days.sortedBy { it.date }.forEach { d ->
            val e = Earn(d.date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1, day = d.date.toEpochDay())
            if (d.steps >= 10_000) earn("first_10k_steps", e)
            steps += d.steps
            if (steps >= 1_000_000) earn("steps_1m", e)
        }
        // Each met week, by the workout that met it, and the run of weeks it extends.
        val goals = weeks.associate { it.weekStart to it.goal }
        val byWeek = counted(workouts, firstDay, zone).groupBy { it.first.toEpochDay() }
        var run = 0
        var last: Long? = null
        for (w in weeks.filter { it.met }.sortedBy { it.weekStart }) {
            val goal = goals[w.weekStart] ?: continue
            val meeting = byWeek[w.weekStart]?.getOrNull(goal - 1)?.second ?: continue
            run = if (last != null && w.weekStart - last == 7L) run + 1 else 1
            last = w.weekStart
            val e = Earn(meeting.end, meeting.start)
            earn("goal_first", e)
            listOf(4, 12, 26, 52).forEach { n -> if (run >= n) earn("goal_$n", e) }
        }
        return BADGES.mapNotNull { b -> earned[b.id]?.let { BadgeRow(b.id, it.at, it.workout, it.day) } }
    }

    /**
     * The badges [w] earned (workouts spec 2.8): those it earns that weren't earned without it, less any already
     * [stored] from another workout or day, since badges are never taken back.
     */
    fun newBadges(w: Done, workouts: List<Done>, days: List<Day>, weeks: List<WeekResult>, stored: List<BadgeRow>, firstDay: DayOfWeek, zone: ZoneId): List<Badge> {
        val others = workouts.filter { it.start != w.start }
        val with = badges(others + w, days, weeks, firstDay, zone).filter { it.workoutStart == w.start }.map { it.id }
        val without = badges(others, days, weeks, firstDay, zone).map { it.id }.toSet()
        val before = stored.filter { it.workoutStart != w.start }.map { it.id }.toSet()
        return with.filter { it !in without && it !in before }.mapNotNull(::badge)
    }

    /** How far toward a locked badge: what you have and what it needs, in its unit; null for one with no count. */
    fun progress(b: Badge, workouts: List<Done>, days: List<Day>, weeks: List<WeekResult>, thisWeek: LocalDate): Pair<Long, Long>? {
        val need = b.needs ?: return null
        val mine = workouts.filter { it.startedByYou }
        val have = when {
            b.id.startsWith("workouts_") -> mine.count { it.counts }.toLong()
            b.id.startsWith("km_") -> (mine.sumOf { (it.meters ?: 0f).toDouble() } / 1000).toLong()
            b.id == "steps_1m" -> days.sumOf { it.steps.toLong() }
            b.id == "first_10k_steps" -> days.maxOfOrNull { it.steps.toLong() } ?: 0
            b.id.startsWith("goal_") -> inARow(weeks, thisWeek).toLong()
            b.id == "first_5k" || b.id == "first_10k" -> mine.filter { it.gpsRun }.maxOfOrNull { it.gpsMeters!!.toLong() } ?: 0
            else -> return null
        }
        return have.coerceAtMost(need) to need
    }
}
