package org.openstrap.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import dev.chrisbanes.haze.rememberHazeState
import java.time.LocalDate
import org.openstrap.app.device.BlePermissions
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutLocation
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.components.FloatingNavBar
import org.openstrap.app.ui.components.FloatingNavRail
import org.openstrap.app.ui.components.GlassHost
import org.openstrap.app.ui.components.GlassOverlay
import org.openstrap.app.ui.components.GrowOrigin
import org.openstrap.app.ui.components.LocalGlassHost
import org.openstrap.app.ui.components.LocalGrowOrigin
import org.openstrap.app.ui.components.SideBySideWidth
import org.openstrap.app.ui.components.rememberGrowProgress
import org.openstrap.app.ui.dashboard.DataPreview
import org.openstrap.app.ui.dashboard.DayViewModel
import org.openstrap.app.ui.dashboard.LocalGoals
import org.openstrap.app.ui.dashboard.LocalHeartZones
import org.openstrap.app.ui.dashboard.Reselect
import org.openstrap.app.ui.dashboard.STALE_AFTER_S
import org.openstrap.app.ui.dashboard.StrapPreview
import org.openstrap.app.ui.dashboard.syncStopped
import org.openstrap.app.ui.device.DeviceViewModel
import org.openstrap.app.ui.firmware.FirmwareStep
import org.openstrap.app.ui.firmware.FirmwareViewModel
import org.openstrap.app.ui.glow.LiveHeroHooks
import org.openstrap.app.ui.glow.LocalHideTabBar
import org.openstrap.app.ui.glow.TodayStrap
import org.openstrap.app.ui.root.AddWorkoutHost
import org.openstrap.app.ui.root.AllMetricsHost
import org.openstrap.app.ui.root.AppNavState
import org.openstrap.app.ui.root.AppRootViewModel
import org.openstrap.app.ui.root.BackResult
import org.openstrap.app.ui.root.BluetoothBlockedDialog
import org.openstrap.app.ui.root.DebugPreviews
import org.openstrap.app.ui.root.DetailHost
import org.openstrap.app.ui.root.DeviceMessages
import org.openstrap.app.ui.root.GoalsSheetHost
import org.openstrap.app.ui.root.HeaderHooks
import org.openstrap.app.ui.root.Histories
import org.openstrap.app.ui.root.HistoryBadgesHost
import org.openstrap.app.ui.root.LiveHeart
import org.openstrap.app.ui.root.NavRequestHooks
import org.openstrap.app.ui.root.NavRequestsEffect
import org.openstrap.app.ui.root.Page
import org.openstrap.app.ui.root.PageHooks
import org.openstrap.app.ui.root.PagesOverTab
import org.openstrap.app.ui.root.SaveStretchHost
import org.openstrap.app.ui.root.ScrollRequests
import org.openstrap.app.ui.root.Tab
import org.openstrap.app.ui.root.TabletopTop
import org.openstrap.app.ui.root.TabsPager
import org.openstrap.app.ui.root.TodayHooks
import org.openstrap.app.ui.root.ViewModelFactories
import org.openstrap.app.ui.root.WorkoutLayer
import org.openstrap.app.ui.root.WorkoutLayerHooks
import org.openstrap.app.ui.root.glanceAction
import org.openstrap.app.ui.root.rememberBackupActions
import org.openstrap.app.ui.root.rememberFolds
import org.openstrap.app.ui.root.rememberHealthConnectActions
import org.openstrap.app.ui.root.rememberPairingStage
import org.openstrap.app.ui.root.rememberPermissionGates
import org.openstrap.app.ui.root.rememberPreviewedDay
import org.openstrap.app.ui.root.rememberShownDay
import org.openstrap.app.ui.root.rememberStrapHeader
import org.openstrap.app.ui.root.rememberTodayMoment
import org.openstrap.app.ui.root.tabItems
import org.openstrap.app.ui.theme.OpenStrapTheme
import org.openstrap.app.ui.workout.WorkoutsViewModel

class MainActivity : ComponentActivity() {
    /** Debug: what the screens are shown in place of the real state, from the debug extras. */
    private val previews = DebugPreviews()

    /** A Strap page action waiting on the Bluetooth answer, kept across a fold or a rotation. */
    private val pendingStrap: PendingStrapAction by viewModels()

    /** What notifications, widgets, the tile and shortcuts asked to show, until the app root has shown it. */
    private val navRequests: NavigationRequests by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark bars (light icons) whatever the system mode, to match the always-dark theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as OpenStrapApp).container
        setContent {
            OpenStrapTheme {
                AppRoot(container, navRequests, previews, pendingStrap)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as OpenStrapApp).container.strap.setAppVisible(true)
    }

    override fun onStop() {
        (application as OpenStrapApp).container.strap.setAppVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Reconnect to a paired strap whenever the app comes to the front (sync follows on connect).
        if (BlePermissions.granted(this)) (application as OpenStrapApp).container.strap.autoConnect()
        // A workout whose service Android refused while the app was in the background starts it now.
        (application as OpenStrapApp).container.workout.inFront()
        // The association or battery setting may have changed in system settings meanwhile.
        (application as OpenStrapApp).container.companion.refresh()
        handleDebugIntent(previews)
        showWorkoutFromIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(previews)
        showWorkoutFromIntent()
    }

    /**
     * The workout notification opens the live screen, even if it was tucked away. A shortcut or the tile opens the
     * ready screen, an auto-detected workout's notification that workout, and a health alert or body-signal
     * notification its metric's screen for its day.
     */
    private fun showWorkoutFromIntent() {
        // The Moment widget: Today as it is now, never a forced moment.
        if (intent.getStringExtra(EXTRA_MOMENT) == MOMENT_NOW) {
            intent.removeExtra(EXTRA_MOMENT)
            navRequests.send(NavRequest.OpenToday)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_STRAP, false)) {
            intent.removeExtra(EXTRA_OPEN_STRAP)
            navRequests.send(NavRequest.OpenStrap)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_RECAP, false)) {
            intent.removeExtra(EXTRA_OPEN_RECAP)
            navRequests.send(NavRequest.OpenRecap)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false)) {
            intent.removeExtra(EXTRA_OPEN_UPDATES)
            navRequests.send(NavRequest.OpenUpdates)
        }
        intent.getStringExtra(EXTRA_OPEN_METRIC)?.let { route ->
            val day = intent.getStringExtra(EXTRA_METRIC_DAY)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            intent.removeExtra(EXTRA_OPEN_METRIC)
            intent.removeExtra(EXTRA_METRIC_DAY)
            navRequests.send(NavRequest.OpenDetail(route, day))
        }
        val open = intent.getLongExtra(EXTRA_OPEN_WORKOUT, -1)
        if (open >= 0) {
            intent.removeExtra(EXTRA_OPEN_WORKOUT)
            navRequests.send(NavRequest.OpenWorkout(open))
        }
        val ready = intent.getStringExtra(EXTRA_READY_SPORT)?.toIntOrNull() ?: intent.getIntExtra(EXTRA_READY_SPORT, -1)
        if (ready >= 0) {
            intent.removeExtra(EXTRA_READY_SPORT)
            navRequests.send(NavRequest.OpenReady(ready))
        }
        if (intent.getBooleanExtra(EXTRA_READY_PICK, false)) {
            intent.removeExtra(EXTRA_READY_PICK)
            navRequests.send(NavRequest.OpenChoose)
        }
        if (!intent.getBooleanExtra(EXTRA_SHOW_WORKOUT, false)) return
        intent.removeExtra(EXTRA_SHOW_WORKOUT)
        navRequests.send(NavRequest.ShowWorkout)
    }

    companion object {
        const val EXTRA_SHOW_WORKOUT = "show_workout"
        const val EXTRA_READY_SPORT = "ready_sport"

        /** True opens Ready on the sport picker, as All sports does: the Start widget's All sports. */
        const val EXTRA_READY_PICK = "ready_pick"
        const val EXTRA_OPEN_WORKOUT = "open_workout"

        /** A metric's detail by [org.openstrap.app.presentation.Metric] name ("Hrv"), or "Recovery" or "Sleep". */
        const val EXTRA_OPEN_METRIC = "open_metric"

        /** The day for [EXTRA_OPEN_METRIC], as an ISO date ("2026-10-23"); without it the day showing stays. */
        const val EXTRA_METRIC_DAY = "metric_day"

        /**
         * [MOMENT_NOW] opens Today at its root, as it is now: the Moment widget's tap (widgets spec 4). Debug: a
         * [org.openstrap.app.data.Moment] name to force on Today, or "auto" to follow the clock again.
         */
        const val EXTRA_MOMENT = "moment"
        const val MOMENT_NOW = "now"

        /** True opens the Strap page over the tab showing: a widget's status line while the strap isn't paired or is low. */
        const val EXTRA_OPEN_STRAP = "open_strap"

        /** True opens last week's recap over the tab showing: the weekly recap notification's tap. */
        const val EXTRA_OPEN_RECAP = "open_recap"

        /** True opens the Updates page over the tab showing: an update notification's tap. */
        const val EXTRA_OPEN_UPDATES = "open_updates"

        /** Debug: a [StrapPreview] name ("stale", "stuck", "disconnected", "charging", "notpaired", "recoveryfail") to show, or "auto". */
        const val EXTRA_STRAP_STATE = "strap_state"

        /**
         * Debug: a [DataPreview] name to show, or "auto": "pair", "pair_search", "pair_found", "pair_connecting",
         * "pair_connected", "pair_failed", "firstnights", "nosleep", "partial", "firstscore".
         */
        const val EXTRA_DATA_STATE = "data_state"

        /** Debug: true opens the summary right after a workout for the latest stored phone workout. */
        const val EXTRA_SHOW_SUMMARY = "show_summary"

        /** Debug: the workout notification with sample figures, "running", "paused", "indoor", "end" or "clear". */
        const val EXTRA_WORKOUT_NOTIFICATION = "workout_notification"
    }
}

/** An update is being installed on the Firmware page over the tab. */
private fun firmwareInstalling(nav: AppNavState, firmwareVm: FirmwareViewModel): Boolean =
    Page.Firmware in nav.pages && firmwareVm.step.value is FirmwareStep.Installing

/**
 * Closes the pages over the tab, unless an update is being installed there, and says if any closed. Firmware starts
 * again from the top once it closes.
 */
private fun closePages(nav: AppNavState, firmwareVm: FirmwareViewModel): Boolean {
    val firmware = Page.Firmware in nav.pages
    return nav.closePages(firmwareInstalling(nav, firmwareVm)).also { closed -> if (closed && firmware) firmwareVm.reset() }
}

/**
 * A tap on the tab bar. Another tab keeps every stack. The tab showing scrolls to the top, then pops to its root (spec
 * 3.3). A tap that closes pages over the tab does only that.
 */
private fun tapTab(t: Int, nav: AppNavState, firmwareVm: FirmwareViewModel, scroll: ScrollRequests) {
    val closed = closePages(nav, firmwareVm)
    when {
        firmwareInstalling(nav, firmwareVm) -> Unit
        t != nav.tab.current -> nav.select(Tab.entries[t])
        closed -> Unit
        nav.reselect(Tab.entries[t]) == Reselect.ScrollToTop -> scroll.bump(t)
    }
}

/** Wider than tall and at least this wide, the tabs stand down the left edge as a rail. */
private val RailMinWidth = 600.dp

/** Where the workout capsule floats: above the tab bar, or near the bottom edge without one. */
private val CapsuleOverBar = 104.dp
private val CapsuleNoBar = 16.dp

/**
 * The app: the tabs, the pages over them, the sheets and the workout screens, in that order. Back goes to what was
 * composed last, so the order is the back order (wave 4 Decisions 1): the tabs, the pages over them, All metrics, add
 * workout, the sport picker, the workout layer, the history badges, the Bluetooth dialog, then the glass popups.
 */
// AppRoot creates the view models and hands each part of the screen the ones it shows.
@Suppress("ViewModelForwarding", "ComposeViewModelForwarding")
@Composable
private fun AppRoot(
    container: AppContainer,
    navRequests: NavigationRequests,
    previews: DebugPreviews,
    pendingStrap: PendingStrapAction,
    dayVm: DayViewModel = viewModel(factory = ViewModelFactories.day(container)),
    deviceVm: DeviceViewModel = viewModel(factory = ViewModelFactories.device(container)),
    firmwareVm: FirmwareViewModel = viewModel(factory = ViewModelFactories.firmware(container)),
    workoutsVm: WorkoutsViewModel = viewModel(factory = ViewModelFactories.workouts(container)),
    rootVm: AppRootViewModel = viewModel(factory = ViewModelFactories.root(container, workoutsVm.workouts)),
) {
    // Where the app is: the tab, the pages over it, each tab's details, the sheets and the workout screens.
    val nav = rememberSaveable(saver = AppNavState.Saver) { AppNavState() }
    val tab = nav.tab.current
    val goals by rootVm.goals.collectAsStateWithLifecycle()
    val heartZones by rootVm.heartZones.collectAsStateWithLifecycle()
    val strapPreview by previews.strap.collectAsStateWithLifecycle()
    val dataPreview by previews.data.collectAsStateWithLifecycle()
    // The pairing steps before a strap is paired show with no strap, as `strap_state notpaired` does.
    val preview = strapPreview ?: StrapPreview.NotPaired.takeIf { dataPreview?.unpaired == true }
    val previewed = rememberPreviewedDay(dayVm, preview, dataPreview)
    val day = previewed.day
    val strapFacts by rootVm.strap.collectAsStateWithLifecycle()
    val connected = strapFacts.connection is ConnectionState.Connected
    val live by rootVm.liveHeartRate.collectAsStateWithLifecycle()
    val workoutState by container.workout.state.collectAsStateWithLifecycle()
    val workoutMinimized by container.workout.minimized.collectAsStateWithLifecycle()
    val running = workoutState as? WorkoutState.Recording
    val forced by previews.moment.collectAsStateWithLifecycle()
    val today = rememberTodayMoment(
        day, previewed.recovery, previewed.recoveryFailure, running != null, forced, workoutsVm, onNewDay = dayVm::select,
    )
    val strap = rememberStrapHeader(strapFacts, day, preview)
    val shown = rememberShownDay(previewed, dayVm, rootVm, today.clockDate, strap.now, preview, dataPreview)
    val histories = remember(container) {
        Histories { m, from, to -> container.health.daily(m, from, to) }
    }
    val liveHeart = LiveHeart(live, connected)
    val pairedKnown = strapFacts.pairedKnown && preview != StrapPreview.NotPaired
    val scan by deviceVm.scan.collectAsStateWithLifecycle()
    val pairing = rememberPairingStage(strapFacts.pairedKnown, scan.scanning, scan.found.isNotEmpty(), strapFacts.connection, dataPreview)
    // Opening Habit Age marks the estimate seen, so Today stops offering it.
    val seeHabitAge: () -> Unit = { shown.healthspan?.result?.asOf?.let(rootVm::seeHabitAge) }
    val profile by deviceVm.profile.collectAsStateWithLifecycle()
    DeviceMessages(deviceVm)
    // The workout at a glance for the Today hero and the floating bar (workouts spec 2.10). Its State is passed down,
    // not read here, so only those two recompose as it ticks.
    val liveGlance = rootVm.liveGlance.collectAsStateWithLifecycle()
    // The Today hero shows the workout: the floating bar stays away meanwhile.
    var liveHeroShown by remember { mutableStateOf(false) }
    val onGlanceAction = remember(container) { glanceAction(container.workout) }
    // The All metrics Charge row asks the Recovery tab to scroll to its Charge card, once.
    var showCharge by remember { mutableStateOf(false) }
    // Each tap on the tab showing bumps its count, and its lists scroll to the top.
    val scroll = remember { ScrollRequests() }
    val firmwareInstalling: () -> Boolean = { firmwareInstalling(nav, firmwareVm) }
    val closePages: () -> Boolean = { closePages(nav, firmwareVm) }
    // The first page over the tab grows out of the header button tapped for it, if one was (spec 3.5), and the tab
    // behind it sinks as it comes forward.
    val growOrigin = remember { GrowOrigin() }
    var growFrom by remember { mutableStateOf<Rect?>(null) }
    val pageGrow = rememberGrowProgress(nav.pages.isNotEmpty())
    val openPage: (Page) -> Unit = { p ->
        val tapped = growOrigin.take()
        if (nav.pages.isEmpty()) growFrom = tapped
        nav.openPage(p)
    }
    NavRequestsEffect(
        navRequests, nav,
        NavRequestHooks(
            recording = { container.workout.state.value is WorkoutState.Recording },
            firmwareInstalling = firmwareInstalling,
            firmwareClosed = firmwareVm::reset,
            selectDay = dayVm::select,
            today = { dayVm.select(LocalDate.now()) },
            strapOpened = { growFrom = growOrigin.take() },
            showWorkout = { container.workout.minimized.value = false },
        ),
    )
    // Starting a workout closes Ready and Choose. [saved] is what Ready opens on next time for the sport, and Choose's
    // Start on your usual saves nothing new.
    val gates = rememberPermissionGates(pendingStrap, { WorkoutLocation.granted(container.app) }) { (plan, saved) ->
        container.workout.start(
            plan.sportType, plan.askToEnd, plan.target, plan.useGps, plan.keepRoute, plan.rest, plan.setsTarget,
            logSets = plan.logSets, structure = plan.structure,
        )
        saved?.let { s -> rootVm.saveSportSettings(plan.sportType, s) }
        nav.workoutStarted()
    }
    val pageHooks = PageHooks(
        container, dayVm, deviceVm, firmwareVm, workoutsVm, rootVm,
        rememberHealthConnectActions(rootVm, deviceVm), rememberBackupActions(deviceVm),
    )
    // Back closes what's open first (those screens handle it, registered later so they win). From a tab's root it goes
    // to the tab before, then to Today. On Today looking at a past day, back first returns to today. Only Today's back
    // leaves the app.
    val subScreenOpen = nav.stack(nav.current).isNotEmpty() || nav.pages.isNotEmpty()
    val now = LocalDate.now()
    BackHandler(enabled = !subScreenOpen && (tab != Tab.Today.ordinal || day.date != now)) {
        if (nav.back(firmwareInstalling(), running != null) == BackResult.AtHome) dayVm.select(now)
    }
    // Back handlers on tabs that aren't showing (they stay composed, stacks and all) answer to a dispatcher nobody
    // presses, so back only ever closes what's on screen.
    val idleBack = remember {
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
    }
    // The pages are what the glass nav bar blurs as they scroll under it, and popups are drawn in the app's own window
    // over a blur of the pages, like the nav bar.
    val navGlass = rememberHazeState()
    val glassHost = remember { GlassHost() }
    // Half open on a table the top half is heart rate, or the workout's heart card with the controls below. Stood up
    // like a book, only the live workout splits.
    val folds = rememberFolds()
    val hinge = folds.table
    CompositionLocalProvider(
        LocalGoals provides goals, LocalHeartZones provides heartZones, LocalGlassHost provides glassHost,
        LocalGrowOrigin provides growOrigin,
    ) {
      Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (hinge != null) {
            TabletopTop(hinge, container, running?.takeIf { !workoutMinimized }, day, live, strapFacts.connection)
        }
        // Below the fold the status bar is far away, so the pages don't make room for it.
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().let { if (hinge != null) it.consumeWindowInsets(WindowInsets.statusBars) else it },
        ) {
            // Wider than tall (a phone on its side, an unfolded foldable turned sideways): the tabs stand down the left
            // edge, so the pages keep their height.
            val rail = maxWidth > maxHeight && maxWidth >= RailMinWidth
            val navItems = remember { tabItems() }
            // A detail covering the tab is a screen stepped into: no tab bar, only its back button (spec 3.2). Wide
            // enough for the detail to open beside the tab, the bar stays with the tab it belongs to.
            var barHidden by remember { mutableStateOf(false) }
            val detailOpen = nav.stack(nav.current).isNotEmpty()
            val showBar = !rail && !barHidden && !(detailOpen && maxWidth < SideBySideWidth)
            val selectTab: (Int) -> Unit = { t -> tapTab(t, nav, firmwareVm, scroll) }
            Scaffold(
                bottomBar = {
                    // Qualified: inside the Column, the plain name would pick its ColumnScope overload.
                    androidx.compose.animation.AnimatedVisibility(
                        showBar,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) { FloatingNavBar(navItems, tab, selectTab, glass = navGlass) }
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                CompositionLocalProvider(LocalHideTabBar provides { barHidden = it }) {
                    GoalsSheetHost(nav, rootVm)
                    TabsPager(
                        nav, shown, histories,
                        TodayHooks(
                            TodayStrap(
                                paired = pairedKnown, pairing = pairing,
                                stopped = syncStopped(pairedKnown, strap.view.connection, strap.view.sync), record = strap.view.record,
                                canSync = connected, staleAfter = preview?.staleAfter ?: STALE_AFTER_S,
                            ),
                            LiveHeroHooks({ liveGlance.value }, onGlanceAction, { liveHeroShown = it }),
                            today.layout, today.usualBedtime, running, onOpenRunning = { container.workout.minimized.value = false },
                        ),
                        container, dayVm, workoutsVm, rootVm, liveHeart, padding, rail, pageGrow, navGlass, idleBack, scroll,
                        showCharge, onChargeConsume = { showCharge = false },
                        header = HeaderHooks(day, today, running, strap.chip, dayVm, rootVm, openPage),
                        detail = { t, d ->
                            DetailHost(
                                t, d, nav, shown, histories, container, dayVm, workoutsVm, liveHeart, profile, padding,
                                onOpenProfile = { openPage(Page.Profile) }, seeHabitAge = seeHabitAge,
                            )
                        },
                        onOpenPage = openPage, seeHabitAge = seeHabitAge,
                    )
                    PagesOverTab(
                        nav, pageHooks, gates.requestBluetooth, strap, preview != null, padding, rail, growFrom, pageGrow,
                        navGlass, idleBack, openPage, closePages, firmwareInstalling,
                    )
                }
            }
            if (rail) FloatingNavRail(navItems, tab, selectTab, Modifier.align(Alignment.CenterStart), glass = navGlass)
            AllMetricsHost(nav, shown, workoutsVm, scroll, closePages, onShowCharge = { showCharge = true })
            AddWorkoutHost(nav, container, workoutsVm, rootVm)
            SaveStretchHost(nav, container, workoutsVm, rootVm)
            WorkoutLayer(
                container, nav, workoutState, workoutMinimized, workoutsVm, rootVm,
                WorkoutLayerHooks(
                    live, strapFacts.connection, gates.startPlan, gates.requestLocation,
                    capsuleBottom = if (showBar) CapsuleOverBar else CapsuleNoBar, tabletop = hinge != null, bookHinge = folds.book,
                ),
                glass = navGlass, glance = { liveGlance.value }, onGlanceAction = onGlanceAction,
                capsuleHidden = liveHeroShown && nav.atTodayRoot,
            )
            HistoryBadgesHost(nav, rootVm, workoutState)
            BluetoothBlockedDialog(gates)
            GlassOverlay(glassHost, navGlass)
        }
      }
    }
}
