package org.openstrap.app.ui.workout

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.sportNoun
import org.openstrap.app.ui.glow.km
import org.openstrap.app.ui.glow.workoutClock
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/*
 * What the summary right after a workout says (spec 6.3, "Right after"): the heart rate drop as it happens and how it
 * compares with yours, Load against your usual, the splits, and a personal best as one quiet line. Pure, so it's tested
 * without a workout ending.
 */

/** The hero right after a workout: still [watching] the first minute, or the [drop] once it's done, and its line. */
data class CalmDownLine(val watching: Boolean, val drop: Int?, val text: String)

/** A kilometre on the Splits list: "Km 3" in "5:42", with its average heart rate when there were readings. */
data class SplitRow(val name: String, val time: String, val averageBpm: Int?)

/** A workout as the personal best check sees it: its sport, active [seconds], and [meters] from GPS only. */
data class BestCandidate(val start: Long, val sportType: Int, val seconds: Long, val meters: Float?)

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

    /** The page title right after a workout, by the sport's short name: "Run saved" (spec 6.3). */
    fun savedTitle(sportType: Int?): String = sportType?.let { tr(R.string.missed_sport_saved, Workouts.shortName(it)) } ?: tr(R.string.common_workout_saved)

    /** The (i) on the calm down hero. */
    val DROP_MEANING: String get() = tr(R.string.summary_copy_how_far_your_heart)

    val WATCHING: String get() = tr(R.string.summary_copy_watching_your_heart_rate)
    val NO_DROP_DATA: String get() = tr(R.string.summary_copy_no_heart_rate_after)

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
        if (drop <= 0) return tr(R.string.summary_copy_no_drop_in_the)
        val head = tr(R.string.summary_copy_down_in_the_first, drop)
        if (endBpm * 100 < maxHr * MIN_EFFORT_PCT) return tr(R.string.summary_copy_from_an_easy_finish, head)
        return when {
            usual == null -> "$head."
            drop >= usual + DROP_BAND -> tr(R.string.summary_copy_a_quick_drop_for, head)
            drop <= usual - DROP_BAND -> tr(R.string.summary_copy_a_slow_drop_for, head)
            else -> tr(R.string.summary_copy_about_usual_for_you, head)
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
        TodayCopy.LoadStatus.Above -> tr(R.string.summary_copy_above_your_usual_of, Math.round(usual!!))
        TodayCopy.LoadStatus.Below -> tr(R.string.summary_copy_below_your_usual_of, Math.round(usual!!))
        TodayCopy.LoadStatus.Usual, TodayCopy.LoadStatus.SoFar -> tr(R.string.summary_copy_about_your_usual)
        null -> tr(R.string.common_learning_your_usual)
    }

    /** The Load card's note, "From this run", or "this workout" where no short noun reads well. */
    fun loadTitle(sportType: Int): String = tr(R.string.summary_copy_from_this, sportNoun(sportType) ?: tr(R.string.summary_copy_from_this__workout))

    /**
     * The Load card's recovery line, "Time to recover 14 h"; null without one. When what earlier workouts left runs an
     * hour or more past this one's [own], it says both, so a short walk after a long one doesn't read as costing the
     * long one's hours: "Time to recover 40 min from this walk, 16 h in all with what's left from earlier."
     */
    fun timeToRecover(minutes: Int?, own: Int? = null, sportType: Int? = null): String? {
        val total = recoverSpan(minutes) ?: return null
        return carriedOver(minutes, own, sportType)?.let { tr(R.string.summary_copy_time_to_recover, it) } ?: tr(R.string.summary_copy_time_to_recover, total)
    }

    /**
     * "40 min from this walk, 16 h in all with what's left from earlier", or "nothing from this walk, 16 h left from
     * earlier"; null when the leftover doesn't run an hour or more past [own], and the total alone says it.
     */
    fun carriedOver(minutes: Int?, own: Int?, sportType: Int?): String? {
        if (minutes == null || own == null || minutes - own < CARRIED_SHOWN_MIN) return null
        val total = recoverSpan(minutes) ?: return null
        val noun = "this ${sportType?.let(::sportNoun) ?: tr(R.string.missed_unit_workout)}"
        return recoverSpan(own)?.let { tr(R.string.summary_copy_from_in_all_with, it, noun, total) }
            ?: tr(R.string.summary_copy_nothing_from_left_from, noun, total)
    }

    /** What earlier workouts left must run this far past a workout's own recovery before the line names both. */
    private const val CARRIED_SHOWN_MIN = 60

    /** A time to recover on its own: "14 h", "40 min"; null without one. */
    fun recoverSpan(minutes: Int?): String? {
        val m = minutes?.takeIf { it > 0 } ?: return null
        return if (m < 90) tr(R.string.missed_duration_min, m) else tr(R.string.missed_duration_h, Math.round(m / 60.0))
    }

    /**
     * The workout card's line: distance (≈ from steps), the average, and paused time past a minute: "5.21 km, average
     * 142 bpm. Paused 4 min." Null when there's none of them.
     */
    fun workoutLine(meters: Float?, fromSteps: Boolean, averageBpm: Int?, pausedSeconds: Long): String? {
        val parts = listOfNotNull(
            meters?.takeIf { it >= 10f }?.let { (if (fromSteps) "≈ " else "") + km(it) + if (it >= 1000) " km" else "" },
            averageBpm?.takeIf { it > 0 }?.let { tr(R.string.summary_copy_average_bpm, it) },
        ).joinToString(", ").replaceFirstChar { it.uppercase() }
        val paused = (pausedSeconds / 60).takeIf { it >= 1 }?.let { tr(R.string.summary_copy_paused_min, it) }
        return listOfNotNull(parts.takeIf { it.isNotEmpty() }?.let { "$it." }, paused).joinToString(" ").ifEmpty { null }
    }

    /** The workout card's calories, active first (calories spec 4.2): "486 active kcal, total 612"; null without them. */
    fun kcalLine(active: Int?, total: Int?, locale: java.util.Locale = java.util.Locale.getDefault()): String? = when {
        total == null || total <= 0 -> null
        active == null || active == total -> trRaw(R.string.summary_copy_kcal).format(locale, total)
        else -> trRaw(R.string.summary_copy_active_kcal_total).format(locale, active, total)
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
            reachedSeconds != null -> tr(R.string.summary_copy_target_kcal_reached_at, goal, workoutClock(reachedSeconds))
            activeKcal != null && activeKcal >= k.kcal -> tr(R.string.summary_copy_target_kcal_reached, goal)
            activeKcal != null -> trRaw(R.string.summary_copy_target_not_reached_of).format(locale, activeKcal, goal)
            else -> tr(R.string.summary_copy_target_kcal_not_reached, goal)
        }
    }

    /** A lap's line on the list: "1.02 km, average 150 bpm"; null with neither. */
    fun lapLine(meters: Double?, averageBpm: Int?): String? = listOfNotNull(
        meters?.let { km(it.toFloat()) + if (it >= 1000) " km" else "" },
        averageBpm?.let { tr(R.string.summary_copy_average_bpm, it) },
    ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { null }

    /** The trim panel's header: "18:02–18:40, 38:12". */
    fun trimHeader(from: String, to: String, seconds: Long): String = "$from–$to, ${workoutClock(seconds)}"

    /** A last stretch shorter than this isn't worth a row. */
    private const val PARTIAL_MIN_M = 100.0

    /**
     * The Splits list: each whole kilometre ("Km 3", "5:42") with the average of [readings] (time, bpm) over it, then the
     * last part ("Last 430 m") when there's enough of it. A kilometre starts where the one before ended, or at [firstT].
     * With the workout's [totalMeters], the last part is what's left of it after the whole kilometres, so the list adds
     * up to the distance shown above it (the raw route runs a little longer than the smoothed distance).
     */
    fun splitRows(splits: List<Route.Split>, readings: List<Pair<Long, Int>>, firstT: Long?, totalMeters: Float? = null): List<SplitRow> =
        splits.mapIndexedNotNull { i, sp ->
            val whole = sp.meters >= 1000.0
            if (!whole && (i != splits.lastIndex || sp.meters < PARTIAL_MIN_M)) return@mapIndexedNotNull null
            val from = splits.getOrNull(i - 1)?.endT ?: firstT ?: sp.endT
            val last = totalMeters?.let { it - 1000f * i }?.takeIf { it > 0f } ?: sp.meters.toFloat()
            SplitRow(if (whole) tr(R.string.summary_copy_km, i + 1) else tr(R.string.summary_copy_last, km(last)), workoutClock(sp.seconds), WorkoutCopy.averageOver(readings, from, sp.endT))
        }

    /** A best counts once the last better one is at least this long ago. */
    const val BEST_GAP_S = 30 * 86_400L

    /** "Yet" only once there are this many of the sport before. */
    const val BEST_MIN_BEFORE = 5

    /** "Since March" only when it beats at least this many of the sport since then. */
    const val BEST_MIN_BEATEN = 3

    /** Shorter than these and a best means little. */
    private const val BEST_MIN_M = 1000f
    private const val BEST_MIN_S = 10 * 60L

    /**
     * A personal best as one line, from what's stored: the farthest of the sport by GPS ("Farthest run since March."),
     * else the longest by active time ("Longest walk since March."). It counts when the last better one of [others] is
     * [BEST_GAP_S] or more before with [BEST_MIN_BEATEN] or more of the sport since, or there is none among
     * [BEST_MIN_BEFORE] or more ("Your farthest run yet."). Null otherwise. TODO: the spec's "Fastest 5 km since March" needs the fastest stretch of every stored route of the
     * sport, which means reading every route back; nothing caches split times yet.
     */
    fun personalBest(w: BestCandidate, others: List<BestCandidate>, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        val noun = sportNoun(w.sportType) ?: Workouts.sportName(w.sportType).lowercase(locale)
        val before = others.filter { it.sportType == w.sportType && it.start < w.start }
        fun best(word: String, value: (BestCandidate) -> Double?, min: Double): String? {
            val mine = value(w)?.takeIf { it >= min } ?: return null
            val measured = before.filter { value(it) != null }
            val better = measured.filter { value(it)!! >= mine }.maxByOrNull { it.start }
            return when {
                better == null -> tr(R.string.summary_copy_your_yet, word, noun).takeIf { measured.size >= BEST_MIN_BEFORE }
                w.start - better.start >= BEST_GAP_S && measured.count { it.start > better.start } >= BEST_MIN_BEATEN -> tr(R.string.summary_copy_since, word.replaceFirstChar { it.uppercase() }, noun, month(better.start, w.start, zone, locale))
                else -> null
            }
        }
        return best(tr(R.string.missed_farthest), { it.meters?.toDouble() }, BEST_MIN_M.toDouble())
            ?: best(tr(R.string.missed_longest), { it.seconds.toDouble().takeIf { s -> s > 0 } }, BEST_MIN_S.toDouble())
    }

    /** "March", or "March 2025" when [at] is in another year than [now]. */
    private fun month(at: Long, now: Long, zone: ZoneId, locale: Locale): String {
        val d = Instant.ofEpochSecond(at).atZone(zone)
        val pattern = if (d.year == Instant.ofEpochSecond(now).atZone(zone).year) "MMMM" else tr(R.string.summary_copy_mmmm_yyyy)
        return d.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
}
