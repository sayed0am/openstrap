package org.openstrap.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.BackupRepository
import org.openstrap.app.data.catching
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.WorkoutState
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.openstrap.app.data.settings.BackupFailure

/**
 * Automatic backup: once a day, while the phone charges, the same zip as Save a copy (every table and the settings)
 * goes into a folder the user chose, which Google Drive, Nextcloud or Syncthing may keep somewhere else too. Written
 * under a temporary name and renamed when complete ([BackupFiles]), the [BackupFiles.KEEP] newest kept. Never while a
 * workout records: the run waits and tries again. A failure is kept for the You page to say, never a notification.
 * [restored] and [workout] are the workout session's, so a backup never runs beside a recording.
 */
class AutoBackup(
    private val context: Context,
    private val settings: SettingsStore,
    private val restored: Flow<Boolean>,
    private val workout: StateFlow<WorkoutState>,
    private val backups: BackupRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    sealed interface Outcome {
        /** Written, as [name]. */
        data class Done(val name: String) : Outcome
        /** A workout is recording: nothing written, try again later. */
        data object Recording : Outcome
        /** Automatic backup is off. */
        data object Off : Outcome
        data class Failed(val failure: BackupFailure) : Outcome
    }

    suspend fun run(): Outcome = lock.withLock {
        val folder = settings.backup.first().folder ?: return@withLock Outcome.Off
        // Until the session has looked for a workout left recording, Idle doesn't mean none.
        withTimeoutOrNull(10_000) { restored.first { it } }
        if (workout.value is WorkoutState.Recording) return@withLock Outcome.Recording
        val now = System.currentTimeMillis() / 1000
        catching {
            val name = withContext(Dispatchers.IO) { write(Uri.parse(folder)) }
            settings.setBackupDone(now)
            Outcome.Done(name)
        }.getOrElse { e ->
            val failure = (e as? Failed)?.failure ?: BackupFiles.classify(e)
            if (e !is Failed) Log.w("unstrap", "backup: failed ($failure): $e")
            settings.setBackupFailed(failure, now)
            Outcome.Failed(failure)
        }
    }

    /** One backup now, the daily one's way. During a workout it waits for the end instead, through WorkManager. */
    suspend fun runNow(): Outcome = run().also { if (it == Outcome.Recording) afterWorkout(context) }

    /**
     * Automatic backup into the folder [uri] the user just picked: keeps the right to write there across restarts, lets
     * go of the folder before and starts the daily backup. False, with nothing changed, when Android won't keep the right.
     */
    suspend fun chooseFolder(uri: Uri): Boolean {
        val kept = runCatching { context.contentResolver.takePersistableUriPermission(uri, FOLDER_ACCESS) }
        kept.onFailure { Log.w("unstrap", "backup: folder permission not kept: $it") }
        if (kept.isFailure) return false
        val before = settings.backup.first().folder
        if (before != null && before != uri.toString()) releaseFolder(before)
        val name = withContext(Dispatchers.IO) { runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull() }
        settings.setBackupFolder(uri.toString(), name)
        schedule(context)
        return true
    }

    /** Stops automatic backup and lets go of its folder. The backups already there stay. */
    suspend fun turnOff() {
        cancel(context)
        settings.backup.first().folder?.let(::releaseFolder)
        settings.clearBackup()
    }

    private fun releaseFolder(folder: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(folder.toUri(), FOLDER_ACCESS) }
    }

    private class Failed(val failure: BackupFailure) : Exception(failure.name)

    private suspend fun write(folder: Uri): String {
        val resolver = context.contentResolver
        if (resolver.persistedUriPermissions.none { it.uri == folder && it.isWritePermission }) throw Failed(BackupFailure.PermissionLost)
        val dir = DocumentFile.fromTreeUri(context, folder)
        if (dir == null || !dir.exists() || !dir.isDirectory) throw Failed(BackupFailure.FolderGone)
        val today = LocalDate.now(zone)
        val name = BackupFiles.name(today)
        // What a run that died part way left.
        dir.listFiles().filter { it.name?.let(BackupFiles::isTemp) == true }.forEach { it.delete() }

        val temp = dir.createFile(TEMP_MIME, BackupFiles.tempName(today)) ?: throw Failed(BackupFailure.CantWrite)
        var written = false
        try {
            (resolver.openOutputStream(temp.uri, "w") ?: throw Failed(BackupFailure.CantWrite)).use { backups.writeZip(it) }
            // A second run on the same day replaces the first.
            dir.findFile(name)?.delete()
            if (!temp.renameTo(name)) copyAs(dir, temp, name)
            written = true
        } finally {
            // Failed or cancelled part way: the temp copy goes, and the failure itself is what's reported.
            if (!written) deleteQuietly(temp)
        }

        for (old in BackupFiles.toDelete(dir.listFiles().mapNotNull { it.name })) dir.findFile(old)?.delete()
        return name
    }

    /** For a provider that can't rename: the complete temp copied to [name], then deleted. A failed copy leaves nothing. */
    private fun copyAs(dir: DocumentFile, temp: DocumentFile, name: String) {
        val resolver = context.contentResolver
        val target = dir.createFile(ZIP_MIME, name) ?: throw Failed(BackupFailure.CantWrite)
        var copied = false
        try {
            val input = resolver.openInputStream(temp.uri)
            val output = input?.let { resolver.openOutputStream(target.uri, "w") }
            if (input == null || output == null) {
                input?.close()
                throw Failed(BackupFailure.CantWrite)
            }
            input.use { i -> output.use { i.copyTo(it) } }
            copied = true
        } finally {
            if (!copied) deleteQuietly(target)
        }
        temp.delete()
    }

    private fun deleteQuietly(file: DocumentFile) {
        runCatching { file.delete() }.onFailure { Log.w("unstrap", "backup: ${file.name} not deleted: $it") }
    }

    companion object {
        /** One backup at a time, the daily one and Back up now alike. */
        private val lock = Mutex()

        private const val DAILY = "auto-backup"
        private const val AFTER_WORKOUT = "auto-backup-retry"
        private const val TEMP_MIME = "application/octet-stream"
        private const val ZIP_MIME = "application/zip"
        private const val FOLDER_ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** Starts the daily backup; a no-op when it's already scheduled. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Back up now, asked during a workout: runs once it's over, trying again every half hour until then. */
        fun afterWorkout(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(AFTER_WORKOUT, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(DAILY)
            WorkManager.getInstance(context).cancelUniqueWork(AFTER_WORKOUT)
        }
    }
}

/** The daily backup's worker ([AutoBackup.schedule]); a workout recording sends it back to wait. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OpenStrapApp).container
        val backup = AutoBackup(
            applicationContext, container.settings, container.workout.restored, container.workout.state, container.backups,
        )
        // A failure is kept for the You page, and tomorrow's run tries again.
        return when (backup.run()) {
            AutoBackup.Outcome.Recording -> Result.retry()
            else -> Result.success()
        }
    }
}
