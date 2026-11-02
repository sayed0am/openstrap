package org.openstrap.app.data.settings

/**
 * Why a backup failed, kept so the You page can say it. Here with the settings that store it ([BackupState.failed],
 * by name) rather than with the backup's files, so data names nothing in export.
 */
enum class BackupFailure { FolderGone, PermissionLost, NoSpace, CantWrite, Other }
