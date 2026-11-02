package org.openstrap.app.ui.glow

import android.content.Context

/*
 * Names kept only for MainActivity, which wave 4's task 4A rewrites at the same time. The coordinator points it at the
 * new homes after the wave 4 merge and deletes this file.
 */

@Deprecated("Moved to ui.glow.draw", ReplaceWith("org.openstrap.app.ui.glow.draw.GlowMood"))
typealias GlowMood = org.openstrap.app.ui.glow.draw.GlowMood

@Deprecated("Moved to device", ReplaceWith("org.openstrap.app.device.locationAvailable(context)"))
internal fun locationAvailable(context: Context): Boolean = org.openstrap.app.device.locationAvailable(context)
