package org.openstrap.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.HealthRepository
import org.openstrap.app.data.LiveSteps
import org.openstrap.app.data.WidgetKind
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.testing.FixedClock
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.protocol.services.StepTotals
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The widgets' one read per refresh (audit A4, A5): only the kinds asked for are read, the live steps come from the
 * app's one [LiveSteps], and the moment, the Moment widget and the Sleep widget pick the same main night.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSnapshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = inMemoryDb()
    private val dao = db.healthDao()
    private val clock = FixedClock()
    private val liveSteps = LiveSteps()
    private var live: StepTotals? = null
    private val snapshot = WidgetSnapshot(
        HealthRepository(dao), dao, SettingsStore(context), liveSteps,
        syncing = { false }, todaySteps = { live }, workoutState = { WorkoutState.Idle }, location = { false }, placed = { emptySet() },
    )
    private val now = clock.instant
    private val zone = clock.zone
    private val midnight = now.atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()

    @After fun tearDown() = db.close()

    @Test fun `a refresh reads only the kinds it is for`() = runBlocking {
        val none = snapshot.gather(emptySet(), now, zone)
        assertNull(none.moment)
        assertNull(none.steps)
        assertNull(none.sleep)
        val steps = snapshot.gather(setOf(WidgetKind.Steps), now, zone)
        assertNotNull(steps.steps)
        // Steps is lit in the moment's mood, so the moment is read, and nothing else is.
        assertNotNull(steps.moment?.getOrNull())
        assertNull(steps.sleep)
        assertNull(steps.recovery)
        assertNull(steps.start)
        assertNull(steps.momentWidget)
    }

    @Test fun `live steps carry on from the app's one counter, as Today's do`() = runBlocking {
        dao.insertActivity(listOf(ActivityMinute(midnight + 3600, 2456, 0, 0, null)))
        // Firmware 3.25's counter with its offset: implausible, so counted from the history on.
        live = StepTotals(11368, 1836, 151)
        assertEquals(2456, snapshot.gather(setOf(WidgetKind.Steps), now, zone).steps?.day?.steps)
        live = StepTotals(11391, 1853, 152)
        // A fresh counter per read would show 2,456 again (A5): the shared one adds the 23 steps since.
        assertEquals(2479, snapshot.gather(setOf(WidgetKind.Steps), now, zone).steps?.day?.steps)
    }

    @Test fun `the moment, Moment and Sleep pick one main night`() = runBlocking {
        val main = SleepSession(start = midnight - 3 * 3600, end = midnight + 7 * 3600)
        val shorter = SleepSession(start = midnight + 8 * 3600, end = midnight + 8 * 3600 + 1800)
        dao.insertSleepSessions(listOf(main, shorter))
        val data = snapshot.gather(setOf(WidgetKind.Sleep, WidgetKind.Moment), now, zone)
        assertEquals(main.end, data.moment?.getOrThrow()?.sleepEnd)
        assertEquals(main.start, data.sleep?.getOrThrow()?.tonight?.session?.start)
        assertEquals(main.start, data.momentWidget?.getOrThrow()?.night?.session?.start)
    }

    @Test fun `the Sleep widget reads as many nights as the Sleep tab`() {
        assertEquals(org.openstrap.app.ui.dashboard.SLEEP_NIGHTS, WidgetSnapshot.SLEEP_NIGHTS)
    }

    @Test fun `each widget adds its own bedtime to the moment`() = runBlocking {
        val data = snapshot.gather(setOf(WidgetKind.Steps), Instant.parse("2026-01-15T21:00:00Z"), zone)
        val bed = java.time.LocalTime.of(22, 30)
        assertEquals(bed, data.momentInputs(bed)?.usualBedtime)
        assertNull(data.moment?.getOrThrow()?.usualBedtime)
    }
}
