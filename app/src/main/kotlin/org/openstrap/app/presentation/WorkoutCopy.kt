package org.openstrap.app.presentation

import org.openstrap.app.R
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.i18n.Strings
import java.util.Locale

/*
 * What the Ready and live workout screens say (spec 6.3), and the small rules behind what they show: the split line's
 * ten seconds, the remembered view, the light that follows the zone. Pure, so it's tested without a workout running.
 */

/** The two views of a GPS workout's live screen, in swipe order. */
enum class LiveView {
    Heart, Map;

    companion object {
        /** The view saved as [code] (its ordinal), Heart when there's none or it's unknown. */
        fun of(code: Int?): LiveView = code?.let { entries.getOrNull(it) } ?: Heart
    }
}

/** A finished kilometre on the live screen: which one ([index] from 0), when it ended, and its line. */
data class SplitLine(val index: Int, val endT: Long, val text: String)

object WorkoutCopy {
    /** How long a split line stays under the stats. */
    const val SPLIT_LINE_S = 10L

    /**
     * At or under this, Ready's line mentions the strap's battery. Earlier on purpose than the low battery everywhere
     * else ([StrapBands.LOW_BATTERY], 15): before a workout, a strap at 20% may not last a long one.
     */
    const val READY_LOW_BATTERY = 20

    private const val KM = 1000.0

    /**
     * Ready's hero, the strap (spec 6.3): its [title], the [line] under it, and whether it's [live], the glow with the
     * heart rate, or an outline card while the strap is away.
     */
    data class ReadyHero(val title: String, val line: String, val live: Boolean)

    /** The GPS row while the phone's location is off or not allowed. Start still works, without GPS. */
    val LOCATION_OFF: String get() = Strings.app.get(R.string.core_workout_location_off)
    val LOCATION_OFF_NOTE: String get() = Strings.app.get(R.string.core_workout_location_off_note)

    /** Under Save a map of my run. */
    fun routeNote(on: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (on) R.string.core_workout_route_on else R.string.core_workout_route_off)

    /** Start, naming the sport: "Start run", "Start HIIT", and "Start workout" for Other. */
    fun startLabel(sportType: Int, strings: Strings = Strings.app): String =
        sportWord(sportType, strings)?.let { strings.format(R.string.core_workout_start_sport, it) }
            ?: strings.get(R.string.core_workout_start_workout)

    /**
     * The sport as Start and Open name it, null for Other: lower case unless the name is all capitals ("run", "HIIT").
     * The lower casing is English's: a language whose names keep their capital can write them so in its short names.
     */
    private fun sportWord(sportType: Int, strings: Strings): String? {
        val name = Workouts.shortName(sportType, strings)
        if (name == strings.get(R.string.core_workouts_short_other)) return null
        return if (name.any { it.isLowerCase() }) name.lowercase() else name
    }

    /** "Set 3 of 5", or "Set 3" with no plan. */
    fun setOf(number: Int, target: Int?, strings: Strings = Strings.app): String =
        target?.let { strings.format(R.string.core_workout_set_of, number, it) } ?: strings.format(R.string.core_workout_set, number)

    /** What ends strength's rest, under its countdown. */
    fun restRule(belowBpm: Int?, strings: Strings = Strings.app): String =
        if (belowBpm == null) {
            strings.get(R.string.core_workout_rest_rule)
        } else {
            strings.format(R.string.core_workout_rest_rule_below, belowBpm)
        }

    /** A set done, as the live screen lists it: "10 × 60 kg, 0:51, peak 148", or without the log "0:51, peak 148". */
    fun setLine(s: org.openstrap.app.data.db.AppWorkoutSet, peak: Int?, strings: Strings = Strings.app): String = listOfNotNull(
        s.reps?.let { r ->
            s.weightKg?.let { strings.format(R.string.core_workout_set_weight, r, org.openstrap.app.data.Strength.kgText(it)) }
                ?: strings.plural(R.plurals.core_workout_set_reps, r, r)
        },
        s.end?.let { Units.workoutClock(it - s.start) },
        peak?.let { strings.format(R.string.core_workout_set_peak, it) },
    ).reduceOrNull { a, b -> strings.format(R.string.core_workout_list, a, b) }.orEmpty()

    /** The Today hero's way back to the live screen, naming the sport: "Open run", "Open workout" for Other. */
    fun openLabel(sportType: Int, strings: Strings = Strings.app): String =
        sportWord(sportType, strings)?.let { strings.format(R.string.core_workout_open_sport, it) }
            ?: strings.get(R.string.core_workout_open_workout)

    /** "Zone 3, aerobic" as its two parts ([WorkoutWords.zoneTitle]). */
    fun zoneTitle(zone: Int, strings: Strings = Strings.app): Pair<String, String> = WorkoutWords.zoneTitle(zone, strings)

    /** [zoneTitle] as one line: "Zone 3, aerobic" ([WorkoutWords.zoneLine]). */
    fun zoneLine(zone: Int, strings: Strings = Strings.app): String = WorkoutWords.zoneLine(zone, strings)

    /** What a live screen says where the heart rate goes and there's none, the same words on every screen and the notification. */
    fun noHeartRate(paused: Boolean, connected: Boolean = true, strings: Strings = Strings.app): String =
        WorkoutWords.noHeartRate(paused, connected, strings)

    /** Where the live zone goes and there's none: none while paused, or none read yet. */
    fun noZone(paused: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (paused) R.string.core_workout_no_zone_paused else R.string.core_workout_no_zone)

    /** The live screen's note while the strap is away ([WorkoutWords.STRAP_GONE_NOTE]). */
    val STRAP_GONE_NOTE: String get() = WorkoutWords.STRAP_GONE_NOTE

    /** "9 min in this zone" ([WorkoutWords.zoneTime]). */
    fun zoneTime(seconds: Int, strings: Strings = Strings.app): String = WorkoutWords.zoneTime(seconds, strings)

    /**
     * The line for the latest whole kilometre in [splits], for [SPLIT_LINE_S] after it ended, else null: "Km 3 in 5:42,
     * avg 152". The average is the readings in [history] (time, bpm) over that kilometre, which starts where the one
     * before ended, or at [firstT] for the first; without readings the line leaves it out.
     */
    fun splitLine(
        splits: List<Route.Split>, history: List<Pair<Long, Int>>, firstT: Long?, now: Long, strings: Strings = Strings.app,
    ): SplitLine? {
        val i = splits.indexOfLast { it.meters >= KM }
        if (i < 0) return null
        val sp = splits[i]
        // A fix stamped a moment ahead of the phone's clock still shows; one long ago doesn't.
        if (now - sp.endT >= SPLIT_LINE_S || sp.endT - now > SPLIT_LINE_S) return null
        val from = splits.getOrNull(i - 1)?.endT ?: firstT ?: sp.endT
        val avg = averageOver(history, from, sp.endT)
        return SplitLine(i, sp.endT, splitText(i, sp.seconds, avg, strings))
    }

    /**
     * The map page's line for the latest whole kilometre, kept until the next: "Km 4 in 5:55, avg 146" and how long
     * ago it ended, "just now" or "3 min ago". Null before the first.
     */
    fun lastSplit(
        splits: List<Route.Split>, history: List<Pair<Long, Int>>, firstT: Long?, now: Long, strings: Strings = Strings.app,
    ): Pair<String, String>? {
        val i = splits.indexOfLast { it.meters >= KM }
        if (i < 0) return null
        val sp = splits[i]
        val from = splits.getOrNull(i - 1)?.endT ?: firstT ?: sp.endT
        val avg = averageOver(history, from, sp.endT)
        val ago = (now - sp.endT).coerceAtLeast(0) / 60
        val agoText = if (ago < 1) strings.get(R.string.core_workout_just_now) else strings.format(R.string.core_workout_min_ago, ago)
        return splitText(i, sp.seconds, avg, strings) to agoText
    }

    /** "Km 3 in 5:42", with ", avg 152" when the kilometre had readings. */
    private fun splitText(index: Int, seconds: Long, avg: Int?, strings: Strings): String {
        val split = WorkoutWords.split(index, seconds, strings = strings)
        return avg?.let { strings.format(R.string.core_workout_split_avg, split, it) } ?: split
    }

    /** The average of the [readings] (time, bpm) from [from] to [to], whole bpm; null without any. */
    fun averageOver(readings: List<Pair<Long, Int>>, from: Long, to: Long): Int? =
        readings.filter { it.first in from..to }.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.toInt()

    /**
     * Every reading so far for [splitLine]: the session's [history], refreshed every few seconds, then the [recent]
     * live readings newer than it, so a kilometre's last seconds count.
     */
    fun readings(history: List<Pair<Long, Int>>, recent: List<Pair<Long, Int>>): List<Pair<Long, Int>> {
        val last = history.lastOrNull()?.first ?: return recent
        return history + recent.filter { it.first > last }
    }

    /** The index of the latest whole kilometre, or -1: the splits already done when the live screen opens. */
    fun wholeKms(splits: List<Route.Split>): Int = splits.indexOfLast { it.meters >= KM }

    /** Whether [line] is a kilometre not ticked for yet ([lastTicked] its index): one tick per split. */
    fun splitTick(line: SplitLine?, lastTicked: Int): Boolean = line != null && line.index > lastTicked

    /** Whether going from zone [previous] to [current] ticks: a change between two readings, not the first one. */
    fun zoneTick(previous: Int?, current: Int?): Boolean = previous != null && current != null && previous != current

    /** The live heart card's progress toward a kcal target: "212 of 300 kcal". */
    fun kcalProgress(activeKcal: Float?, target: WorkoutTarget.Kcal, strings: Strings = Strings.app): String =
        strings.format(R.string.core_workout_progress, (activeKcal?.toInt() ?: 0).toString(), target.label)

    /** GPS on the live screen's top line: "GPS ±4 m", or "Finding GPS" before a fix worth naming. */
    fun gpsPill(accuracy: Float?, strings: Strings = Strings.app): String =
        if (accuracy == null || accuracy > 1000) {
            strings.get(R.string.core_workout_gps_finding)
        } else {
            strings.format(R.string.core_workout_gps, accuracy.toInt())
        }

    /** The same toward a distance goal, in the goal's unit: "2.15 of 5 km", "640 of 800 m". */
    fun distanceProgress(
        meters: Float?, target: WorkoutTarget.Distance, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): String {
        val m = meters ?: 0f
        val done = if (target.meters < 1000) String.format(locale, "%d", m.toInt()) else Units.km(m, Units.Precision.Workout, locale)
        return strings.format(R.string.core_workout_progress, done, target.label)
    }

    /** "312 kcal" for the notification and the capsule ([WorkoutWords.kcalText]); null before there's a figure. */
    fun kcalText(activeKcal: Float?, strings: Strings = Strings.app): String? = WorkoutWords.kcalText(activeKcal, strings)

    /**
     * How high the live heart card's light reaches for [zone] (spec 2.5): about a third of the card in zone 1, all of it
     * in zone 5. Null without a reading, for the card's usual light.
     */
    fun zoneLightLevel(zone: Int?): Float? = zone?.let { 0.35f + 0.65f * it.coerceIn(0, Workouts.ZONES - 1) / (Workouts.ZONES - 1) }
}
