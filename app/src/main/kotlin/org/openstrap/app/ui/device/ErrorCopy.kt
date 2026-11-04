package org.openstrap.app.ui.device

import org.openstrap.app.R
import org.openstrap.app.ble.GattTimeoutException
import org.openstrap.app.device.StrapCommandException
import org.openstrap.app.export.BackupFiles
import org.openstrap.app.firmware.FirmwareCloudException
import org.openstrap.app.i18n.Strings
import org.openstrap.app.zepp.ZeppCloudException
import org.openstrap.protocol.firmware.DfuException
import java.io.IOException
import java.util.zip.ZipException
import org.openstrap.app.data.settings.BackupFailure

/**
 * What a failed action says, as [BackupCopy.failure] does for backups. An exception's own text is shown only when it
 * was written for the screen (a strap, firmware or Zepp reply). Anything else, like a null stream or a lost
 * permission, would show a class name or "null", so it gets plain copy and the raw text goes to Diagnostics (audit E6).
 * The text is in res/values/strings_you_data.xml, strings_you_strap.xml and strings_you_firmware.xml.
 */
object ErrorCopy {
    /** A strap command that failed: the strap's own reason when it gave one, else [fallback]. */
    fun strap(e: Throwable, fallback: String, strings: Strings = Strings.app): String = when (e) {
        is StrapCommandException -> e.message ?: fallback
        is GattTimeoutException -> strings.get(R.string.you_error_no_answer)
        else -> fallback
    }

    /** Save a copy failed while writing the zip to the place the user picked. */
    fun saveCopy(e: Throwable, strings: Strings = Strings.app): String {
        val reason = when (BackupFiles.classify(e)) {
            BackupFailure.NoSpace -> R.string.you_backup_failure_no_space
            BackupFailure.PermissionLost -> R.string.you_error_save_copy_permission
            BackupFailure.FolderGone -> R.string.you_error_save_copy_gone
            BackupFailure.CantWrite, BackupFailure.Other -> R.string.you_error_save_copy_other
        }
        return strings.format(R.string.you_error_save_copy, strings.get(reason))
    }

    /** Restore failed while reading the zip the user picked. */
    fun restoreCopy(e: Throwable, strings: Strings = Strings.app): String {
        val reason = when {
            // CsvImporter says a zip without OpenStrap tables is not an export, and a broken zip can't be read at all.
            e is ZipException || e is IllegalArgumentException -> R.string.you_error_restore_not_copy
            e is SecurityException || e is java.io.FileNotFoundException -> R.string.you_error_restore_cant_open
            else -> R.string.you_error_try_again
        }
        return strings.format(R.string.you_error_restore_copy, strings.get(reason))
    }

    /** A Health Connect read or write that failed. [what] says which, "Couldn't write to Health Connect". */
    fun healthConnect(what: String, e: Throwable, strings: Strings = Strings.app): String {
        val reason = when (e) {
            is SecurityException -> R.string.you_error_hc_permission
            is IOException, is android.os.RemoteException -> R.string.you_error_hc_no_answer
            else -> R.string.you_error_try_again
        }
        return strings.format(R.string.you_error_hc, what, strings.get(reason))
    }

    /**
     * Zepp's sign-in or device list that failed. A refused sign-in asks for a new one, and any other reply from Zepp
     * (an HTTP status, a reply that doesn't parse) says Zepp is having trouble, without its code. Anything else is the
     * network.
     */
    fun zepp(e: Throwable, strings: Strings = Strings.app): String = strings.get(
        when {
            e is ZeppCloudException && e.rejected -> R.string.you_error_zepp_rejected
            e is ZeppCloudException -> R.string.you_error_zepp_server
            else -> R.string.you_error_zepp_network
        },
    )

    /**
     * A firmware check, download or install that failed. The strap's update service words its own reasons, the update
     * server's failures read by their kind as a Zepp one reads in [zepp] (their text, with its HTTP code, goes to
     * Diagnostics), and a Zepp sign-in failure reads as [zepp]. A network failure says so, and anything else gets
     * [fallback].
     */
    fun firmware(e: Throwable, fallback: String, strings: Strings = Strings.app): String = when (e) {
        is ZeppCloudException -> zepp(e, strings)
        is FirmwareCloudException -> strings.get(
            when (e.kind) {
                FirmwareCloudException.Kind.Rejected -> R.string.you_error_firmware_rejected
                FirmwareCloudException.Kind.Server -> R.string.you_error_firmware_server
                FirmwareCloudException.Kind.Download -> R.string.you_error_firmware_download
                FirmwareCloudException.Kind.Damaged -> R.string.you_error_firmware_damaged
            },
        )
        is DfuException, is StrapCommandException -> e.message ?: fallback
        is GattTimeoutException -> strings.get(R.string.you_error_no_answer)
        is IOException -> strings.get(R.string.you_error_firmware_network)
        else -> fallback
    }
}
