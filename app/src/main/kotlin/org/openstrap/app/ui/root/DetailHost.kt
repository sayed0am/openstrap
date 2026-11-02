// The workout detail takes the workouts view model for its summary and delete, as it did inside AppRoot.
@file:Suppress("ViewModelForwarding", "ComposeViewModelForwarding")

package org.openstrap.app.ui.root

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import org.openstrap.app.AppContainer
import org.openstrap.app.data.EnergyExpenditure
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.device.WorkoutSession
import org.openstrap.app.ui.dashboard.DayViewModel
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.glow.ActivityDetailContext
import org.openstrap.app.ui.glow.EnergyHistory
import org.openstrap.app.ui.glow.GlowHealthspanScreen
import org.openstrap.app.ui.glow.GlowMetricDetailScreen
import org.openstrap.app.ui.glow.GlowRecoveryScreen
import org.openstrap.app.ui.glow.GlowScoreDetailScreen
import org.openstrap.app.ui.glow.GlowSleepScreen
import org.openstrap.app.ui.glow.GlowWorkoutDetailScreen
import org.openstrap.app.ui.glow.GlowWorkoutsScreen
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.SleepEditing
import org.openstrap.app.ui.glow.WeekDetail
import org.openstrap.app.ui.glow.dayName
import org.openstrap.app.ui.workout.WorkoutsViewModel

/**
 * What a tab's stack shows on top: the same screens on every tab, each pushing onto the stack it's on. A switcher pick
 * takes the detail's place, anything else it opens goes on top.
 */
@Composable
internal fun DetailHost(
    tab: Tab,
    detail: Detail,
    nav: AppNavState,
    shown: ShownDay,
    histories: Histories,
    container: AppContainer,
    dayVm: DayViewModel,
    workoutsVm: WorkoutsViewModel,
    live: LiveHeart,
    profile: UserProfile,
    padding: PaddingValues,
    onOpenProfile: () -> Unit,
    seeHabitAge: () -> Unit,
) {
    val day = shown.day
    val pop: () -> Unit = { nav.pop(tab) }
    val push: (Detail) -> Unit = { nav.push(tab, it) }
    val onSwitch: (Detail) -> Unit = { nav.replaceTop(tab, it) }
    val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
    when (detail) {
        is Detail.OfMetric -> GlowMetricDetailScreen(
            detail.metric, detail.night, day, histories.daily, live.bpm, live.enabled, live.canUse, live.toggle,
            push, onSwitch, dayVm::select, padding, pop,
        )
        is Detail.OfScore -> GlowScoreDetailScreen(
            detail.score, day, shown.recovery, shown.strapScore, histories.daily,
            onOpen = push, onSwitch = onSwitch, onSelectDay = dayVm::select,
            contentPadding = padding, onBack = pop,
            workouts = workouts, paiHistory = histories.pai, onEditProfile = onOpenProfile, energyHistory = histories.energy,
            sleepEditing = SleepEditing(dayVm::setSleepTimes, dayVm::deleteSleep, dayVm::restoreSleep),
        )
        Detail.Week -> WeekDetail(
            ActivityDetailContext(
                day, shown.recovery?.takeIf { it.date == day.date }, workouts, histories.pai, histories.daily, onOpen = push, onSwitch = {},
                onSelectDay = dayVm::select, onEditProfile = onOpenProfile, contentPadding = padding, onBack = pop,
            ),
        )
        is Detail.Workout -> {
            val openWorkout by workoutsVm.open.collectAsStateWithLifecycle()
            WorkoutDetail(
                openWorkout?.takeIf { it.workout.start == detail.start }, padding, onBack = pop,
                onDelete = { container.workout.discard(detail.start); pop() },
                workoutsVm = workoutsVm, session = container.workout,
                bmrPerMinute = EnergyExpenditure.bmrPerMinute(profile, profile.age()),
                recovery = shown.recovery, energyHistory = histories.energy,
                // Its day's Calories: the day shown moves to it, as a strip pick does.
                onOpenDay = { d -> dayVm.select(d); push(Detail.OfScore(Score.Energy)) },
            )
        }
        Detail.AllWorkouts -> GlowWorkoutsScreen(workouts, padding, onOpen = { push(Detail.Workout(it)) }, onBack = pop)
        Detail.Healthspan -> GlowHealthspanScreen(shown.healthspan, padding, onBack = pop)
        // The Recovery and Sleep tabs' screens, opened over another tab from a card about them.
        Detail.Recovery -> {
            BackHandler(onBack = pop)
            GlowRecoveryScreen(
                day, shown.recovery, histories.daily,
                header = { PageHeader("Recovery", pop, subtitle = dayName(day.date)) },
                contentPadding = padding, onOpenMetric = { push(Detail.OfMetric(it)) },
                onOpenScore = { push(Detail.OfScore(it)) }, strapScore = shown.strapScore,
                healthspan = shown.healthspan, onOpenHealthspan = { seeHabitAge(); push(Detail.Healthspan) }, onOpenProfile = onOpenProfile,
                recoveryFailure = shown.recoveryFailure, onRetryRecovery = dayVm::retryRecovery,
                wear = shown.wear, firstScore = shown.firstScore,
            )
        }
        Detail.Sleep -> {
            BackHandler(onBack = pop)
            GlowSleepScreen(
                day, header = { PageHeader("Sleep", pop, subtitle = dayName(day.date)) },
                contentPadding = padding, onOpen = push, recovery = shown.recovery, onRestore = dayVm::restoreSleep,
            )
        }
    }
}

/** A workout's detail: one page for the strap's and the phone's ([GlowWorkoutDetailScreen]). */
@Composable
private fun WorkoutDetail(
    a: WorkoutAnalysis?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    workoutsVm: WorkoutsViewModel,
    session: WorkoutSession,
    bmrPerMinute: Float?,
    recovery: RecoveryResult?,
    energyHistory: EnergyHistory,
    onOpenDay: (LocalDate) -> Unit,
) {
    val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
    // OpenStrap's own recordings also get their summary (route, laps, sets, the recording for trim) and are deleted
    // through the session. The strap's are deleted and remembered, so the next sync doesn't bring them back.
    val start = a?.workout?.start
    val fromPhone = a?.fromPhone == true
    val summary by remember(start, fromPhone) {
        if (start != null && fromPhone) workoutsVm.phoneSummary(start) else flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val usualDrop by produceState<Int?>(null, start, fromPhone) { value = start?.takeIf { !fromPhone }?.let { workoutsVm.usualDrop(it) } }
    // The workout's day: its active kcal as just worked out when it's the day shown, else as stored.
    val date = a?.let { Instant.ofEpochSecond(it.shownStart).atZone(ZoneId.systemDefault()).toLocalDate() }
    val stored by remember(date, energyHistory) {
        date?.let { d -> energyHistory(d, d) } ?: flowOf(emptyMap())
    }.collectAsStateWithLifecycle(emptyMap())
    val dayActive = recovery?.takeIf { it.date == date }?.energy?.activeKcal ?: date?.let { stored[it]?.activeKcal }
    GlowWorkoutDetailScreen(
        a, summary, workouts, usualDrop, contentPadding, onBack,
        onDelete = if (fromPhone) onDelete else ({ start?.let(workoutsVm::deleteStrapWorkout); onBack() }),
        onTrim = { from, to -> start?.let { session.trim(it, from, to) } }, onUndoTrim = { start?.let(session::undoTrim) },
        bmrPerMinute = bmrPerMinute, dayActiveKcal = dayActive, onOpenDay = date?.let { d -> { onOpenDay(d) } },
    )
}
