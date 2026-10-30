package org.openstrap.app.ui.workout

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.openstrap.app.data.Rewards
import org.openstrap.app.data.db.BadgeRow
import org.openstrap.app.data.db.PersonalBest
import org.openstrap.app.data.db.WeekResult
import org.openstrap.app.ui.glow.workoutClock

/*
 * What the weekly goal, bests and badges say (workouts spec 3): the Earned rows on a summary, the best card, the goal
 * moment, the weekly recap and the Goals and bests page. Pure, so it's tested without a workout ending.
 */

/** What a just ended workout earned and what follows the summary. */
data class SummaryRewards(
    val earned: List<EarnedLine>,
    /** The best for the full screen card, the one that matters most; null without a new best. */
    val best: Rewards.NewBest?,
    /** This workout met the weekly goal: the goal moment may follow. */
    val goalMet: Boolean,
    val week: Rewards.WeekLine,
    val inARow: Int,
    /** The first day of this workout's week, epoch day. */
    val weekStart: Long,
)

object RewardsCopy {
    /** "Fastest 5 km", "Longest run", "Heaviest bench press", "Most steps in a day". */
    fun bestTitle(kind: String, locale: Locale = Locale.getDefault()): String {
        Rewards.Kind.fastestMeters(kind)?.let { return "Fastest " + distanceName(it) }
        Rewards.Kind.liftName(kind)?.let { return "Heaviest " + it.lowercase(locale) }
        return when (kind) {
            Rewards.Kind.LONGEST_RUN -> "Longest run"
            Rewards.Kind.LONGEST_WORKOUT -> "Longest workout"
            Rewards.Kind.MOST_STEPS -> "Most steps in a day"
            else -> "Personal best"
        }
    }

    /** "1 km", "5 km", "half marathon". */
    fun distanceName(meters: Int): String = if (meters == 21097) "half marathon" else "${meters / 1000} km"

    /** The badge art's short text for a best: "5K", "HM", "KG", "RUN". */
    fun bestShort(kind: String): String {
        Rewards.Kind.fastestMeters(kind)?.let { return if (it == 21097) "HM" else "${it / 1000}K" }
        if (Rewards.Kind.liftName(kind) != null) return "KG"
        return when (kind) {
            Rewards.Kind.LONGEST_RUN -> "RUN"
            Rewards.Kind.MOST_STEPS -> "STEP"
            else -> "TIME"
        }
    }

    /** A best's value: "26:41", "14.2 km", "1:32:10", "23,410", "65 kg". */
    fun bestValue(kind: String, value: Double): String = when {
        Rewards.Kind.fastestMeters(kind) != null -> workoutClock(Math.round(value))
        Rewards.Kind.liftName(kind) != null -> org.openstrap.app.data.Strength.kgText(value.toFloat()) + " kg"
        kind == Rewards.Kind.LONGEST_RUN -> String.format(Locale.ROOT, "%.1f km", value / 1000)
        kind == Rewards.Kind.LONGEST_WORKOUT -> workoutClock(Math.round(value))
        else -> "%,d".format(Math.round(value))
    }

    /** "22 s faster", "2.5 kg more", "1.2 km farther", "4 min longer": how far [b] beat [before]. */
    fun beatBy(b: PersonalBest, before: PersonalBest): String {
        val diff = kotlin.math.abs(b.value - before.value)
        return when {
            Rewards.Kind.fastestMeters(b.kind) != null -> Math.round(diff).let { if (it < 60) "$it s" else workoutClock(it) } + " faster"
            Rewards.Kind.liftName(b.kind) != null -> org.openstrap.app.data.Strength.kgText(diff.toFloat()) + " kg more"
            b.kind == Rewards.Kind.LONGEST_RUN -> String.format(Locale.ROOT, "%.1f km", diff / 1000) + " farther"
            b.kind == Rewards.Kind.LONGEST_WORKOUT -> "${Math.round(diff / 60).coerceAtLeast(1)} min longer"
            else -> "%,d".format(Math.round(diff)) + " more"
        }
    }

    /** "22 s faster than your best from August." ("August 2025" in another year than [now]). */
    fun beatLine(n: Rewards.NewBest, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val d = Instant.ofEpochSecond(n.before.date).atZone(zone)
        val pattern = if (d.year == Instant.ofEpochSecond(now).atZone(zone).year) "MMMM" else "MMMM yyyy"
        return "${beatBy(n.best, n.before).replaceFirstChar { it.uppercase() }} than your best from ${d.format(DateTimeFormatter.ofPattern(pattern, locale))}."
    }

    /** Which new best leads: the fastest stretches first, then the longest run, a lift, the longest workout. */
    fun leading(bests: List<Rewards.NewBest>): Rewards.NewBest? = bests.minByOrNull { n ->
        val k = n.best.kind
        when {
            Rewards.Kind.fastestMeters(k) != null -> 0
            k == Rewards.Kind.LONGEST_RUN -> 1
            Rewards.Kind.liftName(k) != null -> 2
            else -> 3
        }
    }

    /**
     * The weekly goal row, always last under Earned (workouts spec 2.8): "Weekly goal met" on the workout that met it,
     * else "Weekly goal", with "3 of 4" and what's left or the weeks in a row.
     */
    fun goalLine(week: Rewards.WeekLine, inARow: Int): EarnedLine {
        val note = when {
            week.metByThis && inARow > 1 -> "$inARow weeks in a row"
            week.count >= week.goal -> "Goal met this week"
            else -> (week.goal - week.count).let { "$it more to go this week" }
        }
        return EarnedLine(EarnedLine.Kind.Goal, if (week.metByThis) "Weekly goal met" else "Weekly goal", "${week.count} of ${week.goal}", note, met = week.count >= week.goal)
    }

    /** Everything a just ended [w] earned, from all [facts] and the badges already [stored]. */
    fun summary(
        w: Rewards.Done, workouts: List<Rewards.Done>, days: List<Rewards.Day>, kept: List<WeekResult>, stored: List<BadgeRow>,
        goal: Int, firstDay: DayOfWeek, zone: ZoneId, now: Long,
    ): SummaryRewards {
        val all = workouts.filter { it.start != w.start } + w
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val weeks = Rewards.weeks(all, kept, goal, today, firstDay, zone)
        val bests = Rewards.newBests(w, all, days, zone)
        val badges = Rewards.newBadges(w, all, days, weeks, stored, firstDay, zone)
        val week = Rewards.weekLine(w, all, goal, firstDay, zone)
        val weekStart = Rewards.weekStart(Instant.ofEpochSecond(w.shownStart).atZone(zone).toLocalDate(), firstDay)
        val inARow = Rewards.inARow(weeks, weekStart)
        val earned = bests.map { n ->
            EarnedLine(EarnedLine.Kind.Best, bestTitle(n.best.kind), bestValue(n.best.kind, n.best.value), "New best, ${beatBy(n.best, n.before)}")
        } + badges.map { EarnedLine(EarnedLine.Kind.Badge, it.title, null, "New badge") } + goalLine(week, inARow)
        return SummaryRewards(earned, leading(bests), week.metByThis, week, inARow, weekStart.toEpochDay())
    }

    /** The goal moment's line: "6 weeks in a row. Rest days never count against you." */
    fun momentLine(inARow: Int): String =
        (if (inARow > 1) "$inARow weeks in a row. " else "Your first week in a row. ") + "Rest days never count against you."

    /** "Thursday, 30 October 2026, morning run": when a best was set, and what. */
    fun whenSet(date: Long, sportName: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val t = Instant.ofEpochSecond(date).atZone(zone)
        val part = when (t.hour) { in 4..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }
        return t.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)) + ", $part ${sportName.lowercase(locale)}"
    }

    /** "27 October to 2 November": a week from its first day. */
    fun weekRange(first: LocalDate, locale: Locale = Locale.getDefault()): String {
        val f = DateTimeFormatter.ofPattern("d MMMM", locale)
        return "${first.format(f)} to ${first.plusDays(6).format(f)}"
    }

    /** "Goal met 6 weeks in a row. Best is 9.", or without a run, what the best was. */
    fun runLine(inARow: Int, best: Int): String = when {
        inARow > 0 && best > inARow -> "Goal met $inARow ${weeks(inARow)} in a row. Best is $best."
        inARow > 0 -> "Goal met $inARow ${weeks(inARow)} in a row, your best yet."
        best > 0 -> "Your best is $best ${weeks(best)} in a row."
        else -> "Meet your goal to start a run of weeks."
    }

    private fun weeks(n: Int) = if (n == 1) "week" else "weeks"

    /** A locked badge's need: "712 of 1,000 km", "3 of 10 workouts". */
    fun progressLine(have: Long, need: Long, unit: String): String = when (unit) {
        "m" -> String.format(Locale.ROOT, "%.1f of %d km", have / 1000.0, need / 1000)
        else -> "%,d of %,d %s".format(have, need, unit)
    }

    /** The recap notification: "4 of 4 workouts, goal met. 6 weeks in a row." */
    fun recapText(week: WeekResult, inARow: Int): String =
        "${week.count} of ${week.goal} workouts" + if (week.met) ", goal met." + (if (inARow > 1) " $inARow weeks in a row." else "") else "."
}

/** What the workout from [start] earned ([RewardsCopy.summary]), from [workouts] and what's stored; null before it's listed. */
suspend fun org.openstrap.app.data.RewardsKeeper.summary(start: Long, workouts: List<org.openstrap.app.data.WorkoutAnalysis>, goal: Int): SummaryRewards? {
    val f = facts(workouts)
    val w = f.workouts.firstOrNull { it.start == start } ?: return null
    val (kept, badges) = stored()
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        RewardsCopy.summary(w, f.workouts, f.days, kept, badges, goal, f.firstDay, f.zone, System.currentTimeMillis() / 1000)
    }
}

/** What the Goals and bests page and the weekly recap show, worked out from everything ([RewardsKeeper.facts]). */
data class RewardsView(
    val goal: Int,
    /** Every week against its goal, this one last. */
    val weeks: List<WeekResult>,
    val thisWeek: LocalDate,
    val firstDay: DayOfWeek,
    val inARow: Int,
    val bestRun: Int,
    val bests: List<PersonalBest>,
    val earned: List<BadgeRow>,
    /** How far toward each locked badge with a count. */
    val progress: Map<String, Pair<Long, Long>>,
    /** Workouts that count, for the week's days. */
    val counted: List<Rewards.Done>,
    val all: List<Rewards.Done>,
) {
    fun week(first: LocalDate): WeekResult? = weeks.firstOrNull { it.weekStart == first.toEpochDay() }

    /** The locked badge closest to done, for "next". */
    val next: Rewards.Badge? get() = Rewards.BADGES.filter { b -> earned.none { it.id == b.id } && progress[b.id] != null }
        .maxByOrNull { b -> progress[b.id]!!.let { (have, need) -> have.toDouble() / need } }
}

/** The Goals and bests page's view, against the weekly [goal]. */
suspend fun org.openstrap.app.data.RewardsKeeper.view(workouts: List<org.openstrap.app.data.WorkoutAnalysis>, goal: Int, bests: List<PersonalBest>, earned: List<BadgeRow>): RewardsView {
    val f = facts(workouts)
    val (kept, _) = stored()
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val today = LocalDate.now(f.zone)
        val thisWeek = Rewards.weekStart(today, f.firstDay)
        val weeks = Rewards.weeks(f.workouts, kept, goal, today, f.firstDay, f.zone)
        val progress = Rewards.BADGES.mapNotNull { b -> Rewards.progress(b, f.workouts, f.days, weeks, thisWeek)?.let { b.id to it } }.toMap()
        RewardsView(
            goal, weeks, thisWeek, f.firstDay, Rewards.inARow(weeks, thisWeek), Rewards.bestRun(weeks), bests, earned, progress,
            Rewards.counted(f.workouts, f.firstDay, f.zone).map { it.second }, f.workouts,
        )
    }
}
