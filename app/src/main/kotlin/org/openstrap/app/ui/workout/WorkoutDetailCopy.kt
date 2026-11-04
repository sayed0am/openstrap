package org.openstrap.app.ui.workout

import androidx.annotation.StringRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.LoadBands
import org.openstrap.app.presentation.Units
import org.openstrap.app.ui.dashboard.Info
import org.openstrap.app.presentation.sportNoun

/*
 * What the page of a past workout says (spec 6.3, "Workout detail"): the sentence first ("Hard run, mostly in zone
 * 4."), the facts, the heart rate drop, and training effect and EPOC explained behind the (i). Pure, so it's tested
 * without a workout. The text is in res/values/strings_workout_detail.xml.
 */

/**
 * A small fact on the workout's list: [name], [value] in dot-matrix digits, and the [status] under the name. [kind] is
 * what its colour and icon go by, so a change to the words can't change them.
 */
data class WorkoutFact(val name: String, val value: String, val status: String?, val kind: Kind) {
    enum class Kind { Distance, Paused, Steps, Calories, Target }
}

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
    enum class Effort(@StringRes private val id: Int) {
        Easy(R.string.workout_detail_effort_easy), Steady(R.string.workout_detail_effort_steady),
        Hard(R.string.workout_detail_effort_hard), VeryHard(R.string.workout_detail_effort_very_hard),
        ;

        val word: String get() = Strings.app.get(id)
    }

    /**
     * How hard a workout was from its [load] against your [usual] workout Load ([Workouts.usualLoad]). The bands match
     * the Load card's: under three quarters of your usual is Easy, within a quarter of it Steady, above that Hard, and
     * from 1.75 times it Very hard. Without a usual the fixed scale behind [Workouts.effort] is used. Null without Load.
     */
    fun effort(load: Int?, usual: Float?): Effort? {
        if (load == null || load <= 0) return null
        val e = Workouts.effort(load, usual)
        return when {
            e < 0.5f * (1 - LoadBands.BAND) -> Effort.Easy
            e <= 0.5f * (1 + LoadBands.BAND) -> Effort.Steady
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
    fun sentence(
        sportType: Int, activeSeconds: Long, load: Int?, usual: Float?, zoneSeconds: List<Int>, strings: Strings = Strings.app,
    ): String {
        val sport = sport(sportType)
        // Each effort word is its own sentence, so a language can place and inflect it to suit the sport.
        val ids = when {
            activeSeconds < SHORT_S -> R.string.workout_detail_sentence_short to R.string.workout_detail_sentence_short_zone
            else -> when (effort(load, usual)) {
                Effort.Easy -> R.string.workout_detail_sentence_easy to R.string.workout_detail_sentence_easy_zone
                Effort.Steady -> R.string.workout_detail_sentence_steady to R.string.workout_detail_sentence_steady_zone
                Effort.Hard -> R.string.workout_detail_sentence_hard to R.string.workout_detail_sentence_hard_zone
                Effort.VeryHard -> R.string.workout_detail_sentence_very_hard to R.string.workout_detail_sentence_very_hard_zone
                null -> if (sportNoun(sportType) != null) {
                    R.string.workout_detail_sentence_a to R.string.workout_detail_sentence_a_zone
                } else {
                    R.string.workout_detail_sentence_sport to R.string.workout_detail_sentence_sport_zone
                }
            }
        }
        val shown = if (ids.first == R.string.workout_detail_sentence_sport) sport.replaceFirstChar { it.uppercase() } else sport
        return mostlyZone(zoneSeconds)?.let { strings.format(ids.second, shown, it + 1) } ?: strings.format(ids.first, shown)
    }

    /** The hero's day ([DayWords.relative]): "Today", "Yesterday", "Tuesday", "Tue 21 Oct", "Tue 21 Oct 2025". */
    fun day(start: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DayWords.relative(Instant.ofEpochSecond(start).atZone(zone).toLocalDate(), today, locale)

    /**
     * The workout's small facts, each left out when there's nothing to say: distance (from GPS, or estimated from
     * steps), time paused past [MIN_PAUSED_S], steps and calories. Calories lead with [activeKcal] and say the total
     * [calories] under it (calories spec 4.2); without an active figure of its own the total stands alone, in kcal.
     */
    fun facts(
        meters: Float?, fromSteps: Boolean, pausedSeconds: Long, steps: Int?, calories: Int?, activeKcal: Int? = null,
        locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): List<WorkoutFact> =
        listOfNotNull(
            meters?.takeIf { it >= Units.SHOWN_FROM_METERS }?.let { m ->
                val km = m >= Units.METERS_PER_KM
                val unit = when {
                    km && fromSteps -> R.string.workout_detail_fact_km_from_steps
                    km -> R.string.workout_detail_fact_km
                    fromSteps -> R.string.workout_detail_fact_m_from_steps
                    else -> R.string.workout_detail_fact_m
                }
                // The value is drawn in the dot font, so it keeps the dot and ASCII digits.
                val value = if (km) Units.km(m, Units.Precision.Workout, Units.DOT_LOCALE) else m.toInt().toString()
                WorkoutFact(strings.get(R.string.workout_detail_fact_distance), value, strings.get(unit), WorkoutFact.Kind.Distance)
            },
            pausedSeconds.takeIf { it >= MIN_PAUSED_S }?.let {
                WorkoutFact(
                    strings.get(R.string.workout_detail_fact_paused), Units.workoutClock(it, Units.DOT_LOCALE),
                    strings.get(R.string.workout_detail_fact_paused_note), WorkoutFact.Kind.Paused,
                )
            },
            steps?.takeIf { it > 0 }?.let {
                WorkoutFact(strings.get(R.string.workout_detail_fact_steps), Units.count(it, locale), null, WorkoutFact.Kind.Steps)
            },
            calories?.takeIf { it > 0 }?.let { total ->
                val name = strings.get(R.string.workout_detail_fact_calories)
                if (activeKcal != null && activeKcal != total) {
                    WorkoutFact(
                        name, Units.count(activeKcal, locale),
                        strings.format(R.string.workout_detail_fact_calories_total, Units.count(total, locale)), WorkoutFact.Kind.Calories,
                    )
                } else {
                    WorkoutFact(name, Units.count(total, locale), strings.get(R.string.workout_detail_fact_kcal), WorkoutFact.Kind.Calories)
                }
            },
        )

    /**
     * A kcal target as a fact, after Calories: "300 kcal" with "Reached at 24:40" ([reachedSeconds]), "Reached" when it
     * got there without a time, or "Not reached, 212 of 300" from [activeKcal]. Null for no target or another kind.
     */
    fun targetFact(
        target: String?, reachedSeconds: Long?, activeKcal: Int?, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): WorkoutFact? {
        val k = WorkoutTarget.parse(target) as? WorkoutTarget.Kcal ?: return null
        val status = when {
            reachedSeconds != null -> strings.format(R.string.workout_detail_target_reached_at, Units.workoutClock(reachedSeconds, locale))
            activeKcal != null && activeKcal >= k.kcal -> strings.get(R.string.workout_detail_target_reached)
            activeKcal != null ->
                strings.format(R.string.workout_detail_target_short, Units.count(activeKcal, locale), Units.count(k.kcal, locale))
            else -> strings.get(R.string.workout_detail_target_not_reached)
        }
        return WorkoutFact(
            strings.get(R.string.workout_detail_fact_target),
            strings.format(R.string.workout_detail_target_kcal, Units.count(k.kcal, locale)), status, WorkoutFact.Kind.Target,
        )
    }

    /**
     * The Load card's heart rate drop line: "Heart rate drop 34 bpm in the first minute, quick for you." Only after a
     * real effort ([meaningful]); against your [usual] drop when there is one ([SummaryCopy.usualDrop]). Null otherwise.
     */
    fun dropLine(drop: Int?, meaningful: Boolean, usual: Int?, strings: Strings = Strings.app): String? {
        if (drop == null || drop <= 0 || !meaningful) return null
        val id = when {
            usual == null -> R.string.workout_detail_drop
            drop >= usual + SummaryCopy.DROP_BAND -> R.string.workout_detail_drop_quick
            drop <= usual - SummaryCopy.DROP_BAND -> R.string.workout_detail_drop_slow
            else -> R.string.workout_detail_drop_usual
        }
        return strings.format(id, drop)
    }

    /** The 0 to 5 training effect, low to high, in plain words. */
    val EFFECT_SCALE: List<String> get() = effectScale(Strings.app)

    private fun effectScale(strings: Strings): List<String> = listOf(
        R.string.workout_detail_scale_light, R.string.workout_detail_scale_keeps, R.string.workout_detail_scale_builds,
        R.string.workout_detail_scale_builds_a_lot, R.string.workout_detail_scale_overreach,
    ).map(strings::get)

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
    private fun strapLine(strap: List<EffectFigures>, strings: Strings): String? {
        fun figures(s: EffectFigures) = listOfNotNull(
            s.aerobic?.let { strings.format(R.string.workout_detail_figure_aerobic, one(it)) },
            s.anaerobic?.let { strings.format(R.string.workout_detail_figure_anaerobic, one(it)) },
            s.load?.let { strings.format(R.string.workout_detail_figure_load, it) },
            SummaryCopy.recoverSpan(s.recoveryMinutes, strings)?.let { strings.format(R.string.workout_detail_figure_recover, it) },
        ).joinToString(", ")
        val each = strap.map { it to figures(it) }.filter { it.second.isNotEmpty() }
        return when {
            each.isEmpty() -> null
            strap.size == 1 -> strings.format(R.string.workout_detail_strap_one, each.single().second)
            else -> (
                listOf(strings.plural(R.plurals.workout_detail_strap_many, strap.size, strap.size)) +
                    each.map { (s, f) ->
                        s.from?.let { strings.format(R.string.workout_detail_strap_from, it, f) }
                            ?: strings.format(R.string.workout_detail_strap_from_start, f)
                    }
                ).reduce { a, b -> sentences(a, b, strings) }
        }
    }

    /** Two sentences one after the other, as the language writes them. */
    private fun sentences(first: String, second: String, strings: Strings): String =
        strings.format(R.string.workout_sentences, first, second)

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
        ownRecoveryMinutes: Int? = null, sportType: Int? = null, strings: Strings = Strings.app,
    ): EffectSheet {
        val rows = listOfNotNull(
            load?.let { l ->
                strings.get(R.string.workout_detail_row_load) to (
                    usualLoad?.let { strings.format(R.string.workout_detail_load_text_usual, l, Math.round(it)) }
                        ?: strings.format(R.string.workout_detail_load_text, l)
                    )
            },
            if (aerobic != null || anaerobic != null) {
                val figures = listOfNotNull(
                    aerobic?.let { strings.format(R.string.workout_detail_figure_aerobic, one(it)) },
                    anaerobic?.let { strings.format(R.string.workout_detail_figure_anaerobic, one(it)) },
                ).joinToString(", ")
                strings.get(R.string.workout_detail_row_effect) to strings.format(R.string.workout_detail_effect_text, figures)
            } else null,
            SummaryCopy.recoverSpan(recoveryMinutes, strings)?.let {
                strings.get(R.string.workout_detail_row_recover) to strings.format(
                    R.string.workout_detail_recover_text,
                    SummaryCopy.carriedOver(recoveryMinutes, ownRecoveryMinutes, sportType, strings) ?: it,
                )
            },
            drop60?.let { d ->
                val value = when {
                    !meaningfulDrop -> strings.format(R.string.workout_detail_drop_not_rated, endBpm ?: d)
                    drop120 != null -> strings.format(R.string.workout_detail_drop_value_two, d, drop120)
                    else -> strings.format(R.string.workout_detail_drop_value, d)
                }
                val meaning = strings.get(R.string.workout_summary_drop_meaning)
                strings.get(R.string.workout_detail_row_drop) to sentences(meaning, value, strings)
            },
            vo2max?.let {
                strings.get(R.string.workout_detail_row_vo2max) to strings.format(R.string.workout_detail_vo2max_text, Math.round(it))
            },
            strings.get(R.string.workout_detail_row_where) to strings.get(
                when {
                    effectFromStrap -> R.string.workout_detail_where_strap_too
                    fromPhone -> R.string.workout_detail_where_phone
                    else -> R.string.workout_detail_where_strap
                },
            ).let { where -> (if (effectFromStrap) null else strapLine(strap, strings))?.let { sentences(where, it, strings) } ?: where },
        )
        val info = Info(
            name = strings.get(R.string.workout_detail_info_name),
            meaning = strings.get(R.string.workout_detail_info_meaning),
            scale = if (aerobic != null) effectScale(strings) else emptyList(),
            how = "", moves = "", off = "",
        )
        return EffectSheet(
            info, aerobic?.let(::effectBand),
            aerobic?.let { strings.format(R.string.workout_detail_here, one(it)) },
            rows,
        )
    }
}
