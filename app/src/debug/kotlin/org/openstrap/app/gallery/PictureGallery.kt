package org.openstrap.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.dashboard.NewsTag
import org.openstrap.app.ui.dashboard.PictureKind
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.GlowGrid
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.NewsPill
import org.openstrap.app.ui.glow.PictureCard
import org.openstrap.app.ui.glow.Rim
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.pictures.PictureSize

/**
 * One picture as the gallery shows it: its [kind], the card [title], [value] and [unit], the [ink], and [draw] for the
 * picture at a size, [off] or usual. [rim] is where the card lights when off, [tag] what it says then.
 */
class PictureSample(
    val kind: PictureKind,
    val title: String,
    val value: String,
    val unit: String?,
    val ink: Color,
    val tag: String?,
    val rim: (PictureSize) -> Rim?,
    val draw: @Composable (size: PictureSize, off: Boolean, modifier: Modifier) -> Unit,
)

/** Every picture kind at a Small and a Wide, usual then off (`--es section pictures`). */
@Composable
internal fun PictureGallery() {
    val samples = LinePictureSamples + LevelPictureSamples
    Text("Pictures, ${samples.size} of ${PictureKind.entries.size - 1} kinds", color = Color.White, style = GlowType.cardTitle)
    Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
        PictureCard(Color(0xFF7CF2C9), Modifier.weight(1f).height(GlowGrid.UnitHeight)) { Sample("Usual", "62", null, null) }
        PictureCard(Color(0xFF7CF2C9), Modifier.weight(1f).height(GlowGrid.UnitHeight), rim = Rim.End) { Sample("Off, rim at the end", "41", null, "Below usual") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
        PictureCard(Color(0xFFFF8A3D), Modifier.weight(1f).height(GlowGrid.UnitHeight), rim = Rim.Top) { Sample("Rim at the top", "+0.6", "°C", "Above normal") }
        PictureCard(Color(0xFF4C6FFF), Modifier.weight(1f).height(GlowGrid.UnitHeight), rim = Rim.Corner) { Sample("Rim in the corner", "5:52", null, "Short") }
    }
    samples.forEach { s ->
        Text(s.kind.name, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            for (off in listOf(false, true)) PictureCard(
                s.ink, Modifier.weight(1f).height(GlowGrid.UnitHeight), rim = if (off) s.rim(PictureSize.Small) else null,
            ) {
                Text(s.title, color = Color.White, style = GlowType.cardTitle, maxLines = 1)
                Spacer(Modifier.height(12.dp))
                s.draw(PictureSize.Small, off, Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(8.dp))
                Number(s.value, s.unit, if (off) s.tag else null)
            }
        }
        for (off in listOf(false, true)) PictureCard(
            s.ink, Modifier.fillMaxWidth().height(GlowGrid.UnitHeight), rim = if (off) s.rim(PictureSize.Wide) else null,
        ) {
            Text(s.title, color = Color.White, style = GlowType.cardTitle, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            s.draw(PictureSize.Wide, off, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(8.dp))
            Number(s.value, s.unit, if (off) s.tag else null)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Sample(title: String, value: String, unit: String?, tag: String?) {
    Text(title, color = Color.White, style = GlowType.cardTitle, maxLines = 1)
    Spacer(Modifier.weight(1f))
    Number(value, unit, tag)
}

@Composable
private fun Number(value: String, unit: String?, tag: String?) {
    org.openstrap.app.ui.glow.NumberWithTag(tag?.let { NewsTag(it, goal = false) }) {
        Row(verticalAlignment = Alignment.Bottom) {
            DotMatrixText(value, dot = DotSize.Edge)
            if (unit != null) Text(unit, color = Color.White.copy(alpha = 0.7f), style = GlowType.secondary, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** A stand-in until a picture is drawn: a dim box. */
@Composable
internal fun PictureStub(modifier: Modifier) {
    Spacer(modifier.background(Color.White.copy(alpha = 0.06f)))
}
