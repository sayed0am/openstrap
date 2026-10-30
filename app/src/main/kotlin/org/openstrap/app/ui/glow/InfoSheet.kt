package org.openstrap.app.ui.glow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.dashboard.Info
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * The info sheet (spec 6.2): what a number means, the scale it's read on with where you are, and how it's worked out,
 * what moves it and why it might be off, folded until asked for. Opened from the (i) on a detail or a hero, over the
 * screen it came from.
 */

/**
 * [info] as a sheet over the screen, lit in [color]. [here] is the bar of [Info.scale] you're in (null leaves every bar
 * dim), [hereLine] the line under the scale ("You are here. Your normal is 44 to 58 ms, from 30 days."), and [notes]
 * what's particular about today, under Why it might be off. [rows], for a sheet that isn't about a number (the workout
 * Ready screen), replaces the three folded rows with its own titles and texts.
 */
@Composable
fun InfoSheet(
    info: Info, color: Color, here: Int?, hereLine: String?, onDismiss: () -> Unit, notes: List<String> = emptyList(),
    rows: List<Pair<String, String>>? = null,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    org.openstrap.app.ui.components.GlassSheet(onDismiss) {
        Column(
            Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 20.dp),
        ) {
            Text(info.name, color = Color.White.copy(alpha = 0.5f), style = GlowType.secondary)
            Text(
                info.meaning, color = Color.White, modifier = Modifier.padding(top = 6.dp),
                style = GlowType.sentence,
            )
            // Some numbers have no scale to place them on (naps, breathing disturbances, whose only words are apnea bands).
            if (info.scale.isNotEmpty()) Scale(info.scale, color, here, Modifier.padding(top = 22.dp))
            if (hereLine != null) Text(
                hereLine, color = Color.White, style = GlowType.secondary,
                modifier = Modifier.padding(top = if (info.scale.isEmpty()) 16.dp else 10.dp),
            )
            Column(
                Modifier.padding(top = 24.dp).fillMaxWidth().background(Color(0xFF141416), GlowLayout.CardShape).padding(horizontal = 18.dp),
            ) {
                val folds = rows ?: listOf(
                    tr(R.string.info_sheet_how_it_s_worked) to info.how,
                    tr(R.string.common_what_moves_it) to info.moves,
                    tr(R.string.info_sheet_why_it_might_be) to (listOf(info.off) + notes).joinToString("\n\n"),
                )
                folds.forEachIndexed { i, (title, text) -> Fold(title, text, divider = i > 0) }
            }
        }
    }
}

/**
 * The scale as coloured bars, low to high, each named under it; the bar you're in is lit and glows. A name may wrap
 * between its words but never inside one: when the widest word of any name doesn't fit its bar, every name steps
 * down to the caption role together, so the row keeps one type size from the design system.
 */
@Composable
private fun Scale(labels: List<String>, color: Color, here: Int?, modifier: Modifier = Modifier) {
    val gap = 6.dp
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cell = ((constraints.maxWidth - with(LocalDensity.current) { gap.roundToPx() } * (labels.size - 1)) / labels.size)
        val style = remember(labels, cell) { fitWords(labels, listOf(GlowType.secondary, GlowType.caption), cell, measurer) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            labels.forEachIndexed { i, label ->
                val on = i == here
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(10.dp)
                            .let { if (on) it.shadow(10.dp, CircleShape, ambientColor = color, spotColor = color) else it }
                            .background(if (on) color else color.copy(alpha = 0.18f + 0.12f * i / (labels.size - 1).coerceAtLeast(1)), CircleShape),
                    )
                    Text(
                        label, color = if (on) Color.White else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                        style = style.copy(fontWeight = if (on) FontWeight.Medium else FontWeight.Normal),
                    )
                }
            }
        }
    }
}

/**
 * The first of [roles] in which the widest single word of [labels] fits [width] px on one line, measured at Medium,
 * the lit bar's weight and the wider one. The last, the smallest, when none does.
 */
private fun fitWords(labels: List<String>, roles: List<TextStyle>, width: Int, measurer: TextMeasurer): TextStyle {
    val words = labels.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
    if (words.isEmpty() || width <= 0) return roles.first()
    fun widest(s: TextStyle) = words.maxOf { measurer.measure(it, s.copy(fontWeight = FontWeight.Medium), softWrap = false, maxLines = 1).size.width }
    return roles.firstOrNull { widest(it) <= width } ?: roles.last()
}

/** A folded row: its [title] with a caret, opening onto [text]. */
@Composable
private fun Fold(title: String, text: String, divider: Boolean = true) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "fold caret")
    Column(
        Modifier.fillMaxWidth()
            .let { if (divider) it.drawBehind { drawLine(RowHairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else it },
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) tr(R.string.info_sheet_fold) else tr(R.string.common_open)) { open = !open }.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Color.White, style = GlowType.body, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(12.dp))
            Icon(AppIcons.CaretDown, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp).rotate(turn))
        }
        AnimatedVisibility(open) {
            Text(text, color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary, modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}
