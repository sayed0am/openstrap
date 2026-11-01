package org.openstrap.app.ui.workout

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.openstrap.app.data.Games
import org.openstrap.app.data.Strength
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.AppWorkoutRound
import org.openstrap.app.data.db.AppWorkoutSet
import org.openstrap.app.ui.glow.workoutClock

/*
 * What each kind of workout's summary says under its hero (workouts spec 2.8): the fastest split, sets per exercise
 * and how fast rest brought the heart rate down, each round's peak and drop, how the heart settled, a game's periods
 * and whether you faded. Pure, so it's tested without a workout ending.
 */

/** One logged or numbered set on the summary: "10 × 60 kg" (or "Set 3" unlogged) and its peak. */
data class SetLine(val number: Int, val text: String, val peak: Int?)

/** An exercise and its sets, in the order first done; [name] is null for sets without one. */
data class ExerciseSets(val name: String?, val sets: List<SetLine>)

/** One round of an interval workout: its peak, and how far the heart rate came down in the rest after it. */
data class RoundLine(val number: Int, val peak: Int?, val drop: Int?)

/** One period of a game: its name, average heart rate and hard efforts. */
data class PeriodLine(val name: String, val averageBpm: Int?, val hardEfforts: Int, val seconds: Long)

/** A row under Earned: a new best, a badge, or the weekly goal ([met] once this week's is). */
data class EarnedLine(val kind: Kind, val text: String, val value: String? = null, val note: String? = null, val met: Boolean = false) {
    enum class Kind { Best, Badge, Goal }
}

object FamilySummary {
    /** "This morning, 07:12", "Today, 18:40" (the afternoon), "This evening, 19:05"; another day "Saturday 10:00". */
    fun whenLine(start: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val t = Instant.ofEpochSecond(start).atZone(zone)
        val clock = t.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        return when (t.toLocalDate()) {
            today -> when (t.hour) {
                in 4..11 -> "This morning, $clock"
                in 17..23 -> "This evening, $clock"
                else -> "Today, $clock"
            }
            today.minusDays(1) -> "Yesterday, $clock"
            else -> t.format(DateTimeFormatter.ofPattern("EEEE", locale)) + " " + clock
        }
    }

    /** The fastest whole kilometre among [rows] (by its time), when there are two or more to choose from. */
    fun fastestSplit(rows: List<SplitRow>): Int? {
        val whole = rows.withIndex().filter { it.value.name.startsWith("Km ") }
        if (whole.size < 2) return null
        return whole.minByOrNull { seconds(it.value.time) }?.index
    }

    private fun seconds(clock: String): Long = clock.split(":").fold(0L) { acc, p -> acc * 60 + (p.toLongOrNull() ?: 0) }

    /**
     * Finished [sets] by exercise, in the order each was first done, with the peak of [readings] (time, bpm) in each set.
     * A logged set reads "10 × 60 kg"; one without a log reads by its time.
     */
    fun exerciseSets(sets: List<AppWorkoutSet>, readings: List<Pair<Long, Int>>): List<ExerciseSets> {
        val done = sets.filter { it.end != null }
        val order = done.map { it.exercise?.trim()?.takeIf(String::isNotEmpty) }.distinctBy { it?.lowercase() }
        return order.map { name ->
            val mine = done.filter { it.exercise?.trim()?.takeIf(String::isNotEmpty)?.lowercase() == name?.lowercase() }
            ExerciseSets(name, mine.mapIndexed { i, s ->
                val text = if (s.reps != null && s.weightKg != null) "${s.reps} × ${Strength.kgText(s.weightKg)} kg"
                else if (s.reps != null) "${s.reps} reps" else workoutClock(s.end!! - s.start)
                SetLine(if (name == null) s.index + 1 else i + 1, text, Strength.peak(readings, s.start, s.end!!))
            })
        }
    }

    /** Sets on the hero: every finished one. */
    fun setsDone(sets: List<AppWorkoutSet>): Int = sets.count { it.end != null }

    /** The average rest between [sets], in seconds; null without a finished rest. */
    fun averageRest(sets: List<AppWorkoutSet>): Long? {
        val rests = sets.mapNotNull { s -> s.end?.let { e -> s.restEnd?.minus(e) } }.filter { it > 0 }
        return if (rests.isEmpty()) null else rests.sum() / rests.size
    }

    /**
     * How long the heart rate took to fall under [below] after each set, on average over the rests where it did: from
     * the set's end to the first reading under the level before the rest ended. Null without a level or such a rest.
     */
    fun restRecovery(sets: List<AppWorkoutSet>, readings: List<Pair<Long, Int>>, below: Int?): Long? {
        below ?: return null
        val times = sets.mapNotNull { s ->
            val end = s.end ?: return@mapNotNull null
            val until = s.restEnd ?: return@mapNotNull null
            readings.firstOrNull { it.first in end..until && it.second < below }?.let { it.first - end }
        }
        return if (times.isEmpty()) null else times.sum() / times.size
    }

    /**
     * "Your heart rate dropped under 110 in 58 s on average", and against the last strength session's [before]: "9 s
     * faster than last time" or slower. Within 3 s it says "about the same as last time".
     */
    fun restLine(seconds: Long, below: Int, before: Long?): String {
        val first = "Your heart rate dropped under $below in ${clockOrSeconds(seconds)} on average"
        val diff = before?.minus(seconds)
        return when {
            diff == null -> "$first."
            diff > 3 -> "$first, ${clockOrSeconds(diff)} faster than last time."
            diff < -3 -> "$first, ${clockOrSeconds(-diff)} slower than last time."
            else -> "$first, about the same as last time."
        }
    }

    private fun clockOrSeconds(s: Long) = if (s < 60) "$s s" else workoutClock(s)

    /**
     * Each finished round's peak, and the drop in the rest after it: the peak less the lowest reading of [readings]
     * in that rest. No drop for a round without a rest after it.
     */
    fun rounds(rounds: List<AppWorkoutRound>, readings: List<Pair<Long, Int>>): List<RoundLine> =
        rounds.filter { it.workEnd != null }.map { r ->
            val workEnd = r.workEnd!!
            val peak = r.peakBpm ?: Strength.peak(readings, r.workStart, workEnd)
            val low = r.restEnd?.takeIf { it > workEnd }?.let { end -> readings.filter { it.first in workEnd..end }.minOfOrNull { it.second } }
            RoundLine(r.index + 1, peak, if (peak != null && low != null) (peak - low).coerceAtLeast(0) else null)
        }

    /**
     * The line under the rounds: "You recovered 19 bpm in 10 s on average. Round 6 was your hardest." The rest is
     * [restSeconds] when the rounds had a set rest; the hardest round is the highest peak, named only with 3 or more.
     */
    fun roundsLine(lines: List<RoundLine>, restSeconds: Long?): String? {
        val drops = lines.mapNotNull { it.drop }
        val hardest = hardestRound(lines)?.let { lines[it] }
        val parts = listOfNotNull(
            drops.takeIf { it.isNotEmpty() }?.let {
                val avg = Math.round(it.average()).toInt()
                if (restSeconds != null) "You recovered $avg bpm in ${clockOrSeconds(restSeconds)} on average." else "You recovered $avg bpm in each rest on average."
            },
            hardest?.let { "Round ${it.number} was your hardest." },
        )
        return parts.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /** The hardest of [lines], the highest peak (the first of equals), as its index; named only with 3 or more peaks. */
    fun hardestRound(lines: List<RoundLine>): Int? {
        if (lines.count { it.peak != null } < 3) return null
        return lines.indices.filter { lines[it].peak != null }.maxByOrNull { lines[it].peak!! }
    }

    /** A peak bar starts from this share of the highest peak, so the gaps between sets and rounds show. */
    const val PEAK_FLOOR = 0.7

    /** The shortest a bar gets, so a low one still shows. */
    const val PEAK_MIN_SHARE = 0.08f

    /**
     * How long each of [peaks] draws against the highest (boards SummaryStrength, SummaryHiit, SummaryGame): the
     * highest is full, the rest by how far they sit above [PEAK_FLOOR] of it, at least [PEAK_MIN_SHARE]. Null for a
     * missing peak.
     */
    fun peakShares(peaks: List<Int?>): List<Float?> {
        val top = peaks.filterNotNull().maxOrNull() ?: return peaks.map { null }
        val floor = top * PEAK_FLOOR
        return peaks.map { p -> p?.let { (if (top <= floor) 1.0 else (it - floor) / (top - floor)).toFloat().coerceIn(PEAK_MIN_SHARE, 1f) } }
    }

    /** Work time over the finished rounds. */
    fun workSeconds(rounds: List<AppWorkoutRound>): Long = rounds.sumOf { r -> r.workEnd?.let { it - r.workStart } ?: 0L }

    /**
     * [summary]'s heart rate over its shown range, as (epoch seconds, bpm): what its hero, its sections and its share
     * card all read.
     */
    fun readings(summary: PhoneSummary): List<Pair<Long, Int>> {
        val a = summary.analysis
        return summary.recorded.filter { it.timestamp in a.shownStart..a.end }.map { it.timestamp to it.bpm }
    }

    /** How long to average over at each end, for the heart settling. */
    const val SETTLE_S = 120L

    /** How far the heart has to come down over a calm session before it reads as settled rather than held. */
    const val SETTLED_BPM = 3

    /**
     * The heart settling over a calm session: the average of [readings] over its first [SETTLE_S] and over its last.
     * Null when the session is too short for both, or either has no readings.
     */
    fun settled(readings: List<Pair<Long, Int>>, start: Long, end: Long): Pair<Int, Int>? {
        if (end - start < SETTLE_S * 3) return null
        val first = WorkoutCopy.averageOver(readings, start, start + SETTLE_S) ?: return null
        val last = WorkoutCopy.averageOver(readings, end - SETTLE_S, end) ?: return null
        return first to last
    }

    /** How far the heart came down from [from] to [to], null under [SETTLED_BPM]: the summary's and the card's rule. */
    fun settledDrop(from: Int, to: Int): Int? = (from - to).takeIf { it >= SETTLED_BPM }

    /** "Your heart settled from 96 to 66.", or when it didn't come down, "Your heart held near 72." */
    fun settledLine(from: Int, to: Int): String = if (settledDrop(from, to) != null) "Your heart settled from $from to $to." else "Your heart held near $to."

    /** [settledLine] as the share card says it: "Heart settled 96 to 66", or "Heart held near 72". */
    fun settledShort(from: Int, to: Int): String = if (settledDrop(from, to) != null) "Heart settled $from to $to" else "Heart held near $to"

    /**
     * A game's [periods] from its rounds, named as the live screen names them out of the [of] it was set up with
     * ([StartCopy.periodName]: "1st half", "3rd quarter", "Period 2"), so a game ended early keeps its periods' names.
     * With the average of [readings] and the hard efforts ([Games.hardEfforts]) in each.
     */
    fun periods(rounds: List<AppWorkoutRound>, readings: List<Pair<Long, Int>>, maxHr: Int, of: Int): List<PeriodLine> {
        val done = rounds.filter { it.workEnd != null }
        return done.map { r ->
            val name = StartCopy.periodName(r.index, of)
            val workEnd = r.workEnd!!
            val inside = readings.filter { it.first in r.workStart..workEnd }
            PeriodLine(name, WorkoutCopy.averageOver(readings, r.workStart, workEnd), Games.hardEfforts(inside, maxHr), workEnd - r.workStart)
        }
    }

    /** A game is read in blocks this long. */
    const val READ_BLOCK_S = 10 * 60L

    /** A block this far under the first half's average is a fade. */
    const val FADE_PCT = 6

    /**
     * One line on how a game went (board SummaryGame), from [readings] over [start] to [end] in [READ_BLOCK_S] blocks:
     * "You faded a little after 70 min" when every block from one on sits [FADE_PCT] or more under the first half's
     * average, "You finished stronger than you started" when the last block is that far above it, else "You kept your
     * effort up to the end". Null for games under half an hour.
     */
    fun gameRead(readings: List<Pair<Long, Int>>, start: Long, end: Long): String? {
        if (end - start < 3 * READ_BLOCK_S) return null
        val firstHalf = WorkoutCopy.averageOver(readings, start, start + (end - start) / 2) ?: return null
        val blocks = generateSequence(start) { it + READ_BLOCK_S }.takeWhile { it + READ_BLOCK_S / 2 < end }
            .map { from -> from to WorkoutCopy.averageOver(readings, from, minOf(from + READ_BLOCK_S, end)) }.toList()
        val low = firstHalf * (100 - FADE_PCT) / 100
        val fadeFrom = blocks.indices.drop(1).firstOrNull { i -> blocks.drop(i).all { (_, avg) -> avg != null && avg <= low } }
        val last = blocks.lastOrNull()?.second
        return when {
            fadeFrom != null -> "You faded a little after ${(blocks[fadeFrom].first - start) / 60} min."
            last != null && last >= firstHalf * (100 + FADE_PCT) / 100 -> "You finished stronger than you started."
            else -> "You kept your effort up to the end."
        }
    }

    /** Each zone's time on the zone strip, to the nearest minute, and a minute for any time under one. */
    fun zoneMinutes(zones: IntArray): List<Int> = zones.map { s -> if (s <= 0) 0 else Math.round(s / 60.0).toInt().coerceAtLeast(1) }

    /**
     * The heart rate as [points] evenly spaced values from [readings] (time, bpm), each the average of its stretch and
     * then of the [window] values round it, so the line draws smooth. Null where a stretch has no reading.
     */
    fun heartLine(readings: List<Pair<Long, Int>>, points: Int, window: Int = 1): List<Float?> {
        if (readings.isEmpty() || points < 2) return List(points.coerceAtLeast(0)) { null }
        val first = readings.first().first
        val span = (readings.last().first - first).coerceAtLeast(1)
        val raw = org.openstrap.app.data.Summaries.bucketMeans(readings, points, { ((it.first - first) * (points - 1) / span).toInt().coerceIn(0, points - 1) }, { it.second.toFloat() })
        val half = window / 2
        return raw.indices.map { i ->
            if (raw[i] == null) null
            else raw.subList((i - half).coerceAtLeast(0), (i + half + 1).coerceAtMost(points)).filterNotNull().average().toFloat()
        }
    }

    /**
     * The line under the week's days with the weekly goal (board SummaryMind): "3 workouts this week, 1 more for your
     * goal." or, once it's met, "4 workouts this week, goal met."
     */
    fun weekGoalLine(count: Int, goal: Int): String {
        val first = "$count ${if (count == 1) "workout" else "workouts"} this week"
        return if (count >= goal) "$first, goal met." else "$first, ${goal - count} more for your goal."
    }

    /** Whether a kcal [target] was reached: at [reachedSeconds], or by the [activeKcal] at the end. */
    fun targetReached(target: String?, reachedSeconds: Long?, activeKcal: Int?): Boolean {
        val k = org.openstrap.app.data.WorkoutTarget.parse(target) as? org.openstrap.app.data.WorkoutTarget.Kcal ?: return false
        return reachedSeconds != null || (activeKcal != null && activeKcal >= k.kcal)
    }

    /** "Mostly zone 3": the zone with the most time, or null with none. */
    fun mostlyZone(zones: IntArray): String? = zones.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.let { "Mostly zone ${it.index + 1}" }

    /** Another workout of the same sport counts as like this one within this share of its active time. */
    const val SIMILAR_SHARE = 0.25

    /**
     * "4 bpm lower than Monday, over a similar time": [a]'s average heart rate against the last workout of the same
     * sport in [others] whose active time is within [SIMILAR_SHARE] of it. Within 2 bpm, "about the same as Monday".
     * Null without one.
     */
    fun versusLast(a: WorkoutAnalysis, others: List<WorkoutAnalysis>, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        val mine = a.workout.averageHeartRate ?: return null
        val active = a.activeSeconds.takeIf { it > 0 } ?: return null
        val last = others.filter {
            it.workout.start < a.workout.start && it.workout.sportType == a.workout.sportType && it.workout.averageHeartRate != null &&
                kotlin.math.abs(it.activeSeconds - active) <= active * SIMILAR_SHARE
        }.maxByOrNull { it.workout.start } ?: return null
        val day = Instant.ofEpochSecond(last.workout.start).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE", locale))
        val diff = mine - last.workout.averageHeartRate!!
        return when {
            diff <= -3 -> "${-diff} bpm lower than $day, over a similar time."
            diff >= 3 -> "$diff bpm higher than $day, over a similar time."
            else -> "About the same heart rate as $day, over a similar time."
        }
    }

    /** The days of the week of [today] (from [firstDay]) and whether each had one of [starts], in order. */
    fun weekDays(starts: List<Long>, today: LocalDate, firstDay: DayOfWeek, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, Boolean>> {
        val first = today.minusDays(((today.dayOfWeek.value - firstDay.value + 7) % 7).toLong())
        val days = starts.map { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }.toSet()
        return (0L until 7L).map { first.plusDays(it).let { d -> d to (d in days) } }
    }

    /** "Load, hard" style label for the game hero's figure: Load and how it compares with your usual. */
    fun loadLabel(load: Int, usual: Float?): String = "Load" + loadWord(load, usual)

    /** The game's share card line, the number in: "Load 120, hard", "Load 80". */
    fun loadLine(load: Int, usual: Float?): String = "Load $load" + loadWord(load, usual)

    /** How [load] compares with your [usual], as the Load labels end: ", hard", ", light", or nothing. */
    private fun loadWord(load: Int, usual: Float?): String = when (org.openstrap.app.ui.dashboard.TodayCopy.loadStatus(load.toFloat(), usual, endOfDay = true)) {
        org.openstrap.app.ui.dashboard.TodayCopy.LoadStatus.Above -> ", hard"
        org.openstrap.app.ui.dashboard.TodayCopy.LoadStatus.Below -> ", light"
        else -> ""
    }

    /** Whether [a] is a run whose distance came from GPS, the only one whose pace means something. */
    fun gpsDistance(meters: Float?, fromSteps: Boolean): Float? = meters?.takeIf { !fromSteps && it > 0f }

    /** Seconds per km from [meters] in [seconds]; null under 100 m. */
    fun pace(meters: Float?, seconds: Long): Double? = meters?.takeIf { it >= 100f }?.let { seconds / (it / 1000.0) }

    /** The family a summary takes for [sportType] with its [structure]: rounds make any sport's summary an intervals one. */
    fun familyOf(sportType: Int, structure: org.openstrap.app.data.Structure?): Workouts.Family = when (structure) {
        is org.openstrap.app.data.Structure.Periods -> Workouts.Family.Games
        null -> Workouts.family(sportType)
        else -> Workouts.Family.Intervals
    }
}
