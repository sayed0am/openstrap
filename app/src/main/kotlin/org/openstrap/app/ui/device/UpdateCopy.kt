package org.openstrap.app.ui.device

import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw
import org.openstrap.app.update.Install
import org.openstrap.app.update.InstallFailure
import org.openstrap.app.update.Release
import org.openstrap.app.update.Releases
import org.openstrap.app.update.UpdateState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words of updates on the You and Updates pages and in their notifications. */
object UpdateCopy {
    /** The You page's Updates row: a new version, a check under way, up to date, or only the version when never checked. */
    fun summary(state: UpdateState, installed: String, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        Releases.newer(state.releases, installed).firstOrNull()?.let { return tr(R.string.update_copy_openstrap_is_ready_to, it.versionName) }
        if (state.checking) return tr(R.string.update_copy_checking)
        val checked = state.checkedAt ?: return tr(R.string.update_copy_version, installed)
        return tr(R.string.update_copy_up_to_date_checked, BackupCopy.whenDone(checked, now, zone, locale))
    }

    /** The Check now row's value. */
    fun checkStatus(state: UpdateState, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (state.checking) return tr(R.string.update_copy_checking_github)
        val checked = state.checkedAt?.let { BackupCopy.whenDone(it, now, zone, locale) }
        if (state.checkFailed) return if (checked != null) tr(R.string.update_copy_couldnt_reach_github_last, checked) else tr(R.string.update_copy_couldnt_reach_github)
        return if (checked != null) tr(R.string.update_copy_last_checked, checked) else tr(R.string.update_copy_never_checked)
    }

    /** The install row's value for [release]: its size, the download, the hand-over to Android, or why it stopped. */
    fun install(release: Release, install: Install, recording: Boolean): String {
        val mine = when (install) {
            is Install.Downloading -> install.tag == release.tag
            is Install.Installing -> install.tag == release.tag
            is Install.Failed -> install.tag == release.tag
            Install.Idle -> false
        }
        if (mine) when (install) {
            is Install.Downloading ->
                return if (install.total > 0) tr(R.string.update_copy_downloading, install.done * 100 / install.total) else tr(R.string.update_copy_downloading_2, megabytes(install.done))
            is Install.Installing -> return tr(R.string.update_copy_installing_openstrap_closes_when)
            is Install.Failed -> return failure(install.failure)
            Install.Idle -> Unit
        }
        val apk = release.apk ?: return tr(R.string.update_copy_no_apk_in_this)
        if (recording) return tr(R.string.update_copy_after_your_workout_installing)
        return tr(R.string.update_copy_download_and_install, megabytes(apk.size))
    }

    /** Why an install stopped, said once. */
    fun failure(f: InstallFailure): String = when (f) {
        InstallFailure.Network -> tr(R.string.update_copy_the_download_stopped_tap)
        InstallFailure.Checksum -> tr(R.string.update_copy_the_download_didnt_match)
        InstallFailure.NoChecksum -> tr(R.string.update_copy_github_gave_no_checksum)
        InstallFailure.NotOpenStrap -> tr(R.string.update_copy_the_download_isnt_openstrap)
        InstallFailure.Signature -> tr(R.string.update_copy_this_copy_of_openstrap)
        InstallFailure.Recording -> tr(R.string.update_copy_after_your_workout_installing)
        InstallFailure.Storage -> tr(R.string.update_copy_there_isnt_enough_space)
        InstallFailure.Android -> tr(R.string.update_copy_android_couldnt_install_it)
    }

    /** "19.3 MB". */
    fun megabytes(bytes: Long): String = trRaw(R.string.update_copy_mb).format(Locale.US, bytes / 1_000_000.0)

    /** When a release came out, beside its version over its notes: "25 Oct 2026". */
    fun date(publishedAt: java.time.Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        publishedAt.atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))

    /** A new release's notification title. */
    fun availableTitle(release: Release): String = tr(R.string.update_copy_openstrap_is_out, release.versionName)

    /** Its text, when the notes have no paragraph or bullet to show. */
    val AVAILABLE_TEXT: String get() = tr(R.string.update_copy_see_what_s_new)

    /** The notification after OpenStrap installed a new version of itself. */
    fun installedTitle(version: String): String = tr(R.string.update_copy_updated_to_openstrap, version)

    val INSTALLED_TEXT: String get() = tr(R.string.update_copy_tap_to_see_what)
}
