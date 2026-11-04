package org.openstrap.app.presentation

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import java.time.ZoneId
import java.util.Locale

/**
 * How the app writes a range (user, 2026-11-03 and 2026-11-04): "11:05 PM to 8:16 AM", "20 to 60 minutes",
 * "Mon 3 Nov to Sun 9 Nov", chart labels too. Never a dash: a line that no longer fits wraps or shortens its other
 * part instead.
 */
object Ranges {
    /** [from] to [to], both already written: "18:02 to 18:40". */
    fun of(from: String, to: String, strings: Strings = Strings.app): String = strings.format(R.string.core_range, from, to)

    /** Two moments as times of day in the phone's clock ([TimeOfDay]): "22:58 to 06:56", or "10:58 PM to 6:56 AM". */
    fun times(
        start: Long, end: Long, zone: ZoneId = ZoneId.systemDefault(),
        use24Hour: Boolean = ClockSetting.use24Hour, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): String = of(TimeOfDay.format(start, zone, use24Hour, locale), TimeOfDay.format(end, zone, use24Hour, locale), strings)

    /** Two whole numbers in the phone's digits, with thousands marks: "8,000 to 10,000", "40 to 45". */
    fun numbers(low: Int, high: Int, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        of(Units.count(low, locale), Units.count(high, locale), strings)
}
