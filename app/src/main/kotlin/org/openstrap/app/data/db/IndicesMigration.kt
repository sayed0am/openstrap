package org.openstrap.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Indices for the reads that pick rows by their end, version 26 to 27: sleep sessions by nap and end, phone workouts
 * and dismissed stretches by end. Indices only, so no row changes. A named object like [WorkoutsMigration], so it can
 * be renumbered on its own. The SQL is Room's own, from the schema exported for this version.
 */
@Suppress("MagicNumber") // The versions it runs between are what the migration is.
object IndicesMigration : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sleep_session_nap_end` ON `sleep_session` (`nap`, `end`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_workout_end` ON `app_workout` (`end`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_workout_dismissed_end` ON `app_workout_dismissed` (`end`)")
    }
}
