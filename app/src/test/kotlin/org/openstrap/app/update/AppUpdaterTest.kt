package org.openstrap.app.update

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.settings.SettingsStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/**
 * Audit test 7: an update is installed only from HTTPS, matching GitHub's checksum, as OpenStrap signed with this
 * copy's key, and never while a workout records. A session that fails before its commit is abandoned (audit E12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdaterTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sha = "a".repeat(64)

    @After fun tearDown() = scope.cancel()

    private fun updater(recording: Boolean) = AppUpdater(app, SettingsStore(app), recording = { recording }, scope = scope)

    private fun release(url: String) = Release(
        tag = "v9.0.0", version = listOf(9, 0, 0), notes = "", publishedAt = null, page = "https://github.com/x/y",
        apk = ReleaseAsset("OpenStrap-9.0.0.apk", url, 1000, sha), sums = null,
    )

    private fun failureAfterInstall(updater: AppUpdater, release: Release): InstallFailure = runBlocking {
        updater.install(release)
        withTimeout(10_000) { (updater.state.first { it.install is Install.Failed }.install as Install.Failed).failure }
    }

    @Test fun `only an https address with a host is fetched`() {
        assertNull(AppUpdater.urlFailure("https://github.com/openstrap/releases/download/v9/OpenStrap.apk"))
        assertEquals(InstallFailure.NotOpenStrap, AppUpdater.urlFailure("http://github.com/OpenStrap.apk"))
        assertEquals(InstallFailure.NotOpenStrap, AppUpdater.urlFailure("file:///sdcard/OpenStrap.apk"))
        assertEquals(InstallFailure.NotOpenStrap, AppUpdater.urlFailure("https:///OpenStrap.apk"))
        assertEquals(InstallFailure.NotOpenStrap, AppUpdater.urlFailure("not a url at all"))
    }

    @Test fun `a bad url stops the install before any download`() {
        assertEquals(InstallFailure.NotOpenStrap, failureAfterInstall(updater(recording = false), release("http://example.com/OpenStrap.apk")))
    }

    @Test fun `a checksum that doesn't match is refused`() {
        assertNull(AppUpdater.checksumFailure(sha, sha))
        assertNull(AppUpdater.checksumFailure(sha.uppercase(), sha))
        assertEquals(InstallFailure.Checksum, AppUpdater.checksumFailure(sha, "b".repeat(64)))
        assertEquals(InstallFailure.Checksum, AppUpdater.checksumFailure("", ""))
    }

    @Test fun `another package or another signer is refused`() {
        val mine = setOf("key1")
        assertNull(AppUpdater.archiveFailure("org.openstrap.app", "org.openstrap.app", mine, mine))
        assertEquals(InstallFailure.NotOpenStrap, AppUpdater.archiveFailure("com.example.other", "org.openstrap.app", mine, mine))
        assertEquals(InstallFailure.NotOpenStrap, AppUpdater.archiveFailure(null, "org.openstrap.app", mine, mine))
        assertEquals(InstallFailure.Signature, AppUpdater.archiveFailure("org.openstrap.app", "org.openstrap.app", setOf("key2"), mine))
        assertEquals(InstallFailure.Signature, AppUpdater.archiveFailure("org.openstrap.app", "org.openstrap.app", emptySet(), mine))
        assertEquals(InstallFailure.Signature, AppUpdater.archiveFailure("org.openstrap.app", "org.openstrap.app", mine + "key2", mine))
    }

    @Test fun `nothing installs while a workout records`() {
        assertEquals(InstallFailure.Recording, failureAfterInstall(updater(recording = true), release("https://github.com/OpenStrap.apk")))
    }

    @Test fun `a session that fails before its commit is abandoned`() = runBlocking {
        val installer = app.packageManager.packageInstaller
        val missing = File(app.cacheDir, "missing.apk").apply { delete() }
        val thrown = runCatching { updater(recording = false).commit(missing) }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is FileNotFoundException)
        assertEquals(emptyList<Any>(), installer.allSessions)
    }
}
