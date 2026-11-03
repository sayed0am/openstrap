package org.openstrap.app.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import org.openstrap.app.data.db.OpenStrapDatabase
import java.io.Closeable
import java.io.File

/**
 * A copy of the database as it was at one moment, for a backup to read (data integrity item 23): a sync that writes
 * while the zip is being written can't leave one table in it from before and another from after. `VACUUM INTO` makes
 * the copy from committed data only, and holds the database only while it copies (a second or two for a year of
 * minutes), not while the zip is written, so a sync waits that long at most. [close] deletes the copy.
 */
class BackupSnapshot private constructor(private val file: File, val database: OpenStrapDatabase) : Closeable {
    override fun close() {
        database.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    companion object {
        /** The copy's file in the app's cache, made again for every backup. */
        private const val FILE = "backup-snapshot.db"

        /** Copies [source] into the cache and opens the copy. Blocking: call it off the main thread. */
        fun take(context: Context, source: OpenStrapDatabase): BackupSnapshot {
            val file = File(context.cacheDir, FILE)
            SQLiteDatabase.deleteDatabase(file)
            // VACUUM takes no bind arguments, so the path, which is the app's own, goes in quoted.
            source.openHelper.writableDatabase.execSQL("VACUUM INTO '${file.path.replace("'", "''")}'")
            val copy = Room.databaseBuilder(context, OpenStrapDatabase::class.java, file.path).build()
            return BackupSnapshot(file, copy)
        }
    }
}
