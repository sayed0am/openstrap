package org.openstrap.app.export

import org.openstrap.app.data.settings.BackupFailure
import java.io.IOException
import java.time.LocalDate

/**
 * The names automatic backup writes into the folder the user chose, and which of them it may delete. A backup is
 * written under a temporary name first and renamed when complete, so a file named like a backup is always whole. Only
 * files with exactly these names are ever deleted: anything else in the folder is the user's.
 */
object BackupFiles {
    /** How many dated backups are kept; older ones are deleted after each new one. */
    const val KEEP = 7

    private val BACKUP = Regex("""^OpenStrap-backup-(\d{4}-\d{2}-\d{2})\.zip$""")
    private val TEMP = Regex("""^OpenStrap-backup-\d{4}-\d{2}-\d{2}\.part$""")

    /** "OpenStrap-backup-2026-10-29.zip". */
    fun name(date: LocalDate): String = "OpenStrap-backup-$date.zip"

    /** The name a backup is written under until it is complete. Not .zip, so no provider adds an extension to it. */
    fun tempName(date: LocalDate): String = "OpenStrap-backup-$date.part"

    /** The day a backup file was made, or null when [name] isn't one of ours. */
    fun dateOf(name: String): LocalDate? =
        BACKUP.matchEntire(name)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** A half-written backup left by a run that died part way. */
    fun isTemp(name: String): Boolean = TEMP.matches(name)

    /** Of the names in the folder, the backups to delete: every one of ours but the [keep] newest. */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.mapNotNull { n -> dateOf(n)?.let { n to it } }.sortedByDescending { it.second }.drop(keep).map { it.first }

    /** What an exception thrown while writing means for the user. */
    // An early return per cause reads as the list of causes it is.
    @Suppress("ReturnCount")
    fun classify(e: Throwable): BackupFailure {
        var t: Throwable? = e
        while (t != null) {
            if (t is SecurityException) return BackupFailure.PermissionLost
            val m = t.message.orEmpty()
            if ("ENOSPC" in m || "No space" in m || "EDQUOT" in m) return BackupFailure.NoSpace
            if (t is java.io.FileNotFoundException) return BackupFailure.FolderGone
            t = t.cause
        }
        return if (e is IOException) BackupFailure.CantWrite else BackupFailure.Other
    }
}
