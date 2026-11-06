package org.openstrap.app.export

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openstrap.app.data.BackupRepository
import org.openstrap.app.data.BackupResult
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.settings.SettingsStore
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * [BackupRepository] as zips of CSV files ([CsvExporter], [CsvImporter]) with the settings beside them
 * ([SettingsBackup]), read and written through the content resolver. [log] takes what Diagnostics should keep.
 */
class CsvBackups(
    private val context: Context,
    private val database: OpenStrapDatabase,
    private val settings: SettingsStore,
    private val csv: CsvExporter,
    private val log: (String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackupRepository {

    override suspend fun exportCsv(to: Uri): BackupResult = catching {
        withContext(io) { output(to).use { writeZip(it) } }
        BackupResult.Saved
    }.getOrElse { BackupResult.Failed(it) }

    /**
     * The zip, read from a copy of the database made as it starts ([BackupSnapshot]), so a sync running meanwhile can't
     * change some tables in it and not others. Without room for the copy it reads the database as it goes, as before.
     */
    override suspend fun writeZip(out: OutputStream) {
        val json = SettingsBackup.toJson(settings.backupValues(SettingsBackup::carries))
        withContext(io) {
            val snapshot = catching { BackupSnapshot.take(context, database) }
                .onFailure { warn("backup: no copy of the database to read, reading it as it goes: $it") }
                .getOrNull()
            snapshot.use { (it?.let { s -> CsvExporter(s.database.healthDao()) } ?: csv).exportZip(out, settings = json) }
        }
    }

    override suspend fun importCsv(from: Uri): BackupResult = catching {
        val result = withContext(io) { input(from).use { CsvImporter(database).importZip(it) } }
        // A damaged settings file costs the settings only, not the records already restored.
        val restored = result.settings?.let { json ->
            catching { settings.restoreValues(SettingsBackup.fromJson(json), SettingsBackup::missing) }
                .onFailure { warn("restore: settings not restored: $it") }
                .getOrDefault(0)
        } ?: 0
        BackupResult.Restored(result.total, restored > 0, result.skipped)
    }.getOrElse { BackupResult.Failed(it) }

    override suspend fun exportSettings(to: Uri): BackupResult = catching {
        val json = SettingsBackup.toJson(settings.backupValues(SettingsBackup::carries))
        withContext(io) { output(to).use { it.write(json.toByteArray(Charsets.UTF_8)) } }
        BackupResult.Saved
    }.getOrElse { BackupResult.Failed(it) }

    override suspend fun importSettings(from: Uri): BackupResult = catching {
        val json = withContext(io) { input(from).use { it.readBytes().toString(Charsets.UTF_8) } }
        BackupResult.Restored(0, settings.restoreValues(SettingsBackup.fromJson(json), SettingsBackup::missing) > 0, 0)
    }.getOrElse { BackupResult.Failed(it) }

    private fun output(uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri) ?: throw FileNotFoundException("no output stream for $uri")

    private fun input(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("no input stream for $uri")

    private fun warn(message: String) {
        Log.w("unstrap", message)
        log(message)
    }
}
