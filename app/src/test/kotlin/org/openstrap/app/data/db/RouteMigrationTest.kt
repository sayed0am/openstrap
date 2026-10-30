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
import org.openstrap.app.data.Route
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A version 12 database, as phones have it, opens at 13 with its kept routes intact and the new fields empty. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouteMigrationTest {
    @Test
    fun oldRoutesStillLoad() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "openstrap-migration-${System.nanoTime()}.db")
        // Built from the exported schema, so it's the database version 12 really made.
        val schema = JSONObject(File("schemas/org.openstrap.app.data.db.OpenStrapDatabase/12.json").readText()).getJSONObject("database")
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
            db.execSQL("INSERT INTO app_workout_route (workoutStart, timestamp, lat, lon, accuracy) VALUES (100, 105, 51.5, -0.1, 4.5)")
            db.version = 12
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java, file.absolutePath)
            .addMigrations(*OpenStrapDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val rows = room.healthDao().routeOnce(100)
            assertEquals(listOf(Route.Point(105, 51.5, -0.1, 4.5f)), rows.map { it.point })
            assertNull(rows.single().speed)

            // New fixes keep everything the phone gave.
            val fix = Route.Point(106, 51.5, -0.1, 4f, speed = 1.4f, bearing = 90f, altitude = 31.5, speedAccuracy = 0.3f, bearingAccuracy = 8f)
            room.healthDao().insertRoutePoints(listOf(AppWorkoutRoutePoint.of(100, fix)))
            assertEquals(fix, room.healthDao().routeOnce(100).last().point)
        } finally {
            room.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
