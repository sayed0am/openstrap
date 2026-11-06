package org.openstrap.app.ui.device

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.settings.BackupState
import org.openstrap.app.export.AutoBackup
import java.time.ZoneId
import java.util.Locale
import org.openstrap.app.data.settings.BackupFailure

class BackupCopyTest {
    private val utc = ZoneId.of("UTC")
    private val now = 1792195200L + 20 * 3600 // 2026-10-17T20:00Z
    private val on = BackupState("content://tree", "Drive backups", null, null, null)

    private fun status(s: BackupState) = BackupCopy.status(s, now, utc, Locale.UK)

    @Test fun `the row says off, waiting, when it last ran, or why it failed`() {
        assertEquals("Off. A zip each day in a folder you choose", status(BackupState(null, null, null, null, null)))
        assertEquals("Each day while the phone charges. No backup yet", status(on))
        assertEquals("Last backup today at 03:12", status(on.copy(lastDone = 1792195200L + 3 * 3600 + 12 * 60)))
        assertEquals("Last backup yesterday at 23:50", status(on.copy(lastDone = 1792195200L - 600)))
        assertEquals("Last backup on Wed 14 Oct", status(on.copy(lastDone = 1792195200L - 3 * 86_400L)))
        assertEquals("Last backup on Fri 9 Oct", status(on.copy(lastDone = 1792195200L - 8 * 86_400L)))
        assertEquals(
            "Last backup failed. The folder is no longer available.",
            status(on.copy(lastDone = 1792195200L, failed = BackupFailure.FolderGone, failedAt = now)),
        )
    }

    @Test fun `the You page's row says it in a few words`() {
        fun summary(s: BackupState) = BackupCopy.summary(s, now, utc, Locale.UK)
        assertEquals("Off", summary(BackupState(null, null, null, null, null)))
        assertEquals("On. No backup yet", summary(on))
        assertEquals("Last backup today at 03:12", summary(on.copy(lastDone = 1792195200L + 3 * 3600 + 12 * 60)))
        assertEquals("Last backup failed", summary(on.copy(lastDone = 1792195200L, failed = BackupFailure.NoSpace, failedAt = now)))
    }

    @Test fun `every failure and outcome has its words`() {
        for (f in BackupFailure.entries) BackupCopy.failure(f)
        assertEquals("Backed up as unstrap-backup-2026-10-17.zip.", BackupCopy.outcome(AutoBackup.Outcome.Done("unstrap-backup-2026-10-17.zip")))
        assertEquals("Couldn't back up. There isn't enough space for it.", BackupCopy.outcome(AutoBackup.Outcome.Failed(BackupFailure.NoSpace)))
        assertEquals("The folder you chose", BackupCopy.folder(on.copy(folderName = null)))
    }

    @Test fun `a restore says what came back in a toast's worth of words`() {
        assertEquals("Restored 1,234 records and your settings.", BackupCopy.restored(1234, settings = true, skipped = 0, Locale.UK))
        assertEquals("Restored 1.234 records.", BackupCopy.restored(1234, settings = false, skipped = 0, Locale.GERMANY))
        assertEquals("Restored 12 records.", BackupCopy.restored(12, settings = false, skipped = 0))
        assertEquals("Restored your settings. Your records were already here.", BackupCopy.restored(0, settings = true, skipped = 0))
        assertEquals("Nothing to restore. It's all already here.", BackupCopy.restored(0, settings = false, skipped = 0))
        assertEquals("Restored 12 records. 3 records couldn't be read.", BackupCopy.restored(12, settings = false, skipped = 3))
    }

    @Test fun `a backup's time follows the phone's clock`() {
        val was = org.openstrap.app.presentation.ClockSetting.use24Hour
        try {
            org.openstrap.app.presentation.ClockSetting.use24Hour = false
            val line = status(on.copy(lastDone = 1792195200L + 3 * 3600 + 12 * 60))
            assertEquals("last backup today at 3:12 am", line.lowercase(Locale.ROOT))
            org.openstrap.app.testing.assertCleanCopy(line)
        } finally {
            org.openstrap.app.presentation.ClockSetting.use24Hour = was
        }
    }
}
