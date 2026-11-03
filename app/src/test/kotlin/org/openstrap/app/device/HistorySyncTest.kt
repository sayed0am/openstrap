package org.openstrap.app.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.ble.GattException
import org.openstrap.app.ble.Notification
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.DeletedWorkout
import org.openstrap.app.data.db.DismissedWorkout
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.workout
import org.openstrap.protocol.ByteWriter
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.StrapLink
import org.openstrap.protocol.history.FetchCodec
import org.openstrap.protocol.history.GdspTime
import org.openstrap.protocol.history.HistoryDataType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * A strap that speaks the device side of GDSP over HUAMI(0004) and (0005) (04 §1): it answers the version query with
 * the types it holds, each START with the records at or after the cursor, a few per chunk, and every ACK.
 */
private class SimulatedStrap : HistoryGatt {
    override val connected = MutableStateFlow(true)
    private val out = MutableSharedFlow<Notification>(extraBufferCapacity = 4096)
    override val notifications = out

    /** Each type's records by code, as (timestamp, record bytes), oldest first. */
    val records = mutableMapOf<Int, List<Pair<Long, ByteArray>>>()
    /** Records per chunk for a type, 2 when not set. */
    val perChunk = mutableMapOf<Int, Int>()
    /** (type, cursor) of every START, in order. */
    val starts = mutableListOf<Pair<Int, Long>>()
    var onEnable: suspend () -> Unit = {}
    var onStart: suspend (Int) -> Unit = {}
    private var pending = ByteArray(0)

    override suspend fun enableNotifications(service: UUID, uuid: UUID) = onEnable()

    override suspend fun write(service: UUID, uuid: UUID, value: ByteArray, withResponse: Boolean) {
        // The sync's two listeners must be there before the first reply, or it is lost.
        out.subscriptionCount.first { it >= 2 }
        when (value[0].toInt()) {
            FetchCodec.OP_QUERY_VERSION -> {
                val w = ByteWriter().u8(0x10).u8(0).u8(1).u8(1).u8(1).u16(records.size)
                records.keys.forEach { w.u8(it).u8(1) }
                control(w.toByteArray())
            }
            FetchCodec.OP_START -> {
                val type = value[1].toInt() and 0xFF
                val since = GdspTime(
                    (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8),
                    value[4].toInt(), value[5].toInt(), value[6].toInt(), value[7].toInt(), value[8].toInt(), value[9].toInt(),
                ).toEpochSecond()
                starts += type to since
                onStart(type)
                val chunk = records[type].orEmpty().filter { it.first >= since }.take(perChunk[type] ?: 2)
                pending = chunk.fold(ByteArray(0)) { acc, r -> acc + r.second }
                val header = Instant.ofEpochSecond(chunk.firstOrNull()?.first ?: since).atZone(ZoneOffset.UTC)
                val w = ByteWriter().u8(0x10).u8(1).u8(1).u32(pending.size.toLong())
                    .u16(header.year).u8(header.monthValue).u8(header.dayOfMonth).u8(header.hour).u8(header.minute).u8(header.second).u8(0)
                control(w.u8(0).toByteArray())
            }
            FetchCodec.OP_TRANSFER -> {
                pending.toList().chunked(19).forEachIndexed { i, c -> out.emit(Notification(HISTORY_DATA, byteArrayOf(i.toByte()) + c.toByteArray())) }
                control(byteArrayOf(0x10, 2, 1))
            }
            FetchCodec.OP_ACK -> control(byteArrayOf(0x10, 3, 1))
        }
    }

    private suspend fun control(bytes: ByteArray) = out.emit(Notification(HISTORY_CONTROL, bytes))

    companion object {
        val HISTORY_CONTROL: UUID = UUID.fromString("00000004-0000-3512-2118-0009af100700")
        val HISTORY_DATA: UUID = UUID.fromString("00000005-0000-3512-2118-0009af100700")
    }
}

private class MemoryCursors(var onSet: suspend (String, Long) -> Unit = { _, _ -> }) : SyncCursors {
    val values = mutableMapOf<String, Long>()

    override suspend fun get(key: String) = values[key]

    override suspend fun set(key: String, epochSeconds: Long) {
        onSet(key, epochSeconds)
        values[key] = epochSeconds
    }
}

/** A session with no channel endpoints, so history control runs over HUAMI(0004), all of it on [SimulatedStrap]. */
private fun gattOnlySession() = ChannelSession(object : StrapLink {
    override val payloadMtu = 20
    override suspend fun writeControl(data: ByteArray) = Unit
    override suspend fun writeData(data: ByteArray) = Unit
    override val controlNotifications = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
    override val dataNotifications = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
})

/** HistorySync against a simulated strap and a real database (audit test 3, C2, C3 and P4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistorySyncTest {
    private lateinit var db: OpenStrapDatabase
    private val dao get() = db.healthDao()
    private val strap = SimulatedStrap()
    private val cursors = MemoryCursors()
    private val state = MutableStateFlow<SyncState>(SyncState.Idle)
    private val log = mutableListOf<String>()
    private val zone = ZoneId.systemDefault()

    /** Ten hours ago, on a whole minute: inside the first sync's two day lookback. */
    private val base = (System.currentTimeMillis() / 1000 - 10 * 3600) / 60 * 60

    @Before fun open() {
        db = inMemoryDb()
    }
    @After fun close() = db.close()

    private fun sync() = HistorySync(gattOnlySession(), strap, dao, cursors, state, { synchronized(log) { log += it } })

    private fun hrv(ts: Long, rmssd: Int) = ts to ByteWriter().u32(ts).u8(0).u8(rmssd).toByteArray()
    private fun sdnn(ts: Long, centiMs: Long) = ts to ByteWriter().u32(ts).u8(0).u32(centiMs).toByteArray()

    /** A version 1 PAI record: a PAI row, and the resting HR it carries for a day without a real one. */
    private fun pai(ts: Long, daily: Float, total: Float, restHr: Int) = ts to ByteBuffer.allocate(69).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(1); putInt(ts.toInt()); putInt(0)
        repeat(10) { putFloat(0f) }
        repeat(3) { putShort(0) }
        putFloat(daily); putFloat(total); putShort(restHr.toShort()); putShort(180); put(0); put(0)
    }.array()

    private fun table(sql: String): List<List<Any?>> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.getType(it) == android.database.Cursor.FIELD_TYPE_FLOAT) c.getDouble(it) else c.getLong(it) }) }
    }

    /** The stored key of the day [ts] falls on here: its date's UTC midnight. */
    private fun dayKey(ts: Long) = org.openstrap.app.data.DayKey.of(Instant.ofEpochSecond(ts).atZone(zone).toLocalDate())

    private fun key(t: HistoryDataType) = "gdsp_${t.code}"

    private fun seedStrap() {
        strap.records[HistoryDataType.HRV_RMSSD.code] = listOf(hrv(base, 41), hrv(base + 300, 0), hrv(base + 600, 55), hrv(base + 900, 62))
        strap.records[HistoryDataType.HRV_SDNN.code] = listOf(sdnn(base, 4_250), sdnn(base + 60, 90_000))
        strap.records[HistoryDataType.PAI.code] = listOf(pai(base, 12.5f, 80f, 52))
        strap.perChunk[HistoryDataType.PAI.code] = 1
    }

    /** In the test's own coroutine, so the test waits through Room's threads for the sync to end. */
    private suspend fun runSync() = sync().run()

    @Test fun `records land in the right rows`() = runTest {
        seedStrap()
        runSync()

        assertTrue(state.value is SyncState.Done)
        // A zero RMSSD is no reading.
        assertEquals(listOf(listOf(base, 41L), listOf(base + 600, 55L), listOf(base + 900, 62L)), table("SELECT timestamp, milliseconds FROM hrv ORDER BY timestamp"))
        // 0.01 ms on the wire, and 900 ms is out of range.
        assertEquals(listOf(listOf(base, 42.5)), table("SELECT timestamp, milliseconds FROM hrv_sdnn"))
        assertEquals(listOf(listOf(base, 12.5, 80.0, 52L)), table("SELECT timestamp, daily, total, restingHeartRate FROM pai"))
        assertEquals(listOf(listOf(dayKey(base), 52L)), table("SELECT day, bpm FROM resting_hr"))
        // Each cursor ends past its last record: HRV went in two chunks and the second START started after the first.
        assertEquals(base + 901, cursors.values[key(HistoryDataType.HRV_RMSSD)])
        assertTrue(strap.starts.contains(HistoryDataType.HRV_RMSSD.code to base + 301))
    }

    @Test fun `the cursor moves only once the chunk's rows are in`() = runTest {
        seedStrap()
        val seen = mutableListOf<Pair<Long, Int>>()
        cursors.onSet = { k, v -> if (k == key(HistoryDataType.HRV_RMSSD)) seen += v to table("SELECT * FROM hrv WHERE timestamp < $v").size }
        runSync()

        // Every record before each cursor was stored when it moved.
        assertEquals(listOf(base + 301 to 1, base + 901 to 3), seen)
    }

    @Test fun `a chunk that fails to store keeps none of its rows and leaves the cursor`() = runTest {
        seedStrap()
        // The PAI chunk writes a resting HR row, then a pai row: the second fails.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_pai BEFORE INSERT ON pai BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        runSync()

        assertEquals(emptyList<Any>(), table("SELECT * FROM resting_hr"))
        assertEquals(null, cursors.values[key(HistoryDataType.PAI)])
        // The other types went on.
        assertEquals(3, table("SELECT * FROM hrv").size)
        assertTrue(state.value is SyncState.Done)
    }

    @Test fun `a kill after the commit and before the cursor stores the chunk again without duplicates`() = runTest {
        seedStrap()
        // The first cursor write dies with the process: the rows are in, the cursor is not.
        var killed = false
        cursors.onSet = { k, _ -> if (!killed && k == key(HistoryDataType.HRV_RMSSD)) { killed = true; error("process killed") } }
        runSync()
        val afterKill = table("SELECT timestamp, milliseconds FROM hrv ORDER BY timestamp")
        assertEquals(listOf(listOf(base, 41L)), afterKill)
        assertEquals(null, cursors.values[key(HistoryDataType.HRV_RMSSD)])

        runSync()
        val once = listOf(
            table("SELECT timestamp, milliseconds FROM hrv ORDER BY timestamp"), table("SELECT * FROM hrv_sdnn"),
            table("SELECT * FROM pai"), table("SELECT * FROM resting_hr"),
        )
        assertEquals(3, once[0].size)

        // A full re-sync of the same data from scratch changes nothing.
        cursors.values.clear()
        runSync()
        assertEquals(once, listOf(
            table("SELECT timestamp, milliseconds FROM hrv ORDER BY timestamp"), table("SELECT * FROM hrv_sdnn"),
            table("SELECT * FROM pai"), table("SELECT * FROM resting_hr"),
        ))
        assertTrue(state.value is SyncState.Done)
    }

    @Test fun `a GATT timeout ends the sync as Failed, not Running`() = runTest {
        seedStrap()
        // withTimeout throws TimeoutCancellationException, a CancellationException, while the sync is still active.
        strap.onEnable = { withTimeout(10) { awaitCancellation() } }
        runSync()

        val s = state.value
        assertTrue("$s", s is SyncState.Failed)
    }

    @Test fun `a GATT timeout as a plain exception ends the sync as Failed`() = runTest {
        seedStrap()
        strap.onEnable = { throw GattException("GATT operation timed out") }
        runSync()

        assertEquals(SyncState.Failed("GATT operation timed out"), state.value)
    }

    @Test fun `a timeout on one type while the link is up skips that type only`() = runTest {
        seedStrap()
        strap.onStart = { type -> if (type == HistoryDataType.HRV_RMSSD.code) withTimeout(10) { awaitCancellation() } }
        runSync()

        assertTrue("${state.value}", state.value is SyncState.Done)
        assertEquals(emptyList<Any>(), table("SELECT * FROM hrv"))
        assertEquals(1, table("SELECT * FROM pai").size)
    }

    @Test fun `a timeout after the link dropped fails the sync`() = runTest {
        seedStrap()
        strap.onStart = { strap.connected.value = false; withTimeout(10) { awaitCancellation() } }
        runSync()

        assertEquals(SyncState.Failed("The link to the strap dropped."), state.value)
    }

    @Test fun `fast-charge mode stops the sync and the log keeps the strap's reason`() = runTest {
        seedStrap()
        strap.onStart = { throw org.openstrap.protocol.history.FastRechargeModeException() }
        runSync()

        assertEquals(SyncState.Failed("The strap is in fast-charge mode. Sync again once it's off."), state.value)
        assertTrue(log.toString(), log.any { it.startsWith("history: strap is in fast-charge mode") && "The strap is in fast-charge mode" in it })
    }

    @Test fun `any failure on one type skips that type only, not just an Exception`() = runTest {
        seedStrap()
        // An Error (a parser's TODO, say) escaped the catch for Exception and with it the whole sync.
        strap.onStart = { type -> if (type == HistoryDataType.HRV_RMSSD.code) throw NotImplementedError("no parser") }
        val thrown = runCatching { runSync() }.exceptionOrNull()

        assertEquals(null, thrown)
        assertTrue("${state.value}", state.value is SyncState.Done)
        assertEquals(1, table("SELECT * FROM pai").size)
        assertTrue(log.toString(), log.any { it.startsWith("history HRV_RMSSD") && "no parser" in it })
    }

    @Test fun `a cancelled sync does not stay Running`() = runTest {
        seedStrap()
        val started = CompletableDeferred<Unit>()
        strap.onStart = { started.complete(Unit); awaitCancellation() }
        val job = launch { sync().run() }
        started.await()
        assertTrue(state.value is SyncState.Running)

        job.cancelAndJoin()
        assertTrue("${state.value}", state.value is SyncState.Failed)
    }

    @Test fun `a database error while repairing earlier syncs fails the sync instead of crashing`() = runTest {
        seedStrap()
        db.openHelper.writableDatabase.execSQL("DROP TABLE activity_minute")
        val thrown = runCatching { sync().run() }.exceptionOrNull()

        assertEquals(null, thrown)
        assertTrue("${state.value}", state.value is SyncState.Failed)
    }

    private val day = 24 * 3600L
    private val now get() = System.currentTimeMillis() / 1000

    @Test fun `a type with no cursor fetches all the strap still holds`() = runTest {
        // Forget strap cleared the cursors, and the strap was away for longer than two days.
        strap.records[HistoryDataType.HRV_RMSSD.code] = listOf(hrv(base - 10 * day, 40), hrv(base - 5 * day, 50))
        runSync()

        assertEquals(listOf(base - 10 * day, base - 5 * day), table("SELECT timestamp FROM hrv ORDER BY timestamp").map { it[0] })
        // From as far back as the strap keeps history: 60 days.
        val first = strap.starts.first { it.first == HistoryDataType.HRV_RMSSD.code }.second
        assertTrue("$first", first in now - 60 * day - 60..now - 60 * day)
    }

    @Test fun `a type with no cursor starts at its newest stored row`() = runTest {
        dao.insertHrv(listOf(HrvSample(base - 3 * day, 45)))
        strap.records[HistoryDataType.HRV_RMSSD.code] = listOf(hrv(base - 5 * day, 50), hrv(base - 3 * day, 45), hrv(base - day, 60))
        runSync()

        assertEquals(base - 3 * day, strap.starts.first { it.first == HistoryDataType.HRV_RMSSD.code }.second)
        assertEquals(listOf(base - 3 * day, base - day), table("SELECT timestamp FROM hrv ORDER BY timestamp").map { it[0] })
    }

    @Test fun `a day table with no cursor starts a day before its newest date's local midnight`() = runTest {
        // Away from UTC, where a date's key and its local midnight differ.
        val saved = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kathmandu"))
        try {
            val kathmandu = ZoneId.of("Asia/Kathmandu")
            val date = Instant.ofEpochSecond(base - 3 * day).atZone(kathmandu).toLocalDate()
            dao.insertRestingHr(listOf(org.openstrap.app.data.db.RestingHeartRate(date, 52)))
            strap.records[HistoryDataType.RESTING_HEART_RATE.code] = emptyList()
            runSync()
            val start = strap.starts.first { it.first == HistoryDataType.RESTING_HEART_RATE.code }.second
            assertEquals(date.atStartOfDay(kathmandu).toEpochSecond() - day, start)
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test fun `activity after now stays when the phone's clock went back`() = runTest {
        val ahead = (now + day) / 60 * 60
        // Before 2024: a strap whose clock was never set. That still goes.
        dao.insertActivity(listOf(ActivityMinute(ahead, 40, 10, 1, 90), ActivityMinute(ahead + 60, 35, 10, 1, 92), ActivityMinute(600, 10, 1, 1, 80)))
        runSync()

        assertEquals(listOf(ahead, ahead + 60), table("SELECT timestamp FROM activity_minute ORDER BY timestamp").map { it[0] })
    }

    /** A sport effect (type 59) ending at [end], with a recovery time: protobuf field 26 is the end, 11 the recovery. */
    private fun effect(end: Long) = end to (byteArrayOf(0xD0.toByte(), 0x01) + varint(end) + byteArrayOf(0x58) + varint(300))

    private fun varint(v: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var x = v
        while (x >= 0x80) { out += ((x and 0x7F) or 0x80).toByte(); x = x ushr 7 }
        out += x.toByte()
        return out.toByteArray()
    }

    private fun effectEnds() = table("SELECT `end` FROM sport_effect ORDER BY `end`").map { it[0] }

    @Test fun `an effect over a workout ended under a minute is left out and stays out`() = runTest {
        // A phone workout ended after 30 s: deleted, its time dismissed. The strap sent its effect but no workout.
        dao.insertDismissedWorkouts(listOf(DismissedWorkout(base, base + 30)))
        strap.records[HistoryDataType.SPORT_EFFECT.code] = listOf(effect(base + 57))
        strap.perChunk[HistoryDataType.SPORT_EFFECT.code] = 1
        runSync()

        assertEquals(emptyList<Long>(), effectEnds())
        assertEquals(listOf(DeletedWorkout(base + 57, base + 57, dao.deletedWorkoutsAll().single().deletedAt)), dao.deletedWorkoutsAll())

        // The strap sends it again after the cursor goes back.
        cursors.values.clear()
        runSync()
        assertEquals(emptyList<Long>(), effectEnds())
    }

    @Test fun `an effect a workout runs to is kept, and one far from a dismissed time`() = runTest {
        dao.insertDismissedWorkouts(listOf(DismissedWorkout(base, base + 30)))
        // A strap session that began earlier and ended as the short one did owns the effect.
        dao.insertWorkouts(listOf(workout(start = base - 1800, end = base + 50)))
        strap.records[HistoryDataType.SPORT_EFFECT.code] = listOf(effect(base + 57))
        strap.perChunk[HistoryDataType.SPORT_EFFECT.code] = 1
        runSync()
        assertEquals(listOf(base + 57), effectEnds())

        // Over three minutes after the dismissed time: some other workout's.
        strap.records[HistoryDataType.SPORT_EFFECT.code] = listOf(effect(base + 30 + 121))
        runSync()
        assertEquals(listOf(base + 57, base + 151), effectEnds())
        assertEquals(emptyList<DeletedWorkout>(), dao.deletedWorkoutsAll())
    }

    @Test fun `an effect stored before over no workout goes at the next sync`() = runTest {
        dao.insertDismissedWorkouts(listOf(DismissedWorkout(base, base + 30)))
        dao.insertSportEffects(listOf(SportEffectRow(base + 57, 300, null, null, null, null, null, null, null)))
        runSync()

        assertEquals(emptyList<Long>(), effectEnds())
        assertEquals(listOf(base + 57), dao.deletedWorkoutsAll().map { it.effectEnd })
    }
}
