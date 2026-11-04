package org.openstrap.app.presentation

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.data.Rewards
import org.openstrap.app.data.db.BadgeRow
import org.openstrap.app.data.db.PersonalBest
import org.openstrap.app.data.db.WeekResult
import org.openstrap.app.i18n.Strings

/*
 * What the weekly goal, bests and badges say (workouts spec 3): the Earned rows on a summary, the best card, the goal
 * moment, the weekly recap and the Goals and bests page. Pure, so it's tested without a workout ending. Here rather than
 * in ui, so the weekly recap notification reads it without depending on the screens. The text is in
 * res/values/strings_core_rewards.xml.
 */

/** A row under Earned: a new best, a badge, or the weekly goal ([met] once this week's is). */
data class EarnedLine(val kind: Kind, val text: String, val value: String? = null, val note: String? = null, val met: Boolean = false) {
    enum class Kind { Best, Badge, Goal }
}

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
    fun bestTitle(kind: String, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String {
        Rewards.Kind.fastestMeters(kind)?.let { return strings.format(R.string.core_rewards_best_fastest, distanceName(it, strings)) }
        Rewards.Kind.liftName(kind)?.let { return strings.format(R.string.core_rewards_best_heaviest, it.lowercase(locale)) }
        return strings.get(
            when (kind) {
                Rewards.Kind.LONGEST_RUN -> R.string.core_rewards_best_longest_run
                Rewards.Kind.LONGEST_WORKOUT -> R.string.core_rewards_best_longest_workout
                Rewards.Kind.MOST_STEPS -> R.string.core_rewards_best_most_steps
                else -> R.string.core_rewards_best_personal
            },
        )
    }

    /** "1 km", "5 km", "half marathon". */
    fun distanceName(meters: Int, strings: Strings = Strings.app): String =
        if (meters == 21097) strings.get(R.string.core_rewards_half_marathon) else strings.format(R.string.core_rewards_km, meters / 1000)

    /** The badge art's short text for a best, drawn in dots, so ASCII digits always: "5K", "HM", "KG", "RUN". */
    fun bestShort(kind: String): String {
        Rewards.Kind.fastestMeters(kind)?.let { return if (it == 21097) "HM" else dotFormat("%dK", it / 1000) }
        if (Rewards.Kind.liftName(kind) != null) return "KG"
        return when (kind) {
            Rewards.Kind.LONGEST_RUN -> "RUN"
            Rewards.Kind.MOST_STEPS -> "STEP"
            else -> "TIME"
        }
    }

    /** A best's value: "26:41", "14.21 km", "1 h 32", "23,410", "65 kg". The longest run is one workout's distance. */
    fun bestValue(kind: String, value: Double, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String = when {
        Rewards.Kind.fastestMeters(kind) != null -> Units.workoutClock(Math.round(value), locale)
        Rewards.Kind.liftName(kind) != null ->
            strings.format(R.string.core_rewards_kg, org.openstrap.app.data.Strength.kgText(value.toFloat()))
        kind == Rewards.Kind.LONGEST_RUN -> workoutDistance(value, locale, strings)
        kind == Rewards.Kind.LONGEST_WORKOUT -> Units.duration(Math.round(value), locale)
        else -> Units.count(Math.round(value), locale)
    }

    /** A run's distance as a workout shows it, and "0 m" for nothing, where a best has to show a figure. */
    private fun workoutDistance(meters: Double, locale: Locale, strings: Strings): String =
        Units.distance(meters.toFloat(), Units.Precision.Workout, locale = locale)
            ?: strings.format(R.string.core_rewards_meters, String.format(locale, "%d", 0))

    /**
     * What a badge's art reads: its short text, except the first weekly goal, which shows the goal it was met at
     * ([goalAt], "3/3"), or a check when that week isn't known.
     */
    fun face(b: Rewards.Badge, goalAt: Int?): String = if (b.id == "goal_first" && goalAt != null) "$goalAt/$goalAt" else b.short

    /** "22 s faster", "2.5 kg more", "1.2 km farther", "4 min longer": how far [b] beat [before]. */
    fun beatBy(b: PersonalBest, before: PersonalBest, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String {
        val (beat, amount) = beat(b, before, locale, strings)
        return strings.format(beat.short, amount)
    }

    /** "22 s faster than your best from August." ("August 2025" in another year than [now]). */
    fun beatLine(
        n: Rewards.NewBest, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(),
        strings: Strings = Strings.app,
    ): String {
        val d = Instant.ofEpochSecond(n.before.date).atZone(zone)
        val pattern = if (d.year == Instant.ofEpochSecond(now).atZone(zone).year) "MMMM" else "MMMM yyyy"
        val (beat, amount) = beat(n.best, n.before, locale, strings)
        return strings.format(beat.line, amount, d.format(Formatters.of(pattern, locale)))
    }

    /**
     * How [b] beat [before]: which way, with the short words and the sentence for it, and the amount ("22 s", "2.5",
     * "1.23 km", 4 minutes). The amount always starts with a digit, so the sentence needs no capital added.
     */
    private fun beat(b: PersonalBest, before: PersonalBest, locale: Locale, strings: Strings): Pair<Beat, Any> {
        val diff = kotlin.math.abs(b.value - before.value)
        return when {
            Rewards.Kind.fastestMeters(b.kind) != null -> Beat.Faster to Units.secondsOrClock(Math.round(diff), locale)
            Rewards.Kind.liftName(b.kind) != null -> Beat.KgMore to org.openstrap.app.data.Strength.kgText(diff.toFloat())
            b.kind == Rewards.Kind.LONGEST_RUN -> Beat.Farther to workoutDistance(diff, locale, strings)
            b.kind == Rewards.Kind.LONGEST_WORKOUT -> Beat.Longer to Math.round(diff / 60).coerceAtLeast(1)
            else -> Beat.More to Units.count(Math.round(diff), locale)
        }
    }

    /** The ways a best beats the last, each as a note ("22 s faster") and as the best card's sentence. */
    private enum class Beat(val short: Int, val line: Int) {
        Faster(R.string.core_rewards_beat_faster, R.string.core_rewards_beat_line_faster),
        KgMore(R.string.core_rewards_beat_kg_more, R.string.core_rewards_beat_line_kg_more),
        Farther(R.string.core_rewards_beat_farther, R.string.core_rewards_beat_line_farther),
        Longer(R.string.core_rewards_beat_longer, R.string.core_rewards_beat_line_longer),
        More(R.string.core_rewards_beat_more, R.string.core_rewards_beat_line_more),
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
     * The weekly goal row, last under Earned for a workout that counts toward it (workouts spec 2.8): "Weekly goal met" on the workout that met it,
     * else "Weekly goal", with "3 of 4" and what's left or the weeks in a row.
     */
    fun goalLine(week: Rewards.WeekLine, inARow: Int, strings: Strings = Strings.app): EarnedLine {
        val note = when {
            week.metByThis && inARow > 1 -> strings.plural(R.plurals.core_rewards_goal_weeks_in_a_row, inARow, inARow)
            week.count >= week.goal -> strings.get(R.string.core_rewards_goal_met_this_week)
            else -> strings.format(R.string.core_rewards_goal_to_go, week.goal - week.count)
        }
        return EarnedLine(
            EarnedLine.Kind.Goal,
            strings.get(if (week.metByThis) R.string.core_rewards_goal_met else R.string.core_rewards_goal),
            strings.format(R.string.core_rewards_goal_count, week.count, week.goal),
            note,
            met = week.count >= week.goal,
        )
    }

    /** Everything a just ended [w] earned, from all [facts] and the badges already [stored]. */
    fun summary(
        w: Rewards.Done, workouts: List<Rewards.Done>, days: List<Rewards.Day>, kept: List<WeekResult>, stored: List<BadgeRow>,
        goal: Int, firstDay: DayOfWeek, zone: ZoneId, now: Long, strings: Strings = Strings.app,
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
            EarnedLine(
                EarnedLine.Kind.Best,
                bestTitle(n.best.kind, strings = strings),
                bestValue(n.best.kind, n.best.value, strings = strings),
                strings.format(R.string.core_rewards_new_best, beatBy(n.best, n.before, strings = strings)),
            )
        } + badges.map { EarnedLine(EarnedLine.Kind.Badge, it.title, null, strings.get(R.string.core_rewards_new_badge)) } +
            // The goal line only for a workout that counts toward it.
            listOfNotNull(goalLine(week, inARow, strings).takeIf { w.counts })
        return SummaryRewards(earned, leading(bests), week.metByThis, week, inARow, weekStart.toEpochDay())
    }

    /** The goal moment's line: "6 weeks in a row. Rest days never count against you." */
    fun momentLine(inARow: Int, strings: Strings = Strings.app): String =
        if (inARow > 1) strings.plural(R.plurals.core_rewards_moment_weeks, inARow, inARow)
        else strings.get(R.string.core_rewards_moment_first)

    /** "Thursday, 30 October 2026, morning run": when a best was set, and what. */
    fun whenSet(
        date: Long, sportName: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(),
        strings: Strings = Strings.app,
    ): String {
        val t = Instant.ofEpochSecond(date).atZone(zone)
        val part = when (t.hour) {
            in 4..11 -> R.string.core_rewards_when_morning
            in 12..16 -> R.string.core_rewards_when_afternoon
            in 17..21 -> R.string.core_rewards_when_evening
            else -> R.string.core_rewards_when_night
        }
        return strings.format(part, t.format(Formatters.of("EEEE, d MMMM yyyy", locale)), sportName.lowercase(locale))
    }

    /** "27 October to 2 November": a week from its first day. */
    fun weekRange(first: LocalDate, locale: Locale = Locale.getDefault()): String {
        val f = Formatters.of("d MMMM", locale)
        return Ranges.of(first.format(f), first.plusDays(6).format(f))
    }

    /** "Goal met 6 weeks in a row. Best is 9.", or without a run, what the best was. */
    fun runLine(inARow: Int, best: Int, strings: Strings = Strings.app): String = when {
        inARow > 0 && best > inARow ->
            strings.format(R.string.core_rewards_run_best_is, strings.plural(R.plurals.core_rewards_weeks, inARow, inARow), best)
        inARow > 0 -> strings.plural(R.plurals.core_rewards_run_best_yet, inARow, inARow)
        best > 0 -> strings.plural(R.plurals.core_rewards_run_best_was, best, best)
        else -> strings.get(R.string.core_rewards_run_none)
    }

    /**
     * A locked badge's need: "712 of 1,000 km", "3 of 10 workouts", and metres as km over many days: "4.2 of 5 km".
     * [unit] is the badge's own unit word from its definition.
     */
    fun progressLine(have: Long, need: Long, unit: String, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        when (unit) {
            "m" -> strings.format(
                R.string.core_rewards_progress_km,
                Units.km(have.toFloat(), Units.Precision.Day, locale),
                Units.count(need / 1000, locale),
            )
            "km" -> strings.format(R.string.core_rewards_progress_km, Units.count(have, locale), Units.count(need, locale))
            else -> {
                // Plain strings, not plurals: the sentence has two counts, and the badges never need just one.
                val id = when (unit) {
                    "steps" -> R.string.core_rewards_progress_steps
                    "workouts" -> R.string.core_rewards_progress_workouts
                    else -> R.string.core_rewards_progress_weeks
                }
                strings.format(id, Units.count(have, locale), Units.count(need, locale))
            }
        }

    /** The recap notification: "4 of 4 workouts, goal met. 6 weeks in a row." */
    fun recapText(week: WeekResult, inARow: Int, strings: Strings = Strings.app): String = when {
        !week.met -> strings.format(R.string.core_rewards_recap, week.count, week.goal)
        inARow > 1 -> strings.format(R.string.core_rewards_recap_met_in_a_row, week.count, week.goal, inARow)
        else -> strings.format(R.string.core_rewards_recap_met, week.count, week.goal)
    }
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
