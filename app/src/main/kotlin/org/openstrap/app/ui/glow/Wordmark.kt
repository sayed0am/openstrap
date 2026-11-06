package org.openstrap.app.ui.glow

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.openstrap.app.ui.theme.MetricColors

/**
 * The app's name as its logo (docs/design/rebrand-unstrap.md): "un" in the heart red, "strap" in white, lowercase.
 * The logo's glow is left out at this size, as the logo sheet says for anything under about 24 px. Placed on dark
 * ground only, where the red reads. A brand, not a word to translate.
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier, style: TextStyle = GlowType.storyBrand, strap: Color = Color.White) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = MetricColors.heart)) { append("un") }
        withStyle(SpanStyle(color = strap)) { append("strap") }
    }
    Text(text, style = style, maxLines = 1, modifier = modifier)
}
