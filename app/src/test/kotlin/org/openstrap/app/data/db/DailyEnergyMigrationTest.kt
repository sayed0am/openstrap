package org.openstrap.app.data.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.MigrationHarness
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * A version 19 database opens at 20 with its phone workouts intact and without kcal, and an empty daily energy table
 * that keeps one row per day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyEnergyMigrationTest {
    @get:Rule val migrations = MigrationHarness()

    @Test
    fun dailyEnergyAndWorkoutKcalAfterMigration() = runTest {
        val room = migrations.open(migrations.create(19) {
            execSQL("INSERT INTO app_workout (start, `end`, sportType, source, pauses, averageHeartRate) VALUES (1000, 2800, 1, 0, '', 140)")
        })
        val dao = room.healthDao()
        val w = dao.appWorkoutOnce(1000)!!
        assertEquals(140, w.averageHeartRate)
        assertNull(w.activeKcal)
        assertNull(w.totalKcal)
        dao.updateAppWorkoutKcal(1000, 210f, 260f)
        assertEquals(260f, dao.appWorkoutOnce(1000)!!.totalKcal)

        assertEquals(emptyList<LocalDate>(), dao.dailyEnergyDaysOnce())
        fun row(day: Long, total: Float) = DailyEnergyRow(LocalDate.ofEpochDay(day), 1, total, 400f, 1650f, 1440, false, 120f, 200f, List(24) { "1.0" }.joinToString(","))
        dao.insertDailyEnergy(listOf(row(1, 2100f), row(2, 2300f)))
        // The same day again replaces it.
        dao.insertDailyEnergy(listOf(row(2, 2350f)))
        assertEquals(listOf(2100f, 2350f), dao.dailyEnergyOnce(LocalDate.EPOCH, LocalDate.ofEpochDay(3)).map { it.totalKcal })
        assertEquals(24, dao.dailyEnergyOnce(LocalDate.ofEpochDay(1), LocalDate.ofEpochDay(1)).single().hourlyKcal.size)
    }
}
