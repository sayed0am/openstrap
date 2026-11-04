package org.openstrap.protocol.history

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.protocol.ByteReader
import org.openstrap.protocol.ByteWriter
import org.openstrap.protocol.crypto.crc32
import java.time.ZoneOffset

/**
 * A simulated strap speaking the device side of GDSP (04 §1): START replies with a size and header time,
 * indexed data packets on 0005, `10 02 st crc32`, and ACKs. It serves one recorded workout (types 5 and 6).
 */
private class SimulatedGdspStrap(private val workouts: Map<Long, Pair<ByteArray, ByteArray>>) {
    val control = Channel<GdspMessage>(Channel.UNLIMITED)
    val data = Channel<ByteArray>(Channel.UNLIMITED)

    /** (type, since epoch) of every START, in order. */
    val starts = mutableListOf<Pair<Int, Long>>()
    val acks = mutableListOf<Int?>()
    var fastRecharge = false
    /** Number of STARTs to leave unanswered. */
    var silentStarts = 0
    /** Number of STARTs to refuse with INVALID_STATE, as when an earlier START is still open. */
    var invalidStateStarts = 0
    /** Pushed `11 <type>` before the next START reply. */
    var pushBeforeStart: Int? = null
    private var pending: ByteArray? = null

    suspend fun send(bytes: ByteArray) {
        when (bytes[0].toInt()) {
            FetchCodec.OP_START -> {
                val type = bytes[1].toInt() and 0xFF
                val since = GdspTime.read(ByteReader(bytes, 2)).toEpochSecond()
                starts += type to since
                pushBeforeStart?.let { pushBeforeStart = null; reply(byteArrayOf(0x11, it.toByte())) }
                if (fastRecharge) return reply(byteArrayOf(0x10, 1, GdspStatus.FAST_RECHARGE_MODE.code.toByte()))
                if (silentStarts > 0) { silentStarts--; return }
                if (invalidStateStarts > 0) { invalidStateStarts--; return reply(byteArrayOf(0x10, 1, GdspStatus.INVALID_STATE.code.toByte())) }
                val track = workouts.keys.sorted().firstOrNull { it >= since }
                val payload = track?.let { workouts.getValue(it).let { (s, d) -> if (type == 5) s else if (type == 6) d else null } }
                pending = payload
                val w = ByteWriter().u8(0x10).u8(1).u8(1).u32((payload?.size ?: 0).toLong())
                GdspTime.of(track ?: since, ZoneOffset.UTC).writeTo(w)
                reply(w.u8(0).toByteArray())
            }
            FetchCodec.OP_TRANSFER -> {
                val p = pending ?: ByteArray(0)
                p.toList().chunked(19).forEachIndexed { i, c -> data.send(byteArrayOf(i.toByte()) + c.toByteArray()) }
                reply(ByteWriter().u8(0x10).u8(2).u8(1).u32(crc32(p)).toByteArray())
            }
            FetchCodec.OP_ACK -> {
                acks += bytes.getOrNull(1)?.toInt()
                pending = null
                reply(byteArrayOf(0x10, 3, 1))
            }
        }
    }

    private suspend fun reply(bytes: ByteArray) = control.send(FetchCodec.parse(bytes))
}

class GdspClientTest {
    private val trackId = 1_792_141_453L
    private val zone = ZoneOffset.UTC
    private fun fixture(name: String) = javaClass.getResourceAsStream("/workout/$name")!!.readBytes()

    private fun strap() = SimulatedGdspStrap(mapOf(trackId to (fixture("summary_5.bin") to fixture("detail_6.bin"))))

    @Test
    fun detailFollowsSummaryWithTheSameCursor() = runTest {
        val strap = strap()
        val client = GdspClient(strap::send, strap.control, strap.data)
        val since = trackId - 3600

        val w = client.fetchWorkout(since, zone) as WorkoutFetch.Workout
        assertEquals(trackId, w.summary.trackId)
        assertEquals(223, w.summary.sportType)
        val detail = w.detail!!
        assertEquals(trackId, detail.timestamp)
        assertEquals(1307, detail.heartRate.size)
        assertEquals(trackId + 1, w.nextCursor)
        // Type 6 is asked from the summary's cursor, not from a cursor of its own; both are acknowledged.
        assertEquals(listOf(5 to since, 6 to since), strap.starts)
        assertEquals(listOf<Int?>(1, 1), strap.acks)

        // The next round starts after the workout and finds nothing (closed with an ACK, no detail START).
        assertEquals(WorkoutFetch.None, client.fetchWorkout(w.nextCursor, zone))
        assertEquals(5 to trackId + 1, strap.starts.last())
        assertEquals(3, strap.acks.size)
    }

    @Test
    fun fastRechargeModeStopsTheFetch() = runTest {
        val strap = strap().apply { fastRecharge = true }
        val client = GdspClient(strap::send, strap.control, strap.data)
        val e = runCatching { client.fetchWorkout(trackId, zone) }.exceptionOrNull()
        assertTrue("got $e", e is FastRechargeModeException)
        assertTrue(runCatching { client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone) }.exceptionOrNull() is FastRechargeModeException)
        // A refusal owes no ACK.
        assertTrue(strap.acks.isEmpty())
    }

    @Test
    fun dataAvailablePushIsReported() = runTest {
        val strap = strap().apply { pushBeforeStart = 5 }
        val pushes = mutableListOf<GdspMessage.DataAvailable>()
        val client = GdspClient(strap::send, strap.control, strap.data, onDataAvailable = { pushes += it })
        assertTrue(client.fetchWorkout(trackId, zone) is WorkoutFetch.Workout)
        assertEquals(listOf(5, 6, 79), pushes.single().typesToSync)
    }

    @Test
    fun aFailedVersionQueryIsLoggedAndReadsAsNone() = runTest {
        val strap = strap()
        val logs = mutableListOf<String>()
        val client = GdspClient({ throw IllegalStateException("link lost") }, strap.control, strap.data, log = { logs += it })
        assertNull(client.queryVersion())
        assertTrue(logs.toString(), logs.any { "version query failed" in it && "link lost" in it })
    }

    @Test
    fun anUnansweredStartIsLogged() = runTest {
        val strap = strap().apply { silentStarts = 1 }
        val logs = mutableListOf<String>()
        val client = GdspClient(strap::send, strap.control, strap.data, log = { logs += it })
        assertTrue(client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone) is GdspFetch.Failed)
        assertTrue(logs.toString(), logs.any { it.startsWith("history: start ${HistoryDataType.ACTIVITY.code}: ") && "no reply" in it })
    }

    @Test
    fun unansweredStartFailsTheTypeAndIsClosed() = runTest {
        val strap = strap().apply { silentStarts = 1 }
        val client = GdspClient(strap::send, strap.control, strap.data)
        // A timeout must not surface as a cancellation, which would abort the whole sync.
        assertTrue(client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone) is GdspFetch.Failed)
        assertEquals(listOf<Int?>(GdspStatus.TIMEOUT.code), strap.acks)
        // The next type still works.
        assertEquals(GdspFetch.Empty, client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone))
    }

    @Test
    fun invalidStateClosesTheOpenFetchAndRetriesOnce() = runTest {
        val strap = strap().apply { invalidStateStarts = 1 }
        val client = GdspClient(strap::send, strap.control, strap.data)
        assertEquals(GdspFetch.Empty, client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone))
        assertEquals(2, strap.starts.size)
        assertEquals(listOf<Int?>(GdspStatus.CANCELLED_BY_APP.code, 1), strap.acks)

        strap.invalidStateStarts = 2
        val f = client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone)
        assertEquals(GdspStatus.INVALID_STATE, (f as GdspFetch.Refused).status)
    }

    @Test
    fun emptyStartIsAcknowledged() = runTest {
        val strap = strap()
        val client = GdspClient(strap::send, strap.control, strap.data)
        assertEquals(GdspFetch.Empty, client.fetch(HistoryDataType.ACTIVITY.code, trackId, zone))
        assertEquals(listOf<Int?>(1), strap.acks)
    }
}
