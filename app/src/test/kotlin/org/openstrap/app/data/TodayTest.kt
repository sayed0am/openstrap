package org.openstrap.app.data

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.FixedClock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** [Today] moves on at midnight on the minute tick, and at once when the phone's clock or zone changes (audit C10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayTest {
    private val clock = FixedClock(Instant.parse("2026-01-15T23:59:30Z"))
    private val jan15 = LocalDate.of(2026, 1, 15)

    @Test fun `today moves on at the first minute past midnight, once`() = runTest {
        val dates = mutableListOf<LocalDate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { Today.dates({ clock.clock }).collect { dates += it } }
        runCurrent()
        assertEquals(listOf(jan15), dates)
        clock.advance(Duration.ofMinutes(1))
        // Not before the minute ends: the clock is only read on the tick.
        advanceTimeBy(29_000)
        assertEquals(listOf(jan15), dates)
        advanceTimeBy(1_001)
        assertEquals(listOf(jan15, jan15.plusDays(1)), dates)
        // Later minutes of the same day add nothing.
        clock.advance(Duration.ofMinutes(5))
        advanceTimeBy(Duration.ofMinutes(5).toMillis())
        assertEquals(listOf(jan15, jan15.plusDays(1)), dates)
    }

    @Test fun `a change of the phone's clock is read at once`() = runTest {
        val changes = MutableSharedFlow<Unit>()
        val dates = mutableListOf<LocalDate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { Today.dates({ clock.clock }, changes).collect { dates += it } }
        runCurrent()
        clock.advance(Duration.ofDays(3))
        changes.emit(Unit)
        assertEquals(listOf(jan15, jan15.plusDays(3)), dates)
    }

    @Test fun `time, zone and date broadcasts each count as a change`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var seen = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { Today.changes(context).collect { seen++ } }
        runCurrent()
        for (action in listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED)) {
            context.sendBroadcast(Intent(action))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
        }
        assertEquals(3, seen)
    }
}
