package org.openstrap.app.ui.glow

import androidx.compose.runtime.mutableStateOf

/**
 * Debug only: a Recovery score the detail shows in place of the real one, so the arc gauge can be checked on a phone
 * whose Recovery is still learning. Set from MainActivity's `recovery_score` extra, and null shows the real score. A release
 * build has a twin that always reads null.
 */
internal val debugRecoveryScoreState = mutableStateOf<Int?>(null)

/** The score [debugRecoveryScoreState] holds, read in composition so the detail follows a new one. */
internal fun debugRecoveryScore(): Int? = debugRecoveryScoreState.value
