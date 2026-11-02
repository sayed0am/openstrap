// Keyed effects run once per key after the frame commits, as they did in AppRoot. A SideEffect would run on every
// recomposition.
@file:Suppress("UnnecessaryLaunchedEffect")

package org.openstrap.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.LocalDate
import kotlinx.coroutines.delay
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.SyncState
import org.openstrap.app.ui.dashboard.DataPreview
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.PAIRED_HOLD_MS
import org.openstrap.app.ui.dashboard.PairStep
import org.openstrap.app.ui.dashboard.StrapChip
import org.openstrap.app.ui.dashboard.StrapPreview
import org.openstrap.app.ui.dashboard.StrapView
import org.openstrap.app.ui.dashboard.pairStep
import org.openstrap.app.ui.dashboard.previewed
import org.openstrap.app.ui.dashboard.strapChip

/** What the screens are shown of the strap, the header chip, and the time the chip counts from. */
class StrapHeader(val view: StrapView, val chip: StrapChip, val now: Long)

private const val CHIP_TICK_MS = 30_000L
private const val MS_PER_S = 1000

// The chip's ticker and the last heart rate's day read the clock as they tick, as the header did before the split.
@Suppress("ForbiddenMethodCall/uiClock")
private fun epochNow() = System.currentTimeMillis() / MS_PER_S

/**
 * The strap as the header and Today show it: the real state, or a debug [preview]'s (nothing else changes). When the
 * strap was last heard from is kept fresh while connected, then left where it stopped, and survives process death.
 * Without that in this run, the last synced reading or the last sync stand in.
 */
@Suppress("ForbiddenMethodCall/uiClock") // see epochNow
@Composable
internal fun rememberStrapHeader(facts: StrapFacts, day: DayUiState, preview: StrapPreview?): StrapHeader {
    var seenConnected by rememberSaveable { mutableStateOf<Long?>(null) }
    val connected = facts.connection is ConnectionState.Connected
    LaunchedEffect(connected) {
        if (connected) while (true) {
            seenConnected = epochNow()
            delay(CHIP_TICK_MS)
        }
    }
    val realLastSeen = listOfNotNull(
        seenConnected,
        (facts.sync as? SyncState.Done)?.atEpochSeconds,
        day.heartRate.lastOrNull()?.x?.takeIf { day.date == LocalDate.now() },
    ).maxOrNull()
    // The chip's "18 min ago" moves on by itself.
    val now by produceState(epochNow()) {
        while (true) {
            delay(CHIP_TICK_MS)
            value = epochNow()
        }
    }
    val view = StrapView(facts.pairedKnown, facts.connection, facts.sync, facts.syncRecord, realLastSeen).previewed(preview, now)
    // Right after the process starts the service is still reconnecting, so the chip waits before saying "18 min ago".
    val sinceStart = if (preview != null) {
        Long.MAX_VALUE
    } else {
        (android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()) / MS_PER_S
    }
    return StrapHeader(view, strapChip(view.paired, view.connection, view.sync, view.lastSeen, now, sinceStart), now)
}

/**
 * The pairing stage (E3) from the real pairing: the Strap page's scan before a strap is paired, then the connection.
 * Once paired it stays on Today until the strap first connects, and holds Connected a moment. A debug data [preview]
 * shows its own step.
 */
@Composable
internal fun rememberPairingStage(
    pairedKnown: Boolean,
    scanning: Boolean,
    found: Boolean,
    connection: ConnectionState,
    preview: DataPreview?,
): PairStep? {
    var firstConnect by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pairedKnown) { if (!pairedKnown) firstConnect = true }
    val realStep = if (!pairedKnown || firstConnect) pairStep(pairedKnown, scanning, found, connection) else null
    LaunchedEffect(realStep == PairStep.Connected) {
        if (realStep == PairStep.Connected) {
            delay(PAIRED_HOLD_MS)
            firstConnect = false
        }
    }
    return preview?.pair ?: realStep
}
