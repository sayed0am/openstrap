package org.openstrap.app.ui.device

import org.openstrap.app.ble.GattTimeoutException
import org.openstrap.app.device.StrapCommandException
import org.openstrap.app.export.BackupFiles
import org.openstrap.app.firmware.FirmwareCloudException
import org.openstrap.app.zepp.ZeppCloudException
import org.openstrap.protocol.firmware.DfuException
import java.io.IOException
import java.util.zip.ZipException
import org.openstrap.app.data.settings.BackupFailure

/**
 * What a failed action says, as [BackupCopy.failure] does for backups. An exception's own text is shown only when it
 * was written for the screen (a strap, firmware or Zepp reply). Anything else, like a null stream or a lost
 * permission, would show a class name or "null", so it gets plain copy and the raw text goes to Diagnostics (audit E6).
 */
object ErrorCopy {
    /** A strap command that failed: the strap's own reason when it gave one, else [fallback]. */
    fun strap(e: Throwable, fallback: String): String = when (e) {
        is StrapCommandException -> e.message ?: fallback
        is GattTimeoutException -> NO_ANSWER
        else -> fallback
    }

    /** Save a copy failed while writing the zip to the place the user picked. */
    fun saveCopy(e: Throwable): String = "Couldn't save the copy. " + when (BackupFiles.classify(e)) {
        BackupFailure.NoSpace -> "There isn't enough space for it."
        BackupFailure.PermissionLost -> "OpenStrap wasn't allowed to write there. Choose another place."
        BackupFailure.FolderGone -> "That place is no longer available. Choose another one."
        BackupFailure.CantWrite, BackupFailure.Other -> "Try again, or choose another place."
    }

    /** Restore failed while reading the zip the user picked. */
    fun restoreCopy(e: Throwable): String = "Couldn't restore the copy. " + when {
        // CsvImporter says a zip without OpenStrap tables is not an export, and a broken zip can't be read at all.
        e is ZipException || e is IllegalArgumentException -> "That file isn't an OpenStrap copy."
        e is SecurityException || e is java.io.FileNotFoundException -> "OpenStrap can't open that file. Choose it again."
        else -> "Try again."
    }

    /** A Health Connect read or write that failed. [what] says which, "Couldn't write to Health Connect". */
    fun healthConnect(what: String, e: Throwable): String = "$what. " + when (e) {
        is SecurityException -> "OpenStrap no longer has permission. Allow it in Health Connect."
        is IOException, is android.os.RemoteException -> "Health Connect didn't answer. Try again."
        else -> "Try again."
    }

    /**
     * Zepp's sign-in or device list that failed. A refused sign-in asks for a new one, and any other reply from Zepp
     * (an HTTP status, a reply that doesn't parse) says Zepp is having trouble, without its code. Anything else is the
     * network.
     */
    fun zepp(e: Throwable): String = when {
        e is ZeppCloudException && e.rejected ->
            "Zepp didn't accept the sign-in. It may have expired, or the account is in another region. Sign in again."
        e is ZeppCloudException -> "Zepp didn't answer as expected. It may be having trouble. Try again in a few minutes."
        else -> "Couldn't reach Zepp's servers. Check your internet connection and try again."
    }

    /**
     * A firmware check, download or install that failed. The update server and the strap's update service word their
     * own reasons, and a Zepp sign-in failure reads as [zepp]. A network failure says so, and anything else gets
     * [fallback].
     */
    fun firmware(e: Throwable, fallback: String): String = when (e) {
        is ZeppCloudException -> zepp(e)
        is FirmwareCloudException, is DfuException, is StrapCommandException -> e.message ?: fallback
        is GattTimeoutException -> NO_ANSWER
        is IOException -> "Couldn't reach the update server. Check your internet connection and try again."
        else -> fallback
    }

    private const val NO_ANSWER = "The strap didn't answer. Try again."
}
