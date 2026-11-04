package org.openstrap.app.presentation

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import org.openstrap.app.R
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.Weeks
import org.openstrap.app.i18n.Strings

/*
 * The words on Today's hero and its small facts (spec 4.2 and 7). Built only from numbers the app already has: where
 * a number is missing the sentence says what it waits for, and nothing is guessed. The text is in
 * res/values/strings_core_today.xml.
 */
object TodayCopy {
    /** A value against your normal, from [org.openstrap.app.data.History.status]: null is no normal yet. */
    fun normalWord(direction: Int?, strings: Strings = Strings.app): String = strings.get(
        when {
            direction == null -> R.string.core_today_normal_learning
            direction > 0 -> R.string.core_today_normal_above
            direction < 0 -> R.string.core_today_normal_below
            else -> R.string.core_today_normal_usual
        },
    )

    /** What a Recovery score means for the day, in a sentence. */
    fun recoveryMeaning(score: Int, alert: Boolean, strings: Strings = Strings.app): String = strings.get(
        when {
            alert -> R.string.core_today_meaning_alert
            score >= RecoveryBands.READY_FOR_MORE_FROM -> R.string.core_today_meaning_ready_for_more
            score >= RecoveryBands.READY_FROM -> R.string.core_today_meaning_ready
            score >= RecoveryBands.MODERATE_FROM -> R.string.core_today_meaning_moderate
            else -> R.string.core_today_meaning_low
        },
    )

    /** The status word beside the score. */
    fun recoveryWord(score: Int, alert: Boolean): String = RecoveryBands.word(score, alert)

    /**
     * Why there's no Recovery score yet. [Learning] counts the nights OpenStrap has toward its first score ([nights] of
     * [of]); [NoHeartData] is a night without the HRV or resting heart rate a score needs.
     */
    sealed interface NoScore {
        data class Learning(val nights: Int, val of: Int) : NoScore
        data object NoHeartData : NoScore
    }

    /** "OpenStrap is still learning your nights, 5 of 7." and the like. */
    fun noScoreLine(why: NoScore, strings: Strings = Strings.app): String = when (why) {
        is NoScore.Learning -> strings.format(R.string.core_today_no_score_learning, why.nights, why.of)
        NoScore.NoHeartData -> strings.get(R.string.core_today_no_score_heart)
    }

    /**
     * The waking hero (spec 4.2, 4.3): how long you slept, then what Recovery says, or why there's no score yet and the
     * strap's own [estimate] when there is one. [firstNights] is before the strap has seen any sleep. [deleted] is a
     * night the user deleted, which never asks whether the strap was on.
     */
    fun waking(
        asleepSeconds: Long?, score: Int?, alert: Boolean, noScore: NoScore?, firstNights: Boolean, isToday: Boolean,
        deleted: Boolean = false, strings: Strings = Strings.app,
    ): String {
        if (firstNights) return strings.get(R.string.core_today_recovery_first_night)
        if (asleepSeconds == null && deleted) return deletedNight(isToday, strings)
        if (asleepSeconds == null) {
            return strings.get(if (isToday) R.string.core_today_no_sleep else R.string.core_today_no_sleep_that_night)
        }
        val slept = strings.format(R.string.core_today_slept, Units.duration(asleepSeconds))
        return when {
            score != null -> "$slept ${recoveryMeaning(score, alert, strings)}"
            // The strap's estimate stands under the sentence with its label, so the sentence doesn't repeat it.
            noScore != null -> "$slept ${noScoreLine(noScore, strings)}"
            else -> slept
        }
    }

    /** The Recovery heroes before the strap has seen a night (spec 4.3). */
    val RECOVERY_FIRST_NIGHT: String get() = Strings.app.get(R.string.core_today_recovery_first_night)

    /** Today's waking hero with no sleep recorded last night, and the Sleep tab's night (spec 4.3). */
    val NO_SLEEP: String get() = Strings.app.get(R.string.core_today_no_sleep)

    /** In [NO_SLEEP]'s place when the user deleted the night themselves (sleep edit spec 4.3). */
    fun deletedNight(isToday: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (isToday) R.string.core_today_deleted_last_night else R.string.core_today_deleted_that_night)

    /** What a Recovery card shows: the number (null for a dash), the word beside it and the line under it. */
    data class RecoveryShown(val value: Int?, val word: String?, val line: String, val estimate: Boolean = false)

    /**
     * Recovery as Today shows it everywhere (spec 4.3): OpenStrap's score, else the strap's [estimate] labelled as its
     * own, else why there's none, else [waiting].
     */
    fun recoveryShown(
        score: Int?, alert: Boolean, noScore: NoScore?, estimate: Int?, waiting: String, strings: Strings = Strings.app,
    ): RecoveryShown = when {
        score != null -> RecoveryShown(score, recoveryWord(score, alert), recoveryMeaning(score, alert, strings))
        estimate != null -> RecoveryShown(
            estimate,
            strings.get(R.string.core_today_strap_estimate),
            noScore?.let { noScoreLine(it, strings) } ?: strings.get(R.string.core_today_own_score_not_ready),
            estimate = true,
        )
        noScore != null -> RecoveryShown(null, null, noScoreLine(noScore, strings))
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
    fun recoveryShown(r: Recovery.Readiness?, strapScore: Int?, waiting: String, strings: Strings = Strings.app): RecoveryShown {
        val estimate = r?.takeIf { it.score == null && it.showStrapEstimate }?.let { strapScore }
        return recoveryShown(r?.score, r?.strainAlert == true, noScore(r), estimate, waiting, strings)
    }

    /**
     * The Charge hero at midday: what the day has [drained] since waking (all of it, as Drained says on the card and
     * the Recovery tab, not just the drop since waking) and what's left for the evening. [hour] is the clock's hour,
     * so the morning is called the morning.
     */
    fun charge(now: Int?, drained: Int?, anchored: Boolean, hour: Int, strings: Strings = Strings.app): String {
        if (now == null || !anchored) return strings.get(R.string.core_today_energy_first_night)
        val first = when {
            drained == null -> null
            drained >= 1 -> strings.format(
                if (hour < 12) R.string.core_today_energy_morning_drained else R.string.core_today_energy_day_drained,
                drained,
            )
            else -> strings.get(R.string.core_today_energy_nothing_drained)
        }
        val left = strings.get(
            when {
                now >= 50 -> R.string.core_today_energy_plenty
                now >= 25 -> R.string.core_today_energy_enough
                else -> R.string.core_today_energy_low
            },
        )
        return listOfNotNull(first, left).joinToString(" ")
    }

    /** The workout window hero's title: what Recovery says about effort now. */
    fun windowTitle(score: Int?, strings: Strings = Strings.app): String = strings.get(
        when {
            score == null -> R.string.core_today_window_title_usual
            score >= RecoveryBands.READY_FROM -> R.string.core_today_window_title_ready
            score >= RecoveryBands.MODERATE_FROM -> R.string.core_today_window_title_steady
            else -> R.string.core_today_window_title_easy
        },
    )

    /** "You usually run around now. Energy is 58, plenty left." [sportType] is the usual sport, null for none. */
    fun window(sportType: Int?, charge: Int?, strings: Strings = Strings.app): String {
        val usual = sportUsually(sportType, strings)
        val left = charge?.let {
            val id = when {
                it >= 50 -> R.string.core_today_window_energy_plenty
                it >= 25 -> R.string.core_today_window_energy_enough
                else -> R.string.core_today_window_energy_low
            }
            " " + strings.format(id, it)
        }
        return usual + left.orEmpty()
    }

    /** After a workout: how the heart is coming down, from the latest reading against resting. */
    fun after(nowHr: Int?, resting: Int?, strings: Strings = Strings.app): String = when {
        nowHr == null -> strings.get(R.string.core_today_after_waiting)
        resting == null -> strings.format(R.string.core_today_after_now, nowHr)
        nowHr <= resting + SETTLED_BPM -> strings.get(R.string.core_today_after_settled)
        else -> strings.get(R.string.core_today_after_settling)
    }

    /** Within this many bpm of resting, the heart has settled. */
    const val SETTLED_BPM = 10

    /**
     * [load] against your [usual] daily Load by [LoadBands]. Before the evening a low Load is only "so far today":
     * compared with a whole usual day it would always look low (spec 2.7, compare by pace). Null before there's a usual.
     */
    fun loadStatus(load: Float, usual: Float?, endOfDay: Boolean): LoadBand? = LoadBands.of(load, usual, endOfDay)

    /**
     * The evening hero: what kind of day it was, then what made it. [workouts] are the sport types of the day's
     * workouts. Without [stepCount] the steps are said against the goal ([stepsAgainstGoal]), for
     * the app's hero, which shows the count in dots under the sentence; the widget keeps the count.
     */
    fun evening(
        workouts: List<Int>, steps: Int, stepGoal: Int, load: LoadBand?, locale: Locale = Locale.getDefault(), stepCount: Boolean = true,
        strings: Strings = Strings.app,
    ): String {
        val head = when {
            workouts.isNotEmpty() && (steps >= stepGoal || load == LoadBand.Above) -> R.string.core_today_evening_full
            workouts.isEmpty() && steps < stepGoal / 2 -> R.string.core_today_evening_quiet
            else -> R.string.core_today_evening_steady
        }
        val parts = listOfNotNull(
            when (workouts.size) {
                0 -> null
                1 -> sportOne(workouts[0], strings)
                else -> strings.plural(R.plurals.core_today_evening_workouts, workouts.size, workouts.size)
            },
            if (stepCount || stepGoal <= 0) {
                strings.plural(R.plurals.core_today_evening_steps, steps, Units.count(steps.toLong(), locale))
            } else {
                stepsAgainstGoal(steps, stepGoal, strings)
            },
            when (load) {
                LoadBand.Above -> strings.get(R.string.core_today_evening_load_above)
                LoadBand.Below -> strings.get(R.string.core_today_evening_load_below)
                LoadBand.Usual -> strings.get(R.string.core_today_evening_load_usual)
                LoadBand.SoFar, null -> null
            },
        )
        val list = if (parts.size == 1) {
            parts[0]
        } else {
            val lead = parts.dropLast(1).reduce { a, b -> strings.format(R.string.core_today_list_comma, a, b) }
            strings.format(R.string.core_today_list_and, lead, parts.last())
        }
        return strings.format(head, list.replaceFirstChar { it.uppercase() })
    }

    /** The day's [steps] against the [goal] in words: under half is well short, from 80% close, the goal itself past. */
    fun stepsAgainstGoal(steps: Int, goal: Int, strings: Strings = Strings.app): String = strings.get(
        when {
            steps >= goal -> R.string.core_today_goal_past
            steps >= goal * 0.8 -> R.string.core_today_goal_close
            steps >= goal / 2 -> R.string.core_today_goal_short
            else -> R.string.core_today_goal_well_short
        },
    )

    /** "Bedtime in 1 h 10" until your usual [bedtime], read on a clock that starts at noon; null once it's passed. */
    fun bedtimeIn(now: LocalTime, bedtime: LocalTime?, strings: Strings = Strings.app): String? {
        bedtime ?: return null
        val minutes = afterNoon(bedtime) - afterNoon(now)
        return if (minutes <= 0) null else strings.format(R.string.core_today_bedtime_in, Units.duration(minutes * 60L))
    }

    private fun afterNoon(t: LocalTime): Int = Math.floorMod(t.toSecondOfDay() / 60 - 720, 1440)

    /** Seconds still to recover from a workout that ended at [end] and needs [minutes], at [now]. */
    fun recoverLeft(end: Long, minutes: Int, now: Long): Long = (end + minutes * 60L - now).coerceAtLeast(0)

    /**
     * Time to recover as a value and a status: "14" and "Hours left, ready tomorrow", "2" and "Hours left, ready by
     * 20:30". Recovered has no number, only the word (null value).
     */
    fun recoverText(
        left: Long, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): Pair<String?, String> = recover(left, now, zone, locale, short = false, strings)

    /**
     * When you're ready, written whole either way, so each language words both forms: with the hours left as the
     * status of [recoverText] ("Hours left, ready by 20:30"), or alone as [recoverShort] says it ("Ready by 20:30").
     */
    private fun recover(left: Long, now: Long, zone: ZoneId, locale: Locale, short: Boolean, strings: Strings): Pair<String?, String> {
        if (left <= 0) return null to strings.get(R.string.core_today_recovered)
        val hours = (left + 3599) / 3600
        val ready = Instant.ofEpochSecond(now + left).atZone(zone)
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val n = hours.toInt()
        val status = when (ready.toLocalDate()) {
            today -> TimeOfDay.format(now + left, zone, locale = locale).let {
                if (short) {
                    strings.format(R.string.core_today_recover_short_by, it)
                } else {
                    strings.plural(R.plurals.core_today_recover_by, n, it)
                }
            }
            today.plusDays(1) -> if (short) {
                strings.get(R.string.core_today_recover_short_tomorrow)
            } else {
                strings.plural(R.plurals.core_today_recover_tomorrow, n)
            }
            else -> ready.dayOfWeek.getDisplayName(TextStyle.FULL, locale).let {
                if (short) {
                    strings.format(R.string.core_today_recover_short_day, it)
                } else {
                    strings.plural(R.plurals.core_today_recover_day, n, it)
                }
            }
        }
        return "$hours" to status
    }

    /** The line under Load today's number, which says it's a Load and how it compares. */
    fun loadLine(status: LoadBand?, strings: Strings = Strings.app): String = strings.get(
        when (status) {
            null -> R.string.core_today_load_line_learning
            LoadBand.Above -> R.string.core_today_load_line_above
            LoadBand.Usual -> R.string.core_today_load_line_usual
            LoadBand.Below -> R.string.core_today_load_line_below
            LoadBand.SoFar -> R.string.core_today_load_line_so_far
        },
    )

    /** Under Your week before there's a week before it to compare with: the week starts on the locale's first day. */
    val WEEK_FIRST: String get() = weekFirst()

    /** [WEEK_FIRST] in [locale]: "Week on week comparisons start next Monday." */
    fun weekFirst(locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        strings.format(R.string.core_today_week_first, Weeks.firstDay(locale).getDisplayName(TextStyle.FULL, locale))

    /** The week Your week tells (spec 4.2): the last full week before [date], from the locale's first day ([Weeks]). */
    fun lastWeek(date: LocalDate, firstDay: java.time.DayOfWeek = Weeks.firstDay()): ClosedRange<LocalDate> {
        val start = Weeks.start(date, firstDay).minusWeeks(1)
        return start..Weeks.end(start, firstDay)
    }

    /** The week's steps against the week before, in whole percent (12 up, -3 down, 0 the same); null without both. */
    fun stepsPercent(now: Double?, before: Double?): Int? {
        if (now == null || before == null || before <= 0) return null
        val pct = Math.round((now - before) / before * 100).toInt()
        return pct
    }

    /** "Steps up 12%" and the like, comparing two weeks; null without both. */
    fun stepsChange(now: Double?, before: Double?, strings: Strings = Strings.app): String? {
        val pct = stepsPercent(now, before) ?: return null
        return when {
            pct == 0 -> strings.get(R.string.core_today_steps_steady)
            pct > 0 -> strings.format(R.string.core_today_steps_up, abs(pct))
            else -> strings.format(R.string.core_today_steps_down, abs(pct))
        }
    }

    /** The Today week card's steps line: "12% more steps than the week before."; null without both weeks. */
    fun weekStepsLine(now: Double?, before: Double?, strings: Strings = Strings.app): String? {
        val pct = stepsPercent(now, before) ?: return null
        return when {
            pct == 0 -> strings.get(R.string.glow_today_week_steps_same)
            pct > 0 -> strings.format(R.string.glow_today_week_steps_more, abs(pct))
            else -> strings.format(R.string.glow_today_week_steps_fewer, abs(pct))
        }
    }

    /** The Last night strip: "7 h 40, score 83". */
    fun lastNight(asleepSeconds: Long?, score: Int?, strings: Strings = Strings.app): String? = asleepSeconds?.let {
        val d = Units.duration(it)
        score?.let { s -> strings.format(R.string.core_today_last_night_score, d, s) } ?: d
    }

    /** A Small's word for Load against your usual: the [LoadBand]'s, or that the usual is still being learned. */
    fun loadWord(status: LoadBand?, strings: Strings = Strings.app): String =
        status?.text ?: strings.get(R.string.core_today_load_word_learning)

    /**
     * Time to recover as a Small says it: the hours as the value and when you're ready as the word. "14" and "Ready
     * tomorrow", "2" and "Ready by 20:30". Recovered has no number, only the word.
     */
    fun recoverShort(left: Long, now: Long, zone: ZoneId, strings: Strings = Strings.app): Pair<String?, String> =
        recover(left, now, zone, Locale.getDefault(), short = true, strings)

    /** How to read the heart rate line, with [band] when your resting range is drawn behind it. */
    fun heartLineNote(band: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (band) R.string.core_today_heart_note_band else R.string.core_today_heart_note)

    /** The heart rate card's line under its number: live, or when the reading shown was taken. */
    fun heartAt(live: Boolean, at: String?, strings: Strings = Strings.app): String = when {
        live -> strings.get(R.string.core_today_heart_live)
        at != null -> strings.format(R.string.core_today_heart_at, at)
        else -> strings.get(R.string.core_today_heart_waiting)
    }
}
