package org.openstrap.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Modifier
import java.util.Collections

/** Forgetting the strap removes only what is about the strap (audit test 6, P1), and settings flows skip repeats (C9). */
class ForgetDeviceTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } }
    }
    private val settings by lazy { SettingsStore.of(store) }

    @After fun stop() = scope.cancel()

    /** Every key name in [SettingsKeys], read off the object so a key added there is in this test too. */
    private val keyNames: List<String> = SettingsKeys::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && !it.name.endsWith("_PREFIX") }
        .map { it.get(null) as String }

    @Test fun `forgetting the strap keeps every setting that isn't about the strap`() = runTest {
        val dynamic = listOf(
            SettingsKeys.SPORT_SETTINGS_PREFIX + "7",
            SettingsKeys.LAST_SYNC_PREFIX + "daily_energy_profile",
            SettingsKeys.LAST_SYNC_PREFIX + "workout_effect_model",
            SettingsKeys.LAST_SYNC_PREFIX + "body_signals_push_day",
            SettingsKeys.HISTORY_CURSOR_PREFIX + "1",
            SettingsKeys.HISTORY_CURSOR_PREFIX + "16",
            "a_key_added_later",
        )
        val all = keyNames + dynamic
        store.edit { prefs -> all.forEach { prefs[stringPreferencesKey(it)] = "x" } }

        settings.forgetDevice()

        val strap = setOf(
            "device_address", "device_auth_key", "device_name",
            "companion_association_id", "companion_declined", "held_disconnected",
            "strap_battery", "strap_charging", "strap_last_sync_done",
            "zepp_app_token", "zepp_user_id", "zepp_api_base", "zepp_china", "zepp_login_token", "zepp_app_token_at", "zepp_app_ttl",
            "last_sync_gdsp_1", "last_sync_gdsp_16",
        )
        assertEquals(all.toSet() - strap, store.data.first().asMap().keys.map { it.name }.toSet())
        assertEquals(strap - setOf("last_sync_gdsp_1", "last_sync_gdsp_16"), SettingsStore.STRAP_KEYS)
    }

    @Test fun `a write to another setting doesn't re-emit a flow`() = runTest {
        val seen = Collections.synchronizedList(mutableListOf<Goals>())
        val job = scope.launch { settings.goals.collect { seen += it } }
        suspend fun until(n: Int) = withContext(Dispatchers.Default) { withTimeout(5_000) { while (seen.size < n) delay(10) } }
        until(1)
        settings.setWeeklyRecap(false)
        val changed = seen[0].copy(steps = seen[0].steps + 1_000)
        settings.saveGoals(changed)
        until(2)
        job.cancel()
        // Emissions are in write order, so a repeat from either unrelated write would sit between the two.
        assertEquals(listOf(seen[0], changed), seen.toList())
    }
}
