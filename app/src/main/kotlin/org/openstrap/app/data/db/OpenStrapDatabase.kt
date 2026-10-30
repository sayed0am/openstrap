package org.openstrap.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HeartRateSample::class,
        ActivityMinute::class,
        SleepSession::class,
        SleepStage::class,
        Spo2Sample::class,
        StressSample::class,
        HrvSample::class,
        RestingHeartRate::class,
        SkinTemperature::class,
        BreathRate::class,
        PaiSample::class,
        HrvSdnnSample::class,
        Workout::class,
        WorkoutHeartRate::class,
        SportEffectRow::class,
        StrapReadinessRow::class,
        SleepSpo2Sample::class,
        OsaEventRow::class,
        OsaResultRow::class,
        DailyMaxHr::class,
        RecoveryHeartRateRow::class,
        ActivityExtendedMinute::class,
        RawRecord::class,
        HealthAlertRow::class,
        CheckInRow::class,
        DayTagRow::class,
        NightMetricsRow::class,
        AppWorkout::class,
        AppWorkoutHeartRate::class,
        DismissedWorkout::class,
        AppWorkoutRoutePoint::class,
        AppWorkoutSet::class,
        WearEventRow::class,
        HabitAgeWeekRow::class,
        DeletedWorkout::class,
        DailyEnergyRow::class,
        SleepEdit::class,
    ],
    version = 25,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14), AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16), AutoMigration(from = 16, to = 17), AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19), AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21), AutoMigration(from = 21, to = 22), AutoMigration(from = 22, to = 23),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25, spec = SleepEditMigration::class),
    ],
)
abstract class OpenStrapDatabase : RoomDatabase() {
    abstract fun healthDao(): HealthDao

    companion object {
        fun create(context: Context): OpenStrapDatabase =
            Room.databaseBuilder(context, OpenStrapDatabase::class.java, "openstrap.db").build()
    }
}
