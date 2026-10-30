package org.openstrap.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHeartRate(samples: List<HeartRateSample>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivity(minutes: List<ActivityMinute>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSleepSessions(sessions: List<SleepSession>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSleepStages(stages: List<SleepStage>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpo2(samples: List<Spo2Sample>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStress(samples: List<StressSample>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHrv(samples: List<HrvSample>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRestingHr(samples: List<RestingHeartRate>)

    /** For estimates (from PAI) that must not overwrite the strap's own daily resting HR. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRestingHrIfMissing(samples: List<RestingHeartRate>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkinTemperature(samples: List<SkinTemperature>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBreathRate(samples: List<BreathRate>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPai(samples: List<PaiSample>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHrvSdnn(samples: List<HrvSdnnSample>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkouts(workouts: List<Workout>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkoutHeartRate(samples: List<WorkoutHeartRate>)

    @Query("DELETE FROM workout_hr WHERE workoutStart = :start")
    suspend fun deleteWorkoutHeartRate(start: Long)

    @Query("DELETE FROM workout WHERE start = :start")
    suspend fun deleteWorkoutRow(start: Long)

    @Query("DELETE FROM recovery_hr WHERE workoutStart = :start")
    suspend fun deleteRecoveryHeartRate(start: Long)

    @Query("DELETE FROM sport_effect WHERE `end` = :end")
    suspend fun deleteSportEffect(end: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletedWorkouts(rows: List<DeletedWorkout>)

    /** Strap workouts deleted in OpenStrap, which a sync must not store again. */
    @Query("SELECT * FROM workout_deleted ORDER BY start")
    suspend fun deletedWorkoutsAll(): List<DeletedWorkout>

    /**
     * Deletes a strap workout: its row, its HR stream and recovery HR, and the sport effect that is only its own
     * ([org.openstrap.app.data.Workouts.ownEffect]). It is remembered in [DeletedWorkout] so the strap's next re-send
     * isn't stored, and its time as "Not a workout" so the same stretch isn't offered as a suggestion.
     */
    @androidx.room.Transaction
    suspend fun deleteStrapWorkout(start: Long, now: Long) {
        val w = workoutOnce(start) ?: return
        val until = start + org.openstrap.app.data.Workouts.EFFECT_WINDOW_S
        val effects = sportEffectsOnce(start, until)
        val others = workoutsOnce(start, until).map { it.start }.filter { it != start } + appWorkoutsOnce(start, until).map { it.from }
        val own = org.openstrap.app.data.Workouts.ownEffect(w, effects, others)
        val end = org.openstrap.app.data.Workouts.endOf(w, workoutHeartRateOnce(start).maxOfOrNull { it.timestamp }, own)
        deleteWorkoutHeartRate(start)
        deleteRecoveryHeartRate(start)
        own?.let { deleteSportEffect(it.end) }
        deleteWorkoutRow(start)
        insertDeletedWorkouts(listOf(DeletedWorkout(start, own?.end, now)))
        insertDismissedWorkouts(listOf(DismissedWorkout(start, end)))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSportEffects(rows: List<SportEffectRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrapReadiness(rows: List<StrapReadinessRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSleepSpo2(samples: List<SleepSpo2Sample>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOsaEvents(rows: List<OsaEventRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOsaResults(rows: List<OsaResultRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyMaxHr(rows: List<DailyMaxHr>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecoveryHeartRate(rows: List<RecoveryHeartRateRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivityExtended(minutes: List<ActivityExtendedMinute>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRawRecords(rows: List<RawRecord>)

    @Query("SELECT * FROM sleep_spo2 WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun sleepSpo2Once(from: Long, to: Long): List<SleepSpo2Sample>

    @Query("SELECT * FROM osa_result WHERE belongTime BETWEEN :from AND :to ORDER BY belongTime")
    suspend fun osaResultsOnce(from: Long, to: Long): List<OsaResultRow>

    @Query("SELECT * FROM daily_max_hr WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun dailyMaxHrOnce(from: Long, to: Long): List<DailyMaxHr>

    @Query("SELECT * FROM strap_readiness WHERE day = :day")
    fun strapReadiness(day: Long): Flow<StrapReadinessRow?>

    @Query("SELECT COUNT(*) FROM workout")
    suspend fun workoutCount(): Int

    /** How many strap workouts there are, as it changes: a delete changes it, so Load and charge are worked out again. */
    @Query("SELECT COUNT(*) FROM workout")
    fun workoutCountFlow(): Flow<Int>

    @Query("SELECT * FROM workout ORDER BY start DESC")
    fun workouts(): Flow<List<Workout>>

    @Query("SELECT * FROM workout WHERE `end` >= :from AND start <= :to ORDER BY start")
    suspend fun workoutsOnce(from: Long, to: Long): List<Workout>

    /** Strap workouts that overlap [from, to], as they change (for the stress exclusion). */
    @Query("SELECT * FROM workout WHERE `end` >= :from AND start <= :to ORDER BY start")
    fun workoutsIn(from: Long, to: Long): Flow<List<Workout>>

    @Query("SELECT * FROM workout WHERE start = :start")
    fun workout(start: Long): Flow<Workout?>

    @Query("SELECT * FROM workout WHERE start = :start")
    suspend fun workoutOnce(start: Long): Workout?

    @Query("SELECT * FROM workout_hr WHERE workoutStart = :start ORDER BY timestamp")
    fun workoutHeartRate(start: Long): Flow<List<WorkoutHeartRate>>

    @Query("SELECT * FROM workout_hr WHERE workoutStart = :start ORDER BY timestamp")
    suspend fun workoutHeartRateOnce(start: Long): List<WorkoutHeartRate>

    /** Last HR sample per workout: the strap records three minutes past the end, so this places the end. */
    @Query("SELECT workoutStart, MAX(timestamp) AS timestamp, 0 AS bpm FROM workout_hr GROUP BY workoutStart")
    fun lastWorkoutHeartRates(): Flow<List<WorkoutHeartRate>>

    /** Each strap workout's HR stream in brief, so the lists redo a workout's zones only when its stream changes. */
    @Query("SELECT workoutStart, MAX(timestamp) AS lastSample, COUNT(*) AS samples, SUM(bpm) AS bpmSum FROM workout_hr GROUP BY workoutStart")
    fun workoutHeartRateMarks(): Flow<List<HrStreamMark>>

    @Query("SELECT * FROM sport_effect ORDER BY `end`")
    fun sportEffects(): Flow<List<SportEffectRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppWorkout(workout: AppWorkout)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppWorkouts(workouts: List<AppWorkout>)

    /** Only a workout's effect figures, so working them out for an old workout can't undo a trim made meanwhile. */
    @Query("UPDATE app_workout SET epoc = :epoc, aerobicTrainingEffect = :aerobicTe, recoveryMinutes = :recoveryMinutes, vo2max = :vo2max, anaerobicTrainingEffect = :anaerobicTe, effectFromStrap = :fromStrap, ownRecoveryMinutes = :ownRecoveryMinutes WHERE start = :start")
    suspend fun updateAppWorkoutEffect(
        start: Long, epoc: Float?, aerobicTe: Float?, recoveryMinutes: Int?, vo2max: Float?, anaerobicTe: Float? = null, fromStrap: Boolean = false,
        ownRecoveryMinutes: Int? = null,
    )

    /** Only a workout's average and peak heart rate, for the strap's own record of it ([updateAppWorkoutEffect]). */
    @Query("UPDATE app_workout SET averageHeartRate = :average, maxHeartRate = :max WHERE start = :start")
    suspend fun updateAppWorkoutHeartRateStats(start: Long, average: Int?, max: Int?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppWorkoutHeartRate(samples: List<AppWorkoutHeartRate>)

    /** Gap filling: never over a live sample, and a second fill of the same minute changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAppWorkoutHeartRateIfMissing(samples: List<AppWorkoutHeartRate>)

    /** Finished phone workouts, newest first. */
    @Query("SELECT * FROM app_workout WHERE `end` IS NOT NULL ORDER BY start DESC")
    fun appWorkouts(): Flow<List<AppWorkout>>

    @Query("SELECT * FROM app_workout WHERE `end` IS NOT NULL ORDER BY start")
    suspend fun appWorkoutsAll(): List<AppWorkout>

    /** The workout still recording, if the app was stopped during one. */
    @Query("SELECT * FROM app_workout WHERE `end` IS NULL ORDER BY start DESC LIMIT 1")
    suspend fun activeAppWorkout(): AppWorkout?

    /** Phone workouts that overlap [from, to], the one recording included. */
    @Query("SELECT * FROM app_workout WHERE (`end` IS NULL OR `end` >= :from) AND start <= :to ORDER BY start")
    suspend fun appWorkoutsOnce(from: Long, to: Long): List<AppWorkout>

    /** Phone workouts that overlap [from, to], the one recording included, as they change (for the stress exclusion). */
    @Query("SELECT * FROM app_workout WHERE (`end` IS NULL OR `end` >= :from) AND start <= :to ORDER BY start")
    fun appWorkoutsIn(from: Long, to: Long): Flow<List<AppWorkout>>

    @Query("SELECT * FROM app_workout WHERE start = :start")
    fun appWorkout(start: Long): Flow<AppWorkout?>

    @Query("SELECT * FROM app_workout WHERE start = :start")
    suspend fun appWorkoutOnce(start: Long): AppWorkout?

    @Query("SELECT * FROM app_workout_hr WHERE workoutStart = :start ORDER BY timestamp")
    fun appWorkoutHeartRate(start: Long): Flow<List<AppWorkoutHeartRate>>

    @Query("SELECT * FROM app_workout_hr WHERE workoutStart = :start ORDER BY timestamp")
    suspend fun appWorkoutHeartRateOnce(start: Long): List<AppWorkoutHeartRate>

    /** Each phone workout's HR stream in brief, as [workoutHeartRateMarks]; gap filling adds rows, so it changes then too. */
    @Query("SELECT workoutStart, MAX(timestamp) AS lastSample, COUNT(*) AS samples, SUM(bpm) AS bpmSum FROM app_workout_hr GROUP BY workoutStart")
    fun appWorkoutHeartRateMarks(): Flow<List<HrStreamMark>>

    @Query("SELECT * FROM app_workout_hr ORDER BY workoutStart, timestamp")
    suspend fun appWorkoutHeartRateAll(): List<AppWorkoutHeartRate>

    @Query("DELETE FROM app_workout WHERE start = :start")
    suspend fun deleteAppWorkoutRow(start: Long)

    @Query("DELETE FROM app_workout_hr WHERE workoutStart = :start")
    suspend fun deleteAppWorkoutHeartRate(start: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSets(sets: List<AppWorkoutSet>)

    @Query("SELECT * FROM app_workout_set WHERE workoutStart = :start ORDER BY `index`")
    fun sets(start: Long): Flow<List<AppWorkoutSet>>

    @Query("SELECT * FROM app_workout_set WHERE workoutStart = :start ORDER BY `index`")
    suspend fun setsOnce(start: Long): List<AppWorkoutSet>

    @Query("SELECT * FROM app_workout_set ORDER BY workoutStart, `index`")
    suspend fun setsAll(): List<AppWorkoutSet>

    /** Finished sets per phone workout, for the workout lists. */
    @Query("SELECT workoutStart, COUNT(*) AS sets FROM app_workout_set WHERE `end` IS NOT NULL GROUP BY workoutStart")
    fun setCounts(): Flow<List<SetCount>>

    @Query("DELETE FROM app_workout_set WHERE workoutStart = :start")
    suspend fun deleteSets(start: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutePoints(points: List<AppWorkoutRoutePoint>)

    @Query("SELECT * FROM app_workout_route WHERE workoutStart = :start ORDER BY timestamp")
    fun route(start: Long): Flow<List<AppWorkoutRoutePoint>>

    @Query("SELECT * FROM app_workout_route WHERE workoutStart = :start ORDER BY timestamp")
    suspend fun routeOnce(start: Long): List<AppWorkoutRoutePoint>

    @Query("SELECT * FROM app_workout_route ORDER BY workoutStart, timestamp")
    suspend fun routeAll(): List<AppWorkoutRoutePoint>

    @Query("DELETE FROM app_workout_route WHERE workoutStart = :start")
    suspend fun deleteRoute(start: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDismissedWorkouts(rows: List<DismissedWorkout>)

    @Query("SELECT * FROM app_workout_dismissed WHERE `end` >= :from ORDER BY start")
    fun dismissedWorkouts(from: Long): Flow<List<DismissedWorkout>>

    @Query("SELECT * FROM app_workout_dismissed ORDER BY start")
    suspend fun dismissedWorkoutsAll(): List<DismissedWorkout>

    @androidx.room.Transaction
    suspend fun deleteAppWorkout(start: Long) {
        deleteSets(start)
        deleteRoute(start)
        deleteAppWorkoutHeartRate(start)
        deleteAppWorkoutRow(start)
    }

    @Query("SELECT * FROM sport_effect WHERE `end` BETWEEN :from AND :to ORDER BY `end`")
    suspend fun sportEffectsOnce(from: Long, to: Long): List<SportEffectRow>

    // Restores from Health Connect: only fill gaps, never replace what the strap synced (which carries more).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHeartRateIfMissing(samples: List<HeartRateSample>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivityIfMissing(minutes: List<ActivityMinute>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSleepSessionsIfMissing(sessions: List<SleepSession>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSleepStagesIfMissing(stages: List<SleepStage>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSpo2IfMissing(samples: List<Spo2Sample>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHrvIfMissing(samples: List<HrvSample>)

    // Whole tables, for the CSV backup.
    @Query("SELECT * FROM workout_hr ORDER BY workoutStart, timestamp")
    suspend fun workoutHeartRateAll(): List<WorkoutHeartRate>

    @Query("SELECT * FROM strap_readiness ORDER BY day")
    suspend fun strapReadinessAll(): List<StrapReadinessRow>

    @Query("SELECT * FROM recovery_hr ORDER BY workoutStart")
    suspend fun recoveryHeartRateAll(): List<RecoveryHeartRateRow>

    @Query("SELECT * FROM hrv_sdnn WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun hrvSdnn(from: Long, to: Long): Flow<List<HrvSdnnSample>>

    @Query("UPDATE activity_minute SET kind = kind & 15 WHERE kind > 15 AND kind != 126")
    suspend fun fixActivityModeNibble(): Int

    /** The strap re-sends a day's sleep as it updates it; drop the old version first. */
    @Query("DELETE FROM sleep_stage WHERE sessionStart IN (SELECT start FROM sleep_session WHERE day = :day AND nap = :nap)")
    suspend fun deleteSleepStagesForDay(day: Long, nap: Boolean)

    @Query("DELETE FROM sleep_session WHERE day = :day AND nap = :nap")
    suspend fun deleteSleepSessionsForDay(day: Long, nap: Boolean)

    @Query("SELECT * FROM sleep_edit")
    fun sleepEdits(): Flow<List<SleepEdit>>

    @Query("SELECT * FROM sleep_edit")
    suspend fun sleepEditsOnce(): List<SleepEdit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSleepEdit(edit: SleepEdit)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSleepEdits(edits: List<SleepEdit>)

    @Query("DELETE FROM sleep_edit WHERE strapStart = :strapStart")
    suspend fun deleteSleepEdit(strapStart: Long)

    /** Sessions the strap started in [from, to], for finding the one a delete removes for good. */
    @Query("SELECT * FROM sleep_session WHERE start BETWEEN :from AND :to ORDER BY start")
    suspend fun sleepSessionsStartingOnce(from: Long, to: Long): List<SleepSession>

    @Query("DELETE FROM sleep_stage WHERE sessionStart = :sessionStart")
    suspend fun deleteSleepStagesOf(sessionStart: Long)

    @Query("DELETE FROM sleep_session WHERE start = :start")
    suspend fun deleteSleepSession(start: Long)

    /**
     * Naps that overlap a main sleep. The strap can briefly report a stretch of broken night sleep as a nap,
     * then fold it into the main sleep; earlier builds kept that nap.
     */
    /**
     * Earlier builds ended each stage at its last minute instead of a minute later, leaving a minute's gap before the
     * next stage. Close those gaps; stages that already end where the next starts, or at the session end, are left.
     */
    @Query(
        "UPDATE sleep_stage SET `end` = `end` + 60 " +
            "WHERE NOT EXISTS (SELECT 1 FROM sleep_stage n WHERE n.sessionStart = sleep_stage.sessionStart AND n.start = sleep_stage.`end` AND n.start > sleep_stage.start) " +
            "AND (EXISTS (SELECT 1 FROM sleep_stage n WHERE n.sessionStart = sleep_stage.sessionStart AND n.start = sleep_stage.`end` + 60) " +
            "OR `end` + 60 = (SELECT s.`end` FROM sleep_session s WHERE s.start = sleep_stage.sessionStart))",
    )
    suspend fun closeSleepStageGaps(): Int

    @Query("DELETE FROM sleep_stage WHERE sessionStart IN (SELECT n.start FROM sleep_session n JOIN sleep_session m ON m.nap = 0 AND n.nap = 1 AND n.start < m.`end` AND n.`end` > m.start)")
    suspend fun deleteNapStagesInsideMainSleep(): Int

    @Query("DELETE FROM sleep_session WHERE nap = 1 AND EXISTS (SELECT 1 FROM sleep_session m WHERE m.nap = 0 AND sleep_session.start < m.`end` AND sleep_session.`end` > m.start)")
    suspend fun deleteNapsInsideMainSleep(): Int

    @Query("SELECT * FROM skin_temperature WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun skinTemperature(from: Long, to: Long): Flow<List<SkinTemperature>>

    @Query("SELECT * FROM skin_temperature WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun skinTemperatureOnce(from: Long, to: Long): List<SkinTemperature>

    @Query("SELECT * FROM breath_rate WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun breathRate(from: Long, to: Long): Flow<List<BreathRate>>

    @Query("SELECT * FROM breath_rate WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun breathRateOnce(from: Long, to: Long): List<BreathRate>

    @Query("SELECT * FROM pai WHERE timestamp <= :before ORDER BY timestamp DESC LIMIT 1")
    fun latestPai(before: Long): Flow<PaiSample?>

    @Query("SELECT * FROM pai WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun paiOnce(from: Long, to: Long): List<PaiSample>

    @Query("SELECT * FROM heart_rate WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun heartRate(from: Long, to: Long): Flow<List<HeartRateSample>>

    @Query("SELECT * FROM activity_minute WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun activity(from: Long, to: Long): Flow<List<ActivityMinute>>

    @Query("SELECT * FROM sleep_session WHERE `end` BETWEEN :from AND :to ORDER BY start")
    fun sleepSessions(from: Long, to: Long): Flow<List<SleepSession>>

    @Query("SELECT * FROM sleep_stage WHERE sessionStart = :sessionStart ORDER BY start")
    fun sleepStages(sessionStart: Long): Flow<List<SleepStage>>

    @Query("SELECT * FROM spo2 WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun spo2(from: Long, to: Long): Flow<List<Spo2Sample>>

    @Query("SELECT * FROM stress WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun stress(from: Long, to: Long): Flow<List<StressSample>>

    @Query("SELECT * FROM hrv WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun hrv(from: Long, to: Long): Flow<List<HrvSample>>

    @Query("SELECT * FROM resting_hr WHERE day BETWEEN :from AND :to ORDER BY day")
    fun restingHr(from: Long, to: Long): Flow<List<RestingHeartRate>>

    // One-shot reads for export.
    @Query("SELECT * FROM heart_rate WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun heartRateOnce(from: Long, to: Long): List<HeartRateSample>

    /** Rows written from 0xFF padding records by builds before the parser skipped them. */
    @Query("SELECT MIN(timestamp) FROM activity_minute WHERE steps = 255 AND intensity = 255 AND kind = 255")
    suspend fun firstPaddingActivity(): Long?

    @Query("DELETE FROM activity_minute WHERE steps = 255 AND intensity = 255 AND kind = 255")
    suspend fun deletePaddingActivity(): Int

    /** Rows dated outside [from, to]: stale buffer slots a strap sent with impossible times. */
    @Query("DELETE FROM activity_minute WHERE timestamp < :from OR timestamp > :to")
    suspend fun deleteActivityOutside(from: Long, to: Long): Int

    @Query("SELECT MAX(timestamp) FROM activity_minute WHERE timestamp <= :before")
    suspend fun latestActivity(before: Long): Long?

    @Query("SELECT * FROM activity_minute WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun activityOnce(from: Long, to: Long): List<ActivityMinute>

    @Query("SELECT * FROM sleep_session WHERE `end` BETWEEN :from AND :to ORDER BY start")
    suspend fun sleepSessionsOnce(from: Long, to: Long): List<SleepSession>

    @Query("SELECT * FROM sleep_stage WHERE sessionStart = :sessionStart ORDER BY start")
    suspend fun sleepStagesOnce(sessionStart: Long): List<SleepStage>

    @Query("SELECT * FROM spo2 WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun spo2Once(from: Long, to: Long): List<Spo2Sample>

    @Query("SELECT * FROM stress WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun stressOnce(from: Long, to: Long): List<StressSample>

    @Query("SELECT * FROM hrv WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun hrvOnce(from: Long, to: Long): List<HrvSample>

    @Query("SELECT * FROM resting_hr WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun restingHrOnce(from: Long, to: Long): List<RestingHeartRate>

    /**
     * Hours (epoch seconds / 3600) that hold something worth showing: heart rate, steps or the end of a
     * sleep. Hours rather than days so the caller can map them to local days in its own time zone.
     */
    @Query(
        "SELECT DISTINCT timestamp / 3600 FROM heart_rate " +
            "UNION SELECT DISTINCT timestamp / 3600 FROM activity_minute WHERE steps > 0 AND steps != 255 " +
            "UNION SELECT DISTINCT `end` / 3600 FROM sleep_session",
    )
    fun hoursWithData(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHealthAlerts(rows: List<HealthAlertRow>)

    @Query("SELECT * FROM health_alert WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun healthAlerts(from: Long, to: Long): Flow<List<HealthAlertRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWearEvent(row: WearEventRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWearEvents(rows: List<WearEventRow>)

    /** Wear changes in [from, to), led by the one in force at [from] if there is one. */
    @Query(
        "SELECT * FROM wear_event WHERE timestamp < :to AND " +
            "timestamp >= COALESCE((SELECT MAX(timestamp) FROM wear_event WHERE timestamp <= :from), :from) ORDER BY timestamp",
    )
    suspend fun wearEventsOnce(from: Long, to: Long): List<WearEventRow>

    @Query("SELECT * FROM wear_event ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastWearEvent(): WearEventRow?

    // Reads for data the dashboard didn't show before.
    @Query("SELECT * FROM sleep_spo2 WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun sleepSpo2(from: Long, to: Long): Flow<List<SleepSpo2Sample>>

    @Query("SELECT * FROM osa_result WHERE belongTime BETWEEN :from AND :to ORDER BY belongTime")
    fun osaResults(from: Long, to: Long): Flow<List<OsaResultRow>>

    @Query("SELECT * FROM osa_event WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun osaEvents(from: Long, to: Long): Flow<List<OsaEventRow>>

    @Query("SELECT * FROM daily_max_hr WHERE day BETWEEN :from AND :to ORDER BY day")
    fun dailyMaxHr(from: Long, to: Long): Flow<List<DailyMaxHr>>

    @Query("SELECT * FROM activity_extended WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    fun activityExtended(from: Long, to: Long): Flow<List<ActivityExtendedMinute>>

    /*
     * One row per local day (SQLite's 'localtime' follows the phone's zone, DST included), for week, month and
     * year views. Heart rate here is the heart_rate table only; the day chart also folds in per-minute activity HR.
     */
    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, AVG(bpm) AS value, MIN(bpm) AS min, MAX(bpm) AS max, COUNT(*) AS count FROM heart_rate WHERE timestamp BETWEEN :from AND :to AND bpm BETWEEN 30 AND 230 GROUP BY day ORDER BY day")
    fun dailyHeartRate(from: Long, to: Long): Flow<List<DailyAggregate>>

    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, SUM(steps) AS value, MIN(steps) AS min, MAX(steps) AS max, COUNT(*) AS count FROM activity_minute WHERE timestamp BETWEEN :from AND :to GROUP BY day ORDER BY day")
    fun dailySteps(from: Long, to: Long): Flow<List<DailyAggregate>>

    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, AVG(milliseconds) AS value, MIN(milliseconds) AS min, MAX(milliseconds) AS max, COUNT(*) AS count FROM hrv WHERE timestamp BETWEEN :from AND :to GROUP BY day ORDER BY day")
    fun dailyHrv(from: Long, to: Long): Flow<List<DailyAggregate>>

    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, AVG(milliseconds) AS value, MIN(milliseconds) AS min, MAX(milliseconds) AS max, COUNT(*) AS count FROM hrv_sdnn WHERE timestamp BETWEEN :from AND :to GROUP BY day ORDER BY day")
    fun dailyHrvSdnn(from: Long, to: Long): Flow<List<DailyAggregate>>

    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, AVG(percent) AS value, MIN(percent) AS min, MAX(percent) AS max, COUNT(*) AS count FROM spo2 WHERE timestamp BETWEEN :from AND :to GROUP BY day ORDER BY day")
    fun dailySpo2(from: Long, to: Long): Flow<List<DailyAggregate>>

    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, AVG(percent) AS value, MIN(percent) AS min, MAX(percent) AS max, COUNT(*) AS count FROM sleep_spo2 WHERE timestamp BETWEEN :from AND :to GROUP BY day ORDER BY day")
    fun dailySleepSpo2(from: Long, to: Long): Flow<List<DailyAggregate>>

    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, AVG(celsius) AS value, MIN(celsius) AS min, MAX(celsius) AS max, COUNT(*) AS count FROM skin_temperature WHERE timestamp BETWEEN :from AND :to GROUP BY day ORDER BY day")
    fun dailySkinTemperature(from: Long, to: Long): Flow<List<DailyAggregate>>

    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, AVG(perMinute) AS value, MIN(perMinute) AS min, MAX(perMinute) AS max, COUNT(*) AS count FROM breath_rate WHERE timestamp BETWEEN :from AND :to GROUP BY day ORDER BY day")
    fun dailyBreathRate(from: Long, to: Long): Flow<List<DailyAggregate>>

    /** Each day's last PAI row; SQLite takes the bare columns from the row that holds MAX(timestamp). */
    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, daily, total, MAX(timestamp) AS latest FROM pai WHERE timestamp BETWEEN :from AND :to GROUP BY day ORDER BY day")
    fun dailyPai(from: Long, to: Long): Flow<List<DailyPai>>

    @Query("SELECT MAX(timestamp) FROM heart_rate")
    fun latestHeartRateTime(): Flow<Long?>

    @Query("SELECT MAX(timestamp) FROM stress")
    fun latestStressTime(): Flow<Long?>

    @Query("SELECT MAX(`end`) FROM sleep_session")
    fun latestSleepEnd(): Flow<Long?>

    // Morning check-in, day tags and OpenStrap's own nightly inputs (evidence-review.md, implementation step 1).
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckIns(rows: List<CheckInRow>)

    @Query("DELETE FROM check_in WHERE day = :day")
    suspend fun deleteCheckIn(day: Long)

    @Query("SELECT * FROM check_in WHERE day = :day")
    fun checkIn(day: Long): Flow<CheckInRow?>

    @Query("SELECT * FROM check_in WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun checkInsOnce(from: Long, to: Long): List<CheckInRow>

    // Each Sunday's Habit Age estimate, for the next week's comparison.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHabitAgeWeek(row: HabitAgeWeekRow)

    @Query("SELECT * FROM habit_age_week WHERE asOf BETWEEN :from AND :to ORDER BY asOf")
    suspend fun habitAgeWeeksOnce(from: Long, to: Long): List<HabitAgeWeekRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayTags(rows: List<DayTagRow>)

    @Query("DELETE FROM day_tag WHERE day = :day AND tag = :tag")
    suspend fun deleteDayTag(day: Long, tag: String)

    @Query("SELECT * FROM day_tag WHERE day = :day ORDER BY tag")
    fun dayTags(day: Long): Flow<List<DayTagRow>>

    @Query("SELECT * FROM day_tag WHERE day BETWEEN :from AND :to ORDER BY day, tag")
    suspend fun dayTagsOnce(from: Long, to: Long): List<DayTagRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNightMetrics(rows: List<NightMetricsRow>)

    @Query("SELECT * FROM night_metrics WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun nightMetricsOnce(from: Long, to: Long): List<NightMetricsRow>

    // Each day's energy as last worked out, for the history views and the export.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyEnergy(rows: List<DailyEnergyRow>)

    @Query("SELECT * FROM daily_energy WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun dailyEnergyOnce(from: Long, to: Long): List<DailyEnergyRow>

    @Query("SELECT * FROM daily_energy WHERE day BETWEEN :from AND :to ORDER BY day")
    fun dailyEnergy(from: Long, to: Long): Flow<List<DailyEnergyRow>>

    @Query("SELECT day FROM daily_energy")
    suspend fun dailyEnergyDaysOnce(): List<Long>

    @Query("UPDATE app_workout SET activeKcal = :activeKcal, totalKcal = :totalKcal WHERE start = :start")
    suspend fun updateAppWorkoutKcal(start: Long, activeKcal: Float?, totalKcal: Float?)

    @Query("SELECT * FROM strap_readiness WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun strapReadinessOnce(from: Long, to: Long): List<StrapReadinessRow>

    // Healthspan reads six months at once, so these return only what it needs.

    /** Steps per local day, minutes over 250 steps left out as bad data. */
    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, SUM(steps) AS value, MIN(steps) AS min, MAX(steps) AS max, COUNT(*) AS count FROM activity_minute WHERE timestamp BETWEEN :from AND :to AND steps BETWEEN 0 AND 250 GROUP BY day ORDER BY day")
    suspend fun dailyStepsOnce(from: Long, to: Long): List<DailyAggregate>

    /**
     * Steps per local hour of each local day, for your usual steps by this time of day ([org.openstrap.app.data.UsualSteps]).
     * Minutes over 250 steps are left out as bad data, as in [dailyStepsOnce].
     */
    @Query("SELECT date(timestamp, 'unixepoch', 'localtime') AS day, CAST(strftime('%H', timestamp, 'unixepoch', 'localtime') AS INTEGER) AS hour, SUM(steps) AS steps FROM activity_minute WHERE timestamp BETWEEN :from AND :to AND steps BETWEEN 0 AND 250 GROUP BY day, hour ORDER BY day, hour")
    suspend fun hourlyStepsOnce(from: Long, to: Long): List<HourSteps>

    /** Minutes (epoch seconds / 60) with a heart rate from either table: the strap was worn. */
    @Query("SELECT timestamp / 60 FROM heart_rate WHERE timestamp BETWEEN :from AND :to AND bpm BETWEEN 30 AND 230 UNION SELECT timestamp / 60 FROM activity_minute WHERE timestamp BETWEEN :from AND :to AND heartRate BETWEEN 30 AND 230")
    suspend fun wornMinutesOnce(from: Long, to: Long): List<Long>

    /**
     * A cheap fingerprint of the data in a window: changes when a sync adds minutes, sleep or workouts to it, or when
     * any sleep is moved, deleted or restored (every edit changes its editedAt).
     */
    @Query("SELECT (SELECT COUNT(*) FROM activity_minute WHERE timestamp BETWEEN :from AND :to) + (SELECT COUNT(*) FROM heart_rate WHERE timestamp BETWEEN :from AND :to) * 7 + (SELECT COUNT(*) FROM sleep_session WHERE `end` BETWEEN :from AND :to) * 1009 + (SELECT COUNT(*) FROM workout WHERE start BETWEEN :from AND :to) * 100003 + (SELECT COUNT(*) + COALESCE(SUM(editedAt), 0) FROM sleep_edit) * 31")
    suspend fun dataFingerprint(from: Long, to: Long): Long
}
