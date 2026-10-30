package org.openstrap.app.ui.device

import org.openstrap.app.R
import org.openstrap.app.data.settings.BackupState
import org.openstrap.app.export.AutoBackup
import org.openstrap.app.export.BackupFiles
import org.openstrap.app.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words of backup on the You and Backup pages and in its messages. */
object BackupCopy {
    /** The Automatic backup row's value: off, waiting for the first one, the last one, or why the last one failed. */
    fun status(state: BackupState, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (!state.on) return tr(R.string.backup_copy_off_a_zip_each)
        state.failed?.let { return tr(R.string.backup_copy_last_backup_failed, failure(it)) }
        val done = state.lastDone ?: return tr(R.string.backup_copy_each_day_while_the)
        return tr(R.string.backup_copy_last_backup, whenDone(done, now, zone, locale))
    }

    /** The You page's Backup row: off, waiting for the first one, the last one, or that it failed (the page says why). */
    fun summary(state: BackupState, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (!state.on) return tr(R.string.common_off)
        if (state.failed != null) return tr(R.string.backup_copy_last_backup_failed_2)
        val done = state.lastDone ?: return tr(R.string.backup_copy_on_no_backup_yet)
        return tr(R.string.backup_copy_last_backup, whenDone(done, now, zone, locale))
    }

    /** Why a backup failed, said once. */
    fun failure(f: BackupFiles.Failure): String = when (f) {
        BackupFiles.Failure.FolderGone -> tr(R.string.backup_copy_the_folder_is_no)
        BackupFiles.Failure.PermissionLost -> tr(R.string.backup_copy_openstrap_can_no_longer)
        BackupFiles.Failure.NoSpace -> tr(R.string.backup_copy_there_isnt_enough_space)
        BackupFiles.Failure.CantWrite -> tr(R.string.backup_copy_openstrap_couldnt_write_to)
        BackupFiles.Failure.Other -> tr(R.string.backup_copy_it_tries_again_tomorrow)
    }

    /** "today at 03:12", "yesterday at 03:12", "on Tue 20 Oct". */
    fun whenDone(epochSeconds: Long, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochSecond(epochSeconds).atZone(zone)
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val clock = at.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        return when (at.toLocalDate()) {
            today -> tr(R.string.backup_copy_today_at, clock)
            today.minusDays(1) -> tr(R.string.backup_copy_yesterday_at, clock)
            else -> tr(R.string.backup_copy_on, at.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)))
        }
    }

    /**
     * Where the folder picker opens when backup is first turned on. The top of the phone's storage can't be chosen
     * ("Can't use this folder"), Documents can, and a folder made inside it too.
     */
    val DOCUMENTS: android.net.Uri
        get() = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

    /**
     * What a restore says, short enough for a toast: what came back, and rows that couldn't be read. Records already on
     * this phone aren't counted, since restoring only fills gaps.
     */
    fun restored(records: Int, settings: Boolean, skipped: Int): String {
        val what = when {
            records > 0 && settings -> tr(R.string.backup_copy_restored_records_and_your, records)
            records > 0 -> tr(R.string.backup_copy_restored_records, records)
            settings -> tr(R.string.backup_copy_restored_your_settings_your)
            else -> tr(R.string.backup_copy_nothing_to_restore_its)
        }
        return if (skipped > 0) tr(R.string.backup_copy_rows_couldnt_be_read, what, skipped) else what
    }

    /** The Backup folder row's value. */
    fun folder(state: BackupState): String = state.folderName ?: tr(R.string.backup_copy_the_folder_you_chose)

    /** What Back up now says when it's done. */
    fun outcome(o: AutoBackup.Outcome): String = when (o) {
        is AutoBackup.Outcome.Done -> tr(R.string.backup_copy_backed_up_as, o.name)
        AutoBackup.Outcome.Recording -> tr(R.string.backup_copy_a_workout_is_recording)
        AutoBackup.Outcome.Off -> tr(R.string.backup_copy_automatic_backup_is_off)
        is AutoBackup.Outcome.Failed -> tr(R.string.backup_copy_couldnt_back_up, failure(o.failure))
    }
}
