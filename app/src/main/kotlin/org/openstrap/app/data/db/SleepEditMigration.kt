package org.openstrap.app.data.db

import androidx.room.RenameTable
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 24 to 25: `nap_edit` becomes `sleep_edit` ([SleepEdit]), one table for sleep and naps that can also mark a
 * session deleted. Every row it had was a nap's times, so each keeps them as a nap, not deleted, edited now. Its own
 * class so the version it runs at can be renumbered on its own.
 */
@RenameTable(fromTableName = "nap_edit", toTableName = "sleep_edit")
class SleepEditMigration : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE sleep_edit SET nap = 1, deleted = 0, editedAt = ?", arrayOf<Any>(System.currentTimeMillis() / 1000))
    }
}
