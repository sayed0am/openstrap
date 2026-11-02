package org.openstrap.app.ui.alarms

import org.openstrap.app.presentation.ClockSetting
import org.openstrap.app.presentation.TimeOfDay
import org.openstrap.app.presentation.Units
import java.time.LocalTime

/**
 * An alarm's time for the dot font, on the phone's clock: the digits ("6:40") and, on a 12 hour phone, the "AM" or
 * "PM" apart, since the font has no letters.
 */
internal fun dotClock(hour: Int, minute: Int, use24Hour: Boolean = ClockSetting.use24Hour): Pair<String, String?> {
    val text = TimeOfDay.format(LocalTime.of(hour, minute), use24Hour, Units.DOT_LOCALE)
    val space = text.indexOf(' ')
    return if (space < 0) text to null else text.substring(0, space) to text.substring(space + 1)
}
