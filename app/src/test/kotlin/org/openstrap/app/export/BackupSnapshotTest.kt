package org.openstrap.app.export

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.util.zip.ZipInputStream

/**
 * A backup is one snapshot (data integrity item 23.5): rows a sync writes while the zip is being written are in none of
 * its tables, rather than in the ones read after them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupSnapshotTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = inMemoryDb()
    private val logged = mutableListOf<String>()

    @After fun close() {
        db.close(); scope.cancel()
    }

    private val settings by lazy {
        SettingsStore.of(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("s.preferences_pb").also { it.delete() } })
    }

    /** The rows of [file] in [zip], its header left out. */
    private fun lines(zip: ByteArray, file: String): List<String> = ZipInputStream(zip.inputStream()).use { z ->
        generateSequence { z.nextEntry }.first { it.name == file }
        z.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.drop(1)
    }

    @Test fun `rows a sync writes while the zip is written are left out of every table`() = runBlocking {
        val t = 1_790_000_000L
        db.healthDao().insertHeartRate((0 until 10).map { HeartRateSample(t + it * 60L, 70) })
        db.healthDao().insertActivity((0 until 10).map { ActivityMinute(t + it * 60L, 20, 0, 0, null) })
        // A sync that lands as the zip's first bytes go out, before any table is read.
        val out = ByteArrayOutputStream()
        var synced = false
        val during = object : FilterOutputStream(out) {
            override fun write(b: Int) {
                sync()
                super.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                sync()
                out.write(b, off, len)
            }

            private fun sync() {
                if (synced) return
                synced = true
                val w = db.openHelper.writableDatabase
                w.execSQL("INSERT INTO heart_rate (timestamp, bpm, source) VALUES (${t + 3600}, 99, 0)")
                w.execSQL("INSERT INTO activity_minute (timestamp, steps, intensity, kind) VALUES (${t + 3600}, 99, 0, 0)")
            }
        }
        CsvBackups(context, db, settings, CsvExporter(db.healthDao()), logged::add).writeZip(during)
        val zip = out.toByteArray()

        assertTrue(synced)
        assertEquals(10, lines(zip, "heart_rate.csv").size)
        assertEquals(10, lines(zip, "activity_minute.csv").size)
        // The copy was made and is gone again.
        assertFalse(logged.toString(), logged.any { "no copy" in it })
        assertFalse(File(context.cacheDir, "backup-snapshot.db").exists())
        // The sync's rows are in the database itself.
        assertEquals(11, db.healthDao().heartRateOnce(t, t + 7200).size)
    }
}
