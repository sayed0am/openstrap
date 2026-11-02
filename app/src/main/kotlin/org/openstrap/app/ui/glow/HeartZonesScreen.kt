package org.openstrap.app.ui.glow

import org.openstrap.app.ui.glow.draw.EdgeSurface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.MaxHeartRate
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.HeartZonesCopy
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.workout.ZONE_NAMES

/**
 * Heart rate zones (board SettingsHeartZones), from Ready's Your zones and the You page: your max heart rate as the
 * hero, lit in heart red from the left, with where it comes from and Set it myself; then the five zones as a plain
 * list with their heart rates. One set for every sport. A zone opens a sheet to move its edges; [onZones] stores the
 * zones and [onMax] your own max (null learns it again). [max] is null until it's worked out.
 */
@Composable
fun HeartZonesScreen(
    max: MaxHeartRate?,
    zones: HeartZones,
    contentPadding: PaddingValues,
    onMax: (Int?) -> Unit,
    onZones: (HeartZones) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var settingMax by rememberSaveable { mutableStateOf(false) }
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(HeartZonesCopy.TITLE, onBack) }
        item { MaxHero(max) { settingMax = true } }
        item {
            PageRows {
                (0 until Workouts.ZONES).forEach { z ->
                    ZoneRow(z, if (max != null) HeartZonesCopy.zoneBpm(zones, z, max.bpm) else "", divider = z > 0) { if (max != null) editing = z }
                }
            }
        }
        item { Text(HeartZonesCopy.NOTE, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(horizontal = 4.dp)) }
        if (!zones.standard) item {
            PageButton(HeartZonesCopy.RESET, { onZones(HeartZones.STANDARD) }, Modifier.fillMaxWidth(), icon = AppIcons.ArrowCounterClockwise)
        }
    }
    val zone = editing
    if (zone != null && max != null) ZoneSheet(zone, zones, max.bpm, onZones) { editing = null }
    if (settingMax && max != null) MaxSheet(max, { onMax(it); settingMax = false }) { settingMax = false }
}

/** The max as the page's hero: its Doto number on heart red lit from the left, where it comes from, and Set it myself. */
@Composable
private fun MaxHero(max: MaxHeartRate?, onSet: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(GlowLayout.CardShape).background(EdgeSurface)
            .drawBehind {
                val r = size.width * HERO_LIGHT_REACH
                drawCardLight(MetricColors.heart, Offset(0f, size.height / 2), r, r, HeroLight)
            }
            .border(1.dp, MetricColors.heart.copy(alpha = 0.3f), GlowLayout.CardShape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(HeartZonesCopy.MAX_TITLE, color = Color.White.copy(alpha = 0.8f), style = GlowType.body)
        Row(verticalAlignment = Alignment.Bottom) {
            DotMatrixText(max?.bpm?.toString() ?: "0", dot = DotSize.Hero, color = if (max != null) Color.White else Color.White.copy(alpha = 0.2f))
            Spacer(Modifier.width(8.dp))
            Text("bpm", color = Color.White.copy(alpha = 0.8f), style = GlowType.body)
        }
        if (max != null) Text(HeartZonesCopy.source(max), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        PageButton(HeartZonesCopy.SET_IT, onSet, enabled = max != null, icon = AppIcons.PencilEdit)
    }
}

/** How far the max hero's light reaches from the left edge, in card widths. */
private const val HERO_LIGHT_REACH = 1.1f

/** The max hero's light: heart red at 55% on the edge, 12% halfway out. */
private val HeroLight = listOf(0f to 0.55f, 0.5f to 0.12f)

/** A zone on the list: its colour as a short bar, its number and name, and its heart rates. */
@Composable
private fun ZoneRow(zone: Int, bpm: String, divider: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .topHairline(divider, RowHairline)
            .padding(vertical = GlowLayout.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(34.dp).clip(RoundedCornerShape(3.dp)).background(ZONE_COLORS[zone]))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            RowName(HeartZonesCopy.zoneName(zone))
            Text(ZONE_NAMES[zone], color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary)
        }
        Text(bpm, color = Color.White.copy(alpha = 0.85f), style = GlowType.body)
        Spacer(Modifier.width(RowCaretGap))
        TapCaret(ROW_CARET_ALPHA)
    }
}

/** Moving a zone's edges: where it starts and where it ends, a bpm at a time. The zone beside each edge moves too. */
@Composable
private fun ZoneSheet(zone: Int, zones: HeartZones, maxHr: Int, onZones: (HeartZones) -> Unit, onDismiss: () -> Unit) {
    PageSheet(onDismiss, "${HeartZonesCopy.zoneName(zone)}, ${ZONE_NAMES[zone]}", note = HeartZonesCopy.edgesNote(zone)) {
        val start = zones.startBpm(zone, maxHr)
        BpmStepper(HeartZonesCopy.STARTS_AT, start, { onZones(zones.withStart(zone, start - 1, maxHr)) }, { onZones(zones.withStart(zone, start + 1, maxHr)) })
        zones.endBpm(zone, maxHr)?.let { end ->
            BpmStepper(HeartZonesCopy.ENDS_AT, end, { onZones(zones.withEnd(zone, end - 1, maxHr)) }, { onZones(zones.withEnd(zone, end + 1, maxHr)) })
        }
    }
}

/** A heart rate with round minus and plus either side of it. */
@Composable
private fun BpmStepper(label: String, bpm: Int, onLess: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = GlowType.body, modifier = Modifier.weight(1f))
        RoundIcon(AppIcons.Minus, "$label lower", onLess)
        Text("$bpm bpm", color = Color.White, style = GlowType.sentence, modifier = Modifier.width(110.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        RoundIcon(AppIcons.Plus, "$label higher", onMore)
    }
}

/** Setting your max yourself, on the number keyboard; Learn it again when it's already yours. */
@Composable
private fun MaxSheet(max: MaxHeartRate, onMax: (Int?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(max.bpm.toString()) }
    val value = HeartZonesCopy.parseMax(text)
    PageSheet(onDismiss, HeartZonesCopy.SET_TITLE, note = HeartZonesCopy.SET_NOTE) {
        PageField(
            text, { v -> text = v.filter { it.isDigit() }.take(3) }, HeartZonesCopy.SET_FIELD, unit = "bpm", error = text.isNotBlank() && value == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (max.source == MaxHeartRate.Source.Set) PageButton(HeartZonesCopy.LEARN_IT, { onMax(null) }, Modifier.weight(1f), icon = AppIcons.ArrowCounterClockwise)
            PageButton(HeartZonesCopy.SAVE, { onMax(value) }, Modifier.weight(1f), enabled = value != null, primary = true, icon = AppIcons.Tick02)
        }
    }
}
