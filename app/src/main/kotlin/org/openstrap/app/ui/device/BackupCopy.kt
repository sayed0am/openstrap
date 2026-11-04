package org.openstrap.app.ui.device

import org.openstrap.app.R
import org.openstrap.app.data.settings.BackupState
import org.openstrap.app.export.AutoBackup
import org.openstrap.app.i18n.Strings
import java.time.ZoneId
import java.util.Locale
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.Units
import org.openstrap.app.data.settings.BackupFailure

/** The words of backup on the You and Backup pages and in its messages. The text is in res/values/strings_you_data.xml. */
object BackupCopy {
    /** The Automatic backup row's value: off, waiting for the first one, the last one, or why the last one failed. */
    fun status(
        state: BackupState,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        strings: Strings = Strings.app,
    ): String {
        if (!state.on) return strings.get(R.string.you_backup_status_off)
        state.failed?.let { return strings.format(R.string.you_backup_status_failed, failure(it, strings)) }
        val done = state.lastDone ?: return strings.get(R.string.you_backup_status_waiting)
        return strings.format(R.string.you_backup_last, whenDone(done, now, zone, locale))
    }

    /** The You page's Backup row: off, waiting for the first one, the last one, or that it failed (the page says why). */
    fun summary(
        state: BackupState,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        strings: Strings = Strings.app,
    ): String {
        if (!state.on) return strings.get(R.string.you_value_off)
        if (state.failed != null) return strings.get(R.string.you_backup_summary_failed)
        val done = state.lastDone ?: return strings.get(R.string.you_backup_summary_waiting)
        return strings.format(R.string.you_backup_last, whenDone(done, now, zone, locale))
    }

    /** Why a backup failed, said once. */
    fun failure(f: BackupFailure, strings: Strings = Strings.app): String = strings.get(
        when (f) {
            BackupFailure.FolderGone -> R.string.you_backup_failure_folder_gone
            BackupFailure.PermissionLost -> R.string.you_backup_failure_permission
            BackupFailure.NoSpace -> R.string.you_backup_failure_no_space
            BackupFailure.CantWrite -> R.string.you_backup_failure_cant_write
            BackupFailure.Other -> R.string.you_backup_failure_other
        },
    )

    /** "today at 03:12", "yesterday at 03:12", "on Tue 20 Oct": [DayWords.whenDone], on the phone's clock. */
    fun whenDone(epochSeconds: Long, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        DayWords.whenDone(epochSeconds, now, zone, locale = locale)

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
    fun restored(
        records: Int, settings: Boolean, skipped: Int, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): String {
        val count = Units.count(records.toLong(), locale)
        val what = when {
            records > 0 && settings -> strings.plural(R.plurals.you_backup_restored_records_settings, records, count)
            records > 0 -> strings.plural(R.plurals.you_backup_restored_records, records, count)
            settings -> strings.get(R.string.you_backup_restored_settings)
            else -> strings.get(R.string.you_backup_restored_nothing)
        }
        if (skipped <= 0) return what
        val rows = strings.plural(R.plurals.you_backup_restored_skipped, skipped, Units.count(skipped.toLong(), locale))
        return strings.format(R.string.you_backup_restored_with_skipped, what, rows)
    }

    /** The Backup folder row's value. */
    fun folder(state: BackupState, strings: Strings = Strings.app): String =
        state.folderName ?: strings.get(R.string.you_backup_folder_unknown)

    /** What Back up now says when it's done. */
    fun outcome(o: AutoBackup.Outcome, strings: Strings = Strings.app): String = when (o) {
        is AutoBackup.Outcome.Done -> strings.format(R.string.you_backup_outcome_done, o.name)
        AutoBackup.Outcome.Recording -> strings.get(R.string.you_backup_outcome_recording)
        AutoBackup.Outcome.Off -> strings.get(R.string.you_backup_outcome_off)
        is AutoBackup.Outcome.Failed -> strings.format(R.string.you_backup_outcome_failed, failure(o.failure, strings))
    }
}
