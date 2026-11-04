package org.openstrap.app.update

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.Formatters
import java.time.ZoneId
import java.util.Locale

/**
 * The words of updates on the You and Updates pages and in their notifications. Here rather than in ui, so the update
 * notifier reads them without depending on the screens, and beside the update state they describe rather than in
 * presentation, which sits below update and can't name it. The text is in res/values/strings_update.xml.
 */
object UpdateCopy {
    /** The You page's Updates row: a new version, a check under way, up to date, or only the version when never checked. */
    fun summary(
        state: UpdateState,
        installed: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        strings: Strings = Strings.app,
    ): String {
        val newest = Releases.newer(state.releases, installed).firstOrNull()
        if (newest != null) return strings.format(R.string.update_summary_ready, newest.versionName)
        if (state.checking) return strings.get(R.string.update_summary_checking)
        val checked = state.checkedAt ?: return strings.format(R.string.update_summary_version, installed)
        return strings.format(R.string.update_summary_up_to_date, DayWords.whenDone(checked, now, zone, locale = locale))
    }

    /** The Check now row's value. */
    fun checkStatus(
        state: UpdateState,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        strings: Strings = Strings.app,
    ): String {
        if (state.checking) return strings.get(R.string.update_check_checking)
        val checked = state.checkedAt?.let { DayWords.whenDone(it, now, zone, locale = locale) }
        if (state.checkFailed) {
            return if (checked != null) {
                strings.format(R.string.update_check_failed_last, checked)
            } else {
                strings.get(R.string.update_check_failed)
            }
        }
        return if (checked != null) strings.format(R.string.update_check_last, checked) else strings.get(R.string.update_check_never)
    }

    /** The install row's value for [release]: its size, the download, the hand-over to Android, or why it stopped. */
    fun install(release: Release, install: Install, recording: Boolean, strings: Strings = Strings.app): String {
        val mine = when (install) {
            is Install.Downloading -> install.tag == release.tag
            is Install.Installing -> install.tag == release.tag
            is Install.Failed -> install.tag == release.tag
            Install.Idle -> false
        }
        if (mine) when (install) {
            is Install.Downloading -> return if (install.total > 0) {
                strings.format(R.string.update_install_downloading_percent, install.done * 100 / install.total)
            } else {
                strings.format(R.string.update_install_downloading_size, megabytes(install.done, strings))
            }
            is Install.Installing -> return strings.get(R.string.update_install_installing)
            is Install.Failed -> return failure(install.failure, strings)
            Install.Idle -> Unit
        }
        val apk = release.apk ?: return strings.get(R.string.update_install_no_apk)
        if (recording) return strings.get(R.string.update_install_after_workout)
        return strings.format(R.string.update_install_download, megabytes(apk.size, strings))
    }

    /** Why an install stopped, said once. */
    fun failure(f: InstallFailure, strings: Strings = Strings.app): String = strings.get(
        when (f) {
            InstallFailure.Network -> R.string.update_failure_network
            InstallFailure.Checksum -> R.string.update_failure_checksum
            InstallFailure.NoChecksum -> R.string.update_failure_no_checksum
            InstallFailure.NotOpenStrap -> R.string.update_failure_not_openstrap
            InstallFailure.Signature -> R.string.update_failure_signature
            InstallFailure.Recording -> R.string.update_install_after_workout
            InstallFailure.Storage -> R.string.update_failure_storage
            InstallFailure.Android -> R.string.update_failure_android
        },
    )

    /** "19.3 MB", "19,3 MB" in German: the number follows the app's language. */
    fun megabytes(bytes: Long, strings: Strings = Strings.app): String = strings.format(R.string.update_megabytes, bytes / 1_000_000.0)

    /** When a release came out, beside its version over its notes: "25 Oct 2026". */
    fun date(publishedAt: java.time.Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        publishedAt.atZone(zone).format(Formatters.of("d MMM yyyy", locale))

    /** A new release's notification title. */
    fun availableTitle(release: Release, strings: Strings = Strings.app): String =
        strings.format(R.string.update_available_title, release.versionName)

    /** Its text, when the notes have no paragraph or bullet to show. Named as the constant it was, which the tests read. */
    val AVAILABLE_TEXT: String get() = Strings.app.get(R.string.update_available_text)

    /** The notification after OpenStrap installed a new version of itself. */
    fun installedTitle(version: String, strings: Strings = Strings.app): String = strings.format(R.string.update_installed_title, version)

    /** Its text. */
    val INSTALLED_TEXT: String get() = Strings.app.get(R.string.update_installed_text)

    /** The update notifications' channel, as Android's notification settings name it. */
    fun channelName(strings: Strings = Strings.app): String = strings.get(R.string.update_channel_name)
}
