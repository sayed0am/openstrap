package org.openstrap.app.ui.workout

import androidx.compose.runtime.Immutable

/**
 * Seconds in each heart rate zone, zone 1 first, as the zone bars and strips take them. Compose counts an `IntArray` as
 * unstable and a bare `List` as unknown, so a screen given either composes again on every new copy even when the times
 * are the same. This one is stable and equal by its values, so those screens skip until a zone's time changes.
 *
 * It reads as the list it wraps. [seconds] must not change afterwards: the live session hands out a new list for each
 * change, and a saved workout's list is read only.
 */
@Immutable
class ZoneSeconds(private val seconds: List<Int>) : List<Int> by seconds {
    override fun equals(other: Any?): Boolean = other is List<*> && seconds == other

    override fun hashCode(): Int = seconds.hashCode()

    override fun toString(): String = seconds.toString()
}
