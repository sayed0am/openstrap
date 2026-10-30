package org.openstrap.app.ui.workout

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.dashboard.Info
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.sportNoun
import org.openstrap.app.ui.glow.km
import org.openstrap.app.ui.glow.workoutClock
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/*
 * What the page of a past workout says (spec 6.3, "Workout detail"): the sentence first ("Hard run, mostly in zone
 * 4."), the facts, the heart rate drop, and training effect and EPOC explained behind the (i). Pure, so it's tested
 * without a workout.
 */

/** A small fact on the workout's list: [name], [value] in dot-matrix digits, and the [status] under the name. */
data class WorkoutFact(val name: String, val value: String, val status: String?)

/** What a workout's other figures look like, for the strap's own record of a phone workout; [from] its start, "22:53". */
data class EffectFigures(val aerobic: Float?, val anaerobic: Float?, val load: Int?, val recoveryMinutes: Int?, val from: String? = null)

/** The (i) on the Load card: the sheet's words, the bar of the 0 to 5 scale this workout is in, and its folded rows. */
data class EffectSheet(val info: Info, val here: Int?, val hereLine: String?, val rows: List<Pair<String, String>>)

object WorkoutDetailCopy {
    /** Shorter than this and a workout is "Short", whatever its Load says. */
    const val SHORT_S = 5 * 60L

    /** Less time in the zones than this says nothing about where the workout sat. */
    const val MIN_ZONE_S = 60

    /** A paused stretch shorter than this isn't worth a row. */
    const val MIN_PAUSED_S = 60L

    /** The effort word, read from [Workouts.effort]: half is your usual Load, full twice it or more. */
    enum class Effort(val word: String) { Easy(tr(R.string.common_easy)), Steady(tr(R.string.common_steady)), Hard(tr(R.string.workout_detail_copy_hard)), VeryHard(tr(R.string.workout_detail_copy_very_hard)) }

    /**
     * How hard a workout was from its [load] against your [usual] workout Load ([Workouts.usualLoad]). The bands match
     * the Load card's: under three quarters of your usual is Easy, within a quarter of it Steady, above that Hard, and
     * from 1.75 times it Very hard. Without a usual the fixed scale behind [Workouts.effort] is used. Null without Load.
     */
    fun effort(load: Int?, usual: Float?): Effort? {
        if (load == null || load <= 0) return null
        val e = Workouts.effort(load, usual)
        return when {
            e < 0.5f * (1 - TodayCopy.LOAD_BAND) -> Effort.Easy
            e <= 0.5f * (1 + TodayCopy.LOAD_BAND) -> Effort.Steady
            e < 0.875f -> Effort.Hard
            else -> Effort.VeryHard
        }
    }

    /** The sport as it reads mid-sentence: "run", "strength training", "HIIT". */
    fun sport(sportType: Int): String = sportNoun(sportType) ?: Workouts.sportName(sportType).let { name ->
        val first = name.substringBefore(' ')
        if (first.length > 1 && first.all { it.isUpperCase() }) name else name.replaceFirstChar { it.lowercase() }
    }

    /** The zone (0 for zone 1) with the most of [zoneSeconds]; null with less than [MIN_ZONE_S] in all of them. */
    fun mostlyZone(zoneSeconds: List<Int>): Int? {
        if (zoneSeconds.sum() < MIN_ZONE_S) return null
        return zoneSeconds.indices.maxByOrNull { zoneSeconds[it] }
    }

    /**
     * The hero's sentence: "Hard run, mostly in zone 4." The effort word from [effort], "Short" under [SHORT_S] of
     * [activeSeconds], and without Load just the sport ("A run", "Strength training"). The zone part goes when there's
     * too little zone time.
     */
    fun sentence(sportType: Int, activeSeconds: Long, load: Int?, usual: Float?, zoneSeconds: List<Int>): String {
        val sport = sport(sportType)
        val word = if (activeSeconds < SHORT_S) tr(R.string.workout_detail_copy_short) else effort(load, usual)?.word
        val head = when {
            word != null -> "$word $sport"
            sportNoun(sportType) != null -> tr(R.string.missed_a_sport, sport)
            else -> sport.replaceFirstChar { it.uppercase() }
        }
        return head + (mostlyZone(zoneSeconds)?.let { tr(R.string.workout_detail_copy_mostly_in_zone, it + 1) } ?: "") + "."
    }

    /** The hero's day: "Today", "Yesterday", "Tue 21 Oct", with the year when it isn't [today]'s. */
    fun day(start: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val d = Instant.ofEpochSecond(start).atZone(zone).toLocalDate()
        return when {
            d == today -> tr(R.string.common_today)
            d == today.minusDays(1) -> tr(R.string.common_yesterday)
            d.year == today.year -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
            else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale))
        }
    }

    /**
     * The workout's small facts, each left out when there's nothing to say: distance (from GPS, or estimated from
     * steps), time paused past [MIN_PAUSED_S], steps and calories. Calories lead with [activeKcal] and say the total
     * [calories] under it (calories spec 4.2); without an active figure of its own the total stands alone, in kcal.
     */
    fun facts(
        meters: Float?, fromSteps: Boolean, pausedSeconds: Long, steps: Int?, calories: Int?, activeKcal: Int? = null,
        locale: Locale = Locale.getDefault(),
    ): List<WorkoutFact> =
        listOfNotNull(
            meters?.takeIf { it >= 10f }?.let { m ->
                val unit = if (m >= 1000) tr(R.string.workout_detail_copy_kilometres) else tr(R.string.workout_detail_copy_metres)
                WorkoutFact(tr(R.string.common_distance), if (m >= 1000) km(m) else m.toInt().toString(), if (fromSteps) tr(R.string.workout_detail_copy_estimated_from_your_steps, unit) else unit)
            },
            pausedSeconds.takeIf { it >= MIN_PAUSED_S }?.let { WorkoutFact(tr(R.string.common_paused), workoutClock(it), tr(R.string.workout_detail_copy_not_counted_in_the)) },
            steps?.takeIf { it > 0 }?.let { WorkoutFact(tr(R.string.common_steps), "%,d".format(locale, it), null) },
            calories?.takeIf { it > 0 }?.let { total ->
                if (activeKcal != null && activeKcal != total) WorkoutFact(tr(R.string.common_calories), "%,d".format(locale, activeKcal), trRaw(R.string.workout_detail_copy_total).format(locale, total))
                else WorkoutFact(tr(R.string.common_calories), "%,d".format(locale, total), "kcal")
            },
        )

    /**
     * A kcal target as a fact, after Calories: "300 kcal" with "Reached at 24:40" ([reachedSeconds]), "Reached" when it
     * got there without a time, or "Not reached, 212 of 300" from [activeKcal]. Null for no target or another kind.
     */
    fun targetFact(target: String?, reachedSeconds: Long?, activeKcal: Int?, locale: Locale = Locale.getDefault()): WorkoutFact? {
        val k = WorkoutTarget.parse(target) as? WorkoutTarget.Kcal ?: return null
        val status = when {
            reachedSeconds != null -> tr(R.string.workout_detail_copy_reached_at, workoutClock(reachedSeconds))
            activeKcal != null && activeKcal >= k.kcal -> tr(R.string.workout_detail_copy_reached)
            activeKcal != null -> trRaw(R.string.workout_detail_copy_not_reached_of).format(locale, activeKcal, k.kcal)
            else -> tr(R.string.workout_detail_copy_not_reached)
        }
        return WorkoutFact("Target", trRaw(R.string.summary_copy_kcal).format(locale, k.kcal), status)
    }

    /**
     * The Load card's heart rate drop line: "Heart rate drop 34 bpm in the first minute, quick for you." Only after a
     * real effort ([meaningful]); against your [usual] drop when there is one ([SummaryCopy.usualDrop]). Null otherwise.
     */
    fun dropLine(drop: Int?, meaningful: Boolean, usual: Int?): String? {
        if (drop == null || drop <= 0 || !meaningful) return null
        val head = tr(R.string.workout_detail_copy_heart_rate_drop_bpm, drop)
        return when {
            usual == null -> "$head."
            drop >= usual + SummaryCopy.DROP_BAND -> tr(R.string.workout_detail_copy_quick_for_you, head)
            drop <= usual - SummaryCopy.DROP_BAND -> tr(R.string.workout_detail_copy_slow_for_you, head)
            else -> tr(R.string.workout_detail_copy_about_usual_for_you, head)
        }
    }

    /** The 0 to 5 training effect, low to high, in plain words. */
    val EFFECT_SCALE = listOf(tr(R.string.common_light), tr(R.string.workout_detail_copy_keeps), tr(R.string.workout_detail_copy_builds), tr(R.string.workout_detail_copy_builds_a_lot), tr(R.string.workout_detail_copy_overreach))

    /** Which bar of [EFFECT_SCALE] a training effect is in. */
    fun effectBand(te: Float): Int = when {
        te < 2f -> 0
        te < 3f -> 1
        te < 4f -> 2
        te < 5f -> 3
        else -> 4
    }

    private fun one(x: Float) = String.format(Locale.ROOT, "%.1f", x)

    /**
     * The strap's own records of a phone workout, in the sheet: one's figures, or each of several by its start, so a
     * long workout that held two shows both. Empty when there are none, or none with figures.
     */
    private fun strapLine(strap: List<EffectFigures>): String {
        fun figures(s: EffectFigures) = listOfNotNull(
            s.aerobic?.let { "aerobic ${one(it)}" }, s.anaerobic?.let { "anaerobic ${one(it)}" }, s.load?.let { tr(R.string.workout_detail_copy_load, it) },
            SummaryCopy.recoverSpan(s.recoveryMinutes)?.let { tr(R.string.workout_detail_copy_time_to_recover, it) },
        ).joinToString(", ")
        val each = strap.map { it to figures(it) }.filter { it.second.isNotEmpty() }
        return when {
            each.isEmpty() -> ""
            strap.size == 1 -> " " + tr(R.string.workout_detail_copy_the_strap_recorded_this_2, each.single().second)
            else -> " " + tr(R.string.workout_detail_copy_the_strap_recorded_it, strap.size) + " " +
                each.joinToString(" ") { (s, f) -> tr(R.string.workout_detail_copy_from, s.from ?: tr(R.string.workout_detail_copy_its_start), f) }
        }
    }

    /**
     * The (i) on the Load card: Load (EPOC), training effect on its 0 to 5 scales, time to recover, the heart rate drop
     * and VO2 max, each explained plainly with this workout's value and left out when it has none. [fromPhone] says who
     * worked them out, and [effectFromStrap] that a phone workout's are the strap's own; [ownRecoveryMinutes] is a phone
     * workout's recovery before what earlier ones left ([SummaryCopy.carriedOver]); [strap] are the strap's own
     * records of a phone workout, by start, when it kept any and its figures aren't already the ones shown.
     */
    fun effectSheet(
        aerobic: Float?, anaerobic: Float?, load: Int?, usualLoad: Float?, recoveryMinutes: Int?,
        drop60: Int?, drop120: Int?, endBpm: Int?, meaningfulDrop: Boolean, vo2max: Float?,
        fromPhone: Boolean, strap: List<EffectFigures>, effectFromStrap: Boolean = false,
        ownRecoveryMinutes: Int? = null, sportType: Int? = null,
    ): EffectSheet {
        val rows = listOfNotNull(
            load?.let { l ->
                val vs = usualLoad?.let { tr(R.string.workout_detail_copy_your_usual_is, Math.round(it)) } ?: ""
                tr(R.string.common_load) to tr(R.string.workout_detail_copy_load_on_a_workout, l, vs)
            },
            if (aerobic != null || anaerobic != null) {
                tr(R.string.workout_detail_copy_training_effect) to tr(R.string.workout_detail_copy_two_scales_from_to) +
                    listOfNotNull(aerobic?.let { tr(R.string.missed_aerobic, one(it)) }, anaerobic?.let { tr(R.string.missed_anaerobic, one(it)) }).joinToString(", ") + "."
            } else null,
            SummaryCopy.recoverSpan(recoveryMinutes)?.let {
                tr(R.string.common_time_to_recover) to tr(R.string.workout_detail_copy_how_long_until_your_2) + " " +
                    tr(R.string.workout_detail_copy_and_how_tired_youve) + " " +
                    (SummaryCopy.carriedOver(recoveryMinutes, ownRecoveryMinutes, sportType) ?: it) + "."
            },
            drop60?.let { d ->
                val value = if (meaningfulDrop) {
                    tr(R.string.workout_detail_copy_this_workout_down_in, d) + (drop120?.let { tr(R.string.workout_detail_copy_in_two, it) } ?: "") + "."
                } else {
                    tr(R.string.workout_detail_copy_you_finished_at_bpm, endBpm ?: d)
                }
                tr(R.string.common_heart_rate_drop) to tr(R.string.workout_detail_copy_how_far_your_heart, value)
            },
            vo2max?.let { tr(R.string.common_vo_max) to tr(R.string.workout_detail_copy_the_most_oxygen_your, Math.round(it)) },
            tr(R.string.workout_detail_copy_where_these_come_from) to (
                when {
                    effectFromStrap -> tr(R.string.workout_detail_copy_from_the_strap_which)
                    fromPhone -> tr(R.string.workout_detail_copy_worked_out_by_openstrap)
                    else -> tr(R.string.workout_detail_copy_from_the_strap_worked)
                }
                ) + if (effectFromStrap) "" else strapLine(strap),
        )
        val info = Info(
            name = tr(R.string.workout_detail_copy_load_and_training_effect),
            meaning = tr(R.string.workout_detail_copy_what_this_workout_asked),
            scale = if (aerobic != null) EFFECT_SCALE else emptyList(),
            how = "", moves = "", off = "",
        )
        return EffectSheet(
            info, aerobic?.let(::effectBand),
            aerobic?.let { tr(R.string.workout_detail_copy_you_are_here_aerobic, one(it)) },
            rows,
        )
    }
}
