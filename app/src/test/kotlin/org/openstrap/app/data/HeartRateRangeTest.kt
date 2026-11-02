package org.openstrap.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** One range of believed heart rates (D9): 30 to 230 bpm in the queries, the summaries, recovery and a workout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HeartRateRangeTest {
    @get:Rule val zoneRule = ZoneRule(ZoneOffset.UTC)

    private val db = inMemoryDb()
    private val dao = db.healthDao()

    @After fun close() = db.close()

    @Test fun `the edges count everywhere and one past them nowhere`() {
        for (bpm in listOf(29, 30, 230, 231)) {
            val inRange = bpm in 30..230
            assertEquals("workout $bpm", inRange, WorkoutRecorder.isValid(bpm))
            assertEquals("recovery $bpm", inRange, bpm in Recovery.HR_MIN_BPM..Recovery.HR_MAX_BPM)
            val series = Summaries.heartRateSeries(listOf(HeartRateSample(0, bpm)), listOf(ActivityMinute(60, 0, 0, 0, bpm)))
            assertEquals("summaries $bpm", if (inRange) 2 else 0, series.size)
        }
    }

    @Test fun `a workout drops a reading over 230, and recovery keeps one up to it`() {
        assertFalse(WorkoutRecorder.isValid(235))
        assertTrue(225 in Recovery.HR_MIN_BPM..Recovery.HR_MAX_BPM)
    }

    @Test fun `the queries take the same range`() = runBlocking {
        dao.insertHeartRate(listOf(HeartRateSample(0, 29), HeartRateSample(60, 30), HeartRateSample(120, 230), HeartRateSample(180, 231)))
        dao.insertActivity(listOf(ActivityMinute(240, 0, 0, 0, 231), ActivityMinute(300, 0, 0, 0, 230)))
        val day = dao.dailyHeartRate(0, 3600).first().single()
        assertEquals(2, day.count)
        assertEquals(230.0, day.max, 0.0)
        assertEquals(listOf(1L, 2L, 5L), dao.wornMinutesOnce(0, 3600).sorted())
    }
}
