package org.openstrap.app.ui.dashboard

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.Saver
import org.openstrap.app.data.settings.Goals
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.R
import org.openstrap.app.tr

/** A metric with its own detail screen: its title, unit, decimals shown, and the per-day series behind it. */
enum class Metric(val title: String, val unit: String, val decimals: Int, val daily: DailyMetric) {
    HeartRate(tr(R.string.common_heart_rate), "bpm", 0, DailyMetric.HeartRate),
    RestingHr(tr(R.string.common_resting_heart_rate_2), "bpm", 0, DailyMetric.RestingHr),
    Hrv(tr(R.string.common_hrv), "ms", 0, DailyMetric.Hrv),
    Spo2(tr(R.string.common_blood_oxygen), "%", 0, DailyMetric.Spo2),
    Stress(tr(R.string.common_stress), "", 0, DailyMetric.Stress),
    SkinTemperature(tr(R.string.common_skin_temperature), "°C", 1, DailyMetric.SkinTemperature),
    Breathing(tr(R.string.common_breathing_rate), tr(R.string.missed_unit_br_min), 1, DailyMetric.Breathing),
    Steps(tr(R.string.common_steps), tr(R.string.missed_unit_steps), 0, DailyMetric.Steps),
    ;

    fun format(v: Float): String = if (this == Steps) "%,d".format(Math.round(v)) else "%.${decimals}f".format(v)

    /** A change from your normal, signed with a true minus (spec 2.3): "+0.2", "−0.3"; no sign is lost to rounding. */
    fun formatChange(d: Float): String {
        val shown = format(kotlin.math.abs(d))
        return (if (d < 0 && shown.any { it in '1'..'9' }) "\u2212" else "+") + shown
    }
}

/**
 * A detail in the metric template with no per-day series behind it, unlike a [Metric]'s: Recovery keeps only its last
 * 7 days, Charge only the day shown, the strap's Sleep score and the night's times only the 14 nights the day loads,
 * and the day's Naps are only that day's. The Activity tab's: Heart points over the strap's 7 days, Active minutes over
 * the 14 days the day works out, Load over the 30 days before it, and VO2 max, Time to recover, Energy and Distance as
 * the day has them.
 */
enum class Score(val title: String) {
    Recovery(tr(R.string.common_recovery)), Charge(tr(R.string.common_energy)), Sleep(tr(R.string.common_sleep_score)), Regularity(tr(R.string.common_sleep_regularity)), Naps(tr(R.string.common_naps)),
    HeartPoints(tr(R.string.common_heart_points)), ActiveMinutes(tr(R.string.common_active_minutes)), Load(tr(R.string.common_load)), Vo2Max(tr(R.string.common_vo_max)),
    Recover(tr(R.string.common_time_to_recover)), Energy(tr(R.string.common_calories)), Distance(tr(R.string.common_distance)),
}

/** Your daily goals, provided once at the top of the app so every screen measures against the same ones. */
val LocalGoals = compositionLocalOf { Goals() }

/**
 * A screen pushed on top of a tab. Each tab keeps its own, so opening one never moves you to another tab: a card on
 * Today that's about Recovery opens the Recovery screen over Today rather than switching to its tab (spec 3.3).
 */
sealed interface Detail {
    /**
     * A metric's detail. [night] scopes it to the night shown, as the Sleep tab's night vitals open it: the value,
     * chart and sentence come from the sleep, not the whole day.
     */
    data class OfMetric(val metric: Metric, val night: Boolean = false) : Detail
    /** A [Score]'s detail, in the same template as a metric's. */
    data class OfScore(val score: Score) : Detail
    data class Workout(val start: Long) : Detail
    /** Every workout, newest first. */
    data object AllWorkouts : Detail
    /** The Healthspan estimate and what moves it, opened from its card on Today. */
    data object Healthspan : Detail
    /** The Recovery tab's screen (Recovery, Charge and the body signals), opened from a card on another tab. */
    data object Recovery : Detail
    /** The Sleep tab's screen, opened from a card on another tab. */
    data object Sleep : Detail
    /** The week story: last Monday to Sunday day by day, opened from Your week on Today. */
    data object Week : Detail

    companion object {
        /** Saves a tab's stack of details, deepest last, as one string ("m:Hrv|n:Spo2|w:1792227300") so it survives process death. */
        val StackSaver: Saver<List<Detail>, String> = Saver(
            save = { stack -> stack.joinToString("|", transform = ::keyOf) },
            restore = { s -> s.split('|').mapNotNull(::restore) },
        )

        /** A detail's saved key ("m:Hrv"), also its key in the card to detail motion. */
        fun keyOf(d: Detail): String = when (d) {
            is OfMetric -> "${if (d.night) "n" else "m"}:${d.metric.name}"
            is OfScore -> "c:${d.score.name}"
            is Workout -> "w:${d.start}"
            AllWorkouts -> "a:"
            Healthspan -> "h:"
            Recovery -> "r:"
            Sleep -> "s:"
            Week -> "k:"
        }

        private fun restore(s: String): Detail? {
            val value = s.substringAfter(':')
            return when (s.substringBefore(':')) {
                "m" -> Metric.entries.firstOrNull { it.name == value }?.let { OfMetric(it) }
                "n" -> NIGHT_VITALS.firstOrNull { it.name == value }?.let { OfMetric(it, night = true) }
                "c" -> Score.entries.firstOrNull { it.name == value }?.let(::OfScore)
                "w" -> value.toLongOrNull()?.let(::Workout)
                "a" -> AllWorkouts
                "h" -> Healthspan
                "r" -> Recovery
                "s" -> Sleep
                "k" -> Week
                else -> null
            }
        }

        /**
         * The screen an `open_metric` link names: a [Metric] by name ("Hrv"), "Recovery" or "Sleep" for those
         * screens, or [SLEEP_SCORE_ROUTE] for the Sleep score's detail. Null for anything else.
         */
        /** The Sleep score's detail for the night of the link's day, as the Sleep tab's hero opens it. */
        const val SLEEP_SCORE_ROUTE = "SleepScore"

        fun fromRoute(route: String): Detail? = when (route) {
            "Recovery" -> Recovery
            "Sleep" -> Sleep
            SLEEP_SCORE_ROUTE -> OfScore(Score.Sleep)
            else -> Metric.entries.firstOrNull { it.name == route }?.let { OfMetric(it) }
        }
    }
}
