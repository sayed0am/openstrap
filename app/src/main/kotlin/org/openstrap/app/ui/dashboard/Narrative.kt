package org.openstrap.app.ui.dashboard

import androidx.annotation.StringRes
import org.openstrap.app.R
import org.openstrap.app.data.continuityScore
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.durationScore
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.RecoveryBands
import org.openstrap.app.presentation.SleepBands
import org.openstrap.app.presentation.Units
import kotlin.math.abs

/**
 * Plain-language headlines and ratings for the dashboard. The thresholds are general sleep and activity
 * guidance, not anything the strap reports. The UI says so where ratings appear. The text is in
 * res/values/strings_day_sleep.xml and strings_day_today.xml.
 */
object Narrative {
    /** A rating's word. No colour of its own: a value off your normal takes its metric's ink (spec 2.2). */
    enum class Rating(@StringRes private val labelRes: Int) {
        OPTIMAL(R.string.day_rating_very_good),
        GOOD(R.string.day_rating_good),
        FAIR(R.string.day_rating_fair),
        LOW(R.string.day_rating_low),
        ;

        /** The word, in the app's language. */
        val label: String get() = Strings.app.get(labelRes)
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
     * What pulled the night down, in a few words. The strap's score mostly tracks duration, so a calm short night
     * says "Short night" rather than "Restless". Short and broken outrank the score. Broken is awake time only: a count
     * of wake-ups and a deep + REM share aren't named, because wearables find 29-52 % of real wake time and score
     * stages at kappa 0.21-0.53 (evidence-review.md, "Sleep"). Null without a score; callers fall back to time asleep.
     */
    fun sleepHeadline(session: SleepSession, asleepSeconds: Long, strings: Strings = Strings.app): String? {
        val score = session.score ?: return null
        val asleepMin = asleepSeconds / 60
        val short = asleepMin < SHORT_NIGHT_MINUTES
        val broken = (session.awakeMinutes ?: 0) >= BROKEN_AWAKE_MINUTES
        return strings.get(
            when {
                short && broken -> R.string.day_sleep_headline_short_broken
                short -> R.string.day_sleep_headline_short
                broken -> R.string.day_sleep_headline_broken
                score >= SleepBands.GREAT_FROM -> R.string.day_sleep_headline_solid
                score >= SleepBands.GOOD_FROM -> R.string.day_sleep_headline_good
                else -> R.string.day_sleep_headline_okay
            },
        )
    }

    /** One sleep contributor: rating plus how full its bar is. [label] is [TOTAL_SLEEP] or [CONTINUITY], a key never shown. */
    data class Contributor(val label: String, val rating: Rating, val fraction: Float, val detail: String)

    /** The keys of the two contributors. */
    const val TOTAL_SLEEP = "Total sleep"
    const val CONTINUITY = "Continuity"

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
        strings: Strings = Strings.app,
    ): List<Contributor> = buildList {
        val asleepMin = (asleepSeconds / 60).toInt()
        val duration = org.openstrap.app.data.Recovery.durationScore(asleepMin, needMinutes)
        val need = Units.duration(needMinutes * 60L)
        val total = strings.format(R.string.day_sleep_factor_asleep_detail, Units.duration(asleepSeconds), need)
        add(Contributor(TOTAL_SLEEP, totalSleepRating(asleepMin, duration), asleepMin / needMinutes.toFloat(), total))
        if (wasoMinutes != null && asleepMin > 0) {
            val c = org.openstrap.app.data.Recovery.continuityScore(wasoMinutes)
            val awake = strings.format(R.string.day_sleep_factor_awake_detail, Units.duration(wasoMinutes * 60L))
            add(Contributor(CONTINUITY, ratingFor(Math.round(c)), c / 100f, awake))
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
    fun chargeHeadline(charge: Int, strings: Strings = Strings.app): String = InfoCopy.chargeWords(strings)[InfoCopy.chargeBar(charge)]

    /** Where stress starts counting, and why: the user's calm level, or how long until there is one. */
    fun stressThreshold(t: org.openstrap.app.data.Recovery.StressThreshold, strings: Strings = Strings.app): String {
        val calm = t.calmLevel
        val left = org.openstrap.app.data.Recovery.MIN_STRESS_DAYS - t.days
        return when {
            calm != null -> strings.format(
                R.string.day_stress_counts_from_calm, t.value, org.openstrap.app.data.Recovery.STRESS_MARGIN, calm,
                strings.plural(R.plurals.day_stress_last_days, t.days, t.days),
            )
            left > 0 -> strings.format(
                R.string.day_stress_counts_for_now, t.value, strings.plural(R.plurals.day_stress_more_days, left, left),
            )
            else -> strings.format(R.string.day_stress_counts_until, t.value)
        }
    }
}
