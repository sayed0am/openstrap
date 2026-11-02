package org.openstrap.app.ui.workout

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.Units
import org.openstrap.app.ui.dashboard.Info
import org.openstrap.app.presentation.TodayCopy
import org.openstrap.app.presentation.sportNoun

/*
 * What the page of a past workout says (spec 6.3, "Workout detail"): the sentence first ("Hard run, mostly in zone
 * 4."), the facts, the heart rate drop, and training effect and EPOC explained behind the (i). Pure, so it's tested
 * without a workout.
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
    enum class Effort(val word: String) { Easy("Easy"), Steady("Steady"), Hard("Hard"), VeryHard("Very hard") }

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
        val word = if (activeSeconds < SHORT_S) "Short" else effort(load, usual)?.word
        val head = when {
            word != null -> "$word $sport"
            sportNoun(sportType) != null -> "A $sport"
            else -> sport.replaceFirstChar { it.uppercase() }
        }
        return head + (mostlyZone(zoneSeconds)?.let { ", mostly in zone ${it + 1}" } ?: "") + "."
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
        locale: Locale = Locale.getDefault(),
    ): List<WorkoutFact> =
        listOfNotNull(
            meters?.takeIf { it >= Units.SHOWN_FROM_METERS }?.let { m ->
                val unit = if (m >= Units.METERS_PER_KM) "Kilometres" else "Metres"
                // The value is drawn in the dot font, so it keeps the dot and ASCII digits.
                val value = if (m >= Units.METERS_PER_KM) Units.km(m, Units.Precision.Workout, Units.DOT_LOCALE) else m.toInt().toString()
                WorkoutFact("Distance", value, if (fromSteps) "$unit, estimated from your steps" else unit, WorkoutFact.Kind.Distance)
            },
            pausedSeconds.takeIf { it >= MIN_PAUSED_S }?.let {
                WorkoutFact("Paused", Units.workoutClock(it, Units.DOT_LOCALE), "Not counted in the time", WorkoutFact.Kind.Paused)
            },
            steps?.takeIf { it > 0 }?.let { WorkoutFact("Steps", "%,d".format(locale, it), null, WorkoutFact.Kind.Steps) },
            calories?.takeIf { it > 0 }?.let { total ->
                if (activeKcal != null && activeKcal != total) {
                    WorkoutFact("Calories", "%,d".format(locale, activeKcal), "Total %,d".format(locale, total), WorkoutFact.Kind.Calories)
                } else {
                    WorkoutFact("Calories", "%,d".format(locale, total), "kcal", WorkoutFact.Kind.Calories)
                }
            },
        )

    /**
     * A kcal target as a fact, after Calories: "300 kcal" with "Reached at 24:40" ([reachedSeconds]), "Reached" when it
     * got there without a time, or "Not reached, 212 of 300" from [activeKcal]. Null for no target or another kind.
     */
    fun targetFact(target: String?, reachedSeconds: Long?, activeKcal: Int?, locale: Locale = Locale.getDefault()): WorkoutFact? {
        val k = WorkoutTarget.parse(target) as? WorkoutTarget.Kcal ?: return null
        val status = when {
            reachedSeconds != null -> "Reached at ${Units.workoutClock(reachedSeconds, locale)}"
            activeKcal != null && activeKcal >= k.kcal -> "Reached"
            activeKcal != null -> "Not reached, %,d of %,d".format(locale, activeKcal, k.kcal)
            else -> "Not reached"
        }
        return WorkoutFact("Target", "%,d kcal".format(locale, k.kcal), status, WorkoutFact.Kind.Target)
    }

    /**
     * The Load card's heart rate drop line: "Heart rate drop 34 bpm in the first minute, quick for you." Only after a
     * real effort ([meaningful]); against your [usual] drop when there is one ([SummaryCopy.usualDrop]). Null otherwise.
     */
    fun dropLine(drop: Int?, meaningful: Boolean, usual: Int?): String? {
        if (drop == null || drop <= 0 || !meaningful) return null
        val head = "Heart rate drop $drop bpm in the first minute"
        return when {
            usual == null -> "$head."
            drop >= usual + SummaryCopy.DROP_BAND -> "$head, quick for you."
            drop <= usual - SummaryCopy.DROP_BAND -> "$head, slow for you."
            else -> "$head, about usual for you."
        }
    }

    /** The 0 to 5 training effect, low to high, in plain words. */
    val EFFECT_SCALE = listOf("Light", "Keeps", "Builds", "Builds a lot", "Overreach")

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
            s.aerobic?.let { "aerobic ${one(it)}" }, s.anaerobic?.let { "anaerobic ${one(it)}" }, s.load?.let { "Load $it" },
            SummaryCopy.recoverSpan(s.recoveryMinutes)?.let { "time to recover $it" },
        ).joinToString(", ")
        val each = strap.map { it to figures(it) }.filter { it.second.isNotEmpty() }
        return when {
            each.isEmpty() -> ""
            strap.size == 1 -> " The strap recorded this workout too. Its own figures: ${each.single().second}."
            else -> " The strap recorded it as ${strap.size} workouts of its own. " +
                each.joinToString(" ") { (s, f) -> "From ${s.from ?: "its start"}: $f." }
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
                val vs = usualLoad?.let { " Your usual is ${Math.round(it)}." } ?: ""
                "Load" to "Load on a workout is EPOC: the extra oxygen your body uses afterwards to recover, in ml per kg of " +
                    "body weight. The harder and longer the workout, the higher it goes. It isn't the day's Load, which " +
                    "counts every minute on another scale. This workout: $l.$vs"
            },
            if (aerobic != null || anaerobic != null) {
                "Training effect" to "Two scales from 0 to 5. Aerobic is what the workout did for your endurance, anaerobic " +
                    "for short, hard efforts. Under 2 trains little, 2 to 3 keeps what you have, 3 to 4 builds on it, 4 to 5 " +
                    "builds a lot, and 5 is more than your body can use right away. This workout: " +
                    listOfNotNull(aerobic?.let { "aerobic ${one(it)}" }, anaerobic?.let { "anaerobic ${one(it)}" }).joinToString(", ") + "."
            } else null,
            SummaryCopy.recoverSpan(recoveryMinutes)?.let {
                "Time to recover" to "How long until your body is ready for another hard workout, from the training effect " +
                    "and how tired you've been lately. It never drops below what earlier workouts left. This workout: " +
                    (SummaryCopy.carriedOver(recoveryMinutes, ownRecoveryMinutes, sportType) ?: it) + "."
            },
            drop60?.let { d ->
                val value = if (meaningfulDrop) {
                    "This workout: down $d in the first minute" + (drop120?.let { ", $it in two" } ?: "") + "."
                } else {
                    "You finished at ${endBpm ?: d} bpm, close to resting, so there was little to come down from and it isn't rated."
                }
                "Heart rate drop" to "How far your heart rate falls in the first minute after you stop. A bigger drop " +
                    "usually means a fitter heart, and under 12 bpm is on the low side. It only says something after a " +
                    "hard effort. $value"
            },
            vo2max?.let { "VO2 max" to "The most oxygen your body can use, in ml per kg per minute, estimated from this workout: ${Math.round(it)}." },
            "Where these come from" to (
                when {
                    effectFromStrap -> "From the strap, which recorded this workout alongside the phone and worked these out itself."
                    fromPhone -> "Worked out by OpenStrap from your heart rate, on the strap's own scales."
                    else -> "From the strap, worked out during the workout."
                }
                ) + if (effectFromStrap) "" else strapLine(strap),
        )
        val info = Info(
            name = "Load and training effect",
            meaning = "What this workout asked of your body, and what it trained.",
            scale = if (aerobic != null) EFFECT_SCALE else emptyList(),
            how = "", moves = "", off = "",
        )
        return EffectSheet(
            info, aerobic?.let(::effectBand),
            aerobic?.let { "You are here. Aerobic ${one(it)} on the 0 to 5 scale." },
            rows,
        )
    }
}
