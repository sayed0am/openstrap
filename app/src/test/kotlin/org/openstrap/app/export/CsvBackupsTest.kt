package org.openstrap.app.export

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openstrap.app.data.BackupResult
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.settings.Goals
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Save a copy and Restore through the repository: records and settings round trip, and a bad file is a failure, not a crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CsvBackupsTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var oldDb: OpenStrapDatabase
    private lateinit var newDb: OpenStrapDatabase
    private val logged = mutableListOf<String>()

    private fun settings(name: String) =
        SettingsStore.of(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("$name.preferences_pb").also { it.delete() } })

    private val oldSettings by lazy { settings("old") }
    private val newSettings by lazy { settings("new") }
    private val oldPhone by lazy { CsvBackups(context, oldDb, oldSettings, CsvExporter(oldDb.healthDao()), logged::add) }
    private val newPhone by lazy { CsvBackups(context, newDb, newSettings, CsvExporter(newDb.healthDao()), logged::add) }

    @Before fun open() {
        oldDb = inMemoryDb(); newDb = inMemoryDb()
    }

    @After fun close() {
        oldDb.close(); newDb.close(); scope.cancel()
    }

    private fun file(name: String): Uri = Uri.fromFile(File(folder.root, name))

    @Test fun `a copy restores its records and settings on another phone`() = runBlocking {
        val day = 1792195200L
        oldDb.healthDao().insertHeartRate((0 until 48).map { HeartRateSample(day + it * 3600L, 60 + it % 10) })
        oldSettings.saveGoals(Goals(steps = 9_000))
        val zip = file("copy.zip")

        assertEquals(BackupResult.Saved, oldPhone.exportCsv(zip))
        assertEquals(BackupResult.Restored(48, settings = true, skipped = 0), newPhone.importCsv(zip))
        assertEquals(9_000, newSettings.goals.first().steps)
        // Twice changes nothing.
        assertEquals(BackupResult.Restored(0, settings = false, skipped = 0), newPhone.importCsv(zip))
    }

    @Test fun `the settings alone round trip`() = runBlocking {
        oldSettings.saveGoals(Goals(steps = 7_500))
        val json = file("settings.json")
        assertEquals(BackupResult.Saved, oldPhone.exportSettings(json))
        assertEquals(BackupResult.Restored(0, settings = true, skipped = 0), newPhone.importSettings(json))
        assertEquals(7_500, newSettings.goals.first().steps)
    }

    @Test fun `a file that isn't a copy fails without throwing`() = runBlocking {
        val notZip = File(folder.root, "notes.zip").apply { writeText("not a zip") }
        val result = newPhone.importCsv(Uri.fromFile(notZip))
        assertTrue("$result", result is BackupResult.Failed)
        val missing = newPhone.importCsv(file("gone.zip"))
        assertTrue("$missing", missing is BackupResult.Failed)
    }
}
