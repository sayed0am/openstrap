package org.openstrap.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.testing.appWorkout
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Data integrity item 19: Today's Recovery, Load and Energy follow [HealthRepository.changes], so a phone workout
 * trimmed, ended earlier or deleted there must move it, without waiting for a sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneWorkoutChangesTest {
    private lateinit var db: OpenStrapDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val emitted = AtomicInteger()
    private val walk = appWorkout(start = START, minutes = 40)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking { db.healthDao().upsertAppWorkout(walk) }
        scope.launch { HealthRepository(db.healthDao()).changes().collect { emitted.incrementAndGet() } }
        runBlocking {
            withTimeout(WAIT_MS) { while (emitted.get() == 0) delay(POLL_MS) }
            // Room may run a query once more as it starts watching: count from where it settles.
            delay(SETTLE_MS)
        }
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** Runs [change] and waits for [HealthRepository.changes] to emit after it. Times out when it never does. */
    private fun refreshes(change: suspend () -> Unit) = runBlocking {
        val before = emitted.get()
        change()
        withTimeout(WAIT_MS) { while (emitted.get() == before) delay(POLL_MS) }
    }

    @Test fun `a trim refreshes`() = refreshes { db.healthDao().upsertAppWorkout(walk.copy(shownStart = START + 300, end = START + 1800)) }

    @Test fun `a delete refreshes`() = refreshes { db.healthDao().deleteAppWorkoutRow(START) }

    private companion object {
        const val START = 1_768_460_400L
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 300L
    }
}
