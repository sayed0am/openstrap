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

/** A version 18 database opens at 19 with its workouts intact and an empty table of strap workouts deleted in OpenStrap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeletedWorkoutMigrationTest {
    @Test
    fun deletedWorkoutsAfterMigration() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        // Built from the exported schema, so it's the database version 18 really made.
        val schema = JSONObject(File("schemas/org.openstrap.app.data.db.OpenStrapDatabase/18.json").readText()).getJSONObject("database")
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
            db.execSQL("INSERT INTO workout (start, `end`, sportType, subtype) VALUES (1000, 2000, 1, 1)")
            db.version = 18
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = room.healthDao()
            assertEquals(listOf(1000L), dao.workoutsOnce(0, Long.MAX_VALUE).map { it.start })
            assertEquals(emptyList<DeletedWorkout>(), dao.deletedWorkoutsAll())
            dao.insertDeletedWorkouts(listOf(DeletedWorkout(1000, null, 5)))
            // Deleting the same workout again replaces the row.
            dao.insertDeletedWorkouts(listOf(DeletedWorkout(1000, 1900, 6)))
            assertEquals(listOf(DeletedWorkout(1000, 1900, 6)), dao.deletedWorkoutsAll())
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
