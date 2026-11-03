package org.openstrap.app.ui.device

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.SyncState
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.LeafPreviews
import org.openstrap.app.ui.glow.PreviewPage
import org.openstrap.app.ui.glow.say

/** [StrapHero] connected and syncing, then away, as the Strap page shows it. No clock, so it reads the same every day. */
@LeafPreviews
@Composable
internal fun StrapHeroPreview() {
    PreviewPage {
        StrapHero(
            "Helio Strap",
            ConnectionState.Connected(batteryPercent = PREVIEW_BATTERY, firmware = null),
            SyncState.Running(say("Syncing heart rate", "مزامنة نبض القلب")),
            null,
        )
        Spacer(Modifier.height(16.dp))
        StrapHero("Helio Strap", ConnectionState.Disconnected, SyncState.Idle, null)
    }
}

/** Two [PairStep]s, as the pairing panel numbers them. */
@LeafPreviews
@Composable
internal fun PairStepPreview() {
    PreviewPage {
        PairStep(1, say("Link the strap in the Zepp app", "اربط السوار في تطبيق Zepp")) {
            StepLine(say("Install Zepp, sign in, and link your Helio Strap.", "ثبّت Zepp وسجّل الدخول واربط سوارك."))
        }
        Spacer(Modifier.height(16.dp))
        PairStep(2, say("Get the strap ready", "جهّز السوار")) {
            StepLine(say("Put the strap on its charger, close to this phone.", "ضع السوار على شاحنه قرب هذا الهاتف."))
        }
    }
}

/** A step's line, as the pairing panel writes it under the title. */
@Composable
private fun StepLine(text: String) {
    Text(text, style = GlowType.secondary, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA))
}

/** The battery the connected strap shows. */
private const val PREVIEW_BATTERY = 80
