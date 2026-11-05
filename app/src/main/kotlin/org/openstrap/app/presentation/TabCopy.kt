package org.openstrap.app.presentation

import org.openstrap.app.R
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.data.SportTypes
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.i18n.Strings
import java.time.LocalDate
import java.util.Locale

/** The sleep stages by name, as the hypnogram and the stage totals say them. */
val SLEEP_NAMES: Map<Int, String>
    get() = Strings.app.let {
        mapOf(
            SleepStage.AWAKE to it.get(R.string.core_tab_stage_awake),
            SleepStage.REM to it.get(R.string.core_tab_stage_rem),
            SleepStage.LIGHT to it.get(R.string.core_tab_stage_light),
            SleepStage.DEEP to it.get(R.string.core_tab_stage_deep),
        )
    }

/*
 * The words on the Recovery, Sleep and Activity tabs (spec 5 and 7). Like [TodayCopy], built only from numbers the
 * app already has: where one is missing the line says what it waits for, and nothing is guessed. The text is in
 * res/values/strings_core_tabs.xml.
 */
object TabCopy {
    /* ---------------------------------------------- Recovery ---------------------------------------------- */

    /** "+3 vs yesterday", "−3 vs yesterday" or "Same as yesterday"; null without yesterday's score. */
    fun vsYesterday(score: Int, yesterday: Int?, strings: Strings = Strings.app): String? = when {
        yesterday == null -> null
        score > yesterday -> strings.format(R.string.core_tab_vs_yesterday_up, score - yesterday)
        score < yesterday -> strings.format(R.string.core_tab_vs_yesterday_down, yesterday - score)
        else -> strings.get(R.string.core_tab_same_as_yesterday)
    }

    /**
     * The caveat while Recovery calibrates: nights toward the first score, then toward a settled one. Null once
     * there are enough.
     */
    fun learning(phase: Recovery.Phase, nights: Int, strings: Strings = Strings.app): String? = when (phase) {
        Recovery.Phase.Withheld -> learningOf(Recovery.SCORED_NIGHTS, nights, strings)
        Recovery.Phase.Learning -> learningOf(Recovery.SETTLED_NIGHTS, nights, strings)
        Recovery.Phase.Scored, Recovery.Phase.Calibrated -> null
    }

    /** "Learning, 4 of 7 nights", the noun agreeing with the nights it needs. */
    private fun learningOf(needed: Int, nights: Int, strings: Strings): String =
        strings.plural(R.plurals.core_tab_learning_nights, needed, nights, needed)

    /** A contributor's name on a row. */
    fun factorName(f: Recovery.Factor, strings: Strings = Strings.app): String = strings.get(
        when (f) {
            Recovery.Factor.HrvBalance -> R.string.core_tab_factor_hrv
            Recovery.Factor.RestingHr -> R.string.core_tab_factor_resting_hr
            Recovery.Factor.Sleep -> R.string.core_tab_factor_sleep
            Recovery.Factor.SleepBalance -> R.string.core_tab_factor_sleep_balance
            Recovery.Factor.Temperature -> R.string.core_tab_factor_temperature
            Recovery.Factor.Breathing -> R.string.core_tab_factor_breathing
            Recovery.Factor.PreviousDayLoad -> R.string.core_tab_factor_previous_load
            Recovery.Factor.BloodOxygen -> R.string.core_tab_factor_blood_oxygen
            Recovery.Factor.RecoveryIndex -> R.string.core_tab_factor_recovery_index
        },
    )

    /** [factorName] inside a sentence, written out since not every language lowers a name's first letter. */
    private fun factorPhrase(f: Recovery.Factor, strings: Strings): String = strings.get(
        when (f) {
            Recovery.Factor.HrvBalance -> R.string.core_tab_phrase_hrv
            Recovery.Factor.RestingHr -> R.string.core_tab_phrase_resting_hr
            Recovery.Factor.Sleep -> R.string.core_tab_phrase_sleep
            Recovery.Factor.SleepBalance -> R.string.core_tab_phrase_sleep_balance
            Recovery.Factor.Temperature -> R.string.core_tab_phrase_temperature
            Recovery.Factor.Breathing -> R.string.core_tab_phrase_breathing
            Recovery.Factor.PreviousDayLoad -> R.string.core_tab_phrase_previous_load
            Recovery.Factor.BloodOxygen -> R.string.core_tab_phrase_blood_oxygen
            Recovery.Factor.RecoveryIndex -> R.string.core_tab_phrase_recovery_index
        },
    )

    /** Contributors this light or lighter never lead the sentence: too small a part of the score. */
    private const val SENTENCE_WEIGHT = 0.06f

    /** Below this a contributor holds the score back (the Narrative's ratingFor calls it Good). */
    private const val HELD_BACK_BELOW = RecoveryBands.READY_FROM

    /**
     * The contributor holding Recovery back the most: the lowest scored of those that weigh enough, when it's under
     * [HELD_BACK_BELOW]. The same one the score's old explanation named.
     */
    fun heldBackBy(contributors: List<Recovery.Contributor>): Recovery.Factor? =
        contributors.filter { it.factor.weight >= SENTENCE_WEIGHT }.minByOrNull { it.score }?.takeIf { it.score < HELD_BACK_BELOW }?.factor

    /**
     * The Recovery hero's sentence: what the score means for the day and, short of well recovered, what held it
     * back most. "Recovered enough for your usual training. Mostly held back by HRV."
     */
    fun recovery(score: Int, alert: Boolean, contributors: List<Recovery.Contributor>, strings: Strings = Strings.app): String {
        val meaning = TodayCopy.recoveryMeaning(score, alert, strings)
        val by = heldBackBy(contributors)?.takeIf { score < RecoveryBands.READY_FOR_MORE_FROM && !alert } ?: return meaning
        return "$meaning ${strings.format(R.string.core_tab_held_back_by, factorPhrase(by, strings))}"
    }

    /** Before a Recovery has been worked out for the day. */
    val RECOVERY_WAITING: String get() = Strings.app.get(R.string.core_tab_recovery_waiting)

    /** The Recovery hero with no sleep recorded last night: no night to score (spec 4.3). */
    val NO_SLEEP_SCORE: String get() = Strings.app.get(R.string.core_tab_no_sleep_score)

    /** The same when the user deleted last night's sleep. */
    val DELETED_SLEEP_SCORE: String get() = Strings.app.get(R.string.core_tab_deleted_sleep_score)

    /** A contributor's status word, by the score's own bands. */
    fun contributorWord(score: Int, strings: Strings = Strings.app): String = strings.get(
        when {
            score >= RecoveryBands.READY_FOR_MORE_FROM -> R.string.core_tab_contributor_helping
            score >= RecoveryBands.READY_FROM -> R.string.core_tab_contributor_usual
            score >= RecoveryBands.MODERATE_FROM -> R.string.core_tab_contributor_little
            else -> R.string.core_tab_contributor_holding
        },
    )

    /** Whether a contributor is far enough from usual to take its colour. */
    fun contributorOff(score: Int): Boolean = score < RecoveryBands.READY_FROM || score >= RecoveryBands.READY_FOR_MORE_FROM

    /** The [n] contributors that weigh most, heaviest first: the ones "What drove it" shows. */
    fun topDrivers(contributors: List<Recovery.Contributor>, n: Int = 3): List<Recovery.Contributor> =
        contributors.sortedByDescending { it.factor.weight }.take(n)

    /** A Charge amount with its sign: "+32", "−12" with a real minus sign (spec 2.3), or "0" with none. */
    fun chargeAmount(v: Float, sign: Int): String {
        val n = v.toInt()
        return if (n == 0) "0" else "${if (sign > 0) "+" else "−"}$n"
    }

    /**
     * How much Charge was drained, as a plain amount: the "Drained" beside it already says which way, so no minus.
     * The model only ever adds to a drain (each minute's fall), so it's never below zero; the floor is a guard.
     */
    fun drainedAmount(v: Float): String = v.toInt().coerceAtLeast(0).toString()

    /** One row of "Energy today": what moved it, and whether it filled or drained it. */
    data class ChargeRow(val name: String, val value: String, val status: String)

    /** Where today's Charge came from and went, in the order it happened: sleep first, then the day. */
    fun chargeRows(recharged: Float, movement: Float, stress: Float, awake: Float, strings: Strings = Strings.app): List<ChargeRow> {
        val drained = strings.get(R.string.core_tab_charge_drained)
        val filled = strings.get(R.string.core_tab_charge_recharged)
        return listOf(
            ChargeRow(strings.get(R.string.core_tab_charge_sleep_rest), chargeAmount(recharged, +1), filled),
            ChargeRow(strings.get(R.string.core_tab_charge_movement), drainedAmount(movement), drained),
            ChargeRow(strings.get(R.string.core_tab_charge_stress), drainedAmount(stress), drained),
            ChargeRow(strings.get(R.string.core_tab_charge_awake), drainedAmount(awake), drained),
        )
    }

    /** The Charge card's line under the number. */
    fun chargeLine(now: Int?, atWake: Int?, anchored: Boolean, strings: Strings = Strings.app): String = when {
        !anchored -> strings.get(R.string.core_tab_charge_first_night)
        now == null -> strings.get(R.string.core_tab_charge_waiting)
        atWake == null -> strings.get(R.string.core_tab_charge_now)
        now < atWake -> strings.format(R.string.core_tab_charge_used, atWake - now)
        now > atWake -> strings.format(R.string.core_tab_charge_up, now - atWake)
        else -> strings.get(R.string.core_tab_charge_level)
    }

    /* ----------------------------------------------- Sleep ----------------------------------------------- */

    /** Fewer other nights than this in the week and "longest this week" means little. */
    const val WEEK_COMPARE_NIGHTS = 3

    /**
     * The night's sentence: how long you slept, and whether it was the longest or shortest of the week ([others]
     * are the week's other nights, asleep seconds). "7 h 40, your longest this week."
     */
    fun night(asleepSeconds: Long, others: List<Long>, strings: Strings = Strings.app): String {
        val id = when {
            others.size < WEEK_COMPARE_NIGHTS -> R.string.core_tab_night_asleep
            asleepSeconds > others.max() -> R.string.core_tab_night_longest
            asleepSeconds < others.min() -> R.string.core_tab_night_shortest
            else -> R.string.core_tab_night_asleep
        }
        return strings.format(id, Units.duration(asleepSeconds))
    }

    /** The Sleep tab's night before the strap has seen one (spec 4.3). */
    val FIRST_NIGHT_SLEEP: String get() = Strings.app.get(R.string.core_tab_first_night_sleep)

    /** [deleted]: the user deleted the night, so it says so rather than ask whether the strap was on. */
    fun noNight(naps: Boolean, isToday: Boolean, deleted: Boolean = false, strings: Strings = Strings.app): String = when {
        deleted -> TodayCopy.deletedNight(isToday, strings)
        naps -> strings.get(R.string.core_tab_only_naps)
        isToday -> strings.get(R.string.core_today_no_sleep)
        else -> strings.get(R.string.core_tab_no_sleep_that_night)
    }

    /** The sleep score's word, in the bands the strap's score is built for. */
    fun sleepScoreWord(score: Int): String = SleepBands.word(score)

    /**
     * The score's line beside the night's dotted score: "Sleep score, good", or why there's no score. One line per
     * band, so a language need not lower the band's word to fit it in.
     */
    fun sleepScoreLine(score: Int?, strings: Strings = Strings.app): String = score?.let {
        strings.get(
            when (SleepBands.bar(it)) {
                0 -> R.string.core_tab_sleep_score_poor
                1 -> R.string.core_tab_sleep_score_fair
                2 -> R.string.core_tab_sleep_score_good
                else -> R.string.core_tab_sleep_score_great
            },
        )
    } ?: strings.get(R.string.core_tab_no_strap_score)

    /** A night the strap gave no score. */
    val NO_STRAP_SCORE: String get() = Strings.app.get(R.string.core_tab_no_strap_score)

    /** A stage's total on the stage bar's legend: "Deep 1 h 32", "Awake 18 min". */
    fun stageTotal(stage: Int, seconds: Long, strings: Strings = Strings.app): String =
        strings.format(R.string.core_tab_stage_total, SLEEP_NAMES[stage].orEmpty(), Units.duration(seconds))

    /** "7 h 40 asleep". */
    fun asleep(seconds: Long, strings: Strings = Strings.app): String = strings.format(R.string.core_tab_asleep, Units.duration(seconds))

    /** When the night ran, bedtime to waking: "22:58 to 06:56", in the phone's clock. */
    fun nightTimes(start: Long, end: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        Ranges.times(start, end, zone)

    /** "Woke 3 times", "Woke once", "Slept through"; null when the strap didn't say. */
    fun woke(count: Int?, strings: Strings = Strings.app): String? = when (count) {
        null -> null
        0 -> strings.get(R.string.core_tab_slept_through)
        else -> strings.plural(R.plurals.core_tab_woke, count, count)
    }

    /** Sleep regularity's word, or what it still needs. */
    fun regularity(reg: SleepRegularity.Result?, strings: Strings = Strings.app): String = when {
        reg == null -> strings.get(R.string.core_tab_regularity_needs)
        reg.sri != null -> reg.band!!.label
        reg.midpointSdMinutes != null ->
            strings.get(if (reg.good == true) R.string.core_tab_regularity_steady else R.string.core_tab_regularity_moved)
        else -> strings.get(R.string.core_tab_regularity_needs)
    }

    /** "Usually asleep by 23:10, up at 06:50." from minutes since midnight. */
    fun usualTimes(bedMinutes: Int, wakeMinutes: Int, strings: Strings = Strings.app): String =
        strings.format(R.string.core_tab_usual_times, TimeOfDay.minutes(bedMinutes), TimeOfDay.minutes(wakeMinutes))

    /** Breathing disturbances against your usual, or how many nights it still needs. */
    fun breathing(b: Recovery.BreathingDisturbances, strings: Strings = Strings.app): String = when (val usual = b.usual) {
        null -> strings.plural(
            R.plurals.core_tab_breathing_learning, Recovery.SCORED_NIGHTS,
            b.nights.coerceAtMost(Recovery.SCORED_NIGHTS), Recovery.SCORED_NIGHTS,
        )
        else -> strings.format(R.string.core_tab_breathing_usual, String.format(Locale.getDefault(), "%.1f", usual))
    }

    /* --------------------------------------------- Activity --------------------------------------------- */

    /** Under Load's number: your usual for a whole day, or that it's still being learned. */
    fun loadUsual(usual: Float?, strings: Strings = Strings.app): String =
        if (usual == null || usual <= 0f) {
            strings.get(R.string.core_tab_load_learning)
        } else {
            strings.format(R.string.core_tab_load_usual, Math.round(usual))
        }

    /**
     * The week's balance from fatigue (the last 7 days) against fitness (the last 6 weeks): "Building" when the week
     * is well above your usual, "Easing off" when well below, "Steady" between, with the band Today uses for a usual
     * Load ([LoadBands.BAND]). Null before there's a fitness to compare with.
     *
     * Not a [org.openstrap.app.presentation.LoadBands] rule: it weighs the week's ATL against the CTL, not a day's Load
     * against your usual day, so it keeps its own words and only borrows the band's width.
     */
    fun loadBalance(trend: Recovery.LoadTrend?, strings: Strings = Strings.app): String? {
        val t = trend ?: return null
        if (t.ctl <= 0f) return null
        return strings.get(
            when {
                t.atl > t.ctl * (1 + LoadBands.BAND) -> R.string.core_tab_balance_building
                t.atl < t.ctl * (1 - LoadBands.BAND) -> R.string.core_tab_balance_easing
                else -> R.string.core_tab_balance_steady
            },
        )
    }

    /** Active minutes over the last 7 days against the 150 to aim for, named as the detail page names the span. */
    fun activeMinutes(week: Int, target: Int, strings: Strings = Strings.app): String =
        strings.format(if (week >= target) R.string.core_tab_active_reached else R.string.core_tab_active_goal, target)

    /** Heart points over the last 7 days against the 100 to aim for. */
    fun heartPoints(total: Int, target: Int, strings: Strings = Strings.app): String =
        strings.format(if (total >= target) R.string.core_tab_points_reached else R.string.core_tab_points_aim, target)

    /** Calories under its number. */
    fun calories(partial: Boolean, isToday: Boolean, strings: Strings = Strings.app): String = strings.get(
        when {
            isToday -> R.string.core_tab_calories_today
            partial -> R.string.core_tab_calories_partial
            else -> R.string.core_tab_calories
        },
    )

    /* ------------------------------------------- Small cards ------------------------------------------- */

    /** Under Drained on the Recovery tab: what took the most, or that nothing has yet. */
    fun drainedMostly(movement: Float, stress: Float, awake: Float, strings: Strings = Strings.app): String {
        val most = listOf(
            R.string.core_tab_mostly_movement to movement,
            R.string.core_tab_mostly_stress to stress,
            R.string.core_tab_mostly_awake to awake,
        ).maxBy { it.second }
        return strings.get(if (most.second.toInt() <= 0) R.string.core_tab_nothing_yet else most.first)
    }

    /** Under Recharged on the Recovery tab. */
    val RECHARGED: String get() = Strings.app.get(R.string.core_tab_charge_sleep_rest)

    /** Breathing disturbances as a Small: your usual, or how many nights it still needs, and that it's early. */
    fun breathingShort(b: Recovery.BreathingDisturbances, strings: Strings = Strings.app): String = when (val usual = b.usual) {
        null -> strings.plural(
            R.plurals.core_tab_breathing_short_learning, Recovery.SCORED_NIGHTS,
            b.nights.coerceAtMost(Recovery.SCORED_NIGHTS), Recovery.SCORED_NIGHTS,
        )
        else -> strings.format(R.string.core_tab_breathing_short_usual, String.format(Locale.getDefault(), "%.1f", usual))
    }

    /** The naps Small's title: "Nap" for one, "Naps" for more. */
    fun napsTitle(count: Int, strings: Strings = Strings.app): String = strings.plural(R.plurals.core_tab_naps_title, count)

    /** Under the naps Small: when the nap was, or how many and when the first. */
    fun naps(count: Int, firstAt: String, strings: Strings = Strings.app): String =
        if (count == 1) {
            strings.format(R.string.core_tab_nap_at, firstAt)
        } else {
            strings.plural(R.plurals.core_tab_naps_first_at, count, count, firstAt)
        }

    /** Under the Recovery hero's trend dots. */
    val LAST_7_DAYS: String get() = Strings.app.get(R.string.core_tab_last_7_days)

    /* ---------------------------------------------- Workouts --------------------------------------------- */

    /** The quiet tag beside a workout the strap found by itself, or filed under its generic "Activity". */
    val AUTO_DETECTED: String get() = Strings.app.get(R.string.core_tab_auto_detected)

    /** Which fact leads a workout's line, by sport. */
    enum class SportFact { Run, Walk, Strength, Other }

    fun sportFact(sportType: Int): SportFact = when (sportType) {
        in SportTypes.RUNS -> SportFact.Run
        in SportTypes.WALKS -> SportFact.Walk
        in SportTypes.STRENGTH -> SportFact.Strength
        else -> SportFact.Other
    }

    /**
     * A workout's one fact, by [sport]: distance and pace for a run ("5.2 km, 5:24 /km"), distance for a walk, sets
     * for strength ("14 sets"), average heart rate otherwise ("Average 128 bpm"). Each falls back to the next the
     * workout has: a run without a GPS pace to its distance, a run or walk without one to heart rate, strength without
     * sets to heart rate, anything else without heart rate to distance. Distance from steps is approximate ("≈ 3.4
     * km") and gets no pace. Only phone workouts carry distance and sets; the strap's fall back to heart rate.
     */
    fun workoutFact(
        sport: SportFact, distanceMeters: Float?, fromSteps: Boolean, activeSeconds: Long?, sets: Int?, averageHeartRate: Int?,
        strings: Strings = Strings.app,
    ): String? {
        val distance = distanceMeters?.let { Units.distance(it, Units.Precision.Day, fromSteps) }
        val pace = distanceMeters?.takeIf { !fromSteps }?.let { m ->
            activeSeconds?.takeIf { it > 0 }?.let { Pace.text(Pace.of(m, it)) }
        }
        val heart = averageHeartRate?.takeIf { it > 0 }?.let { strings.format(R.string.core_tab_fact_average_bpm, it) }
        val counted = sets?.takeIf { it > 0 }?.let { strings.plural(R.plurals.core_tab_fact_sets, it, it) }
        return when (sport) {
            SportFact.Run -> pace?.let { strings.format(R.string.core_tab_fact_distance_pace, distance.toString(), it) }
                ?: distance ?: heart
            SportFact.Walk -> distance ?: heart
            SportFact.Strength -> counted ?: heart
            SportFact.Other -> heart ?: distance
        }
    }

    /** "850 m" under a kilometre, "5.2 km" from one, "≈ " in front when estimated from steps. Empty under 10 m. */
    fun distanceText(meters: Float, fromSteps: Boolean): String = Units.distance(meters, Units.Precision.Day, fromSteps).orEmpty()

    /** "5:24 /km", null for a pace slower than an hour a kilometre, which is standing still. */
    fun paceText(secondsPerKm: Float): String? = Pace.text(secondsPerKm.toDouble())

    /**
     * A workout row's second line: its fact, then when it started ("3.4 km, 17:35"), or the time alone.
     * TODO: a personal best ("Fastest 5 km since March") goes after the fact in the mock's warm colour (0xFFFFD08A)
     * once the app detects them; it doesn't yet.
     */
    fun workoutLine(fact: String?, startClock: String, strings: Strings = Strings.app): String =
        fact?.let { strings.format(R.string.core_tab_workout_line, it, startClock) } ?: startClock

    /** The header over a day's workouts, by [DayWords]: "Today", "Yesterday", "Tuesday", then "Wed 21 Oct". */
    fun workoutDay(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = DayWords.relative(date, today, locale)

    /* --------------------------------------------- Habit Age --------------------------------------------- */

    /** "Updated Sun 18 Oct", for the Sunday the estimate is for. */
    fun habitAgeUpdated(sunday: LocalDate, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        strings.format(R.string.core_tab_habit_age_updated, DayWords.short(sunday, locale))

    /** "First estimate Sunday 22 Nov", before there's a number. */
    fun firstEstimate(sunday: LocalDate, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        strings.format(R.string.core_tab_first_estimate, DayWords.weekdayDayMonth(sunday, locale))

    /** Habit Age before its first estimate, under your own age in its place: "Your age for now", then what it waits for. */
    fun habitAgeForNow(waiting: String?, strings: Strings = Strings.app): String =
        if (waiting.isNullOrBlank()) {
            strings.get(R.string.core_tab_age_for_now)
        } else {
            strings.format(R.string.core_tab_age_for_now_waiting, waiting)
        }

    /** Habit Age's one status line, and whether the number moved, which alone takes the orchid. */
    data class HabitAgeLine(val text: String, val moved: Boolean)

    /**
     * Habit Age against last week's estimate, in whole years: "1 year younger than last week", "Same as last week".
     * Until there's a [lastWeek] it says the range instead: "Likely 27 to 31".
     */
    fun habitAgeLine(estimate: Float, lastWeek: Float?, low: Int, high: Int, strings: Strings = Strings.app): HabitAgeLine {
        lastWeek ?: return HabitAgeLine(strings.format(R.string.core_tab_habit_age_likely, low, high), moved = false)
        val d = Math.round(estimate) - Math.round(lastWeek)
        return when {
            d < 0 -> HabitAgeLine(strings.plural(R.plurals.core_tab_habit_age_younger, -d, -d), moved = true)
            d > 0 -> HabitAgeLine(strings.plural(R.plurals.core_tab_habit_age_older, d, d), moved = true)
            else -> HabitAgeLine(strings.get(R.string.core_tab_habit_age_same), moved = false)
        }
    }

    /** The age scale's labels: the range under its band and your age at its hairline. */
    fun ageRange(low: Int, high: Int, strings: Strings = Strings.app): String = strings.format(R.string.core_tab_age_range, low, high)
    fun yourAge(age: Int, strings: Strings = Strings.app): String = strings.format(R.string.core_tab_your_age, age)
}
