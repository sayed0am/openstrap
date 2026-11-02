package org.openstrap.app.data

import android.net.Uri
import java.io.OutputStream

/**
 * Save a copy and Restore: every table and the settings as one zip, at a place the user picked. The view model turns a
 * [BackupResult] into what the screen says, so nothing here knows the copy.
 */
interface BackupRepository {
    /** The whole zip, records and settings, written to [to]. */
    suspend fun exportCsv(to: Uri): BackupResult

    /** A zip from [exportCsv] or automatic backup, restored into what this phone is missing. Safe to run twice. */
    suspend fun importCsv(from: Uri): BackupResult

    /** The settings alone, as the zip's settings file. */
    suspend fun exportSettings(to: Uri): BackupResult

    /** Settings from [exportSettings], restored where this phone hasn't set them. */
    suspend fun importSettings(from: Uri): BackupResult

    /**
     * The zip [exportCsv] writes, into [out], throwing on failure. For automatic backup, which writes through a temp
     * file and names the failure itself.
     */
    suspend fun writeZip(out: OutputStream)
}

sealed interface BackupResult {
    data object Saved : BackupResult

    /** [total] records came back, [settings] says whether any setting did, and [skipped] rows couldn't be read. */
    data class Restored(val total: Int, val settings: Boolean, val skipped: Int) : BackupResult

    /** What went wrong, for the view model to word and to log. */
    data class Failed(val error: Throwable) : BackupResult
}
