package org.openstrap.app.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowSwitch
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PagePanel
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.update.Install
import org.openstrap.app.update.NoteBlock
import org.openstrap.app.update.NoteSpan
import org.openstrap.app.update.Release
import org.openstrap.app.update.ReleaseNotes
import org.openstrap.app.update.Releases
import org.openstrap.app.update.UpdateState

/**
 * The Updates page, from the You page or an update notification: the newest release to install when there is one,
 * Check now, the daily check's switch and the releases on GitHub, then the release notes in one panel. Those are every
 * release newer than this one, newest first, or else this version's own.
 */
@Composable
fun UpdatesPage(
    state: UpdateState,
    installed: String,
    auto: Boolean,
    /** A workout is recording: installing would close it, so it waits. */
    recording: Boolean,
    contentPadding: PaddingValues,
    onInstall: (Release) -> Unit,
    onCheck: () -> Unit,
    onAuto: (Boolean) -> Unit,
    onGitHub: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Read again whenever the state changes, so "today" follows the last check.
    val now = remember(state) { System.currentTimeMillis() / 1000 }
    val newer = Releases.newer(state.releases, installed)
    val newest = newer.firstOrNull()
    val busy = state.install is Install.Downloading || state.install is Install.Installing
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("Updates", onBack) }
        item {
            PageRows {
                if (newest != null) PageRow(
                    "OpenStrap ${newest.versionName}", UpdateCopy.install(newest, state.install, recording),
                    onClick = { onInstall(newest) }, below = true, divider = false, enabled = !busy && !(recording && newest.apk != null),
                    icon = AppIcons.DownloadSimple,
                )
                PageRow(
                    "Check now", UpdateCopy.checkStatus(state, now), onClick = onCheck, below = true, divider = newest != null,
                    enabled = !state.checking, icon = AppIcons.Refresh04,
                )
                PageRow("Check every day", "Notifies you of new versions", onClick = { onAuto(!auto) }, below = true, icon = AppIcons.CalendarBlank) {
                    GlowSwitch(auto, onAuto)
                }
                PageRow("All releases", "On GitHub", onClick = onGitHub, below = true, icon = AppIcons.Info)
            }
        }
        val current = Releases.current(state.releases, installed)
        // A build newer than any release (a developer's) shows the latest release's notes.
        val shown = newer.ifEmpty { listOfNotNull(current ?: state.releases.firstOrNull()) }
        if (shown.isNotEmpty()) item {
            val title = when {
                newer.isNotEmpty() -> "What's new"
                current != null -> "In your version"
                else -> "Latest release"
            }
            PagePanel(title = title) {
                shown.forEachIndexed { i, release ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                    ReleaseNotesView(release)
                }
            }
        }
        item {
            Text(
                "You have OpenStrap $installed.",
                Modifier.fillMaxWidth().padding(top = 4.dp),
                color = Color.White.copy(alpha = 0.45f), style = GlowType.secondary, textAlign = TextAlign.Center,
            )
        }
    }
}

/** One release: its version and date, then its notes as headings, bullets and paragraphs. */
@Composable
private fun ReleaseNotesView(release: Release) {
    val blocks = remember(release.notes) { ReleaseNotes.parse(release.notes) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(release.versionName, color = Color.White, style = GlowType.body.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
            release.publishedAt?.let {
                Text(UpdateCopy.date(it), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            }
        }
        if (blocks.isEmpty()) Text("No notes for this release.", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        blocks.forEachIndexed { i, block -> when (block) {
            is NoteBlock.Heading -> Text(
                spans(block.spans), color = Color.White, style = GlowType.body.copy(fontWeight = FontWeight.Medium), modifier = Modifier.padding(top = 6.dp),
            )
            // The opening paragraph leads, larger; any later one reads like the bullets.
            is NoteBlock.Paragraph ->
                if (i == 0) Text(spans(block.spans), color = Color.White.copy(alpha = 0.85f), style = GlowType.body)
                else Text(spans(block.spans), color = Color.White.copy(alpha = 0.75f), style = GlowType.secondary)
            is NoteBlock.Bullet -> Row {
                Text("•", color = Color.White.copy(alpha = 0.5f), style = GlowType.body, modifier = Modifier.width(16.dp))
                Text(spans(block.spans), color = Color.White.copy(alpha = 0.75f), style = GlowType.secondary, modifier = Modifier.padding(top = 1.dp))
            }
        } }
    }
}

/** Bold runs in white at medium weight, the rest in the text's own colour. */
private fun spans(spans: List<NoteSpan>): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        if (s.bold) withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Color.White)) { append(s.text) } else append(s.text)
    }
}
