// The workout screens read the workouts and root view models for their lists, settings and rewards, as inside AppRoot.
@file:Suppress("ViewModelForwarding", "ComposeViewModelForwarding")

package org.openstrap.app.ui.root

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.emptyFlow
import org.openstrap.app.AppContainer
import org.openstrap.app.data.SportSettings
import org.openstrap.app.data.WorkoutPlan
import org.openstrap.app.data.WorkoutSetup
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.SavedWorkout
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutSession
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.device.locationAvailable
import org.openstrap.app.presentation.SummaryRewards
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.glow.Celebrations
import org.openstrap.app.ui.glow.ChooseSport
import org.openstrap.app.ui.glow.GlowAddWorkoutScreen
import org.openstrap.app.ui.glow.GlowChooseScreen
import org.openstrap.app.ui.glow.GlowSportPickerScreen
import org.openstrap.app.ui.glow.GlowTabletopHeart
import org.openstrap.app.ui.glow.GlowWorkoutReadyScreen
import org.openstrap.app.ui.glow.GlowWorkoutSummaryScreen
import org.openstrap.app.ui.glow.PushOverlay
import org.openstrap.app.ui.glow.WorkoutCapsule
import org.openstrap.app.ui.glow.WorkoutMorph
import org.openstrap.app.ui.glow.live.GlowWorkoutGlance
import org.openstrap.app.ui.glow.live.GlowWorkoutLiveScreen
import org.openstrap.app.ui.workout.GlanceAction
import org.openstrap.app.ui.workout.LiveGlance
import org.openstrap.app.ui.workout.StartCopy
import org.openstrap.app.ui.workout.WorkoutsViewModel

/** The highest heart rate the add workout chart allows for when yours isn't known. */
private const val FALLBACK_MAX_HR = 190

/** Where Choose, Ready and the zones page sit in what popups blur, each over the one before. */
private const val CHOOSE_Z = 2f
private const val READY_Z = 2.5f
private const val ZONES_Z = 3f

private const val MS_PER_S = 1000

/** The live workout, the Choose and Ready screens, and the folds that split them. */
internal class WorkoutLayerHooks(
    val live: Int?,
    val connection: ConnectionState,
    /** Starts a plan once Bluetooth (and location, for GPS) may be used, then closes Ready and Choose. */
    val startPlan: (WorkoutPlan, SportSettings?) -> Unit,
    val requestLocation: (() -> Unit) -> Unit,
    val capsuleBottom: Dp,
    val tabletop: Boolean,
    val bookHinge: FoldingFeature?,
)

/** What a tap on the workout at a glance does, on the floating bar and on the Today hero. */
internal fun glanceAction(session: WorkoutSession): (GlanceAction) -> Unit = { a ->
    when (a) {
        GlanceAction.Pause -> session.pause()
        GlanceAction.Resume -> session.resume()
        GlanceAction.KeepGoing -> session.keepGoing()
        GlanceAction.SetDone -> session.setDone()
        GlanceAction.StartSet -> session.startSet()
        GlanceAction.AddRest -> {
            val strength = (session.state.value as? WorkoutState.Recording)?.strength == true
            if (strength) session.extendRest() else session.addRest()
        }
        GlanceAction.Skip -> session.skipPhase()
        // Ending asks on the live screen, as it does there.
        GlanceAction.End, GlanceAction.Open -> session.minimized.value = false
    }
}

/**
 * Half open on a table, the fold level: heart rate fills the standing top half and the app carries on below the fold.
 * During a workout the top half is the workout's heart card instead, and the controls stay below.
 */
@Composable
internal fun TabletopTop(
    hinge: FoldingFeature,
    container: AppContainer,
    workoutOnTop: WorkoutState.Recording?,
    day: DayUiState,
    live: Int?,
    liveEnabled: Boolean,
    connection: ConnectionState,
) {
    val density = LocalDensity.current
    val top = Modifier.height(with(density) { hinge.bounds.top.toDp() })
    if (workoutOnTop != null) {
        val recent by container.workout.recent.collectAsStateWithLifecycle()
        val zoneSeconds by container.workout.zoneSeconds.collectAsStateWithLifecycle()
        val kcal by container.workout.kcal.collectAsStateWithLifecycle()
        GlowWorkoutGlance(workoutOnTop, live, recent, zoneSeconds, connection, top, kcal)
    } else {
        GlowTabletopHeart(
            day, live, liveEnabled.takeIf { connection is ConnectionState.Connected }, { container.strap.setLiveHeartRate(it) }, top,
        )
    }
    Spacer(Modifier.height(with(density) { hinge.bounds.height().toDp() }))
}

/**
 * What the sport picker needs (spec 6.3): your last four sports for its Recent row, newest first and only those you
 * started, and the Outdoor or Indoor side last chosen per sport on Ready.
 */
@Composable
internal fun pickerSports(workoutsVm: WorkoutsViewModel, rootVm: AppRootViewModel): Pair<List<Int>, Map<Int, Boolean>> {
    val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
    val sides by rootVm.workoutSides.collectAsStateWithLifecycle()
    val recent = remember(workouts) {
        Workouts.recentSports(workouts.filter { it.startedByYou }.sortedByDescending { it.workout.start }.map { it.workout.sportType })
    }
    return recent to sides
}

/** Adding a workout from a day's heart rate, over everything. */
@Composable
internal fun AddWorkoutHost(nav: AppNavState, container: AppContainer, workoutsVm: WorkoutsViewModel, rootVm: AppRootViewModel) {
    val date = nav.addingOn ?: return
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val series by produceState<List<Pair<Long, Int>>?>(null, date) { value = workoutsVm.dayMinuteHeartRate(date) }
        val maxHr by workoutsVm.maxHeartRate.collectAsStateWithLifecycle()
        val (recent, sides) = pickerSports(workoutsVm, rootVm)
        GlowAddWorkoutScreen(
            date, series, maxHr ?: FALLBACK_MAX_HR, WindowInsets.navigationBars.asPaddingValues(),
            onSave = { from, to, sport ->
                container.workout.saveFromHistory(from, to, sport)
                nav.setAddingOn(null)
            },
            onBack = { nav.setAddingOn(null) },
            recentSports = recent, sides = sides,
        )
    }
}

/** The sport for a stretch the strap noticed, saved as a workout once picked. */
@Composable
internal fun SaveStretchHost(nav: AppNavState, container: AppContainer, workoutsVm: WorkoutsViewModel, rootVm: AppRootViewModel) {
    val s = nav.savingStretch ?: return
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val (recent, sides) = pickerSports(workoutsVm, rootVm)
        GlowSportPickerScreen(
            WindowInsets.navigationBars.asPaddingValues(),
            onPick = { sport ->
                container.workout.saveFromHistory(s.first, s.last, sport)
                nav.setSavingStretch(null)
            },
            onBack = { nav.setSavingStretch(null) },
            recentSports = recent, sides = sides,
        )
    }
}

/**
 * The workout screens over the whole app: the ready screen, the live screen (or, tucked away, a capsule above the tab
 * bar) and the summary once it ends.
 */
@Composable
internal fun BoxScope.WorkoutLayer(
    container: AppContainer,
    nav: AppNavState,
    state: WorkoutState,
    minimized: Boolean,
    workoutsVm: WorkoutsViewModel,
    rootVm: AppRootViewModel,
    hooks: WorkoutLayerHooks,
    /** What popups blur: Choose, Ready and the zones page join it over the pages, so a sheet blurs the screen it opened from. */
    glass: HazeState?,
    /** The workout at a glance, for the floating bar, read only there. */
    glance: () -> LiveGlance?,
    onGlanceAction: (GlanceAction) -> Unit,
    /** The Today hero is showing the workout, so the bar stays away. */
    capsuleHidden: Boolean,
) {
    // Read here, ahead of a workout, so the live screen usually opens on the right view at once.
    val liveView by rootVm.workoutLiveView.collectAsStateWithLifecycle()
    // Also kept subscribed all along, so Ready never opens on the defaults before your settings arrive.
    val sportSettings by rootVm.sportSettings.collectAsStateWithLifecycle()
    val padding = WindowInsets.navigationBars.asPaddingValues()
    val full = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    val over: (Float) -> Modifier = { z -> if (glass != null) full.hazeSource(glass, zIndex = z) else full }
    when (state) {
        // Minimizing morphs the live screen into the capsule above the tab bar, and tapping the capsule back.
        is WorkoutState.Recording -> LiveWorkout(
            container.workout, state, minimized, liveView, rootVm::setWorkoutLiveView, hooks, padding,
            capsule = { place ->
                glance()?.let { g ->
                    WorkoutCapsule(g, onClick = { container.workout.minimized.value = false }, onAction = onGlanceAction, modifier = place)
                }
            },
            capsuleHidden = capsuleHidden,
        )
        is WorkoutState.Ended -> Box(full) { WorkoutSummary(container.workout, state, workoutsVm, rootVm, padding) }
        else -> Unit
    }
    // The ready screen comes and goes like any other screen, and a workout starting replaces it at once.
    val recording = state is WorkoutState.Recording || state is WorkoutState.Ended
    // Read once for both screens, and handed down to be read only there.
    val saved by remember { container.workout.savedWorkouts() }.collectAsStateWithLifecycle(emptyList())
    // Choose sits under Ready, so closing Ready comes back to it.
    PushOverlay(nav.choosing.takeIf { it && !recording }, cut = recording) {
        Box(over(CHOOSE_Z)) { ChooseOverlay(nav, workoutsVm, rootVm, { sportSettings }, { saved }, hooks, padding) }
    }
    val ready = nav.workout as? WorkoutOverlay.Ready
    PushOverlay(ready?.sport.takeIf { !recording }, cut = recording) { sport ->
        Box(over(READY_Z)) {
            ReadyOverlay(nav, sport, ready, container.workout, workoutsVm, rootVm, { sportSettings }, { saved }, hooks, padding)
            // Your zones, over Ready. Back comes back to it.
            PushOverlay(Unit.takeIf { ready?.zones == true }, cut = false) {
                Box(over(ZONES_Z)) {
                    HeartZonesPage(container, padding, rootVm::setMaxHeartRate, rootVm::setHeartZones, onBack = nav::closeZones)
                }
            }
        }
    }
}

@Composable
private fun LiveWorkout(
    session: WorkoutSession,
    state: WorkoutState.Recording,
    minimized: Boolean,
    liveView: Int?,
    onLiveView: (Int) -> Unit,
    hooks: WorkoutLayerHooks,
    padding: PaddingValues,
    capsule: @Composable (Modifier) -> Unit,
    capsuleHidden: Boolean,
) {
    val kcal by session.kcal.collectAsStateWithLifecycle()
    WorkoutMorph(
        minimized, hooks.capsuleBottom + padding.calculateBottomPadding(),
        full = {
            val recent by session.recent.collectAsStateWithLifecycle()
            val stats by session.stats.collectAsStateWithLifecycle()
            val zoneSeconds by session.zoneSeconds.collectAsStateWithLifecycle()
            val route by session.route.collectAsStateWithLifecycle()
            val history by session.history.collectAsStateWithLifecycle()
            val exercises by remember { session.exercises() }.collectAsStateWithLifecycle(emptyList())
            // Folded, the heart card has the standing half: above the fold (drawn with the tabletop heart) or, stood
            // up like a book, the left half here.
            val liveScreen: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier) {
                    GlowWorkoutLiveScreen(
                        state, hooks.live, recent, stats, zoneSeconds, hooks.connection, padding,
                        onPause = session::pause, onResume = session::resume, onEnd = session::end,
                        onMinimize = { session.minimized.value = true },
                        onEndAtPrompt = session::endAtPrompt, onKeepGoing = session::keepGoing,
                        route = route, history = history,
                        onStartSet = session::startSet, onSetDone = session::setDone, onExtendRest = session::extendRest,
                        onLap = session::lap,
                        exercises = exercises, onExercise = session::setExercise, onLogSet = session::logSet,
                        onSkip = session::skipPhase, onAddRest = session::addRest,
                        split = hooks.tabletop || hooks.bookHinge != null,
                        liveView = liveView, onLiveView = onLiveView,
                        kcal = kcal,
                    )
                }
            }
            val book = hooks.bookHinge
            if (book != null) {
                val density = LocalDensity.current
                Row(Modifier.fillMaxSize()) {
                    GlowWorkoutGlance(
                        state, hooks.live, recent, zoneSeconds, hooks.connection,
                        Modifier.width(with(density) { book.bounds.left.toDp() }).fillMaxHeight(), kcal,
                    )
                    Spacer(Modifier.width(with(density) { book.bounds.width().toDp() }))
                    liveScreen(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                liveScreen(Modifier.fillMaxSize())
            }
        },
        capsule = capsule,
        capsuleHidden = capsuleHidden,
    )
}

@Composable
private fun WorkoutSummary(
    session: WorkoutSession,
    state: WorkoutState.Ended,
    workoutsVm: WorkoutsViewModel,
    rootVm: AppRootViewModel,
    padding: PaddingValues,
) {
    // A debug preview of a stored workout writes nothing just by opening, so no effect is worked out for it.
    val summary by remember(state) { workoutsVm.phoneSummary(state.start, backfill = !state.preview) }.collectAsStateWithLifecycle(null)
    val calmDown by session.calmDown.collectAsStateWithLifecycle()
    val route by session.route.collectAsStateWithLifecycle()
    val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
    val usualDrop by produceState<Int?>(null, state.start) { value = workoutsVm.usualDrop(state.start) }
    // What it earned, from every workout and day (workouts spec 2.8), once the list holds it.
    val goals by rootVm.goals.collectAsStateWithLifecycle()
    val ended = workouts.firstOrNull { it.workout.start == state.start }
    val rewards by produceState<SummaryRewards?>(null, ended?.end, ended?.shownStart, goals.workoutsPerWeek) {
        if (ended == null) return@produceState
        value = rootVm.summaryRewards(state.start)
    }
    val celebrations by rootVm.celebrations.collectAsStateWithLifecycle()
    GlowWorkoutSummaryScreen(
        summary, calmDown.takeIf { !state.preview }, if (state.preview) emptyList() else route, workouts, usualDrop, padding,
        onDone = session::dismissSummary, onDelete = { session.discard(state.start) },
        onTrim = { from, to -> session.trim(state.start, from, to) }, onUndoTrim = { session.undoTrim(state.start) },
        rewards = rewards.takeIf { !state.preview },
        celebrations = Celebrations(
            celebrations.goalMoment, celebrations.weeklyRecap, celebrations.momentWeek,
            setGoalMoment = rootVm::setGoalMoment,
            setWeeklyRecap = rootVm::setWeeklyRecap,
            markMoment = rootVm::setGoalMomentWeek,
        ),
    )
}

// Choose works out your usual sport and its "last time" as it opens, from the clock then, as it did inside AppRoot.
@Suppress("ForbiddenMethodCall/uiClock")
@Composable
private fun ChooseOverlay(
    nav: AppNavState,
    workoutsVm: WorkoutsViewModel,
    rootVm: AppRootViewModel,
    sportSettings: () -> Map<Int, SportSettings>,
    savedWorkouts: () -> List<SavedWorkout>,
    hooks: WorkoutLayerHooks,
    padding: PaddingValues,
) {
    val context = LocalContext.current
    val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
    val (_, sides) = pickerSports(workoutsVm, rootVm)
    val zone = ZoneId.systemDefault()
    val mine = remember(workouts) { workouts.filter { it.startedByYou }.sortedByDescending { it.workout.start } }
    val sports = remember(mine) {
        WorkoutSetup.choose(mine.map { it.workout.sportType to it.workout.start }, System.currentTimeMillis() / MS_PER_S)
    }
    val today = LocalDate.now(zone)
    val usual = sports.usual?.let { type ->
        val last = mine.firstOrNull { Workouts.tileType(it.workout.sportType) == type }
        ChooseSport(type, last?.let { StartCopy.lastTime(it, today, zone) }.orEmpty())
    }
    val saved = savedWorkouts()
    val recent = remember(sports, saved) { WorkoutSetup.recentChips(sports.recent, saved) }
    GlowChooseScreen(
        usual, recent, hooks.connection, hooks.live, padding,
        onStartUsual = { type ->
            val settings = sportSettings()[type] ?: SportSettings()
            hooks.startPlan(WorkoutSetup.plan(type, settings, sides, locationAvailable(context)), null)
        },
        onReady = { nav.openReady(it, picking = false, structure = null) },
        onSaved = { w -> nav.openReady(w.sport, picking = false, structure = w.structure) },
        onBack = nav::closeChoose,
    )
}

@Composable
private fun ReadyOverlay(
    nav: AppNavState,
    sport: Int,
    ready: WorkoutOverlay.Ready?,
    session: WorkoutSession,
    workoutsVm: WorkoutsViewModel,
    rootVm: AppRootViewModel,
    sportSettings: () -> Map<Int, SportSettings>,
    savedWorkouts: () -> List<SavedWorkout>,
    hooks: WorkoutLayerHooks,
    padding: PaddingValues,
) {
    val saved = savedWorkouts()
    val (recentSports, sides) = pickerSports(workoutsVm, rootVm)
    val key = Workouts.tileType(sport)
    val maxHr by workoutsVm.maxHeartRate.collectAsStateWithLifecycle()
    GlowWorkoutReadyScreen(
        sport, ready?.picking == true, hooks.connection, hooks.live, padding,
        settings = sportSettings()[key] ?: SportSettings(),
        saved = saved, onSaveIntervals = { name, code -> session.saveWorkout(name, sport, code) }, structure = ready?.structure,
        recentSports = recentSports, sides = sides,
        maxHeartRate = maxHr, onYourZones = nav::openZones,
        onSide = rootVm::setWorkoutSide,
        onTurnOnLocation = hooks.requestLocation,
        onSport = nav::readySport,
        onStart = hooks.startPlan,
        onBack = nav::closeReady,
    )
}

/** The folds half open: [table] lying level (the top half stands), [book] stood up like a book (the fold upright). */
internal class Folds(val table: FoldingFeature?, val book: FoldingFeature?)

/** Where the phone is folded half open, as the window reports it. */
@Composable
internal fun rememberFolds(): Folds {
    val activity = LocalActivity.current
    val windowLayout by remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) } ?: emptyFlow()
    }.collectAsStateWithLifecycle(null)
    val halfOpen = windowLayout?.displayFeatures?.filterIsInstance<FoldingFeature>()
        ?.filter { it.state == FoldingFeature.State.HALF_OPENED }.orEmpty()
    return Folds(
        halfOpen.firstOrNull { it.orientation == FoldingFeature.Orientation.HORIZONTAL },
        halfOpen.firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL },
    )
}
