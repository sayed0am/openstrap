package org.openstrap.app.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openstrap.app.device.SyncState
import org.openstrap.app.ui.dashboard.DATA_ON_STRAP
import org.openstrap.app.ui.theme.AppIcons
import kotlin.math.roundToInt
import org.openstrap.app.R
import org.openstrap.app.tr

/** How far the pull has gone (1 = far enough to sync) and whether a sync is running. */
class PullSyncState(val fraction: () -> Float, val syncing: () -> Boolean)

/** Set by [PullToSync]; [PullSyncReveal] reads it to open a gap under the wordmark. */
val LocalPullSync = staticCompositionLocalOf<PullSyncState?> { null }

/**
 * Pull down to sync with the strap, and the app's one sync indicator: any sync opens the same gap. There's no floating spinner: [PullSyncReveal], placed in the screen's
 * header, opens as you pull and pushes everything below it down. The pull itself is silent (spec 3.6); release
 * confirms, and a sync that can't start says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToSync(
    sync: SyncState,
    connected: Boolean,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val feedback = rememberHaptics()
    var pulled by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val running = sync is SyncState.Running
    // Read from the long-lived effect and lambdas below, which would otherwise keep the first value.
    val runningNow by rememberUpdatedState(running)
    val syncNow by rememberUpdatedState(sync)
    LaunchedEffect(pulled, running) {
        if (!pulled) return@LaunchedEffect
        when {
            running -> started = true
            // Done: a soft tick as the gap closes. Failed: the reject buzz (spec 3.6) and where the data is.
            started -> {
                pulled = false
                started = false
                if (syncNow is SyncState.Failed) {
                    feedback.perform(HapticEvent.SyncFailed)
                    Toast.makeText(context, tr(R.string.pull_to_sync_couldn_t_sync, DATA_ON_STRAP), Toast.LENGTH_SHORT).show()
                } else haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            // The strap busy or dropping: a sync that doesn't start soon just lets go.
            else -> { delay(START_GRACE_MS); pulled = false }
        }
    }
    val state = rememberPullToRefreshState()
    val reveal = remember(state) { PullSyncState(fraction = { state.distanceFraction }, syncing = { pulled || runningNow }) }
    PullToRefreshBox(
        // Every sync opens the gap, pulled or not (on connect, in the background), so it's the one sync indicator.
        isRefreshing = pulled || running,
        onRefresh = {
            if (!connected) {
                feedback.perform(HapticEvent.SyncFailed)
                Toast.makeText(context, tr(R.string.common_the_strap_isn_t), Toast.LENGTH_SHORT).show()
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                pulled = true
                onSync()
            }
        },
        modifier = modifier.fillMaxSize(),
        state = state,
        indicator = {},
    ) {
        CompositionLocalProvider(LocalPullSync provides reveal) { Box(Modifier.fillMaxSize(), content = content) }
    }
}

/** The gap a pull opens in the header: the sync arrows, turning as you pull and spinning while syncing. */
@Composable
fun PullSyncReveal() {
    val pull = LocalPullSync.current ?: return
    val ready by remember { derivedStateOf { pull.fraction() >= 1f } }
    val syncing = pull.syncing()
    val spin = rememberInfiniteTransition(label = "sync spin")
    val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Box(
        Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
            // Height follows the pull, read here so dragging only relayouts.
            val p = measurable.measure(constraints.copy(minHeight = 0))
            val h = (pull.fraction().coerceIn(0f, 1.6f) * REVEAL.toPx()).roundToInt()
            layout(p.width, h) { p.place(0, (h - p.height) / 2) }
        },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(REVEAL).graphicsLayer { alpha = pull.fraction().coerceIn(0f, 1f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Refresh04, null,
                Modifier.size(18.dp).graphicsLayer { rotationZ = if (syncing) turn else pull.fraction() * 180f },
                tint = if (ready || syncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            val label = when {
                syncing -> tr(R.string.pull_to_sync_syncing_strap)
                ready -> tr(R.string.pull_to_sync_release_to_sync_strap)
                else -> tr(R.string.pull_to_sync_pull_to_sync_strap)
            }
            // One line at a time: the old one fades out before the new one fades in, so "Pull to sync strap" never
            // draws over "Syncing strap…" as a sync starts.
            AnimatedContent(
                label, transitionSpec = { fadeIn(tween(LABEL_FADE_MS, delayMillis = LABEL_FADE_MS)) togetherWith fadeOut(tween(LABEL_FADE_MS)) },
                label = "pull label",
            ) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

private val REVEAL = 48.dp
/** Each half of the label's crossfade: out, then in. */
internal const val LABEL_FADE_MS = 120
private const val START_GRACE_MS = 3_000L
