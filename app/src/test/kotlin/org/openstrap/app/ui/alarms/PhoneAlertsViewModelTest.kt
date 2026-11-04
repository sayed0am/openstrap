package org.openstrap.app.ui.alarms

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openstrap.app.data.settings.BuzzPattern
import org.openstrap.app.data.settings.CallBuzz
import org.openstrap.app.data.settings.QuietHours
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.testing.endMain

/** The Phone alerts page writes through its view model, and quiet hours change from what is stored (audit A2). */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAlertsViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } }
    }
    /** Closed, reads wait as a cold store's first read does, and writes still land. */
    private val readable = MutableStateFlow(true)
    private val settings by lazy {
        SettingsStore.of(object : DataStore<Preferences> by store {
            override val data: Flow<Preferences> = store.data.onStart { readable.first { it } }
        })
    }
    private val alerts = FakeAlerts()
    private val vms = mutableListOf<PhoneAlertsViewModel>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        endMain(vms.map { it.viewModelScope.coroutineContext[Job] } + scope.coroutineContext[Job])
    }

    private fun vm() = PhoneAlertsViewModel(settings, alerts).also { vms += it }

    private val stored = QuietHours(on = true, from = 21 * 60 + 30, to = 6 * 60, calls = false)

    @Test fun `a quiet hours tap before the settings load keeps the user's hours`() = runBlocking {
        settings.setQuietHours(stored)
        readable.value = false
        val vm = vm()
        // The page still shows the defaults: the store hasn't answered the view model yet.
        val tap = vm.updateQuietHours { it.copy(calls = true) }
        assertEquals(QuietHours(), vm.quiet.value)
        readable.value = true
        tap.join()
        assertEquals(stored.copy(calls = true), settings.quietHours.first())
    }

    @Test fun `two quick changes to quiet hours both count`() = runBlocking {
        settings.setQuietHours(stored)
        val vm = vm()
        val first = vm.updateQuietHours { it.copy(from = 23 * 60) }
        val second = vm.updateQuietHours { it.copy(to = 5 * 60) }
        first.join()
        second.join()
        assertEquals(stored.copy(from = 23 * 60, to = 5 * 60), settings.quietHours.first())
    }

    @Test fun `picking a pattern keeps it and plays it, and a failed buzz is said until the page closes`() = runBlocking {
        val vm = vm()
        vm.pickPattern(BuzzPattern.entries.last()).join()
        assertEquals(BuzzPattern.entries.last(), settings.notificationPattern.first())
        assertEquals(listOf<Any>(BuzzPattern.entries.last()), alerts.played)
        assertNull(vm.previewError.value)

        alerts.fail = "The strap didn't answer."
        vm.pickCallBuzz(CallBuzz.PULSED).join()
        assertEquals(CallBuzz.PULSED, settings.callBuzzStyle.first())
        assertEquals("The strap didn't answer.", vm.callPreviewError.value)
        vm.clearPreviewErrors()
        assertNull(vm.callPreviewError.value)
    }

    @Test fun `the switches write the settings`() = runBlocking {
        val vm = vm()
        vm.setBuzzOnCalls(true).join()
        vm.setBuzzOnNotifications(true).join()
        assertEquals(true, settings.buzzOnCalls.first())
        assertEquals(true, settings.buzzOnNotifications.first())
    }

    private class FakeAlerts : BuzzAlerts {
        override val callPermission: StateFlow<Boolean> = MutableStateFlow(true)
        override val notificationAccess: StateFlow<Boolean> = MutableStateFlow(true)
        val played = mutableListOf<Any>()
        var fail: String? = null
        override fun refresh() = Unit
        override fun hasCallPermission() = true
        override suspend fun preview(pattern: BuzzPattern) = play(pattern)
        override suspend fun previewCall(style: CallBuzz) = play(style)
        private fun play(what: Any) {
            fail?.let { throw IllegalStateException(it) }
            played += what
        }
    }
}
