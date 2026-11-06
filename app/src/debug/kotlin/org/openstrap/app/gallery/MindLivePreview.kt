package org.openstrap.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.glow.live.LiveControls
import org.openstrap.app.ui.glow.live.LiveMindScreen

/**
 * The whole Mind and body live screen, a yoga session ten minutes in, for its dimming (`--es section mind`): it dims
 * ten seconds after the last touch, all but the time.
 */
// Sample figures: ten minutes in, a settled heart rate.
@Suppress("MagicNumber")
@Composable
internal fun MindLive() {
    val start = remember { System.currentTimeMillis() / 1000 - 600 }
    val run = WorkoutState.Recording(AppWorkout(start = start, end = null, sportType = 60), maxHeartRate = 190)
    val recent = MindLine.mapIndexed { i, bpm -> start + 600 - (MindLine.size - i) * 5L to (bpm ?: 0f).toInt() }
    val none = {}
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LiveMindScreen(
            run, 64, recent, ConnectionState.Connected(batteryPercent = 82, firmware = null), WindowInsets.systemBars.asPaddingValues(),
            LiveControls(none, none, none, none, none, none),
        )
    }
}
