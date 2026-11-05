package org.openstrap.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openstrap.app.data.db.ActivityExtendedMinute
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.DailyAggregate
import org.openstrap.app.data.db.DailyMaxHr
import org.openstrap.app.data.db.DailyPai
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.data.db.HrvSdnnSample
import org.openstrap.app.data.db.OsaEventRow
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.BreathRate
import org.openstrap.app.data.db.CheckInRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.data.db.SkinTemperature
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.Spo2Sample
import org.openstrap.app.data.db.StressSample
import org.openstrap.app.data.settings.UserProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/** A [from, to] window in epoch seconds covering one local calendar day. */
data class DayWindow(val from: Long, val to: Long) {
    companion object {
        fun of(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayWindow {
            return DayWindow(Days.start(date, zone), Days.end(date, zone) - 1)
        }
    }
}

class HealthRepository(
    private val dao: HealthDao,
    /** The clock Recovery is worked out at, in epoch seconds. Tests pass one they move by hand. */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val recoveryEngine = RecoveryEngine(dao)
    private val caloriesHistory = DailyCaloriesHistory(dao)
    private val healthspanEngine = HealthspanEngine(dao)
    /** The one reader of sleep for everything shown ([SleepSource]). */
    val sleep = SleepSource(dao)

    /**
     * A Recovery worked out, the data version it was worked out from, and the clock minute when the day's Charge run
     * still reached the present ([liveMinute]).
     */
    private class CachedRecovery(val version: Long, val minute: Long?, val result: RecoveryResult)

    /**
     * Recovery by day and profile, so Today and the widgets work a day out once per data version, not once each. The
     * profile is part of the key because age, weight and the sleep need change the result. The oldest goes past
     * [RECOVERY_CACHE_SIZE]. Only read and written under [recoveryLock].
     */
    private val recoveries = LinkedHashMap<Triple<LocalDate, UserProfile, ZoneId>, CachedRecovery>()
    private val recoveryLock = Mutex()

    /** Moves whenever a table Recovery reads changes, so a cached Recovery is never older than the data under it. */
    private val dataVersion = AtomicLong()

    /** How many times the inputs given to [start] have emitted, the first emission included. */
    @get:androidx.annotation.VisibleForTesting
    internal val recoveryVersion: Long get() = dataVersion.get()

    /** False until [start]: with nothing following the data, every Recovery is worked out afresh. */
    @Volatile private var caching = false

    /**
     * Follows [inputs] for as long as [scope] lives, so the Recovery cache knows when new data lands: a sync, a
     * restore, a sleep edit or a trim. [inputs] is [recoveryInputs] in the app, which watches every table Recovery
     * reads, not only those [changes] watches. Called once, from the app's container.
     */
    fun start(scope: CoroutineScope, inputs: Flow<*>) {
        caching = true
        scope.launch { inputs.collect { dataVersion.incrementAndGet() } }
    }

    /**
     * Readiness and charge for [date]; heavy, so call it off the main thread. Also logs the night's own inputs next to
     * the strap's ([NightMetricsRow]) for the calibration review, and keeps the day's calories ([DailyCaloriesHistory]).
     * Once [start]ed, a result is kept until the data changes, and a kept one writes nothing: its rows were written
     * when it was first worked out. While the day's Charge still runs up to the present, it is kept for the minute
     * only. A failure is never kept, so asking again works it out again.
     */
    suspend fun recovery(date: LocalDate, profile: UserProfile): RecoveryResult {
        val now = nowSeconds()
        if (!caching) return computeRecovery(date, profile, now)
        // The zone too: a day's moments move with it, so a result worked out before a flight is another day's after it.
        val key = Triple(date, profile, ZoneId.systemDefault())
        val version = dataVersion.get()
        val minute = liveMinute(date, now)
        return recoveryLock.withLock { recoveries[key]?.takeIf { it.version == version && it.minute == minute }?.result }
            ?: computeRecovery(date, profile, now).also { r ->
                recoveryLock.withLock {
                    recoveries.remove(key)
                    recoveries[key] = CachedRecovery(version, minute, r)
                    while (recoveries.size > RECOVERY_CACHE_SIZE) recoveries.remove(recoveries.keys.first())
                }
            }
    }

    /**
     * The clock minute at [now] when [date]'s Charge run ends at the present rather than at its lookahead
     * ([RecoveryEngine.LOOKAHEAD_SECONDS]): today, and yesterday until the lookahead has passed. Null for older days,
     * whose result changes only with the data.
     */
    private fun liveMinute(date: LocalDate, now: Long): Long? =
        (now / MINUTE_S).takeIf { now / MINUTE_S * MINUTE_S + MINUTE_S < DayWindow.of(date).to + 1 + RecoveryEngine.LOOKAHEAD_SECONDS }

    /**
     * Works [date] out. Its calories are [DailyCaloriesHistory.calories]'s, the rule its stored row follows after a sync too,
     * so the day's page and its bar in the week agree (data integrity item 18). Max HR and VO2max come from the
     * Recovery just worked out, which takes them by the same rules: max HR as of [date] is today's when [date] is today.
     */
    private suspend fun computeRecovery(date: LocalDate, profile: UserProfile, now: Long): RecoveryResult {
        val r = recoveryEngine.compute(date, profile, now = now)
        r.tonight?.let { logNight(it) }
        val today = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val maxHr = if (date == today) r.maxHr else recoveryEngine.maxHeartRate(profile, today)
        val calories = caloriesHistory.calories(date, profile, maxHr, r.vo2max?.value?.toFloat(), now = now)
        if (calories != null) {
            dao.insertDailyCalories(listOf(DailyCaloriesHistory.row(date, calories, System.currentTimeMillis() / 1000)))
        }
        return r.copy(calories = calories)
    }

    /** When you usually go to sleep as of [date] ([RecoveryResult.usualBedtime]), without working out Recovery. */
    suspend fun usualBedtime(date: LocalDate): java.time.LocalTime? = recoveryEngine.usualBedtime(date)

    /** The weekly Healthspan estimate shown on [date] (made the Sunday on or before it), or why it's withheld; heavy. */
    suspend fun healthspan(date: LocalDate, profile: UserProfile): Healthspan.Outcome =
        healthspanEngine.compute(date, profile)

    private suspend fun logNight(n: Recovery.Night) {
        // The strap's row for the strap's own night, which may be another day's when the night was moved.
        val strap = n.strapDay?.let { dao.strapReadinessOnce(it, it).firstOrNull() }
        dao.insertNightMetrics(listOf(NightMetrics.row(n, strap, System.currentTimeMillis() / 1000, ZoneId.systemDefault())))
    }

    /** The morning check-in for [date], 1-5, or null before the user answered. */
    fun checkIn(date: LocalDate): Flow<CheckInRow?> = dao.checkIn(date)

    /** Saves the check-in for [date]; null clears it. */
    suspend fun saveCheckIn(date: LocalDate, feeling: Int?) {
        if (feeling == null) dao.deleteCheckIn(date)
        else dao.insertCheckIns(listOf(CheckInRow(date, feeling.coerceIn(CheckIn.MIN, CheckIn.MAX), System.currentTimeMillis() / 1000)))
    }


    /**
     * Emits whenever new heart-rate, stress or sleep data lands, a strap workout comes or goes, a sleep's times are
     * changed, or a finished phone workout is saved, trimmed, ended earlier or deleted, so derived scores can refresh.
     * A phone workout moves Recovery, Load and Energy as much as a strap one (data integrity item 19).
     */
    fun changes(): Flow<Unit> = kotlinx.coroutines.flow.combine(
        dao.latestHeartRateTime(), dao.latestStressTime(), dao.latestSleepEnd(), dao.workoutCountFlow().distinctUntilChanged(),
        dao.sleepEdits().distinctUntilChanged(), dao.appWorkouts().distinctUntilChanged(),
    ) { }

    /**
     * Local days that have any heart rate, steps or sleep, for the calendar. Only the last [RECENT_DATA_DAYS] are
     * watched, where syncs write: a query over all of every table ran again on each sync batch. The older days are read
     * once, and again only when older data comes in ([HealthDao.earliestDataMark]), as a restore brings.
     */
    fun daysWithData(zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis() / 1000): Flow<Set<LocalDate>> {
        val split = (now / 3600 - RECENT_DATA_DAYS * 24L) * 3600
        val older = dao.earliestDataMark().distinctUntilChanged().map { dao.hoursWithDataOnce(Long.MIN_VALUE, split - 1) }
        return combine(older, dao.hoursWithData(split, Long.MAX_VALUE)) { old, recent ->
            (old + recent).mapTo(HashSet()) { Instant.ofEpochSecond(it * 3600).atZone(zone).toLocalDate() }
        }.distinctUntilChanged()
    }

    fun heartRate(w: DayWindow): Flow<List<HeartRateSample>> = dao.heartRate(w.from, w.to)
    fun activity(w: DayWindow): Flow<List<ActivityMinute>> = dao.activity(w.from, w.to)
    /** Sleep and naps as shown, ending in [w]: with the user's times, deleted ones left out ([SleepSource]). */
    fun sleepSessions(w: DayWindow): Flow<List<SleepSession>> = sleep.sessions(w.from, w.to)
    /** Each strap session ending in [w] with its edit, deleted ones too, for the screens that change them. */
    fun sleepResolved(w: DayWindow): Flow<List<SleepEdits.Resolved>> = sleep.resolved(w.from, w.to)
    /** [resolved] as [sleepSessions] shows them. */
    suspend fun sleepShown(resolved: List<SleepEdits.Resolved>): List<SleepSession> = sleep.shown(resolved)
    /** [r]'s stages as shown. */
    fun sleepStages(r: SleepEdits.Resolved): Flow<List<SleepStage>> = sleep.stages(r)
    /** The strap's own stages for its session [strap], unclipped. */
    fun strapStages(strap: SleepSession): Flow<List<SleepStage>> = sleep.strapStages(strap)
    fun sleepEdits(): Flow<List<org.openstrap.app.data.db.SleepEdit>> = dao.sleepEdits()

    /** Keeps [start] to [end] as the strap's session [strap]'s times; its own times again drop the edit. */
    suspend fun setSleepTimes(strap: SleepSession, start: Long, end: Long) {
        val current = sleep.editOf(strap)
        val e = SleepEdits.edit(strap, start, end, current, System.currentTimeMillis() / 1000)
        if (e == null) dao.deleteSleepEdit(current?.strapStart ?: strap.start) else dao.upsertSleepEdit(e)
    }

    /** Deletes the strap's session [strap], keeping any times the user gave it, so [restoreSleep] brings back exactly that. */
    suspend fun deleteSleep(strap: SleepSession) {
        val current = sleep.editOf(strap)
        dao.upsertSleepEdit(SleepEdits.delete(strap, current, System.currentTimeMillis() / 1000))
    }

    /** Brings back the strap's deleted session [strap], with the times it had: Undo and Restore. */
    suspend fun restoreSleep(strap: SleepSession) {
        val current = sleep.editOf(strap) ?: return
        val restored = SleepEdits.restore(current, System.currentTimeMillis() / 1000)
        if (restored == null) dao.deleteSleepEdit(current.strapStart) else dao.upsertSleepEdit(restored)
    }

    /** Sleep and naps deleted in the last [SleepEdits.KEEP_DAYS], newest first, for You, Your data, Deleted sleep. */
    fun deletedSleep(): Flow<List<SleepEdits.Resolved>> = sleep.deleted { System.currentTimeMillis() / 1000 }
    fun spo2(w: DayWindow): Flow<List<Spo2Sample>> = dao.spo2(w.from, w.to)
    /**
     * The day's stress readings that count as daily stress: those outside the exclusion around workouts
     * ([Recovery.stressExcluded]), so the chart shows a gap there and averages and highs leave them out. The raw
     * readings stay in the database and the export.
     */
    fun stress(w: DayWindow): Flow<List<StressSample>> =
        combine(dao.stress(w.from, w.to), stressExcluded(w.from, w.to)) { s, excluded -> s.filter { Recovery.stressCounts(it.timestamp, excluded) } }

    /** The stress exclusion for workouts touching [from]..[to]; a hard one that ended up to 90 minutes before [from] reaches in. */
    private fun stressExcluded(from: Long, to: Long): Flow<List<LongRange>> {
        val since = from - Recovery.STRESS_AFTER_VERY_HARD_WORKOUT_SECONDS
        return combine(dao.appWorkoutsIn(since, to), dao.workoutsIn(since, to)) { phone, strap ->
            val ranges = Recovery.workoutRanges(phone, strap, System.currentTimeMillis() / 1000)
            Recovery.stressExcluded(ranges, Recovery.stressTails(phone, strap))
        }
    }
    fun hrv(w: DayWindow): Flow<List<HrvSample>> = dao.hrv(w.from, w.to)
    fun restingHr(from: LocalDate, to: LocalDate): Flow<List<RestingHeartRate>> = dao.restingHr(from, to)
    fun skinTemperature(w: DayWindow): Flow<List<SkinTemperature>> = dao.skinTemperature(w.from, w.to)
    fun breathRate(w: DayWindow): Flow<List<BreathRate>> = dao.breathRate(w.from, w.to)
    fun latestPai(before: Long): Flow<PaiSample?> = dao.latestPai(before)

    fun hrvSdnn(w: DayWindow): Flow<List<HrvSdnnSample>> = dao.hrvSdnn(w.from, w.to)
    fun osaEvents(w: DayWindow): Flow<List<OsaEventRow>> = dao.osaEvents(w.from, w.to)
    fun activityExtended(w: DayWindow): Flow<List<ActivityExtendedMinute>> = dao.activityExtended(w.from, w.to)
    fun healthAlerts(w: DayWindow): Flow<List<HealthAlertRow>> = dao.healthAlerts(w.from, w.to)
    fun dailyMaxHr(date: LocalDate): Flow<DailyMaxHr?> = dao.dailyMaxHr(date, date).map { it.lastOrNull() }

    /**
     * The strap's sleep-apnea summary for the strap's night [strapDay] ([SleepHistory.strapDay]): its belongTime + 2 h
     * falls on that day.
     */
    fun osaResultOf(strapDay: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<OsaResultRow?> {
        val d = DayWindow.of(strapDay, zone)
        return dao.osaResults(d.from - 2 * 3600, d.to - 2 * 3600).map { it.lastOrNull() }
    }

    /** One value per local day from [from] to [to], for week, month and year views. Days without data are absent. */
    fun daily(metric: DailyMetric, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<List<DayStat>> {
        val a = DayWindow.of(from, zone).from
        val b = DayWindow.of(to, zone).to
        // The strap stores one resting HR per day already, keyed by its date.
        if (metric == DailyMetric.RestingHr) return dao.restingHr(from, to).map { rows ->
            rows.map { r ->
                val bpm = r.bpm.toFloat()
                DayStat(r.day, bpm, bpm, bpm, 1)
            }
        }
        // Stress leaves out readings in and just after workouts, so it's aggregated here rather than in SQL.
        if (metric == DailyMetric.Stress) return stress(DayWindow(a, b)).map { s ->
            s.groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }.toSortedMap().map { (d, v) ->
                val values = v.map { it.value.toFloat() }
                DayStat(d, values.average().toFloat(), values.min(), values.max(), values.size)
            }
        }
        val rows = when (metric) {
            DailyMetric.RestingHr, DailyMetric.Stress -> error("handled above")
            DailyMetric.HeartRate -> dao.dailyHeartRate(a, b)
            DailyMetric.Steps -> dao.dailySteps(a, b)
            DailyMetric.Hrv -> dao.dailyHrv(a, b)
            DailyMetric.HrvSdnn -> dao.dailyHrvSdnn(a, b)
            DailyMetric.Spo2 -> dao.dailySpo2(a, b)
            DailyMetric.SleepSpo2 -> dao.dailySleepSpo2(a, b)
            DailyMetric.SkinTemperature -> dao.dailySkinTemperature(a, b)
            DailyMetric.Breathing -> dao.dailyBreathRate(a, b)
        }
        return rows.map { list -> list.mapNotNull(DayStat::of) }
    }

    /**
     * Each local day's steps hour by hour, from [from] to [to], keyed by date; days without a step record are absent.
     * Read once, off the main thread, for your usual steps by this time of day.
     */
    suspend fun hourlySteps(from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, List<Float>> =
        dao.hourlyStepsOnce(DayWindow.of(from, zone).from, DayWindow.of(to, zone).to).groupBy { it.day }.mapNotNull { (day, hours) ->
            DayStat.parseDay(day)?.let { d ->
                val buckets = FloatArray(24)
                hours.forEach { if (it.hour in 0..23) buckets[it.hour] += it.steps.toFloat() }
                d to buckets.toList()
            }
        }.toMap()

    /** Each day's PAI (earned that day, and the 7-day total), keyed by local date. */
    fun dailyPai(from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<Map<LocalDate, DailyPai>> =
        dao.dailyPai(DayWindow.of(from, zone).from, DayWindow.of(to, zone).to).map { rows ->
            rows.mapNotNull { r -> DayStat.parseDay(r.day)?.let { it to r } }.toMap()
        }

    /** Each local day's steps from [from] to [to], as Steps' daily history counts them, keyed by date. */
    fun dailySteps(from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<Map<LocalDate, Int>> =
        dao.dailySteps(DayWindow.of(from, zone).from, DayWindow.of(to, zone).to).map { rows ->
            rows.mapNotNull { r -> DayStat.parseDay(r.day)?.let { it to r.value.toInt() } }.toMap()
        }

    /** The stored personal best of [kind] ([Rewards.Kind]) as it changes, null without one. */
    fun personalBest(kind: String): Flow<org.openstrap.app.data.db.PersonalBest?> =
        dao.personalBests().map { bests -> bests.firstOrNull { it.kind == kind } }

    /** The most steps of any day before [date], over all the history, read once, null without a day with steps. */
    suspend fun mostStepsBefore(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int? =
        dao.dailyStepsOnce(0, DayWindow.of(date, zone).from - 1).maxOfOrNull { it.value.toInt() }?.takeIf { it > 0 }

    /** Each day's stored calories from [from] to [to] ([DailyCaloriesHistory]), by local day, for Calories' ranges. */
    fun dailyCalories(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, org.openstrap.app.data.db.DailyCaloriesRow>> =
        dao.dailyCalories(from, to).map { rows -> rows.associateBy { it.day } }

    /**
     * The calorie goal the last [org.openstrap.app.data.settings.CalorieGoal.DAYS] suggest: from the active kcal of
     * their full days, today left out ([org.openstrap.app.data.settings.CalorieGoal.suggested]). Null under a week of them.
     */
    suspend fun suggestedCalorieGoal(today: LocalDate = LocalDate.now()): Int? {
        val days = org.openstrap.app.data.settings.CalorieGoal.DAYS.toLong()
        val rows = dao.dailyCaloriesOnce(today.minusDays(days), today.minusDays(1))
        return org.openstrap.app.data.settings.CalorieGoal.suggested(rows.filter { !it.partial }.map { it.activeKcal })
    }

    companion object {
        /** The days [daysWithData] watches as they change: well past how far back a strap's history sync reaches. */
        const val RECENT_DATA_DAYS = 14

        /** Recoveries kept: today, yesterday and a few days browsed. */
        const val RECOVERY_CACHE_SIZE = 8

        private const val MINUTE_S = 60L

        /**
         * Every table [RecoveryEngine.compute] reads, directly or through [SleepSource]. Not the two it writes
         * (`daily_energy` and `night_metrics`), or each compute would throw away its own result.
         */
        val RECOVERY_TABLES = arrayOf(
            "activity_minute", "heart_rate", "stress", "hrv", "skin_temperature", "breath_rate", "resting_hr", "pai",
            "spo2", "osa_result", "strap_readiness", "wear_event", "daily_max_hr", "workout", "workout_hr",
            "app_workout", "app_workout_hr", "sleep_session", "sleep_edit", "sleep_stage",
        )

        // Built once, at app start, so the spread's copy of the table names costs nothing.
        /** Emits once at first and again whenever one of [RECOVERY_TABLES] changes, for [start]. */
        @Suppress("SpreadOperator")
        fun recoveryInputs(database: OpenStrapDatabase): Flow<Unit> =
            database.invalidationTracker.createFlow(*RECOVERY_TABLES).map { }
    }
}

/** Sampled metrics that have a per-day series. */
enum class DailyMetric { HeartRate, RestingHr, Steps, Hrv, HrvSdnn, Spo2, SleepSpo2, Stress, SkinTemperature, Breathing }

/** One local day of a metric: the average (the total, for steps), lowest and highest sample, and how many samples. */
data class DayStat(val date: LocalDate, val value: Float, val min: Float, val max: Float, val count: Int) {
    companion object {
        fun parseDay(day: String): LocalDate? = runCatching { LocalDate.parse(day) }.getOrNull()

        fun of(row: DailyAggregate): DayStat? =
            parseDay(row.day)?.let { DayStat(it, row.value.toFloat(), row.min.toFloat(), row.max.toFloat(), row.count) }
    }
}
