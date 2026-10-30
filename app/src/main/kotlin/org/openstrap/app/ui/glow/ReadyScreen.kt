package org.openstrap.app.ui.glow

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.openstrap.app.data.SportSettings
import org.openstrap.app.data.Strength
import org.openstrap.app.data.WorkoutPlan
import org.openstrap.app.data.WorkoutSetup
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
import org.openstrap.app.ui.workout.StartCopy
import org.openstrap.app.ui.workout.WorkoutCopy
import org.openstrap.app.ui.workout.familyColor
import org.openstrap.app.data.Structure

/* -------------------------------------------- Ready screen ------------------------------------------- */

/** What opens over Ready. */
private enum class ReadySheet { Settings, More, About }

/**
 * Ready (workouts spec 2.2): the same frame for every sport. Close, the sport as a pill (tap to change it), the
 * settings; the live heart rate large, one status line saying what the strap or phone will do, the sport's one choice,
 * and Start named for the sport. Recording starts even when the strap is away; the strap's history fills in after it
 * syncs.
 *
 * Everything opens on what was chosen last time for the sport ([settings]); Ready never asks what it can answer
 * itself. The Outdoor or Indoor switch picks the sport type saved ([Workouts.Sides]) and opens on the side last chosen
 * ([sides], saved through [onSide]). An outdoor sport warms GPS up while Ready shows and says how close the fix is; Start
 * never waits for it. The goal chips are the sport's two most used goals ([goalChips]), More has the rest.
 *
 * Strength shows its plan and steppers instead of the heart rate: the sets, the rest, and the level that ends rest
 * sooner. [onStart] gets the workout to record and the settings to open on next time.
 */
@Composable
fun GlowWorkoutReadyScreen(
    sportType: Int,
    pickSport: Boolean,
    connection: ConnectionState,
    liveHeartRate: Int?,
    contentPadding: PaddingValues,
    onSport: (Int) -> Unit,
    onStart: (WorkoutPlan, SportSettings) -> Unit,
    onBack: () -> Unit,
    settings: SportSettings = SportSettings(),
    goalChips: List<WorkoutTarget> = WorkoutSetup.defaultGoals(Workouts.family(sportType)),
    recentSports: List<Int> = emptyList(),
    sides: Map<Int, Boolean> = emptyMap(),
    onSide: (sport: Int, indoor: Boolean) -> Unit = { _, _ -> },
    onTurnOnLocation: (then: () -> Unit) -> Unit = { it() },
    /** kcal goals are offered: there's a profile to work kcal out from. */
    kcalTargets: Boolean = false,
    /** Run intervals saved by name, for More, and saving one ([onSaveIntervals], its name and code). */
    saved: List<org.openstrap.app.data.db.SavedWorkout> = emptyList(),
    onSaveIntervals: (name: String, structure: String) -> Unit = { _, _ -> },
    /** Rounds to open on instead of last time's, from a saved workout picked on Choose. */
    structure: String? = null,
) {
    var picking by rememberSaveable { mutableStateOf(pickSport) }
    val pair = Workouts.sidesOf(sportType)
    // The side picked on this screen; until then the one you last chose for the sport, or its default.
    var chosenIndoor by rememberSaveable(sportType) { mutableStateOf<Boolean?>(null) }
    val indoor = chosenIndoor ?: Workouts.indoorSide(sportType, sides)
    val type = pair?.type(indoor == true) ?: sportType
    val family = Workouts.family(type)
    // Last time's settings until changed here. Keyed on them too: the stored ones load a moment after the screen opens.
    val key = Workouts.tileType(sportType)
    var goalCode by rememberSaveable(key, settings) { mutableStateOf(settings.goal) }
    var askToEnd by rememberSaveable(key, settings) { mutableStateOf(settings.askToEnd) }
    var gpsOn by rememberSaveable(key, settings) { mutableStateOf(settings.gps) }
    var keepRoute by rememberSaveable(key, settings) { mutableStateOf(settings.keepRoute) }
    val lastRest = Strength.Rest.parse(settings.rest) ?: Strength.Rest.DEFAULT
    var sets by rememberSaveable(key, settings) { mutableStateOf(settings.sets) }
    var restSeconds by rememberSaveable(key, settings) { mutableStateOf(lastRest.seconds) }
    var restBelow by rememberSaveable(key, settings) { mutableStateOf(lastRest.belowBpm) }
    var log by rememberSaveable(key, settings) { mutableStateOf(settings.log) }
    // HIIT's rounds (Tabata unless last time was another), or a run's reps; null is Free, or no reps.
    var structureCode by rememberSaveable(key, settings, structure) {
        mutableStateOf(structure ?: settings.structure ?: Structure.TABATA.code.takeIf { family == Workouts.Family.Intervals })
    }
    var sheet by rememberSaveable { mutableStateOf<ReadySheet?>(null) }
    val context = LocalContext.current
    var locationOn by remember { mutableStateOf(locationAvailable(context)) }
    LifecycleResumeEffect(Unit) {
        locationOn = locationAvailable(context)
        onPauseOrDispose { }
    }
    val outdoor = Workouts.gpsOffered(sportType, indoor)
    val useGps = outdoor && gpsOn && locationOn
    val strength = family == Workouts.Family.Strength
    // A distance only where there is one; a kcal goal picked before the profile went is dropped with its chip.
    // Rounds only where they fit: HIIT, and reps for a run.
    val rounds = Structure.parse(structureCode)?.takeIf { Structure.fits(it, type) }
    val goal = WorkoutTarget.parse(goalCode)?.takeIf { rounds == null && WorkoutSetup.offered(it, useGps || type in Workouts.ON_FOOT, kcalTargets) }
    // GPS warms up while Ready shows, so the fix is often there by Start.
    var accuracy by remember(useGps) { mutableStateOf<Float?>(null) }
    if (useGps) DisposableEffect(Unit) {
        val location = WorkoutLocation(context) { accuracy = it.accuracy }
        location.start()
        onDispose { location.stop() }
    }
    val haptics = rememberHaptics()
    if (picking) {
        SportPicker(type, recentSports, contentPadding, onPick = { onSport(it); picking = false }, onBack = { if (pickSport) onBack() else picking = false })
        return
    }
    BackHandler(onBack = onBack)
    val start = {
        haptics.perform(HapticEvent.WorkoutStart)
        val rest = Strength.Rest(restSeconds, restBelow)
        onStart(
            WorkoutPlan(
                type, askToEnd, goal.takeIf { !strength }, useGps, useGps && keepRoute,
                rest.takeIf { strength }, sets.takeIf { strength }, logSets = strength && log, structure = rounds?.code,
            ),
            settings.copy(
                goal = if (strength) settings.goal else goal?.code, askToEnd = askToEnd, gps = gpsOn, keepRoute = keepRoute,
                rest = if (strength) rest.code else settings.rest, sets = if (strength) sets else settings.sets,
                log = if (strength) log else settings.log, structure = rounds?.code,
            ),
        )
    }
    WorkoutPage(
        Color.Transparent, contentPadding,
        pinned = { WorkoutButton(WorkoutCopy.startLabel(type), Color.White, Color.Black, start, icon = AppIcons.PlayFill, height = 64.dp) },
    ) {
        Box(Modifier.fillMaxWidth()) {
            RoundIcon(AppIcons.X, "Close", onBack, Modifier.align(Alignment.CenterStart))
            Box(Modifier.align(Alignment.Center)) { Pill(Workouts.shortName(type), null, onClick = { picking = true }, caret = true) }
            RoundIcon(AppIcons.SlidersHorizontal, "${Workouts.shortName(type)} settings", { sheet = ReadySheet.Settings }, Modifier.align(Alignment.CenterEnd))
        }
        // Outdoor or indoor is a switch, not a second sport.
        if (pair != null) SideSwitch(listOf(pair.outdoorName, pair.indoorName), if (indoor == true) 1 else 0) { i ->
            chosenIndoor = i == 1
            onSide(pair.outdoor, i == 1)
        }
        if (strength) {
            StrapLine(connection, liveHeartRate)
            StrengthPlan(sets, restSeconds, restBelow, log, { sets = it }, { restSeconds = it }, { restBelow = it }, { log = it })
            return@WorkoutPage
        }
        if (family == Workouts.Family.Intervals) {
            StrapLine(connection, liveHeartRate)
            IntervalsPlan(rounds, { structureCode = it?.code })
            return@WorkoutPage
        }
        ReadyHeart(connection, liveHeartRate)
        // The one status line: GPS for an outdoor sport, else what the strap will do.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                outdoor && !locationOn -> StatusPill(WorkoutCopy.LOCATION_OFF, TrendBad) {
                    SmallPill("Turn on") {
                        if (!WorkoutLocation.granted(context)) onTurnOnLocation { locationOn = locationAvailable(context) }
                        else context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                }
                outdoor && !gpsOn -> StatusPill(StartCopy.GPS_OFF, null)
                outdoor -> StatusPill(StartCopy.gpsStatus(accuracy), if (StartCopy.gpsReady(accuracy)) TrendGood else TrendBad)
                family == Workouts.Family.Games -> StatusText(StartCopy.GAMES_STATUS)
                rounds != null -> StatusText(StartCopy.INTERVALS_STATUS)
                else -> StatusText(StartCopy.goalStatus(goal))
            }
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChipLabel(StartCopy.goalTitle(family))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val chips = if (family == Workouts.Family.MindBody) WorkoutSetup.LENGTHS
                    else (goalChips.filter { WorkoutSetup.offered(it, useGps || type in Workouts.ON_FOOT, kcalTargets) } + listOfNotNull(goal)).distinct()
                Chip(if (family == Workouts.Family.MindBody) StartCopy.OPEN else StartCopy.noGoal(type), goal == null && rounds == null, Color.White) {
                    goalCode = null; structureCode = null
                }
                chips.forEach { g -> Chip(StartCopy.goalLabel(g), g == goal, Color.White) { goalCode = g.code; structureCode = null } }
                // Run reps chosen in More show as a chip of their own.
                rounds?.let { Chip(StartCopy.structureName(it), true, Color.White) { sheet = ReadySheet.More } }
                if (family != Workouts.Family.MindBody) Chip("More", false, Color.White) { sheet = ReadySheet.More }
            }
        }
    }
    when (sheet) {
        ReadySheet.Settings -> PageSheet({ sheet = null }, "${Workouts.shortName(type)} settings") {
            Column {
                if (outdoor) {
                    PageSwitchRow("GPS", WorkoutCopy.gpsNote(gpsOn, type), gpsOn, { gpsOn = it }, divider = false)
                    if (gpsOn) PageSwitchRow("Keep the route", WorkoutCopy.routeNote(keepRoute), keepRoute, { keepRoute = it })
                }
                PageSwitchRow(
                    "Ask to end when I stop", "After 8 min near resting heart rate and no steps, the strap buzzes and asks.",
                    askToEnd, { askToEnd = it }, divider = outdoor,
                )
            }
            PageButton("About recording", { sheet = ReadySheet.About }, icon = AppIcons.Info)
        }
        ReadySheet.More -> PageSheet({ sheet = null }, "Goal", note = StartCopy.goalStatus(goal)) {
            WorkoutSetup.moreGoals(kcalTargets).forEach { (kind, goals) ->
                val shown = goals.filter { WorkoutSetup.offered(it, useGps || type in Workouts.ON_FOOT, kcalTargets) }
                if (shown.isEmpty()) return@forEach
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipLabel(kind)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.forEach { g -> Chip(StartCopy.goalLabel(g), g == goal, Color.White) { goalCode = g.code; structureCode = null; sheet = null } }
                    }
                }
            }
            // A run can be reps instead: of a distance with GPS or on foot, or of a time.
            if (type in Workouts.RUNS) RunIntervals(
                rounds as? Structure.Reps, saved.filter { Workouts.tileType(it.sport) == key },
                onUse = { structureCode = it.code; goalCode = null; sheet = null },
                onSave = { name, s -> onSaveIntervals(name, s.code); structureCode = s.code; goalCode = null; sheet = null },
            )
        }
        ReadySheet.About -> InfoSheet(
            Info("Recording", WorkoutCopy.READY_MEANING, emptyList(), "", "", ""), MetricColors.heart, null, null,
            onDismiss = { sheet = null }, rows = WorkoutCopy.readyRows(outdoor),
        )
        null -> Unit
    }
}

/** Whether the phone's location can be used now: allowed, and turned on. */
internal fun locationAvailable(context: Context): Boolean =
    WorkoutLocation.granted(context) && context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

/** A 44 dp round button on the Control surface with its icon, as on the workout pages' top bars. */
@Composable
internal fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(Raised).pressClickable(onClick, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(18.dp)) }
}

/**
 * Ready's heart: the live heart rate from the strap in large dots on a soft heart red glow, no card, "bpm, live from
 * your strap" under it. While the strap is away, what's wrong and that you can start anyway.
 */
@Composable
private fun ReadyHeart(connection: ConnectionState, liveHeartRate: Int?) {
    val hero = WorkoutCopy.readyHero(connection, liveHeartRate)
    val bpm = liveHeartRate.takeIf { hero.live }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 220.dp).drawBehind {
            val radius = size.minDimension * 0.55f
            drawCircle(
                Brush.radialGradient(listOf(MetricColors.heart.copy(alpha = if (bpm != null) 0.45f else 0.18f), Color.Transparent), center, radius),
                radius, center,
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (bpm != null) {
                DotMatrixText(bpm.toString(), dot = DotSize.Detail)
                Text("bpm, live from your strap", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            } else {
                Text(hero.title, color = Color.White, style = GlowType.sentence, textAlign = TextAlign.Center)
                Text(hero.line, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, textAlign = TextAlign.Center)
            }
        }
    }
}

/** The strap in one line with its dot, where Ready shows no heart rate (strength): "Strap ready, 72 bpm". */
@Composable
internal fun StrapLine(connection: ConnectionState, liveHeartRate: Int?) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(6.dp)) { drawCircle(if (connection is ConnectionState.Connected) TrendGood else TrendBad) }
        Spacer(Modifier.width(8.dp))
        Text(StartCopy.strapLine(connection, liveHeartRate), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
    }
}

/** Ready's status line as a quiet pill, with a [dot] for GPS and an optional [action] at its end. */
@Composable
private fun StatusPill(text: String, dot: Color?, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).background(QuietSurface).padding(start = 14.dp, end = if (action != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, style = GlowType.secondary, modifier = Modifier.padding(vertical = 8.dp))
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            action()
        }
    }
}

/** Ready's status line where there's nothing to point at: what the strap will do, centred. */
@Composable
private fun StatusText(text: String) =
    Text(text, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))

/** The status pill's surface, darker than the Control surface so the chips under it stay the things to press. */
private val QuietSurface = Color(0xFF0F0F11)

/**
 * Strength's Ready (workouts spec 2.2): the plan in one sentence on a card lit in strength's orange, then the sets and
 * rest steppers and the level that ends rest sooner, on a quiet panel, so the two never look alike.
 */
@Composable
private fun StrengthPlan(
    sets: Int?, restSeconds: Long, restBelow: Int?,
    log: Boolean, onSets: (Int?) -> Unit, onRest: (Long) -> Unit, onBelow: (Int?) -> Unit, onLog: (Boolean) -> Unit,
) {
    EdgeCard(StrengthColor, Modifier.fillMaxWidth()) {
        Text("Your plan", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        Spacer(Modifier.height(6.dp))
        Text(StartCopy.plan(sets, Strength.Rest(restSeconds, restBelow)), color = Color.White, style = GlowType.sentence)
        Spacer(Modifier.height(8.dp))
        Text(StartCopy.STRENGTH_STATUS, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
    }
    QuietPanel {
        Stepper("Sets", StartCopy.setsValue(sets), "Fewer sets", "More sets", { onSets(Strength.stepSets(sets, -1)) }, { onSets(Strength.stepSets(sets, 1)) })
        Stepper("Rest", StartCopy.restText(restSeconds), "Shorter rest", "Longer rest", { onRest(Strength.stepRest(restSeconds, -1)) }, { onRest(Strength.stepRest(restSeconds, 1)) })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipLabel("End rest sooner below")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Strength.Rest.LEVELS.forEachIndexed { i, level ->
                    val label = level?.let { if (i == Strength.Rest.LEVELS.lastIndex) "$it bpm" else "$it" } ?: "Off"
                    Chip(label, level == restBelow, Color.White) { onBelow(level) }
                }
            }
        }
        PageSwitchRow("Log reps and weight", "A quick card after each set. Off by default.", log, onLog)
    }
}

/** Strength's colour on Ready, the orange of zone 4 the live strength screen keeps. */
internal val StrengthColor = familyColor(Workouts.Family.Strength)

/** A labelled value with round minus and plus buttons either side of it. */
@Composable
private fun Stepper(label: String, value: String, less: String, more: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = GlowType.body, modifier = Modifier.weight(1f))
        RoundIcon(AppIcons.Minus, less, onLess)
        Text(value, color = Color.White, style = GlowType.sentence, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
        RoundIcon(AppIcons.Plus, more, onMore)
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
internal fun SettingRow(
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
            .semantics { role = Role.Button; contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The label over a row of chips, in Caption. */
@Composable
internal fun ChipLabel(text: String) =
    Text(text, color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.caption, modifier = Modifier.padding(start = 4.dp))

/** A choice as a round chip on the Control surface, the chosen one lit in [color]. */
@Composable
internal fun Chip(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Text(
        label, color = if (on) Color.Black else Color.White, style = GlowType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1,
        modifier = Modifier.height(40.dp).clip(CircleShape).background(if (on) color else Raised)
            .pressClickable(onClick, CircleShape).semantics { selected = on }
            .padding(horizontal = 16.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

/**
 * HIIT's Ready (workouts spec 2.2, 2.5, board HiitReady): the presets as tiles, the one chosen lit in work red; what it
 * is on a card (its total time, a strip of work and rest, and the work, rest and rounds); and Warm up first. Free has
 * no timer: heart rate only, and laps mark the rounds.
 */
@Composable
private fun IntervalsPlan(rounds: Structure?, onChange: (Structure?) -> Unit) {
    val chosen = rounds?.warm(false)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StartCopy.PRESETS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (preset, name, note) ->
                    val on = preset?.code == chosen?.code
                    Column(
                        Modifier.weight(1f).clip(GlowLayout.TileShape).background(if (on) MetricColors.heart else QuietSurface)
                            .pressClickable({ onChange(preset?.warm(rounds?.warmUp == true)) }, GlowLayout.TileShape)
                            .semantics { selected = on }.padding(14.dp),
                    ) {
                        Text(name, color = if (on) Color.Black else Color.White, style = GlowType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1)
                        Text(note, color = (if (on) Color.Black else Color.White).copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 2)
                    }
                }
            }
        }
    }
    if (rounds != null) {
        EdgeCard(MetricColors.heart, Modifier.fillMaxWidth()) {
            Row {
                Text(StartCopy.structureName(rounds), color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
                StartCopy.totalTime(rounds)?.let { Text(workoutClock(it), color = Color.White, style = GlowType.sentence) }
            }
            if (rounds is Structure.Timed) {
                Spacer(Modifier.height(12.dp))
                RoundsStrip(rounds)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                val figures = when (rounds) {
                    is Structure.Timed -> listOf("${rounds.work} s" to "Work", "${rounds.rest} s" to "Rest", "${rounds.rounds}" to "Rounds")
                    is Structure.EveryMinute -> listOf(StartCopy.restText(rounds.period) to "Each round", "${rounds.rounds}" to "Rounds")
                    is Structure.Reps -> listOf(StartCopy.structureName(rounds) to "Reps", StartCopy.restText(rounds.rest) to "Rest")
                }
                figures.forEach { (value, label) ->
                    Column(Modifier.weight(1f)) {
                        Text(value, color = Color.White, style = GlowType.sentence, maxLines = 1)
                        Text(label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
                    }
                }
            }
        }
        QuietPanel {
            PageSwitchRow("Warm up first", StartCopy.WARM_UP_NOTE, rounds.warmUp, { onChange(rounds.warm(it)) }, divider = false)
        }
    }
    StatusText(StartCopy.INTERVALS_STATUS)
}

/** Work and rest along the rounds, each as wide as its time: work in heart red, rest in sleep blue. */
@Composable
private fun RoundsStrip(s: Structure.Timed) {
    Row(
        Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = "${s.rounds} rounds of ${s.work} seconds work and ${s.rest} seconds rest" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(s.rounds) { i ->
            Box(Modifier.weight(s.work.toFloat()).height(10.dp).clip(CircleShape).background(MetricColors.heart))
            if (i < s.rounds - 1 && s.rest > 0) Box(Modifier.weight(s.rest.toFloat()).height(10.dp).clip(CircleShape).background(MetricColors.sleepDeep))
        }
    }
}

/**
 * Run intervals in More (workouts spec 2.5): reps of a distance or a time, the rest between, how many; Use them, or
 * Save them by name to find on Choose and here. Up to ten are kept.
 */
@Composable
private fun RunIntervals(current: Structure.Reps?, saved: List<org.openstrap.app.data.db.SavedWorkout>, onUse: (Structure.Reps) -> Unit, onSave: (String, Structure.Reps) -> Unit) {
    val reps = listOf<Pair<Int?, Long?>>(400 to null, 800 to null, 1000 to null, null to 120L, null to 180L)
    var rep by rememberSaveable { mutableStateOf(reps.indexOfFirst { it.first == current?.meters && it.second == current?.seconds }.takeIf { it >= 0 } ?: 0) }
    var rest by rememberSaveable { mutableStateOf(current?.rest ?: 90L) }
    var count by rememberSaveable { mutableStateOf(current?.rounds ?: 6) }
    var name by rememberSaveable { mutableStateOf("") }
    val built = Structure.Reps(reps[rep].first, reps[rep].second, rest, count)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChipLabel("Intervals")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            reps.forEachIndexed { i, (m, s) ->
                Chip(m?.let { if (it >= 1000) "${it / 1000} km" else "$it m" } ?: StartCopy.restText(s ?: 0), i == rep, Color.White) { rep = i }
            }
        }
        ChipLabel("Rest between")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60L, 90L, 120L).forEach { s -> Chip(StartCopy.restText(s), s == rest, Color.White) { rest = s } }
        }
        Stepper("Reps", "$count", "Fewer reps", "More reps", { count = (count - 1).coerceAtLeast(2) }, { count = (count + 1).coerceAtMost(20) })
        PageButton("Use ${StartCopy.structureName(built)}", { onUse(built) }, Modifier.fillMaxWidth(), primary = true, icon = AppIcons.PlayFill)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.text.BasicTextField(
                name, { name = it.take(40) }, Modifier.weight(1f), singleLine = true,
                textStyle = GlowType.body.copy(color = Color.White), cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth().height(48.dp).clip(GlowLayout.TileShape).background(Raised).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        if (name.isEmpty()) Text("Name, like Tuesday intervals", color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.body, maxLines = 1)
                        field()
                    }
                },
            )
            PageButton("Save", { onSave(name.trim(), built) }, enabled = name.isNotBlank(), icon = AppIcons.Plus)
        }
        if (saved.isNotEmpty()) {
            ChipLabel("Saved")
            Column {
                saved.forEachIndexed { i, w ->
                    val s = Structure.parse(w.structure) as? Structure.Reps ?: return@forEachIndexed
                    PageRow(w.name, value = StartCopy.structureName(s), divider = i > 0, onClick = { onUse(s) })
                }
            }
        }
    }
}
