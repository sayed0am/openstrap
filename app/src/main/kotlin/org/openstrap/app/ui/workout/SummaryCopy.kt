package org.openstrap.app.ui.workout

import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.LoadBand
import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.TodayCopy
import org.openstrap.app.presentation.sportNoun
import org.openstrap.app.presentation.WorkoutCopy

/*
 * What the summary right after a workout says (spec 6.3, "Right after"): the heart rate drop as it happens and how it
 * compares with yours, Load against your usual, the splits, and a personal best as one quiet line. Pure, so it's tested
 * without a workout ending. The text is in res/values/strings_workout_summary.xml.
 */

/** The hero right after a workout: still [watching] the first minute, or the [drop] once it's done, and its line. */
data class CalmDownLine(val watching: Boolean, val drop: Int?, val text: String)

/**
 * A kilometre on the Splits list: "Km 3" in "5:42", with its average heart rate when there were readings. [seconds] and
 * [kind] are what the list's marks read, so a change to the words can't move them.
 */
data class SplitRow(val name: String, val time: String, val averageBpm: Int?, val seconds: Long, val kind: Kind) {
    /** A whole kilometre, or the part one at the end. */
    enum class Kind { Whole, Last }
}

object SummaryCopy {
    /** The drop is read over the first minute after the end. */
    const val DROP_S = 60L

    /** The 5-second median at the minute needs readings this far past it. */
    private const val MEDIAN_HALF_S = 2L

    /** A drop within this many bpm of your usual is usual. */
    const val DROP_BAND = 4

    /** Your usual drop is the median of this many recent ones, once there are [USUAL_DROP_MIN]. */
    const val USUAL_DROP_OF = 10
    const val USUAL_DROP_MIN = 3

    /** A drop only says something about fitness after a real effort: ending at this share of max or more. */
    private const val MIN_EFFORT_PCT = 65

    /** The (i) on the calm down hero. */
    val DROP_MEANING: String get() = Strings.app.get(R.string.workout_summary_drop_meaning)

    val WATCHING: String get() = Strings.app.get(R.string.workout_summary_watching)
    val NO_DROP_DATA: String get() = Strings.app.get(R.string.workout_summary_no_drop_data)

    /**
     * The hero's line from every reading around the end ([readings], time and bpm: the workout's, then what came
     * after). While [live] readings still arrive and the first minute after [end] isn't over at [now], it says so;
     * then the drop over that minute ([Workouts.heartRateDrop]) against your [usualDrop].
     */
    fun calmDown(
        readings: List<Pair<Long, Int>>, end: Long, now: Long, live: Boolean, maxHr: Int, usualDrop: Int?, strings: Strings = Strings.app,
    ): CalmDownLine {
        if (live && now <= end + DROP_S + MEDIAN_HALF_S) {
            return CalmDownLine(watching = true, drop = null, text = strings.get(R.string.workout_summary_watching))
        }
        val drop = Workouts.heartRateDrop(readings, end, DROP_S)
        return CalmDownLine(false, drop, dropLine(drop, Workouts.heartRateAt(readings, end), maxHr, usualDrop, strings))
    }

    /**
     * "Down 34 in the first minute. A quick drop for you." Compared with your [usual] only after a real effort (ending
     * at [MIN_EFFORT_PCT] of [maxHr] or more, from [endBpm]); without a usual, the plain drop.
     */
    fun dropLine(drop: Int?, endBpm: Int?, maxHr: Int, usual: Int?, strings: Strings = Strings.app): String {
        if (drop == null || endBpm == null) return strings.get(R.string.workout_summary_no_drop_data)
        if (drop <= 0) return strings.get(R.string.workout_summary_no_drop)
        val id = when {
            endBpm * 100 < maxHr * MIN_EFFORT_PCT -> R.string.workout_summary_drop_easy_finish
            usual == null -> R.string.workout_summary_drop
            drop >= usual + DROP_BAND -> R.string.workout_summary_drop_quick
            drop <= usual - DROP_BAND -> R.string.workout_summary_drop_slow
            else -> R.string.workout_summary_drop_usual
        }
        return strings.format(id, drop)
    }

    /** Your usual drop: the median of the latest [USUAL_DROP_OF] of [drops] (newest first); null with fewer than [USUAL_DROP_MIN]. */
    fun usualDrop(drops: List<Int>): Int? {
        val recent = drops.take(USUAL_DROP_OF).sorted()
        if (recent.size < USUAL_DROP_MIN) return null
        val mid = recent.size / 2
        return if (recent.size % 2 == 1) recent[mid] else Math.round((recent[mid - 1] + recent[mid]) / 2.0).toInt()
    }

    /** The Load card's line: against your [usual] workout Load ([Workouts.usualLoad]), with the band Today uses. */
    fun loadLine(load: Int, usual: Float?, strings: Strings = Strings.app): String =
        when (TodayCopy.loadStatus(load.toFloat(), usual, endOfDay = true)) {
        LoadBand.Above -> strings.format(R.string.workout_summary_load_above, Math.round(usual!!))
        LoadBand.Below -> strings.format(R.string.workout_summary_load_below, Math.round(usual!!))
        LoadBand.Usual, LoadBand.SoFar -> strings.get(R.string.workout_summary_load_usual)
        null -> strings.get(R.string.workout_summary_load_learning)
    }

    /** The Load card's note, "From this run", or "this workout" where no short noun reads well. */
    fun loadTitle(sportType: Int, strings: Strings = Strings.app): String =
        sportNoun(sportType)?.let { strings.format(R.string.workout_summary_load_title, it) }
            ?: strings.get(R.string.workout_summary_load_title_workout)

    /**
     * The Load card's recovery line, "Time to recover 14 h"; null without one. When what earlier workouts left runs an
     * hour or more past this one's [own], it says both, so a short walk after a long one doesn't read as costing the
     * long one's hours: "Time to recover 40 min from this walk, 16 h in all with what's left from earlier."
     */
    fun timeToRecover(minutes: Int?, own: Int? = null, sportType: Int? = null, strings: Strings = Strings.app): String? {
        val total = recoverSpan(minutes, strings) ?: return null
        return strings.format(R.string.workout_summary_time_to_recover, carriedOver(minutes, own, sportType, strings) ?: total)
    }

    /**
     * "40 min from this walk, 16 h in all with what's left from earlier", or "nothing from this walk, 16 h left from
     * earlier"; null when the leftover doesn't run an hour or more past [own], and the total alone says it.
     */
    fun carriedOver(minutes: Int?, own: Int?, sportType: Int?, strings: Strings = Strings.app): String? {
        if (minutes == null || own == null || minutes - own < CARRIED_SHOWN_MIN) return null
        val total = recoverSpan(minutes, strings) ?: return null
        val noun = sportType?.let(::sportNoun)
        val mine = recoverSpan(own, strings)
        return when {
            mine != null && noun != null -> strings.format(R.string.workout_summary_carried_over, mine, noun, total)
            mine != null -> strings.format(R.string.workout_summary_carried_over_workout, mine, total)
            noun != null -> strings.format(R.string.workout_summary_carried_over_nothing, noun, total)
            else -> strings.format(R.string.workout_summary_carried_over_nothing_workout, total)
        }
    }

    /** What earlier workouts left must run this far past a workout's own recovery before the line names both. */
    private const val CARRIED_SHOWN_MIN = 60

    /** A time to recover on its own: "14 h", "40 min"; null without one. */
    fun recoverSpan(minutes: Int?, strings: Strings = Strings.app): String? {
        val m = minutes?.takeIf { it > 0 } ?: return null
        return if (m < 90) {
            strings.format(R.string.workout_summary_minutes, m)
        } else {
            strings.format(R.string.workout_summary_hours, Math.round(m / 60.0))
        }
    }

    /**
     * The workout card's line: distance (≈ from steps), the average, and paused time past a minute: "5.21 km, average
     * 142 bpm. Paused 4 min." Null when there's none of them.
     */
    fun workoutLine(
        meters: Float?, fromSteps: Boolean, averageBpm: Int?, pausedSeconds: Long, locale: Locale = Locale.getDefault(),
        strings: Strings = Strings.app,
    ): String? {
        val distance = meters?.let { Units.distance(it, Units.Precision.Workout, fromSteps, locale) }
        val average = averageBpm?.takeIf { it > 0 }
        val first = when {
            distance != null && average != null -> strings.format(R.string.workout_summary_line_distance_average, distance, average)
            distance != null -> strings.format(R.string.workout_summary_line_distance, distance)
            average != null -> strings.format(R.string.workout_summary_line_average, average)
            else -> null
        }
        val paused = (pausedSeconds / 60).takeIf { it >= 1 }?.let { strings.format(R.string.workout_summary_line_paused, it) }
        return when {
            first != null && paused != null -> strings.format(R.string.workout_sentences, first, paused)
            else -> first ?: paused
        }
    }

    /**
     * The workout card's kcal target ([target], a [WorkoutTarget.code]): "Target 400 kcal, reached at 38:10" from
     * [reachedSeconds], or "Target not reached, 212 of 400 kcal" from [activeKcal]. A workout that got there without a
     * time (a gap fill brought it over) is just "reached". Null for no target or another kind.
     */
    fun targetLine(
        target: String?, reachedSeconds: Long?, activeKcal: Int?, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): String? {
        val k = WorkoutTarget.parse(target) as? WorkoutTarget.Kcal ?: return null
        val goal = Units.count(k.kcal, locale)
        return when {
            reachedSeconds != null ->
                strings.format(R.string.workout_summary_target_reached_at, goal, Units.workoutClock(reachedSeconds, locale))
            activeKcal != null && activeKcal >= k.kcal -> strings.format(R.string.workout_summary_target_reached, goal)
            activeKcal != null -> strings.format(R.string.workout_summary_target_short, Units.count(activeKcal, locale), goal)
            else -> strings.format(R.string.workout_summary_target_not_reached, goal)
        }
    }

    /** A lap's line on the list: "1.02 km, average 150 bpm"; null with neither. */
    fun lapLine(meters: Double?, averageBpm: Int?, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String? {
        val distance = meters?.let { Units.distance(it.toFloat(), Units.Precision.Workout, locale = locale) }
        return when {
            distance != null && averageBpm != null -> strings.format(R.string.workout_summary_lap_distance_average, distance, averageBpm)
            distance != null -> distance
            averageBpm != null -> strings.format(R.string.workout_summary_lap_average, averageBpm)
            else -> null
        }
    }

    /** Under the trim sheet's title: the times the workout was recorded, which the handles can reach back to. */
    fun trimRecorded(from: String, to: String, strings: Strings = Strings.app): String =
        strings.format(R.string.workout_summary_trim_recorded, Ranges.of(from, to))

    /** How to use the trim sheet's bar, under it. */
    val TRIM_HINT: String get() = Strings.app.get(R.string.workout_summary_trim_hint)

    /** The trim sheet's header: "18:02 to 18:40, 38:12". */
    fun trimHeader(from: String, to: String, seconds: Long): String = "${Ranges.of(from, to)}, ${Units.workoutClock(seconds)}"

    /** The last part's distance, down to "0 m" where the smoothed total leaves next to nothing. */
    private fun lastPart(meters: Float, locale: Locale, strings: Strings): String =
        Units.distance(meters, Units.Precision.Workout, locale = locale) ?: strings.format(R.string.workout_summary_meters, meters.toInt())

    /** A last stretch shorter than this isn't worth a row. */
    private const val PARTIAL_MIN_M = 100.0

    /**
     * The Splits list: each whole kilometre ("Km 3", "5:42") with the average of [readings] (time, bpm) over it, then the
     * last part ("Last 430 m") when there's enough of it. A kilometre starts where the one before ended, or at [firstT].
     * With the workout's [totalMeters], the last part is what's left of it after the whole kilometres, so the list adds
     * up to the distance shown above it (the raw route runs a little longer than the smoothed distance).
     */
    fun splitRows(
        splits: List<Route.Split>, readings: List<Pair<Long, Int>>, firstT: Long?, totalMeters: Float? = null,
        locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): List<SplitRow> =
        splits.mapIndexedNotNull { i, sp ->
            val whole = sp.meters >= 1000.0
            if (!whole && (i != splits.lastIndex || sp.meters < PARTIAL_MIN_M)) return@mapIndexedNotNull null
            val from = splits.getOrNull(i - 1)?.endT ?: firstT ?: sp.endT
            val last = totalMeters?.let { it - 1000f * i }?.takeIf { it > 0f } ?: sp.meters.toFloat()
            SplitRow(
                if (whole) {
                    strings.format(R.string.workout_summary_split_km, i + 1)
                } else {
                    strings.format(R.string.workout_summary_split_last, lastPart(last, locale, strings))
                },
                Units.workoutClock(sp.seconds, locale), WorkoutCopy.averageOver(readings, from, sp.endT), sp.seconds,
                if (whole) SplitRow.Kind.Whole else SplitRow.Kind.Last,
            )
        }
}
