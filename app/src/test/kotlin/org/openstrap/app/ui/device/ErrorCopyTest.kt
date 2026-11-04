package org.openstrap.app.ui.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.ble.GattTimeoutException
import org.openstrap.app.device.StrapCommandException
import org.openstrap.app.firmware.FirmwareCloudException
import org.openstrap.app.testing.assertCleanCopy
import org.openstrap.app.zepp.ZeppCloudException
import org.openstrap.protocol.firmware.DfuException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

/** Failures say plain words, never an exception's class or "null" (audit E6), and the address to pair is trimmed (E7). */
class ErrorCopyTest {
    private val raw = listOf(
        NullPointerException(),
        FileNotFoundException("open failed: ENOENT"),
        IOException("write failed: ENOSPC (No space left on device)"),
        SecurityException("Permission Denial: opening provider"),
        ZipException("invalid END header"),
        IllegalArgumentException("This isn't an OpenStrap export."),
        IllegalStateException("Room cannot verify the data integrity"),
        GattTimeoutException("op 0x12 timed out"),
        IOException("Unable to resolve host"),
    )

    private val all: List<String> = raw.flatMap { e ->
        listOf(
            ErrorCopy.strap(e, "Couldn't buzz the strap"),
            ErrorCopy.saveCopy(e),
            ErrorCopy.restoreCopy(e),
            ErrorCopy.healthConnect("Couldn't write to Health Connect", e),
            ErrorCopy.firmware(e, "Install failed"),
            ErrorCopy.zepp(e),
        )
    }

    @Test fun `no exception text reaches the screen`() {
        for (text in all) {
            assertFalse(text, "Exception" in text)
            assertFalse(text, "null" in text)
            assertFalse(text, "ENOSPC" in text || "ENOENT" in text || "Permission Denial" in text || "END header" in text)
        }
        assertCleanCopy(all)
    }

    @Test fun `a full disk and a lost permission say so`() {
        assertEquals("Couldn't save the copy. There isn't enough space for it.", ErrorCopy.saveCopy(raw[2]))
        assertEquals("Couldn't save the copy. OpenStrap wasn't allowed to write there. Choose another place.", ErrorCopy.saveCopy(raw[3]))
        assertEquals("Couldn't save the copy. That place is no longer available. Choose another one.", ErrorCopy.saveCopy(raw[1]))
        assertEquals("Couldn't save the copy. Try again, or choose another place.", ErrorCopy.saveCopy(raw[0]))
    }

    @Test fun `a file that isn't a copy says so`() {
        assertEquals("Couldn't restore the copy. That file isn't an OpenStrap copy.", ErrorCopy.restoreCopy(raw[4]))
        assertEquals("Couldn't restore the copy. That file isn't an OpenStrap copy.", ErrorCopy.restoreCopy(raw[5]))
        assertEquals("Couldn't restore the copy. OpenStrap can't open that file. Choose it again.", ErrorCopy.restoreCopy(raw[1]))
        assertEquals("Couldn't restore the copy. Try again.", ErrorCopy.restoreCopy(raw[6]))
    }

    @Test fun `health connect says permission or no answer`() {
        assertEquals(
            "Couldn't write to Health Connect. OpenStrap no longer has permission. Allow it in Health Connect.",
            ErrorCopy.healthConnect("Couldn't write to Health Connect", raw[3]),
        )
        assertEquals(
            "Couldn't restore from Health Connect. Health Connect didn't answer. Try again.",
            ErrorCopy.healthConnect("Couldn't restore from Health Connect", raw[8]),
        )
    }

    @Test fun `the strap keeps its own words`() {
        assertEquals("The strap isn't connected", ErrorCopy.strap(StrapCommandException("The strap isn't connected"), "Couldn't buzz the strap"))
        assertEquals("The strap didn't answer. Try again.", ErrorCopy.strap(raw[7], "Couldn't buzz the strap"))
        assertEquals("Couldn't buzz the strap", ErrorCopy.strap(raw[0], "Couldn't buzz the strap"))
        assertEquals("Wait for the sync to finish first", ErrorCopy.firmware(DfuException(-1, "Wait for the sync to finish first"), "Install failed"))
        assertEquals(
            "Couldn't reach the update server. Check your internet connection and try again.",
            ErrorCopy.firmware(raw[8], "Update check failed"),
        )
        assertEquals("Install failed", ErrorCopy.firmware(raw[6], "Install failed"))
    }

    @Test fun `the update server's replies read in plain words, never with their HTTP code`() {
        val cloud = FirmwareCloudException.Kind.entries.associateWith { kind ->
            ErrorCopy.firmware(FirmwareCloudException("Raw $kind (HTTP 503): <html>busy</html>", kind), "Update check failed")
        }
        assertEquals(
            "Zepp's update server didn't accept the sign-in. It may have expired. Sign in again.",
            cloud[FirmwareCloudException.Kind.Rejected],
        )
        assertEquals(
            "Zepp's update server didn't answer as expected. Try again in a few minutes.",
            cloud[FirmwareCloudException.Kind.Server],
        )
        assertEquals("The firmware download failed. Try again in a few minutes.", cloud[FirmwareCloudException.Kind.Download])
        assertEquals("The firmware download was damaged. Try again.", cloud[FirmwareCloudException.Kind.Damaged])
        for (text in cloud.values) {
            assertFalse(text, "HTTP" in text || "<" in text || "Raw" in text)
            assertCleanCopy(text)
        }
        // What the server sent is kept for Diagnostics, as FirmwareViewModel logs the exception itself.
        assertTrue(FirmwareCloudException("Download failed (HTTP 404)", FirmwareCloudException.Kind.Download).toString().contains("HTTP 404"))
    }

    @Test fun `a Zepp reply never shows its HTTP code`() {
        val troubled = "Zepp didn't answer as expected. It may be having trouble. Try again in a few minutes."
        val refused = "Zepp didn't accept the sign-in. It may have expired, or the account is in another region. Sign in again."
        val replies = listOf(
            ZeppCloudException("Zepp's device list returned HTTP 502"),
            ZeppCloudException("Unexpected reply from Zepp's device list"),
            ZeppCloudException("Renewing the Zepp sign-in returned HTTP 500"),
        )
        for (e in replies) {
            assertEquals(troubled, ErrorCopy.zepp(e))
            assertEquals(troubled, ErrorCopy.firmware(e, "Update check failed"))
        }
        val rejected = ZeppCloudException("Zepp rejected the sign-in (HTTP 401). Sign in again.", rejected = true)
        assertEquals(refused, ErrorCopy.zepp(rejected))
        assertEquals(refused, ErrorCopy.firmware(rejected, "Update check failed"))
        assertEquals("Couldn't reach Zepp's servers. Check your internet connection and try again.", ErrorCopy.zepp(raw[8]))
        for (text in (replies + rejected).flatMap { listOf(ErrorCopy.zepp(it), ErrorCopy.firmware(it, "Update check failed")) }) {
            assertFalse(text, "HTTP" in text)
        }
    }

    @Test fun `a pasted address with spaces pairs, trimmed and upper case`() {
        assertEquals("C8:0F:10:AB:CD:EF", DeviceViewModel.pairAddress("  c8:0f:10:ab:cd:ef \n"))
        assertNull(DeviceViewModel.pairAddress("C8:0F:10:AB:CD"))
        assertEquals(true, DeviceViewModel.isAddress(" C8:0F:10:AB:CD:EF "))
    }
}
