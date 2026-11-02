package org.openstrap.app.device

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.settings.SettingsStore
import java.io.IOException

/**
 * The zones in use follow only what's stored (audit A8): a move that the store fails to write leaves them as they are
 * on disk, where they used to change before the write.
 */
class HeartZonesInUseTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } }
    }
    private val moved = HeartZones.STANDARD.withStart(1, 100, 190)

    @After fun tearDown() = runBlocking {
        HeartZonesInUse.follow(flowOf(HeartZones.STANDARD), scope).join()
        scope.cancel()
    }

    @Test fun `stored zones come into use`() = runBlocking {
        val settings = SettingsStore.of(store)
        HeartZonesInUse.follow(settings.heartZones, scope)
        settings.setHeartZones(moved)
        assertEquals(moved, withTimeout(WAIT_MS) { HeartZonesInUse.current.first { it == moved } })
    }

    @Test fun `a failed write leaves the zones in use as stored`() = runBlocking {
        val failing = SettingsStore.of(object : DataStore<Preferences> by store {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
        })
        HeartZonesInUse.follow(failing.heartZones, scope)
        val write = runCatching { failing.setHeartZones(moved) }
        assertTrue(write.exceptionOrNull() is IOException)
        assertEquals(HeartZones.STANDARD, failing.heartZones.first())
        assertEquals(HeartZones.STANDARD, HeartZonesInUse.current.value)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
