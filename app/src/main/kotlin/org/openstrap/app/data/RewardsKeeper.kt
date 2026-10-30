package org.openstrap.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.SettingsStore

/**
 * Keeps the stored rewards ([Rewards]) in step with the workouts: works the weeks, bests and badges out again from
 * everything whenever the list of workouts or the weekly goal changes (a new workout, a trim, a delete), and stores
 * them. The first time, the badges past history earned wait to be shown once, all together.
 */
class RewardsKeeper(private val dao: HealthDao, private val settings: SettingsStore) {
    /** Everything the rewards read: the workouts as [Rewards.Done], the days' steps, the weeks stored. */
    data class Facts(val workouts: List<Rewards.Done>, val days: List<Rewards.Day>, val firstDay: DayOfWeek, val zone: ZoneId)

    /** The weekly recap notification's tap, until the app root has opened the recap. */
    val openRecap = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** The locale's first day of the week (workouts spec 3.1). */
    val firstDay: DayOfWeek get() = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    /** [workouts] (the merged list, [org.openstrap.app.ui.workout.WorkoutsViewModel.workouts]) with what the phone kept of each. */
    suspend fun facts(workouts: List<WorkoutAnalysis>): Facts = withContext(Dispatchers.IO) {
        val phone = dao.appWorkoutsAll().associateBy { it.start }
        val sets = dao.setsAll().groupBy { it.workoutStart }
        // One still recording isn't done yet.
        val done = workouts.filter { it.end > it.workout.start && !(it.fromPhone && phone[it.workout.start]?.end == null) }.map { a ->
            val p = phone[a.workout.start].takeIf { a.fromPhone }
            Rewards.Done(
                a.workout.start, a.shownStart, a.end, a.workout.sportType, a.activeSeconds, a.startedByYou,
                gpsMeters = a.distanceMeters?.takeIf { !a.distanceFromSteps }, meters = a.distanceMeters,
                stretches = Rewards.Stretch.parse(p?.stretches), structure = p?.structure, sets = sets[a.workout.start].orEmpty(), activeKcal = a.activeKcal,
            )
        }
        val days = dao.dailyStepsOnce(0, Long.MAX_VALUE / 2).mapNotNull { d ->
            runCatching { Rewards.Day(LocalDate.parse(d.day), d.value.toInt()) }.getOrNull()
        }
        Facts(done, days, firstDay, ZoneId.systemDefault())
    }

    /** The weeks and badges stored: past weeks' goals, and badges once earned. */
    suspend fun stored(): Pair<List<org.openstrap.app.data.db.WeekResult>, List<org.openstrap.app.data.db.BadgeRow>> =
        withContext(Dispatchers.IO) { dao.weekResultsOnce() to dao.badgesOnce() }

    /** Works everything out again from [workouts] against [goal] and stores it. */
    suspend fun refresh(workouts: List<WorkoutAnalysis>, goal: Int) {
        // The list starts empty before it loads: only a database with no workouts at all starts the rewards empty.
        if (workouts.isEmpty()) {
            if (!settings.rewardsStarted.first() && dao.appWorkoutsAll().isEmpty() && dao.workouts().first().isEmpty()) settings.startRewards(historyBadges = false)
            return
        }
        val f = facts(workouts)
        withContext(Dispatchers.Default) {
            val today = LocalDate.now(f.zone)
            val weeks = Rewards.weeks(f.workouts, dao.weekResultsOnce(), goal, today, f.firstDay, f.zone)
            dao.upsertWeekResults(weeks)
            dao.replacePersonalBests(Rewards.bests(f.workouts, f.days, f.zone))
            val badges = Rewards.badges(f.workouts, f.days, weeks, f.firstDay, f.zone)
            val started = settings.rewardsStarted.first()
            dao.insertBadges(badges)
            if (!started) settings.startRewards(historyBadges = badges.isNotEmpty())
        }
    }
}
