package org.openstrap.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openstrap.app.testing.ZoneRule
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * The Habit Age Sunday last opened is kept as a date (item 17), so a flight doesn't offer the same week again. The
 * local midnight kept before reads back as its date.
 */
class HabitAgeSeenTest {
    @get:Rule val folder = TemporaryFolder()
    @get:Rule val zoneRule = ZoneRule()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } }
    }
    private val settings by lazy { SettingsStore.of(store) }
    private val sunday = LocalDate.of(2026, 11, 1)

    @After fun stop() = scope.cancel()

    @Test fun `none opened yet is null`() = runTest {
        assertNull(settings.habitAgeSeenAsOf.first())
    }

    @Test fun `a Sunday opened in Dubai is that Sunday in New York`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"))
        settings.setHabitAgeSeen(sunday)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals(sunday, settings.habitAgeSeenAsOf.first())
    }

    @Test fun `the local midnight kept before reads back as its date`() = runTest {
        val dubai = ZoneId.of("Asia/Dubai")
        store.edit { it[longPreferencesKey(SettingsKeys.HABIT_AGE_SEEN)] = sunday.atStartOfDay(dubai).toEpochSecond() }
        TimeZone.setDefault(TimeZone.getTimeZone(dubai))
        assertEquals(sunday, settings.habitAgeSeenAsOf.first())
        // Opened again since: the date wins.
        settings.setHabitAgeSeen(sunday.plusDays(7))
        assertEquals(sunday.plusDays(7), settings.habitAgeSeenAsOf.first())
    }
}
