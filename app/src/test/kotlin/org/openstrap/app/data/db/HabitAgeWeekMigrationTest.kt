package org.openstrap.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A version 17 database opens at 18 with its data intact and an empty Habit Age table that keeps each Sunday's estimate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HabitAgeWeekMigrationTest {
    @Test
    fun habitAgeWeeksAfterMigration() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        // Built from the exported schema, so it's the database version 17 really made.
        val schema = JSONObject(File("schemas/org.openstrap.app.data.db.OpenStrapDatabase/17.json").readText()).getJSONObject("database")
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
            db.version = 17
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = room.healthDao()
            assertEquals(1, room.openHelper.readableDatabase.query("SELECT * FROM health_alert").use { it.count })
            assertEquals(emptyList<HabitAgeWeekRow>(), dao.habitAgeWeeksOnce(0, Long.MAX_VALUE))
            dao.insertHabitAgeWeek(HabitAgeWeekRow(1000, 30.1f, 28, 32, 1))
            dao.insertHabitAgeWeek(HabitAgeWeekRow(2000, 29.2f, 27, 31, 2))
            // The same Sunday again replaces it.
            dao.insertHabitAgeWeek(HabitAgeWeekRow(2000, 29.4f, 27, 31, 3))
            assertEquals(listOf(30.1f, 29.4f), dao.habitAgeWeeksOnce(0, 3000).map { it.estimate })
            assertEquals(listOf(1000L), dao.habitAgeWeeksOnce(0, 1999).map { it.asOf })
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
