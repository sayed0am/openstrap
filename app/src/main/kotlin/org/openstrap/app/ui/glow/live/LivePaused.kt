package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.dotClock
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R

/**
 * A paused workout (workouts spec 2.3, board Paused): the time stops and the pause counts up in dots, "Paused for",
 * the heart rate now and how far it has dropped since you stopped ([recent], the live readings, hold the one at the
 * pause), then the workout's [figures] so far (value, label).
 */
@Composable
internal fun ColumnScope.LivePaused(r: WorkoutState.Recording, liveHeartRate: Int?, recent: List<Pair<Long, Int>>, figures: List<Pair<String?, String>>) {
    val at = r.workout.pausedAt ?: return
    val now = nowTicking()
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.workout_live_paused_for), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
            style = GlowType.secondary,
        )
        DotMatrixText(dotClock((now - at).coerceAtLeast(0)), dot = DotSize.Detail)
        val before = Workouts.heartRateAt(recent, at) ?: recent.lastOrNull { it.first <= at }?.second
        val drop = if (before != null && liveHeartRate != null) before - liveHeartRate else null
        if (liveHeartRate != null) Text(
            drop?.takeIf { it > 0 }?.let { stringResource(R.string.workout_live_paused_heart_down, liveHeartRate, it) }
                ?: stringResource(R.string.workout_live_paused_heart, liveHeartRate),
            color = Color.White, style = GlowType.sentence, textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.workout_live_paused_note), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
            style = GlowType.secondary, textAlign = TextAlign.Center,
        )
    }
    LiveFigures(figures, Modifier.padding(top = 16.dp))
}

/** A row of plain live figures (value over label) with thin rules between, a missing value as the waiting dash. */
@Composable
internal fun LiveFigures(figures: List<Pair<String?, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
        figures.forEachIndexed { i, (value, label) ->
            if (i > 0) StatRule()
            LiveStat(label, Modifier.weight(1f)) { LiveValue(value) }
        }
    }
}
