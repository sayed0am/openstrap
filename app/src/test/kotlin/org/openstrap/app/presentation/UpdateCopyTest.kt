package org.openstrap.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.update.Install
import org.openstrap.app.update.InstallFailure
import org.openstrap.app.update.Release
import org.openstrap.app.update.ReleaseAsset
import org.openstrap.app.update.UpdateState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class UpdateCopyTest {
    private val utc = ZoneId.of("UTC")
    private val now = 1792195200L + 20 * 3600 // 2026-10-17T20:00Z
    private val apk = ReleaseAsset("OpenStrap-v2.2.0.apk", "https://example.com/a.apk", 20_264_590, "abc")
    private val r220 = Release("v2.2.0", listOf(2, 2, 0), "Notes", Instant.parse("2026-10-25T13:38:41Z"), "https://example.com", apk, null)
    private val r210 = r220.copy(tag = "v2.1.0", version = listOf(2, 1, 0))

    @Test fun `the You page's row says a new version, checking, up to date, or just the version`() {
        assertEquals("OpenStrap 2.2.0 is ready to install", UpdateCopy.summary(UpdateState(listOf(r220, r210), checkedAt = now), "2.1.0", now, utc, Locale.UK))
        assertEquals("Checking", UpdateCopy.summary(UpdateState(listOf(r210), checking = true), "2.1.0", now, utc, Locale.UK))
        assertEquals("Up to date. Checked today at 19:00", UpdateCopy.summary(UpdateState(listOf(r210), checkedAt = now - 3600), "2.1.0", now, utc, Locale.UK))
        assertEquals("Version 2.1.0", UpdateCopy.summary(UpdateState(), "2.1.0", now, utc, Locale.UK))
    }

    @Test fun `Check now says when it last looked, and when GitHub couldn't be reached`() {
        assertEquals("Never checked", UpdateCopy.checkStatus(UpdateState(), now, utc, Locale.UK))
        assertEquals("Checking GitHub", UpdateCopy.checkStatus(UpdateState(checking = true), now, utc, Locale.UK))
        assertEquals("Last checked yesterday at 23:50", UpdateCopy.checkStatus(UpdateState(checkedAt = 1792195200L - 600), now, utc, Locale.UK))
        assertEquals(
            "Couldn't reach GitHub. Last checked today at 19:00",
            UpdateCopy.checkStatus(UpdateState(checkedAt = now - 3600, checkFailed = true), now, utc, Locale.UK),
        )
        assertEquals("Couldn't reach GitHub", UpdateCopy.checkStatus(UpdateState(checkFailed = true), now, utc, Locale.UK))
    }

    @Test fun `the install row follows the download and says why it stopped`() {
        assertEquals("Download and install, 20.3 MB", UpdateCopy.install(r220, Install.Idle, recording = false))
        assertEquals("After your workout. Installing closes the app", UpdateCopy.install(r220, Install.Idle, recording = true))
        assertEquals("Downloading, 45%", UpdateCopy.install(r220, Install.Downloading("v2.2.0", 45, 100), recording = false))
        assertEquals("Installing. OpenStrap closes when it's done", UpdateCopy.install(r220, Install.Installing("v2.2.0"), recording = false))
        assertEquals(
            "This copy of OpenStrap is signed with another key. Install it from GitHub instead",
            UpdateCopy.install(r220, Install.Failed("v2.2.0", InstallFailure.Signature), recording = false),
        )
        // Another release's download isn't this one's.
        assertEquals("Download and install, 20.3 MB", UpdateCopy.install(r220, Install.Downloading("v2.1.0", 1, 2), recording = false))
        assertEquals("No APK in this release. Open it on GitHub", UpdateCopy.install(r220.copy(apk = null), Install.Idle, recording = false))
    }

    @Test fun `no user-facing line uses a semicolon, middle dot or dash`() {
        val lines = InstallFailure.entries.map(UpdateCopy::failure) +
            listOf(UpdateCopy.AVAILABLE_TEXT, UpdateCopy.INSTALLED_TEXT, UpdateCopy.availableTitle(r220), UpdateCopy.installedTitle("2.2.0"))
        for (line in lines) assert(line.none { it in ";·—–" }) { line }
    }
}
