package org.openstrap.app.ui.workout

import androidx.compose.ui.graphics.Color

/** Zone 1 (easy) to 5 (max), cool to hot; during a workout the app takes on the current one. */
internal val ZONE_COLORS = listOf(Color(0xFF8FA8FF), Color(0xFF3DDC97), Color(0xFFFFD166), Color(0xFFFF9F43), Color(0xFFFF5A5F))
internal val ZONE_NAMES = listOf("Easy", "Aerobic", "Tempo", "Threshold", "Max")

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
