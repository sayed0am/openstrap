package org.openstrap.app.ui.dashboard

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import org.openstrap.app.data.Recovery
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/*
 * The words on Today's hero and its small facts (spec 4.2 and 7). Built only from numbers the app already has: where
 * a number is missing the sentence says what it waits for, and nothing is guessed.
 */
object TodayCopy {
    /** A length of time the way Today says it: "40 min", "7 h 40", "8 h". */
    fun duration(seconds: Long): String {
        val minutes = seconds.coerceAtLeast(0) / 60
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> tr(R.string.missed_duration_min, m)
            m == 0L -> tr(R.string.missed_duration_h, h)
            else -> tr(R.string.missed_duration_h_m, h, "%02d".format(m))
        }
    }

    /** A value against your normal, from [org.openstrap.app.data.History.status]: null is no normal yet. */
    fun normalWord(direction: Int?): String = when {
        direction == null -> tr(R.string.today_copy_learning_your_normal)
        direction > 0 -> tr(R.string.common_above_your_normal)
        direction < 0 -> tr(R.string.common_below_your_normal)
        else -> tr(R.string.common_usual)
    }

    /** What a Recovery score means for the day, in a sentence. */
    fun recoveryMeaning(score: Int, alert: Boolean): String = when {
        alert -> tr(R.string.today_copy_some_overnight_signals_are)
        score >= 85 -> tr(R.string.today_copy_well_recovered_a_good)
        score >= 70 -> tr(R.string.today_copy_recovered_enough_for_your)
        score >= Recovery.FAIR_FROM -> tr(R.string.today_copy_not_fully_recovered_keep)
        else -> tr(R.string.today_copy_still_catching_up_an)
    }

    /** The status word beside the score. */
    fun recoveryWord(score: Int, alert: Boolean): String = when {
        alert -> tr(R.string.common_take_it_easy)
        score >= 85 -> tr(R.string.common_ready_for_more)
        score >= 70 -> tr(R.string.common_ready)
        score >= Recovery.FAIR_FROM -> tr(R.string.common_moderate)
        else -> tr(R.string.common_take_it_easy)
    }

    /**
     * Why there's no Recovery score yet. [Learning] counts the nights OpenStrap has toward its first score ([nights] of
     * [of]); [NoHeartData] is a night without the HRV or resting heart rate a score needs.
     */
    sealed interface NoScore {
        data class Learning(val nights: Int, val of: Int) : NoScore
        data object NoHeartData : NoScore
    }

    /** "OpenStrap is still learning your nights, 5 of 7." and the like. */
    fun noScoreLine(why: NoScore): String = when (why) {
        is NoScore.Learning -> tr(R.string.today_copy_openstrap_is_still_learning, why.nights, why.of)
        NoScore.NoHeartData -> tr(R.string.today_copy_not_enough_heart_data)
    }

    /**
     * The waking hero (spec 4.2, 4.3): how long you slept, then what Recovery says, or why there's no score yet and the
     * strap's own [estimate] when there is one. [firstNights] is before the strap has seen any sleep.
     */
    fun waking(asleepSeconds: Long?, score: Int?, alert: Boolean, noScore: NoScore?, firstNights: Boolean, isToday: Boolean): String {
        if (firstNights) return RECOVERY_FIRST_NIGHT
        if (asleepSeconds == null) return if (isToday) NO_SLEEP else tr(R.string.common_no_sleep_recorded_that)
        val slept = tr(R.string.today_copy_slept, duration(asleepSeconds))
        return when {
            score != null -> "$slept ${recoveryMeaning(score, alert)}"
            // The strap's estimate stands under the sentence with its label, so the sentence doesn't repeat it.
            noScore != null -> "$slept ${noScoreLine(noScore)}"
            else -> slept
        }
    }

    /** The Recovery heroes before the strap has seen a night (spec 4.3). */
    val RECOVERY_FIRST_NIGHT: String get() = tr(R.string.today_copy_recovery_starts_after_your)

    /** Today's waking hero with no sleep recorded last night, and the Sleep tab's night (spec 4.3). */
    val NO_SLEEP: String get() = tr(R.string.today_copy_no_sleep_recorded_last)

    /** What a Recovery card shows: the number (null for a dash), the word beside it and the line under it. */
    data class RecoveryShown(val value: Int?, val word: String?, val line: String, val estimate: Boolean = false)

    /**
     * Recovery as Today shows it everywhere (spec 4.3): OpenStrap's score, else the strap's [estimate] labelled as its
     * own, else why there's none, else [waiting].
     */
    fun recoveryShown(score: Int?, alert: Boolean, noScore: NoScore?, estimate: Int?, waiting: String): RecoveryShown = when {
        score != null -> RecoveryShown(score, recoveryWord(score, alert), recoveryMeaning(score, alert))
        estimate != null -> RecoveryShown(estimate, tr(R.string.common_strap_s_estimate), noScore?.let(::noScoreLine) ?: tr(R.string.today_copy_openstrap_s_own_score), estimate = true)
        noScore != null -> RecoveryShown(null, null, noScoreLine(noScore))
        else -> RecoveryShown(null, null, waiting)
    }

    /** Why OpenStrap has no score: still learning comes first, since a learning night has no baseline to score against. */
    fun noScore(r: Recovery.Readiness?): NoScore? = when {
        r == null || r.score != null -> null
        r.phase == Recovery.Phase.Withheld -> NoScore.Learning(r.baselineNights, Recovery.SCORED_NIGHTS)
        r.withheld -> NoScore.NoHeartData
        else -> null
    }

    /**
     * [recoveryShown] from the day's [r], so Today, the Recovery tab and All metrics show the same value: the strap's
     * [strapScore] stands in, as its estimate, while OpenStrap's own score is withheld.
     */
    fun recoveryShown(r: Recovery.Readiness?, strapScore: Int?, waiting: String): RecoveryShown {
        val estimate = r?.takeIf { it.score == null && it.showStrapEstimate }?.let { strapScore }
        return recoveryShown(r?.score, r?.strainAlert == true, noScore(r), estimate, waiting)
    }

    /**
     * The Charge hero at midday: what the day has [drained] since waking (all of it, as Drained says on the card and
     * the Recovery tab, not just the drop since waking) and what's left for the evening. [hour] is the clock's hour,
     * so the morning is called the morning.
     */
    fun charge(now: Int?, drained: Int?, anchored: Boolean, hour: Int): String {
        if (now == null || !anchored) return tr(R.string.common_energy_starts_after_your)
        val part = if (hour < 12) tr(R.string.today_copy_the_morning) else tr(R.string.today_copy_the_day_so_far)
        val first = when {
            drained == null -> null
            drained >= 1 -> tr(R.string.today_copy_drained, part, drained)
            else -> tr(R.string.today_copy_nothing_drained_since_waking)
        }
        val left = when {
            now >= 50 -> tr(R.string.today_copy_plenty_left_for_this)
            now >= 25 -> tr(R.string.today_copy_enough_left_for_an)
            else -> tr(R.string.today_copy_running_low_an_early)
        }
        return listOfNotNull(first, left).joinToString(" ")
    }

    /** The workout window hero's title: what Recovery says about effort now. */
    fun windowTitle(score: Int?): String = when {
        score == null -> tr(R.string.today_copy_your_usual_workout_time)
        score >= 70 -> tr(R.string.today_copy_ready_for_effort)
        score >= Recovery.FAIR_FROM -> tr(R.string.today_copy_fine_for_a_steady)
        else -> tr(R.string.today_copy_an_easy_session_suits)
    }

    /** "You usually run around now. Energy is 58, plenty left." [sport] is a short noun that is also a verb. */
    fun window(sport: String?, charge: Int?): String {
        val usual = tr(R.string.today_copy_you_usually_around_now, sport ?: tr(R.string.today_copy_you_usually_around_now__work_out))
        val left = charge?.let {
            tr(R.string.today_copy_energy_is, it) + when {
                it >= 50 -> tr(R.string.today_copy_plenty_left)
                it >= 25 -> tr(R.string.today_copy_enough_for_something_easy)
                else -> tr(R.string.today_copy_running_low)
            }
        }
        return usual + left.orEmpty()
    }

    /** After a workout: how the heart is coming down, from the latest reading against resting. */
    fun after(nowHr: Int?, resting: Int?): String = when {
        nowHr == null -> tr(R.string.today_copy_heart_rate_since_the)
        resting == null -> tr(R.string.today_copy_heart_rate_now, nowHr)
        nowHr <= resting + SETTLED_BPM -> tr(R.string.today_copy_heart_rate_back_near)
        else -> tr(R.string.today_copy_heart_rate_settling)
    }

    /** Within this many bpm of resting, the heart has settled. */
    const val SETTLED_BPM = 10

    /** How a Load compares with your usual. */
    enum class LoadStatus(val text: String) {
        Above(tr(R.string.common_above_your_usual)), Usual(tr(R.string.common_usual)), Below(tr(R.string.common_below_your_usual)), SoFar(tr(R.string.common_so_far_today))
    }

    /**
     * [load] against your [usual] daily Load. Before the evening a low Load is only "so far today": compared with a
     * whole usual day it would always look low (spec 2.7, compare by pace). Null before there's a usual.
     */
    fun loadStatus(load: Float, usual: Float?, endOfDay: Boolean): LoadStatus? {
        if (usual == null || usual <= 0f) return null
        return when {
            load > usual * (1 + LOAD_BAND) -> LoadStatus.Above
            !endOfDay -> LoadStatus.SoFar
            load < usual * (1 - LOAD_BAND) -> LoadStatus.Below
            else -> LoadStatus.Usual
        }
    }

    /** A Load within this share of your usual counts as usual. */
    const val LOAD_BAND = 0.25f

    /**
     * The evening hero: what kind of day it was, then what made it. [workouts] are the day's workouts as short nouns
     * (null where none reads well). Without [stepCount] the steps are said against the goal ([stepsAgainstGoal]), for
     * the app's hero, which shows the count in dots under the sentence; the widget keeps the count.
     */
    fun evening(
        workouts: List<String?>, steps: Int, stepGoal: Int, load: LoadStatus?, locale: Locale = Locale.getDefault(), stepCount: Boolean = true,
    ): String {
        val head = when {
            workouts.isNotEmpty() && (steps >= stepGoal || load == LoadStatus.Above) -> tr(R.string.today_copy_a_full_day)
            workouts.isEmpty() && steps < stepGoal / 2 -> tr(R.string.today_copy_a_quiet_day)
            else -> tr(R.string.today_copy_a_steady_day)
        }
        val parts = listOfNotNull(
            when (workouts.size) {
                0 -> null
                1 -> tr(R.string.missed_a_workout, workouts[0] ?: tr(R.string.frame_after_your__workout))
                else -> tr(R.string.missed_n_workouts, workouts.size)
            },
            if (stepCount || stepGoal <= 0) trRaw(R.string.today_copy_steps_2).format(locale, steps) else stepsAgainstGoal(steps, stepGoal),
            when (load) {
                LoadStatus.Above -> tr(R.string.today_copy_a_load_above_your)
                LoadStatus.Below -> tr(R.string.today_copy_a_load_below_your)
                LoadStatus.Usual -> tr(R.string.today_copy_a_usual_load)
                LoadStatus.SoFar, null -> null
            },
        )
        val list = if (parts.size == 1) parts[0] else tr(R.string.missed_list_and, parts.dropLast(1).joinToString(trRaw(R.string.missed_list_separator)), parts.last())
        return tr(R.string.missed_head_and_list, head, list.replaceFirstChar { it.uppercase() })
    }

    /** The day's [steps] against the [goal] in words: under half is well short, from 80% close, the goal itself past. */
    fun stepsAgainstGoal(steps: Int, goal: Int): String = when {
        steps >= goal -> tr(R.string.today_copy_past_your_step_goal)
        steps >= goal * 0.8 -> tr(R.string.today_copy_close_to_your_step)
        steps >= goal / 2 -> tr(R.string.today_copy_short_of_your_step)
        else -> tr(R.string.today_copy_well_short_of_your)
    }

    /** "Bedtime in 1 h 10" until your usual [bedtime], read on a clock that starts at noon; null once it's passed. */
    fun bedtimeIn(now: LocalTime, bedtime: LocalTime?): String? {
        bedtime ?: return null
        val minutes = afterNoon(bedtime) - afterNoon(now)
        return if (minutes <= 0) null else tr(R.string.today_copy_bedtime_in, duration(minutes * 60L))
    }

    private fun afterNoon(t: LocalTime): Int = Math.floorMod(t.toSecondOfDay() / 60 - 720, 1440)

    /** Seconds still to recover from a workout that ended at [end] and needs [minutes], at [now]. */
    fun recoverLeft(end: Long, minutes: Int, now: Long): Long = (end + minutes * 60L - now).coerceAtLeast(0)

    /**
     * Time to recover as a value and a status: "14" and "Hours left, ready tomorrow", "2" and "Hours left, ready by
     * 20:30". Recovered has no number, only the word (null value).
     */
    fun recoverText(left: Long, now: Long, zone: ZoneId): Pair<String?, String> {
        if (left <= 0) return null to tr(R.string.common_recovered)
        val hours = (left + 3599) / 3600
        val ready = Instant.ofEpochSecond(now + left).atZone(zone)
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val unit = if (hours == 1L) tr(R.string.today_copy_hour_left) else tr(R.string.today_copy_hours_left)
        val status = when (ready.toLocalDate()) {
            today -> tr(R.string.today_copy_ready_by, ready.format(DateTimeFormatter.ofPattern("HH:mm")))
            today.plusDays(1) -> tr(R.string.today_copy_ready_tomorrow)
            else -> tr(R.string.missed_ready_weekday, ready.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()))
        }
        return "$hours" to "$unit, $status"
    }

    /** The line under Load today's number, which says it's a Load and how it compares. */
    fun loadLine(status: LoadStatus?): String = when (status) {
        null -> tr(R.string.today_copy_load_learning_your_usual)
        LoadStatus.Above -> tr(R.string.today_copy_load_above_your_usual)
        LoadStatus.Usual -> tr(R.string.today_copy_your_usual_load)
        LoadStatus.Below -> tr(R.string.today_copy_load_below_your_usual)
        LoadStatus.SoFar -> tr(R.string.today_copy_load_so_far_today)
    }

    /** Under Your week before there's a week before it to compare with. */
    val WEEK_FIRST: String get() = tr(R.string.today_copy_week_on_week_comparisons)

    /** The week Your week tells (spec 4.2): Monday to Sunday, the last full week before [date]. */
    fun lastWeek(date: LocalDate): ClosedRange<LocalDate> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
        return start..start.plusDays(6)
    }

    /** "Steps up 12%" and the like, comparing two weeks; null without both. */
    fun stepsChange(now: Double?, before: Double?): String? {
        if (now == null || before == null || before <= 0) return null
        val pct = Math.round((now - before) / before * 100).toInt()
        return if (pct == 0) tr(R.string.today_copy_steps_held_steady) else tr(R.string.today_copy_steps, if (pct > 0) tr(R.string.today_copy_steps__up) else tr(R.string.today_copy_steps__down), abs(pct))
    }

    /** The Last night strip: "7 h 40, score 83". */
    fun lastNight(asleepSeconds: Long?, score: Int?): String? = asleepSeconds?.let {
        duration(it) + (score?.let { s -> tr(R.string.today_copy_score, s) } ?: "")
    }

    /** A Small's word for Load against your usual: the [LoadStatus]'s, or that the usual is still being learned. */
    fun loadWord(status: LoadStatus?): String = status?.text ?: tr(R.string.common_learning_your_usual)

    /**
     * Time to recover as a Small says it: the hours as the value and when you're ready as the word. "14" and "Ready
     * tomorrow", "2" and "Ready by 20:30". Recovered has no number, only the word.
     */
    fun recoverShort(left: Long, now: Long, zone: ZoneId): Pair<String?, String> {
        val (value, status) = recoverText(left, now, zone)
        if (value == null) return null to status
        return value to status.substringAfter(", ").replaceFirstChar { it.uppercase() }
    }

    /** How to read the heart rate line, with [band] when your resting range is drawn behind it. */
    fun heartLineNote(band: Boolean): String =
        if (band) tr(R.string.today_copy_hold_to_scrub_the) else tr(R.string.today_copy_hold_to_scrub_the_2)

    /** The heart rate card's line under its number: live, or when the reading shown was taken. */
    fun heartAt(live: Boolean, at: String?): String = when {
        live -> tr(R.string.today_copy_live_now)
        at != null -> tr(R.string.today_copy_at, at)
        else -> tr(R.string.common_waiting_for_the_strap)
    }
}
