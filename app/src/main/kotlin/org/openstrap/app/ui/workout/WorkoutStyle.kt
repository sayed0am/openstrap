package org.openstrap.app.ui.workout

import androidx.compose.ui.graphics.Color
import org.openstrap.app.presentation.WorkoutColors
import org.openstrap.app.presentation.WorkoutWords

/** Zone 1 (easy) to 5 (max), cool to hot ([WorkoutColors.ZONES]); during a workout the app takes on the current one. */
internal val ZONE_COLORS: List<Color> = WorkoutColors.ZONES.map(::Color)
/**
 * A workout's top card colour: the zone it spent most time in, so the card agrees with its sentence ("mostly in zone
 * 1"), or [fallback] with no heart rate in any zone.
 */
internal fun mainZoneColor(zones: List<Int>, fallback: Color): Color =
    zones.indices.maxByOrNull { zones[it] }?.takeIf { zones[it] > 0 }?.let { ZONE_COLORS.getOrNull(it) } ?: fallback

/** Read each time, so the names follow the app's language. */
internal val ZONE_NAMES: List<String> get() = WorkoutWords.ZONE_NAMES

/**
 * Each kind of workout's colour on Choose and Ready (workouts spec 2.1): lime for distance, strength's orange, heart red
 * for intervals, a calm blue for indoor cardio, the evening's violet for mind and body, and a clear blue for games.
 */
internal fun familyColor(family: org.openstrap.app.data.Workouts.Family): Color = when (family) {
    org.openstrap.app.data.Workouts.Family.Distance -> org.openstrap.app.ui.theme.MetricColors.steps
    org.openstrap.app.data.Workouts.Family.IndoorCardio -> ZONE_COLORS[0]
    org.openstrap.app.data.Workouts.Family.Strength -> ZONE_COLORS[3]
    org.openstrap.app.data.Workouts.Family.Intervals -> org.openstrap.app.ui.theme.MetricColors.heart
    org.openstrap.app.data.Workouts.Family.MindBody -> org.openstrap.app.ui.theme.MetricColors.evening
    org.openstrap.app.data.Workouts.Family.Games -> org.openstrap.app.ui.theme.MetricColors.spo2
}
