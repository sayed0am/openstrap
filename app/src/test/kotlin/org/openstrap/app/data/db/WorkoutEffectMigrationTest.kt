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

/** A version 13 database opens at 14 with its phone workouts intact and their effect figures empty, ready to fill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutEffectMigrationTest {
    @Test
    fun oldWorkoutsStillLoad() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        // Built from the exported schema, so it's the database version 13 really made.
        val schema = JSONObject(File("schemas/org.openstrap.app.data.db.OpenStrapDatabase/13.json").readText()).getJSONObject("database")
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
            db.execSQL(
                "INSERT INTO app_workout (start, `end`, sportType, source, pauses, askToEnd, useGps, keepRoute, distanceFromSteps, laps, averageHeartRate) " +
                    "VALUES (100, 1900, 1, 0, '', 1, 1, 0, 0, '', 150)",
            )
            db.version = 13
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = room.healthDao()
            val w = dao.appWorkoutOnce(100)!!
            assertEquals(150, w.averageHeartRate)
            assertNull(w.epoc)
            assertNull(w.vo2max)

            dao.updateAppWorkoutEffect(100, 42.5f, 2.6f, 960, 48.2f)
            val after = dao.appWorkoutOnce(100)!!
            assertEquals(w.copy(epoc = 42.5f, aerobicTrainingEffect = 2.6f, recoveryMinutes = 960, vo2max = 48.2f), after)
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
