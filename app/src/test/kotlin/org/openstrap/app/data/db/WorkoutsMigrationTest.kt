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

/**
 * A version 24 database opens at 25 ([WorkoutsMigration]) with its workouts and sets intact, no structure or log on
 * them, and the new tables there to write to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutsMigrationTest {
    @Test
    fun workoutsKeepTheirSetsAndGainTheNewTables() = runTest {
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
            db.execSQL("INSERT INTO app_workout (start, `end`, sportType, source, pauses, target) VALUES (1000, 2800, 52, 0, '', NULL)")
            db.execSQL("INSERT INTO app_workout_set (workoutStart, `index`, start, `end`, restEnd) VALUES (1000, 0, 1010, 1060, 1150)")
            db.version = 24
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = room.healthDao()
            val w = dao.appWorkoutOnce(1000)!!
            assertNull(w.structure)
            val set = dao.setsOnce(1000).single()
            assertEquals(1060L, set.end)
            assertNull(set.exercise)
            assertNull(set.reps)
            assertNull(set.weightKg)
            dao.upsertSets(listOf(set.copy(exercise = "Bench press", reps = 8, weightKg = 60f)))
            assertEquals(60f, dao.setsOnce(1000).single().weightKg)
            dao.upsertAppWorkout(w.copy(structure = "t:20/10x8"))
            assertEquals("t:20/10x8", dao.appWorkoutOnce(1000)!!.structure)
            dao.upsertExercise(ExerciseRow("Bench press", 8, 60f, 2800))
            assertEquals(listOf("Bench press"), dao.exercisesOnce().map { it.name })
            dao.upsertRounds(listOf(AppWorkoutRound(1000, 0, 1000, 1020, 1030, 171)))
            assertEquals(171, dao.roundsOnce(1000).single().peakBpm)
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
