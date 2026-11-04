package org.openstrap.app

import org.openstrap.app.ui.root.DebugPreviews

/** The debug extras exist only in debug builds: a release build ignores them. */
@Suppress("UnusedParameter", "UNUSED_PARAMETER")
internal fun MainActivity.handleDebugIntent(previews: DebugPreviews) = Unit
