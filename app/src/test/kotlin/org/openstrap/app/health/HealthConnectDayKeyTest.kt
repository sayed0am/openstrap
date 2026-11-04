package org.openstrap.app.health

import android.content.Context
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.DayKey
import org.openstrap.app.data.db.DailyCaloriesRow
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.reflect.KClass

/**
 * Health Connect and day keys as dates (item 17): a day's records keep the ids they had while days were local
 * midnights, a record read back takes its date from its own offset, and a resting heart rate written again after a
 * change of zone doesn't stay beside the one from before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HealthConnectDayKeyTest {
    @get:Rule val zoneRule = ZoneRule()

    private lateinit var db: OpenStrapDatabase
    private val dao get() = db.healthDao()
    private lateinit var exporter: HealthConnectExporter

    private val zones = listOf("Asia/Dubai", "Europe/London", "America/New_York", "Pacific/Auckland", "Asia/Kathmandu").map(ZoneId::of)
    private val dubai = ZoneId.of("Asia/Dubai")
    private val newYork = ZoneId.of("America/New_York")
    private val d = LocalDate.of(2026, 1, 15)

    /** A plain day, and the days London and New York change their clocks. */
    private val dates = listOf(d, LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 1))

    @Before fun open() {
        db = inMemoryDb()
        exporter = HealthConnectExporter(ApplicationProvider.getApplicationContext<Context>(), dao)
    }

    @After fun close() = db.close()

    private fun midnight(date: LocalDate, zone: ZoneId) = date.atStartOfDay(zone).toEpochSecond()

    @Test fun `a day's resting heart rate keeps its id, time and offset in every zone`() = runTest {
        dao.insertRestingHr(dates.map { RestingHeartRate(it, 55) })
        for (zone in zones) for (date in dates) {
            val m = midnight(date, zone)
            val r = exporter.restingHr(m, m, zone).single()
            assertEquals("$date in $zone", "openstrap:rhr:$m", r.metadata.clientRecordId)
            assertEquals(Instant.ofEpochSecond(m), r.time)
            assertEquals(zone.rules.getOffset(Instant.ofEpochSecond(m)), r.zoneOffset)
        }
    }

    @Test fun `a day's calorie total keeps its id in every zone`() {
        for (zone in zones) for (date in dates) {
            val row = DailyCaloriesRow(date, 0, 2200f, 500f, 1650f, 1440, false, 100f, 150f, List(24) { "1" }.joinToString(","))
            val m = midnight(date, zone)
            assertEquals("$date in $zone", "total:$m", CalorieRecords.dayTotals(listOf(row), zone, m + 3 * 86_400).single().id)
        }
    }

    private fun rhr(at: Long, offset: ZoneOffset?, bpm: Long = 55, id: String = "openstrap:rhr:$at", recordId: String = "") =
        RestingHeartRateRecord(
            time = Instant.ofEpochSecond(at), zoneOffset = offset, beatsPerMinute = bpm,
            metadata = Metadata.autoRecorded(device = Device(type = Device.TYPE_FITNESS_BAND), clientRecordId = id),
        ).also { if (recordId.isNotEmpty()) Metadata::class.java.getDeclaredField("id").apply { isAccessible = true }.set(it.metadata, recordId) }

    private suspend fun import(vararg records: Record) = exporter.importRange { type -> records.filter { type.isInstance(it) } }

    @Test fun `a resting heart rate written at Dubai's midnight reads back as that date in New York`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        val m = midnight(d, dubai)
        import(rhr(m, dubai.rules.getOffset(Instant.ofEpochSecond(m))))
        assertEquals(listOf(RestingHeartRate(d, 55)), dao.restingHrAll())
    }

    @Test fun `a resting heart rate without an offset takes the phone's zone`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        // 20:00 the day before in New York.
        import(rhr(midnight(d, ZoneOffset.UTC) + 3600, null))
        assertEquals(listOf(RestingHeartRate(d.minusDays(1), 55)), dao.restingHrAll())
    }

    @Test fun `a night in Dubai imported in New York is its morning's date there`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        val offset = ZoneOffset.ofHours(4)
        // 23:00 to 07:00 in Dubai, which ends at 22:00 the day before in New York.
        val end = midnight(d, dubai) + 7 * 3600
        import(
            SleepSessionRecord(
                startTime = Instant.ofEpochSecond(end - 8 * 3600), startZoneOffset = offset,
                endTime = Instant.ofEpochSecond(end), endZoneOffset = offset,
                metadata = Metadata.autoRecorded(Device(type = Device.TYPE_FITNESS_BAND)),
            ),
        )
        assertEquals(DayKey.of(d), dao.sleepSessionsOnce(0, Long.MAX_VALUE / 2).single().day)
    }

    /** Health Connect holding [held], each written by OpenStrap, where [readable] false refuses the read of its own. */
    private class FakeStore(held: List<Record>, val readable: Boolean = true) : RecordStore {
        val records = held.toMutableList()
        val deleted = mutableListOf<String>()

        override suspend fun insert(records: List<Record>) {
            this.records.removeAll { r -> records.any { it.metadata.clientRecordId == r.metadata.clientRecordId } }
            this.records += records
        }

        override suspend fun ownRecords(type: KClass<out Record>, range: TimeRangeFilter): List<Record>? =
            if (readable) records.filter { type.isInstance(it) } else null

        override suspend fun deleteIds(type: KClass<out Record>, ids: List<String>) {
            deleted += ids
            records.removeAll { it.metadata.id in ids }
        }

        override suspend fun deleteRange(type: KClass<out Record>, range: TimeRangeFilter) = error("not by range")
    }

    private fun offset(zone: ZoneId, at: Long) = zone.rules.getOffset(Instant.ofEpochSecond(at))

    @Test fun `after a flight the date's record from the old zone goes, and only that one`() = runTest {
        val dubaiD = midnight(d, dubai)
        val dubaiNext = midnight(d.plusDays(1), dubai)
        val store = FakeStore(
            listOf(
                rhr(dubaiD, offset(dubai, dubaiD), recordId = "old d"),
                // A date not written again stays.
                rhr(dubaiNext, offset(dubai, dubaiNext), recordId = "old next"),
                // Without an offset a record says no date, so it stays.
                rhr(dubaiD + 60, null, id = "openstrap:rhr:no-offset", recordId = "no offset"),
            ),
        )
        val nyD = midnight(d, newYork)
        val written = listOf(rhr(nyD, offset(newYork, nyD), bpm = 56, recordId = "new d"))
        store.insert(written)
        store.dropMovedRestingHr(written)
        assertEquals(listOf("old d"), store.deleted)
        assertEquals(setOf("old next", "no offset", "new d"), store.records.map { it.metadata.id }.toSet())
    }

    @Test fun `the same zone deletes nothing`() = runTest {
        val m = midnight(d, dubai)
        val store = FakeStore(listOf(rhr(m, offset(dubai, m), recordId = "a")))
        val written = listOf(rhr(m, offset(dubai, m), bpm = 57, recordId = "b"))
        store.insert(written)
        store.dropMovedRestingHr(written)
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun `without a read of its own records nothing is deleted`() = runTest {
        val m = midnight(d, dubai)
        val store = FakeStore(listOf(rhr(m, offset(dubai, m), recordId = "old")), readable = false)
        val nyD = midnight(d, newYork)
        store.dropMovedRestingHr(listOf(rhr(nyD, offset(newYork, nyD), recordId = "new")))
        assertTrue(store.deleted.isEmpty())
        assertEquals(listOf("old"), store.records.map { it.metadata.id })
    }
}
