package org.openstrap.app.export

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The settings file in a backup, and zips from before it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupZipTest {
    private val day = 1792195200L // 2026-10-17T00:00Z

    private lateinit var old: OpenStrapDatabase
    private lateinit var phone: OpenStrapDatabase

    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java)
        .allowMainThreadQueries().build()

    @Before fun open() {
        old = db(); phone = db()
    }

    @After fun close() { old.close(); phone.close() }

    private suspend fun fill() = old.healthDao().insertHeartRate((0 until 48).map { HeartRateSample(day - 2 * 86_400L + it * 3600L, 60 + it % 10) })
    private suspend fun backup(settings: String?) =
        ByteArrayOutputStream().also { CsvExporter(old.healthDao()).exportZip(it, settings = settings) }.toByteArray()
    private suspend fun restore(zip: ByteArray) = CsvImporter(phone).importZip(ByteArrayInputStream(zip))

    @Test fun `a backup carries its settings to the restore, beside the same records`() = runTest {
        fill()
        val json = SettingsBackup.toJson(mapOf("goal_steps" to 9_000, "profile_birth_year" to 1990))
        val result = restore(backup(json))
        assertEquals(json, result.settings)
        assertEquals(mapOf<String, Any>("goal_steps" to 9_000, "profile_birth_year" to 1990), SettingsBackup.fromJson(result.settings!!))
        assertEquals(48, result.total)
    }

    @Test fun `a zip from before settings were kept restores as it did`() = runTest {
        fill()
        val result = restore(backup(settings = null))
        assertNull(result.settings)
        assertEquals(48, result.total)
        assertEquals(48, phone.healthDao().heartRateOnce(0, Long.MAX_VALUE / 2).size)
    }

    @Test fun `a zip with only a settings file isn't an export`() = runTest {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry(SettingsBackup.FILE)); it.write("{}".toByteArray()); it.closeEntry() }
        }.toByteArray()
        try {
            restore(zip)
            fail("restored a zip with no records")
        } catch (e: IllegalArgumentException) {
            assertEquals("This isn't an OpenStrap export.", e.message)
        }
    }
}
