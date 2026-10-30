package org.openstrap.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.R
import org.openstrap.app.tr

val PanelShape = org.openstrap.app.ui.glow.GlowLayout.CardShape

/** The base card: a slightly raised surface with a hairline border, as in WHOOP/Oura. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** When set, the border fades to this colour (used to point at a card after jumping to it). */
    highlight: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border by androidx.compose.animation.animateColorAsState(
        highlight ?: MaterialTheme.colorScheme.outlineVariant, androidx.compose.animation.core.tween(450), label = "panel border",
    )
    Surface(
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.pressClickable(onClick, PanelShape) else it },
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (highlight != null) 1.5.dp else 1.dp, border),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** Small uppercase tracked label: "TIME ASLEEP". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}

/** Label row with an optional leading accent icon and trailing text (e.g. "Last 7 days"). */
@Composable
fun PanelHeader(
    title: String,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: String? = null,
    /** Shows a "›" at the end: the card opens something when tapped. */
    chevron: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        SectionLabel(title, Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(AppIcons.CaretRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        // A short workout reads "45s", not "0m".
        seconds in 1 until 60 -> tr(R.string.missed_compact_s, seconds)
        h > 0 && m == 0L -> tr(R.string.missed_compact_h, h)
        h > 0 -> tr(R.string.missed_compact_h_m, h, m)
        else -> tr(R.string.missed_compact_m, m)
    }
}
