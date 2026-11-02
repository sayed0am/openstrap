package org.openstrap.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate
import org.openstrap.app.data.settings.BackupFailure

class BackupFilesTest {
    private val day = LocalDate.of(2026, 10, 29)

    @Test fun `a backup is named by its day, and its temp name is never one`() {
        assertEquals("OpenStrap-backup-2026-10-29.zip", BackupFiles.name(day))
        assertEquals(day, BackupFiles.dateOf(BackupFiles.name(day)))
        assertNull(BackupFiles.dateOf(BackupFiles.tempName(day)))
        assertTrue(BackupFiles.isTemp(BackupFiles.tempName(day)))
        assertFalse(BackupFiles.isTemp(BackupFiles.name(day)))
    }

    @Test fun `only our own names count as backups`() {
        for (name in listOf(
            "OpenStrap-backup-2026-10-29 (1).zip", "openstrap-backup-2026-10-29.zip", "OpenStrap-backup-2026-10-29.zip.bak",
            "OpenStrap-backup-2026-13-40.zip", "openstrap-export.zip", "notes.txt", "",
        )) assertNull(name, BackupFiles.dateOf(name))
    }

    @Test fun `retention keeps the seven newest and deletes only ours`() {
        val backups = (0L until 10).map { BackupFiles.name(day.minusDays(it)) }.shuffled()
        val others = listOf("openstrap-export.zip", "OpenStrap-backup-2020-01-01 (1).zip", "photo.jpg", BackupFiles.tempName(day))
        val deleted = BackupFiles.toDelete(backups + others)
        assertEquals((7L until 10).map { BackupFiles.name(day.minusDays(it)) }.toSet(), deleted.toSet())
        assertEquals(3, deleted.size)
    }

    @Test fun `seven or fewer backups delete nothing`() {
        assertEquals(emptyList<String>(), BackupFiles.toDelete((0L until 7).map { BackupFiles.name(day.minusDays(it)) }))
        assertEquals(emptyList<String>(), BackupFiles.toDelete(listOf("photo.jpg")))
    }

    @Test fun `a gap in the days still keeps the seven newest files, not the last seven days`() {
        val days = listOf(0L, 1, 5, 9, 20, 30, 40, 50, 60)
        val deleted = BackupFiles.toDelete(days.map { BackupFiles.name(day.minusDays(it)) })
        assertEquals(listOf(BackupFiles.name(day.minusDays(50)), BackupFiles.name(day.minusDays(60))), deleted)
    }

    @Test fun `failures say what went wrong`() {
        assertEquals(BackupFailure.PermissionLost, BackupFiles.classify(SecurityException("Permission Denial")))
        assertEquals(BackupFailure.NoSpace, BackupFiles.classify(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(BackupFailure.NoSpace, BackupFiles.classify(RuntimeException(IOException("No space left on device"))))
        assertEquals(BackupFailure.FolderGone, BackupFiles.classify(FileNotFoundException("Missing file for primary:Backups")))
        assertEquals(BackupFailure.CantWrite, BackupFiles.classify(IOException("Broken pipe")))
        assertEquals(BackupFailure.Other, BackupFiles.classify(IllegalStateException("odd")))
    }
}
