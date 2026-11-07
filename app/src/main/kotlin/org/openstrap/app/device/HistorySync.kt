package org.openstrap.app.device

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openstrap.app.R
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.StrapUuids
import org.openstrap.app.data.DayKey
import org.openstrap.app.i18n.Strings
import org.openstrap.app.data.Days
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.ActivityExtendedMinute
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.DailyMaxHr
import org.openstrap.app.data.db.DeletedWorkout
import org.openstrap.app.data.db.DismissedWorkout
import org.openstrap.app.data.db.OsaEventRow
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.RawRecord
import org.openstrap.app.data.db.RecoveryHeartRateRow
import org.openstrap.app.data.db.SleepSpo2Sample
import org.openstrap.app.data.db.BreathRate
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.HrvSdnnSample
import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.data.db.StrapReadinessRow
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SkinTemperature
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.Spo2Sample
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.history.ActivityLayout
import org.openstrap.protocol.history.ActivitySample
import org.openstrap.protocol.history.DailyMaxHeartRate
import org.openstrap.protocol.history.OsaResult
import org.openstrap.protocol.history.RecoveryHeartRate
import org.openstrap.protocol.history.SleepRespiratoryEvent
import org.openstrap.protocol.history.SleepSpO2Sample
import org.openstrap.protocol.history.SpO2OsaEvent
import org.openstrap.protocol.history.TodayActivityExtended
import org.openstrap.protocol.history.FastRechargeModeException
import org.openstrap.protocol.history.FetchCodec
import org.openstrap.protocol.history.GdspClient
import org.openstrap.protocol.history.GdspFetch
import org.openstrap.protocol.history.GdspMessage
import org.openstrap.protocol.history.GdspStatus
import org.openstrap.protocol.history.WorkoutFetch
import org.openstrap.protocol.history.HistoryDataType
import org.openstrap.protocol.history.HistoryRecord
import org.openstrap.protocol.history.HistoryTypeSpec
import org.openstrap.protocol.history.HistoryTypeSpecs
import org.openstrap.protocol.history.HrvRmssd
import org.openstrap.protocol.history.HrvSdnn
import org.openstrap.protocol.history.SportEffect
import org.openstrap.protocol.history.WorkoutDetail
import org.openstrap.protocol.history.WorkoutSummary
import org.openstrap.protocol.history.PaiRecord
import org.openstrap.protocol.history.ParseContext
import org.openstrap.protocol.history.ParseResult
import org.openstrap.protocol.history.RestHrRealtime
import org.openstrap.protocol.history.SecondHeartRate
import org.openstrap.protocol.history.SkinTemperatureSample
import org.openstrap.protocol.history.SleepBreathRate
import org.openstrap.protocol.history.SleepResult
import org.openstrap.protocol.history.SleepStageCodes
import org.openstrap.protocol.history.SleepStageType
import org.openstrap.protocol.history.HeartRateSentinel
import org.openstrap.protocol.history.SpO2Reading
import org.openstrap.protocol.history.SpotHeartRate
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.ReadinessCodec
import org.openstrap.protocol.u8
import org.openstrap.protocol.toHex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.openstrap.app.data.db.StressSample as DbStress
import org.openstrap.protocol.history.RestingHeartRate as StrapRestingHr
import org.openstrap.protocol.history.StressSample as StrapStress

/**
 * Pulls history from the strap into Room using the GDSP fetch protocol (docs/protocol/02).
 * Control runs over channel endpoint 0x004B when listed, otherwise HUAMI(0004); data always
 * arrives on HUAMI(0005). Each type keeps its own cursor so an interrupted sync resumes.
 */
class HistorySync internal constructor(
    private val session: ChannelSession,
    private val gatt: HistoryGatt,
    private val dao: HealthDao,
    private val cursors: SyncCursors,
    private val state: MutableStateFlow<SyncState>,
    private val log: (String) -> Unit,
    /** Called for a newly synced workout the strap auto-detected, to notify about it (codemap 05 §8). */
    private val onAutoDetected: (Workout) -> Unit = {},
) {
    constructor(
        session: ChannelSession,
        gatt: GattClient,
        dao: HealthDao,
        settings: SettingsStore,
        state: MutableStateFlow<SyncState>,
        log: (String) -> Unit,
        onAutoDetected: (Workout) -> Unit = {},
    ) : this(session, gatt.asHistoryGatt(), dao, settings.asSyncCursors(), state, log, onAutoDetected)

    private val zone = ZoneId.systemDefault()
    private val useChannel = session.services.has(Endpoints.HISTORY)
    private val control = Channel<GdspMessage>(Channel.UNLIMITED)
    private val data = Channel<ByteArray>(Channel.UNLIMITED)
    private val client = GdspClient(::send, control, data, log)

    /** Syncs every type, or only the codes in [only] (an on-demand sync after a data-available push). */
    suspend fun run(only: Set<Int>? = null) = coroutineScope {
        state.value = SyncState.Running(Strings.app.get(R.string.system_sync_preparing))
        val listeners = launch {
            launch { gatt.notifications.filter { it.characteristic == HISTORY_DATA }.collect { data.send(it.value) } }
            if (useChannel) {
                launch { session.messagesFor(Endpoints.HISTORY).collect { log("gdsp < ${it.toHex()}"); control.send(FetchCodec.parse(it)) } }
            } else {
                launch { gatt.notifications.filter { it.characteristic == HISTORY_CONTROL }.collect { control.send(FetchCodec.parse(it.value)) } }
            }
        }
        // catching gives back a CancellationException only once the sync is cancelled: a timeout is one too, and fails it.
        try {
            catching { sync(only) }.onFailure { e ->
                if (e is FastRechargeModeException) {
                    // Zepp ends the sync job at once in this mode (xyk.java:66-70), and the data waits on the strap.
                    log("history: strap is in fast-charge mode, sync stopped ($e)")
                    state.value = SyncState.Failed(Strings.app.get(R.string.system_sync_fast_charge))
                } else {
                    log("history failed: $e")
                    // A wrapped system error's message is just its class name (android.os.DeadObjectException), not for
                    // the card. Whatever a call on a dropped link threw (a missing characteristic, a closed op), the cause
                    // is the link.
                    val reason = if (!gatt.connected.value) Strings.app.get(R.string.system_sync_link_dropped)
                    else e.message?.takeUnless { e.cause != null && it == e.cause.toString() }
                    state.value = SyncState.Failed(reason ?: Strings.app.get(R.string.system_sync_failed))
                }
            }
        } finally {
            listeners.cancel()
            // A cancelled sync, or anything else that got past the catches, must not stay Running: auto sync skips
            // while it is, and the notification would show progress for good.
            if (state.value is SyncState.Running) state.value = SyncState.Failed(Strings.app.get(R.string.system_sync_stopped))
        }
    }

    /** The sync itself, for [run], which turns what it throws into a Failed state. */
    private suspend fun sync(only: Set<Int>?) {
        // Inside [run]'s catch: a database error here ends the sync as Failed instead of escaping the app's scope.
        repairEarlierSyncs()
        gatt.enableNotifications(StrapUuids.MAIN_SERVICE, HISTORY_DATA)
        if (!useChannel) gatt.enableNotifications(StrapUuids.MAIN_SERVICE, HISTORY_CONTROL)
        log("history: control via ${if (useChannel) "channel 0x004B" else "HUAMI(0004)"}")

        val version = client.queryVersion()
        val advertised = version?.supportedTypes()
        log("history version ${version?.version}, strap offers types ${advertised?.sorted()}" + only?.let { ", syncing $it" }.orEmpty())

        HistoryTypeSpecs.featureDisagreements(advertised, session.features).takeIf { it.isNotEmpty() }
            ?.let { log("history: feature bits clear for advertised types $it; fetching them anyway") }
        val plan = HistoryTypeSpecs.plan(advertised, session.features).filter { it.parser != null && (only == null || it.code in only) }
        plan.forEachIndexed { i, spec ->
            val step = Strings.app.format(R.string.system_sync_step, syncKindName(spec.type), i + 1, plan.size)
            state.value = SyncState.Running(step, i.toFloat() / plan.size)
            catching { if (spec.followedBy == HistoryDataType.SPORT_DETAIL) syncWorkouts(spec) else syncType(spec) }.onFailure { e ->
                // A timeout is a CancellationException too, but catching gives back only a cancelled sync's (audit C2).
                if (e is FastRechargeModeException) throw e
                // The link is gone: every later type would fail the same way, and the sync must not end as Done.
                if (!gatt.connected.value) throw e
                log("history ${spec.type}: $e")
            }
        }
        if (only == null && session.services.has(Endpoints.READINESS)) catching { syncReadiness() }.onFailure { log("readiness: $it") }
        // A session the strap ran alongside a phone workout has just come in: its figures replace the model's.
        catching {
            org.openstrap.app.data.WorkoutEffectEngine(dao)
                .takeStrapEffects(System.currentTimeMillis() / 1000 - WORKOUT_LOOKBACK, WorkoutSession.LOCK)
        }
            .onFailure { log("strap effect: $it") }
        // A night from the strap's minute sleep signal where its own sleep result is missing or wrong (firmware 3.27.0.1).
        catching { org.openstrap.app.data.MinuteSleep.apply(dao, zone, System.currentTimeMillis() / 1000) }
            .onSuccess { if (it.isNotEmpty()) log("history: sleep from the minute signal for $it") }
            .onFailure { log("minute sleep: $it") }
        // Sleep deleted over a week ago goes for good, and a copy the strap just sent again with it.
        catching { org.openstrap.app.data.SleepSource(dao).purgeExpired(System.currentTimeMillis() / 1000) }
            .onSuccess { if (it > 0) log("history: removed $it sleep sessions deleted over a week ago") }
            .onFailure { log("deleted sleep: $it") }
        state.value = SyncState.Done(System.currentTimeMillis() / 1000)
    }

    /**
     * Debug aid: fetches every type the strap offers since [fromEpoch] and writes each chunk's payload to
     * [dir] as `<code>_<headerTime>.bin`, without storing records or moving sync cursors.
     */
    suspend fun dumpRaw(fromEpoch: Long, dir: java.io.File, only: Set<Int>? = null) = coroutineScope {
        if (only == null) dir.deleteRecursively()
        dir.mkdirs()
        val listeners = launch {
            launch { gatt.notifications.filter { it.characteristic == HISTORY_DATA }.collect { data.send(it.value) } }
            if (useChannel) launch { session.messagesFor(Endpoints.HISTORY).collect { log("dump gdsp < ${it.toHex()}"); control.send(FetchCodec.parse(it)) } }
            else launch { gatt.notifications.filter { it.characteristic == HISTORY_CONTROL }.collect { control.send(FetchCodec.parse(it.value)) } }
        }
        try {
            gatt.enableNotifications(StrapUuids.MAIN_SERVICE, HISTORY_DATA)
            if (!useChannel) gatt.enableNotifications(StrapUuids.MAIN_SERVICE, HISTORY_CONTROL)
            val version = client.queryVersion()
            val codes = only?.sorted() ?: version?.supportedTypes()?.sorted().orEmpty()
            log("dump: types $codes (strap offers ${version?.supportedTypes()?.sorted()})")
            for (code in codes) {
                state.value = SyncState.Running(Strings.app.format(R.string.system_sync_dumping, code))
                catching {
                    var cursor = fromEpoch
                    repeat(DUMP_CHUNKS) {
                        val chunk = when (val f = client.fetch(code, cursor, zone)) {
                            is GdspFetch.Chunk -> f
                            is GdspFetch.Refused -> { log("dump $code: start ${f.status}"); return@catching }
                            is GdspFetch.Failed -> { log("dump $code: ${f.reason}"); return@catching }
                            GdspFetch.Empty -> { log("dump $code: empty"); return@catching }
                        }
                        client.finish()
                        val header = chunk.headerTime
                        val payload = chunk.payload
                        java.io.File(dir, "${code}_$header.bin").writeBytes(payload)
                        log("dump $code: ${payload.size} bytes, size field ${chunk.sizeField}, from ${Instant.ofEpochSecond(header)}")
                        val spec = HistoryTypeSpecs.forCode(code)
                        val parsed = spec?.parser?.parse(payload, ParseContext(header, activityRecordSize = recordSize(code, payload, chunk.sizeField)))
                        val next = (parsed as? ParseResult.Success)?.nextCursor
                        if (next == null || next <= cursor || spec.loops == false) return@catching
                        cursor = next
                    }
                }.onFailure { log("dump $code: $it") }
            }
            state.value = SyncState.Done(System.currentTimeMillis() / 1000)
            log("dump: done")
        } finally {
            listeners.cancel()
        }
    }

    private suspend fun send(bytes: ByteArray) {
        if (useChannel) session.send(Endpoints.HISTORY, bytes)
        else gatt.write(StrapUuids.MAIN_SERVICE, HISTORY_CONTROL, bytes, withResponse = true)
    }

    /**
     * Undo damage from earlier builds: 0xFF padding stored as 255-step minutes, minutes dated before any strap clock
     * was set, sync cursors past the present, and sport effects left with no workout to own them.
     *
     * Activity dated after now is kept. The stale slots dated weeks ahead that builds before 16 Oct stored are gone,
     * and [keepable] keeps new ones out, so a minute after now means the phone's clock went back, and deleting it
     * lost that day's activity.
     */
    private suspend fun repairEarlierSyncs() {
        val now = System.currentTimeMillis() / 1000
        val padding = dao.deletePaddingActivity()
        val outside = dao.deleteActivityOutside(MIN_VALID_EPOCH, Long.MAX_VALUE)
        if (padding + outside > 0) log("history: removed $padding padding and $outside misdated activity rows")
        // Earlier builds stored the whole mode byte; the mode is its low nibble.
        val modes = dao.fixActivityModeNibble()
        if (modes > 0) log("history: fixed the mode of $modes activity rows")
        val gaps = dao.closeSleepStageGaps()
        if (gaps > 0) log("history: closed $gaps one-minute gaps after sleep stages")
        dao.deleteNapStagesInsideMainSleep()
        val naps = dao.deleteNapsInsideMainSleep()
        if (naps > 0) log("history: removed $naps naps inside a main sleep")
        // Builds before workouts were decoded moved the workout cursors past workouts they threw away. None left because
        // they were all deleted here is not that, and going back would only fetch them again to skip them.
        if (dao.workoutCount() == 0 && dao.deletedWorkoutsAll().isEmpty()) for (type in WORKOUT_TYPES) {
            val key = "gdsp_${type.code}"
            if ((cursors.get(key) ?: continue) > now - WORKOUT_LOOKBACK) cursors.set(key, now - WORKOUT_LOOKBACK)
        }
        for (type in HistoryDataType.entries) {
            val key = "gdsp_${type.code}"
            val cursor = cursors.get(key) ?: continue
            val activityRepaired = type == HistoryDataType.ACTIVITY && padding + outside > 0
            if (cursor <= now + FUTURE_SLACK && !activityRepaired) continue
            val reset = if (type == HistoryDataType.ACTIVITY) {
                dao.latestActivity(now)?.plus(60) ?: (now - DEFAULT_LOOKBACK)
            } else {
                now - DEFAULT_LOOKBACK
            }
            log("history: cursor for $type was ${Instant.ofEpochSecond(cursor)}, reset to ${Instant.ofEpochSecond(reset)}")
            cursors.set(key, reset)
        }
        removeUnownedEffects(now)
    }

    /** Effects stored before a sync left them out ([unownedEffects]) go, remembered as a sync would. */
    private suspend fun removeUnownedEffects(now: Long) {
        val leftover = unownedEffects(dao.sportEffectsOnce(MIN_VALID_EPOCH, Long.MAX_VALUE).map { it.end }, now)
        if (leftover.isEmpty()) return
        dao.inTransaction {
            leftover.forEach { dao.deleteSportEffect(it) }
            dao.insertDeletedWorkouts(leftover.map { effectMarker(it, now) })
        }
        log("history: removed ${leftover.size} sport effects over no workout")
    }

    /**
     * The ends of the sport effects in [ends] that no workout owns. The strap sends an effect for a phone workout ended
     * under a minute, or one discarded, but no workout record, and the phone has already deleted that workout and
     * remembered its time ([DismissedWorkout]). Kept, the effect waits for a later strap workout with none of its own
     * to adopt it, which takes its end and recovery time. So an effect that ends inside a dismissed time, or up to
     * [EFFECT_GRACE] after it, goes, unless a strap workout or a phone workout runs to it.
     */
    private suspend fun unownedEffects(ends: List<Long>, now: Long): Set<Long> {
        if (ends.isEmpty()) return emptySet()
        val dismissed = dao.dismissedWorkoutsAll()
        return ends.filter { inDismissedTime(it, dismissed) }.filterTo(HashSet()) { !ownedEffect(it, workoutsBefore(it, now)) }
    }

    /** The times of the strap and phone workouts that could own an effect ending at [end], each to its real end. */
    private suspend fun workoutsBefore(end: Long, now: Long): List<LongRange> {
        val from = end - Workouts.EFFECT_WINDOW_S
        val strap = dao.workoutsOnce(from, end).map { w ->
            w.start..Workouts.endOf(w, dao.workoutHeartRateOnce(w.start).lastOrNull()?.timestamp, null)
        }
        return strap + dao.appWorkoutsOnce(from, end).map { Workouts.phoneRange(it, now) }
    }

    /**
     * Where a type with no cursor starts. Forget strap clears every cursor, and the strap may hold days the phone never
     * fetched, so the sync takes what it holds after the type's newest stored row (fetched again, which replaces it),
     * or, with none, as far back as the strap keeps history ([HISTORY_LOOKBACK]). Workouts keep their own lookback.
     */
    private suspend fun firstCursor(type: HistoryDataType, now: Long): Long {
        if (type in WORKOUT_TYPES) return now - WORKOUT_LOOKBACK
        val oldest = now - HISTORY_LOOKBACK
        val newest = NEWEST_KEY[type]?.let { key ->
            dao.newestKeyOnce(androidx.sqlite.db.SimpleSQLiteQuery(key.sql))
                // A day key is a date: its local midnight here is where that day's records start.
                ?.let { if (key.day) Days.start(DayKey.date(it), zone) else it }
                ?.minus(key.before)
        }
        return newest?.coerceIn(oldest, now) ?: oldest
    }

    /**
     * Whether the strap workout [row] is the strap's copy ([Workouts.sameWorkout]) of a time marked as no workout: a
     * phone workout discarded or ended under a minute, a strap workout deleted, or a suggestion turned down.
     */
    private suspend fun discardedPhoneWorkout(row: Workout): Boolean =
        dao.dismissedWorkoutsAll().any { Workouts.sameWorkout(row.start..row.end, it.start..it.end) }

    private suspend fun syncType(spec: HistoryTypeSpec) {
        val key = "gdsp_${spec.code}"
        var cursor = cursors.get(key) ?: firstCursor(spec.type, System.currentTimeMillis() / 1000)
        // The strap keeps updating a day's max exercise HR, so Zepp re-fetches it from the start of the day on every
        // sync (y4t.java:62-69).
        if (spec.type == HistoryDataType.DAILY_MAX_EXERCISE_HR) cursor = dayStart(cursor)
        repeat(MAX_CHUNKS_PER_TYPE) {
            val chunk = when (val f = client.fetch(spec.code, cursor, zone)) {
                is GdspFetch.Chunk -> f
                is GdspFetch.Refused -> { log("history ${spec.type}: start refused (${f.status})"); return }
                is GdspFetch.Failed -> { log("history ${spec.type}: ${f.reason}"); return }
                GdspFetch.Empty -> return
            }
            val headerTime = chunk.headerTime
            val payload = chunk.payload
            // The size field counts records, not bytes (activity: size 4 = four 8-byte minutes).
            val unit = chunk.unit ?: run {
                log("history ${spec.type}: got ${payload.size} bytes for size ${chunk.sizeField}, discarding")
                finish(GdspStatus.DATA_SIZE_MISMATCH)
                return
            }
            log("history ${spec.type}: ${payload.size} bytes (${chunk.sizeField}×$unit) from ${Instant.ofEpochSecond(headerTime)}: ${payload.copyOf(minOf(32, payload.size)).toHex()}")

            when (val result = spec.parser!!.parse(payload, ParseContext(headerTime, activityRecordSize = recordSize(spec.code, payload, chunk.sizeField)))) {
                is ParseResult.Failure -> {
                    log("history ${spec.type}: parse failed: ${result.reason}")
                    finish(GdspStatus.CONTENT_PARSE_ERROR)
                    return
                }
                is ParseResult.Success -> {
                    // Drop records with impossible times (stale buffer slots, data from before the clock was set), and
                    // activity minutes that haven't ended yet.
                    val now = System.currentTimeMillis() / 1000
                    val (records, parsedNext) = holdBackPartialMinute(result.records, result.nextCursor, cursor)
                    val kept = keepable(spec.type, records, now)
                    val dropped = records.size - kept.size
                    log(
                        "history ${spec.type}: ${kept.size} records" +
                            if (dropped > 0) " ($dropped unfinished or with impossible times dropped)" else "",
                    )
                    // The chunk's rows commit together, before the ACK and the cursor: a kill before the commit keeps
                    // none of them and the cursor where it was, a kill after it fetches the chunk again, and storing
                    // it again replaces the same rows.
                    dao.inTransaction { store(kept) }
                    // Only acknowledge once the data is safely in the database.
                    finish()
                    val next = nextCursor(spec.type, headerTime, records, parsedNext, kept, now)
                    if (next != null && next > cursor) cursors.set(key, next)
                    if (!HistoryTypeSpecs.shouldContinue(spec, cursor, next)) return
                    cursor = next!!
                }
            }
        }
    }

    /**
     * Per-second HR is stored as one average per minute, keyed by the minute. A minute split across two chunks
     * would be averaged twice, the second half replacing the first, so the chunk's last minute is left out and the
     * cursor stops at its start: the next chunk brings the whole minute.
     */
    private fun holdBackPartialMinute(records: List<HistoryRecord>, next: Long?, cursor: Long): Pair<List<HistoryRecord>, Long?> {
        if (records.isEmpty() || records.any { it !is SecondHeartRate }) return records to next
        val lastMinute = records.maxOf { it.timestamp } / 60 * 60
        // Never hold back so much that the cursor can't move.
        if (lastMinute <= cursor) return records to next
        return records.filter { it.timestamp < lastMinute } to lastMinute
    }

    /** Saves the strap's latest readiness score; it keeps no history, so this runs every sync. */
    private suspend fun syncReadiness() {
        val since = System.currentTimeMillis() / 1000 - DEFAULT_LOOKBACK
        val reply = session.request(Endpoints.READINESS, ReadinessCodec.scoreRequest(since), 8_000) { it.isNotEmpty() && it.u8(0) == ReadinessCodec.SCORE + 1 }
        val scores = ReadinessCodec.parseScores(reply).orEmpty()
        log("readiness: ${scores.joinToString { "${Instant.ofEpochSecond(it.day)} ${it.score}" }.ifEmpty { "none" }}")
        // The strap's day is its local midnight, taken as its date in the phone's zone, as the strap keeps the phone's.
        val rows = scores.filter { it.score != null }.map {
            StrapReadinessRow(
                DayKey.fromLegacy(it.day, zone), it.updatedAt, it.score, it.hrvScore, it.restingHrScore, it.skinTemperatureScore,
                it.breathingScore, it.sleepHrv, it.sleepRestingHr, it.algorithmVersion,
            )
        }
        if (rows.isNotEmpty()) dao.insertStrapReadiness(rows)
    }

    private suspend fun finish(status: GdspStatus = GdspStatus.SUCCESS) = client.finish(status)

    /**
     * Workouts, one per round: the summary, then its detail from the same cursor (04 §3.16). The cursor only
     * moves once both are stored, so a workout whose detail transfer broke is fetched again next time.
     */
    private suspend fun syncWorkouts(spec: HistoryTypeSpec) {
        val key = "gdsp_${spec.code}"
        var cursor = cursors.get(key) ?: (System.currentTimeMillis() / 1000 - WORKOUT_LOOKBACK)
        repeat(MAX_CHUNKS_PER_TYPE) {
            when (val w = client.fetchWorkout(cursor, zone)) {
                WorkoutFetch.None -> return
                is WorkoutFetch.Failed -> { log("history workout: ${w.reason}"); return }
                is WorkoutFetch.Workout -> {
                    val now = System.currentTimeMillis() / 1000
                    val deleted = dao.deletedWorkoutsAll().any { it.start == w.summary.trackId }
                    val discarded = !deleted && discardedPhoneWorkout(workoutRow(w.summary))
                    if (deleted) {
                        log("history workout ${Instant.ofEpochSecond(w.summary.trackId)}: deleted in OpenStrap, skipped")
                    } else if (discarded) {
                        // The strap's copy of a phone workout discarded before it synced: deleted like one deleted here.
                        log("history workout ${Instant.ofEpochSecond(w.summary.trackId)}: over a discarded workout, skipped")
                        dao.insertDeletedWorkouts(listOf(DeletedWorkout(w.summary.trackId, null, now)))
                    } else if (w.summary.timestamp in MIN_VALID_EPOCH..now + FUTURE_SLACK) {
                        log("history workout ${Instant.ofEpochSecond(w.summary.trackId)}: type ${w.summary.sportType}, subtype ${w.summary.subtype}, ${w.detail?.heartRate?.size ?: 0} HR samples")
                        val storedBefore = dao.workout(w.summary.trackId).first() != null
                        dao.inTransaction { store(listOfNotNull(w.summary, w.detail)) }
                        val row = workoutRow(w.summary)
                        // One you started on the phone over the same time is the same workout: nothing new to tell.
                        val yours = Workouts.phoneTwin(row, dao.appWorkoutsOnce(row.start, row.end), now) != null
                        if (!yours && Workouts.notifyAutoDetected(row, storedBefore, now)) {
                            catching { onAutoDetected(row) }.onFailure { log("auto-detect notification: $it") }
                        }
                    } else {
                        log("history workout with impossible time ${w.summary.timestamp} dropped")
                    }
                    if (w.retryDetail) { log("history workout: detail failed, will retry next sync"); return }
                    val next = w.nextCursor.coerceAtMost(now)
                    if (next <= cursor) return
                    cursors.set(key, next)
                    cursor = next
                }
            }
        }
    }

    private suspend fun store(received: List<HistoryRecord>) {
        val deleted = if (received.any { it is WorkoutSummary || it is WorkoutDetail || it is SportEffect || it is RecoveryHeartRate }) {
            dao.deletedWorkoutsAll()
        } else emptyList()
        val kept = withoutDeletedWorkouts(received, deleted)
        if (kept.size < received.size) log("history: skipped ${received.size - kept.size} records of workouts deleted in OpenStrap")
        val now = System.currentTimeMillis() / 1000
        val unowned = unownedEffects(kept.filterIsInstance<SportEffect>().map { it.timestamp }, now)
        if (unowned.isNotEmpty()) {
            log("history: skipped ${unowned.size} sport effects over no workout")
            dao.insertDeletedWorkouts(unowned.map { effectMarker(it, now) })
        }
        val records = if (unowned.isEmpty()) kept else kept.filter { it !is SportEffect || it.timestamp !in unowned }
        val activity = mutableListOf<ActivityMinute>()
        val hr = mutableListOf<HeartRateSample>()
        val perSecond = mutableMapOf<Long, MutableList<Int>>()
        val spo2 = mutableListOf<Spo2Sample>()
        val stress = mutableListOf<DbStress>()
        val hrv = mutableListOf<HrvSample>()
        val resting = mutableListOf<RestingHeartRate>()
        val restingIfMissing = mutableListOf<RestingHeartRate>()
        val skin = mutableListOf<SkinTemperature>()
        val breath = mutableListOf<BreathRate>()
        val pai = mutableListOf<PaiSample>()
        val sdnn = mutableListOf<HrvSdnnSample>()
        val workouts = mutableListOf<Workout>()
        val effects = mutableListOf<SportEffectRow>()
        val sleepSpo2 = mutableListOf<SleepSpo2Sample>()
        val osaEvents = mutableListOf<OsaEventRow>()
        val osaResults = mutableListOf<OsaResultRow>()
        val maxHr = mutableMapOf<LocalDate, Int>()
        val recoveryHr = mutableListOf<RecoveryHeartRateRow>()
        val extended = mutableListOf<ActivityExtendedMinute>()
        val raw = mutableListOf<RawRecord>()
        for (r in records) when (r) {
            is ActivitySample -> activity += ActivityMinute(r.timestamp, r.steps, r.intensity, r.mode, r.heartRate)
            // Per-second HR is averaged per minute to keep the database small.
            is SecondHeartRate -> r.heartRate?.let { perSecond.getOrPut(r.timestamp / 60 * 60) { mutableListOf() } += it }
            is SpotHeartRate -> r.heartRate?.let { hr += HeartRateSample(r.timestamp, it) }
            is SpO2Reading -> if (r.isPlausible) spo2 += Spo2Sample(r.timestamp, r.spo2)
            is StrapStress -> if (!r.isLikelyGap) stress += DbStress(r.timestamp, r.stress)
            is HrvRmssd -> if (r.rmssd > 0) hrv += HrvSample(r.timestamp, r.rmssd)
            is HrvSdnn -> if (r.sdnnMs in 1.0..400.0) sdnn += HrvSdnnSample(r.timestamp, r.sdnnMs.toFloat())
            is WorkoutSummary -> workouts += workoutRow(r)
            is WorkoutDetail -> {
                // One sample per second; the strap re-sends a workout whole, so replace it.
                val samples = r.heartRate.associate { (ms, bpm) -> ms / 1000 to bpm }
                    .map { (t, bpm) -> WorkoutHeartRate(r.timestamp, t, bpm) }
                dao.replaceWorkoutDetail(r.timestamp, samples)
            }
            is SportEffect -> effects += SportEffectRow(
                r.timestamp, r.recoveryTimeMinutes, r.vo2max, r.epoc, r.trainingLoadWeekly, r.trainingLoadMonthly,
                r.optimalLoadMin, r.optimalLoadMax, r.maximalHeartRate,
            )
            is StrapRestingHr -> if (r.heartRate in 30..120) resting += RestingHeartRate(dayOf(r.timestamp), r.heartRate)
            // A realtime value (86) must not replace the strap's daily resting HR (58) for the same day.
            is RestHrRealtime -> if (r.realtimeRestHr in 30..120) restingIfMissing += RestingHeartRate(dayOf(r.timestamp), r.realtimeRestHr)
            is SleepResult -> storeSleep(r)
            is SkinTemperatureSample -> r.skinCelsius?.let { skin += SkinTemperature(r.timestamp, it) }
            is SleepBreathRate -> if (r.breathRate in 4..40) breath += BreathRate(r.timestamp, r.breathRate)
            is PaiRecord -> {
                pai += PaiSample(r.timestamp, r.dailyPai, r.totalPai, r.restHr, r.maxHr, r.nextActivityScores?.getOrNull(1))
                // The strap's resting-HR profile; used only for days without a real resting value.
                if (r.restHr in 30..120) restingIfMissing += RestingHeartRate(dayOf(r.timestamp), r.restHr)
            }
            // Every record as sent, extras and all, so they can be decoded later ([SleepSpo2Sample]).
            is SleepSpO2Sample -> sleepSpo2 += SleepSpo2Sample(
                r.timestamp, r.spo2, r.time, r.v2?.high, r.v2?.low, r.v2?.signalQuality, r.v2?.extend,
            )
            is SpO2OsaEvent -> osaEvents += OsaEventRow(
                r.timestamp, r.spo2Decrease, r.spo2?.map { it.toByte() }?.toByteArray(), r.heartRate?.map { it.toByte() }?.toByteArray(),
            )
            is OsaResult -> osaResults += OsaResultRow(r.timestamp, r.odiValidTime, r.odiRefined, r.odiScore, r.odiDispCode, r.odNumRefined, r.version)
            // The strap updates the day's value as it rises; keep the highest seen.
            is DailyMaxHeartRate -> HeartRateSentinel.valid(r.maxHeartRate)?.let { bpm ->
                maxHr.merge(dayOf(r.timestamp), bpm, ::maxOf)
            }
            is RecoveryHeartRate -> if (r.samples.isNotEmpty()) recoveryHr += RecoveryHeartRateRow(r.trackId, r.samples.map { it.toByte() }.toByteArray())
            // A record of 0xFF bytes is buffer padding, not a minute (keepable drops it already).
            is TodayActivityExtended -> if (!isBlank(r)) extended += ActivityExtendedMinute(
                r.timestamp, r.floorCount, r.runDuration, r.fatBurnDuration, r.standDuration, r.walkDuration, r.sitDuration,
                r.steps, r.calories, r.distanceCm,
            )
            is SleepRespiratoryEvent -> raw += RawRecord(HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT.code, r.timestamp, r.raw)
            else -> Unit
        }
        perSecond.forEach { (minute, values) -> hr += HeartRateSample(minute, values.average().toInt()) }
        if (activity.isNotEmpty()) dao.insertActivity(activity)
        if (hr.isNotEmpty()) dao.insertHeartRate(hr)
        if (spo2.isNotEmpty()) dao.insertSpo2(spo2)
        if (stress.isNotEmpty()) dao.insertStress(stress)
        if (hrv.isNotEmpty()) dao.insertHrv(hrv)
        if (resting.isNotEmpty()) dao.insertRestingHr(resting)
        if (restingIfMissing.isNotEmpty()) dao.insertRestingHrIfMissing(restingIfMissing)
        if (skin.isNotEmpty()) dao.insertSkinTemperature(skin)
        if (breath.isNotEmpty()) dao.insertBreathRate(breath)
        if (pai.isNotEmpty()) dao.insertPai(pai)
        if (sdnn.isNotEmpty()) dao.insertHrvSdnn(sdnn)
        if (workouts.isNotEmpty()) dao.insertWorkouts(workouts)
        if (effects.isNotEmpty()) dao.insertSportEffects(effects)
        if (sleepSpo2.isNotEmpty()) dao.insertSleepSpo2(sleepSpo2)
        if (osaEvents.isNotEmpty()) dao.insertOsaEvents(osaEvents)
        if (osaResults.isNotEmpty()) dao.insertOsaResults(osaResults)
        if (maxHr.isNotEmpty()) dao.insertDailyMaxHr(maxHr.map { (day, bpm) -> DailyMaxHr(day, bpm) })
        if (recoveryHr.isNotEmpty()) dao.insertRecoveryHeartRate(recoveryHr)
        if (extended.isNotEmpty()) dao.insertActivityExtended(extended)
        if (raw.isNotEmpty()) dao.insertRawRecords(raw)
    }

    /**
     * Stores a day's sleep result (02 §3.3): the main sleep with the strap's own score and totals, and each
     * nap. The strap re-sends a day as it updates it, so the day's earlier version is replaced.
     */
    private suspend fun storeSleep(r: SleepResult) {
        // The strap's day for the result (02 §3.3), stored as its date ([DayKey]). Its times stay moments from r.day.
        val day = Days.date(r.day(zone), zone)
        var mainWindow: Pair<Long, Long>? = null
        // Every stage the count covers is real; Zepp shows one with an unknown code as light sleep (04 §3.5).
        val main = r.mainStages.filter { it.stop >= it.start }
        main.filter { it.rawType !in SleepStageCodes.VALID }.takeIf { it.isNotEmpty() }
            ?.let { log("history: ${it.size} sleep stages with unknown codes ${it.map { s -> s.rawType }.toSet()}, counted as light") }
        if (main.isNotEmpty()) {
            val start = r.resolveTime(main.first().start, zone)
            val end = r.resolveTime(r.mainSleepEndFixed.takeIf { it > main.first().start } ?: main.last().stop + 1, zone)
            if (end <= start || end - start > 20 * 3600) {
                log("history: implausible sleep window $start..$end, skipped")
            } else {
                mainWindow = start to end
                val session = SleepSession(
                    start, end, DayKey.of(day), nap = false,
                    score = r.sleepScore.takeIf { it in 1..100 },
                    averageHeartRate = HeartRateSentinel.valid(r.averageHeartRate),
                    deepMinutes = r.deepSleepLength, lightMinutes = r.lightSleepLength,
                    remMinutes = r.remSleepLength.takeIf { r.isRemSupported }, awakeMinutes = r.wakeLength,
                    wakeCount = r.wakeCount,
                )
                dao.replaceSleepDay(day, nap = false, listOf(session), main.map { stage(it, start, r) })
            }
        }
        // The newest copy of a day replaces its naps, including when it has none: the strap can report a stretch
        // of broken night sleep as a nap and later fold it into the main sleep. The empty record the strap writes
        // at midnight doesn't claim nap support, so it leaves them alone.
        if (!r.isShortSleepSupported) return
        val stages = r.shortStages.map { stage(it, 0, r) }.filter { it.end > it.start }.sortedBy { it.start }
        val naps = mutableListOf<SleepSession>()
        val napStages = mutableListOf<SleepStage>()
        for ((start, end) in napWindows(r, stages)) {
            if (mainWindow != null && start < mainWindow.second && end > mainWindow.first) continue
            naps += SleepSession(start, end, DayKey.of(day), nap = true)
            napStages += stages.filter { it.start >= start && it.end <= end }.map { it.copy(sessionStart = start) }
        }
        dao.replaceSleepDay(day, nap = true, naps, napStages)
    }

    /** One window per nap: the strap's nap list when it gives one, otherwise runs of back-to-back nap stages. */
    private fun napWindows(r: SleepResult, stages: List<SleepStage>): List<Pair<Long, Long>> {
        val listed = r.shortSleeps.map { r.resolveTime(it.start, zone) to r.resolveTime(it.stop + 1, zone) }.filter { it.second > it.first }
        if (listed.isNotEmpty()) return listed
        val runs = mutableListOf<Pair<Long, Long>>()
        for (s in stages) {
            val last = runs.lastOrNull()
            if (last != null && s.start <= last.second) runs[runs.size - 1] = last.first to maxOf(last.second, s.end)
            else runs += s.start to s.end
        }
        return runs
    }

    /** A stage's stop is the last minute it covers (the next stage starts at stop + 1), so the end is a minute later. */
    private fun stage(s: org.openstrap.protocol.history.SleepStage, sessionStart: Long, r: SleepResult) = SleepStage(
        start = r.resolveTime(s.start, zone),
        end = r.resolveTime(s.stop + 1, zone),
        sessionStart = sessionStart,
        stage = when (s.type) {
            SleepStageType.AWAKE -> SleepStage.AWAKE
            SleepStageType.DEEP -> SleepStage.DEEP
            SleepStageType.REM -> SleepStage.REM
            SleepStageType.LIGHT, SleepStageType.UNKNOWN -> SleepStage.LIGHT
        },
    )

    /** Activity record size: the START size field counts records; then the 0x0028 feature blob, then a guess. */
    private fun recordSize(code: Int, payload: ByteArray, sizeField: Long): Int =
        if (code != HistoryDataType.ACTIVITY.code) 4 else ActivityLayout.recordSize(payload, sizeField, session.features)

    private fun dayStart(epoch: Long) = Days.start(epoch, zone)

    /** The day a record's moment falls on in the phone's zone, the day its row is stored under. */
    private fun dayOf(epoch: Long) = DayKey.dayOf(epoch, zone)

    companion object {
        private val HISTORY_CONTROL = java.util.UUID.fromString("00000004-0000-3512-2118-0009af100700")
        private val HISTORY_DATA = java.util.UUID.fromString("00000005-0000-3512-2118-0009af100700")
        private const val DEFAULT_LOOKBACK = 2L * 24 * 3600
        /**
         * As far back as the strap keeps history. A fetch from 60 days back on firmware 3.25.0.3 returned the whole
         * window for stress, skin temperature, extended activity and sleep, and each shorter type from its oldest
         * record (02 "Verified on hardware: retention"), so asking from before what a type holds is safe.
         */
        private const val HISTORY_LOOKBACK = 60L * 24 * 3600
        private const val DAY_SECONDS = 24 * 3600L

        /**
         * The newest stored key of the table a type fills ([store]), and how far before it to start. A [day] key is a
         * date ([DayKey]), read as that date's local midnight.
         */
        private class NewestKey(val sql: String, val before: Long = 0, val day: Boolean = false)

        private fun newest(table: String, column: String = "timestamp", before: Long = 0, where: String = "") =
            NewestKey("SELECT MAX($column) FROM $table$where", before)

        private fun newestDay(table: String) = NewestKey("SELECT MAX(day) FROM $table", DAY_SECONDS, day = true)

        /**
         * Types that share a table read its newest row, whichever type wrote it. A day key starts a day earlier: the
         * strap dates a day's record by its own time, which can fall before the local midnight the row is keyed by.
         */
        private val NEWEST_KEY: Map<HistoryDataType, NewestKey> = buildMap {
            put(HistoryDataType.ACTIVITY, newest("activity_minute"))
            put(HistoryDataType.TODAY_ACTIVITY_EXTENDED, newest("activity_extended"))
            for (t in listOf(HistoryDataType.HEART_RATE_MANUAL, HistoryDataType.SEC_HR, HistoryDataType.SEC_HR_V2)) {
                put(t, newest("heart_rate"))
            }
            for (t in listOf(HistoryDataType.STRESS_SINGLE, HistoryDataType.STRESS_ALL_DAY)) put(t, newest("stress"))
            put(HistoryDataType.SPO2_SINGLE, newest("spo2"))
            put(HistoryDataType.SPO2_OSA, newest("sleep_spo2"))
            for (t in listOf(HistoryDataType.SPO2_OSA_EVENT, HistoryDataType.SPO2_OSA_EVENT_ALT)) put(t, newest("osa_event"))
            put(HistoryDataType.OSA_RESULT, newest("osa_result", "belongTime"))
            put(HistoryDataType.HRV_RMSSD, newest("hrv"))
            put(HistoryDataType.HRV_SDNN, newest("hrv_sdnn"))
            put(HistoryDataType.PAI, newest("pai"))
            put(HistoryDataType.BODY_TEMPERATURE_ALL_DAY, newest("skin_temperature"))
            put(HistoryDataType.SLEEP_BREATH_RATE, newest("breath_rate"))
            put(
                HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT,
                newest("raw_record", where = " WHERE type = ${HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT.code}"),
            )
            put(HistoryDataType.RECOVERY_HEART_RATE, newest("recovery_hr", "workoutStart"))
            for (t in listOf(HistoryDataType.RESTING_HEART_RATE, HistoryDataType.REST_HR)) put(t, newestDay("resting_hr"))
            put(HistoryDataType.DAILY_MAX_EXERCISE_HR, newestDay("daily_max_hr"))
            put(HistoryDataType.SLEEP_RESULT, newestDay("sleep_session"))
        }
        /** Workouts are few and worth having: on first sync, take whatever the strap still holds. */
        private const val WORKOUT_LOOKBACK = 30L * 24 * 3600
        private val WORKOUT_TYPES = setOf(HistoryDataType.SPORT_SUMMARY, HistoryDataType.SPORT_DETAIL, HistoryDataType.SPORT_EFFECT)
        /** 2024-01-01: anything older comes from a strap whose clock was never set. */
        private const val MIN_VALID_EPOCH = 1_704_067_200L
        private const val FUTURE_SLACK = 120L
        private const val MAX_CHUNKS_PER_TYPE = 50
        private const val DUMP_CHUNKS = 5

        /**
         * The records of a chunk worth storing. Activity keeps only minutes that have ended: the strap sends the
         * minute it is still counting and stale slots after it, and a later sync replaced those with the real
         * minutes, so steps went up and then back down. Other types allow a strap clock a little ahead.
         */
        internal fun <R : HistoryRecord> keepable(type: HistoryDataType, records: List<R>, now: Long): List<R> =
            if (type in MINUTE_SERIES) {
                records.filter { !isBlank(it) && it.timestamp >= MIN_VALID_EPOCH && it.timestamp < minuteStart(now) }
            } else {
                records.filter { it.timestamp in MIN_VALID_EPOCH..now + FUTURE_SLACK }
            }

        /**
         * Where the cursor goes after a chunk starting at [headerTime], or null to leave it.
         *
         * A chunk with nothing usable (only gap slots, or only impossible times) still moves the cursor past it, or
         * the strap serves the same chunk on every sync. The chunk holds the earliest records after the cursor, so
         * skipping it loses nothing, and the cap at now (the current minute for the minute series) keeps a strap
         * clock that runs ahead from pushing the cursor into the future.
         *
         * Except recent blank minutes: the strap writes a minute a little after it ends and sends the slot blank
         * until then, even with written minutes after it. Moving past that slot lost a minute that was never fetched
         * (gaps of 2 to 10 minutes while heart rate had every one). So the cursor stops at the first blank minute
         * still inside [UNWRITTEN_WAIT], and the minutes after it are fetched again, replacing the same rows.
         */
        internal fun nextCursor(
            type: HistoryDataType,
            headerTime: Long,
            records: List<HistoryRecord>,
            parsedNext: Long?,
            kept: List<HistoryRecord>,
            now: Long,
        ): Long? {
            if (parsedNext == null) return null
            val next = firstRecentBlank(type, headerTime, records, parsedNext, now)
                ?: if (kept.isEmpty()) parsedNext else parsedNext.coerceAtMost(kept.maxOf { it.timestamp } + MINUTE_SECONDS)
            return next.coerceAtMost(latestCursor(type, now))
        }

        /**
         * The earliest blank minute of a minute-series chunk the strap may still write. Activity's parser skips the
         * blank slots before its first written minute (they run from [headerTime]), and stops at the first one after
         * it ([parsedNext]). Extended activity keeps its blank slots as 0xFF records.
         */
        private fun firstRecentBlank(
            type: HistoryDataType,
            headerTime: Long,
            records: List<HistoryRecord>,
            parsedNext: Long,
            now: Long,
        ): Long? {
            val blanks = when (type) {
                HistoryDataType.ACTIVITY -> {
                    val firstWritten = records.minOfOrNull { it.timestamp } ?: parsedNext
                    (headerTime until firstWritten step MINUTE_SECONDS).toList()
                }
                HistoryDataType.TODAY_ACTIVITY_EXTENDED -> records.filter(::isBlank).map { it.timestamp }
                else -> return null
            }
            return blanks.firstOrNull { it >= now - UNWRITTEN_WAIT }
        }

        /** An extended activity slot the strap hasn't written: all 0xFF, so its version byte is too. */
        private fun isBlank(r: HistoryRecord): Boolean = r is TodayActivityExtended && r.version == BLANK_VERSION

        private const val BLANK_VERSION = 0xFF

        /** One row a minute, written by the strap a little after the minute ends. */
        private val MINUTE_SERIES = setOf(HistoryDataType.ACTIVITY, HistoryDataType.TODAY_ACTIVITY_EXTENDED)

        /** How long the strap may take to write a minute; blank slots older than this are real gaps. */
        private const val UNWRITTEN_WAIT = 10 * 60L

        /** How far a type's cursor may move: the minute series stop at the current minute, so it is fetched once it ends. */
        internal fun latestCursor(type: HistoryDataType, now: Long): Long =
            if (type in MINUTE_SERIES) minuteStart(now) else now

        private const val MINUTE_SECONDS = 60L

        private fun minuteStart(epoch: Long) = epoch / MINUTE_SECONDS * MINUTE_SECONDS
    }
}

/**
 * [records] less those of strap workouts deleted in OpenStrap ([deleted]), which the strap keeps and re-sends: the
 * summary, HR stream and recovery HR by the workout's start (all keyed by its trackId), and the sport effect deleted
 * with it by its end.
 */
internal fun withoutDeletedWorkouts(records: List<HistoryRecord>, deleted: Collection<DeletedWorkout>): List<HistoryRecord> {
    if (deleted.isEmpty()) return records
    val starts = deleted.mapTo(HashSet()) { it.start }
    val effectEnds = deleted.mapNotNullTo(HashSet()) { it.effectEnd }
    return records.filter { r ->
        when (r) {
            is WorkoutSummary, is WorkoutDetail, is RecoveryHeartRate -> r.timestamp !in starts
            is SportEffect -> r.timestamp !in effectEnds
            else -> true
        }
    }
}

/** How long after a dismissed time the strap's effect for it may end: it writes the effect as the session closes. */
internal const val EFFECT_GRACE = 120L

/** Whether a sport effect ending at [end] falls inside one of the [dismissed] times, or up to [EFFECT_GRACE] after it. */
internal fun inDismissedTime(end: Long, dismissed: Collection<DismissedWorkout>): Boolean =
    dismissed.any { end >= it.start && end <= it.end + EFFECT_GRACE }

/** Whether one of the [workouts] (start to real end) runs to a sport effect ending at [end], so it owns it. */
internal fun ownedEffect(end: Long, workouts: Collection<LongRange>): Boolean =
    workouts.any { end > it.first && end <= it.last + EFFECT_GRACE }

/**
 * The deleted marker for a sport effect no workout owns, so a re-send is skipped ([withoutDeletedWorkouts]) and a
 * restore leaves it out. It has no workout, so its effect's end stands for the start: no strap workout starts the
 * second an effect ends.
 */
internal fun effectMarker(end: Long, now: Long) = DeletedWorkout(end, end, now)

/** A strap workout summary (type 5) as stored. The subtype is kept so auto-detected workouts can be told apart (codemap 05 §8). */
internal fun workoutRow(r: WorkoutSummary): Workout = Workout(
    r.trackId, r.trackId + (r.exerciseTime ?: r.totalTime ?: 0), r.sportType, r.exerciseTime ?: r.totalTime,
    r.calories, r.averageHeartRate, r.maxHeartRate, r.minHeartRate, r.aerobicTrainingEffect,
    r.anaerobicTrainingEffect, r.trainingLoad, r.vo2max, r.steps, subtype = r.subtype,
)

/** The kind of data a sync step fetches, as "Syncing heart rate (3/12)" names it. Any other type is "other data". */
internal fun syncKindName(t: HistoryDataType, strings: Strings = Strings.app): String =
    strings.get(SYNC_KIND_NAMES[t] ?: R.string.system_sync_kind_other)

/** Each named kind of data and the strap types it covers. */
private val SYNC_KIND_NAMES: Map<HistoryDataType, Int> = listOf(
    R.string.system_sync_kind_activity to listOf(HistoryDataType.ACTIVITY, HistoryDataType.TODAY_ACTIVITY_EXTENDED),
    R.string.system_sync_kind_sleep to listOf(HistoryDataType.SLEEP_RESULT),
    R.string.system_sync_kind_hrv to listOf(HistoryDataType.HRV_RMSSD, HistoryDataType.HRV_SDNN),
    R.string.system_sync_kind_resting_hr to listOf(HistoryDataType.REST_HR, HistoryDataType.RESTING_HEART_RATE),
    R.string.system_sync_kind_heart_rate to listOf(HistoryDataType.HEART_RATE_MANUAL, HistoryDataType.SEC_HR, HistoryDataType.SEC_HR_V2),
    R.string.system_sync_kind_peak_hr to listOf(HistoryDataType.DAILY_MAX_EXERCISE_HR),
    R.string.system_sync_kind_recovery_hr to listOf(HistoryDataType.RECOVERY_HEART_RATE),
    R.string.system_sync_kind_blood_oxygen to listOf(HistoryDataType.SPO2_SINGLE, HistoryDataType.SPO2_OSA),
    R.string.system_sync_kind_sleep_breathing to listOf(
        HistoryDataType.SPO2_OSA_EVENT, HistoryDataType.SPO2_OSA_EVENT_ALT, HistoryDataType.OSA_RESULT,
        HistoryDataType.SLEEP_RESPIRATORY_RATE_EVENT,
    ),
    R.string.system_sync_kind_breathing_rate to listOf(HistoryDataType.SLEEP_BREATH_RATE),
    R.string.system_sync_kind_stress to listOf(HistoryDataType.STRESS_SINGLE, HistoryDataType.STRESS_ALL_DAY),
    R.string.system_sync_kind_heart_points to listOf(HistoryDataType.PAI),
    R.string.system_sync_kind_skin_temperature to listOf(
        HistoryDataType.BODY_TEMPERATURE_ALL_DAY, HistoryDataType.BODY_TEMPERATURE_SINGLE, HistoryDataType.BODY_TEMPERATURE_80S,
        HistoryDataType.TEMPERATURE_LEGACY,
    ),
    R.string.system_sync_kind_workouts to listOf(HistoryDataType.SPORT_SUMMARY, HistoryDataType.SPORT_DETAIL, HistoryDataType.SPORT_EFFECT),
).flatMap { (id, types) -> types.map { it to id } }.toMap()
