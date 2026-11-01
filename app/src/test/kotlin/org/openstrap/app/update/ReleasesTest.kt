package org.openstrap.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ReleasesTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, digest: String? = "sha256:ABC123", body: String? = "Notes") = """
        {
          "tag_name": "$tag", "draft": $draft, "prerelease": $prerelease,
          "html_url": "https://github.com/sayed0am/openstrap/releases/tag/$tag",
          "published_at": "2026-10-25T13:38:41Z",
          "body": ${if (body == null) "null" else "\"$body\""},
          "assets": [
            {"name": "OpenStrap-$tag.apk", "size": 20264590, ${if (digest != null) "\"digest\": \"$digest\"," else ""}
             "browser_download_url": "https://github.com/sayed0am/openstrap/releases/download/$tag/OpenStrap-$tag.apk"},
            {"name": "SHA256SUMS.txt", "size": 87,
             "browser_download_url": "https://github.com/sayed0am/openstrap/releases/download/$tag/SHA256SUMS.txt"}
          ]
        }
    """

    @Test fun `versions read with or without the v, a suffix dropped, short ones padded`() {
        assertEquals(listOf(2, 1, 0), Releases.version("v2.1.0"))
        assertEquals(listOf(2, 1, 0), Releases.version("2.1"))
        assertEquals(listOf(2, 2, 0), Releases.version("v2.2.0-rc1"))
        assertNull(Releases.version("latest"))
        assertNull(Releases.version("v1.2.3.4"))
    }

    @Test fun `versions compare by number, not as text`() {
        assert(Releases.compare(listOf(2, 10, 0), listOf(2, 9, 0)) > 0)
        assert(Releases.compare(listOf(2, 1, 0), listOf(2, 1, 0)) == 0)
        assert(Releases.compare(listOf(1, 9, 9), listOf(2, 0, 0)) < 0)
    }

    @Test fun `the list keeps published releases, newest first, with the APK and its digest`() {
        val json = "[${release("v2.0.0")}, ${release("v2.2.0", draft = true)}, ${release("v2.1.0")}, ${release("v2.3.0", prerelease = true)}, ${release("nightly")}]"
        val releases = Releases.parse(json)
        assertEquals(listOf("v2.1.0", "v2.0.0"), releases.map { it.tag })
        val r = releases.first()
        assertEquals("2.1.0", r.versionName)
        assertEquals(Instant.parse("2026-10-25T13:38:41Z"), r.publishedAt)
        assertEquals("OpenStrap-v2.1.0.apk", r.apk?.name)
        assertEquals("abc123", r.apk?.sha256)
        assertEquals(20264590L, r.apk?.size)
        assertEquals("SHA256SUMS.txt", r.sums?.name)
        assertNull(r.sums?.sha256)
    }

    @Test fun `a release without notes has empty ones, and an APK without a digest has no hash`() {
        val r = Releases.parse("[${release("v2.1.0", digest = null, body = null)}]").single()
        assertEquals("", r.notes)
        assertNull(r.apk?.sha256)
    }

    @Test fun `newer lists releases past the installed one, and current finds its own`() {
        val releases = Releases.parse("[${release("v2.2.0")}, ${release("v2.1.0")}, ${release("v2.0.0")}]")
        assertEquals(listOf("v2.2.0", "v2.1.0"), Releases.newer(releases, "2.0.0").map { it.tag })
        assertEquals(emptyList<Release>(), Releases.newer(releases, "2.2.0"))
        // A local build ahead of every release.
        assertEquals(emptyList<Release>(), Releases.newer(releases, "2.2.4"))
        assertEquals("v2.1.0", Releases.current(releases, "2.1.0")?.tag)
        assertNull(Releases.current(releases, "2.1.4"))
    }

    @Test fun `a newer release waits until the Updates page has shown it`() {
        val releases = Releases.parse("[${release("v2.2.0")}, ${release("v2.1.0")}]")
        assert(Releases.waiting(releases, "2.1.0", seen = null))
        assert(!Releases.waiting(releases, "2.1.0", seen = "v2.2.0"))
        // A release out since the page was last opened brings the dot back.
        assert(Releases.waiting(releases, "2.0.0", seen = "v2.1.0"))
        assert(!Releases.waiting(releases, "2.2.0", seen = null))
        assert(!Releases.waiting(emptyList(), "2.1.0", seen = null))
    }

    @Test fun `a release that asks for a newer Android than the phone's is skipped, one at or below it kept`() {
        // The JSON escape for a newline, as GitHub sends the body.
        val nl = "\\n"
        val json = "[${release("v3.1.0", body = "Notes$nl<!-- minSdk: 37 -->")}, ${release("v3.0.0", body = "Notes$nl$nl<!-- minSdk: 36 -->")}, " +
            "${release("v2.2.2", body = "minSdk: 35")}, ${release("v2.2.1")}]"
        val onSixteen = Releases.parse(json, sdk = 36)
        assertEquals(listOf("v3.0.0", "v2.2.2", "v2.2.1"), onSixteen.map { it.tag })
        assertEquals(36, onSixteen.first().minSdk)
        assertNull(onSixteen.last().minSdk)
        assertEquals(listOf("v3.1.0", "v3.0.0", "v2.2.2", "v2.2.1"), Releases.parse(json, sdk = 37).map { it.tag })
        // So the Updates page never offers what the installer would turn down.
        assertEquals(listOf("v3.0.0"), Releases.newer(onSixteen, "2.2.2").map { it.tag })
    }

    @Test fun `the minimum SDK marker reads as a hidden comment or a plain line, and nowhere else`() {
        assertEquals(36, Releases.minSdk("Fixes\n\n<!-- minSdk: 36 -->\n"))
        assertEquals(37, Releases.minSdk("minSdk: 37"))
        assertNull(Releases.minSdk("Needs minSdk: 36 or newer, said in a sentence."))
        assertNull(Releases.minSdk("Android 16 or newer."))
    }

    @Test fun `a SHA256SUMS line gives the APK's hash`() {
        val sums = "CFB8B129  OpenStrap-v2.1.0.apk\nffff  other.apk\n"
        assertEquals("cfb8b129", Releases.sumFor(sums, "OpenStrap-v2.1.0.apk"))
        assertEquals("ffff", Releases.sumFor("ffff *other.apk", "other.apk"))
        assertNull(Releases.sumFor(sums, "missing.apk"))
    }
}
