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
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h %02d".format(m)
        }
    }

    /** A value against your normal, from [org.openstrap.app.data.History.status]: null is no normal yet. */
    fun normalWord(direction: Int?): String = when {
        direction == null -> "Learning your normal"
        direction > 0 -> "Above your normal"
        direction < 0 -> "Below your normal"
        else -> "Usual"
    }

    /** What a Recovery score means for the day, in a sentence. */
    fun recoveryMeaning(score: Int, alert: Boolean): String = when {
        alert -> "Some overnight signals are off your normal. An easy day suits."
        score >= 85 -> "Well recovered. A good day for a hard session."
        score >= 70 -> "Recovered enough for your usual training."
        score >= Recovery.FAIR_FROM -> "Not fully recovered. Keep the effort moderate."
        else -> "Still catching up. An easy day helps."
    }

    /** The status word beside the score. */
    fun recoveryWord(score: Int, alert: Boolean): String = when {
        alert -> "Take it easy"
        score >= 85 -> "Ready for more"
        score >= 70 -> "Ready"
        score >= Recovery.FAIR_FROM -> "Moderate"
        else -> "Take it easy"
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
        is NoScore.Learning -> "OpenStrap is still learning your nights, ${why.nights} of ${why.of}."
        NoScore.NoHeartData -> "Not enough heart data last night for a Recovery score."
    }

    /**
     * The waking hero (spec 4.2, 4.3): how long you slept, then what Recovery says, or why there's no score yet and the
     * strap's own [estimate] when there is one. [firstNights] is before the strap has seen any sleep. [deleted] is a
     * night the user deleted, which never asks whether the strap was on.
     */
    fun waking(asleepSeconds: Long?, score: Int?, alert: Boolean, noScore: NoScore?, firstNights: Boolean, isToday: Boolean, deleted: Boolean = false): String {
        if (firstNights) return RECOVERY_FIRST_NIGHT
        if (asleepSeconds == null && deleted) return deletedNight(isToday)
        if (asleepSeconds == null) return if (isToday) NO_SLEEP else "No sleep recorded that night."
        val slept = "Slept ${duration(asleepSeconds)}."
        return when {
            score != null -> "$slept ${recoveryMeaning(score, alert)}"
            // The strap's estimate stands under the sentence with its label, so the sentence doesn't repeat it.
            noScore != null -> "$slept ${noScoreLine(noScore)}"
            else -> slept
        }
    }

    /** The Recovery heroes before the strap has seen a night (spec 4.3). */
    const val RECOVERY_FIRST_NIGHT = "Recovery starts after your first night with the strap."

    /** Today's waking hero with no sleep recorded last night, and the Sleep tab's night (spec 4.3). */
    const val NO_SLEEP = "No sleep recorded last night. Was the strap on?"

    /** In [NO_SLEEP]'s place when the user deleted the night themselves (sleep edit spec 4.3). */
    fun deletedNight(isToday: Boolean): String = if (isToday) "You deleted last night's sleep." else "You deleted that night's sleep."

    /** What a Recovery card shows: the number (null for a dash), the word beside it and the line under it. */
    data class RecoveryShown(val value: Int?, val word: String?, val line: String, val estimate: Boolean = false)

    /**
     * Recovery as Today shows it everywhere (spec 4.3): OpenStrap's score, else the strap's [estimate] labelled as its
     * own, else why there's none, else [waiting].
     */
    fun recoveryShown(score: Int?, alert: Boolean, noScore: NoScore?, estimate: Int?, waiting: String): RecoveryShown = when {
        score != null -> RecoveryShown(score, recoveryWord(score, alert), recoveryMeaning(score, alert))
        estimate != null -> RecoveryShown(estimate, "Strap's estimate", noScore?.let(::noScoreLine) ?: "OpenStrap's own score isn't ready yet.", estimate = true)
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
        if (now == null || !anchored) return "Energy starts after your first night. Sleep is what fills it."
        val part = if (hour < 12) "The morning" else "The day so far"
        val first = when {
            drained == null -> null
            drained >= 1 -> "$part drained $drained."
            else -> "Nothing drained since waking."
        }
        val left = when {
            now >= 50 -> "Plenty left for this evening."
            now >= 25 -> "Enough left for an easy evening."
            else -> "Running low. An early night helps."
        }
        return listOfNotNull(first, left).joinToString(" ")
    }

    /** The workout window hero's title: what Recovery says about effort now. */
    fun windowTitle(score: Int?): String = when {
        score == null -> "Your usual workout time"
        score >= 70 -> "Ready for effort"
        score >= Recovery.FAIR_FROM -> "Fine for a steady session"
        else -> "An easy session suits today"
    }

    /** "You usually run around now. Energy is 58, plenty left." [sport] is a short noun that is also a verb. */
    fun window(sport: String?, charge: Int?): String {
        val usual = "You usually ${sport ?: "work out"} around now."
        val left = charge?.let {
            " Energy is $it, " + when {
                it >= 50 -> "plenty left."
                it >= 25 -> "enough for something easy."
                else -> "running low."
            }
        }
        return usual + left.orEmpty()
    }

    /** After a workout: how the heart is coming down, from the latest reading against resting. */
    fun after(nowHr: Int?, resting: Int?): String = when {
        nowHr == null -> "Heart rate since the end shows once the strap sends it."
        resting == null -> "Heart rate $nowHr now."
        nowHr <= resting + SETTLED_BPM -> "Heart rate back near resting."
        else -> "Heart rate settling."
    }

    /** Within this many bpm of resting, the heart has settled. */
    const val SETTLED_BPM = 10

    /** How a Load compares with your usual. */
    enum class LoadStatus(val text: String) {
        Above("Above your usual"), Usual("Usual"), Below("Below your usual"), SoFar("So far today")
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
            workouts.isNotEmpty() && (steps >= stepGoal || load == LoadStatus.Above) -> "A full day."
            workouts.isEmpty() && steps < stepGoal / 2 -> "A quiet day."
            else -> "A steady day."
        }
        val parts = listOfNotNull(
            when (workouts.size) {
                0 -> null
                1 -> "a ${workouts[0] ?: "workout"}"
                else -> "${workouts.size} workouts"
            },
            if (stepCount || stepGoal <= 0) "%,d steps".format(locale, steps) else stepsAgainstGoal(steps, stepGoal),
            when (load) {
                LoadStatus.Above -> "a Load above your usual"
                LoadStatus.Below -> "a Load below your usual"
                LoadStatus.Usual -> "a usual Load"
                LoadStatus.SoFar, null -> null
            },
        )
        val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        return "$head ${list.replaceFirstChar { it.uppercase() }}."
    }

    /** The day's [steps] against the [goal] in words: under half is well short, from 80% close, the goal itself past. */
    fun stepsAgainstGoal(steps: Int, goal: Int): String = when {
        steps >= goal -> "past your step goal"
        steps >= goal * 0.8 -> "close to your step goal"
        steps >= goal / 2 -> "short of your step goal"
        else -> "well short of your step goal"
    }

    /** "Bedtime in 1 h 10" until your usual [bedtime], read on a clock that starts at noon; null once it's passed. */
    fun bedtimeIn(now: LocalTime, bedtime: LocalTime?): String? {
        bedtime ?: return null
        val minutes = afterNoon(bedtime) - afterNoon(now)
        return if (minutes <= 0) null else "Bedtime in ${duration(minutes * 60L)}"
    }

    private fun afterNoon(t: LocalTime): Int = Math.floorMod(t.toSecondOfDay() / 60 - 720, 1440)

    /** Seconds still to recover from a workout that ended at [end] and needs [minutes], at [now]. */
    fun recoverLeft(end: Long, minutes: Int, now: Long): Long = (end + minutes * 60L - now).coerceAtLeast(0)

    /**
     * Time to recover as a value and a status: "14" and "Hours left, ready tomorrow", "2" and "Hours left, ready by
     * 20:30". Recovered has no number, only the word (null value).
     */
    fun recoverText(left: Long, now: Long, zone: ZoneId): Pair<String?, String> {
        if (left <= 0) return null to "Recovered"
        val hours = (left + 3599) / 3600
        val ready = Instant.ofEpochSecond(now + left).atZone(zone)
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val unit = if (hours == 1L) "Hour left" else "Hours left"
        val status = when (ready.toLocalDate()) {
            today -> "ready by ${ready.format(DateTimeFormatter.ofPattern("HH:mm"))}"
            today.plusDays(1) -> "ready tomorrow"
            else -> "ready ${ready.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())}"
        }
        return "$hours" to "$unit, $status"
    }

    /** The line under Load today's number, which says it's a Load and how it compares. */
    fun loadLine(status: LoadStatus?): String = when (status) {
        null -> "Load, learning your usual"
        LoadStatus.Above -> "Load above your usual"
        LoadStatus.Usual -> "Your usual Load"
        LoadStatus.Below -> "Load below your usual"
        LoadStatus.SoFar -> "Load so far today"
    }

    /** Under Your week before there's a week before it to compare with. */
    const val WEEK_FIRST = "Week on week comparisons start next Monday."

    /** The week Your week tells (spec 4.2): Monday to Sunday, the last full week before [date]. */
    fun lastWeek(date: LocalDate): ClosedRange<LocalDate> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
        return start..start.plusDays(6)
    }

    /** "Steps up 12%" and the like, comparing two weeks; null without both. */
    fun stepsChange(now: Double?, before: Double?): String? {
        if (now == null || before == null || before <= 0) return null
        val pct = Math.round((now - before) / before * 100).toInt()
        return if (pct == 0) "Steps held steady" else "Steps ${if (pct > 0) "up" else "down"} ${abs(pct)}%"
    }

    /** The Last night strip: "7 h 40, score 83". */
    fun lastNight(asleepSeconds: Long?, score: Int?): String? = asleepSeconds?.let {
        duration(it) + (score?.let { s -> ", score $s" } ?: "")
    }

    /** A Small's word for Load against your usual: the [LoadStatus]'s, or that the usual is still being learned. */
    fun loadWord(status: LoadStatus?): String = status?.text ?: "Learning your usual"

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
        if (band) "Hold to scrub, the number follows. The band is your resting range." else "Hold to scrub, the number follows."

    /** The heart rate card's line under its number: live, or when the reading shown was taken. */
    fun heartAt(live: Boolean, at: String?): String = when {
        live -> "Live now"
        at != null -> "At $at"
        else -> "Waiting for the strap"
    }
}
