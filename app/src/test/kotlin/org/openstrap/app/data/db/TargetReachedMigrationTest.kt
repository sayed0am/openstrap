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

/** A version 20 database opens at 21 with its phone workouts intact and no kcal target reached on them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TargetReachedMigrationTest {
    @Test
    fun targetReachedAfterMigration() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        // Built from the exported schema, so it's the database version 20 really made.
        val schema = JSONObject(File("schemas/org.openstrap.app.data.db.OpenStrapDatabase/20.json").readText()).getJSONObject("database")
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
            db.execSQL("INSERT INTO app_workout (start, `end`, sportType, source, pauses, target, activeKcal) VALUES (1000, 2800, 1, 0, '', 'k:300', 320)")
            db.version = 20
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = room.healthDao()
            val w = dao.appWorkoutOnce(1000)!!
            assertEquals("k:300", w.target)
            assertEquals(320f, w.activeKcal)
            assertNull(w.targetReachedAt)
            dao.upsertAppWorkout(w.copy(targetReachedAt = 2400))
            assertEquals(2400L, dao.appWorkoutOnce(1000)!!.targetReachedAt)
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
