package org.openstrap.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.presentation.Weeks

/**
 * Keeps the stored rewards ([Rewards]) in step with the workouts: works the weeks, bests and badges out again from
 * everything whenever the list of workouts or the weekly goal changes (a new workout, a trim, a delete), and stores
 * them. The first time, the badges past history earned wait to be shown once, all together.
 */
class RewardsKeeper(
    private val dao: HealthDao,
    private val settings: SettingsStore,
    private val maxHeartRates: MaxHeartRates = MaxHeartRates(dao, settings.profile),
) {
    /** Everything the rewards read: the workouts as [Rewards.Done], the days' steps, the weeks stored. */
    data class Facts(val workouts: List<Rewards.Done>, val days: List<Rewards.Day>, val firstDay: DayOfWeek, val zone: ZoneId)

    /** The weekly recap notification's tap, until the app root has opened the recap. */
    val openRecap = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** The locale's first day of the week (workouts spec 3.1, [Weeks]). */
    val firstDay: DayOfWeek get() = Weeks.firstDay()

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
        val zone = ZoneId.systemDefault()
        Facts(done, days(zone), firstDay, zone)
    }

    private class DaysRead(val stamp: org.openstrap.app.data.db.TableStamp, val zone: ZoneId, val at: Long, val days: List<Rewards.Day>)

    @Volatile private var daysRead: DaysRead? = null
    private val daysLock = kotlinx.coroutines.sync.Mutex()

    /**
     * One [refresh] at a time: each reads what is stored, works out what changed and writes it, so two at once (the
     * app root and the weekly recap) could each write against what the other was replacing, or both start the rewards.
     */
    private val refreshLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Steps per day, for the bests and badges. Summing every minute ever recorded each time took a scan of the whole
     * table, so the days are kept: when only newer minutes came since, just the last day kept and after are read
     * again (its minutes are the ones a sync rewrites). Anything else (older minutes in, some gone, another zone, or
     * [FULL_READ_S] gone by) reads them all.
     */
    private suspend fun days(zone: ZoneId): List<Rewards.Day> = daysLock.withLock {
        val stamp = dao.activityStampOnce()
        val now = System.currentTimeMillis() / 1000
        val kept = daysRead
        val last = kept?.stamp?.latest
        val onlyNewer = kept != null && last != null && kept.zone == zone && now - kept.at < FULL_READ_S &&
            stamp.rowCount == kept.stamp.rowCount + dao.activityCountAfterOnce(last)
        val days = if (onlyNewer) {
            val from = java.time.Instant.ofEpochSecond(last).atZone(zone).toLocalDate()
            kept.days.filter { it.date < from } + readDays(from.atStartOfDay(zone).toEpochSecond())
        } else readDays(0)
        daysRead = DaysRead(stamp, zone, if (onlyNewer) kept.at else now, days)
        days
    }

    private suspend fun readDays(from: Long) = dao.dailyStepsOnce(from, Long.MAX_VALUE / 2).mapNotNull { d ->
        runCatching { Rewards.Day(LocalDate.parse(d.day), d.value.toInt()) }.getOrNull()
    }

    /** The weeks and badges stored: past weeks' goals, and badges once earned. */
    suspend fun stored(): Pair<List<org.openstrap.app.data.db.WeekResult>, List<org.openstrap.app.data.db.BadgeRow>> =
        withContext(Dispatchers.IO) { dao.weekResultsOnce() to dao.badgesOnce() }

    /** Works everything out again from [workouts] against [goal] and stores it. */
    suspend fun refresh(workouts: List<WorkoutAnalysis>, goal: Int) = refreshLock.withLock {
        // The list starts empty before it loads: only a database with no workouts at all starts the rewards empty.
        if (workouts.isEmpty()) {
            if (!settings.rewardsStarted.first() && !dao.anyWorkoutOnce()) settings.startRewards(historyBadges = false)
            return@withLock
        }
        val f = facts(workouts)
        withContext(Dispatchers.Default) {
            val today = LocalDate.now(f.zone)
            val kept = dao.weekResultsOnce()
            val weeks = Rewards.weeks(f.workouts, kept, goal, today, f.firstDay, f.zone)
            val bests = Rewards.bests(f.workouts, f.days, f.zone)
            val badges = Rewards.badges(f.workouts, f.days, weeks, f.firstDay, f.zone)
            val started = settings.rewardsStarted.first()
            // Only what changed is written: every write sends the bests, badges and weeks to whatever shows them,
            // which works the rewards out again. Together, so a refresh cut short leaves none of it half done.
            val had = dao.badgesOnce().mapTo(HashSet()) { it.id }
            dao.storeRewards(
                weeks = weeks - kept.toSet(),
                bests = bests.takeIf { it.toSet() != dao.personalBestsOnce().toSet() },
                badges = badges.filter { it.id !in had },
            )
            if (!started) settings.startRewards(historyBadges = badges.isNotEmpty())
        }
    }

    /**
     * The merged list as [org.openstrap.app.ui.workout.WorkoutsViewModel.workouts] has it, read once: the strap's
     * workouts and the phone's ([Workouts.listed]) against the shared max heart rate ([MaxHeartRates]). Without the
     * list's zone seconds, which no reward reads.
     */
    suspend fun listedOnce(): List<WorkoutAnalysis> = withContext(Dispatchers.IO) {
        val maxHr = maxHeartRates.now().bpm
        Workouts.listed(
            dao.workouts().first(), dao.sportEffects().first(), dao.lastWorkoutHeartRates().first(), dao.appWorkouts().first(),
            dao.setCounts().first().associate { it.workoutStart to it.sets }, maxHr,
        )
    }

    /**
     * [refresh] without the screen: from [listedOnce] against the weekly goal stored, less the workout starting at
     * [recordingStart] (the one recording, as the app root leaves it out). The weekly recap runs it before it reads
     * the weeks, so workouts synced while the app was closed are counted.
     */
    suspend fun refreshNow(recordingStart: Long?) {
        refresh(listedOnce().filter { it.workout.start != recordingStart }, settings.goals.first().workoutsPerWeek)
    }

    private companion object {
        /** The longest the steps per day are kept before every minute is read again. */
        const val FULL_READ_S = 6 * 3600L
    }
}
