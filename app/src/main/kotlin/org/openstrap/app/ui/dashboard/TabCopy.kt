package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.data.Workouts
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The words on the Recovery, Sleep and Activity tabs (spec 5 and 7). Like [TodayCopy], built only from numbers the
 * app already has: where one is missing the line says what it waits for, and nothing is guessed.
 */
object TabCopy {
    /* ---------------------------------------------- Recovery ---------------------------------------------- */

    /** "+3 vs yesterday", "−3 vs yesterday" or "Same as yesterday"; null without yesterday's score. */
    fun vsYesterday(score: Int, yesterday: Int?): String? = when {
        yesterday == null -> null
        score > yesterday -> "+${score - yesterday} vs yesterday"
        score < yesterday -> "−${yesterday - score} vs yesterday"
        else -> "Same as yesterday"
    }

    /**
     * The caveat while Recovery calibrates: nights toward the first score, then toward a settled one. Null once
     * there are enough.
     */
    fun learning(phase: Recovery.Phase, nights: Int): String? = when (phase) {
        Recovery.Phase.Withheld -> "Learning, $nights of ${Recovery.SCORED_NIGHTS} nights"
        Recovery.Phase.Learning -> "Learning, $nights of ${Recovery.SETTLED_NIGHTS} nights"
        Recovery.Phase.Scored, Recovery.Phase.Calibrated -> null
    }

    /** A contributor's name on a row. */
    fun factorName(f: Recovery.Factor): String = when (f) {
        Recovery.Factor.HrvBalance -> "HRV"
        Recovery.Factor.RestingHr -> "Resting heart rate"
        Recovery.Factor.Sleep -> "Last night's sleep"
        Recovery.Factor.SleepBalance -> "Sleep over recent nights"
        Recovery.Factor.Temperature -> "Skin temperature"
        Recovery.Factor.Breathing -> "Breathing rate"
        Recovery.Factor.PreviousDayLoad -> "Yesterday's Load"
        Recovery.Factor.BloodOxygen -> "Blood oxygen"
        Recovery.Factor.RecoveryIndex -> "When your heart settled"
    }

    /** [factorName] inside a sentence. */
    private fun factorPhrase(f: Recovery.Factor): String = when (f) {
        Recovery.Factor.HrvBalance -> "HRV"
        Recovery.Factor.RecoveryIndex -> "how late your heart settled"
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
        return "$meaning Mostly held back by ${factorPhrase(by)}."
    }

    /** Before a Recovery has been worked out for the day. */
    const val RECOVERY_WAITING = "Recovery shows once last night is in."

    /** The Recovery hero with no sleep recorded last night: no night to score (spec 4.3). */
    const val NO_SLEEP_SCORE = "No sleep recorded last night, so there's no score today."

    /** The same when the user deleted last night's sleep. */
    const val DELETED_SLEEP_SCORE = "You deleted last night's sleep, so there's no score today."

    /** A contributor's status word, by the score's own bands. */
    fun contributorWord(score: Int): String = when {
        score >= 85 -> "Helping"
        score >= 70 -> "Usual"
        score >= Recovery.FAIR_FROM -> "Holding back a little"
        else -> "Holding you back"
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
        ChargeRow("Sleep and calm rest", chargeAmount(recharged, +1), "Recharged"),
        ChargeRow("Movement", drainedAmount(movement), "Drained"),
        ChargeRow("Stress", drainedAmount(stress), "Drained"),
        ChargeRow("Being awake", drainedAmount(awake), "Drained"),
    )

    /** The Charge card's line under the number. */
    fun chargeLine(now: Int?, atWake: Int?, anchored: Boolean): String = when {
        !anchored -> "Energy starts after your first night. Sleep is what fills it."
        now == null -> "Energy shows once the strap has a few hours of heart rate."
        atWake == null -> "Now"
        now < atWake -> "Used ${atWake - now} since waking"
        now > atWake -> "Up ${now - atWake} since waking"
        else -> "Level since waking"
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
            others.size < WEEK_COMPARE_NIGHTS -> "$d asleep."
            asleepSeconds > others.max() -> "$d, your longest this week."
            asleepSeconds < others.min() -> "$d, your shortest this week."
            else -> "$d asleep."
        }
    }

    /** The Sleep hero with no night to tell. */
    /** The Sleep tab's night before the strap has seen one (spec 4.3). */
    const val FIRST_NIGHT_SLEEP = "Your first night with the strap shows here in the morning."

    /** [deleted]: the user deleted the night, so it says so rather than ask whether the strap was on. */
    fun noNight(naps: Boolean, isToday: Boolean, deleted: Boolean = false): String = when {
        deleted -> TodayCopy.deletedNight(isToday)
        naps -> "No night sleep recorded, only naps."
        isToday -> TodayCopy.NO_SLEEP
        else -> "No sleep recorded that night."
    }

    /** The sleep score's word, in the bands the strap's score is built for. */
    fun sleepScoreWord(score: Int): String = when {
        score >= 90 -> "Great"
        score >= 80 -> "Good"
        score >= 60 -> "Fair"
        else -> "Poor"
    }

    /** The score's line beside the night's dotted score: "Sleep score, good", or why there's no score. */
    fun sleepScoreLine(score: Int?): String = score?.let { "Sleep score, ${sleepScoreWord(it).lowercase()}" } ?: NO_STRAP_SCORE

    /** A night the strap gave no score. */
    const val NO_STRAP_SCORE = "No score from the strap"

    /** A stage's total on the stage bar's legend: "Deep 1 h 32", "Awake 18 min". */
    fun stageTotal(stage: Int, seconds: Long): String = "${SLEEP_NAMES[stage].orEmpty()} ${TodayCopy.duration(seconds)}"

    /** "7 h 40 asleep". */
    fun asleep(seconds: Long): String = "${TodayCopy.duration(seconds)} asleep"

    /** When the night ran, bedtime to waking: "22:58–06:56" (an en dash, a time range). */
    fun nightTimes(start: Long, end: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        fun clock(epoch: Long) = java.time.Instant.ofEpochSecond(epoch).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        return "${clock(start)}–${clock(end)}"
    }

    /** "Woke 3 times", "Woke once", "Slept through"; null when the strap didn't say. */
    fun woke(count: Int?): String? = when (count) {
        null -> null
        0 -> "Slept through"
        1 -> "Woke once"
        else -> "Woke $count times"
    }

    /** Sleep regularity's word, or what it still needs. */
    fun regularity(reg: SleepRegularity.Result?): String = when {
        reg == null -> "Needs about 5 days of wear"
        reg.sri != null -> reg.band!!.label
        reg.midpointSdMinutes != null -> if (reg.good == true) "Steady bedtimes this week" else "Bedtimes moved this week"
        else -> "Needs about 5 days of wear"
    }

    /** "Usually asleep by 23:10, up at 06:50." from minutes since midnight. */
    fun usualTimes(bedMinutes: Int, wakeMinutes: Int): String =
        "Usually asleep by ${minutes(bedMinutes)}, up at ${minutes(wakeMinutes)}."

    private fun minutes(m: Int) = Math.floorMod(m, 1440).let { "%02d:%02d".format(it / 60, it % 60) }

    /** Breathing disturbances against your usual, or how many nights it still needs. */
    fun breathing(b: Recovery.BreathingDisturbances): String = when (val usual = b.usual) {
        null -> "Learning your usual, ${b.nights.coerceAtMost(Recovery.SCORED_NIGHTS)} of ${Recovery.SCORED_NIGHTS} nights"
        else -> "Your usual is %.1f an hour".format(usual)
    }

    /* --------------------------------------------- Activity --------------------------------------------- */

    /** Under Load's number: your usual for a whole day, or that it's still being learned. */
    fun loadUsual(usual: Float?): String =
        if (usual == null || usual <= 0f) "Learning your usual" else "Your usual is ${Math.round(usual)}"

    /**
     * The week's balance from fatigue (the last 7 days) against fitness (the last 6 weeks): "Building" when the week
     * is well above your usual, "Easing off" when well below, "Steady" between, with the band Today uses for a usual
     * Load ([TodayCopy.LOAD_BAND]). Null before there's a fitness to compare with.
     */
    fun loadBalance(trend: Recovery.LoadTrend?): String? {
        val t = trend ?: return null
        if (t.ctl <= 0f) return null
        return when {
            t.atl > t.ctl * (1 + TodayCopy.LOAD_BAND) -> "Building"
            t.atl < t.ctl * (1 - TodayCopy.LOAD_BAND) -> "Easing off"
            else -> "Steady"
        }
    }

    /** Active minutes over the week against the 150 to aim for. */
    fun activeMinutes(week: Int, target: Int): String =
        if (week >= target) "This week, goal of $target reached" else "This week, goal $target"

    /** Heart points over the last 7 days against the 100 to aim for. */
    fun heartPoints(total: Int, target: Int): String =
        if (total >= target) "Last 7 days, at $target or more" else "Last 7 days, aim for $target"

    /** Energy under its number. */
    fun energy(partial: Boolean, isToday: Boolean): String = when {
        isToday -> "Estimated kcal so far today"
        partial -> "Estimated kcal, part of the day worn"
        else -> "Estimated kcal"
    }

    /* ------------------------------------------- Small cards ------------------------------------------- */

    /** Under Drained on the Recovery tab: what took the most, or that nothing has yet. */
    fun drainedMostly(movement: Float, stress: Float, awake: Float): String {
        val most = listOf("Mostly movement" to movement, "Mostly stress" to stress, "Mostly being awake" to awake).maxBy { it.second }
        return if (most.second.toInt() <= 0) "Nothing yet" else most.first
    }

    /** Under Recharged on the Recovery tab. */
    const val RECHARGED = "Sleep and calm rest"

    /** Breathing disturbances as a Small: your usual, or how many nights it still needs, and that it's early. */
    fun breathingShort(b: Recovery.BreathingDisturbances): String = when (val usual = b.usual) {
        null -> "Learning, ${b.nights.coerceAtMost(Recovery.SCORED_NIGHTS)} of ${Recovery.SCORED_NIGHTS} nights"
        else -> "Usual %.1f, early estimate".format(usual)
    }

    /** The naps Small's title: "Nap" for one, "Naps" for more. */
    fun napsTitle(count: Int): String = if (count == 1) "Nap" else "Naps"

    /** Under the naps Small: when the nap was, or how many and when the first. */
    fun naps(count: Int, firstAt: String): String = if (count == 1) "At $firstAt" else "$count naps, first at $firstAt"

    /** Under the Recovery hero's trend dots. */
    const val LAST_7_DAYS = "Last 7 days"

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
        val heart = averageHeartRate?.takeIf { it > 0 }?.let { "Average $it bpm" }
        val counted = sets?.takeIf { it > 0 }?.let { if (it == 1) "1 set" else "$it sets" }
        return when (sport) {
            SportFact.Run -> pace?.let { "$distance, $it" } ?: distance ?: heart
            SportFact.Walk -> distance ?: heart
            SportFact.Strength -> counted ?: heart
            SportFact.Other -> heart ?: distance
        }
    }

    /** "850 m" under a kilometre, "5.2 km" from one; "≈ " in front when estimated from steps. */
    fun distanceText(meters: Float, fromSteps: Boolean): String =
        (if (fromSteps) "≈ " else "") + if (meters < 1000f) "${meters.toInt()} m" else "%.1f km".format(meters / 1000f)

    /** "5:24 a km"; null for a pace slower than an hour a kilometre, which is standing still. */
    fun paceText(secondsPerKm: Float): String? {
        if (secondsPerKm <= 0f || secondsPerKm > 3600f) return null
        val s = Math.round(secondsPerKm)
        return "%d:%02d a km".format(s / 60, s % 60)
    }

    /**
     * A workout row's second line: its fact, then when it started ("3.4 km, 17:35"), or the time alone.
     * TODO: a personal best ("Fastest 5 km since March") goes after the fact in the mock's warm colour (0xFFFFD08A)
     * once the app detects them; it doesn't yet.
     */
    fun workoutLine(fact: String?, startClock: String): String = listOfNotNull(fact, startClock).joinToString(", ")

    /** The header over a day's workouts: "Today", "Yesterday", then "Wed 21 Oct", with the year when it isn't this one. */
    fun workoutDay(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", locale))
    }

    /* --------------------------------------------- Habit Age --------------------------------------------- */

    /** "Updated Sun 18 Oct", for the Sunday the estimate is for. */
    fun habitAgeUpdated(sunday: LocalDate, locale: Locale = Locale.getDefault()): String =
        "Updated " + sunday.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

    /** "First estimate Sunday 22 Nov", before there's a number. */
    fun firstEstimate(sunday: LocalDate, locale: Locale = Locale.getDefault()): String =
        "First estimate " + sunday.format(DateTimeFormatter.ofPattern("EEEE d MMM", locale))

    /** Habit Age before its first estimate, under your own age in its place: "Your age for now", then what it waits for. */
    fun habitAgeForNow(waiting: String?): String = if (waiting.isNullOrBlank()) "Your age for now" else "Your age for now. $waiting"

    /** Habit Age's one status line, and whether the number moved, which alone takes the orchid. */
    data class HabitAgeLine(val text: String, val moved: Boolean)

    /**
     * Habit Age against last week's estimate, in whole years: "1 year younger than last week", "Same as last week".
     * Until there's a [lastWeek] it says the range instead: "Likely 27 to 31".
     */
    fun habitAgeLine(estimate: Float, lastWeek: Float?, low: Int, high: Int): HabitAgeLine {
        lastWeek ?: return HabitAgeLine("Likely $low to $high", moved = false)
        val d = Math.round(estimate) - Math.round(lastWeek)
        fun years(n: Int) = if (n == 1) "1 year" else "$n years"
        return when {
            d < 0 -> HabitAgeLine("${years(-d)} younger than last week", moved = true)
            d > 0 -> HabitAgeLine("${years(d)} older than last week", moved = true)
            else -> HabitAgeLine("Same as last week", moved = false)
        }
    }

    /** The age scale's labels: the range under its band and your age at its hairline. */
    fun ageRange(low: Int, high: Int): String = "$low to $high"
    fun yourAge(age: Int): String = "You, $age"
}
