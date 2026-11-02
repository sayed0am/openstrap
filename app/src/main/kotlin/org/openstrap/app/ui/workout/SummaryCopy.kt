package org.openstrap.app.ui.workout

import java.util.Locale
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.presentation.Units
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.sportNoun

/*
 * What the summary right after a workout says (spec 6.3, "Right after"): the heart rate drop as it happens and how it
 * compares with yours, Load against your usual, the splits, and a personal best as one quiet line. Pure, so it's tested
 * without a workout ending.
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
    const val DROP_MEANING = "How far your heart rate falls in the first minute after you stop. A bigger drop usually " +
        "means a fitter heart, and under 12 bpm is on the low side. It only says something after a hard effort."

    const val WATCHING = "Watching your heart rate come down."
    const val NO_DROP_DATA = "No heart rate after the end, so no drop this time."

    /**
     * The hero's line from every reading around the end ([readings], time and bpm: the workout's, then what came
     * after). While [live] readings still arrive and the first minute after [end] isn't over at [now], it says so;
     * then the drop over that minute ([Workouts.heartRateDrop]) against your [usualDrop].
     */
    fun calmDown(readings: List<Pair<Long, Int>>, end: Long, now: Long, live: Boolean, maxHr: Int, usualDrop: Int?): CalmDownLine {
        if (live && now <= end + DROP_S + MEDIAN_HALF_S) return CalmDownLine(watching = true, drop = null, text = WATCHING)
        val drop = Workouts.heartRateDrop(readings, end, DROP_S)
        return CalmDownLine(false, drop, dropLine(drop, Workouts.heartRateAt(readings, end), maxHr, usualDrop))
    }

    /**
     * "Down 34 in the first minute. A quick drop for you." Compared with your [usual] only after a real effort (ending
     * at [MIN_EFFORT_PCT] of [maxHr] or more, from [endBpm]); without a usual, the plain drop.
     */
    fun dropLine(drop: Int?, endBpm: Int?, maxHr: Int, usual: Int?): String {
        if (drop == null || endBpm == null) return NO_DROP_DATA
        if (drop <= 0) return "No drop in the first minute."
        val head = "Down $drop in the first minute"
        if (endBpm * 100 < maxHr * MIN_EFFORT_PCT) return "$head, from an easy finish."
        return when {
            usual == null -> "$head."
            drop >= usual + DROP_BAND -> "$head. A quick drop for you."
            drop <= usual - DROP_BAND -> "$head. A slow drop for you."
            else -> "$head. About usual for you."
        }
    }

    /** Your usual drop: the median of the latest [USUAL_DROP_OF] of [drops] (newest first); null with fewer than [USUAL_DROP_MIN]. */
    fun usualDrop(drops: List<Int>): Int? {
        val recent = drops.take(USUAL_DROP_OF).sorted()
        if (recent.size < USUAL_DROP_MIN) return null
        val mid = recent.size / 2
        return if (recent.size % 2 == 1) recent[mid] else Math.round((recent[mid - 1] + recent[mid]) / 2.0).toInt()
    }

    /** The Load card's line: against your [usual] workout Load ([Workouts.usualLoad]), with the band Today uses. */
    fun loadLine(load: Int, usual: Float?): String = when (TodayCopy.loadStatus(load.toFloat(), usual, endOfDay = true)) {
        TodayCopy.LoadStatus.Above -> "Above your usual of ${Math.round(usual!!)}"
        TodayCopy.LoadStatus.Below -> "Below your usual of ${Math.round(usual!!)}"
        TodayCopy.LoadStatus.Usual, TodayCopy.LoadStatus.SoFar -> "About your usual"
        null -> "Learning your usual"
    }

    /** The Load card's note, "From this run", or "this workout" where no short noun reads well. */
    fun loadTitle(sportType: Int): String = "From this ${sportNoun(sportType) ?: "workout"}"

    /**
     * The Load card's recovery line, "Time to recover 14 h"; null without one. When what earlier workouts left runs an
     * hour or more past this one's [own], it says both, so a short walk after a long one doesn't read as costing the
     * long one's hours: "Time to recover 40 min from this walk, 16 h in all with what's left from earlier."
     */
    fun timeToRecover(minutes: Int?, own: Int? = null, sportType: Int? = null): String? {
        val total = recoverSpan(minutes) ?: return null
        return carriedOver(minutes, own, sportType)?.let { "Time to recover $it" } ?: "Time to recover $total"
    }

    /**
     * "40 min from this walk, 16 h in all with what's left from earlier", or "nothing from this walk, 16 h left from
     * earlier"; null when the leftover doesn't run an hour or more past [own], and the total alone says it.
     */
    fun carriedOver(minutes: Int?, own: Int?, sportType: Int?): String? {
        if (minutes == null || own == null || minutes - own < CARRIED_SHOWN_MIN) return null
        val total = recoverSpan(minutes) ?: return null
        val noun = "this ${sportType?.let(::sportNoun) ?: "workout"}"
        return recoverSpan(own)?.let { "$it from $noun, $total in all with what's left from earlier" }
            ?: "nothing from $noun, $total left from earlier"
    }

    /** What earlier workouts left must run this far past a workout's own recovery before the line names both. */
    private const val CARRIED_SHOWN_MIN = 60

    /** A time to recover on its own: "14 h", "40 min"; null without one. */
    fun recoverSpan(minutes: Int?): String? {
        val m = minutes?.takeIf { it > 0 } ?: return null
        return if (m < 90) "$m min" else "${Math.round(m / 60.0)} h"
    }

    /**
     * The workout card's line: distance (≈ from steps), the average, and paused time past a minute: "5.21 km, average
     * 142 bpm. Paused 4 min." Null when there's none of them.
     */
    fun workoutLine(
        meters: Float?, fromSteps: Boolean, averageBpm: Int?, pausedSeconds: Long, locale: Locale = Locale.getDefault(),
    ): String? {
        val parts = listOfNotNull(
            meters?.let { Units.distance(it, Units.Precision.Workout, fromSteps, locale) },
            averageBpm?.takeIf { it > 0 }?.let { "average $it bpm" },
        ).joinToString(", ").replaceFirstChar { it.uppercase() }
        val paused = (pausedSeconds / 60).takeIf { it >= 1 }?.let { "Paused $it min." }
        return listOfNotNull(parts.takeIf { it.isNotEmpty() }?.let { "$it." }, paused).joinToString(" ").ifEmpty { null }
    }

    /**
     * The workout card's kcal target ([target], a [WorkoutTarget.code]): "Target 400 kcal, reached at 38:10" from
     * [reachedSeconds], or "Target not reached, 212 of 400 kcal" from [activeKcal]. A workout that got there without a
     * time (a gap fill brought it over) is just "reached". Null for no target or another kind.
     */
    fun targetLine(target: String?, reachedSeconds: Long?, activeKcal: Int?, locale: Locale = Locale.getDefault()): String? {
        val k = WorkoutTarget.parse(target) as? WorkoutTarget.Kcal ?: return null
        val goal = "%,d".format(locale, k.kcal)
        return when {
            reachedSeconds != null -> "Target $goal kcal, reached at ${Units.workoutClock(reachedSeconds, locale)}"
            activeKcal != null && activeKcal >= k.kcal -> "Target $goal kcal, reached"
            activeKcal != null -> "Target not reached, %,d of $goal kcal".format(locale, activeKcal)
            else -> "Target $goal kcal, not reached"
        }
    }

    /** A lap's line on the list: "1.02 km, average 150 bpm"; null with neither. */
    fun lapLine(meters: Double?, averageBpm: Int?, locale: Locale = Locale.getDefault()): String? = listOfNotNull(
        meters?.let { Units.distance(it.toFloat(), Units.Precision.Workout, locale = locale) },
        averageBpm?.let { "average $it bpm" },
    ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { null }

    /** The trim panel's header: "18:02–18:40, 38:12". */
    fun trimHeader(from: String, to: String, seconds: Long): String = "$from–$to, ${Units.workoutClock(seconds)}"

    /** The last part's distance, down to "0 m" where the smoothed total leaves next to nothing. */
    private fun lastPart(meters: Float, locale: Locale): String =
        Units.distance(meters, Units.Precision.Workout, locale = locale) ?: String.format(locale, "%d m", meters.toInt())

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
        locale: Locale = Locale.getDefault(),
    ): List<SplitRow> =
        splits.mapIndexedNotNull { i, sp ->
            val whole = sp.meters >= 1000.0
            if (!whole && (i != splits.lastIndex || sp.meters < PARTIAL_MIN_M)) return@mapIndexedNotNull null
            val from = splits.getOrNull(i - 1)?.endT ?: firstT ?: sp.endT
            val last = totalMeters?.let { it - 1000f * i }?.takeIf { it > 0f } ?: sp.meters.toFloat()
            SplitRow(
                if (whole) "Km ${i + 1}" else "Last " + lastPart(last, locale),
                Units.workoutClock(sp.seconds, locale), WorkoutCopy.averageOver(readings, from, sp.endT), sp.seconds,
                if (whole) SplitRow.Kind.Whole else SplitRow.Kind.Last,
            )
        }
}
