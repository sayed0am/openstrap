package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.data.Workouts
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/*
 * The words on the Recovery, Sleep and Activity tabs (spec 5 and 7). Like [TodayCopy], built only from numbers the
 * app already has: where one is missing the line says what it waits for, and nothing is guessed.
 */
object TabCopy {
    /* ---------------------------------------------- Recovery ---------------------------------------------- */

    /** "+3 vs yesterday", "−3 vs yesterday" or "Same as yesterday"; null without yesterday's score. */
    fun vsYesterday(score: Int, yesterday: Int?): String? = when {
        yesterday == null -> null
        score > yesterday -> tr(R.string.tab_copy_vs_yesterday, score - yesterday)
        score < yesterday -> tr(R.string.tab_copy_vs_yesterday_2, yesterday - score)
        else -> tr(R.string.tab_copy_same_as_yesterday)
    }

    /**
     * The caveat while Recovery calibrates: nights toward the first score, then toward a settled one. Null once
     * there are enough.
     */
    fun learning(phase: Recovery.Phase, nights: Int): String? = when (phase) {
        Recovery.Phase.Withheld -> tr(R.string.tab_copy_learning_of_nights, nights, Recovery.SCORED_NIGHTS)
        Recovery.Phase.Learning -> tr(R.string.tab_copy_learning_of_nights_2, nights, Recovery.SETTLED_NIGHTS)
        Recovery.Phase.Scored, Recovery.Phase.Calibrated -> null
    }

    /** A contributor's name on a row. */
    fun factorName(f: Recovery.Factor): String = when (f) {
        Recovery.Factor.HrvBalance -> tr(R.string.common_hrv)
        Recovery.Factor.RestingHr -> tr(R.string.common_resting_heart_rate_2)
        Recovery.Factor.Sleep -> tr(R.string.common_last_night_s_sleep)
        Recovery.Factor.SleepBalance -> tr(R.string.tab_copy_sleep_over_recent_nights)
        Recovery.Factor.Temperature -> tr(R.string.common_skin_temperature)
        Recovery.Factor.Breathing -> tr(R.string.common_breathing_rate)
        Recovery.Factor.PreviousDayLoad -> tr(R.string.tab_copy_yesterday_s_load)
        Recovery.Factor.BloodOxygen -> tr(R.string.common_blood_oxygen)
        Recovery.Factor.RecoveryIndex -> tr(R.string.tab_copy_when_your_heart_settled)
    }

    /** [factorName] inside a sentence. */
    private fun factorPhrase(f: Recovery.Factor): String = when (f) {
        Recovery.Factor.HrvBalance -> tr(R.string.common_hrv)
        Recovery.Factor.RecoveryIndex -> tr(R.string.tab_copy_how_late_your_heart)
        else -> factorName(f).replaceFirstChar { it.lowercase() }
    }

    /** Contributors this light or lighter never lead the sentence: too small a part of the score. */
    private const val SENTENCE_WEIGHT = 0.06f

    /** Below this a contributor holds the score back ([org.openstrap.app.ui.dashboard.Narrative.ratingFor]'s Good). */
    private const val HELD_BACK_BELOW = 70

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
    fun recovery(score: Int, alert: Boolean, contributors: List<Recovery.Contributor>): String {
        val meaning = TodayCopy.recoveryMeaning(score, alert)
        val by = heldBackBy(contributors)?.takeIf { score < 85 && !alert } ?: return meaning
        return tr(R.string.tab_copy_mostly_held_back_by, meaning, factorPhrase(by))
    }

    /** Before a Recovery has been worked out for the day. */
    val RECOVERY_WAITING: String get() = tr(R.string.tab_copy_recovery_shows_once_last)

    /** The Recovery hero with no sleep recorded last night: no night to score (spec 4.3). */
    val NO_SLEEP_SCORE: String get() = tr(R.string.tab_copy_no_sleep_recorded_last)

    /** A contributor's status word, by the score's own bands. */
    fun contributorWord(score: Int): String = when {
        score >= 85 -> tr(R.string.common_helping)
        score >= 70 -> tr(R.string.common_usual)
        score >= Recovery.FAIR_FROM -> tr(R.string.tab_copy_holding_back_a_little)
        else -> tr(R.string.tab_copy_holding_you_back)
    }

    /** Whether a contributor is far enough from usual to take its colour. */
    fun contributorOff(score: Int): Boolean = score < 70 || score >= 85

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
    fun chargeRows(recharged: Float, movement: Float, stress: Float, awake: Float): List<ChargeRow> = listOf(
        ChargeRow(tr(R.string.tab_copy_sleep_and_calm_rest), chargeAmount(recharged, +1), tr(R.string.common_recharged)),
        ChargeRow(tr(R.string.tab_copy_movement), drainedAmount(movement), tr(R.string.common_drained)),
        ChargeRow(tr(R.string.common_stress), drainedAmount(stress), tr(R.string.common_drained)),
        ChargeRow(tr(R.string.tab_copy_being_awake), drainedAmount(awake), tr(R.string.common_drained)),
    )

    /** The Charge card's line under the number. */
    fun chargeLine(now: Int?, atWake: Int?, anchored: Boolean): String = when {
        !anchored -> tr(R.string.common_energy_starts_after_your)
        now == null -> tr(R.string.tab_copy_energy_shows_once_the)
        atWake == null -> tr(R.string.common_now)
        now < atWake -> tr(R.string.tab_copy_used_since_waking, atWake - now)
        now > atWake -> tr(R.string.tab_copy_up_since_waking, now - atWake)
        else -> tr(R.string.tab_copy_level_since_waking)
    }

    /* ----------------------------------------------- Sleep ----------------------------------------------- */

    /** Fewer other nights than this in the week and "longest this week" means little. */
    const val WEEK_COMPARE_NIGHTS = 3

    /**
     * The night's sentence: how long you slept, and whether it was the longest or shortest of the week ([others]
     * are the week's other nights, asleep seconds). "7 h 40, your longest this week."
     */
    fun night(asleepSeconds: Long, others: List<Long>): String {
        val d = TodayCopy.duration(asleepSeconds)
        return when {
            others.size < WEEK_COMPARE_NIGHTS -> tr(R.string.missed_asleep_sentence, d)
            asleepSeconds > others.max() -> tr(R.string.tab_copy_your_longest_this_week, d)
            asleepSeconds < others.min() -> tr(R.string.tab_copy_your_shortest_this_week, d)
            else -> tr(R.string.missed_asleep_sentence, d)
        }
    }

    /** The Sleep hero with no night to tell. */
    /** The Sleep tab's night before the strap has seen one (spec 4.3). */
    val FIRST_NIGHT_SLEEP: String get() = tr(R.string.tab_copy_your_first_night_with)

    fun noNight(naps: Boolean, isToday: Boolean): String = when {
        naps -> tr(R.string.tab_copy_no_night_sleep_recorded)
        isToday -> TodayCopy.NO_SLEEP
        else -> tr(R.string.common_no_sleep_recorded_that)
    }

    /** The sleep score's word, in the bands the strap's score is built for. */
    fun sleepScoreWord(score: Int): String = when {
        score >= 90 -> tr(R.string.common_great)
        score >= 80 -> tr(R.string.common_good)
        score >= 60 -> tr(R.string.common_fair)
        else -> tr(R.string.common_poor)
    }

    /** The score's line beside the night's dotted score: "Sleep score, good", or why there's no score. */
    fun sleepScoreLine(score: Int?): String = score?.let { tr(R.string.tab_copy_sleep_score, sleepScoreWord(it).lowercase()) } ?: NO_STRAP_SCORE

    /** A night the strap gave no score. */
    val NO_STRAP_SCORE: String get() = tr(R.string.tab_copy_no_score_from_the)

    /** A stage's total on the stage bar's legend: "Deep 1 h 32", "Awake 18 min". */
    fun stageTotal(stage: Int, seconds: Long): String = "${SLEEP_NAMES[stage].orEmpty()} ${TodayCopy.duration(seconds)}"

    /** "7 h 40 asleep". */
    fun asleep(seconds: Long): String = tr(R.string.missed_asleep, TodayCopy.duration(seconds))

    /** When the night ran, bedtime to waking: "22:58–06:56" (an en dash, a time range). */
    fun nightTimes(start: Long, end: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        fun clock(epoch: Long) = java.time.Instant.ofEpochSecond(epoch).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        return "${clock(start)}–${clock(end)}"
    }

    /** "Woke 3 times", "Woke once", "Slept through"; null when the strap didn't say. */
    fun woke(count: Int?): String? = when (count) {
        null -> null
        0 -> tr(R.string.tab_copy_slept_through)
        1 -> tr(R.string.tab_copy_woke_once)
        else -> tr(R.string.tab_copy_woke_times, count)
    }

    /** Sleep regularity's word, or what it still needs. */
    fun regularity(reg: SleepRegularity.Result?): String = when {
        reg == null -> tr(R.string.tab_copy_needs_about_days_of)
        reg.sri != null -> reg.band!!.label
        reg.midpointSdMinutes != null -> if (reg.good == true) tr(R.string.tab_copy_steady_bedtimes_this_week) else tr(R.string.tab_copy_bedtimes_moved_this_week)
        else -> tr(R.string.tab_copy_needs_about_days_of)
    }

    /** "Usually asleep by 23:10, up at 06:50." from minutes since midnight. */
    fun usualTimes(bedMinutes: Int, wakeMinutes: Int): String =
        tr(R.string.tab_copy_usually_asleep_by_up, minutes(bedMinutes), minutes(wakeMinutes))

    private fun minutes(m: Int) = Math.floorMod(m, 1440).let { "%02d:%02d".format(it / 60, it % 60) }

    /** Breathing disturbances against your usual, or how many nights it still needs. */
    fun breathing(b: Recovery.BreathingDisturbances): String = when (val usual = b.usual) {
        null -> tr(R.string.tab_copy_learning_your_usual_of, b.nights.coerceAtMost(Recovery.SCORED_NIGHTS), Recovery.SCORED_NIGHTS)
        else -> trRaw(R.string.tab_copy_your_usual_is_f).format(usual)
    }

    /* --------------------------------------------- Activity --------------------------------------------- */

    /** Under Load's number: your usual for a whole day, or that it's still being learned. */
    fun loadUsual(usual: Float?): String =
        if (usual == null || usual <= 0f) tr(R.string.common_learning_your_usual) else tr(R.string.tab_copy_your_usual_is, Math.round(usual))

    /**
     * The week's balance from fatigue (the last 7 days) against fitness (the last 6 weeks): "Building" when the week
     * is well above your usual, "Easing off" when well below, "Steady" between, with the band Today uses for a usual
     * Load ([TodayCopy.LOAD_BAND]). Null before there's a fitness to compare with.
     */
    fun loadBalance(trend: Recovery.LoadTrend?): String? {
        val t = trend ?: return null
        if (t.ctl <= 0f) return null
        return when {
            t.atl > t.ctl * (1 + TodayCopy.LOAD_BAND) -> tr(R.string.tab_copy_building)
            t.atl < t.ctl * (1 - TodayCopy.LOAD_BAND) -> tr(R.string.tab_copy_easing_off)
            else -> tr(R.string.common_steady)
        }
    }

    /** Active minutes over the week against the 150 to aim for. */
    fun activeMinutes(week: Int, target: Int): String =
        if (week >= target) tr(R.string.tab_copy_this_week_goal_of, target) else tr(R.string.tab_copy_this_week_goal, target)

    /** Heart points over the last 7 days against the 100 to aim for. */
    fun heartPoints(total: Int, target: Int): String =
        if (total >= target) tr(R.string.tab_copy_last_days_at_or, target) else tr(R.string.tab_copy_last_days_aim_for, target)

    /** Energy under its number. */
    fun energy(partial: Boolean, isToday: Boolean): String = when {
        isToday -> tr(R.string.tab_copy_estimated_kcal_so_far)
        partial -> tr(R.string.tab_copy_estimated_kcal_part_of)
        else -> tr(R.string.tab_copy_estimated_kcal)
    }

    /* ------------------------------------------- Small cards ------------------------------------------- */

    /** Under Drained on the Recovery tab: what took the most, or that nothing has yet. */
    fun drainedMostly(movement: Float, stress: Float, awake: Float): String {
        val most = listOf(tr(R.string.tab_copy_mostly_movement) to movement, tr(R.string.tab_copy_mostly_stress) to stress, tr(R.string.tab_copy_mostly_being_awake) to awake).maxBy { it.second }
        return if (most.second.toInt() <= 0) tr(R.string.tab_copy_nothing_yet) else most.first
    }

    /** Under Recharged on the Recovery tab. */
    val RECHARGED: String get() = tr(R.string.tab_copy_sleep_and_calm_rest)

    /** Breathing disturbances as a Small: your usual, or how many nights it still needs, and that it's early. */
    fun breathingShort(b: Recovery.BreathingDisturbances): String = when (val usual = b.usual) {
        null -> tr(R.string.tab_copy_learning_of_nights_3, b.nights.coerceAtMost(Recovery.SCORED_NIGHTS), Recovery.SCORED_NIGHTS)
        else -> trRaw(R.string.tab_copy_usual_f_early_estimate).format(usual)
    }

    /** The naps Small's title: "Nap" for one, "Naps" for more. */
    fun napsTitle(count: Int): String = if (count == 1) tr(R.string.tab_copy_nap) else tr(R.string.common_naps)

    /** Under the naps Small: when the nap was, or how many and when the first. */
    fun naps(count: Int, firstAt: String): String = if (count == 1) tr(R.string.tab_copy_at, firstAt) else tr(R.string.tab_copy_naps_first_at, count, firstAt)

    /** Under the Recovery hero's trend dots. */
    val LAST_7_DAYS: String get() = tr(R.string.common_last_days)

    /* ---------------------------------------------- Workouts --------------------------------------------- */

    /** The quiet tag beside a workout the strap found by itself, or filed under its generic "Activity". */
    const val AUTO_DETECTED = "Auto-detected"

    /** Which fact leads a workout's line, by sport. */
    enum class SportFact { Run, Walk, Strength, Other }

    fun sportFact(sportType: Int): SportFact = when (sportType) {
        in Workouts.RUNS -> SportFact.Run
        in Workouts.WALKS -> SportFact.Walk
        in org.openstrap.app.data.Strength.SPORTS -> SportFact.Strength
        else -> SportFact.Other
    }

    /** Shorter than this and a pace says more about GPS noise than the run. */
    private const val PACE_MIN_M = 500f

    /**
     * A workout's one fact, by [sport]: distance and pace for a run ("5.2 km, 5:24 a km"), distance for a walk, sets
     * for strength ("14 sets"), average heart rate otherwise ("Average 128 bpm"). Each falls back to the next the
     * workout has: a run without a GPS pace to its distance, a run or walk without one to heart rate, strength without
     * sets to heart rate, anything else without heart rate to distance. Distance from steps is approximate ("≈ 3.4
     * km") and gets no pace. Only phone workouts carry distance and sets; the strap's fall back to heart rate.
     */
    fun workoutFact(sport: SportFact, distanceMeters: Float?, fromSteps: Boolean, activeSeconds: Long?, sets: Int?, averageHeartRate: Int?): String? {
        val distance = distanceMeters?.takeIf { it >= 10f }?.let { distanceText(it, fromSteps) }
        val pace = distanceMeters?.takeIf { !fromSteps && it >= PACE_MIN_M }?.let { m ->
            activeSeconds?.takeIf { it > 0 }?.let { paceText(it / (m / 1000f)) }
        }
        val heart = averageHeartRate?.takeIf { it > 0 }?.let { tr(R.string.common_average_bpm, it) }
        val counted = sets?.takeIf { it > 0 }?.let { if (it == 1) tr(R.string.common_set) else tr(R.string.missed_n_sets, it) }
        return when (sport) {
            SportFact.Run -> pace?.let { "$distance, $it" } ?: distance ?: heart
            SportFact.Walk -> distance ?: heart
            SportFact.Strength -> counted ?: heart
            SportFact.Other -> heart ?: distance
        }
    }

    /** "850 m" under a kilometre, "5.2 km" from one; "≈ " in front when estimated from steps. */
    fun distanceText(meters: Float, fromSteps: Boolean): String =
        (if (fromSteps) "≈ " else "") + if (meters < 1000f) "${meters.toInt()} m" else trRaw(R.string.tab_copy_f_km).format(meters / 1000f)

    /** "5:24 a km"; null for a pace slower than an hour a kilometre, which is standing still. */
    fun paceText(secondsPerKm: Float): String? {
        if (secondsPerKm <= 0f || secondsPerKm > 3600f) return null
        val s = Math.round(secondsPerKm)
        return trRaw(R.string.tab_copy_d_d_a_km).format(s / 60, s % 60)
    }

    /**
     * A workout row's second line: its fact, then when it started ("3.4 km, 17:35"), or the time alone.
     * TODO: a personal best ("Fastest 5 km since March") goes after the fact in the mock's warm colour (0xFFFFD08A)
     * once the app detects them; it doesn't yet.
     */
    fun workoutLine(fact: String?, startClock: String): String = listOfNotNull(fact, startClock).joinToString(", ")

    /** The header over a day's workouts: "Today", "Yesterday", then "Wed 21 Oct", with the year when it isn't this one. */
    fun workoutDay(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (date) {
        today -> tr(R.string.common_today)
        today.minusDays(1) -> tr(R.string.common_yesterday)
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", locale))
    }

    /* --------------------------------------------- Habit Age --------------------------------------------- */

    /** "Updated Sun 18 Oct", for the Sunday the estimate is for. */
    fun habitAgeUpdated(sunday: LocalDate, locale: Locale = Locale.getDefault()): String =
        tr(R.string.tab_copy_updated) + sunday.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

    /** "First estimate Sunday 22 Nov", before there's a number. */
    fun firstEstimate(sunday: LocalDate, locale: Locale = Locale.getDefault()): String =
        tr(R.string.tab_copy_first_estimate) + sunday.format(DateTimeFormatter.ofPattern("EEEE d MMM", locale))

    /** Habit Age before its first estimate, under your own age in its place: "Your age for now", then what it waits for. */
    fun habitAgeForNow(waiting: String?): String =
        if (waiting.isNullOrBlank()) tr(R.string.tab_copy_your_age_for_now) else tr(R.string.tab_copy_your_age_for_now_2, waiting)

    /** Habit Age's one status line, and whether the number moved, which alone takes the orchid. */
    data class HabitAgeLine(val text: String, val moved: Boolean)

    /**
     * Habit Age against last week's estimate, in whole years: "1 year younger than last week", "Same as last week".
     * Until there's a [lastWeek] it says the range instead: "Likely 27 to 31".
     */
    fun habitAgeLine(estimate: Float, lastWeek: Float?, low: Int, high: Int): HabitAgeLine {
        lastWeek ?: return HabitAgeLine(tr(R.string.tab_copy_likely_to, low, high), moved = false)
        val d = Math.round(estimate) - Math.round(lastWeek)
        fun years(n: Int) = if (n == 1) tr(R.string.tab_copy_year) else tr(R.string.missed_n_years, n)
        return when {
            d < 0 -> HabitAgeLine(tr(R.string.tab_copy_younger_than_last_week, years(-d)), moved = true)
            d > 0 -> HabitAgeLine(tr(R.string.tab_copy_older_than_last_week, years(d)), moved = true)
            else -> HabitAgeLine(tr(R.string.tab_copy_same_as_last_week), moved = false)
        }
    }

    /** The age scale's labels: the range under its band and your age at its hairline. */
    fun ageRange(low: Int, high: Int): String = tr(R.string.missed_age_range, low, high)
    fun yourAge(age: Int): String = tr(R.string.missed_you_age, age)
}
