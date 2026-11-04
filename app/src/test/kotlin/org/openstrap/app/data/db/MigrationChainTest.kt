package org.openstrap.app.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.MigrationHarness
import org.openstrap.app.testing.SourceTree
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The whole migration chain (audit test 2): a database from every exported version opens at the current one, which
 * Room checks table by table and index by index against the schema, and a version 1 database keeps its rows all the
 * way. An old install that fails here would crash at launch with no way back, as there is no destructive fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationChainTest {
    @get:Rule val harness = MigrationHarness()

    /** The newest exported schema, which Room writes for the version the database is at. */
    private val current = SourceTree.schemas.listFiles()!!.filter { it.extension == "json" }.maxOf { it.nameWithoutExtension.toInt() }

    @Test fun `every exported version opens at the current one`() = runTest {
        val versions = SourceTree.schemas.listFiles()!!.filter { it.extension == "json" }.map { it.nameWithoutExtension.toInt() }.sorted()
        assertEquals((1..current).toList(), versions)
        assertEquals(28, current)
        for (v in 1 until current) {
            val db = harness.open(harness.create(v))
            // Any read opens it, running the migrations and Room's check of the result.
            assertEquals("from $v", 0, db.healthDao().workoutCount())
            assertEquals("from $v", current, db.openHelper.readableDatabase.version)
            db.close()
        }
    }

    @Test fun `a version 1 database keeps its data through every migration`() = runTest {
        val file = harness.create(1) {
            execSQL("INSERT INTO heart_rate (timestamp, bpm, source) VALUES (1000, 61, 0)")
            execSQL("INSERT INTO activity_minute (timestamp, steps, intensity, kind, heartRate) VALUES (1020, 12, 30, 1, 64)")
            execSQL("INSERT INTO sleep_session (start, `end`) VALUES (2000, 30800)")
            execSQL("INSERT INTO sleep_stage (start, `end`, sessionStart, stage) VALUES (2000, 9200, 2000, 2)")
            execSQL("INSERT INTO spo2 (timestamp, percent) VALUES (3000, 97)")
            execSQL("INSERT INTO stress (timestamp, value) VALUES (3060, 33)")
            execSQL("INSERT INTO hrv (timestamp, milliseconds) VALUES (3120, 48)")
            execSQL("INSERT INTO resting_hr (day, bpm) VALUES (86400, 55)")
        }
        val dao = harness.open(file, ZoneOffset.UTC).healthDao()
        assertEquals(listOf(HeartRateSample(1000, 61, 0)), dao.heartRateOnce(0, 5000))
        assertEquals(listOf(ActivityMinute(1020, 12, 30, 1, 64)), dao.activityOnce(0, 5000))
        // A session from before days were kept has day 0, and isn't a nap.
        assertEquals(listOf(SleepSession(2000, 30800)), dao.sleepSessionsOnce(0, 40_000))
        assertEquals(listOf(SleepStage(2000, 9200, 2000, 2)), dao.sleepStagesOfSessionsEndingOnce(0, 40_000))
        assertEquals(listOf(Spo2Sample(3000, 97)), dao.spo2Once(0, 5000))
        assertEquals(listOf(StressSample(3060, 33)), dao.stressOnce(0, 5000))
        assertEquals(listOf(HrvSample(3120, 48)), dao.hrvOnce(0, 5000))
        // A day key from then (the local midnight of 2 January 1970 in UTC) becomes that date.
        assertEquals(listOf(RestingHeartRate(LocalDate.of(1970, 1, 2), 55)), dao.restingHrOnce(LocalDate.EPOCH, LocalDate.of(1970, 1, 3)))
        assertEquals(setOf(0L, 8L), dao.hoursWithData(0, 100_000).first().toSet())
    }

    @Test fun `version 27 adds the end indices and leaves the rows`() = runTest {
        val file = harness.create(26) {
            execSQL("INSERT INTO sleep_session (start, `end`, day, nap) VALUES (2000, 30800, 0, 0)")
            execSQL("INSERT INTO app_workout (start, `end`, sportType, source, pauses) VALUES (1000, 2800, 52, 0, '')")
            execSQL("INSERT INTO app_workout_dismissed (start, `end`) VALUES (5000, 6000)")
        }
        val db = harness.open(file)
        val dao = db.healthDao()
        assertEquals(1, dao.sleepSessionsOnce(0, 40_000).size)
        assertEquals(listOf(1000L), dao.appWorkoutsStartingOnce(0, 5000).map { it.start })
        assertEquals(listOf(DismissedWorkout(5000, 6000)), dao.dismissedWorkoutsStartingOnce(0, 9000))
        val indices = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertTrue(indices.toString(), indices.containsAll(setOf("index_sleep_session_nap_end", "index_app_workout_end", "index_app_workout_dismissed_end")))
    }
}
