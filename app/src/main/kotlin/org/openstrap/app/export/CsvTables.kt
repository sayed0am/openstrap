package org.openstrap.app.export

import kotlinx.coroutines.flow.first
import org.openstrap.app.data.db.ActivityExtendedMinute
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.AppWorkoutRoutePoint
import org.openstrap.app.data.db.AppWorkoutSet
import org.openstrap.app.data.db.DismissedWorkout
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.BreathRate
import org.openstrap.app.data.db.CheckInRow
import org.openstrap.app.data.db.DailyEnergyRow
import org.openstrap.app.data.db.DailyMaxHr
import org.openstrap.app.data.db.DeletedWorkout
import org.openstrap.app.data.db.DayTagRow
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.HrvSdnnSample
import org.openstrap.app.data.db.NightMetricsRow
import org.openstrap.app.data.db.OsaEventRow
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.RecoveryHeartRateRow
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.data.db.SkinTemperature
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepSpo2Sample
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.data.db.Spo2Sample
import org.openstrap.app.data.db.StrapReadinessRow
import org.openstrap.app.data.db.StressSample
import org.openstrap.app.data.db.WearEventRow
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.db.WorkoutHeartRate
import java.time.Instant
import java.time.ZoneId

/** One CSV row, read by column name so files from older exports (fewer columns, other order) still import. */
class CsvRow(private val index: Map<String, Int>, private val cells: List<String>) {
    private fun cell(name: String): String? = index[name]?.let(cells::getOrNull)?.takeIf { it.isNotEmpty() }

    fun has(name: String) = cell(name) != null
    fun long(name: String): Long = cell(name)!!.toLong()
    fun longOrNull(name: String): Long? = cell(name)?.toLongOrNull()
    fun int(name: String): Int = cell(name)!!.toInt()
    fun intOrNull(name: String): Int? = cell(name)?.toIntOrNull()
    fun float(name: String): Float = cell(name)!!.toFloat()
    fun floatOrNull(name: String): Float? = cell(name)?.toFloatOrNull()
    fun bool(name: String): Boolean = cell(name)!!.toBooleanStrict()
    fun string(name: String): String = cell(name)!!
    /** An ISO-8601 time column, as epoch seconds. */
    fun instant(name: String): Long = Instant.parse(cell(name)!!).epochSecond
    fun bytes(name: String): ByteArray? = cell(name)?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
}

/**
 * Where a table's rows sit in time: the database table, the column that holds each row's time, and the CSV column
 * with that time in epoch seconds. A restore only writes rows from before the first day the phone has in the table. A
 * [span] table (sleep, workouts) also skips rows that overlap one on the phone, as a night can cross into that day.
 * A [parent] table's rows belong to a row of that table, keyed by its start, and come back only with it.
 */
class Key(val table: String, val column: String, val epoch: String, val span: Span? = null, val parent: String? = null)

/** A span table's end: the database column and the CSV column, in epoch seconds. */
class Span(val column: String, val epoch: String)

/**
 * One table in the CSV backup: its file, columns, how to read it from the database and write it back. Export and
 * import both go through these, so a column added here round-trips. Columns already in older exports keep their
 * names, so an old zip still imports; columns it lacks fall back to defaults.
 */
class CsvTable<T>(
    val file: String,
    val header: String,
    val key: Key,
    val read: suspend (HealthDao, Long, Long) -> List<T>,
    val row: (T) -> List<Any?>,
    val parse: (CsvRow) -> T,
    val write: suspend (HealthDao, List<T>) -> Unit,
)

private fun iso(epochSeconds: Long) = CsvExporter.iso(epochSeconds)
private fun hex(bytes: ByteArray?) = bytes?.joinToString("") { "%02x".format(it) }

/** Local midnight of the day a sleep belongs to, for rows from exports that didn't carry it: the day you woke up. */
private fun sleepDay(start: Long, end: Long, nap: Boolean): Long {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochSecond(if (nap) start else end).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
}

private fun stageCode(name: String) = when (name) {
    "awake" -> SleepStage.AWAKE; "light" -> SleepStage.LIGHT; "deep" -> SleepStage.DEEP; "rem" -> SleepStage.REM
    else -> name.toIntOrNull() ?: SleepStage.LIGHT
}

val CSV_TABLES: List<CsvTable<*>> = listOf(
    CsvTable("heart_rate.csv", "time,epoch,bpm,source",
        Key("heart_rate", "timestamp", "epoch"),
        { d, f, t -> d.heartRateOnce(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.bpm, it.source) },
        { HeartRateSample(it.long("epoch"), it.int("bpm"), it.intOrNull("source") ?: HeartRateSample.SOURCE_HISTORY) },
        { d, r -> d.insertHeartRate(r) }),
    CsvTable("activity_minute.csv", "time,epoch,steps,intensity,kind,heart_rate",
        Key("activity_minute", "timestamp", "epoch"),
        { d, f, t -> d.activityOnce(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.steps, it.intensity, it.kind, it.heartRate) },
        { ActivityMinute(it.long("epoch"), it.int("steps"), it.int("intensity"), it.int("kind"), it.intOrNull("heart_rate")) },
        { d, r -> d.insertActivity(r) }),
    CsvTable("activity_extended.csv", "time,epoch,floors,run,fat_burn,stand,walk,sit,steps,calories,distance_cm",
        Key("activity_extended", "timestamp", "epoch"),
        { d, f, t -> d.activityExtended(f, t).first() },
        { listOf(iso(it.timestamp), it.timestamp, it.floors, it.run, it.fatBurn, it.stand, it.walk, it.sit, it.steps, it.calories, it.distanceCm) },
        {
            ActivityExtendedMinute(it.long("epoch"), it.int("floors"), it.int("run"), it.int("fat_burn"), it.int("stand"),
                it.int("walk"), it.int("sit"), it.int("steps"), it.int("calories"), it.int("distance_cm"))
        },
        { d, r -> d.insertActivityExtended(r) }),
    CsvTable("sleep_session.csv", "start,end,start_epoch,end_epoch,nap,score,avg_hr,deep_min,light_min,rem_min,awake_min,wake_count,day_epoch",
        Key("sleep_session", "start", "start_epoch", span = Span("`end`", "end_epoch")),
        { d, f, t -> d.sleepSessionsOnce(f, t) },
        {
            listOf(iso(it.start), iso(it.end), it.start, it.end, it.nap, it.score, it.averageHeartRate,
                it.deepMinutes, it.lightMinutes, it.remMinutes, it.awakeMinutes, it.wakeCount, it.day)
        },
        {
            val start = it.long("start_epoch"); val end = it.long("end_epoch"); val nap = it.bool("nap")
            SleepSession(
                start, end, it.longOrNull("day_epoch")?.takeIf { d -> d > 0 } ?: sleepDay(start, end, nap), nap,
                it.intOrNull("score"), it.intOrNull("avg_hr"), it.intOrNull("deep_min"), it.intOrNull("light_min"),
                it.intOrNull("rem_min"), it.intOrNull("awake_min"), it.intOrNull("wake_count"),
            )
        },
        { d, r -> d.insertSleepSessions(r) }),
    CsvTable("sleep_stage.csv", "session_start_epoch,start,end,stage",
        Key("sleep_stage", "sessionStart", "session_start_epoch", parent = "sleep_session"),
        { d, f, t -> d.sleepSessionsOnce(f, t).flatMap { s -> d.sleepStagesOnce(s.start) } },
        { listOf(it.sessionStart, iso(it.start), iso(it.end), CsvExporter.stageName(it.stage)) },
        { SleepStage(it.instant("start"), it.instant("end"), it.long("session_start_epoch"), stageCode(it.string("stage"))) },
        { d, r -> d.insertSleepStages(r) }),
    CsvTable("spo2.csv", "time,epoch,percent",
        Key("spo2", "timestamp", "epoch"),
        { d, f, t -> d.spo2Once(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.percent) },
        { Spo2Sample(it.long("epoch"), it.int("percent")) }, { d, r -> d.insertSpo2(r) }),
    CsvTable("sleep_spo2.csv", "time,epoch,percent",
        Key("sleep_spo2", "timestamp", "epoch"),
        { d, f, t -> d.sleepSpo2Once(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.percent) },
        { SleepSpo2Sample(it.long("epoch"), it.int("percent")) }, { d, r -> d.insertSleepSpo2(r) }),
    CsvTable("stress.csv", "time,epoch,value",
        Key("stress", "timestamp", "epoch"),
        { d, f, t -> d.stressOnce(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.value) },
        { StressSample(it.long("epoch"), it.int("value")) }, { d, r -> d.insertStress(r) }),
    CsvTable("hrv.csv", "time,epoch,milliseconds",
        Key("hrv", "timestamp", "epoch"),
        { d, f, t -> d.hrvOnce(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.milliseconds) },
        { HrvSample(it.long("epoch"), it.int("milliseconds")) }, { d, r -> d.insertHrv(r) }),
    CsvTable("hrv_sdnn.csv", "time,epoch,milliseconds",
        Key("hrv_sdnn", "timestamp", "epoch"),
        { d, f, t -> d.hrvSdnn(f, t).first() }, { listOf(iso(it.timestamp), it.timestamp, it.milliseconds) },
        { HrvSdnnSample(it.long("epoch"), it.float("milliseconds")) }, { d, r -> d.insertHrvSdnn(r) }),
    CsvTable("resting_hr.csv", "day,epoch,bpm",
        Key("resting_hr", "day", "epoch"),
        { d, f, t -> d.restingHrOnce(f, t) }, { listOf(iso(it.day), it.day, it.bpm) },
        { RestingHeartRate(it.long("epoch"), it.int("bpm")) }, { d, r -> d.insertRestingHr(r) }),
    CsvTable("skin_temperature.csv", "time,epoch,celsius",
        Key("skin_temperature", "timestamp", "epoch"),
        { d, f, t -> d.skinTemperatureOnce(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.celsius) },
        { SkinTemperature(it.long("epoch"), it.float("celsius")) }, { d, r -> d.insertSkinTemperature(r) }),
    CsvTable("breath_rate.csv", "time,epoch,breaths_per_minute",
        Key("breath_rate", "timestamp", "epoch"),
        { d, f, t -> d.breathRateOnce(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.perMinute) },
        { BreathRate(it.long("epoch"), it.int("breaths_per_minute")) }, { d, r -> d.insertBreathRate(r) }),
    CsvTable("pai.csv", "time,epoch,daily,total,resting_hr,max_hr,tomorrow_total",
        Key("pai", "timestamp", "epoch"),
        { d, f, t -> d.paiOnce(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.daily, it.total, it.restingHeartRate, it.maxHeartRate, it.tomorrowTotal) },
        { PaiSample(it.long("epoch"), it.float("daily"), it.float("total"), it.int("resting_hr"), it.int("max_hr"), it.floatOrNull("tomorrow_total")) },
        { d, r -> d.insertPai(r) }),
    CsvTable("workout.csv", "start,start_epoch,end_epoch,sport_type,exercise_seconds,calories,avg_hr,max_hr,min_hr,aerobic_te,anaerobic_te,training_load,vo2max,steps,subtype",
        Key("workout", "start", "start_epoch", span = Span("`end`", "end_epoch")),
        { d, f, t -> d.workouts().first().filter { it.start in f..t }.sortedBy { it.start } },
        {
            listOf(iso(it.start), it.start, it.end, it.sportType, it.exerciseSeconds, it.calories, it.averageHeartRate, it.maxHeartRate,
                it.minHeartRate, it.aerobicTrainingEffect, it.anaerobicTrainingEffect, it.trainingLoad, it.vo2max, it.steps, it.subtype)
        },
        {
            Workout(it.long("start_epoch"), it.long("end_epoch"), it.int("sport_type"), it.intOrNull("exercise_seconds"),
                it.intOrNull("calories"), it.intOrNull("avg_hr"), it.intOrNull("max_hr"), it.intOrNull("min_hr"),
                it.floatOrNull("aerobic_te"), it.floatOrNull("anaerobic_te"), it.intOrNull("training_load"),
                it.intOrNull("vo2max"), it.intOrNull("steps"), it.intOrNull("subtype"))
        },
        { d, r -> d.insertWorkouts(r) }),
    CsvTable("workout_hr.csv", "workout_start_epoch,epoch,bpm",
        Key("workout_hr", "workoutStart", "workout_start_epoch", parent = "workout"),
        { d, f, t -> d.workoutHeartRateAll().filter { it.workoutStart in f..t } }, { listOf(it.workoutStart, it.timestamp, it.bpm) },
        { WorkoutHeartRate(it.long("workout_start_epoch"), it.long("epoch"), it.int("bpm")) }, { d, r -> d.insertWorkoutHeartRate(r) }),
    // Workouts recorded on the phone; one still recording is left out until it ends.
    CsvTable("app_workout.csv",
        "start,start_epoch,end_epoch,sport_type,source,pauses,end_reason,avg_hr,max_hr,ask_to_end,recorded_end_epoch,shown_start_epoch,target," +
            "use_gps,keep_route,distance_m,distance_from_steps,rest,sets_target,laps,epoc,aerobic_te,recovery_minutes,vo2max,active_kcal,total_kcal,target_reached_epoch,structure",
        Key("app_workout", "start", "start_epoch", span = Span("`end`", "end_epoch")),
        { d, f, t -> d.appWorkoutsAll().filter { it.start in f..t } },
        {
            listOf(iso(it.start), it.start, it.end, it.sportType, it.source, it.pauses, it.endReason, it.averageHeartRate, it.maxHeartRate,
                it.askToEnd, it.recordedEnd, it.shownStart, it.target, it.useGps, it.keepRoute, it.distanceMeters, it.distanceFromSteps,
                it.rest, it.setsTarget, it.laps, it.epoc, it.aerobicTrainingEffect, it.recoveryMinutes, it.vo2max, it.activeKcal, it.totalKcal, it.targetReachedAt, it.structure)
        },
        {
            AppWorkout(it.long("start_epoch"), it.long("end_epoch"), it.int("sport_type"), it.intOrNull("source") ?: AppWorkout.SOURCE_APP,
                if (it.has("pauses")) it.string("pauses") else "", null, it.intOrNull("end_reason"), it.intOrNull("avg_hr"), it.intOrNull("max_hr"),
                askToEnd = !it.has("ask_to_end") || it.bool("ask_to_end"), recordedEnd = it.longOrNull("recorded_end_epoch"),
                shownStart = it.longOrNull("shown_start_epoch"), target = if (it.has("target")) it.string("target") else null,
                useGps = it.has("use_gps") && it.bool("use_gps"), keepRoute = it.has("keep_route") && it.bool("keep_route"),
                distanceMeters = it.floatOrNull("distance_m"), distanceFromSteps = it.has("distance_from_steps") && it.bool("distance_from_steps"),
                rest = if (it.has("rest")) it.string("rest") else null, setsTarget = it.intOrNull("sets_target"),
                laps = if (it.has("laps")) it.string("laps") else "", epoc = it.floatOrNull("epoc"),
                aerobicTrainingEffect = it.floatOrNull("aerobic_te"), recoveryMinutes = it.intOrNull("recovery_minutes"), vo2max = it.floatOrNull("vo2max"),
                activeKcal = it.floatOrNull("active_kcal"), totalKcal = it.floatOrNull("total_kcal"),
                targetReachedAt = it.longOrNull("target_reached_epoch"), structure = if (it.has("structure")) it.string("structure") else null)
        },
        { d, r -> d.insertAppWorkouts(r) }),
    CsvTable("app_workout_hr.csv", "workout_start_epoch,epoch,bpm,from_history",
        Key("app_workout_hr", "workoutStart", "workout_start_epoch", parent = "app_workout"),
        { d, f, t -> d.appWorkoutHeartRateAll().filter { it.workoutStart in f..t } }, { listOf(it.workoutStart, it.timestamp, it.bpm, it.fromHistory) },
        { AppWorkoutHeartRate(it.long("workout_start_epoch"), it.long("epoch"), it.int("bpm"), it.has("from_history") && it.bool("from_history")) },
        { d, r -> d.insertAppWorkoutHeartRate(r) }),
    // Only routes the wearer chose to keep are ever stored, so only those are exported.
    CsvTable("app_workout_route.csv", "workout_start_epoch,epoch,lat,lon,accuracy_m,speed_ms,bearing_deg,altitude_m,speed_accuracy_ms,bearing_accuracy_deg",
        Key("app_workout_route", "workoutStart", "workout_start_epoch", parent = "app_workout"),
        { d, f, t -> d.routeAll().filter { it.workoutStart in f..t } },
        { listOf(it.workoutStart, it.timestamp, it.lat, it.lon, it.accuracy, it.speed, it.bearing, it.altitude, it.speedAccuracy, it.bearingAccuracy) },
        {
            AppWorkoutRoutePoint(it.long("workout_start_epoch"), it.long("epoch"), it.string("lat").toDouble(), it.string("lon").toDouble(), it.float("accuracy_m"),
                it.floatOrNull("speed_ms"), it.floatOrNull("bearing_deg"), if (it.has("altitude_m")) it.string("altitude_m").toDouble() else null,
                it.floatOrNull("speed_accuracy_ms"), it.floatOrNull("bearing_accuracy_deg"))
        },
        { d, r -> d.insertRoutePoints(r) }),
    CsvTable("app_workout_set.csv", "workout_start_epoch,set_index,start_epoch,end_epoch,rest_end_epoch,exercise,reps,weight_kg",
        Key("app_workout_set", "workoutStart", "workout_start_epoch", parent = "app_workout"),
        { d, f, t -> d.setsAll().filter { it.workoutStart in f..t } }, { listOf(it.workoutStart, it.index, it.start, it.end, it.restEnd, it.exercise, it.reps, it.weightKg) },
        {
            AppWorkoutSet(it.long("workout_start_epoch"), it.int("set_index"), it.long("start_epoch"), it.longOrNull("end_epoch"), it.longOrNull("rest_end_epoch"),
                if (it.has("exercise")) it.string("exercise") else null, it.intOrNull("reps"), it.floatOrNull("weight_kg"))
        },
        { d, r -> d.upsertSets(r) }),
    CsvTable("app_workout_dismissed.csv", "start_epoch,end_epoch",
        Key("app_workout_dismissed", "start", "start_epoch"),
        { d, f, t -> d.dismissedWorkoutsAll().filter { it.start in f..t } }, { listOf(it.start, it.end) },
        { DismissedWorkout(it.long("start_epoch"), it.long("end_epoch")) },
        { d, r -> d.insertDismissedWorkouts(r) }),
    // Strap workouts deleted in OpenStrap. The strap still holds them and re-sends them, so a restore without these
    // would bring them back. A restore also leaves out the workout rows and sport effects these name.
    CsvTable("workout_deleted.csv", "start_epoch,effect_end_epoch,deleted_epoch",
        Key("workout_deleted", "start", "start_epoch"),
        { d, f, t -> d.deletedWorkoutsAll().filter { it.start in f..t } }, { listOf(it.start, it.effectEnd, it.deletedAt) },
        { DeletedWorkout(it.long("start_epoch"), it.longOrNull("effect_end_epoch"), it.longOrNull("deleted_epoch") ?: it.long("start_epoch")) },
        { d, r -> d.insertDeletedWorkouts(r) }),
    CsvTable("sport_effect.csv", "end_epoch,recovery_minutes,vo2max,epoc,weekly_load,monthly_load,optimal_load_min,optimal_load_max,max_hr",
        Key("sport_effect", "`end`", "end_epoch"),
        { d, f, t -> d.sportEffects().first().filter { it.end in f..t } },
        {
            listOf(it.end, it.recoveryMinutes, it.vo2max, it.epoc, it.weeklyLoad, it.monthlyLoad, it.optimalLoadMin,
                it.optimalLoadMax, it.maximalHeartRate)
        },
        {
            SportEffectRow(it.long("end_epoch"), it.intOrNull("recovery_minutes"), it.floatOrNull("vo2max"), it.floatOrNull("epoc"),
                it.floatOrNull("weekly_load"), it.floatOrNull("monthly_load"), it.intOrNull("optimal_load_min"),
                it.intOrNull("optimal_load_max"), it.intOrNull("max_hr"))
        },
        { d, r -> d.insertSportEffects(r) }),
    CsvTable("recovery_hr.csv", "workout_start_epoch,samples_hex",
        Key("recovery_hr", "workoutStart", "workout_start_epoch", parent = "workout"),
        { d, f, t -> d.recoveryHeartRateAll().filter { it.workoutStart in f..t } }, { listOf(it.workoutStart, hex(it.samples)) },
        { RecoveryHeartRateRow(it.long("workout_start_epoch"), it.bytes("samples_hex") ?: ByteArray(0)) },
        { d, r -> d.insertRecoveryHeartRate(r) }),
    CsvTable("daily_max_hr.csv", "day,epoch,bpm",
        Key("daily_max_hr", "day", "epoch"),
        { d, f, t -> d.dailyMaxHr(f, t).first() }, { listOf(iso(it.day), it.day, it.bpm) },
        { DailyMaxHr(it.long("epoch"), it.int("bpm")) }, { d, r -> d.insertDailyMaxHr(r) }),
    CsvTable("strap_readiness.csv", "day_epoch,updated_epoch,score,hrv_score,resting_hr_score,skin_temperature_score,breathing_score,sleep_hrv,sleep_resting_hr,algorithm_version",
        Key("strap_readiness", "day", "day_epoch"),
        { d, f, t -> d.strapReadinessAll().filter { it.day in f..t } },
        {
            listOf(it.day, it.updatedAt, it.score, it.hrvScore, it.restingHrScore, it.skinTemperatureScore, it.breathingScore,
                it.sleepHrv, it.sleepRestingHr, it.algorithmVersion)
        },
        {
            StrapReadinessRow(it.long("day_epoch"), it.long("updated_epoch"), it.intOrNull("score"), it.intOrNull("hrv_score"),
                it.intOrNull("resting_hr_score"), it.intOrNull("skin_temperature_score"), it.intOrNull("breathing_score"),
                it.intOrNull("sleep_hrv"), it.intOrNull("sleep_resting_hr"), it.string("algorithm_version"))
        },
        { d, r -> d.insertStrapReadiness(r) }),
    CsvTable("osa_result.csv", "belong_epoch,valid_time,odi_raw,odi_score,disp_code,event_count,version",
        Key("osa_result", "belongTime", "belong_epoch"),
        { d, f, t -> d.osaResultsOnce(f, t) },
        { listOf(it.belongTime, it.validTime, it.odiRaw, it.odiScore, it.dispCode, it.eventCount, it.version) },
        {
            OsaResultRow(it.long("belong_epoch"), it.long("valid_time"), it.long("odi_raw"), it.int("odi_score"),
                it.int("disp_code"), it.int("event_count"), it.int("version"))
        },
        { d, r -> d.insertOsaResults(r) }),
    CsvTable("osa_event.csv", "time,epoch,drop,spo2_hex,heart_rate_hex",
        Key("osa_event", "timestamp", "epoch"),
        { d, f, t -> d.osaEvents(f, t).first() }, { listOf(iso(it.timestamp), it.timestamp, it.drop, hex(it.spo2), hex(it.heartRate)) },
        { OsaEventRow(it.long("epoch"), it.int("drop"), it.bytes("spo2_hex"), it.bytes("heart_rate_hex")) },
        { d, r -> d.insertOsaEvents(r) }),
    CsvTable("health_alert.csv", "time,epoch,type,value",
        Key("health_alert", "timestamp", "epoch"),
        { d, f, t -> d.healthAlerts(f, t).first() }, { listOf(iso(it.timestamp), it.timestamp, it.type, it.value) },
        { HealthAlertRow(it.long("epoch"), it.int("type"), it.long("value")) }, { d, r -> d.insertHealthAlerts(r) }),
    // The strap's on/off-body changes; worn is blank where the link went down and the state became unknown.
    CsvTable("wear_event.csv", "time,epoch,worn",
        Key("wear_event", "timestamp", "epoch"),
        { d, f, t -> d.wearEventsOnce(f, t + 1).filter { it.timestamp in f..t } }, { listOf(iso(it.timestamp), it.timestamp, it.worn) },
        { WearEventRow(it.long("epoch"), if (it.has("worn")) it.bool("worn") else null) }, { d, r -> d.insertWearEvents(r) }),
    // What the user entered: the morning check-in and day tags can't be synced again from the strap.
    CsvTable("check_in.csv", "day,day_epoch,feeling,updated_epoch",
        Key("check_in", "day", "day_epoch"),
        { d, f, t -> d.checkInsOnce(f, t) }, { listOf(iso(it.day), it.day, it.feeling, it.updatedAt) },
        { CheckInRow(it.long("day_epoch"), it.int("feeling"), it.longOrNull("updated_epoch") ?: it.long("day_epoch")) },
        { d, r -> d.insertCheckIns(r) }),
    CsvTable("day_tag.csv", "day,day_epoch,tag",
        Key("day_tag", "day", "day_epoch"),
        { d, f, t -> d.dayTagsOnce(f, t) }, { listOf(iso(it.day), it.day, it.tag) },
        { DayTagRow(it.long("day_epoch"), it.string("tag")) }, { d, r -> d.insertDayTags(r) }),
    CsvTable("night_metrics.csv", "day,day_epoch,updated_epoch,hrv_ln,hrv_samples,rhr_lowest30,rhr_mean_sleep,rhr_p10,hr_minutes,strap_hrv,strap_resting_hr",
        Key("night_metrics", "day", "day_epoch"),
        { d, f, t -> d.nightMetricsOnce(f, t) },
        {
            listOf(iso(it.day), it.day, it.updatedAt, it.hrvLn, it.hrvSamples, it.rhrLowest30, it.rhrMeanSleep, it.rhrP10, it.hrMinutes,
                it.strapHrv, it.strapRestingHr)
        },
        {
            NightMetricsRow(it.long("day_epoch"), it.long("updated_epoch"), it.floatOrNull("hrv_ln"), it.int("hrv_samples"),
                it.floatOrNull("rhr_lowest30"), it.floatOrNull("rhr_mean_sleep"), it.floatOrNull("rhr_p10"), it.int("hr_minutes"),
                it.intOrNull("strap_hrv"), it.intOrNull("strap_resting_hr"))
        },
        { d, r -> d.insertNightMetrics(r) }),
    // Worked out from the data above, but kept so a later change of model doesn't rewrite the history that was shown.
    CsvTable("daily_energy.csv", "day,day_epoch,updated_epoch,total_kcal,active_kcal,bmr_kcal,minutes_with_data,partial,workout_kcal,walking_kcal,hourly_active_kcal",
        Key("daily_energy", "day", "day_epoch"),
        { d, f, t -> d.dailyEnergyOnce(f, t) },
        {
            listOf(iso(it.day), it.day, it.updatedAt, it.totalKcal, it.activeKcal, it.bmrKcal, it.minutesWithData, it.partial,
                it.workoutKcal, it.walkingKcal, it.hourly)
        },
        {
            DailyEnergyRow(it.long("day_epoch"), it.long("updated_epoch"), it.float("total_kcal"), it.float("active_kcal"), it.float("bmr_kcal"),
                it.int("minutes_with_data"), it.bool("partial"), it.float("workout_kcal"), it.float("walking_kcal"),
                if (it.has("hourly_active_kcal")) it.string("hourly_active_kcal") else "")
        },
        { d, r -> d.insertDailyEnergy(r) }),
)
