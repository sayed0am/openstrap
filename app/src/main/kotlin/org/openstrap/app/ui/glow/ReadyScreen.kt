package org.openstrap.app.ui.glow

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.workout.StartCopy
import org.openstrap.app.ui.workout.familyColor
import org.openstrap.app.data.Structure
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement

/* -------------------------------------------- Ready screen ------------------------------------------- */

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
 * No sport has a settings sheet: the heart on its own card, then one plan card typed in place, and switch rows for
 * the rest. A distance or cardio sport (board ReadyFinal) has where you are with Ask to end when I stop, then the goal
 * ([ReadyGoal]); a zone goal reads your zones ([maxHeartRate], [HeartZones.current]) and Your zones opens them
 * ([onYourZones]). Run intervals save by name ([saved], [onSaveIntervals]) and open on rounds picked on Choose
 * ([structure]).
 *
 * Strength, HIIT, mind and body and games (boards ReadyFinalStrength, ReadyFinalHiit, ReadyFinalMind,
 * ReadyFinalGames) light the heart card in the family's colour, then their plan: the sets and rest, the rounds, the
 * length, or the match ([StrengthPlanCard] and the others); then their switches, Ask to end when I stop last. [onStart] gets the workout to
 * record and the settings to open on next time.
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
    // Each choice is keyed on the stored field it starts from, so settings arriving again, or another field changing,
    // keeps what was just picked or typed (audit K7).
    val key = Workouts.tileType(sportType)
    var askToEnd by rememberSaveable(key, settings.askToEnd) { mutableStateOf(settings.askToEnd) }
    var keepRoute by rememberSaveable(key, settings.keepRoute) { mutableStateOf(settings.keepRoute) }
    val lastRest = Strength.Rest.parse(settings.rest) ?: Strength.Rest.DEFAULT
    var sets by rememberSaveable(key, settings.sets) { mutableStateOf(settings.sets) }
    var restSeconds by rememberSaveable(key, settings.rest) { mutableStateOf(lastRest.seconds) }
    var restBelow by rememberSaveable(key, settings.rest) { mutableStateOf(lastRest.belowBpm) }
    // The level End rest early turns back on at, and the sets Plan opens on after Open.
    val belowLevel = rememberSaveable(key, settings.rest) { lastRest.belowBpm ?: StartCopy.DEFAULT_BELOW }
    var planSets by rememberSaveable(key, settings.sets) { mutableStateOf(settings.sets ?: StartCopy.DEFAULT_SETS) }
    var log by rememberSaveable(key, settings.log) { mutableStateOf(settings.log) }
    // HIIT's rounds (Tabata unless last time was another), or a game's periods; null is Free, or free play.
    var structureCode by rememberSaveable(key, settings.structure, structure) {
        mutableStateOf(structure ?: settings.structure ?: Structure.TABATA.code.takeIf { family == Workouts.Family.Intervals })
    }
    // A cardio sport's goal, or mind and body's length: the kind chosen and each kind's value, from last time's goal or reps.
    var draft by rememberSaveable(key, settings.goal, settings.structure, structure, stateSaver = GoalDraftSaver) {
        mutableStateOf(goalDraft(settings, structure))
    }
    // Typing outlives a rotation or a fold, so the half-typed value comes back with its keyboard.
    var typing by rememberSaveable { mutableStateOf(false) }
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
    val mind = family == Workouts.Family.MindBody
    val hasDistance = useGps || type in Workouts.ON_FOOT
    val kinds = WorkoutSetup.goalKinds(type, hasDistance)
    // A kind that doesn't fit the side now (a distance indoors on a bike) shows as no goal.
    val kind = draft.kind.takeIf { it in kinds } ?: WorkoutSetup.GoalKind.None
    // Rounds only where they fit: HIIT, a game's periods, and reps for a run (from the goal card).
    val rounds = if (cardio) draft.reps.takeIf { kind == WorkoutSetup.GoalKind.Intervals && Structure.fits(it, type) }
        else Structure.parse(structureCode)?.takeIf { Structure.fits(it, type) }
    val goal = when {
        cardio -> when (kind) {
            WorkoutSetup.GoalKind.Distance -> WorkoutTarget.Distance(draft.meters)
            WorkoutSetup.GoalKind.Time -> WorkoutTarget.Time(draft.seconds)
            WorkoutSetup.GoalKind.Zone -> draft.zones
            else -> null
        }
        mind -> WorkoutTarget.Time(draft.seconds).takeIf { draft.kind == WorkoutSetup.GoalKind.Time }
        // Strength, HIIT and games set no goal here; one saved from before still counts where nothing replaces it.
        else -> WorkoutTarget.parse(settings.goal)?.takeIf { rounds == null && WorkoutSetup.offered(it, hasDistance, kcal = false) }
    }
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
        ReadyHeartCard(connection, liveHeartRate, familyColor(family))
        when (family) {
            Workouts.Family.Strength -> StrengthPlanCard(
                type, sets, { sets = it; if (it != null) planSets = it }, planSets, restSeconds, { restSeconds = it }, typing, { typing = it },
            )
            Workouts.Family.Intervals -> HiitPlanCard(type, rounds, { structureCode = it?.code }, typing, { typing = it })
            Workouts.Family.MindBody -> MindPlanCard(
                type, draft.seconds.takeIf { draft.kind == WorkoutSetup.GoalKind.Time },
                { draft = if (it == null) draft.copy(kind = WorkoutSetup.GoalKind.None) else draft.copy(kind = WorkoutSetup.GoalKind.Time, seconds = it) },
                draft.seconds, typing, { typing = it },
            )
            else -> GamePlanCard(type, rounds as? Structure.Periods, { structureCode = it?.code }, typing, { typing = it })
        }
        ReadyRows {
            var divider = false
            if (strength) {
                PageSwitchRow(
                    StartCopy.END_REST_EARLY, StartCopy.endRestNote(restBelow ?: belowLevel), restBelow != null,
                    { restBelow = if (it) belowLevel else null }, divider = false,
                )
                PageSwitchRow(StartCopy.LOG_SETS, StartCopy.LOG_SETS_NOTE, log, { log = it })
                divider = true
            }
            if (rounds != null && family == Workouts.Family.Intervals) {
                PageSwitchRow(StartCopy.WARM_UP, StartCopy.WARM_UP_NOTE, rounds.warmUp, { structureCode = rounds.warm(it).code }, divider = false)
                divider = true
            }
            PageSwitchRow(StartCopy.ASK_TO_END, StartCopy.ASK_TO_END_NOTE, askToEnd, { askToEnd = it }, divider = divider)
        }
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

/**
 * A round button with its icon, 44 dp on the Control surface as on the workout pages' top bars; a stepper's are 52 dp,
 * the map's sit on [surface] over the map.
 */
@Composable
internal fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    size: Dp = 44.dp, surface: Color = Raised, ink: Color = Color.White, iconSize: Dp = 18.dp,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(surface).pressClickable(onClick, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = ink, modifier = Modifier.size(iconSize)) }
}

/** Minus, the [value] between, and plus: Ready's goal steppers ([buttonSize] 52 dp) and the log card's. */
@Composable
internal fun MinusPlus(
    less: String, more: String, onLess: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier,
    buttonSize: Dp = 44.dp, gap: Dp = 0.dp, value: @Composable RowScope.() -> Unit,
) {
    val iconSize = if (buttonSize > 44.dp) 20.dp else 18.dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        RoundIcon(AppIcons.Minus, less, onLess, size = buttonSize, iconSize = iconSize)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = value)
        RoundIcon(AppIcons.Plus, more, onMore, size = buttonSize, iconSize = iconSize)
    }
}

/** The strap in one line with its dot, on Choose: "Strap ready, 72 bpm". */
@Composable
internal fun StrapLine(connection: ConnectionState, liveHeartRate: Int?) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(6.dp)) { drawCircle(if (connection is ConnectionState.Connected) TrendGood else TrendBad) }
        Spacer(Modifier.width(8.dp))
        Text(StartCopy.strapLine(connection, liveHeartRate), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
    }
}

/** The label over a row of chips, in Caption. */
@Composable
internal fun ChipLabel(text: String) =
    Text(text, color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.caption, modifier = Modifier.padding(start = 4.dp))
