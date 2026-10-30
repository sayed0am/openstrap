package org.openstrap.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.SleepEdit
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sleep read through the edits against a real database: what every screen and model sees. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepSourceTest {
    private lateinit var db: OpenStrapDatabase
    private val dao get() = db.healthDao()
    private val h = 3600L
    private val main = SleepSession(100 * h, 108 * h, score = 80, deepMinutes = 100, lightMinutes = 300, remMinutes = 60)
    private val nap = SleepSession(114 * h, 115 * h, nap = true)

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    private suspend fun seed() {
        dao.insertSleepSessions(listOf(main, nap))
        // Two hours of deep, then light to the end.
        dao.insertSleepStages(listOf(
            SleepStage(100 * h, 102 * h, main.start, SleepStage.DEEP),
            SleepStage(102 * h, 108 * h, main.start, SleepStage.LIGHT),
        ))
    }

    @Test fun `a moved main sleep has its stages clipped and its totals worked out from them`() = runTest {
        seed()
        dao.upsertSleepEdit(SleepEdit(main.start, nap = false, start = 101 * h, end = 108 * h, editedAt = 1))
        val source = SleepSource(dao)
        val shown = source.sessionsOnce(0, 200 * h)
        val m = shown.single { !it.nap }
        assertEquals(101 * h, m.start)
        assertEquals(60, m.deepMinutes)
        assertEquals(360, m.lightMinutes)
        assertTrue(m.score != 80)
        assertEquals(listOf(101 * h to 102 * h, 102 * h to 108 * h), source.stagesOnce(m).map { it.start to it.end })
        assertEquals(source.stagesOnce(m), source.stages(m).first())
        // The flow reads the same.
        assertEquals(shown, source.sessions(0, 200 * h).first())
    }

    @Test fun `a deleted session is left out, and comes back on restore`() = runTest {
        seed()
        dao.upsertSleepEdit(SleepEdit(nap.start, nap = true, deleted = true, editedAt = 1))
        val source = SleepSource(dao)
        assertEquals(listOf(main), source.sessionsOnce(0, 200 * h))
        assertTrue(source.resolvedOnce(0, 200 * h).single { it.strap.nap }.deleted)
        dao.deleteSleepEdit(nap.start)
        assertEquals(listOf(main, nap), source.sessionsOnce(0, 200 * h))
    }

    @Test fun `the window is on the end as shown`() = runTest {
        seed()
        // The nap moved to end an hour later, past the window's end at 115 h.
        dao.upsertSleepEdit(SleepEdit(nap.start, nap = true, start = 114 * h, end = 116 * h, editedAt = 1))
        val source = SleepSource(dao)
        assertEquals(listOf(main), source.sessionsOnce(0, 115 * h))
        assertEquals(116 * h, source.sessionsOnce(0, 117 * h).single { it.nap }.end)
    }

    @Test fun `delete, undo and restore through the repository bring back exactly what was there`() = runTest {
        seed()
        val repo = HealthRepository(dao)
        repo.setSleepTimes(main, 101 * h, 108 * h)
        val moved = repo.sleep.sessionsOnce(0, 200 * h)
        repo.deleteSleep(main)
        assertEquals(listOf(nap), repo.sleep.sessionsOnce(0, 200 * h))
        assertEquals(listOf(main.start), repo.sleep.deletedOnce(200 * h).map { it.strap.start })
        repo.restoreSleep(main)
        assertEquals(moved, repo.sleep.sessionsOnce(0, 200 * h))
        assertEquals(emptyList<SleepEdits.Resolved>(), repo.sleep.deletedOnce(200 * h))
        // A delete of an unmoved nap leaves no trace once restored.
        repo.deleteSleep(nap)
        repo.restoreSleep(nap)
        assertEquals(listOf(main.start), dao.sleepEditsOnce().map { it.strapStart })
    }

    @Test fun `after 7 days a delete goes for good, and a resync doesn't bring it back`() = runTest {
        seed()
        val day = 86_400L
        dao.upsertSleepEdit(SleepEdit(main.start, nap = false, start = 101 * h, end = 108 * h, deleted = true, editedAt = 200 * h))
        val source = SleepSource(dao)
        // Still restorable on day 6, and nothing goes.
        assertEquals(0, source.purgeExpired(200 * h + 6 * day))
        assertEquals(1, source.deletedOnce(200 * h + 6 * day).size)
        // Day 7: the session and its stages go, the edit stays as a marker without times.
        assertEquals(1, source.purgeExpired(200 * h + 7 * day))
        assertEquals(listOf(nap), dao.sleepSessionsOnce(0, 200 * h))
        assertEquals(emptyList<SleepStage>(), dao.sleepStagesOnce(main.start))
        val marker = dao.sleepEditsOnce().single()
        assertTrue(marker.deleted && marker.start == null && marker.end == null)
        assertEquals(emptyList<SleepEdits.Resolved>(), source.deletedOnce(200 * h + 7 * day))
        // The strap sends the night again, a few minutes off: it stays out, and the next clean up takes it away.
        dao.insertSleepSessions(listOf(main.copy(start = main.start + 120)))
        assertEquals(listOf(nap), source.sessionsOnce(0, 200 * h))
        assertEquals(1, source.purgeExpired(200 * h + 8 * day))
        assertEquals(listOf(nap), dao.sleepSessionsOnce(0, 200 * h))
    }

    @Test fun `a resync that shifts the strap's start keeps the move, and the stages follow`() = runTest {
        seed()
        dao.upsertSleepEdit(SleepEdit(main.start, nap = false, start = 101 * h, end = 108 * h, editedAt = 1))
        // The strap re-sends the night starting 4 minutes later.
        dao.deleteSleepStagesOf(main.start)
        dao.deleteSleepSession(main.start)
        val shifted = main.copy(start = main.start + 240)
        dao.insertSleepSessions(listOf(shifted))
        dao.insertSleepStages(listOf(SleepStage(shifted.start, 108 * h, shifted.start, SleepStage.LIGHT)))
        val source = SleepSource(dao)
        val m = source.sessionsOnce(0, 200 * h).single { !it.nap }
        assertEquals(101 * h, m.start)
        assertEquals(listOf(101 * h to 108 * h), source.stagesOnce(m).map { it.start to it.end })
    }
}
