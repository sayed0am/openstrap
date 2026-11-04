package org.openstrap.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A version 24 database opens at 25 (and on to the latest) with each nap's times kept as a nap's edit in `sleep_edit`, not deleted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepEditMigrationTest {
    @Test
    fun napEditsBecomeSleepEdits() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        // Built from the exported schema, so it's the database version 24 really made.
        val schema = JSONObject(File("schemas/org.openstrap.app.data.db.OpenStrapDatabase/24.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO nap_edit (strapStart, start, `end`) VALUES (1000, 900, 2900)")
            db.version = 24
        }

        val before = System.currentTimeMillis() / 1000
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = room.healthDao()
            val e = dao.sleepEditsOnce().single()
            assertEquals(SleepEdit(1000, nap = true, start = 900, end = 2900, deleted = false, editedAt = e.editedAt), e)
            assertTrue(e.editedAt >= before)
            // The new columns take a main sleep's delete with no times.
            dao.upsertSleepEdit(SleepEdit(5000, nap = false, deleted = true, editedAt = 7))
            assertEquals(listOf(1000L, 5000L), dao.sleepEditsOnce().map { it.strapStart }.sorted())
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
