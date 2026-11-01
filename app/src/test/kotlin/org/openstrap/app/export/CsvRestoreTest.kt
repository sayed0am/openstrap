package org.openstrap.app.export

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutRound
import org.openstrap.app.data.db.BadgeRow
import org.openstrap.app.data.db.ExerciseRow
import org.openstrap.app.data.db.SavedWorkout
import org.openstrap.app.data.db.WeekResult
import org.openstrap.app.data.db.DailyEnergyRow
import org.openstrap.app.data.db.DeletedWorkout
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.RecoveryHeartRateRow
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.data.db.WearEventRow
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.db.WorkoutHeartRate
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Export and restore against a real database: the backup fills the days before the strap's history, and no others. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CsvRestoreTest {
    private val day = 1792195200L // 2026-10-17T00:00Z
    private val week = 7 * 86_400L

    private lateinit var old: OpenStrapDatabase
    private lateinit var phone: OpenStrapDatabase

    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OpenStrapDatabase::class.java)
        .allowMainThreadQueries().build()

    @Before fun open() { old = db(); phone = db() }
    @After fun close() { old.close(); phone.close() }

    private suspend fun backup(db: OpenStrapDatabase) = ByteArrayOutputStream().also { CsvExporter(db.healthDao()).exportZip(it) }.toByteArray()
    private suspend fun restore(zip: ByteArray) = CsvImporter(phone, ZoneId.of("UTC")).importZip(ByteArrayInputStream(zip))

    /** Every file of an export, by name, for comparing two databases in full. */
    private suspend fun contents(db: OpenStrapDatabase): Map<String, String> {
        val zip = ZipInputStream(ByteArrayInputStream(backup(db)))
        return buildMap { while (true) { val e = zip.nextEntry ?: break; put(e.name, zip.readBytes().toString(Charsets.UTF_8)) } }
    }

    private suspend fun HealthDao.hr() = heartRateOnce(0, Long.MAX_VALUE / 2)
    private suspend fun HealthDao.sleeps() = sleepSessionsOnce(0, Long.MAX_VALUE / 2)
    private fun workout(start: Long, end: Long) = Workout(start, end, 1, (end - start).toInt(), null, null, null, null, null, null, null, null, null)

    /** A month on the old phone: a heart rate every hour, a night's sleep with stages, and a workout every week. */
    private suspend fun fillOldPhone() {
        val d = old.healthDao()
        d.insertHeartRate((0 until 30 * 24).map { HeartRateSample(day - 30 * 86_400L + it * 3600L, 60 + it % 20) })
        for (n in 0 until 30) {
            val start = day - (30 - n) * 86_400L - 2 * 3600
            d.insertSleepSessions(listOf(SleepSession(start, start + 8 * 3600, start + 2 * 3600, score = 80)))
            d.insertSleepStages(listOf(SleepStage(start, start + 4 * 3600, start, SleepStage.DEEP), SleepStage(start + 4 * 3600, start + 8 * 3600, start, SleepStage.LIGHT)))
        }
        for (w in 0 until 4) {
            val start = day - (4 - w) * week + 17 * 3600
            d.insertWorkouts(listOf(workout(start, start + 3600)))
            d.insertWorkoutHeartRate(listOf(WorkoutHeartRate(start, start + 60, 120), WorkoutHeartRate(start, start + 120, 130)))
        }
        // The strap off for an hour, then the link dropping.
        val off = day - 3 * 86_400L
        d.insertWearEvents(listOf(WearEventRow(off, false), WearEventRow(off + 3600, true), WearEventRow(off + 7200, null)))
    }

    @Test fun `a backup restored onto an empty phone matches the phone it came from`() = runTest {
        fillOldPhone()
        val result = restore(backup(old))
        assertEquals(contents(old), contents(phone))
        assertEquals(0, result.present)
        assertEquals(0, result.skipped)
    }

    @Test fun `restoring twice changes nothing`() = runTest {
        fillOldPhone()
        val zip = backup(old)
        restore(zip)
        val once = contents(phone)
        val again = restore(zip)
        assertEquals(once, contents(phone))
        assertEquals(0, again.total)
    }

    @Test fun `heart rate the strap re-sent keeps its values, and the backup fills every minute it lacks`() = runTest {
        fillOldPhone()
        // The strap's last week on the new phone: its first day starts at 10:00, and it has every other hour.
        val strapFrom = day - week
        val strap = old.healthDao().hr().filter { it.timestamp >= strapFrom + 10 * 3600 && (it.timestamp / 3600) % 2 == 0L }
        phone.healthDao().insertHeartRate(strap.map { it.copy(bpm = 200) })
        val result = restore(backup(old))

        val hr = phone.healthDao().hr()
        val strapTimes = strap.mapTo(HashSet()) { it.timestamp }
        assertEquals(old.healthDao().hr().map { it.timestamp }, hr.map { it.timestamp })
        assertTrue(hr.filter { it.timestamp in strapTimes }.all { it.bpm == 200 })
        assertEquals(old.healthDao().hr().filter { it.timestamp !in strapTimes }, hr.filter { it.timestamp !in strapTimes })
        assertEquals(strap.size, result.present)
    }

    @Test fun `a night the strap re-synced with another start isn't doubled, and keeps its own stages`() = runTest {
        fillOldPhone()
        val night = old.healthDao().sleeps().last()
        val resynced = night.copy(start = night.start + 60, score = 55)
        phone.healthDao().insertSleepSessions(listOf(resynced))
        phone.healthDao().insertSleepStages(listOf(SleepStage(resynced.start, resynced.end, resynced.start, SleepStage.REM)))
        restore(backup(old))

        val sleeps = phone.healthDao().sleeps()
        assertEquals(30, sleeps.size)
        assertTrue(sleeps.none { it.start == night.start })
        assertEquals(55, sleeps.single { it.start == resynced.start }.score)
        assertEquals(listOf(SleepStage.REM), phone.healthDao().sleepStagesOnce(resynced.start).map { it.stage })
        // The nights before it came back with their stages.
        assertEquals(2, phone.healthDao().sleepStagesOnce(sleeps.first().start).size)
    }

    @Test fun `a workout already on the phone keeps its heart rate, and the others come back with theirs`() = runTest {
        fillOldPhone()
        val last = old.healthDao().workouts().first().maxBy { it.start }
        phone.healthDao().insertWorkouts(listOf(last))
        phone.healthDao().insertWorkoutHeartRate(listOf(WorkoutHeartRate(last.start, last.start + 30, 99)))
        restore(backup(old))

        val hr = phone.healthDao().workoutHeartRateAll()
        assertEquals(listOf(99), hr.filter { it.workoutStart == last.start }.map { it.bpm })
        assertEquals(6, hr.count { it.workoutStart != last.start })
        assertEquals(4, phone.healthDao().workouts().first().size)
    }

    @Test fun `a strap workout deleted on the old phone is still deleted after a restore`() = runTest {
        fillOldPhone()
        val first = old.healthDao().workouts().first().minBy { it.start }
        old.healthDao().deleteStrapWorkout(first.start, day)
        restore(backup(old))

        assertEquals(old.healthDao().deletedWorkoutsAll(), phone.healthDao().deletedWorkoutsAll())
        assertEquals(listOf(first.start), phone.healthDao().deletedWorkoutsAll().map { it.start })
        assertEquals(3, phone.healthDao().workouts().first().size)
        assertEquals(contents(old), contents(phone))
    }

    @Test fun `a workout the backup has a tombstone for doesn't come back, nor its heart rate or own sport effect`() = runTest {
        fillOldPhone()
        val first = old.healthDao().workouts().first().minBy { it.start }
        val effectEnd = first.end + 60
        old.healthDao().insertSportEffects(listOf(SportEffectRow(effectEnd, 600, null, null, null, null, null, null, null)))
        old.healthDao().insertRecoveryHeartRate(listOf(RecoveryHeartRateRow(first.start, byteArrayOf(1, 2))))
        // The tombstone and the workout in one backup, as if the row had been left behind.
        old.healthDao().insertDeletedWorkouts(listOf(DeletedWorkout(first.start, effectEnd, day)))
        val result = restore(backup(old))

        val d = phone.healthDao()
        assertNull(d.workoutOnce(first.start))
        assertEquals(3, d.workouts().first().size)
        assertTrue(d.workoutHeartRateAll().none { it.workoutStart == first.start })
        assertEquals(6, d.workoutHeartRateAll().size)
        assertTrue(d.recoveryHeartRateAll().isEmpty())
        assertTrue(d.sportEffects().first().isEmpty())
        assertEquals(listOf(first.start), d.deletedWorkoutsAll().map { it.start })
        assertEquals(0, result.skipped)
    }

    @Test fun `a tombstone already on the phone keeps the backup's copy of that workout out`() = runTest {
        fillOldPhone()
        val first = old.healthDao().workouts().first().minBy { it.start }
        phone.healthDao().insertDeletedWorkouts(listOf(DeletedWorkout(first.start, null, day)))
        restore(backup(old))
        assertNull(phone.healthDao().workoutOnce(first.start))
        assertEquals(3, phone.healthDao().workouts().first().size)
    }

    @Test fun `every tombstone in the backup comes back, even after one the phone already has`() = runTest {
        fillOldPhone()
        val (first, second) = old.healthDao().workouts().first().sortedBy { it.start }
        old.healthDao().deleteStrapWorkout(second.start, day)
        val mine = DeletedWorkout(first.start, null, day + 5)
        phone.healthDao().insertDeletedWorkouts(listOf(mine))
        restore(backup(old))
        val tombstones = phone.healthDao().deletedWorkoutsAll().associateBy { it.start }
        assertEquals(setOf(first.start, second.start), tombstones.keys)
        assertEquals(mine, tombstones[first.start])
        assertNull(phone.healthDao().workoutOnce(second.start))
    }

    @Test fun `a backup from before deleted workouts were kept still restores`() = runTest {
        fillOldPhone()
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                val src = ZipInputStream(ByteArrayInputStream(backup(old)))
                while (true) {
                    val e = src.nextEntry ?: break
                    if (e.name == "workout_deleted.csv") continue
                    z.putNextEntry(ZipEntry(e.name)); z.write(src.readBytes()); z.closeEntry()
                }
            }
        }.toByteArray()
        val result = restore(zip)
        assertTrue("workout_deleted.csv" !in result.rows)
        assertEquals(4, phone.healthDao().workouts().first().size)
        assertEquals(8, phone.healthDao().workoutHeartRateAll().size)
        assertTrue(phone.healthDao().deletedWorkoutsAll().isEmpty())
    }

    @Test fun `a zip that isn't an export changes nothing`() = runTest {
        phone.healthDao().insertHeartRate(listOf(HeartRateSample(day, 70)))
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("notes.txt")); it.write("hello".toByteArray()); it.closeEntry() }
        }.toByteArray()
        try { restore(zip); fail("restored a zip that isn't an export") } catch (_: IllegalArgumentException) {}
        assertEquals(listOf(HeartRateSample(day, 70)), phone.healthDao().hr())
    }

    @Test fun `daily energy and a phone workout's kcal target come back, before the phone's own days`() = runTest {
        fun energy(d: Long, total: Float) = DailyEnergyRow(d, d + 86_399, total, 450f, 1650f, 1440, false, 120f, 200f, List(24) { "%.1f".format(java.util.Locale.ROOT, it * 1.5f) }.joinToString(","))
        old.healthDao().insertDailyEnergy(listOf(energy(day - 2 * 86_400, 2100f), energy(day - 86_400, 2200f), energy(day, 2300f)))
        val w = AppWorkout(day - 86_400 + 17 * 3600, day - 86_400 + 18 * 3600, 1, target = "k:300", activeKcal = 320f, totalKcal = 410f, targetReachedAt = day - 86_400 + 17 * 3600 + 2900)
        old.healthDao().insertAppWorkouts(listOf(w))
        // The phone already worked out today: it keeps its own row.
        phone.healthDao().insertDailyEnergy(listOf(energy(day, 999f)))
        restore(backup(old))
        assertEquals(listOf(2100f, 2200f, 999f), phone.healthDao().dailyEnergyOnce(0, Long.MAX_VALUE / 2).map { it.totalKcal })
        assertEquals(old.healthDao().dailyEnergyOnce(0, day - 1), phone.healthDao().dailyEnergyOnce(0, day - 1))
        assertEquals(24, phone.healthDao().dailyEnergyOnce(day - 86_400, day - 86_400).single().hourlyKcal.size)
        assertEquals(listOf(w), phone.healthDao().appWorkoutsAll())
    }
    @Test fun `badges, past weeks, saved workouts, exercises and rounds come back, once`() = runTest {
        val d = old.healthDao()
        val w = AppWorkout(day - 86_400 + 17 * 3600, day - 86_400 + 18 * 3600, 30)
        d.insertAppWorkouts(listOf(w))
        d.upsertRounds(listOf(AppWorkoutRound(w.start, 0, w.start, w.start + 60, w.start + 90, 168), AppWorkoutRound(w.start, 1, w.start + 90, w.start + 150, null, 172)))
        d.insertBadges(listOf(BadgeRow("first_workout", w.end!!, workoutStart = w.start), BadgeRow("steps_10k", day - 3 * 86_400, day = 20_740)))
        d.upsertWeekResults(listOf(WeekResult(20_733, 3, 4, true), WeekResult(20_740, 5, 2, false)))
        d.upsertSavedWorkout(SavedWorkout(name = "Tuesday intervals", sport = 30, structure = "i:6x60/30", createdAt = day - 5 * 86_400))
        d.upsertExercise(ExerciseRow("Bench press", 8, 60f, w.end!!))
        // The phone already logged the bench press since: it keeps its own last reps and weight.
        phone.healthDao().upsertExercise(ExerciseRow("Bench press", 5, 70f, day))
        val zip = backup(old)
        restore(zip)
        val p = phone.healthDao()
        assertEquals(d.badgesOnce(), p.badgesOnce())
        assertEquals(d.weekResultsOnce(), p.weekResultsOnce())
        assertEquals(d.roundsOnce(w.start), p.roundsOnce(w.start))
        assertEquals(listOf("Tuesday intervals"), p.savedWorkoutsOnce().map { it.name })
        assertEquals(listOf(ExerciseRow("Bench press", 5, 70f, day)), p.exercisesOnce())
        val once = contents(phone)
        restore(zip)
        assertEquals(once, contents(phone))
    }
}
