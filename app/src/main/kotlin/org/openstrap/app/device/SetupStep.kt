package org.openstrap.app.device

import org.openstrap.app.data.catching

/**
 * One setup step after auth. A failed step, a timeout included, is logged and setup goes on without it. A cancelled
 * connect (Disconnect, the link lost) stops here instead of carrying on and reporting Failed over the state its
 * canceller set (audit C5).
 */
internal suspend inline fun <T> setupStep(name: String, log: (String) -> Unit, block: () -> T): T? =
    catching(block).onFailure { log("$name failed: $it") }.getOrNull()
