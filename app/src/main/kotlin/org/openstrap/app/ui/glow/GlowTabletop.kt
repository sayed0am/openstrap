package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openstrap.app.R
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.glow.draw.GlowMotion
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.draw.chart

/**
 * The top half of a foldable standing half-open on a table: your heart rate, big enough to read from across
 * the room, beating at the latest reading, with the day's trace under it. The app carries on in the bottom half.
 * [live] null (no reading, or no strap connected) shows the latest stored reading, labelled as such.
 */
@Composable
fun GlowTabletopHeart(
    state: DayUiState,
    live: Int?,
    modifier: Modifier = Modifier,
) {
    val now = live ?: state.latestHeartRate
    val style = GlowStyle.chart(MetricColors.heart).copy(motion = GlowMotion.Heartbeat(now ?: 62))
    Column(modifier.statusBarsPadding().padding(horizontal = GlowLayout.Gutter, vertical = 12.dp)) {
        GlowCard(style, Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                GlowHeader(
                    stringResource(R.string.glow_tabletop_heart_rate),
                    trailing = stringResource(if (live == null) R.string.glow_tabletop_latest else R.string.glow_tabletop_live),
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    DotMatrixText(now?.toString() ?: "-", dot = DotSize.Live)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            stringResource(R.string.glow_unit_bpm), color = Color.White.copy(alpha = 0.7f), style = GlowType.cardTitle,
                            maxLines = 1,
                        )
                    }
                    state.restingHeartRate?.let {
                        Text(
                            stringResource(R.string.glow_tabletop_resting, it), color = Color.White.copy(alpha = 0.85f),
                            style = GlowType.body,
                        )
                    }
                }
                if (state.heartRate.isNotEmpty()) {
                    val values = remember(state.heartRate, state.dayStart, state.dayEnd) {
                        bucket(state.heartRate, state.dayStart..state.dayEnd, 5 * 60)
                    }
                    val resources = LocalResources.current
                    GlowLineChart(values, height = GlowLayout.ChartHeight, labels = listOf("00", "06", "12", "18", "24")) { _, v ->
                        resources.getString(R.string.glow_tabletop_bpm, v.toInt())
                    }
                }
            }
        }
    }
}
