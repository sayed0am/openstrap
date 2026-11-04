package org.openstrap.app.ui.root

import kotlinx.coroutines.flow.MutableStateFlow
import org.openstrap.app.data.Moment
import org.openstrap.app.ui.dashboard.DataPreview
import org.openstrap.app.ui.dashboard.StrapPreview

/**
 * Debug: what the screens are shown in place of the real state, set by the activity's debug extras. Release builds
 * never set them, so they stay null there.
 */
class DebugPreviews {
    /** The moment forced by the `moment` extra, null to follow the clock. */
    val moment = MutableStateFlow<Moment?>(null)

    /** The strap state previewed by the `strap_state` extra, null for the real one. */
    val strap = MutableStateFlow<StrapPreview?>(null)

    /** The data state previewed by the `data_state` extra, null for the real one. */
    val data = MutableStateFlow<DataPreview?>(null)
}
