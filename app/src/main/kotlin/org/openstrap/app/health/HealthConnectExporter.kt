package org.openstrap.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.Spo2Sample
import java.time.Duration
import kotlin.reflect.KClass
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Percentage
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.SleepStage
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pushes locally stored data into Android Health Connect, and reads it back. Each exported record carries a stable
 * clientRecordId, so re-exporting the same range updates rather than duplicates. [import] reads only what this app
 * wrote (Health Connect keeps it after an uninstall), so it restores OpenStrap's own data and nothing else's.
 */
class HealthConnectExporter(private val context: Context, private val dao: HealthDao) {

    val permissions = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        HealthPermission.getWritePermission(androidx.health.connect.client.records.ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(androidx.health.connect.client.records.DistanceRecord::class),
    )

    /**
     * Asked for with [permissions], but not needed: without them everything else is still written and the calories
     * are left out, so a grant from before calories, or one turned off, keeps working.
     */
    val caloriePermissions = setOf(
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    /** What a write asks Health Connect for. */
    val requestedPermissions: Set<String> get() = permissions + caloriePermissions

    /** Reading back what was exported; history is the permission to read further back than 30 days. */
    val readPermissions: Set<String>
        get() = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        ) + listOfNotNull(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY.takeIf { historyAvailable() })

    private fun historyAvailable() = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
        HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(permissions)

    /** Every permission a write asks for, the optional calorie ones too: when not, the write asks again first. */
    suspend fun hasRequestedPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(requestedPermissions)

    /** The record reads the user has granted (any of them is enough to import that kind). */
    suspend fun hasReadPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().any { it in readPermissions }

    private val device = Device(type = Device.TYPE_FITNESS_BAND, manufacturer = "Amazfit", model = "Helio Strap")

    private fun meta(id: String, version: Long = 1) = Metadata.autoRecorded(device = device, clientRecordId = "openstrap:$id", clientRecordVersion = version)

    /** Zepp's sport ids to Health Connect's exercise types; anything else is "other workout". */
    private fun exerciseType(sport: Int): Int = with(androidx.health.connect.client.records.ExerciseSessionRecord) {
        when (sport) {
            1, 11 -> EXERCISE_TYPE_RUNNING
            2 -> EXERCISE_TYPE_RUNNING_TREADMILL
            3, 40, 215 -> EXERCISE_TYPE_WALKING
            15, 224, 225 -> EXERCISE_TYPE_HIKING
            4 -> EXERCISE_TYPE_BIKING
            8, 143 -> EXERCISE_TYPE_BIKING_STATIONARY
            24, 50, 52, 222, 226 -> EXERCISE_TYPE_STRENGTH_TRAINING
            49 -> EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
            60 -> EXERCISE_TYPE_YOGA
            61 -> EXERCISE_TYPE_PILATES
            6 -> EXERCISE_TYPE_SWIMMING_POOL
            7 -> EXERCISE_TYPE_SWIMMING_OPEN_WATER
            9 -> EXERCISE_TYPE_ELLIPTICAL
            23 -> EXERCISE_TYPE_ROWING_MACHINE
            else -> EXERCISE_TYPE_OTHER_WORKOUT
        }
    }

    private fun offset(epochSeconds: Long): ZoneOffset =
        ZoneId.systemDefault().rules.getOffset(Instant.ofEpochSecond(epochSeconds))

    /** Exports everything in [from, to] (epoch seconds). Returns the number of records written. */
    suspend fun export(from: Long, to: Long, now: Long = System.currentTimeMillis() / 1000): Int {
        val records = mutableListOf<Record>()
        records += heartRate(from, to)
        records += steps(from, to)
        records += workouts(dao.appWorkoutsOnce(from, to))
        records += calories(from, to, now)

        // Every night the delete below can reach (from a day before [from]) is written again, so a night ending
        // just before the range isn't taken out of Health Connect and left out.
        val edits = dao.sleepEditsOnce()
        for (r in org.openstrap.app.data.SleepEdits.resolve(dao.sleepSessionsOnce(from - 86_400, to), edits)) {
            // A sleep goes with the user's own times; its record id stays the strap's start, so it replaces the old one.
            // A deleted one isn't written, and the delete above takes it out of Health Connect.
            val strapSession = r.strap
            val s = r.shown ?: continue
            // Rows synced before stage ends were made exclusive can be zero length; Health Connect rejects those,
            // and stages must stay inside the session.
            val stages = dao.sleepStagesOnce(strapSession.start).mapNotNull {
                val start = it.start.coerceAtLeast(s.start)
                val end = maxOf(it.end, it.start + 60).coerceAtMost(s.end)
                if (end <= start) return@mapNotNull null
                SleepSessionRecord.Stage(
                    Instant.ofEpochSecond(start), Instant.ofEpochSecond(end),
                    when (it.stage) {
                        SleepStage.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
                        SleepStage.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
                        SleepStage.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
                        SleepStage.REM -> SleepSessionRecord.STAGE_TYPE_REM
                        else -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
                    },
                )
            }
            records += SleepSessionRecord(
                startTime = Instant.ofEpochSecond(s.start), startZoneOffset = offset(s.start),
                endTime = Instant.ofEpochSecond(s.end), endZoneOffset = offset(s.end),
                stages = stages,
                metadata = meta("sleep:${strapSession.start}"),
            )
        }

        for (o in dao.spo2Once(from, to)) {
            if (o.percent !in 70..100) continue
            records += OxygenSaturationRecord(
                time = Instant.ofEpochSecond(o.timestamp), zoneOffset = offset(o.timestamp),
                percentage = Percentage(o.percent.toDouble()),
                metadata = meta("spo2:${o.timestamp}"),
            )
        }

        for (h in dao.hrvOnce(from, to)) {
            if (h.milliseconds !in 1..200) continue // Health Connect's accepted RMSSD range
            records += HeartRateVariabilityRmssdRecord(
                time = Instant.ofEpochSecond(h.timestamp), zoneOffset = offset(h.timestamp),
                heartRateVariabilityMillis = h.milliseconds.toDouble(),
                metadata = meta("hrv:${h.timestamp}"),
            )
        }

        for (r in dao.restingHrOnce(from, to)) {
            records += RestingHeartRateRecord(
                time = Instant.ofEpochSecond(r.day), zoneOffset = offset(r.day),
                beatsPerMinute = r.bpm.toLong(),
                metadata = meta("rhr:${r.day}"),
            )
        }

        // Sleep that OpenStrap exported earlier may since have changed shape (a nap folded into the night),
        // so its old records in this range are replaced rather than updated. This only touches OpenStrap's records.
        client.deleteRecords(SleepSessionRecord::class, TimeRangeFilter.between(Instant.ofEpochSecond(from - 86_400), Instant.ofEpochSecond(to)))
        // Health Connect caps the batch size per call.
        records.chunked(500).forEach { client.insertRecords(it) }
        return records.size
    }

    /**
     * Exports the phone workout that started at [start], as it's saved: the session, its distance, and the heart rate
     * and steps over it ([AutoHealthConnect.workoutRange], whole hours of heart rate), and the calories of the days it
     * touches, whose kcal it moved. Sleep is left alone. Returns the number of records written; 0 for a workout that
     * isn't there or hasn't ended.
     */
    suspend fun exportWorkout(start: Long, now: Long = System.currentTimeMillis() / 1000): Int {
        val w = dao.appWorkoutOnce(start) ?: return 0
        val end = w.end ?: return 0
        val (from, to) = AutoHealthConnect.workoutRange(w.from, end, now)
        val records = heartRate(from, to) + steps(from, to) + workouts(listOf(w)) + calories(w.from, end, now)
        records.chunked(500).forEach { client.insertRecords(it) }
        return records.size
    }

    /**
     * Active kcal an hour and each finished full day's total ([CalorieRecords]) over the whole local days [from] to
     * [to] are in, from the stored days, each only with its permission granted. A day worked out again can lose an
     * hour or turn partial, so OpenStrap's old calorie records over those days go first, as its sleep does.
     */
    private suspend fun calories(from: Long, to: Long, now: Long): List<Record> {
        val granted = client.permissionController.getGrantedPermissions()
        val active = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class) in granted
        val total = HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class) in granted
        if (!active && !total) return emptyList()
        val zone = ZoneId.systemDefault()
        val (a, b) = CalorieRecords.days(from, to, zone)
        val rows = dao.dailyEnergyOnce(a, b - 1)
        val range = TimeRangeFilter.between(Instant.ofEpochSecond(a), Instant.ofEpochSecond(minOf(b, now)))
        val records = mutableListOf<Record>()
        if (active) {
            client.deleteRecords(ActiveCaloriesBurnedRecord::class, range)
            records += CalorieRecords.activeHours(rows, zone, now).map {
                ActiveCaloriesBurnedRecord(
                    startTime = Instant.ofEpochSecond(it.start), startZoneOffset = offset(it.start),
                    endTime = Instant.ofEpochSecond(it.end), endZoneOffset = offset(it.end),
                    energy = Energy.kilocalories(it.kcal), metadata = meta(it.id, it.version),
                )
            }
        }
        if (total) {
            client.deleteRecords(TotalCaloriesBurnedRecord::class, range)
            records += CalorieRecords.dayTotals(rows, zone, now).map {
                TotalCaloriesBurnedRecord(
                    startTime = Instant.ofEpochSecond(it.start), startZoneOffset = offset(it.start),
                    endTime = Instant.ofEpochSecond(it.end), endZoneOffset = offset(it.end),
                    energy = Energy.kilocalories(it.kcal), metadata = meta(it.id, it.version),
                )
            }
        }
        return records
    }

    /** Heart rate: one record per hour with all samples in it. */
    private suspend fun heartRate(from: Long, to: Long): List<Record> {
        val hr = dao.heartRateOnce(from, to) + dao.activityOnce(from, to)
            .mapNotNull { m -> m.heartRate?.takeIf { it in 30..230 }?.let { org.openstrap.app.data.db.HeartRateSample(m.timestamp, it) } }
        return hr.distinctBy { it.timestamp }.sortedBy { it.timestamp }.groupBy { it.timestamp / 3600 }.map { (hour, samples) ->
            val start = hour * 3600
            val end = samples.last().timestamp + 1 // not the end of the hour, which can be in the future
            HeartRateRecord(
                startTime = Instant.ofEpochSecond(start), startZoneOffset = offset(start),
                endTime = Instant.ofEpochSecond(end), endZoneOffset = offset(end),
                samples = samples.map { HeartRateRecord.Sample(Instant.ofEpochSecond(it.timestamp), it.bpm.toLong()) },
                metadata = meta("hr:$start"),
            )
        }
    }

    private suspend fun steps(from: Long, to: Long): List<Record> = dao.activityOnce(from, to).filter { it.steps > 0 }.map { m ->
        StepsRecord(
            startTime = Instant.ofEpochSecond(m.timestamp), startZoneOffset = offset(m.timestamp),
            endTime = Instant.ofEpochSecond(m.timestamp + 60), endZoneOffset = offset(m.timestamp),
            count = m.steps.toLong(),
            metadata = meta("steps:${m.timestamp}"),
        )
    }

    /** Workouts recorded on the phone, as exercise sessions; GPS distance with them (a step estimate stays here). */
    private fun workouts(list: List<org.openstrap.app.data.db.AppWorkout>): List<Record> {
        val records = mutableListOf<Record>()
        for (w in list) {
            val end = w.end ?: continue
            records += androidx.health.connect.client.records.ExerciseSessionRecord(
                startTime = Instant.ofEpochSecond(w.from), startZoneOffset = offset(w.from),
                endTime = Instant.ofEpochSecond(end), endZoneOffset = offset(end),
                exerciseType = exerciseType(w.sportType),
                title = org.openstrap.app.data.Workouts.sportName(w.sportType),
                metadata = meta("workout:${w.start}"),
            )
            val meters = w.distanceMeters?.takeIf { !w.distanceFromSteps && it > 0 } ?: continue
            records += androidx.health.connect.client.records.DistanceRecord(
                startTime = Instant.ofEpochSecond(w.from), startZoneOffset = offset(w.from),
                endTime = Instant.ofEpochSecond(end), endZoneOffset = offset(end),
                distance = androidx.health.connect.client.units.Length.meters(meters.toDouble()),
                metadata = meta("distance:${w.start}"),
            )
        }
        return records
    }

    /**
     * Copies what OpenStrap wrote to Health Connect back into the database, filling gaps only: a day the strap has
     * already synced keeps its own, richer rows. Returns the number of rows read. Without the history permission,
     * Health Connect only returns the last 30 days.
     */
    suspend fun import(): Int {
        val granted = client.permissionController.getGrantedPermissions()
        val range = TimeRangeFilter.after(Instant.now().minus(Duration.ofDays(10 * 365)))
        val ours = setOf(DataOrigin(context.packageName))
        suspend fun <T : Record> read(type: KClass<T>): List<T> {
            if (HealthPermission.getReadPermission(type) !in granted) return emptyList()
            val out = mutableListOf<T>()
            var page: String? = null
            do {
                val response = client.readRecords(ReadRecordsRequest(type, range, ours, pageSize = 1000, pageToken = page))
                out += response.records
                page = response.pageToken
            } while (page != null)
            return out
        }
        var n = 0

        val hr = read(HeartRateRecord::class).flatMap { r -> r.samples.map { HeartRateSample(it.time.epochSecond, it.beatsPerMinute.toInt()) } }
        dao.insertHeartRateIfMissing(hr); n += hr.size

        // Steps were exported a minute at a time; a longer record (another export, an edit) is spread over its minutes.
        val steps = read(StepsRecord::class).flatMap { r ->
            val start = r.startTime.epochSecond / 60 * 60
            val minutes = ((r.endTime.epochSecond - start + 59) / 60).coerceAtLeast(1)
            (0 until minutes).map { i ->
                val count = (r.count / minutes + if (i < r.count % minutes) 1 else 0).toInt()
                ActivityMinute(start + i * 60, count, intensity = 0, kind = 0, heartRate = null)
            }
        }
        dao.insertActivityIfMissing(steps); n += steps.size

        for (r in read(SleepSessionRecord::class)) {
            val start = r.startTime.epochSecond
            val end = r.endTime.epochSecond
            val nap = end - start < 3 * 3600
            val zone = ZoneId.systemDefault()
            val day = Instant.ofEpochSecond(if (nap) start else end).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
            val stages = r.stages.map {
                SleepStage(
                    it.startTime.epochSecond, it.endTime.epochSecond, start,
                    when (it.stage) {
                        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStage.AWAKE
                        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.DEEP
                        SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.REM
                        else -> SleepStage.LIGHT
                    },
                )
            }
            fun minutes(stage: Int) = (stages.filter { it.stage == stage }.sumOf { it.end - it.start } / 60).toInt().takeIf { stages.isNotEmpty() }
            dao.insertSleepSessionsIfMissing(listOf(SleepSession(
                start, end, day, nap,
                deepMinutes = minutes(SleepStage.DEEP), lightMinutes = minutes(SleepStage.LIGHT),
                remMinutes = minutes(SleepStage.REM), awakeMinutes = minutes(SleepStage.AWAKE),
                wakeCount = stages.count { it.stage == SleepStage.AWAKE }.takeIf { stages.isNotEmpty() },
            )))
            dao.insertSleepStagesIfMissing(stages)
            n += 1 + stages.size
        }

        val spo2 = read(OxygenSaturationRecord::class).map { Spo2Sample(it.time.epochSecond, it.percentage.value.toInt()) }
        dao.insertSpo2IfMissing(spo2); n += spo2.size

        val hrv = read(HeartRateVariabilityRmssdRecord::class).map { HrvSample(it.time.epochSecond, it.heartRateVariabilityMillis.toInt()) }
        dao.insertHrvIfMissing(hrv); n += hrv.size

        val rhr = read(RestingHeartRateRecord::class).map { RestingHeartRate(it.time.epochSecond, it.beatsPerMinute.toInt()) }
        dao.insertRestingHrIfMissing(rhr); n += rhr.size
        return n
    }
}
