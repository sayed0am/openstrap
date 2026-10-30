package org.openstrap.app.ui.workout

import androidx.compose.ui.graphics.Color
import org.openstrap.app.R
import org.openstrap.app.tr

/** Zone 1 (easy) to 5 (max), cool to hot; during a workout the app takes on the current one. */
internal val ZONE_COLORS = listOf(Color(0xFF8FA8FF), Color(0xFF3DDC97), Color(0xFFFFD166), Color(0xFFFF9F43), Color(0xFFFF5A5F))
internal val ZONE_NAMES = listOf(tr(R.string.common_easy), tr(R.string.workout_style_aerobic), tr(R.string.workout_style_tempo), tr(R.string.workout_style_threshold), tr(R.string.common_max))
