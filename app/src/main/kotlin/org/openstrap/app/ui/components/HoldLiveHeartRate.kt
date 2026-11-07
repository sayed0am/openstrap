package org.openstrap.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.openstrap.app.OpenStrapApp

/**
 * Holds the strap's live heart rate while this is composed and [active], for a screen that shows it. As in Zepp, live
 * HR streams only while a screen uses it (and the app is in front) or a workout records. Previews hold nothing.
 */
@Composable
fun HoldLiveHeartRate(active: Boolean = true) {
    val strap = (LocalContext.current.applicationContext as? OpenStrapApp)?.container?.strap ?: return
    val key = remember { Any() }
    DisposableEffect(strap, active) {
        strap.showLiveHeartRate(key, active)
        onDispose { strap.showLiveHeartRate(key, false) }
    }
}
