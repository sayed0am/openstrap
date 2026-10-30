package org.openstrap.app.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.openstrap.app.R
import org.openstrap.app.data.settings.BackupState
import org.openstrap.app.tr
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowSwitch
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
        item { PageHeader(tr(R.string.main_activity_backup), onBack) }
        item {
            PageRows {
                PageRow(tr(R.string.backup_page_automatic_backup), BackupCopy.status(state, now), onClick = { toggle(!state.on) }, below = true, divider = false, icon = AppIcons.ShieldCheck) {
                    GlowSwitch(state.on, toggle)
                }
                if (state.on) {
                    PageRow(tr(R.string.backup_page_backup_folder), BackupCopy.folder(state), onClick = onFolder, below = true, icon = AppIcons.FolderOpen)
                    PageRow(
                        tr(R.string.backup_page_back_up_now), if (backingUp) tr(R.string.backup_page_backing_up) else tr(R.string.backup_page_saves_todays_zip_in),
                        onClick = onBackUpNow, below = true, enabled = !backingUp, icon = AppIcons.CloudArrowDown,
                    )
                }
                PageRow(tr(R.string.backup_page_restore), tr(R.string.backup_page_any_openstrap_zip_fills), onClick = onRestore, below = true, icon = AppIcons.ArrowCounterClockwise)
                PageRow(tr(R.string.you_page_save_a_copy), tr(R.string.backup_page_a_zip_saved_where), onClick = onSaveCopy, below = true, icon = AppIcons.DownloadSimple)
            }
        }
    }
}

/** The file picker for Restore, opening on the backup folder (a tree URI) when there is one, else on Download. */
class PickBackup : androidx.activity.result.contract.ActivityResultContract<android.net.Uri?, android.net.Uri?>() {
    override fun createIntent(context: android.content.Context, input: android.net.Uri?): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            .apply {
                runCatching {
                    if (input != null) android.provider.DocumentsContract.buildDocumentUriUsingTree(input, android.provider.DocumentsContract.getTreeDocumentId(input))
                    else android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
                }.getOrNull()?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) }
            }

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): android.net.Uri? =
        intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
}
