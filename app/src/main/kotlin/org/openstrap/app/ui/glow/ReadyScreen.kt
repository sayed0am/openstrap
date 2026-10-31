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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.openstrap.app.data.HeartZones
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

/** What opens over Ready: strength's, HIIT's, a game's and mind and body's settings, and what they say about recording. */
private enum class ReadySheet { Settings, About }

/**
 * Ready (workouts spec 2.2): the same frame for every sport. Close and the sport as a pill (tap to change it); the
 * live heart rate, what the strap or phone will do, the sport's one choice, and Start named for the sport. Recording
 * starts even when the strap is away; the strap's history fills in after it syncs.
 *
 * Everything opens on what was chosen last time for the sport ([settings]); Ready never asks what it can answer
 * itself. A sport with an outdoor and an indoor side saves the type of the side chosen ([Workouts.Sides]) and opens
 * on the side last chosen ([sides], saved through [onSide]). An outdoor sport warms GPS up while Ready shows; Start
 * never waits for it.
 *
 * A distance or cardio sport (board ReadyFinal) has no settings sheet: the heart on its own card, where you are with
 * Ask to end when I stop, then the goal, typed in place ([ReadyGoal]); a zone goal reads your zones ([maxHeartRate],
 * [HeartZones.current]) and Your zones opens them ([onYourZones]). Run intervals save by name ([saved],
 * [onSaveIntervals]) and open on rounds picked on Choose ([structure]).
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
    recentSports: List<Int> = emptyList(),
    sides: Map<Int, Boolean> = emptyMap(),
    onSide: (sport: Int, indoor: Boolean) -> Unit = { _, _ -> },
    onTurnOnLocation: (then: () -> Unit) -> Unit = { it() },
    /** The max heart rate zones are shares of, once worked out, for a zone goal's heart rates. */
    maxHeartRate: Int? = null,
    onYourZones: () -> Unit = {},
    /** Run intervals saved by name, and saving one ([onSaveIntervals], its name and code). */
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
    val cardio = family == Workouts.Family.Distance || family == Workouts.Family.IndoorCardio
    // Last time's settings until changed here. Keyed on them too: the stored ones load a moment after the screen opens.
    val key = Workouts.tileType(sportType)
    var goalCode by rememberSaveable(key, settings) { mutableStateOf(settings.goal) }
    var askToEnd by rememberSaveable(key, settings) { mutableStateOf(settings.askToEnd) }
    var keepRoute by rememberSaveable(key, settings) { mutableStateOf(settings.keepRoute) }
    val lastRest = Strength.Rest.parse(settings.rest) ?: Strength.Rest.DEFAULT
    var sets by rememberSaveable(key, settings) { mutableStateOf(settings.sets) }
    var restSeconds by rememberSaveable(key, settings) { mutableStateOf(lastRest.seconds) }
    var restBelow by rememberSaveable(key, settings) { mutableStateOf(lastRest.belowBpm) }
    var log by rememberSaveable(key, settings) { mutableStateOf(settings.log) }
    // HIIT's rounds (Tabata unless last time was another), or a game's periods; null is Free, or free play.
    var structureCode by rememberSaveable(key, settings, structure) {
        mutableStateOf(structure ?: settings.structure ?: Structure.TABATA.code.takeIf { family == Workouts.Family.Intervals })
    }
    // A cardio sport's goal: the kind chosen and each kind's value, from last time's goal or reps.
    var draft by rememberSaveable(key, settings, structure, stateSaver = GoalDraftSaver) { mutableStateOf(goalDraft(settings, structure)) }
    var typing by remember { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<ReadySheet?>(null) }
    val context = LocalContext.current
    var locationOn by remember { mutableStateOf(locationAvailable(context)) }
    LifecycleResumeEffect(Unit) {
        locationOn = locationAvailable(context)
        onPauseOrDispose { }
    }
    val outdoor = Workouts.gpsOffered(sportType, indoor)
    // GPS wherever it's offered and the phone's location can be used; the indoor side is the way without it.
    val useGps = outdoor && locationOn
    val strength = family == Workouts.Family.Strength
    val hasDistance = useGps || type in Workouts.ON_FOOT
    val kinds = WorkoutSetup.goalKinds(type, hasDistance)
    // A kind that doesn't fit the side now (a distance indoors on a bike) shows as no goal.
    val kind = draft.kind.takeIf { it in kinds } ?: WorkoutSetup.GoalKind.None
    // Rounds only where they fit: HIIT, a game's periods, and reps for a run (from the goal card).
    val rounds = if (cardio) draft.reps.takeIf { kind == WorkoutSetup.GoalKind.Intervals && Structure.fits(it, type) }
        else Structure.parse(structureCode)?.takeIf { Structure.fits(it, type) }
    val goal = if (cardio) when (kind) {
        WorkoutSetup.GoalKind.Distance -> WorkoutTarget.Distance(draft.meters)
        WorkoutSetup.GoalKind.Time -> WorkoutTarget.Time(draft.seconds)
        WorkoutSetup.GoalKind.Zone -> draft.zones
        else -> null
    } else WorkoutTarget.parse(goalCode)?.takeIf { rounds == null && WorkoutSetup.offered(it, hasDistance, kcal = false) }
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
    val focusManager = LocalFocusManager.current
    // Back while typing puts the keyboard and the caret away first.
    BackHandler(enabled = typing) { focusManager.clearFocus() }
    val start = {
        haptics.perform(HapticEvent.WorkoutStart)
        val rest = Strength.Rest(restSeconds, restBelow)
        onStart(
            WorkoutPlan(
                type, askToEnd, goal.takeIf { !strength }, useGps, useGps && keepRoute,
                rest.takeIf { strength }, sets.takeIf { strength }, logSets = strength && log, structure = rounds?.code,
            ),
            settings.copy(
                goal = if (strength) settings.goal else goal?.code, askToEnd = askToEnd, gps = true, keepRoute = keepRoute,
                rest = if (strength) rest.code else settings.rest, sets = if (strength) sets else settings.sets,
                log = if (strength) log else settings.log, structure = rounds?.code,
            ),
        )
    }
    WorkoutPage(
        Color.Transparent, contentPadding,
        // While a goal is typed the keyboard has the bottom of the screen.
        pinned = if (typing) null else ({ WorkoutButton(StartCopy.startLabel(type), Color.White, Color.Black, start, icon = AppIcons.PlayFill, height = 64.dp) }),
    ) {
        Box(Modifier.fillMaxWidth()) {
            RoundIcon(AppIcons.X, "Close", onBack, Modifier.align(Alignment.CenterStart))
            Box(Modifier.align(Alignment.Center)) { Pill(Workouts.shortName(type), null, onClick = { picking = true }, caret = true) }
            if (!cardio) RoundIcon(AppIcons.SlidersHorizontal, "${Workouts.shortName(type)} settings", { sheet = ReadySheet.Settings }, Modifier.align(Alignment.CenterEnd))
        }
        if (cardio) {
            ReadyHeartCard(connection, liveHeartRate)
            ReadyWhere(
                type, pair, indoor == true, outdoor, locationOn, StartCopy.gpsStatus(accuracy),
                onSwitch = {
                    val next = indoor != true
                    chosenIndoor = next
                    onSide(pair!!.outdoor, next)
                },
                onTurnOnLocation = {
                    if (!WorkoutLocation.granted(context)) onTurnOnLocation { locationOn = locationAvailable(context) }
                    else context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
                askToEnd = askToEnd, onAskToEnd = { askToEnd = it },
                keepRoute = keepRoute.takeIf { useGps }, onKeepRoute = { keepRoute = it },
            )
            val zones by HeartZones.current.collectAsState()
            ReadyGoal(
                type, kinds, hasDistance, draft.copy(kind = kind), { draft = it }, typing, { typing = it }, zones, maxHeartRate, onYourZones,
                saved.filter { Workouts.tileType(it.sport) == key },
                onSave = { name, reps -> onSaveIntervals(name, reps.code) },
            )
            return@WorkoutPage
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
        // The one status line: what the strap will do.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            StatusText(if (family == Workouts.Family.Games) StartCopy.GAMES_STATUS else StartCopy.MIND_STATUS)
        }
        // A game: free play, halves or quarters, with no label (workouts spec 2.2), then their length and break.
        if (family == Workouts.Family.Games) {
            GamePlan(rounds as? Structure.Periods) { structureCode = it?.code; goalCode = null }
            return@WorkoutPage
        }
        // Mind and body: Open or a length.
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChipLabel(StartCopy.goalTitle(family))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(StartCopy.OPEN, goal == null, Color.White) { goalCode = null }
                WorkoutSetup.LENGTHS.forEach { g -> Chip(StartCopy.goalLabel(g), g == goal, Color.White) { goalCode = g.code } }
            }
        }
    }
    when (sheet) {
        ReadySheet.Settings -> PageSheet({ sheet = null }, "${Workouts.shortName(type)} settings") {
            PageSwitchRow(
                "Ask to end when I stop", "After 8 min near resting heart rate and no steps, the strap buzzes and asks.",
                askToEnd, { askToEnd = it }, divider = false,
            )
            PageButton("About recording", { sheet = ReadySheet.About }, icon = AppIcons.Info)
        }
        ReadySheet.About -> InfoSheet(
            Info("Recording", WorkoutCopy.READY_MEANING, emptyList(), "", "", ""), MetricColors.heart, null, null,
            onDismiss = { sheet = null }, rows = WorkoutCopy.readyRows(),
        )
        null -> Unit
    }
}

/** What a cardio sport's goal card opens on: last time's goal or reps ([structure] first, from Choose), each kind's default else. */
private fun goalDraft(settings: SportSettings, structure: String?): GoalDraft {
    val goal = WorkoutTarget.parse(settings.goal)
    val reps = Structure.parse(structure ?: settings.structure) as? Structure.Reps
    return GoalDraft(
        WorkoutSetup.goalKind(goal, reps),
        (goal as? WorkoutTarget.Distance)?.meters ?: WorkoutSetup.DEFAULT_DISTANCE.meters,
        (goal as? WorkoutTarget.Time)?.seconds ?: WorkoutSetup.DEFAULT_TIME.seconds,
        goal as? WorkoutTarget.Zones ?: WorkoutSetup.DEFAULT_ZONES,
        reps ?: StartCopy.DEFAULT_INTERVALS,
    )
}

/** Keeps the goal card's draft across process death, as the kind, the values and the reps' code. */
private val GoalDraftSaver = androidx.compose.runtime.saveable.listSaver<GoalDraft, Any>(
    save = { d -> listOf(d.kind.ordinal, d.meters, d.seconds, d.zones.low, d.zones.high, d.reps.code) },
    restore = { v ->
        GoalDraft(
            WorkoutSetup.GoalKind.entries[v[0] as Int], v[1] as Int, v[2] as Long, WorkoutTarget.Zones(v[3] as Int, v[4] as Int),
            Structure.parse(v[5] as String) as? Structure.Reps ?: StartCopy.DEFAULT_INTERVALS,
        )
    },
)

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
                    is Structure.Periods -> listOf(StartCopy.restText(rounds.length) to "Each", StartCopy.restText(rounds.rest) to "Break")
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

/**
 * A game's Ready (board ReadyGame): Free play, Halves or Quarters as chips, no label; with periods, steppers for
 * their length (5 min at a time) and the break (1 min).
 */
@Composable
private fun GamePlan(periods: Structure.Periods?, onChange: (Structure.Periods?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(StartCopy.FREE_PLAY, periods == null, Color.White) { onChange(null) }
        listOf(Structure.HALVES, Structure.QUARTERS).forEach { p ->
            Chip(StartCopy.periodsName(p), periods?.rounds == p.rounds, Color.White) { onChange(p) }
        }
    }
    if (periods != null) QuietPanel {
        Stepper(
            "Length", StartCopy.restText(periods.length), "Shorter", "Longer",
            { onChange(periods.copy(length = (periods.length - 300).coerceAtLeast(300))) }, { onChange(periods.copy(length = (periods.length + 300).coerceAtMost(90 * 60))) },
        )
        Stepper(
            StartCopy.breakName(periods.rounds), StartCopy.restText(periods.rest), "Shorter break", "Longer break",
            { onChange(periods.copy(rest = (periods.rest - 60).coerceAtLeast(0))) }, { onChange(periods.copy(rest = (periods.rest + 60).coerceAtMost(30 * 60))) },
        )
        Text("The strap buzzes at the end of each period and break.", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
    }
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
