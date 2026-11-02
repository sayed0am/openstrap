package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.theme.MetricColors

/**
 * The top half of a foldable standing half-open on a table: your heart rate, big enough to read from across
 * the room, beating at the latest reading, with the day's trace under it. The app carries on in the bottom half.
 * [liveEnabled] null hides the Live switch (no strap connected).
 */
@Composable
fun GlowTabletopHeart(
    state: DayUiState,
    live: Int?,
    liveEnabled: Boolean?,
    onLiveToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = live ?: state.latestHeartRate
    val style = GlowStyle.chart(MetricColors.heart).copy(motion = GlowMotion.Heartbeat(now ?: 62))
    Column(modifier.statusBarsPadding().padding(horizontal = GlowLayout.Gutter, vertical = 12.dp)) {
        GlowCard(style, Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                GlowHeader(
                    "Heart rate",
                    trailing = if (live == null && liveEnabled == null) "Latest" else null,
                    trailingContent = liveEnabled?.let { on ->
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Live", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(8.dp))
                                GlowSwitch(on, onLiveToggle, accent = style.color)
                            }
                        }
                    },
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    DotMatrixText(now?.toString() ?: "-", dot = DotSize.Live)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("bpm", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium)
                    }
                    state.restingHeartRate?.let {
                        Text("Resting $it", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.heartRate.isNotEmpty()) {
                    val values = remember(state.heartRate, state.dayStart, state.dayEnd) {
                        bucket(state.heartRate, state.dayStart..state.dayEnd, 5 * 60)
                    }
                    GlowLineChart(values, height = GlowLayout.ChartHeight, labels = listOf("00", "06", "12", "18", "24")) { _, v -> "${v.toInt()} bpm" }
                }
            }
        }
    }
}
