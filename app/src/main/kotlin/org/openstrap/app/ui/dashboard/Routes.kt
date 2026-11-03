package org.openstrap.app.ui.dashboard

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.Saver
import org.openstrap.app.data.settings.Goals
import org.openstrap.app.data.HeartZones
import org.openstrap.app.presentation.Metric

/**
 * A detail in the metric template with no per-day series behind it, unlike a [Metric]'s: Recovery keeps only its last
 * 7 days, Energy (the Charge model) only the day shown, the strap's Sleep score and the night's times only the 14
 * nights the day loads, and the day's Naps are only that day's. The Activity tab's: Heart points over the strap's 7
 * days, Active minutes over the 14 days the day works out, Load over the 30 days before it, and VO2 max, Time to
 * recover, Calories and Distance as the day has them.
 *
 * [key] names it in saved stacks ([Detail.StackSaver]) and card motion: its name, except for the two renamed to match
 * what the screens say. Energy was `Charge` and Calories was `Energy`, and they keep those names there, so a stack
 * saved before the rename still opens the same detail and a saved "Energy" only ever means Calories.
 */
enum class Score(val title: String, private val savedAs: String? = null) {
    Recovery("Recovery"), Energy("Energy", savedAs = "Charge"), Sleep("Sleep score"), Regularity("Sleep regularity"),
    Naps("Naps"), HeartPoints("Heart points"), ActiveMinutes("Active minutes"), Load("Load"), Vo2Max("VO2 max"),
    Recover("Time to recover"), Calories("Calories", savedAs = "Energy"), Distance("Distance"),
    ;

    /** The name a saved stack knows it by. */
    val key: String get() = savedAs ?: name
}

/** Your daily goals, provided once at the top of the app so every screen measures against the same ones. */
val LocalGoals = compositionLocalOf { Goals() }

/** Your heart rate zones as stored, provided once at the top of the app so every zone on screen reads the same ones. */
// Like LocalGoals: every zone on screen reads the same stored zones, without threading them through every live screen.
@Suppress("CompositionLocalAllowlist", "ComposeCompositionLocalUsage")
val LocalHeartZones = compositionLocalOf { HeartZones.STANDARD }

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
    /** The week story: last week day by day, opened from Your week on Today. */
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
            is OfScore -> "c:${d.score.key}"
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
                "c" -> Score.entries.firstOrNull { it.key == value }?.let(::OfScore)
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
