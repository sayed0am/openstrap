package org.openstrap.app.export

import org.openstrap.app.data.DayKey
import org.openstrap.app.data.Days
import org.openstrap.app.data.db.ActivityExtendedMinute
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.AppWorkoutRound
import org.openstrap.app.data.db.AppWorkoutRoutePoint
import org.openstrap.app.data.db.BadgeRow
import org.openstrap.app.data.db.ExerciseRow
import org.openstrap.app.data.db.SavedWorkout
import org.openstrap.app.data.db.WeekResult
import org.openstrap.app.data.db.AppWorkoutSet
import org.openstrap.app.data.db.DismissedWorkout
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.BreathRate
import org.openstrap.app.data.db.CheckInRow
import org.openstrap.app.data.db.DailyCaloriesRow
import org.openstrap.app.data.db.DailyMaxHr
import org.openstrap.app.data.db.DeletedWorkout
import org.openstrap.app.data.db.DayTagRow
import org.openstrap.app.data.db.HabitAgeWeekRow
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.HrvSdnnSample
import org.openstrap.app.data.db.NightMetricsRow
import org.openstrap.app.data.db.OsaEventRow
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.PersonalBest
import org.openstrap.app.data.db.RecoveryHeartRateRow
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.data.db.SkinTemperature
import org.openstrap.app.data.db.SleepEdit
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * One CSV row, read by column name so files from older exports (fewer columns, other order) still import. [zone] is
 * where a day from an older backup, a local midnight, is read as a date ([date]).
 */
class CsvRow(private val index: Map<String, Int>, private val cells: List<String>, private val zone: ZoneId = ZoneId.systemDefault()) {
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

    /**
     * A day column: its `yyyy-MM-dd` [DayColumn.date] when the file has one, else its older local midnight
     * ([DayColumn.legacy]) as its date in the phone's zone now (user, 2026-11-03), as the migration reads the database's.
     */
    fun date(day: DayColumn): LocalDate =
        cell(day.date)?.let(LocalDate::parse) ?: DayKey.fromLegacy(long(day.legacy), zone)

    /** The older local midnight of a day column, for a row from a file without its date: what a merge compares. */
    fun legacy(day: DayColumn): Long? = if (has(day.date)) null else longOrNull(day.legacy)
}

/**
 * A day kept as a date ([DayKey]): the CSV column with its `yyyy-MM-dd` date, written since day keys became dates, and
 * the older column with its local midnight in epoch seconds, still written for the builds before, which read only that.
 */
class DayColumn(val date: String, val legacy: String)

/**
 * Where a table's rows sit in time and what keys them: the database table, the column that holds each row's time, and
 * the CSV column with that time in epoch seconds. A restore writes the rows whose key the phone has no row for. The key
 * is the time, or the text [unique] in its place, or the time with [part] beside it. A [span] table (sleep, workouts)
 * also leaves out rows that overlap one on the phone, as the strap can re-send one with another start. A [parent]
 * table's rows belong to a row of that table, keyed by its start, and come back only with it.
 */
class Key(
    val table: String, val column: String, val epoch: String,
    val span: Span? = null, val parent: String? = null, val unique: Unique? = null, val part: Unique? = null,
    /** For a table keyed by a day: its date, which keys it in place of [epoch] ([DayKey.of]). */
    val day: DayColumn? = null,
)

/** A key column read as text: the database column and the CSV column. */
class Unique(val column: String, val csv: String)

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
    /** Every row in [from, to], in the file's order. A [Paged] read also hands them over a page at a time. */
    val read: suspend (HealthDao, Long, Long) -> List<T>,
    val row: (T) -> List<Any?>,
    val parse: (CsvRow) -> T,
    val write: suspend (HealthDao, List<T>) -> Unit,
) {
    /** The rows [read] gives, in the same order, a page at a time when it's [Paged], else all in one. */
    suspend fun forEachPage(dao: HealthDao, from: Long, to: Long, block: suspend (List<T>) -> Unit) {
        val paged = read as? Paged<*>
        if (paged == null) block(read(dao, from, to))
        else paged.forEachPage(dao, from, to) {
            // A Paged read is the read of this table, so its rows are this table's.
            @Suppress("UNCHECKED_CAST")
            block(it as List<T>)
        }
    }
}

/** Rows a backup page holds: enough to keep the queries few, few enough that a year of minutes is never in memory. */
const val CSV_PAGE = 5000

/**
 * A table read a page at a time by keyset: [page] gives up to a page of rows after the last row of the one before
 * (null for the first), in the table's order. The backup writes each page as it comes, so the biggest tables (a row a
 * minute for years) are never held whole. Called as a plain read, it gathers every page.
 */
class Paged<T>(private val page: suspend (HealthDao, Long, Long, T?, Int) -> List<T>) : suspend (HealthDao, Long, Long) -> List<T> {
    suspend fun forEachPage(dao: HealthDao, from: Long, to: Long, size: Int = CSV_PAGE, block: suspend (List<T>) -> Unit) {
        var last: T? = null
        while (true) {
            val rows = page(dao, from, to, last, size)
            if (rows.isNotEmpty()) block(rows)
            if (rows.size < size) return
            last = rows.last()
        }
    }

    override suspend fun invoke(dao: HealthDao, from: Long, to: Long): List<T> = buildList { forEachPage(dao, from, to) { addAll(it) } }
}

/** A [Paged] read of a table keyed by its [time] alone, from [page] (rows after a time, up to a time, at most a limit). */
private fun <T> byTime(page: suspend HealthDao.(Long, Long, Int) -> List<T>, time: (T) -> Long) =
    Paged<T> { d, f, t, last, n -> d.page(last?.let(time) ?: (f - 1), t, n) }

/**
 * A [Paged] read of a workout's samples, keyed by the workout's [start] then the sample's [time], from [page] (the
 * workouts started in a range, after a start and time, at most a limit).
 */
private fun <T> byWorkout(page: suspend HealthDao.(Long, Long, Long, Long, Int) -> List<T>, start: (T) -> Long, time: (T) -> Long) =
    Paged<T> { d, f, t, last, n -> d.page(f, t, last?.let(start) ?: (f - 1), last?.let(time) ?: Long.MIN_VALUE, n) }

private fun iso(epochSeconds: Long) = CsvExporter.iso(epochSeconds)
private fun hex(bytes: ByteArray?) = bytes?.joinToString("") { "%02x".format(it) }

/** The strap's day for a sleep, for rows from exports that didn't carry it: the day you woke up. */
private fun sleepDay(start: Long, end: Long, nap: Boolean): Long =
    DayKey.of(Days.date(if (nap) start else end, ZoneId.systemDefault()))

/** [date]'s local midnight in the phone's zone, what the older day columns hold ([DayColumn.legacy]). */
private fun legacy(date: LocalDate) = DayKey.legacyMidnight(date, ZoneId.systemDefault())

/**
 * The epoch days whose local midnight in the phone's zone is in [from, to], as [legacyIn] picks a day table's rows, for
 * a table that keys a row by its epoch day. The ends are held to the years a date can have, as a whole backup's end is
 * far past them.
 */
private fun epochDaysIn(from: Long, to: Long): LongRange {
    val moments = DayKey.of(LocalDate.of(1, 1, 2))..DayKey.of(LocalDate.of(9999, 12, 30))
    val dates = DayKey.datesIn(from.coerceIn(moments), to.coerceIn(moments), ZoneId.systemDefault())
    return dates.start.toEpochDay()..dates.endInclusive.toEpochDay()
}

/** The rows of a day table whose older key ([legacy]) is in [from, to], as the read of the local midnights gave. */
private fun <T> List<T>.legacyIn(from: Long, to: Long, day: (T) -> LocalDate) = filter { legacy(day(it)) in from..to }

/** The strap's day of a sleep row from its file: the date, else the older local midnight, else [sleepDay]. */
private fun sleepDayOf(row: CsvRow, start: Long, end: Long, nap: Boolean): Long = when {
    row.has(SLEEP_DAY.date) -> DayKey.of(row.date(SLEEP_DAY))
    (row.longOrNull(SLEEP_DAY.legacy) ?: 0) > 0 -> DayKey.of(row.date(SLEEP_DAY))
    else -> sleepDay(start, end, nap)
}

/** The day columns of each day-keyed file. */
private val SLEEP_DAY = DayColumn("date", "day_epoch")
private val RESTING_DAY = DayColumn("date", "epoch")
private val MAX_HR_DAY = DayColumn("date", "epoch")
private val STRAP_DAY = DayColumn("date", "day_epoch")
private val CHECK_IN_DAY = DayColumn("date", "day_epoch")
private val TAG_DAY = DayColumn("date", "day_epoch")
private val NIGHT_DAY = DayColumn("date", "day_epoch")
private val HABIT_DAY = DayColumn("as_of_date", "as_of_epoch")
private val CALORIES_DAY = DayColumn("date", "day_epoch")

private fun stageCode(name: String) = when (name) {
    "awake" -> SleepStage.AWAKE; "light" -> SleepStage.LIGHT; "deep" -> SleepStage.DEEP; "rem" -> SleepStage.REM
    else -> name.toIntOrNull() ?: SleepStage.LIGHT
}

/**
 * The tables a backup carries: every table of user data. Left out: raw_record, records the app can't decode yet, kept
 * only to parse later and never read back, so a restore would add nothing anything shows.
 */
val CSV_TABLES: List<CsvTable<*>> = listOf(
    CsvTable("heart_rate.csv", "time,epoch,bpm,source",
        Key("heart_rate", "timestamp", "epoch"),
        byTime(HealthDao::heartRatePage) { it.timestamp }, { listOf(iso(it.timestamp), it.timestamp, it.bpm, it.source) },
        { HeartRateSample(it.long("epoch"), it.int("bpm"), it.intOrNull("source") ?: HeartRateSample.SOURCE_HISTORY) },
        { d, r -> d.insertHeartRate(r) }),
    CsvTable("activity_minute.csv", "time,epoch,steps,intensity,kind,heart_rate",
        Key("activity_minute", "timestamp", "epoch"),
        byTime(HealthDao::activityPage) { it.timestamp },
        { listOf(iso(it.timestamp), it.timestamp, it.steps, it.intensity, it.kind, it.heartRate) },
        { ActivityMinute(it.long("epoch"), it.int("steps"), it.int("intensity"), it.int("kind"), it.intOrNull("heart_rate")) },
        { d, r -> d.insertActivity(r) }),
    CsvTable("activity_extended.csv", "time,epoch,floors,run,fat_burn,stand,walk,sit,steps,calories,distance_cm",
        Key("activity_extended", "timestamp", "epoch"),
        byTime(HealthDao::activityExtendedPage) { it.timestamp },
        { listOf(iso(it.timestamp), it.timestamp, it.floors, it.run, it.fatBurn, it.stand, it.walk, it.sit, it.steps, it.calories, it.distanceCm) },
        {
            ActivityExtendedMinute(it.long("epoch"), it.int("floors"), it.int("run"), it.int("fat_burn"), it.int("stand"),
                it.int("walk"), it.int("sit"), it.int("steps"), it.int("calories"), it.int("distance_cm"))
        },
        { d, r -> d.insertActivityExtended(r) }),
    CsvTable("sleep_session.csv", "start,end,start_epoch,end_epoch,nap,score,avg_hr,deep_min,light_min,rem_min,awake_min,wake_count,day_epoch,date,source",
        Key("sleep_session", "start", "start_epoch", span = Span("`end`", "end_epoch")),
        { d, f, t -> d.sleepSessionsOnce(f, t) },
        {
            listOf(iso(it.start), iso(it.end), it.start, it.end, it.nap, it.score, it.averageHeartRate,
                it.deepMinutes, it.lightMinutes, it.remMinutes, it.awakeMinutes, it.wakeCount, it.strapDate?.let(::legacy) ?: 0,
                it.strapDate, it.source)
        },
        {
            val start = it.long("start_epoch"); val end = it.long("end_epoch"); val nap = it.bool("nap")
            SleepSession(
                start, end, sleepDayOf(it, start, end, nap), nap,
                it.intOrNull("score"), it.intOrNull("avg_hr"), it.intOrNull("deep_min"), it.intOrNull("light_min"),
                it.intOrNull("rem_min"), it.intOrNull("awake_min"), it.intOrNull("wake_count"),
                // Backups from before database 30 have no source: those are all the strap's.
                it.intOrNull("source") ?: SleepSession.FROM_STRAP,
            )
        },
        { d, r -> d.insertSleepSessions(r) }),
    CsvTable("sleep_stage.csv", "session_start_epoch,start,end,stage",
        Key("sleep_stage", "sessionStart", "session_start_epoch", parent = "sleep_session"),
        { d, f, t -> d.sleepStagesOfSessionsEndingOnce(f, t) },
        { listOf(it.sessionStart, iso(it.start), iso(it.end), CsvExporter.stageName(it.stage)) },
        { SleepStage(it.instant("start"), it.instant("end"), it.long("session_start_epoch"), stageCode(it.string("stage"))) },
        { d, r -> d.insertSleepStages(r) }),
    // The user's own times for a sleep or nap, and the ones they deleted. The strap re-sends its sessions, so a restore
    // without these would bring deleted ones back. The sessions above are the strap's, as synced.
    CsvTable("sleep_edit.csv", "strap_start_epoch,nap,start_epoch,end_epoch,deleted,edited_epoch",
        Key("sleep_edit", "strapStart", "strap_start_epoch"),
        { d, f, t -> d.sleepEditsStartingOnce(f, t) },
        { listOf(it.strapStart, it.nap, it.start, it.end, it.deleted, it.editedAt) },
        {
            SleepEdit(it.long("strap_start_epoch"), it.bool("nap"), it.longOrNull("start_epoch"), it.longOrNull("end_epoch"),
                it.bool("deleted"), it.longOrNull("edited_epoch") ?: 0)
        },
        { d, r -> d.insertSleepEdits(r) }),
    CsvTable("spo2.csv", "time,epoch,percent",
        Key("spo2", "timestamp", "epoch"),
        byTime(HealthDao::spo2Page) { it.timestamp }, { listOf(iso(it.timestamp), it.timestamp, it.percent) },
        { Spo2Sample(it.long("epoch"), it.int("percent")) }, { d, r -> d.insertSpo2(r) }),
    CsvTable("sleep_spo2.csv", "time,epoch,percent,seconds,high_hex,low_hex,quality_hex,extend_hex",
        Key("sleep_spo2", "timestamp", "epoch"),
        byTime(HealthDao::sleepSpo2Page) { it.timestamp },
        { listOf(iso(it.timestamp), it.timestamp, it.percent, it.time, hex(it.high), hex(it.low), hex(it.signalQuality), hex(it.extend)) },
        {
            SleepSpo2Sample(
                it.long("epoch"), it.int("percent"), it.intOrNull("seconds"),
                it.bytes("high_hex"), it.bytes("low_hex"), it.bytes("quality_hex"), it.bytes("extend_hex"),
            )
        },
        { d, r -> d.insertSleepSpo2(r) }),
    CsvTable("stress.csv", "time,epoch,value",
        Key("stress", "timestamp", "epoch"),
        byTime(HealthDao::stressPage) { it.timestamp }, { listOf(iso(it.timestamp), it.timestamp, it.value) },
        { StressSample(it.long("epoch"), it.int("value")) }, { d, r -> d.insertStress(r) }),
    CsvTable("hrv.csv", "time,epoch,milliseconds",
        Key("hrv", "timestamp", "epoch"),
        byTime(HealthDao::hrvPage) { it.timestamp }, { listOf(iso(it.timestamp), it.timestamp, it.milliseconds) },
        { HrvSample(it.long("epoch"), it.int("milliseconds")) }, { d, r -> d.insertHrv(r) }),
    CsvTable("hrv_sdnn.csv", "time,epoch,milliseconds",
        Key("hrv_sdnn", "timestamp", "epoch"),
        byTime(HealthDao::hrvSdnnPage) { it.timestamp }, { listOf(iso(it.timestamp), it.timestamp, it.milliseconds) },
        { HrvSdnnSample(it.long("epoch"), it.float("milliseconds")) }, { d, r -> d.insertHrvSdnn(r) }),
    CsvTable("resting_hr.csv", "day,epoch,bpm,date",
        Key("resting_hr", "day", "epoch", day = RESTING_DAY),
        { d, f, t -> d.restingHrAll().legacyIn(f, t) { it.day } }, { legacy(it.day).let { m -> listOf(iso(m), m, it.bpm, it.day) } },
        { RestingHeartRate(it.date(RESTING_DAY), it.int("bpm")) }, { d, r -> d.insertRestingHr(r) }),
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
        { d, f, t -> d.workoutsStartingOnce(f, t) },
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
        byWorkout(HealthDao::workoutHeartRatePage, { it.workoutStart }) { it.timestamp }, { listOf(it.workoutStart, it.timestamp, it.bpm) },
        { WorkoutHeartRate(it.long("workout_start_epoch"), it.long("epoch"), it.int("bpm")) }, { d, r -> d.insertWorkoutHeartRate(r) }),
    // Workouts recorded on the phone; one still recording is left out until it ends.
    CsvTable("app_workout.csv",
        "start,start_epoch,end_epoch,sport_type,source,pauses,end_reason,avg_hr,max_hr,ask_to_end,recorded_end_epoch,shown_start_epoch,target," +
            "use_gps,keep_route,distance_m,distance_from_steps,rest,sets_target,laps,epoc,aerobic_te,recovery_minutes,vo2max,active_kcal," +
            "total_kcal,target_reached_epoch,structure,stretches,anaerobic_te,effect_from_strap,own_recovery_minutes",
        Key("app_workout", "start", "start_epoch", span = Span("`end`", "end_epoch")),
        { d, f, t -> d.appWorkoutsStartingOnce(f, t) },
        {
            listOf(iso(it.start), it.start, it.end, it.sportType, it.source, it.pauses, it.endReason, it.averageHeartRate, it.maxHeartRate,
                it.askToEnd, it.recordedEnd, it.shownStart, it.target, it.useGps, it.keepRoute, it.distanceMeters, it.distanceFromSteps,
                it.rest, it.setsTarget, it.laps, it.epoc, it.aerobicTrainingEffect, it.recoveryMinutes, it.vo2max, it.activeKcal, it.totalKcal, it.targetReachedAt, it.structure, it.stretches,
                it.anaerobicTrainingEffect, it.effectFromStrap, it.ownRecoveryMinutes)
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
                targetReachedAt = it.longOrNull("target_reached_epoch"), structure = if (it.has("structure")) it.string("structure") else null,
                stretches = if (it.has("stretches")) it.string("stretches") else null,
                anaerobicTrainingEffect = it.floatOrNull("anaerobic_te"),
                effectFromStrap = it.has("effect_from_strap") && it.bool("effect_from_strap"),
                ownRecoveryMinutes = it.intOrNull("own_recovery_minutes"))
        },
        { d, r -> d.insertAppWorkouts(r) }),
    CsvTable("app_workout_hr.csv", "workout_start_epoch,epoch,bpm,from_history",
        Key("app_workout_hr", "workoutStart", "workout_start_epoch", parent = "app_workout"),
        byWorkout(HealthDao::appWorkoutHeartRatePage, { it.workoutStart }) { it.timestamp },
        { listOf(it.workoutStart, it.timestamp, it.bpm, it.fromHistory) },
        { AppWorkoutHeartRate(it.long("workout_start_epoch"), it.long("epoch"), it.int("bpm"), it.has("from_history") && it.bool("from_history")) },
        { d, r -> d.insertAppWorkoutHeartRate(r) }),
    // Only routes the wearer chose to keep are ever stored, so only those are exported.
    CsvTable("app_workout_route.csv", "workout_start_epoch,epoch,lat,lon,accuracy_m,speed_ms,bearing_deg,altitude_m,speed_accuracy_ms,bearing_accuracy_deg",
        Key("app_workout_route", "workoutStart", "workout_start_epoch", parent = "app_workout"),
        byWorkout(HealthDao::routePage, { it.workoutStart }) { it.timestamp },
        { listOf(it.workoutStart, it.timestamp, it.lat, it.lon, it.accuracy, it.speed, it.bearing, it.altitude, it.speedAccuracy, it.bearingAccuracy) },
        {
            AppWorkoutRoutePoint(it.long("workout_start_epoch"), it.long("epoch"), it.string("lat").toDouble(), it.string("lon").toDouble(), it.float("accuracy_m"),
                it.floatOrNull("speed_ms"), it.floatOrNull("bearing_deg"), if (it.has("altitude_m")) it.string("altitude_m").toDouble() else null,
                it.floatOrNull("speed_accuracy_ms"), it.floatOrNull("bearing_accuracy_deg"))
        },
        { d, r -> d.insertRoutePoints(r) }),
    CsvTable("app_workout_set.csv", "workout_start_epoch,set_index,start_epoch,end_epoch,rest_end_epoch,exercise,reps,weight_kg",
        Key("app_workout_set", "workoutStart", "workout_start_epoch", parent = "app_workout"),
        { d, f, t -> d.setsOfWorkoutsOnce(f, t) },
        { listOf(it.workoutStart, it.index, it.start, it.end, it.restEnd, it.exercise, it.reps, it.weightKg) },
        {
            AppWorkoutSet(it.long("workout_start_epoch"), it.int("set_index"), it.long("start_epoch"), it.longOrNull("end_epoch"), it.longOrNull("rest_end_epoch"),
                if (it.has("exercise")) it.string("exercise") else null, it.intOrNull("reps"), it.floatOrNull("weight_kg"))
        },
        { d, r -> d.upsertSets(r) }),
    CsvTable("app_workout_round.csv", "workout_start_epoch,round_index,work_start_epoch,work_end_epoch,rest_end_epoch,peak_bpm",
        Key("app_workout_round", "workoutStart", "workout_start_epoch", parent = "app_workout"),
        { d, f, t -> d.roundsOfWorkoutsOnce(f, t) },
        { listOf(it.workoutStart, it.index, it.workStart, it.workEnd, it.restEnd, it.peakBpm) },
        {
            AppWorkoutRound(it.long("workout_start_epoch"), it.int("round_index"), it.long("work_start_epoch"), it.longOrNull("work_end_epoch"),
                it.longOrNull("rest_end_epoch"), it.intOrNull("peak_bpm"))
        },
        { d, r -> d.upsertRounds(r) }),
    // What the user named and saved, which no history can bring back.
    CsvTable("saved_workout.csv", "created_epoch,name,sport_type,structure,goal",
        Key("saved_workout", "createdAt", "created_epoch"),
        { d, f, t -> d.savedWorkoutsCreatedOnce(f, t) },
        { listOf(it.createdAt, it.name, it.sport, it.structure, it.goal) },
        {
            SavedWorkout(name = it.string("name"), sport = it.int("sport_type"), structure = it.string("structure"),
                goal = if (it.has("goal")) it.string("goal") else null, createdAt = it.long("created_epoch"))
        },
        { d, r -> d.insertSavedWorkouts(r) }),
    CsvTable("exercise.csv", "used_epoch,name,last_reps,last_kg",
        Key("exercise", "usedAt", "used_epoch", unique = Unique("name", "name")),
        { d, f, t -> d.exercisesUsedOnce(f, t) },
        { listOf(it.usedAt, it.name, it.lastReps, it.lastKg) },
        { ExerciseRow(it.string("name"), it.intOrNull("last_reps"), it.floatOrNull("last_kg"), it.long("used_epoch")) },
        { d, r -> d.insertExercises(r) }),
    // Badges are never taken back and each past week keeps the goal it had (Rewards): neither can be worked out again.
    CsvTable("badge.csv", "earned_epoch,id,workout_start_epoch,day",
        Key("badge", "earnedAt", "earned_epoch", unique = Unique("id", "id")),
        { d, f, t -> d.badgesEarnedOnce(f, t) },
        { listOf(it.earnedAt, it.id, it.workoutStart, it.day) },
        { BadgeRow(it.string("id"), it.long("earned_epoch"), it.longOrNull("workout_start_epoch"), it.longOrNull("day")) },
        { d, r -> d.insertBadges(r) }),
    CsvTable("week_result.csv", "week_start_day,goal,count,met",
        Key("week_result", "weekStart", "week_start_day"),
        { d, f, t -> epochDaysIn(f, t).let { d.weekResultsStartingOnce(it.first, it.last) } },
        { listOf(it.weekStart, it.goal, it.count, it.met) },
        { WeekResult(it.long("week_start_day"), it.int("goal"), it.int("count"), it.bool("met")) },
        { d, r -> d.upsertWeekResults(r) }),
    // Bests by kind. A rewards refresh works them out again from the workouts above, so these mostly show what a backup had.
    CsvTable("personal_best.csv", "kind,value,date,workout_start_epoch",
        Key("personal_best", "date", "date", unique = Unique("kind", "kind")),
        { d, f, t -> d.personalBestsSetOnce(f, t) },
        { listOf(it.kind, it.value, it.date, it.workoutStart) },
        { PersonalBest(it.string("kind"), it.string("value").toDouble(), it.long("date"), it.longOrNull("workout_start_epoch")) },
        { d, r -> d.insertPersonalBests(r) }),
    CsvTable("app_workout_dismissed.csv", "start_epoch,end_epoch",
        Key("app_workout_dismissed", "start", "start_epoch"),
        { d, f, t -> d.dismissedWorkoutsStartingOnce(f, t) }, { listOf(it.start, it.end) },
        { DismissedWorkout(it.long("start_epoch"), it.long("end_epoch")) },
        { d, r -> d.insertDismissedWorkouts(r) }),
    // Strap workouts deleted in OpenStrap. The strap still holds them and re-sends them, so a restore without these
    // would bring them back. A restore also leaves out the workout rows and sport effects these name.
    CsvTable("workout_deleted.csv", "start_epoch,effect_end_epoch,deleted_epoch",
        Key("workout_deleted", "start", "start_epoch"),
        { d, f, t -> d.deletedWorkoutsStartingOnce(f, t) }, { listOf(it.start, it.effectEnd, it.deletedAt) },
        { DeletedWorkout(it.long("start_epoch"), it.longOrNull("effect_end_epoch"), it.longOrNull("deleted_epoch") ?: it.long("start_epoch")) },
        { d, r -> d.insertDeletedWorkouts(r) }),
    CsvTable("sport_effect.csv", "end_epoch,recovery_minutes,vo2max,epoc,weekly_load,monthly_load,optimal_load_min,optimal_load_max,max_hr",
        Key("sport_effect", "`end`", "end_epoch"),
        { d, f, t -> d.sportEffectsOnce(f, t) },
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
        { d, f, t -> d.recoveryHeartRateOfWorkoutsOnce(f, t) }, { listOf(it.workoutStart, hex(it.samples)) },
        { RecoveryHeartRateRow(it.long("workout_start_epoch"), it.bytes("samples_hex") ?: ByteArray(0)) },
        { d, r -> d.insertRecoveryHeartRate(r) }),
    CsvTable("daily_max_hr.csv", "day,epoch,bpm,date",
        Key("daily_max_hr", "day", "epoch", day = MAX_HR_DAY),
        { d, f, t -> d.dailyMaxHrAll().legacyIn(f, t) { it.day } }, { legacy(it.day).let { m -> listOf(iso(m), m, it.bpm, it.day) } },
        { DailyMaxHr(it.date(MAX_HR_DAY), it.int("bpm")) }, { d, r -> d.insertDailyMaxHr(r) }),
    CsvTable("strap_readiness.csv", "day_epoch,updated_epoch,score,hrv_score,resting_hr_score,skin_temperature_score,breathing_score,sleep_hrv,sleep_resting_hr,algorithm_version,date",
        Key("strap_readiness", "day", "day_epoch", day = STRAP_DAY),
        { d, f, t -> d.strapReadinessAll().legacyIn(f, t) { it.day } },
        {
            listOf(legacy(it.day), it.updatedAt, it.score, it.hrvScore, it.restingHrScore, it.skinTemperatureScore, it.breathingScore,
                it.sleepHrv, it.sleepRestingHr, it.algorithmVersion, it.day)
        },
        {
            StrapReadinessRow(it.date(STRAP_DAY), it.long("updated_epoch"), it.intOrNull("score"), it.intOrNull("hrv_score"),
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
        { d, f, t -> d.osaEventsOnce(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.drop, hex(it.spo2), hex(it.heartRate)) },
        { OsaEventRow(it.long("epoch"), it.int("drop"), it.bytes("spo2_hex"), it.bytes("heart_rate_hex")) },
        { d, r -> d.insertOsaEvents(r) }),
    CsvTable("health_alert.csv", "time,epoch,type,value",
        Key("health_alert", "timestamp", "epoch", part = Unique("type", "type")),
        { d, f, t -> d.healthAlertsOnce(f, t) }, { listOf(iso(it.timestamp), it.timestamp, it.type, it.value) },
        { HealthAlertRow(it.long("epoch"), it.int("type"), it.long("value")) }, { d, r -> d.insertHealthAlerts(r) }),
    // The strap's on/off-body changes; worn is blank where the link went down and the state became unknown.
    CsvTable("wear_event.csv", "time,epoch,worn",
        Key("wear_event", "timestamp", "epoch"),
        { d, f, t -> d.wearEventsOnce(f, t + 1).filter { it.timestamp in f..t } }, { listOf(iso(it.timestamp), it.timestamp, it.worn) },
        { WearEventRow(it.long("epoch"), if (it.has("worn")) it.bool("worn") else null) }, { d, r -> d.insertWearEvents(r) }),
    // What the user entered: the morning check-in and day tags can't be synced again from the strap.
    CsvTable("check_in.csv", "day,day_epoch,feeling,updated_epoch,date",
        Key("check_in", "day", "day_epoch", day = CHECK_IN_DAY),
        { d, f, t -> d.checkInsAll().legacyIn(f, t) { it.day } },
        { legacy(it.day).let { m -> listOf(iso(m), m, it.feeling, it.updatedAt, it.day) } },
        {
            CheckInRow(it.date(CHECK_IN_DAY), it.int("feeling"),
                it.longOrNull("updated_epoch") ?: it.longOrNull("day_epoch") ?: DayKey.of(it.date(CHECK_IN_DAY)))
        },
        { d, r -> d.insertCheckIns(r) }),
    CsvTable("day_tag.csv", "day,day_epoch,tag,date",
        Key("day_tag", "day", "day_epoch", part = Unique("tag", "tag"), day = TAG_DAY),
        { d, f, t -> d.dayTagsAll().legacyIn(f, t) { it.day } }, { legacy(it.day).let { m -> listOf(iso(m), m, it.tag, it.day) } },
        { DayTagRow(it.date(TAG_DAY), it.string("tag")) }, { d, r -> d.insertDayTags(r) }),
    CsvTable("night_metrics.csv", "day,day_epoch,updated_epoch,hrv_ln,hrv_samples,rhr_lowest30,rhr_mean_sleep,rhr_p10,hr_minutes,strap_hrv,strap_resting_hr,date",
        Key("night_metrics", "day", "day_epoch", day = NIGHT_DAY),
        { d, f, t -> d.nightMetricsAll().legacyIn(f, t) { it.day } },
        {
            val m = legacy(it.day)
            listOf(iso(m), m, it.updatedAt, it.hrvLn, it.hrvSamples, it.rhrLowest30, it.rhrMeanSleep, it.rhrP10, it.hrMinutes,
                it.strapHrv, it.strapRestingHr, it.day)
        },
        {
            NightMetricsRow(it.date(NIGHT_DAY), it.long("updated_epoch"), it.floatOrNull("hrv_ln"), it.int("hrv_samples"),
                it.floatOrNull("rhr_lowest30"), it.floatOrNull("rhr_mean_sleep"), it.floatOrNull("rhr_p10"), it.int("hr_minutes"),
                it.intOrNull("strap_hrv"), it.intOrNull("strap_resting_hr"))
        },
        { d, r -> d.insertNightMetrics(r) }),
    // Each Sunday's Habit Age as it was shown: only the current week is ever worked out, so a past one can't come back.
    CsvTable("habit_age_week.csv", "as_of,as_of_epoch,estimate,low,high,updated_epoch,as_of_date",
        Key("habit_age_week", "asOf", "as_of_epoch", day = HABIT_DAY),
        { d, f, t -> d.habitAgeWeeksAll().legacyIn(f, t) { it.asOf } },
        { legacy(it.asOf).let { m -> listOf(iso(m), m, it.estimate, it.low, it.high, it.updatedAt, it.asOf) } },
        { HabitAgeWeekRow(it.date(HABIT_DAY), it.float("estimate"), it.int("low"), it.int("high"), it.long("updated_epoch")) },
        { d, r -> r.forEach { row -> d.insertHabitAgeWeek(row) } }),
    // Worked out from the data above, but kept so a later change of model doesn't rewrite the history that was shown.
    CsvTable("daily_energy.csv", "day,day_epoch,updated_epoch,total_kcal,active_kcal,bmr_kcal,minutes_with_data,partial,workout_kcal,walking_kcal,hourly_active_kcal,date",
        Key("daily_energy", "day", "day_epoch", day = CALORIES_DAY),
        { d, f, t -> d.dailyCaloriesAll().legacyIn(f, t) { it.day } },
        {
            val m = legacy(it.day)
            listOf(iso(m), m, it.updatedAt, it.totalKcal, it.activeKcal, it.bmrKcal, it.minutesWithData, it.partial,
                it.workoutKcal, it.walkingKcal, it.hourly, it.day)
        },
        {
            DailyCaloriesRow(
                it.date(CALORIES_DAY), it.long("updated_epoch"), it.float("total_kcal"), it.float("active_kcal"), it.float("bmr_kcal"),
                it.int("minutes_with_data"), it.bool("partial"), it.float("workout_kcal"), it.float("walking_kcal"),
                if (it.has("hourly_active_kcal")) it.string("hourly_active_kcal") else "")
        },
        { d, r -> d.insertDailyCalories(r) }),
)
