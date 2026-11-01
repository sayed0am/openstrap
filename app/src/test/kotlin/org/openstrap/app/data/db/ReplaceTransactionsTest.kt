package org.openstrap.app.data.db

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.SleepSource
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The delete-then-insert replaces run all or none (audit P4): a write that fails half way leaves what was there. A
 * trigger stands in for the failure, such as a full disk, or a kill between the statements.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplaceTransactionsTest {
    private lateinit var db: OpenStrapDatabase
    private val dao get() = db.healthDao()
    private val h = 3600L
    private val day = 99 * h
    private val night = SleepSession(100 * h, 108 * h, day, nap = false, score = 80)
    private val stages = listOf(
        SleepStage(100 * h, 102 * h, night.start, SleepStage.DEEP),
        SleepStage(102 * h, 108 * h, night.start, SleepStage.LIGHT),
    )

    @Before fun open() {
        db = inMemoryDb()
    }
    @After fun close() = db.close()

    private fun failInsertsInto(table: String) =
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_$table BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'disk full'); END")

    private fun failDeletesFrom(table: String, where: String) =
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_$table BEFORE DELETE ON $table WHEN $where BEGIN SELECT RAISE(ABORT, 'disk full'); END")

    @Test fun `replaceSleepDay swaps the night for the new copy`() = runTest {
        dao.replaceSleepDay(day, nap = false, listOf(night), stages)
        val newer = night.copy(start = 101 * h, score = 85)
        dao.replaceSleepDay(day, nap = false, listOf(newer), listOf(SleepStage(101 * h, 108 * h, newer.start, SleepStage.REM)))

        assertEquals(listOf(newer), dao.sleepSessionsOnce(0, 200 * h))
        assertEquals(emptyList<SleepStage>(), dao.sleepStagesOnce(night.start))
        assertEquals(1, dao.sleepStagesOnce(newer.start).size)
    }

    @Test fun `replaceSleepDay leaves the other kind alone`() = runTest {
        val nap = SleepSession(114 * h, 115 * h, day, nap = true)
        dao.replaceSleepDay(day, nap = false, listOf(night), stages)
        dao.replaceSleepDay(day, nap = true, listOf(nap), emptyList())
        dao.replaceSleepDay(day, nap = true, emptyList(), emptyList())

        assertEquals(listOf(night), dao.sleepSessionsOnce(0, 200 * h))
        assertEquals(stages, dao.sleepStagesOnce(night.start))
    }

    @Test fun `replaceSleepDay that fails mid insert keeps the old night`() = runTest {
        dao.replaceSleepDay(day, nap = false, listOf(night), stages)
        failInsertsInto("sleep_stage")

        val newer = night.copy(start = 101 * h, score = 85)
        val thrown = runCatching { dao.replaceSleepDay(day, nap = false, listOf(newer), listOf(SleepStage(101 * h, 108 * h, newer.start, SleepStage.REM))) }
        assertTrue(thrown.isFailure)

        assertEquals(listOf(night), dao.sleepSessionsOnce(0, 200 * h))
        assertEquals(stages, dao.sleepStagesOnce(night.start))
    }

    @Test fun `replaceWorkoutDetail that fails mid insert keeps the old stream`() = runTest {
        val start = 500 * h
        val old = listOf(WorkoutHeartRate(start, start + 1, 120), WorkoutHeartRate(start, start + 2, 121))
        dao.replaceWorkoutDetail(start, old)
        dao.replaceWorkoutDetail(start, old.take(1))
        assertEquals(old.take(1), dao.workoutHeartRateOnce(start))

        failInsertsInto("workout_hr")
        assertTrue(runCatching { dao.replaceWorkoutDetail(start, old) }.isFailure)
        assertEquals(old.take(1), dao.workoutHeartRateOnce(start))
    }

    @Test fun `purgeExpired that fails half way removes nothing`() = runTest {
        val dayS = 86_400L
        val other = SleepSession(300 * h, 308 * h, 299 * h, nap = false)
        dao.insertSleepSessions(listOf(night, other))
        dao.insertSleepStages(stages + SleepStage(300 * h, 308 * h, other.start, SleepStage.LIGHT))
        // Two deletes past their week, one of them moved first, so its edit becomes a marker.
        dao.insertSleepEdits(listOf(
            SleepEdit(night.start, nap = false, start = 101 * h, end = 108 * h, deleted = true, editedAt = 310 * h),
            SleepEdit(other.start, nap = false, deleted = true, editedAt = 310 * h),
        ))
        val editsBefore = dao.sleepEditsOnce()
        // The second session's delete fails, after the first session and its stages went.
        failDeletesFrom("sleep_session", "old.start = ${other.start}")

        assertTrue(runCatching { SleepSource(dao).purgeExpired(310 * h + 7 * dayS) }.isFailure)

        assertEquals(listOf(night, other), dao.sleepSessionsOnce(0, 400 * h))
        assertEquals(stages, dao.sleepStagesOnce(night.start))
        assertEquals(editsBefore, dao.sleepEditsOnce())
    }
}
