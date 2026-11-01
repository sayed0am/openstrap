package org.openstrap.app.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.db.WearEventRow

/** Wear changes are stored one at a time, in order; wear_event is keyed by the second with REPLACE. */
@OptIn(ExperimentalCoroutinesApi::class)
class WearEventLogTest {
    private val jobs = mutableListOf<Job>()

    /** On the test's scheduler, so advanceUntilIdle runs the log's writer; not a child, so the test can end. */
    private fun TestScope.scope() = CoroutineScope(coroutineContext + Job().also { jobs += it })

    @After fun stop() = jobs.forEach { it.cancel() }

    @Test
    fun writesKeepTheOrderTheChangesCameIn() = runTest {
        val stored = mutableListOf<WearEventRow>()
        // The first write is slow; the later ones must still wait for it.
        val log = WearEventLog(scope(), { row -> if (row.timestamp == 100L) delay(500); stored += row })
        log.record(100, false)
        log.record(101, true)
        log.record(102, null)
        advanceUntilIdle()
        assertEquals(listOf(WearEventRow(100, false), WearEventRow(101, true), WearEventRow(102, null)), stored)
    }

    @Test
    fun theLaterStateInTheSameSecondWins() = runTest {
        // A table keyed by the second, replacing on conflict, as wear_event is.
        val table = sortedMapOf<Long, Boolean?>()
        val log = WearEventLog(scope(), { row -> table[row.timestamp] = row.worn })
        log.record(100, false)
        log.record(100, true)
        // The clock stepped back: stamped at the last second rather than before it.
        log.record(99, null)
        advanceUntilIdle()
        assertEquals(mapOf(100L to null), table)
    }

    @Test
    fun aFailedWriteDoesNotStopTheRest() = runTest {
        val stored = mutableListOf<WearEventRow>()
        val errors = mutableListOf<Throwable>()
        val log = WearEventLog(scope(), { row -> if (row.worn == false) error("disk full"); stored += row }, errors::add)
        log.record(100, false)
        log.record(101, true)
        advanceUntilIdle()
        assertEquals(listOf(WearEventRow(101, true)), stored)
        assertEquals(1, errors.size)
    }
}
