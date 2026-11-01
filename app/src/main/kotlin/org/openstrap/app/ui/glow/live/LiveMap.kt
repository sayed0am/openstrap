package org.openstrap.app.ui.glow.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.theme.MetricColors
import androidx.compose.foundation.layout.Column
import org.openstrap.app.ui.workout.WorkoutCopy
import org.openstrap.app.ui.workout.LiveView
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.device.WorkoutState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.Route
import org.openstrap.app.ui.glow.*

/** The live heart rate on the map: a pill in the zone's [color], a dot beating at it, the number in dots. */
@Composable
internal fun BpmChip(bpm: Int?, color: Color, paused: Boolean, modifier: Modifier) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bpm != null) {
            PulseDot(Color.Black, bpm, 8.dp)
            Spacer(Modifier.width(10.dp))
            DotMatrixText("$bpm", dot = DotSize.Edge, color = Color.Black)
            Spacer(Modifier.width(6.dp))
            Text("bpm", color = Color.Black, style = MediumBody)
        } else Text(if (paused) "Paused" else "No heart rate yet", color = Color.Black, style = MediumBody)
    }
}

/**
 * A distance workout's map page (board Map view): the map from the top of the screen, with minimize, the heart rate
 * in its zone's [color] and the GPS over it, route only and follow me on the right; under it a black panel with the
 * distance, the moving time and the pace (a ride's speed), the latest kilometre, the page dots and the controls. A
 * drag on the map pans it and stops following; follow me fits the route again.
 */
@Composable
internal fun LiveMapScreen(
    r: WorkoutState.Recording,
    route: List<Route.Point>,
    readings: List<Pair<Long, Int>>,
    bpm: Int?,
    color: Color,
    connected: Boolean,
    contentPadding: PaddingValues,
    controls: LiveControls,
    lap: (@Composable () -> Unit)?,
    figures: List<Pair<String?, String>>,
    /** The moving time, read by its own text so the page isn't composed again each second. */
    time: () -> Long,
    page: Int,
    onPage: (Int) -> Unit,
    /** Under a bar shared with the other view ([LiveBottomBar]): the panel leaves this much room for it. */
    bottomRoom: Dp? = null,
) {
    BackHandler(onBack = controls.onMinimize)
    val density = LocalDensity.current
    var panel by remember { mutableStateOf(344.dp) }
    var routeOnly by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    // A reading comes every second: the zone colours follow every tenth, the line every fix.
    val stretches = remember(route.size, readings.size / 10) { Route.byZone(route, readings, r.maxHeartRate) }
    val here = route.lastOrNull { it.accuracy <= Route.MAX_ACCURACY_M }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + contentPadding.calculateTopPadding() + 8.dp
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The map runs under the panel's rounded top, so no black shows at its corners.
        Box(Modifier.fillMaxWidth().fillMaxHeight().padding(bottom = (panel - PANEL_OVERLAP).coerceAtLeast(0.dp))) {
            WorkoutMap(
                stretches, routeOnly, Modifier.fillMaxSize(), here,
                fitPadding = PaddingValues(start = 28.dp, top = top + 64.dp, end = 76.dp, bottom = PANEL_OVERLAP + 28.dp),
                creditBottom = PANEL_OVERLAP + 14.dp,
                hereColor = color.takeIf { bpm != null },
                follow = follow, onPanned = { follow = false },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = top, start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(OverMap).pressClickable(controls.onMinimize, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(AppIcons.CaretDown, "Minimize", tint = Color.White, modifier = Modifier.size(18.dp)) }
                BpmChip(bpm, color, r.paused, Modifier)
                Spacer(Modifier.weight(1f))
                val acc = route.lastOrNull()?.accuracy
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(OverMap).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (acc != null && acc <= Route.MAX_ACCURACY_M) TrendGood else TrendBad))
                    Spacer(Modifier.width(6.dp))
                    Text(WorkoutCopy.gpsPill(acc), color = Color.White, style = GlowType.secondary, maxLines = 1)
                }
            }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = PANEL_OVERLAP + 44.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MapButton(AppIcons.MapFold, if (routeOnly) "Show the map" else "Show route only, no map", lit = routeOnly) { routeOnly = !routeOnly }
                MapButton(AppIcons.NavigationArrow, "Follow my position", lit = follow) { follow = true }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { panel = with(density) { it.height.toDp() } }
                .clip(org.openstrap.app.ui.glow.GlowLayout.SheetShape).background(Color.Black)
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = contentPadding.calculateBottomPadding() + if (bottomRoom != null) 0.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The moving time in the middle and larger: on this page it isn't above the views.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
                LiveStat(figures[0].second, Modifier.weight(1f)) { LiveValue(figures[0].first) }
                StatRule()
                LiveStat("Moving time", Modifier.weight(1.3f)) { ClockText(time, GlowType.mapClock, maxLines = 1) }
                StatRule()
                LiveStat(figures[1].second, Modifier.weight(1f)) { LiveValue(figures[1].first) }
            }
            LastSplit(route, readings, r.workout.pauseRanges)
            if (bottomRoom != null) {
                Spacer(Modifier.height(bottomRoom))
                return@Column
            }
            if (!connected) Note("The strap isn't connected. Recording carries on, and the minutes missed are filled from its own history after it syncs.")
            r.endPrompt?.let { at -> StoppedPrompt(r, at, controls.onEndAtPrompt, controls.onKeepGoing) }
            Box(Modifier.fillMaxWidth().semantics { contentDescription = "View ${page + 1} of 2" }) {
                PageDots(page, LiveView.entries.size, onSelect = onPage)
            }
            ControlsRow(r, controls, lap, labelled = true, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** How far the map runs under the panel's rounded top. */
private val PANEL_OVERLAP = 28.dp

/** Buttons and pills drawn over the map: near black, a little see-through. */
private val OverMap = Color(0xD9141416)

/** The moving time between the map page's figures. */

/** A round button over the map, white while what it does is on ([lit]). */
@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, lit: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (lit) Color.White else OverMap).pressClickable(onClick, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (lit) Color.Black else Color.White, modifier = Modifier.size(20.dp)) }
}

/** The latest whole kilometre on a dark row with how long ago it ended, until the next; nothing before the first. */
@Composable
private fun LastSplit(route: List<Route.Point>, readings: List<Pair<Long, Int>>, pauses: List<LongRange>) {
    val splits = remember(route.size, pauses) { Route.splits(route, pauses) }
    val now = nowTicking()
    val (line, ago) = WorkoutCopy.lastSplit(splits, readings, route.firstOrNull()?.t, now) ?: return
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(Raised).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MetricColors.steps))
        Spacer(Modifier.width(10.dp))
        Text(line, color = Color.White, style = GlowType.body.copy(fontFeatureSettings = "tnum"), maxLines = 1, modifier = Modifier.weight(1f))
        Text(ago, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, maxLines = 1)
    }
}
