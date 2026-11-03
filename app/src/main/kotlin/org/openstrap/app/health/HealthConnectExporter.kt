package org.openstrap.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import org.openstrap.app.data.Days
import org.openstrap.app.data.HeartRate
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
    /** Health Connect's writes and OpenStrap's own records in it, for [RecordStore.write]. */
    private val store = object : RecordStore {
        override suspend fun insert(records: List<Record>) {
            client.insertRecords(records)
        }

        override suspend fun ownRecords(type: KClass<out Record>, range: TimeRangeFilter): List<Record>? = try {
            readAll(type, range)
        } catch (_: SecurityException) {
            // Without a read grant Health Connect may refuse even this app's own records.
            null
        }

        override suspend fun deleteIds(type: KClass<out Record>, ids: List<String>) = client.deleteRecords(type, ids, emptyList())

        override suspend fun deleteRange(type: KClass<out Record>, range: TimeRangeFilter) = client.deleteRecords(type, range)
    }

    /** Every record of [type] in [range] that OpenStrap wrote, a page at a time. */
    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val out = mutableListOf<T>()
        var page: String? = null
        do {
            val request = ReadRecordsRequest(type, range, setOf(DataOrigin(context.packageName)), pageSize = 1000, pageToken = page)
            val response = client.readRecords(request)
            out += response.records
            page = response.pageToken
        } while (page != null)
        return out
    }

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
        val (kcal, kcalRanges) = calories(from, to, now)
        records += kcal

        records += sleep(from, to, now)

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

        store.write(records, kcalRanges + (SleepSessionRecord::class to sleepRange(from, to)))
        return records.size
    }

    /**
     * Writes only the sleep in [from, to] again, as [export] does: for a sleep the user moved, deleted or brought back,
     * which changes nothing else. Returns the number of records written.
     */
    suspend fun exportSleep(from: Long, to: Long, now: Long = System.currentTimeMillis() / 1000): Int {
        val records = sleep(from, to, now)
        store.write(records, listOf(SleepSessionRecord::class to sleepRange(from, to)))
        return records.size
    }

    /**
     * Sleep that OpenStrap exported earlier may since have changed shape (a nap folded into the night), so its old
     * records in this range are replaced rather than updated. This only touches OpenStrap's records.
     */
    private fun sleepRange(from: Long, to: Long) = TimeRangeFilter.between(Instant.ofEpochSecond(from - 86_400), Instant.ofEpochSecond(to))

    /**
     * The sleep sessions [sleepRange] reaches, as shown. Each carries [now] as its version, so it replaces the record
     * with its id that an earlier export wrote.
     */
    private suspend fun sleep(from: Long, to: Long, now: Long): List<Record> {
        val records = mutableListOf<Record>()
        // Every night [sleepRange] reaches (from a day before [from]) is written again, so a night ending
        // just before the range isn't taken out of Health Connect and left out.
        val sleep = org.openstrap.app.data.SleepSource(dao)
        for (r in sleep.resolvedOnce(from - 86_400, to)) {
            // A sleep goes with the user's own times; its record id stays the strap's start, so it replaces the old one.
            // A deleted one isn't written, so the delete below takes it out of Health Connect.
            val strapSession = r.strap
            val s = r.shown ?: continue
            // Rows synced before stage ends were made exclusive can be zero length; Health Connect rejects those,
            // and stages must stay inside the session.
            val stages = sleep.stagesOnce(r).mapNotNull {
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
                metadata = meta("sleep:${strapSession.start}", now),
            )
        }

        return records
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
        val (kcal, kcalRanges) = calories(w.from, end, now)
        val records = heartRate(from, to) + steps(from, to) + workouts(listOf(w)) + kcal
        store.write(records, kcalRanges)
        return records.size
    }

    /**
     * Active kcal an hour and each finished full day's total ([CalorieRecords]) over the whole local days [from] to
     * [to] are in, from the stored days, each only with its permission granted. A day worked out again can lose an
     * hour or turn partial, so OpenStrap's calorie records over those days are replaced, as its sleep is: with the
     * types and range to replace them in.
     */
    private suspend fun calories(from: Long, to: Long, now: Long): Pair<List<Record>, List<Pair<KClass<out Record>, TimeRangeFilter>>> {
        val granted = client.permissionController.getGrantedPermissions()
        val active = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class) in granted
        val total = HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class) in granted
        if (!active && !total) return emptyList<Record>() to emptyList()
        val zone = ZoneId.systemDefault()
        val (a, b) = CalorieRecords.days(from, to, zone)
        val rows = dao.dailyEnergyOnce(a, b - 1)
        val range = TimeRangeFilter.between(Instant.ofEpochSecond(a), Instant.ofEpochSecond(minOf(b, now)))
        val records = mutableListOf<Record>()
        val replaced = mutableListOf<Pair<KClass<out Record>, TimeRangeFilter>>()
        if (active) {
            replaced += ActiveCaloriesBurnedRecord::class to range
            records += CalorieRecords.activeHours(rows, zone, now).map {
                ActiveCaloriesBurnedRecord(
                    startTime = Instant.ofEpochSecond(it.start), startZoneOffset = offset(it.start),
                    endTime = Instant.ofEpochSecond(it.end), endZoneOffset = offset(it.end),
                    energy = Energy.kilocalories(it.kcal), metadata = meta(it.id, it.version),
                )
            }
        }
        if (total) {
            replaced += TotalCaloriesBurnedRecord::class to range
            records += CalorieRecords.dayTotals(rows, zone, now).map {
                TotalCaloriesBurnedRecord(
                    startTime = Instant.ofEpochSecond(it.start), startZoneOffset = offset(it.start),
                    endTime = Instant.ofEpochSecond(it.end), endZoneOffset = offset(it.end),
                    energy = Energy.kilocalories(it.kcal), metadata = meta(it.id, it.version),
                )
            }
        }
        return records to replaced
    }

    /**
     * Heart rate: one record per hour with all samples in it. Its id is the hour, so it replaces the hour's record
     * written before, and [from] and [to] reach out to whole hours: a range starting mid hour would write that hour's
     * later part over the whole of it.
     */
    internal suspend fun heartRate(from: Long, to: Long): List<Record> {
        val first = Math.floorDiv(from, 3600L) * 3600
        val last = (Math.floorDiv(to, 3600L) + 1) * 3600 - 1
        val hr = dao.heartRateOnce(first, last) + dao.activityOnce(first, last)
            .mapNotNull { m -> m.heartRate?.takeIf { it in HeartRate.VALID }?.let { HeartRateSample(m.timestamp, it) } }
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
     * already synced keeps its own, richer rows, and a sleep the user moved or deleted doesn't come back as another.
     * Returns the number of rows read. Without the history permission,
     * Health Connect only returns the last 30 days. Read a year at a time, so ten years are never in memory at once.
     */
    suspend fun import(now: Instant = Instant.now()): Int {
        val granted = client.permissionController.getGrantedPermissions()
        suspend fun <T : Record> read(type: KClass<T>, range: TimeRangeFilter): List<T> =
            if (HealthPermission.getReadPermission(type) !in granted) emptyList() else readAll(type, range)
        var n = 0
        val start = now.minus(Duration.ofDays(IMPORT_YEARS * 365L))
        for (year in 0 until IMPORT_YEARS) {
            // Each year reaches a day into the one before, so a record across the boundary (a night) is whole in one of
            // them whichever way Health Connect matches a span. Read twice, it is only kept once.
            val from = start.plus(Duration.ofDays(year * 365L))
            val reach = if (year == 0) from else from.minus(Duration.ofDays(1))
            val range = if (year == IMPORT_YEARS - 1) TimeRangeFilter.after(reach)
            else TimeRangeFilter.between(reach, from.plus(Duration.ofDays(365)))
            n += importRange { type -> read(type, range) }
        }
        return n
    }

    /**
     * The sleep in [records] the phone doesn't have yet. One it has at other times (a night the user moved) or
     * deleted stays out ([SleepImport]), with its stages.
     */
    private suspend fun newSleep(records: List<SleepSessionRecord>): List<SleepSessionRecord> {
        if (records.isEmpty()) return records
        val from = records.minOf { it.startTime.epochSecond } - SleepImport.REACH_SECONDS
        val to = records.maxOf { it.endTime.epochSecond } + SleepImport.REACH_SECONDS
        val phone = SleepImport(org.openstrap.app.data.SleepSource(dao).resolvedOnce(from, to), dao.sleepEditsOnce())
        return records.filterNot {
            phone.leavesOut(it.startTime.epochSecond, it.endTime.epochSecond, SleepImport.strapStart(it.metadata.clientRecordId))
        }
    }

    /** [import] over one range, reading each kind of record in it with [read]. */
    internal suspend fun importRange(read: suspend (KClass<out Record>) -> List<Record>): Int {
        var n = 0
        @Suppress("UNCHECKED_CAST") // [read] returns records of the type it is given.
        suspend fun <T : Record> of(type: KClass<T>): List<T> = read(type) as List<T>

        val hr = of(HeartRateRecord::class)
            .flatMap { r -> r.samples.map { HeartRateSample(it.time.epochSecond, it.beatsPerMinute.toInt()) } }
        dao.insertHeartRateIfMissing(hr); n += hr.size

        // Steps were exported a minute at a time; a longer record (another export, an edit) is spread over its minutes.
        val steps = of(StepsRecord::class).flatMap { r ->
            val start = r.startTime.epochSecond / 60 * 60
            val minutes = ((r.endTime.epochSecond - start + 59) / 60).coerceAtLeast(1)
            (0 until minutes).map { i ->
                val count = (r.count / minutes + if (i < r.count % minutes) 1 else 0).toInt()
                ActivityMinute(start + i * 60, count, intensity = 0, kind = 0, heartRate = null)
            }
        }
        dao.insertActivityIfMissing(steps); n += steps.size

        val sessions = mutableListOf<SleepSession>()
        val allStages = mutableListOf<SleepStage>()
        val zone = ZoneId.systemDefault()
        for (r in newSleep(of(SleepSessionRecord::class))) {
            val start = r.startTime.epochSecond
            val end = r.endTime.epochSecond
            val nap = end - start < 3 * 3600
            val day = Days.start(if (nap) start else end, zone)
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
            sessions += SleepSession(
                start, end, day, nap,
                deepMinutes = minutes(SleepStage.DEEP), lightMinutes = minutes(SleepStage.LIGHT),
                remMinutes = minutes(SleepStage.REM), awakeMinutes = minutes(SleepStage.AWAKE),
                wakeCount = stages.count { it.stage == SleepStage.AWAKE }.takeIf { stages.isNotEmpty() },
            )
            allStages += stages
            n += 1 + stages.size
        }
        // The range's sleep in two writes, not two a night.
        if (sessions.isNotEmpty()) dao.insertSleepSessionsIfMissing(sessions)
        if (allStages.isNotEmpty()) dao.insertSleepStagesIfMissing(allStages)

        val spo2 = of(OxygenSaturationRecord::class).map { Spo2Sample(it.time.epochSecond, it.percentage.value.toInt()) }
        dao.insertSpo2IfMissing(spo2); n += spo2.size

        val hrv = of(HeartRateVariabilityRmssdRecord::class).map { HrvSample(it.time.epochSecond, it.heartRateVariabilityMillis.toInt()) }
        dao.insertHrvIfMissing(hrv); n += hrv.size

        val rhr = of(RestingHeartRateRecord::class).map { RestingHeartRate(it.time.epochSecond, it.beatsPerMinute.toInt()) }
        dao.insertRestingHrIfMissing(rhr); n += rhr.size
        return n
    }

    private companion object {
        /** How far back [import] reads, a year at a time. */
        const val IMPORT_YEARS = 10
    }
}

/** The Health Connect calls a write makes, apart so a test can stand in for Health Connect. */
internal interface RecordStore {
    suspend fun insert(records: List<Record>)

    /** OpenStrap's own records of [type] in [range], or null when Health Connect won't let it read them. */
    suspend fun ownRecords(type: KClass<out Record>, range: TimeRangeFilter): List<Record>?

    suspend fun deleteIds(type: KClass<out Record>, ids: List<String>)

    suspend fun deleteRange(type: KClass<out Record>, range: TimeRangeFilter)
}

/**
 * Writes [records], replacing OpenStrap's own records of each type in [replaced] over its range: the new records go
 * in first, and only then are the old ones that weren't written again (by clientRecordId) removed, so a failed insert
 * leaves what was there. Where this app may not read its own records back, the stale ones can only be found by time,
 * so that range is cleared first, as every write did before.
 */
internal suspend fun RecordStore.write(records: List<Record>, replaced: List<Pair<KClass<out Record>, TimeRangeFilter>>) {
    val old = replaced.map { (type, range) -> Triple(type, range, ownRecords(type, range)) }
    for ((type, range, own) in old) if (own == null) deleteRange(type, range)
    // Health Connect caps the batch size per call.
    records.chunked(HC_BATCH).forEach { insert(it) }
    val written = records.mapTo(HashSet()) { it.metadata.clientRecordId }
    for ((type, _, own) in old) {
        val stale = own.orEmpty().filter { it.metadata.clientRecordId !in written }.map { it.metadata.id }
        stale.chunked(HC_BATCH).forEach { deleteIds(type, it) }
    }
}

/** The most records one Health Connect call takes. */
internal const val HC_BATCH = 500
