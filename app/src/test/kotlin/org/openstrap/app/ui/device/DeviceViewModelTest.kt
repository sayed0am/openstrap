package org.openstrap.app.ui.device

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openstrap.app.data.BackupRepository
import org.openstrap.app.data.BackupResult
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.CompanionLink
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.export.AutoBackup
import org.openstrap.app.health.HealthConnectAutoSync
import org.openstrap.app.health.HealthConnectExporter
import org.openstrap.app.testing.endMain
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.zip.ZipException

/** The Strap page drives the strap and the settings through its view model only, and backup through the repository (audit A2, A3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings by lazy {
        SettingsStore.of(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } })
    }
    private lateinit var db: OpenStrapDatabase
    private val commands = Collections.synchronizedList(mutableListOf<String>())
    private val backups = FakeBackups()
    private lateinit var vm: DeviceViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDb()
        val strap = recordingStrap()
        val exporter = HealthConnectExporter(context, db.healthDao())
        vm = DeviceViewModel(
            settings, strap, CompanionLink(context, settings), { emptyFlow() }, backups,
            AutoBackup(context, settings, flowOf(true), MutableStateFlow<WorkoutState>(WorkoutState.Idle), backups),
            exporter, HealthConnectAutoSync(context, settings, exporter, strap, scope), sampleData = {},
        )
    }

    @After fun tearDown() {
        endMain(vm.viewModelScope.coroutineContext[Job], scope.coroutineContext[Job])
        db.close()
    }

    @Test fun `the strap's buttons reach the strap through the view model`() {
        vm.syncNow()
        vm.connect()
        vm.disconnect()
        assertEquals(listOf("syncNow", "connect", "disconnect"), commands.toList())
    }

    @Test fun `the strap page's switches write the settings`() = runBlocking {
        vm.setWearReminder(false).join()
        vm.setAutoStartWorkouts(false).join()
        assertEquals(false, settings.wearReminder.first())
        assertEquals(false, settings.autoStartWorkouts.first())
    }

    @Test fun `save a copy and restore say what the repository did`() = runBlocking {
        val uri = Uri.parse("content://test/backup.zip")
        backups.next = BackupResult.Saved
        vm.exportCsv(uri).join()
        assertEquals("Saved a copy.", vm.message.value)

        backups.next = BackupResult.Failed(SecurityException("denied"))
        vm.exportCsv(uri).join()
        assertEquals(ErrorCopy.saveCopy(SecurityException()), vm.message.value)

        backups.next = BackupResult.Restored(1200, settings = true, skipped = 0)
        vm.importCsv(uri).join()
        assertEquals(BackupCopy.restored(1200, true, 0), vm.message.value)

        backups.next = BackupResult.Failed(ZipException("bad"))
        vm.importCsv(uri).join()
        assertEquals("Couldn't restore the copy. That file isn't an unstrap copy.", vm.message.value)
    }

    @Test fun `back up now with automatic backup off says so`() = runBlocking {
        vm.backUpNow().join()
        assertEquals(BackupCopy.outcome(AutoBackup.Outcome.Off), vm.message.value)
        assertEquals(false, vm.backingUp.value)
    }

    private class FakeBackups : BackupRepository {
        var next: BackupResult = BackupResult.Saved
        override suspend fun exportCsv(to: Uri) = next
        override suspend fun importCsv(from: Uri) = next
        override suspend fun exportSettings(to: Uri) = next
        override suspend fun importSettings(from: Uri) = next
        override suspend fun writeZip(out: OutputStream) = Unit
    }

    /** A strap that is never connected and only records the commands it is given. */
    private fun recordingStrap(): StrapController {
        val flows = mutableMapOf<String, Any>()
        return Proxy.newProxyInstance(StrapController::class.java.classLoader, arrayOf(StrapController::class.java)) { proxy, method, args ->
            when {
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "toString" -> "recording strap"
                method.name == "getConnection" -> flows.getOrPut(method.name) { MutableStateFlow<ConnectionState>(ConnectionState.Disconnected) }
                StateFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableStateFlow<Any?>(null) }
                SharedFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableSharedFlow<Any?>() }
                method.returnType == java.lang.Boolean.TYPE -> false
                else -> { commands += method.name; Unit }
            }
        } as StrapController
    }
}
