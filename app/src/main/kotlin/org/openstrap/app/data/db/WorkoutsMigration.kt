package org.openstrap.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Workouts, first release (workouts spec 4), in one migration: a workout's rounds or periods ([AppWorkout.structure]),
 * a run's fastest stretches ([AppWorkout.stretches]), a strength set's log ([AppWorkoutSet.exercise], reps, weight), and the new tables for rounds, saved workouts, the
 * exercise list, personal bests, badges and weekly goal results. Version 25 to 26, after the sleep edits took 25. A
 * named object rather than an auto migration, so it can be renumbered on its own. The SQL is Room's own, from the
 * schema exported for this version.
 */
object WorkoutsMigration : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `app_workout` ADD COLUMN `structure` TEXT")
        db.execSQL("ALTER TABLE `app_workout` ADD COLUMN `stretches` TEXT")
        db.execSQL("ALTER TABLE `app_workout_set` ADD COLUMN `exercise` TEXT")
        db.execSQL("ALTER TABLE `app_workout_set` ADD COLUMN `reps` INTEGER")
        db.execSQL("ALTER TABLE `app_workout_set` ADD COLUMN `weightKg` REAL")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_workout_round` (`workoutStart` INTEGER NOT NULL, `index` INTEGER NOT NULL, " +
                "`workStart` INTEGER NOT NULL, `workEnd` INTEGER, `restEnd` INTEGER, `peakBpm` INTEGER, PRIMARY KEY(`workoutStart`, `index`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_workout` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`sport` INTEGER NOT NULL, `structure` TEXT NOT NULL, `goal` TEXT, `createdAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exercise` (`name` TEXT NOT NULL, `lastReps` INTEGER, `lastKg` REAL, `usedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`name`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `personal_best` (`kind` TEXT NOT NULL, `value` REAL NOT NULL, `date` INTEGER NOT NULL, " +
                "`workoutStart` INTEGER, PRIMARY KEY(`kind`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `badge` (`id` TEXT NOT NULL, `earnedAt` INTEGER NOT NULL, `workoutStart` INTEGER, `day` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `week_result` (`weekStart` INTEGER NOT NULL, `goal` INTEGER NOT NULL, `count` INTEGER NOT NULL, " +
                "`met` INTEGER NOT NULL, PRIMARY KEY(`weekStart`))",
        )
    }
}
