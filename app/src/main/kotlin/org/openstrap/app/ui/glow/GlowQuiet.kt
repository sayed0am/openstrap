package org.openstrap.app.ui.glow

import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.border
import org.openstrap.app.ui.theme.MetricColors
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * The quiet tier of the glow language as a free-form panel (spec 2.5, L0): a list's surface and hairline round
 * content that isn't rows, like controls, a chart or a note. No light and no dots, so the glow cards on a screen stay
 * special.
 */

/** A quiet panel: the [ListPanel] surface and its 6% hairline, [GlowLayout.CardPadding] inside, blocks 14 dp apart. */
@Composable
fun QuietPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.pressClickable(onClick, GlowLayout.CardShape) else it }
            .background(ListSurface, GlowLayout.CardShape)
            .border(1.dp, RowHairline, GlowLayout.CardShape)
            .padding(GlowLayout.CardPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/**
 * The title row of a [QuietPanel]: the title, an optional note on the right and an optional chevron. No leading mark:
 * colour belongs to the data inside. [warning] puts an amber warning icon in front, for panels that flag a problem.
 * [expanded], when set, shows a caret that turns as a folding panel opens.
 */
@Composable
fun PanelTitle(title: String, trailing: String? = null, chevron: Boolean = false, warning: Boolean = false, expanded: Boolean? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (warning) {
            Icon(AppIcons.Warning, null, tint = MetricColors.warn, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(title, color = Color.White, style = GlowType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(AppIcons.CaretRight, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
        if (expanded != null) {
            // In a glass circle, like every other control inside a quiet panel.
            val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "panel title caret")
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(32.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                Icon(AppIcons.CaretDown, if (expanded) tr(R.string.common_hide) else tr(R.string.common_show), tint = Color.White, modifier = Modifier.size(16.dp).rotate(turn))
            }
        }
    }
}

/**
 * The glass surface every control inside a quiet panel wears (rows, pills, buttons): a faint white fill
 * and hairline, so it reads as something to press against the panel. With [onClick] it
 * presses like the rest of the glow controls.
 */
fun Modifier.glass(shape: Shape, onClick: (() -> Unit)? = null, scale: Float = 0.98f): Modifier =
    background(Color.White.copy(alpha = 0.08f), shape)
        .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
        .let { if (onClick != null) it.pressClickable(onClick, shape, scale) else it }

/** The shape of a glass row inside a quiet panel. */
val GlassRowShape = GlowLayout.TileShape

/** A quiet rule between blocks in a panel: a solid 1 dp hairline, as between list rows. */
@Composable
fun PanelDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(RowHairline))
}

internal val TrendGood = Color(0xFF3DDC97)
internal val TrendBad = Color(0xFFFFB547)
