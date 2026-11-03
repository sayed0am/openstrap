package org.openstrap.app.health

import android.content.Context
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.SleepEdit
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/**
 * What goes to Health Connect and back against a real database: heart rate written a whole hour at a time, and sleep
 * read back that the phone has at the user's times or deleted left out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HealthConnectRoundTripTest {
    private lateinit var db: OpenStrapDatabase
    private val dao get() = db.healthDao()
    private lateinit var exporter: HealthConnectExporter
    private val h = 3600L

    @Before fun open() {
        db = inMemoryDb()
        exporter = HealthConnectExporter(ApplicationProvider.getApplicationContext<Context>(), dao)
    }

    @After fun close() = db.close()

    @Test fun `a range starting mid hour writes that hour whole`() = runTest {
        val at = listOf(100 * h + 300, 100 * h + 1200, 100 * h + 3000, 101 * h + 600, 101 * h + 2400)
        dao.insertHeartRateIfMissing(at.map { HeartRateSample(it, 70) })
        // From 10:30 to 11:15, as an auto export two days back from now would start.
        val records = exporter.heartRate(100 * h + 1800, 101 * h + 900).map { it as HeartRateRecord }
        assertEquals(listOf("openstrap:hr:${100 * h}", "openstrap:hr:${101 * h}"), records.map { it.metadata.clientRecordId })
        assertEquals(at, records.flatMap { r -> r.samples.map { it.time.epochSecond } })
        assertEquals(Instant.ofEpochSecond(100 * h), records.first().startTime)
    }

    private val strapNight = SleepSession(100 * h, 108 * h, day = 0, score = 80)

    private fun sleep(start: Long, end: Long, strapStart: Long?, stages: List<SleepSessionRecord.Stage> = emptyList()) = SleepSessionRecord(
        startTime = Instant.ofEpochSecond(start), startZoneOffset = ZoneOffset.UTC,
        endTime = Instant.ofEpochSecond(end), endZoneOffset = ZoneOffset.UTC,
        stages = stages,
        metadata = strapStart?.let { Metadata.autoRecorded(device = band, clientRecordId = "openstrap:sleep:$it") } ?: Metadata.autoRecorded(band),
    )

    private val band = Device(type = Device.TYPE_FITNESS_BAND)

    /** Imports [records] as Health Connect would hand them back, with nothing else in it. */
    private suspend fun import(vararg records: Record) = exporter.importRange { type -> records.filter { type.isInstance(it) } }

    private suspend fun sessions() = dao.sleepSessionsOnce(0, 1000 * h)

    @Test fun `a night moved in OpenStrap doesn't come back as a second night`() = runTest {
        dao.insertSleepSessions(listOf(strapNight))
        dao.upsertSleepEdit(SleepEdit(strapNight.start, nap = false, start = 101 * h, end = 109 * h, editedAt = 1))
        // Exported at the user's times, under the strap's start.
        val stage = SleepSessionRecord.Stage(Instant.ofEpochSecond(101 * h), Instant.ofEpochSecond(109 * h), SleepSessionRecord.STAGE_TYPE_LIGHT)
        import(sleep(101 * h, 109 * h, strapNight.start, listOf(stage)))
        assertEquals(listOf(strapNight), sessions())
        assertTrue(dao.sleepStagesOnce(101 * h).isEmpty())
    }

    @Test fun `a sleep overlapping the phone's at either times stays out, whatever its id`() = runTest {
        dao.insertSleepSessions(listOf(strapNight))
        dao.upsertSleepEdit(SleepEdit(strapNight.start, nap = false, start = 102 * h, end = 110 * h, editedAt = 1))
        // Overlaps the strap's times only, then the user's only.
        import(sleep(99 * h, 101 * h, null), sleep(108 * h + 1800, 111 * h, null))
        assertEquals(listOf(strapNight), sessions())
    }

    @Test fun `a sleep the user deleted stays out after its session has gone for good`() = runTest {
        // The marker a delete leaves once its session is removed ([SleepEdits.marker]).
        dao.upsertSleepEdit(SleepEdit(strapNight.start, nap = false, deleted = true, editedAt = 1))
        import(sleep(101 * h, 109 * h, strapNight.start), sleep(100 * h + 300, 108 * h, null))
        assertTrue(sessions().isEmpty())
    }

    @Test fun `a sleep the user deleted and moved stays out while its session is kept`() = runTest {
        dao.insertSleepSessions(listOf(strapNight))
        dao.upsertSleepEdit(SleepEdit(strapNight.start, nap = false, start = 101 * h, end = 109 * h, deleted = true, editedAt = 1))
        import(sleep(101 * h, 109 * h, strapNight.start))
        assertEquals(listOf(strapNight), sessions())
    }

    @Test fun `a night the phone doesn't have comes in with its stages`() = runTest {
        dao.insertSleepSessions(listOf(strapNight))
        val stage = SleepSessionRecord.Stage(Instant.ofEpochSecond(124 * h), Instant.ofEpochSecond(130 * h), SleepSessionRecord.STAGE_TYPE_DEEP)
        import(sleep(124 * h, 132 * h, 124 * h, listOf(stage)))
        assertEquals(listOf(100 * h, 124 * h), sessions().map { it.start })
        assertEquals(listOf(124 * h to 130 * h), dao.sleepStagesOnce(124 * h).map { it.start to it.end })
    }

    @Test fun `the strap's start is read from an exported sleep's id`() {
        assertEquals(360_000L, SleepImport.strapStart("openstrap:sleep:360000"))
        assertEquals(null, SleepImport.strapStart("openstrap:hr:360000"))
        assertEquals(null, SleepImport.strapStart(null))
    }
}
