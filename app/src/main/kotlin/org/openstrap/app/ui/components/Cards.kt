package org.openstrap.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val PanelShape = org.openstrap.app.ui.glow.GlowLayout.CardShape

/** Small uppercase tracked label: "TIME ASLEEP". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        // A short workout reads "45s", not "0m".
        seconds in 1 until 60 -> "${seconds}s"
        h > 0 && m == 0L -> "${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}
