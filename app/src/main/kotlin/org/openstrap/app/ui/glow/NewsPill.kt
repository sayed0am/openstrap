package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.dashboard.NewsTag

/** A news tag's fill: white at 12%, no border (spec 2.5, Tags). */
internal val NewsPillFill = Color.White.copy(alpha = 0.12f)

/**
 * A news tag (spec 2.5, Tags): a fully round pill, white at 12% with no border, its words in Caption type in white.
 * It sits 8 dp after a number's unit, or under a list row's name. Apart from the outlined [OutlineTag] beside a title,
 * which is about the data, never about you.
 */
@Composable
fun NewsPill(tag: NewsTag, modifier: Modifier = Modifier) = NewsPill(tag.text, modifier)

/** A news tag with its [text]. */
@Composable
fun NewsPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.background(NewsPillFill, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
        color = Color.White,
        style = GlowType.caption,
        maxLines = 1,
    )
}

/** From a number's unit to its tag. */
internal val NewsPillGap = 8.dp
