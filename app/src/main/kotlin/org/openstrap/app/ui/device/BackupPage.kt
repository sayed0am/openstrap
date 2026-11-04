package org.openstrap.app.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R
import org.openstrap.app.data.settings.BackupState
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.PageSwitchRow
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.theme.AppIcons

/**
 * The Backup page, from the You page, in one panel: automatic backup (the switch, with the last backup or why it failed
 * under it, then, while on, the folder and Back up now), one Restore for any zip, and a copy saved by hand where the
 * user chooses, which the keep-7 rule never clears. Turning backup on asks for the folder first ([onTurnOn]).
 */
@Composable
fun BackupPage(
    state: BackupState,
    backingUp: Boolean,
    contentPadding: PaddingValues,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onFolder: () -> Unit,
    onBackUpNow: () -> Unit,
    onRestore: () -> Unit,
    onSaveCopy: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Read again whenever the state changes, so "today" follows the last backup.
    val now = remember(state, backingUp) { System.currentTimeMillis() / 1000 }
    val toggle = { on: Boolean -> if (on) onTurnOn() else onTurnOff() }
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(stringResource(R.string.you_backup_title), onBack) }
        item {
            PageRows {
                PageSwitchRow(
                    stringResource(R.string.you_backup_automatic), BackupCopy.status(state, now), state.on, toggle, divider = false,
                    icon = AppIcons.ShieldCheck,
                )
                if (state.on) {
                    PageRow(
                        stringResource(R.string.you_backup_folder), BackupCopy.folder(state), onClick = onFolder, below = true,
                        icon = AppIcons.FolderOpen,
                    )
                    PageRow(
                        stringResource(R.string.you_backup_now),
                        stringResource(if (backingUp) R.string.you_backup_now_running else R.string.you_backup_now_note),
                        onClick = onBackUpNow, below = true, enabled = !backingUp, icon = AppIcons.CloudArrowDown,
                    )
                }
                PageRow(
                    stringResource(R.string.you_action_restore), stringResource(R.string.you_backup_restore_note), onClick = onRestore,
                    below = true,
                    icon = AppIcons.ArrowCounterClockwise,
                )
                PageRow(
                    stringResource(R.string.you_backup_save_copy), stringResource(R.string.you_backup_save_copy_note), onClick = onSaveCopy,
                    below = true,
                    icon = AppIcons.DownloadSimple,
                )
            }
        }
    }
}

/** The picker's Downloads view, which lists the newest files first, so a zip just saved is at the top. */
private val DOWNLOADS: android.net.Uri =
    android.provider.DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", "downloads")

/** The file picker for Save a copy, opening on Downloads. */
class SaveBackup : androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip") {
    override fun createIntent(context: android.content.Context, input: String): android.content.Intent =
        super.createIntent(context, input).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS)
}

/** The file picker for Restore, opening on the backup folder (a tree URI) when there is one, else on Downloads. */
class PickBackup : androidx.activity.result.contract.ActivityResultContract<android.net.Uri?, android.net.Uri?>() {
    override fun createIntent(context: android.content.Context, input: android.net.Uri?): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, input?.let(::folderDocument) ?: DOWNLOADS)

    private fun folderDocument(tree: android.net.Uri): android.net.Uri? = runCatching {
        android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull()

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): android.net.Uri? =
        intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
}
