package org.openstrap.app.ui.glow

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.min
import org.openstrap.app.data.Strength
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutLocation
import org.openstrap.app.ui.components.HapticEvent
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.components.rememberHaptics
import org.openstrap.app.ui.dashboard.Info
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.SportIcons
import org.openstrap.app.ui.workout.WorkoutCopy

/* -------------------------------------------- Ready screen ------------------------------------------- */

/**
 * One screen, not a wizard (spec 6.3): close, the sport (tap to change), the Outdoor or Indoor switch for a sport with
 * both, the strap as the hero with its live heart rate, the settings as list rows, and Start pinned to the bottom.
 * Recording starts even when the strap is away; the strap's history fills in after it syncs.
 *
 * The switch picks the sport type saved ([Workouts.Sides]) and whether GPS is offered; it opens on the side you last
 * chose for the sport ([sides], saved through [onSide]). GPS is on for the outdoor side and sports done outdoors, with
 * its row to turn it off for this workout. While the phone's location is off or not allowed, the row says so with
 * Turn on ([onTurnOnLocation] asks for the permission), and Start works without GPS.
 */
@Composable
fun GlowWorkoutReadyScreen(
    sportType: Int,
    pickSport: Boolean,
    connection: ConnectionState,
    liveHeartRate: Int?,
    contentPadding: PaddingValues,
    onSport: (Int) -> Unit,
    onStart: (sportType: Int, askToEnd: Boolean, target: WorkoutTarget?, useGps: Boolean, keepRoute: Boolean, rest: Strength.Rest?, setsTarget: Int?) -> Unit,
    onBack: () -> Unit,
    recentSports: List<Int> = emptyList(),
    sides: Map<Int, Boolean> = emptyMap(),
    onSide: (sport: Int, indoor: Boolean) -> Unit = { _, _ -> },
    onTurnOnLocation: (then: () -> Unit) -> Unit = { it() },
    /** kcal targets are offered: there's a profile to work kcal out from. */
    kcalTargets: Boolean = false,
) {
    var picking by rememberSaveable { mutableStateOf(pickSport) }
    var askToEnd by rememberSaveable { mutableStateOf(true) }
    var targetCode by rememberSaveable { mutableStateOf<String?>(null) }
    val pair = Workouts.sidesOf(sportType)
    // The side picked on this screen; until then the one you last chose for the sport, or its default.
    var chosenIndoor by rememberSaveable(sportType) { mutableStateOf<Boolean?>(null) }
    val indoor = chosenIndoor ?: Workouts.indoorSide(sportType, sides)
    val type = pair?.type(indoor == true) ?: sportType
    val outdoor = Workouts.gpsOffered(sportType, indoor)
    // GPS is on wherever it's offered; turning it off holds for this workout only.
    var gpsOff by rememberSaveable(type, indoor) { mutableStateOf(false) }
    var keepRoute by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var locationOn by remember { mutableStateOf(locationAvailable(context)) }
    LifecycleResumeEffect(Unit) {
        locationOn = locationAvailable(context)
        onPauseOrDispose { }
    }
    val useGps = outdoor && !gpsOff && locationOn
    val strength = type in Strength.SPORTS
    var setsTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var restSeconds by rememberSaveable { mutableStateOf(Strength.Rest.DEFAULT.seconds) }
    var restBelow by rememberSaveable { mutableStateOf<Int?>(null) }
    // A kcal target picked before the profile went is dropped with its chip.
    val target = WorkoutTarget.parse(targetCode)?.takeIf { it in WorkoutTarget.offered(kcalTargets) }
    var info by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    if (picking) {
        SportPicker(type, recentSports, contentPadding, onPick = { onSport(it); picking = false }, onBack = { if (pickSport) onBack() else picking = false })
        return
    }
    BackHandler(onBack = onBack)
    // Start takes the target's colour, previewing the workout mode; plain white with none.
    val accent = targetColor(target)
    WorkoutPage(
        accent, contentPadding,
        pinned = {
            WorkoutButton(WorkoutCopy.startLabel(type), accent, Color.Black, {
                haptics.perform(HapticEvent.WorkoutStart)
                onStart(
                    type, askToEnd, target, useGps, useGps && keepRoute,
                    Strength.Rest(restSeconds, restBelow).takeIf { strength }, setsTarget.takeIf { strength },
                )
            }, icon = AppIcons.PlayFill)
        },
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Raised).pressClickable(onBack, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.X, "Close", tint = Color.White, modifier = Modifier.size(18.dp)) }
            Box(Modifier.align(Alignment.Center)) { Pill(Workouts.shortName(type), null, onClick = { picking = true }, caret = true) }
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ready when you are", color = Color.White, style = GlowType.pageTitle, maxLines = 1)
            Text("Recording starts when you tap Start.", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        // Outdoor or indoor is a switch, not a second sport.
        if (pair != null) SideSwitch(listOf(pair.outdoorName, pair.indoorName), if (indoor == true) 1 else 0) { i ->
            chosenIndoor = i == 1
            onSide(pair.outdoor, i == 1)
        }
        StrapHero(connection, liveHeartRate) { info = true }
        // GPS for the outdoor side and sports done outdoors only; indoors there's no row at all.
        if (outdoor) ListPanel {
            if (locationOn) {
                SettingRow(SportIcons.Location, "GPS", WorkoutCopy.gpsNote(useGps, type), divider = false, onClick = { gpsOff = !gpsOff }) {
                    GlowSwitch(!gpsOff, { gpsOff = !it })
                }
                if (useGps) SettingRow(AppIcons.Route01, "Keep the route", WorkoutCopy.routeNote(keepRoute), onClick = { keepRoute = !keepRoute }) {
                    GlowSwitch(keepRoute, { keepRoute = it })
                }
            } else {
                val turnOn = {
                    if (!WorkoutLocation.granted(context)) onTurnOnLocation { locationOn = locationAvailable(context) }
                    else context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                SettingRow(SportIcons.Location, WorkoutCopy.LOCATION_OFF, WorkoutCopy.LOCATION_OFF_NOTE, divider = false, onClick = turnOn) {
                    SmallPill("Turn on", turnOn)
                }
            }
        }
        // Strength counts sets and rest; the strap buzzes when rest is over, so the phone can stay in a pocket.
        if (strength) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipLabel("Sets")
            Chips(listOf("Open") + listOf(3, 4, 5).map { "$it sets" }, listOf(null, 3, 4, 5).indexOf(setsTarget), Color.White) { setsTarget = listOf(null, 3, 4, 5)[it] }
            ChipLabel("Rest. The strap buzzes when it's over.")
            Chips(Strength.Rest.TIMES.map { "${it} s" }, Strength.Rest.TIMES.indexOf(restSeconds), Color.White) { restSeconds = Strength.Rest.TIMES[it] }
            ChipLabel("Or sooner, once heart rate drops below")
            Chips(Strength.Rest.LEVELS.map { it?.let { b -> "$b bpm" } ?: "Off" }, Strength.Rest.LEVELS.indexOf(restBelow), Color.White) { restBelow = Strength.Rest.LEVELS[it] }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipLabel(WorkoutCopy.targetHint(target))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<WorkoutTarget?>(null) + WorkoutTarget.offered(kcalTargets)).forEach { t ->
                    Chip(targetLabel(t), t == target, targetColor(t)) { targetCode = t?.code }
                }
            }
        }
        ListPanel {
            SettingRow(null, "Ask to end when I stop", "After 8 min near resting heart rate and no steps, the strap buzzes and asks.", divider = false, onClick = { askToEnd = !askToEnd }) {
                GlowSwitch(askToEnd, { askToEnd = it })
            }
        }
    }
    if (info) InfoSheet(
        Info("Recording", WorkoutCopy.READY_MEANING, emptyList(), "", "", ""), accent, null, null,
        onDismiss = { info = false }, rows = WorkoutCopy.readyRows(outdoor),
    )
}

/** Whether the phone's location can be used now: allowed, and turned on. */
internal fun locationAvailable(context: Context): Boolean =
    WorkoutLocation.granted(context) && context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

/**
 * Ready's hero, the strap (spec 6.3): a glow in heart red lit from below with the live heart rate in the hero's dots,
 * pulsing at the real bpm, "Live from your strap." and the battery when low. While the strap is away it drops to an
 * outline card that says you can start anyway. The (i) explains recording, GPS and the route.
 */
@Composable
private fun StrapHero(connection: ConnectionState, liveHeartRate: Int?, onInfo: () -> Unit) {
    val hero = WorkoutCopy.readyHero(connection, liveHeartRate)
    val bpm = liveHeartRate.takeIf { hero.live }
    val style = GlowStyle.chart(MetricColors.heart).copy(
        lightAngle = LightAngle.Below,
        motion = if (bpm != null) GlowMotion.Heartbeat(bpm) else GlowMotion.Still,
        outline = !hero.live,
    )
    GlowCard(style, Modifier.fillMaxWidth()) {
        Column {
            GlowHeader(hero.title, trailingContent = { RoundButton(AppIcons.Info, "About recording", onInfo, size = 32.dp) })
            if (bpm != null) Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.Bottom) {
                DotMatrixText(bpm.toString(), dot = DotSize.Hero)
                // The word after a number (spec 2.3).
                Text("bpm", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                hero.line, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                modifier = Modifier.padding(top = if (bpm != null) 4.dp else 12.dp),
            )
        }
    }
}

/** The Outdoor or Indoor switch: two halves on a Control pill, the chosen one white. */
@Composable
private fun SideSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(Raised).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(CircleShape).background(if (on) Color.White else Color.Transparent)
                    .pressClickable({ if (!on) onSelect(i) }, CircleShape, scale = 0.97f)
                    .semantics { this.selected = on; role = Role.Tab }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (on) Color.Black else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1,
                )
            }
        }
    }
}

/**
 * A settings row in a list (spec 2.5, Grouped list): an optional white [icon], the [title] in Body with its [note]
 * under it in Secondary, and [trailing] (a switch) on the right. The whole row toggles through [onClick].
 */
@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?, title: String, note: String, divider: Boolean = true,
    onClick: () -> Unit, trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .let { if (divider) it.drawBehind { drawLine(RowHairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else it }
            .padding(vertical = GlowLayout.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = SportIconTint, modifier = Modifier.size(RowIcon))
            Spacer(Modifier.width(RowIconGap))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RowName(title)
            Text(note, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** A small white pill button at the end of a row ("Turn on"). */
@Composable
private fun SmallPill(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.Black, style = GlowType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(Color.White).pressClickable(onClick, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The label over a row of chips, in Caption. */
@Composable
internal fun ChipLabel(text: String) =
    Text(text, color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.caption, modifier = Modifier.padding(start = 4.dp))

/** A choice on a Control tile, the chosen one lit in [color]. */
@Composable
private fun Chip(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Text(
        label, color = if (on) Color.Black else Color.White, style = GlowType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1,
        modifier = Modifier.height(40.dp).clip(GlowLayout.TileShape).background(if (on) color else Raised)
            .pressClickable(onClick, GlowLayout.TileShape).padding(horizontal = 14.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

/** A scrolling row of choices; the chosen one lit in [color]. */
@Composable
private fun Chips(labels: List<String>, selected: Int, color: Color, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label -> Chip(label, i == selected, color) { onSelect(i) } }
    }
}
