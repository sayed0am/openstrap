package org.openstrap.app.export

import android.content.Context
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openstrap.app.AppContainer
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.catching
import org.openstrap.app.device.WorkoutState
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Automatic backup: once a day, while the phone charges, the same zip as Save a copy (every table and the settings)
 * goes into a folder the user chose, which Google Drive, Nextcloud or Syncthing may keep somewhere else too. Written
 * under a temporary name and renamed when complete ([BackupFiles]), the [BackupFiles.KEEP] newest kept. Never while a
 * workout records: the run waits and tries again. A failure is kept for the You page to say, never a notification.
 */
class AutoBackup(private val context: Context, private val container: AppContainer, private val zone: ZoneId = ZoneId.systemDefault()) {

    sealed interface Outcome {
        /** Written, as [name]. */
        data class Done(val name: String) : Outcome
        /** A workout is recording: nothing written, try again later. */
        data object Recording : Outcome
        /** Automatic backup is off. */
        data object Off : Outcome
        data class Failed(val failure: BackupFiles.Failure) : Outcome
    }

    suspend fun run(): Outcome = lock.withLock {
        val folder = container.settings.backup.first().folder ?: return@withLock Outcome.Off
        // Until the session has looked for a workout left recording, Idle doesn't mean none.
        withTimeoutOrNull(10_000) { container.workout.restored.first { it } }
        if (container.workout.state.value is WorkoutState.Recording) return@withLock Outcome.Recording
        val now = System.currentTimeMillis() / 1000
        catching {
            val name = withContext(Dispatchers.IO) { write(Uri.parse(folder)) }
            container.settings.setBackupDone(now)
            Outcome.Done(name)
        }.getOrElse { e ->
            val failure = (e as? Failed)?.failure ?: BackupFiles.classify(e)
            if (e !is Failed) Log.w("OpenStrap", "backup: failed ($failure): $e")
            container.settings.setBackupFailed(failure, now)
            Outcome.Failed(failure)
        }
    }

    private class Failed(val failure: BackupFiles.Failure) : Exception(failure.name)

    private suspend fun write(folder: Uri): String {
        val resolver = context.contentResolver
        if (resolver.persistedUriPermissions.none { it.uri == folder && it.isWritePermission }) throw Failed(BackupFiles.Failure.PermissionLost)
        val dir = DocumentFile.fromTreeUri(context, folder)
        if (dir == null || !dir.exists() || !dir.isDirectory) throw Failed(BackupFiles.Failure.FolderGone)
        val today = LocalDate.now(zone)
        val name = BackupFiles.name(today)
        // What a run that died part way left.
        dir.listFiles().filter { it.name?.let(BackupFiles::isTemp) == true }.forEach { it.delete() }

        val temp = dir.createFile(TEMP_MIME, BackupFiles.tempName(today)) ?: throw Failed(BackupFiles.Failure.CantWrite)
        var written = false
        try {
            val settings = SettingsBackup.toJson(container.settings.backupValues())
            (resolver.openOutputStream(temp.uri, "w") ?: throw Failed(BackupFiles.Failure.CantWrite)).use {
                container.csv.exportZip(it, settings = settings)
            }
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
        val target = dir.createFile(ZIP_MIME, name) ?: throw Failed(BackupFiles.Failure.CantWrite)
        var copied = false
        try {
            val input = resolver.openInputStream(temp.uri)
            val output = input?.let { resolver.openOutputStream(target.uri, "w") }
            if (input == null || output == null) {
                input?.close()
                throw Failed(BackupFiles.Failure.CantWrite)
            }
            input.use { i -> output.use { i.copyTo(it) } }
            copied = true
        } finally {
            if (!copied) deleteQuietly(target)
        }
        temp.delete()
    }

    private fun deleteQuietly(file: DocumentFile) {
        runCatching { file.delete() }.onFailure { Log.w("OpenStrap", "backup: ${file.name} not deleted: $it") }
    }

    companion object {
        /** One backup at a time, the daily one and Back up now alike. */
        private val lock = Mutex()

        private const val DAILY = "auto-backup"
        private const val AFTER_WORKOUT = "auto-backup-retry"
        private const val TEMP_MIME = "application/octet-stream"
        private const val ZIP_MIME = "application/zip"

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
        // A failure is kept for the You page, and tomorrow's run tries again.
        return when (AutoBackup(applicationContext, container).run()) {
            AutoBackup.Outcome.Recording -> Result.retry()
            else -> Result.success()
        }
    }
}
