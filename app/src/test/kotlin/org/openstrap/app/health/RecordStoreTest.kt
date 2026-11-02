package org.openstrap.app.health

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * Health Connect replaces ([RecordStore.write]) put the new records in before the old ones go, so a failed insert
 * leaves what was there. Then only OpenStrap's records not written again are removed.
 */
class RecordStoreTest {
    private val range = TimeRangeFilter.between(Instant.ofEpochSecond(0), Instant.ofEpochSecond(100_000))

    private fun sleep(start: Long, id: String, recordId: String = "") = SleepSessionRecord(
        startTime = Instant.ofEpochSecond(start), startZoneOffset = ZoneOffset.UTC,
        endTime = Instant.ofEpochSecond(start + 3600), endZoneOffset = ZoneOffset.UTC,
        metadata = Metadata.autoRecorded(device = androidx.health.connect.client.records.metadata.Device(type = 0), clientRecordId = id),
    ).let { if (recordId.isEmpty()) it else it.withId(recordId) }

    /** A copy of [this] as Health Connect hands it back, with its record id. */
    private fun SleepSessionRecord.withId(recordId: String): SleepSessionRecord {
        val f = Metadata::class.java.getDeclaredField("id").apply { isAccessible = true }
        f.set(metadata, recordId)
        return this
    }

    /** Health Connect holding [held], each written by OpenStrap, where [readable] false refuses the read of its own. */
    private inner class FakeStore(held: List<Record>, val readable: Boolean = true, val failInsert: Boolean = false) : RecordStore {
        val records = held.toMutableList()
        val calls = mutableListOf<String>()

        override suspend fun insert(records: List<Record>) {
            calls += "insert"
            if (failInsert) error("Health Connect said no")
            this.records.removeAll { r -> records.any { it.metadata.clientRecordId == r.metadata.clientRecordId } }
            this.records += records
        }

        override suspend fun ownRecords(type: KClass<out Record>, range: TimeRangeFilter): List<Record>? =
            if (readable) records.filter { type.isInstance(it) } else null

        override suspend fun deleteIds(type: KClass<out Record>, ids: List<String>) {
            calls += "deleteIds"
            records.removeAll { it.metadata.id in ids }
        }

        override suspend fun deleteRange(type: KClass<out Record>, range: TimeRangeFilter) {
            calls += "deleteRange"
            records.removeAll { type.isInstance(it) }
        }
    }

    @Test fun `new records go in, then only stale ones go`() = runTest {
        val store = FakeStore(listOf(sleep(1000, "openstrap:sleep:1000", "a"), sleep(9000, "openstrap:sleep:9000", "b")))
        // The nap at 9000 was folded into the night: only the night is written again.
        store.write(listOf(sleep(1000, "openstrap:sleep:1000")), listOf(SleepSessionRecord::class to range))
        assertEquals(listOf("insert", "deleteIds"), store.calls)
        assertEquals(listOf("openstrap:sleep:1000"), store.records.map { it.metadata.clientRecordId })
    }

    @Test fun `a failed insert leaves the old records`() = runTest {
        val old = listOf(sleep(1000, "openstrap:sleep:1000", "a"), sleep(9000, "openstrap:sleep:9000", "b"))
        val store = FakeStore(old, failInsert = true)
        val failure = runCatching { store.write(listOf(sleep(1000, "openstrap:sleep:1000")), listOf(SleepSessionRecord::class to range)) }
        assertTrue(failure.isFailure)
        assertEquals(listOf("insert"), store.calls)
        assertEquals(old, store.records)
    }

    @Test fun `without a read of its own records the range is cleared first, as before`() = runTest {
        val store = FakeStore(listOf(sleep(9000, "openstrap:sleep:9000", "b")), readable = false)
        store.write(listOf(sleep(1000, "openstrap:sleep:1000")), listOf(SleepSessionRecord::class to range))
        assertEquals(listOf("deleteRange", "insert"), store.calls)
        assertEquals(listOf("openstrap:sleep:1000"), store.records.map { it.metadata.clientRecordId })
    }
}
