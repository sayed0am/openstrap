package org.openstrap.app.presentation

import org.openstrap.app.update.Install
import org.openstrap.app.update.InstallFailure
import org.openstrap.app.update.Release
import org.openstrap.app.update.Releases
import org.openstrap.app.update.UpdateState
import java.time.ZoneId
import java.util.Locale

/**
 * The words of updates on the You and Updates pages and in their notifications. Here rather than in ui, so the update
 * notifier reads them without depending on the screens.
 */
object UpdateCopy {
    /** The You page's Updates row: a new version, a check under way, up to date, or only the version when never checked. */
    fun summary(state: UpdateState, installed: String, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        Releases.newer(state.releases, installed).firstOrNull()?.let { return "OpenStrap ${it.versionName} is ready to install" }
        if (state.checking) return "Checking"
        val checked = state.checkedAt ?: return "Version $installed"
        return "Up to date. Checked ${DayWords.whenDone(checked, now, zone, locale = locale)}"
    }

    /** The Check now row's value. */
    fun checkStatus(state: UpdateState, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (state.checking) return "Checking GitHub"
        val checked = state.checkedAt?.let { DayWords.whenDone(it, now, zone, locale = locale) }
        if (state.checkFailed) return if (checked != null) "Couldn't reach GitHub. Last checked $checked" else "Couldn't reach GitHub"
        return if (checked != null) "Last checked $checked" else "Never checked"
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
                return if (install.total > 0) "Downloading, ${install.done * 100 / install.total}%" else "Downloading, ${megabytes(install.done)}"
            is Install.Installing -> return "Installing. OpenStrap closes when it's done"
            is Install.Failed -> return failure(install.failure)
            Install.Idle -> Unit
        }
        val apk = release.apk ?: return "No APK in this release. Open it on GitHub"
        if (recording) return "After your workout. Installing closes the app"
        return "Download and install, ${megabytes(apk.size)}"
    }

    /** Why an install stopped, said once. */
    fun failure(f: InstallFailure): String = when (f) {
        InstallFailure.Network -> "The download stopped. Tap to try again"
        InstallFailure.Checksum -> "The download didn't match GitHub's checksum. Tap to try again"
        InstallFailure.NoChecksum -> "GitHub gave no checksum for it, so it wasn't installed"
        InstallFailure.NotOpenStrap -> "The download isn't OpenStrap, so it wasn't installed"
        InstallFailure.Signature -> "This copy of OpenStrap is signed with another key. Install it from GitHub instead"
        InstallFailure.Recording -> "After your workout. Installing closes the app"
        InstallFailure.Storage -> "There isn't enough space to install it"
        InstallFailure.Android -> "Android couldn't install it. Tap to try again"
    }

    /** "19.3 MB". */
    fun megabytes(bytes: Long): String = "%.1f MB".format(Locale.US, bytes / 1_000_000.0)

    /** When a release came out, beside its version over its notes: "25 Oct 2026". */
    fun date(publishedAt: java.time.Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        publishedAt.atZone(zone).format(Formatters.of("d MMM yyyy", locale))

    /** A new release's notification title. */
    fun availableTitle(release: Release): String = "OpenStrap ${release.versionName} is out"

    /** Its text, when the notes have no paragraph or bullet to show. */
    const val AVAILABLE_TEXT = "See what's new and install it from the Updates page."

    /** The notification after OpenStrap installed a new version of itself. */
    fun installedTitle(version: String): String = "Updated to OpenStrap $version"

    const val INSTALLED_TEXT = "Tap to see what's new."
}
