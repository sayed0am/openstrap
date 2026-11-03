// The tabs read the day, workouts and root view models they show, as they did inside AppRoot. The keyed effect runs once
// per key after the frame commits, as it did there. A SideEffect would run on every recomposition.
@file:Suppress("ViewModelForwarding", "ComposeViewModelForwarding", "UnnecessaryLaunchedEffect")

package org.openstrap.app.ui.root

import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import org.openstrap.app.AppContainer
import org.openstrap.app.data.Healthspan
import org.openstrap.app.data.Moment
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.db.StrapReadinessRow
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.components.GrowProgress
import org.openstrap.app.ui.components.ListDetail
import org.openstrap.app.ui.components.Motion
import org.openstrap.app.ui.components.NavItem
import org.openstrap.app.ui.components.NavRailWidth
import org.openstrap.app.ui.components.PullToSync
import org.openstrap.app.ui.components.SideBySideWidth
import org.openstrap.app.ui.components.sinkBehind
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.ui.dashboard.DayPicker
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.DayViewModel
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.LocalScrollToTop
import org.openstrap.app.ui.dashboard.MomentHeader
import org.openstrap.app.ui.dashboard.RecoveryFailure
import org.openstrap.app.ui.dashboard.StrapChip
import org.openstrap.app.ui.dashboard.Wear
import org.openstrap.app.presentation.dayTitle
import org.openstrap.app.ui.dashboard.heroLightAngle
import org.openstrap.app.ui.glow.draw.momentMood
import org.openstrap.app.presentation.todayTitle
import org.openstrap.app.ui.glow.AllMetricsSheet
import org.openstrap.app.ui.glow.GlowActivityScreen
import org.openstrap.app.ui.glow.GlowRecoveryScreen
import org.openstrap.app.ui.glow.GlowSleepScreen
import org.openstrap.app.ui.glow.GlowTodayScreen
import org.openstrap.app.ui.glow.HomeTab
import org.openstrap.app.ui.glow.IndexTarget
import org.openstrap.app.ui.glow.LiveHeroHooks
import org.openstrap.app.ui.glow.LocalGlowMood
import org.openstrap.app.ui.glow.LocalGlowPagePaused
import org.openstrap.app.ui.glow.TodayStrap
import org.openstrap.app.ui.glow.draw.GlowMood
import org.openstrap.app.ui.glow.metricIndex
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.WorkoutsViewModel

/** Each tab's place in the bar: its label, icons and the capsule's colour while it shows (spec 3.2). */
fun tabItems(): List<NavItem> = Tab.entries.map { tab ->
    when (tab) {
        Tab.Today -> NavItem(tab.label, AppIcons.HouseSimple, AppIcons.HouseSimpleFill, Color.White)
        Tab.Recovery -> NavItem(tab.label, AppIcons.Plant, AppIcons.PlantFill, MetricColors.readiness)
        Tab.Sleep -> NavItem(tab.label, AppIcons.Moon, AppIcons.MoonFill, MetricColors.sleepDeep)
        Tab.Activity -> NavItem(tab.label, AppIcons.SneakerMove, AppIcons.SneakerMoveFill, MetricColors.steps)
    }
}

/** The day shown as every tab and detail sees it: the debug previews applied, its Recovery, strap score and check-in. */
class ShownDay(
    val day: DayUiState,
    val recovery: RecoveryResult?,
    val recoveryFailure: RecoveryFailure?,
    val healthspan: Healthspan.Outcome?,
    val strapReadiness: StrapReadinessRow?,
    val checkIn: Int?,
    /** How much of the day the strap was worn, for the Partial day tag on the cards it lowers (spec 4.3). */
    val wear: Wear?,
    /** The first real score shows its moment (E4). */
    val firstScore: Boolean,
) {
    /** The strap's own score for the day shown. */
    val strapScore: Int? get() = strapReadiness?.takeIf { it.day == day.dayStart }?.score
}

/** The stored histories the charts read. */
class Histories(val daily: DailyHistory)

/** Live heart rate, whether it's on, whether it can be (the strap connected), and the switch. */
class LiveHeart(val bpm: Int?, val enabled: Boolean, val canUse: Boolean, val toggle: (Boolean) -> Unit)

/** Each tab's count of taps asking its lists to scroll to the top. */
@Stable
class ScrollRequests {
    private val counts = mutableStateListOf(*Array(Tab.entries.size) { 0 })

    fun of(tab: Int): Int = counts[tab]

    fun bump(tab: Int) {
        counts[tab] = counts[tab] + 1
    }
}

/** What Today shows of the strap and a workout under way, and the moment it's laid out for. */
class TodayHooks(
    val strap: TodayStrap,
    val liveHero: LiveHeroHooks,
    val moment: Moment?,
    val usualBedtime: java.time.LocalTime?,
    val running: WorkoutState.Recording?,
    val onOpenRunning: () -> Unit,
)

/**
 * The four tabs, every one kept composed so a swipe never builds a whole dashboard mid-gesture (with only the
 * neighbours kept, crossing halfway built the next one). Glows on tabs off screen, while the pager moves, or under the
 * full-screen workout hold still. Swiping moves between tabs: [nav] owns the tab and the pager follows it. A tap
 * animates the pager there, and only a swipe the user made, once it settles, is another way to pick the tab. Back
 * handlers on tabs that aren't showing answer to [idleBack], a dispatcher nobody presses, so back only ever closes
 * what's on screen.
 */
@Composable
internal fun TabsPager(
    nav: AppNavState,
    shown: ShownDay,
    histories: Histories,
    today: TodayHooks,
    container: AppContainer,
    dayVm: DayViewModel,
    workoutsVm: WorkoutsViewModel,
    rootVm: AppRootViewModel,
    live: LiveHeart,
    padding: PaddingValues,
    rail: Boolean,
    pageGrow: GrowProgress,
    navGlass: HazeState,
    idleBack: NavigationEventDispatcherOwner,
    scroll: ScrollRequests,
    showCharge: Boolean,
    onChargeConsume: () -> Unit,
    header: HeaderHooks,
    detail: @Composable (Tab, Detail) -> Unit,
    onOpenPage: (Page) -> Unit,
    seeHabitAge: () -> Unit,
) {
    val tab = nav.tab.current
    val pager = rememberPagerState(initialPage = tab) { Tab.entries.size }
    val swiped = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    LaunchedEffect(tab) {
        if (pager.currentPage != tab) {
            swiped.set(false)
            pager.animateScrollToPage(tab, animationSpec = tween(Motion.SWIFT, easing = Motion.easing))
        }
    }
    LaunchedEffect(pager) {
        launch { pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) swiped.set(true) } }
        snapshotFlow { pager.settledPage }.collect { nav.settled(it, swiped.get()) }
    }
    val currentDetail = nav.stack(nav.current).lastOrNull()
    LaunchedEffect(currentDetail) {
        workoutsVm.open((currentDetail as? Detail.Workout)?.start)
        // A screen opened or closed: the next tap on the tab scrolls before it pops.
        nav.moved()
    }
    val strap by rootVm.strap.collectAsStateWithLifecycle()
    val connected = strap.connection is ConnectionState.Connected
    // A detail screen open on this tab keeps the swipe for itself (and for its back gesture).
    val subScreenOpen = currentDetail != null || nav.pages.isNotEmpty()
    val covered = rememberWorkoutCovers(container.workout.state, container.workout.minimized, nav)
    HorizontalPager(
        pager, Modifier.padding(start = if (rail) NavRailWidth else 0.dp).sinkBehind(pageGrow).hazeSource(navGlass),
        userScrollEnabled = !subScreenOpen, key = { it }, beyondViewportPageCount = Tab.entries.size - 1,
    ) { page ->
        val back = if (page == tab) emptyArray() else arrayOf(LocalNavigationEventDispatcherOwner provides idleBack)
        @Suppress("SpreadOperator") // at most one local, once per page
        CompositionLocalProvider(
            // Remembered: a new lambda each time would be a new static local, recomposing the whole tab under it.
            LocalGlowPagePaused provides remember(pager, page, covered) { tabPaused(pager, page, covered) },
            LocalScrollToTop provides scroll.of(page),
            *back,
        ) {
            val t = Tab.entries[page]
            // A tab's root with its stack opening over it (or beside it, when wide).
            ListDetail(
                nav.stack(t), isOpen = { it.isNotEmpty() }, keys = { s -> s.map(Detail::keyOf) }, minWidth = SideBySideWidth,
                detail = { stack -> stack.lastOrNull()?.let { detail(t, it) } },
            ) {
                PullToSync(strap.sync, connected, container.strap::syncNow) {
                    TabRoot(
                        t, nav, shown, histories, today, container, dayVm, workoutsVm, rootVm, live, padding, showCharge,
                        onChargeConsume, header, onOpenPage, seeHabitAge,
                    )
                }
            }
        }
    }
}

/**
 * Whether glows on [page] hold still: while the pager moves, while the page is off screen, and while [covered] says the
 * workout layer hides the tabs (the Today hero beating under the dimmed Mind and body screen drew every frame).
 */
internal fun tabPaused(pager: PagerState, page: Int, covered: () -> Boolean): () -> Boolean =
    { covered() || pager.isScrollInProgress || pager.settledPage != page }

/** A tab's own screen, at the root of its stack. Cards about other tabs open those screens here, and none switches tab. */
@Composable
private fun TabRoot(
    t: Tab,
    nav: AppNavState,
    shown: ShownDay,
    histories: Histories,
    today: TodayHooks,
    container: AppContainer,
    dayVm: DayViewModel,
    workoutsVm: WorkoutsViewModel,
    rootVm: AppRootViewModel,
    live: LiveHeart,
    padding: PaddingValues,
    showCharge: Boolean,
    onChargeConsume: () -> Unit,
    header: HeaderHooks,
    onOpenPage: (Page) -> Unit,
    seeHabitAge: () -> Unit,
) {
    val day = shown.day
    val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
    val usualSteps by dayVm.usualSteps.collectAsStateWithLifecycle()
    val activity by dayVm.activityHistory.collectAsStateWithLifecycle()
    val suggestions by workoutsVm.suggestions.collectAsStateWithLifecycle()
    // Hidden while a workout records: the strap's history may show the one under way.
    val suggestion = suggestions.firstOrNull().takeIf { today.running == null }
    val saveSuggestion: () -> Unit = { nav.setSavingStretch(suggestion?.let { it.start..it.end }) }
    val dismissSuggestion: () -> Unit = { suggestion?.let { container.workout.dismissSuggestion(it.start, it.end) } }
    val openStack: (Tab, Detail) -> Unit = { tab, d -> nav.setStack(tab, listOf(d)) }
    when (t) {
        // The moment's mood lights Today only (spec 2.1 Moods), the Sleep tab sets its own.
        Tab.Today -> CompositionLocalProvider(LocalGlowMood provides (today.moment?.let(::momentMood) ?: GlowMood.Day)) {
            val offer by container.workout.offer.collectAsStateWithLifecycle()
            val edits by rootVm.todayEdits.collectAsStateWithLifecycle()
            val habitAgeSeen by rootVm.habitAgeSeenAsOf.collectAsStateWithLifecycle()
            GlowTodayScreen(
                day, live.bpm, header = { TabHeader(Tab.Today, header) }, contentPadding = padding, moment = today.moment,
                onOpenMetric = { openStack(Tab.Today, Detail.OfMetric(it)) },
                onOpen = { openStack(Tab.Today, it) }, usualSteps = usualSteps,
                recovery = shown.recovery,
                strapReadiness = shown.strapReadiness,
                checkIn = shown.checkIn,
                onCheckIn = { v -> rootVm.saveCheckIn(day.date, v) },
                onOpenRecovery = { openStack(Tab.Today, Detail.Recovery) },
                workouts = workouts,
                onOpenWorkout = { openStack(Tab.Today, Detail.Workout(it)) },
                onOpenHealthspan = { seeHabitAge(); openStack(Tab.Today, Detail.Healthspan) },
                healthspan = shown.healthspan,
                habitAgeSeenAsOf = habitAgeSeen,
                onOpenProfile = { onOpenPage(Page.Profile) },
                history = histories.daily,
                edits = edits,
                onEditsChange = rootVm::saveTodayEdits,
                suggestion = suggestion,
                onSaveSuggestion = saveSuggestion,
                onDismissSuggestion = dismissSuggestion,
                // The same way in as Activity's start card: the ready screen for the sport, or Choose with every sport.
                onStartSport = { nav.openReady(it) },
                onOtherSport = nav::openChoose,
                offer = offer,
                onRecordOffer = { container.workout.recordOffer() },
                onDeclineOffer = { container.workout.declineOffer() },
                running = today.running,
                onOpenRunning = today.onOpenRunning,
                liveHero = today.liveHero,
                heroLightAngle = today.moment?.let(::heroLightAngle),
                strap = today.strap,
                recoveryFailure = shown.recoveryFailure,
                onRetryRecovery = dayVm::retryRecovery,
                onPair = { onOpenPage(Page.Strap) },
                onSyncNow = container.strap::syncNow,
                usualBedtime = today.usualBedtime,
                wear = shown.wear, firstScore = shown.firstScore,
            )
        }
        Tab.Recovery -> GlowRecoveryScreen(
            day, shown.recovery, histories.daily, header = { TabHeader(Tab.Recovery, header) }, contentPadding = padding,
            onOpenMetric = { openStack(Tab.Recovery, Detail.OfMetric(it)) },
            onOpenScore = { openStack(Tab.Recovery, Detail.OfScore(it)) },
            strapScore = shown.strapScore,
            healthspan = shown.healthspan,
            onOpenHealthspan = { seeHabitAge(); openStack(Tab.Recovery, Detail.Healthspan) },
            onOpenProfile = { onOpenPage(Page.Profile) },
            showCharge = showCharge, onChargeShown = onChargeConsume,
            recoveryFailure = shown.recoveryFailure, onRetryRecovery = dayVm::retryRecovery,
            wear = shown.wear, firstScore = shown.firstScore,
        )
        Tab.Sleep -> GlowSleepScreen(
            day, header = { TabHeader(Tab.Sleep, header) }, contentPadding = padding,
            onOpen = { openStack(Tab.Sleep, it) }, onRestore = dayVm::restoreSleep,
            recovery = shown.recovery,
        )
        Tab.Activity -> GlowActivityScreen(
            day, shown.recovery, workouts, activity,
            header = { TabHeader(Tab.Activity, header) }, contentPadding = padding,
            onOpenMetric = { openStack(Tab.Activity, Detail.OfMetric(it)) },
            onOpen = { openStack(Tab.Activity, it) }, usualSteps = usualSteps,
            onOpenWorkout = { openStack(Tab.Activity, Detail.Workout(it)) },
            onAllWorkouts = { openStack(Tab.Activity, Detail.AllWorkouts) },
            onEditProfile = { onOpenPage(Page.Profile) },
            onStartWorkout = { nav.openReady(it) },
            suggestion = suggestion,
            onSaveSuggestion = saveSuggestion,
            onDismissSuggestion = dismissSuggestion,
            onAllSports = nav::openChoose,
            onAddWorkout = { nav.setAddingOn(day.date) },
            liveHeartRate = live.bpm, history = histories.daily, wear = shown.wear,
        )
    }
}

/**
 * All metrics: a map, not a card. Rows whose home is a tab go there, at its root and top (Charge to its card), the
 * rest open their detail on the tab showing. The day is shared, so the tab shows the same one. The sheet closes first.
 */
@Composable
internal fun AllMetricsHost(
    nav: AppNavState,
    shown: ShownDay,
    workoutsVm: WorkoutsViewModel,
    scroll: ScrollRequests,
    closePages: () -> Boolean,
    onShowCharge: () -> Unit,
) {
    if (!nav.allMetricsOpen) return
    val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
    AllMetricsSheet(
        metricIndex(shown.day, shown.recovery, shown.healthspan, workouts, strapScore = shown.strapScore), shown.day.date,
        onDismiss = { nav.setAllMetrics(false) },
        onOpen = { target ->
            nav.setAllMetrics(false)
            closePages()
            when (target) {
                is IndexTarget.Open -> nav.push(nav.current, target.detail)
                is IndexTarget.Home -> {
                    val home = when (target.tab) {
                        HomeTab.Recovery -> Tab.Recovery
                        HomeTab.Sleep -> Tab.Sleep
                    }
                    nav.setStack(home, emptyList())
                    nav.select(home)
                    if (target.charge) onShowCharge() else scroll.bump(home.ordinal)
                }
            }
        },
    )
}

/** What the header on every tab shows and opens. */
internal class HeaderHooks(
    val day: DayUiState,
    val today: TodayMoment,
    val running: WorkoutState.Recording?,
    val chip: StrapChip,
    val dayVm: DayViewModel,
    val rootVm: AppRootViewModel,
    val onOpenPage: (Page) -> Unit,
)

/** The header on every tab: the moment on Today (a past day's name for a past day), the tab's name elsewhere. */
@Composable
private fun TabHeader(t: Tab, h: HeaderHooks) {
    val dayVm = h.dayVm
    val daysWithData by dayVm.daysWithData.collectAsStateWithLifecycle()
    val picker = remember(daysWithData, dayVm) { DayPicker(daysWithData, dayVm::select) }
    val updateWaiting by h.rootVm.updateWaiting.collectAsStateWithLifecycle()
    val day = h.day
    val title = if (t != Tab.Today) {
        t.label
    } else {
        h.today.layout?.let { m ->
            // During a workout the header is the sport under way, after one the sport just done.
            val sport = if (m == Moment.During) h.running?.workout?.sportType else h.today.lastWorkout?.workout?.sportType
            todayTitle(m, day.date, sport)
        } ?: dayTitle(day.date)
    }
    MomentHeader(
        title, day.date, h.chip, dayVm::previousDay, dayVm::nextDay, picker,
        onOpenYou = { h.onOpenPage(Page.You) }, onOpenStrap = { h.onOpenPage(Page.Strap) },
        // All metrics is hidden for now (spec 3.1). Passing { nav.setAllMetrics(true) } brings the button back: the
        // sheet opens over whatever shows, and no tab hears of it (spec 3.2).
        onOpenAllMetrics = null,
        updateWaiting = updateWaiting,
    )
}
