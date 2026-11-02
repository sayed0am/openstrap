package org.openstrap.app.ui.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.LocalDate
import org.openstrap.app.AppContainer
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.Rewards
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.presentation.RewardsCopy
import org.openstrap.app.ui.device.GoalsSheet
import org.openstrap.app.ui.glow.AllBadgesPage
import org.openstrap.app.ui.glow.CelebrationsSheet
import org.openstrap.app.ui.glow.GoalsAndBestsPage
import org.openstrap.app.ui.glow.HeartZonesScreen
import org.openstrap.app.ui.glow.HistoryBadgesScreen
import org.openstrap.app.ui.glow.WeeklyGoalSheet
import org.openstrap.app.ui.glow.WeeklyRecapPage
import org.openstrap.app.ui.dashboard.LocalHeartZones

/**
 * The Goals and bests page, All badges and the weekly recap (workouts spec 3.5), from what's stored and every workout,
 * with the weekly goal and celebrations sheets they open.
 */
@Composable
internal fun RewardsPage(
    shown: Page,
    rootVm: AppRootViewModel,
    firstDay: DayOfWeek,
    padding: PaddingValues,
    open: (Page) -> Unit,
    close: () -> Unit,
) {
    val view by rootVm.rewardsView.collectAsStateWithLifecycle()
    val goals by rootVm.goals.collectAsStateWithLifecycle()
    var editGoal by rememberSaveable { mutableStateOf(false) }
    var celebrations by rememberSaveable { mutableStateOf(false) }
    when (shown) {
        Page.GoalsAndBests -> GoalsAndBestsPage(
            view, padding, onGoal = { editGoal = true }, onAllBadges = { open(Page.AllBadges) }, onRecap = { open(Page.WeeklyRecap) },
            onCelebrations = { celebrations = true }, onBack = close,
        )
        Page.AllBadges -> AllBadgesPage(view, padding, onBack = close)
        else -> {
            // Only before the rewards first load: last week by the clock, as the page opens.
            @Suppress("ForbiddenMethodCall/uiClock")
            val first = view?.thisWeek?.minusWeeks(1) ?: Rewards.weekStart(LocalDate.now(), firstDay).minusWeeks(1)
            WeeklyRecapPage(view, first, padding, onGoal = { editGoal = true }, onBack = close)
        }
    }
    if (editGoal) {
        WeeklyGoalSheet(goals.workoutsPerWeek, onSave = { n ->
            rootVm.saveGoals(goals.copy(workoutsPerWeek = n))
            editGoal = false
        }, onDismiss = { editGoal = false })
    }
    if (celebrations) {
        val settings by rootVm.celebrations.collectAsStateWithLifecycle()
        CelebrationsSheet(
            settings.goalMoment, settings.weeklyRecap,
            onGoalMoment = rootVm::setGoalMoment,
            onWeeklyRecap = rootVm::setWeeklyRecap,
            onDismiss = { celebrations = false },
        )
    }
}

/**
 * The Heart rate zones page, from Ready's Your zones and the You page: your max as the app works it out, and your
 * zones. Both are stored in the settings, which every zone and max HR reader goes by, and moved zones are in use at once.
 */
@Composable
internal fun HeartZonesPage(
    container: AppContainer,
    padding: PaddingValues,
    onMax: (Int?) -> Unit,
    onZones: (HeartZones) -> Unit,
    onBack: () -> Unit,
) {
    val zones = LocalHeartZones.current
    val max by container.maxHeartRates.current.collectAsStateWithLifecycle()
    // Afresh as the page opens, as it always was. A profile change works it out again on its own.
    LaunchedEffect(Unit) { container.maxHeartRates.now() }
    HeartZonesScreen(max, zones, padding, onMax = onMax, onZones = onZones, onBack = onBack)
}

/** The Goals editor, over everything, with the calorie goal your last four weeks suggest read as it opens (calories spec 3.4). */
@Composable
internal fun GoalsSheetHost(nav: AppNavState, rootVm: AppRootViewModel) {
    if (!nav.editGoals) return
    val goals by rootVm.goals.collectAsStateWithLifecycle()
    val suggestedCalories by produceState<Int?>(null) { value = rootVm.suggestedCalorieGoal() }
    GoalsSheet(goals, suggestedCalories = suggestedCalories, onSave = { g ->
        rootVm.saveGoals(g)
        nav.setEditGoals(false)
    }, onDismiss = { nav.setEditGoals(false) })
}

/** The badges past history earned, once, all on one screen (requests spec 7.2), when no workout is on screen. */
@Composable
internal fun HistoryBadgesHost(nav: AppNavState, rootVm: AppRootViewModel, workoutState: WorkoutState) {
    val historyBadges by rootVm.historyBadges.collectAsStateWithLifecycle()
    val earned = historyBadges?.takeIf { workoutState is WorkoutState.Idle && nav.workout == WorkoutOverlay.None }
    if (earned.isNullOrEmpty()) return
    Box(Modifier.fillMaxSize()) {
        HistoryBadgesScreen(
            earned.map { it.badge }, WindowInsets.navigationBars.asPaddingValues(),
            onContinue = rootVm::historyBadgesShown,
            face = { b -> RewardsCopy.face(b, earned.firstOrNull { it.badge.id == b.id }?.goal) },
        )
    }
}
