package org.openstrap.app.ui.glow

import androidx.compose.runtime.staticCompositionLocalOf
import org.openstrap.app.ui.glow.draw.GlowMood

/**
 * The mood for the cards on this screen: every [GlowCard] and [EdgeCard] under it follows. Only Today (by its moment)
 * and the Sleep tab provide one, everywhere else it's [GlowMood.Day].
 */
// Moved as is from GlowStyle.kt: the screen's light is ambient by nature, every card under a screen follows it.
@Suppress("CompositionLocalAllowlist", "ComposeCompositionLocalUsage")
val LocalGlowMood = staticCompositionLocalOf { GlowMood.Day }
