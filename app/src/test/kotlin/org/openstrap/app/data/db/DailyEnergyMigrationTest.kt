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
 * A version 19 database opens at 20 with its phone workouts intact and without kcal, and an empty daily energy table
 * that keeps one row per day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyEnergyMigrationTest {
    @Test
    fun dailyEnergyAndWorkoutKcalAfterMigration() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        // Built from the exported schema, so it's the database version 19 really made.
        val schema = JSONObject(File("schemas/org.openstrap.app.data.db.OpenStrapDatabase/19.json").readText()).getJSONObject("database")
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
            db.execSQL("INSERT INTO app_workout (start, `end`, sportType, source, pauses, averageHeartRate) VALUES (1000, 2800, 1, 0, '', 140)")
            db.version = 19
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = room.healthDao()
            val w = dao.appWorkoutOnce(1000)!!
            assertEquals(140, w.averageHeartRate)
            assertNull(w.activeKcal)
            assertNull(w.totalKcal)
            dao.updateAppWorkoutKcal(1000, 210f, 260f)
            assertEquals(260f, dao.appWorkoutOnce(1000)!!.totalKcal)

            assertEquals(emptyList<Long>(), dao.dailyEnergyDaysOnce())
            fun row(day: Long, total: Float) = DailyEnergyRow(day, 1, total, 400f, 1650f, 1440, false, 120f, 200f, List(24) { "1.0" }.joinToString(","))
            dao.insertDailyEnergy(listOf(row(86_400, 2100f), row(172_800, 2300f)))
            // The same day again replaces it.
            dao.insertDailyEnergy(listOf(row(172_800, 2350f)))
            assertEquals(listOf(2100f, 2350f), dao.dailyEnergyOnce(0, 200_000).map { it.totalKcal })
            assertEquals(24, dao.dailyEnergyOnce(86_400, 86_400).single().hourlyKcal.size)
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
