package org.openstrap.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A version 15 database opens at 16 with its data intact and an empty wear-event table that takes rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearEventMigrationTest {
    @Test
    fun wearEventsAfterMigration() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        // Built from the exported schema, so it's the database version 15 really made.
        val schema = JSONObject(File("schemas/org.openstrap.app.data.db.OpenStrapDatabase/15.json").readText()).getJSONObject("database")
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
            db.execSQL("INSERT INTO health_alert (timestamp, type, value) VALUES (1000, 1, 150)")
            db.version = 15
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = room.healthDao()
            assertEquals(1, room.openHelper.readableDatabase.query("SELECT * FROM health_alert").use { it.count })
            assertNull(dao.lastWearEvent())

            // On at 1000, off at 1300, back on at 1600, unknown (link lost) at 2000.
            dao.insertWearEvent(WearEventRow(1000, true))
            dao.insertWearEvent(WearEventRow(1300, false))
            dao.insertWearEvent(WearEventRow(1600, true))
            dao.insertWearEvent(WearEventRow(2000, null))
            assertEquals(WearEventRow(2000, null), dao.lastWearEvent())
            // A window starts with the state in force at its start.
            assertEquals(listOf(1300L, 1600L), dao.wearEventsOnce(1400, 2000).map { it.timestamp })
            assertEquals(listOf(false, true), dao.wearEventsOnce(1400, 2000).map { it.worn })
            assertEquals(listOf(1000L), dao.wearEventsOnce(0, 1300).map { it.timestamp })
            assertEquals(emptyList<WearEventRow>(), dao.wearEventsOnce(0, 1000))
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
