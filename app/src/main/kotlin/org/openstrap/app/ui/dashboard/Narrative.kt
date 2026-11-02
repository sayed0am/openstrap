package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.presentation.RecoveryBands
import org.openstrap.app.presentation.SleepBands
import org.openstrap.app.presentation.Units
import kotlin.math.abs

/**
 * Plain-language headlines and ratings for the dashboard. The thresholds are general sleep and activity
 * guidance, not anything the strap reports; the UI says so where ratings appear.
 */
object Narrative {
    /** A rating's word. No colour of its own: a value off your normal takes its metric's ink (spec 2.2). */
    enum class Rating(val label: String) {
        OPTIMAL("Very good"),
        GOOD("Good"),
        FAIR("Fair"),
        LOW("Low"),
    }

    fun rate(value: Float, optimal: Float, good: Float, fair: Float): Rating = when {
        value >= optimal -> Rating.OPTIMAL
        value >= good -> Rating.GOOD
        value >= fair -> Rating.FAIR
        else -> Rating.LOW
    }

    /** Under 6 h asleep: the bottom of Zepp's duration bands (codemap 06 §4.2), and 56 on [org.openstrap.app.data.Recovery.durationScore]. */
    const val SHORT_NIGHT_MINUTES = 6 * 60

    /** Awake after falling asleep from here on: where [org.openstrap.app.data.Recovery.continuityScore] starts taking points off. */
    const val BROKEN_AWAKE_MINUTES = 30

    /**
     * This many wake-ups is broken sleep even when they were brief. Set high on purpose: everyone wakes a few times a
     * night and wake detection is the weakest part of wearable staging (evidence-review.md, "Sleep"), so only about
     * one every hour of a normal night counts. Short wake-ups that add up to 30 min are caught by [BROKEN_AWAKE_MINUTES].
     */
    const val BROKEN_WAKE_COUNT = 6

    /**
     * Deep + REM below this share of the time asleep is a light night. Adults usually spend 40-50 % there; a quarter
     * leaves room for the strap misreading deep as light (evidence-review.md, "Sleep").
     */
    const val LIGHT_SLEEP_SHARE = 0.25f

    /**
     * What pulled the night down, in a few words. The strap's score mostly tracks duration, so a calm short night
     * says "Short night" rather than "Restless". Short and broken outrank the score; light sleep is the weakest
     * cause, since stages aren't rated (see [sleepContributors]), so it's named only when nothing else is and the
     * score is below Good. Null without a score; callers fall back to time asleep.
     */
    fun sleepHeadline(session: SleepSession, asleepSeconds: Long): String? {
        val score = session.score ?: return null
        val asleepMin = asleepSeconds / 60
        val short = asleepMin < SHORT_NIGHT_MINUTES
        val broken = (session.awakeMinutes ?: 0) >= BROKEN_AWAKE_MINUTES || (session.wakeCount ?: 0) >= BROKEN_WAKE_COUNT
        val deep = session.deepMinutes
        val rem = session.remMinutes
        val light = deep != null && rem != null && asleepMin > 0 && (deep + rem) < LIGHT_SLEEP_SHARE * asleepMin
        return when {
            short && broken -> "Short, broken night"
            short -> "Short night"
            broken -> "Broken sleep"
            score >= SleepBands.GREAT_FROM -> "Solid night"
            score >= SleepBands.GOOD_FROM -> "Good night"
            light -> "Light sleep"
            else -> "Okay night"
        }
    }

    /** One sleep contributor: rating plus how full its bar is. */
    data class Contributor(val label: String, val rating: Rating, val fraction: Float, val detail: String)

    /**
     * What shaped the night: total sleep against need (and too much of it) and continuity (wake after sleep onset). Deep and REM aren't
     * rated: wearable staging is too unreliable to score (evidence-review.md, "Sleep"), so the stages panel shows them
     * without a target.
     */
    fun sleepContributors(
        session: SleepSession,
        asleepSeconds: Long,
        needMinutes: Int = org.openstrap.app.data.Recovery.SLEEP_NEED_MINUTES,
        wasoMinutes: Int? = null,
    ): List<Contributor> = buildList {
        val asleepMin = (asleepSeconds / 60).toInt()
        val duration = org.openstrap.app.data.Recovery.durationScore(asleepMin, needMinutes)
        val total = Units.duration(asleepSeconds) + " of ${Units.duration(needMinutes * 60L)}"
        add(Contributor("Total sleep", totalSleepRating(asleepMin, duration), asleepMin / needMinutes.toFloat(), total))
        if (wasoMinutes != null && asleepMin > 0) {
            val c = org.openstrap.app.data.Recovery.continuityScore(wasoMinutes)
            val awake = "${Units.duration(wasoMinutes * 60L)} awake after falling asleep"
            add(Contributor("Continuity", ratingFor(Math.round(c)), c / 100f, awake))
        }
    }

    /**
     * Total sleep against need, plus Zepp's oversleep bands: over 10 h is Attention, over 9 h 30 Fair (codemap 06
     * §4.2). Need tops out at 9 h, so these only ever lower a night that met it.
     */
    private fun totalSleepRating(asleepMinutes: Int, durationScore: Float): Rating = when {
        asleepMinutes > OVERSLEEP_ATTENTION_MINUTES -> Rating.LOW
        asleepMinutes > OVERSLEEP_FAIR_MINUTES -> Rating.FAIR
        else -> ratingFor(Math.round(durationScore))
    }

    const val OVERSLEEP_FAIR_MINUTES = 570
    const val OVERSLEEP_ATTENTION_MINUTES = 600

    /** +1 / -1 when [today] differs from [baseline] by more than [tolerance], else 0; null if either is missing. */
    fun direction(today: Float?, baseline: Float?, tolerance: Float): Int? {
        if (today == null || baseline == null) return null
        val d = today - baseline
        return if (abs(d) < tolerance) 0 else if (d > 0) 1 else -1
    }

    /** A 0-100 contributor score as a rating word. */
    fun ratingFor(score: Int): Rating = when {
        score >= RecoveryBands.READY_FOR_MORE_FROM -> Rating.OPTIMAL
        score >= RecoveryBands.READY_FROM -> Rating.GOOD
        score >= RecoveryBands.MODERATE_FROM -> Rating.FAIR
        else -> Rating.LOW
    }

    /** A Charge as its band's word from the info sheet's scale. */
    fun chargeHeadline(charge: Int): String = InfoCopy.CHARGE_SCALE[InfoCopy.chargeBar(charge)]

    /** Where stress starts counting, and why: the user's calm level, or how long until there is one. */
    fun stressThreshold(t: org.openstrap.app.data.Recovery.StressThreshold): String {
        val calm = t.calmLevel
        val left = org.openstrap.app.data.Recovery.MIN_STRESS_DAYS - t.days
        return when {
            calm != null -> "Stress counts from ${t.value}: ${org.openstrap.app.data.Recovery.STRESS_MARGIN} above your calm level of $calm over the last ${t.days} days."
            left > 0 -> "Stress counts from ${t.value} for now. After ${if (left == 1) "1 more day" else "$left more days"} of wear it adjusts to your own calm level."
            else -> "Stress counts from ${t.value} for now, until there are enough calm readings to find your own level."
        }
    }
}
