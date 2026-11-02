package org.openstrap.app.presentation

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * How the app writes a unit, one style each, as decided at the wave 3 design gate
 * (docs/audits/2026-11-02-wave3-format-choices.md, all ten recommendations approved on 2026-11-02):
 *
 * 1. Distance. A workout shows 2 decimals ("5.21 km"), a day's total 1 ("5.2 km"). Under 1 km it's metres ("820 m"),
 *    under 10 m nothing. The decimal mark follows the phone's language, except in the dot font, which keeps the dot.
 *    Distance from steps always gets "≈ " in front.
 * 2. Duration. "7 h 40" everywhere, "7 h" on the hour and "40 min" under an hour. "7:40" only where a design asks
 *    for a clock look.
 * 3. Workout timer. "12:34", then "1:02:03" past the hour, written once. A finished workout under a minute reads "45 s".
 * 4. Time of day. Every clock follows the phone's 12 or 24 hour setting through [TimeOfDay], widgets and the
 *    notification too.
 * 5. Pace. "5:24 /km" everywhere, shown once a workout has covered 100 m, and never slower than 60 min a km ([Pace]).
 * 6. Day words. "Today", "Yesterday", the weekday for the rest of the last 7 days, then "Tue 21 Oct", with the year
 *    from an earlier year. Lowercase after other words ("yesterday 10:42"). Charts keep "Tue 21 Oct" ([DayWords]).
 * 7. Week start. The phone's locale decides the first day of every week, in one place ([org.openstrap.app.data.Weeks]).
 * 8. Score bands. Recovery 85 and 70, sleep 90, 80 and 60, words unchanged, defined once. Load follows Today's rule
 *    ([LoadBands]).
 * 9. Valid heart rate. 30 to 230 bpm everywhere, database queries and workouts included
 *    ([org.openstrap.app.data.HeartRate]).
 * 10. Strap out of date. One threshold, 3 hours, and one low battery level, 15% ([StrapBands]).
 *
 * Every function takes the locale it writes in, the phone's by default. Text drawn in the dot font passes [DOT_LOCALE]
 * or uses [dotFormat], since the font has only ASCII digits and both marks but no other script's digits.
 */
object Units {
    /** The locale for text drawn in the dot font: ASCII digits and a dot decimal mark. */
    val DOT_LOCALE: Locale = Locale.ROOT

    /** In front of a distance worked out from steps rather than measured. */
    const val FROM_STEPS = "≈ "

    /** A distance under this many metres shows nothing: too little to mean anything. */
    const val SHOWN_FROM_METERS = 10f

    /** Metres in a kilometre, where a distance turns from metres to km. */
    const val METERS_PER_KM = 1000f

    internal const val MINUTE = 60L
    private const val HOUR = 3600L
    private const val MINUTES_PER_HOUR = 60L

    /** How many decimals a distance in km takes: a workout's own, or a whole day's total. */
    enum class Precision(val decimals: Int) {
        /** The live screen, summary, notification and share card: "5.21 km". */
        Workout(2),

        /** Today, Activity and the widgets: "5.2 km". */
        Day(1),
    }

    /**
     * [meters] with its unit: "5.21 km" or "5.2 km" by [precision], "820 m" under a kilometre, with "≈ " in front
     * when [fromSteps]. Null under [SHOWN_FROM_METERS].
     */
    fun distance(meters: Float, precision: Precision, fromSteps: Boolean = false, locale: Locale = Locale.getDefault()): String? {
        if (meters < SHOWN_FROM_METERS) return null
        val text = if (meters < METERS_PER_KM) "${metres(meters, locale)} m" else "${km(meters, precision, locale)} km"
        return if (fromSteps) FROM_STEPS + text else text
    }

    /** [meters] as a number of km alone, for a figure with its unit drawn apart: "5.21". */
    fun km(meters: Float, precision: Precision, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.${precision.decimals}f", meters / METERS_PER_KM)

    /** Whole metres, cut rather than rounded so 999.6 m never reads as a kilometre's 1000. */
    private fun metres(meters: Float, locale: Locale): String = String.format(locale, "%d", meters.toInt())

    /** A count with the language's grouping: "23,410" steps, "23.410" in German. */
    fun count(n: Long, locale: Locale = Locale.getDefault()): String = String.format(locale, "%,d", n)

    /** [count] for an Int. */
    fun count(n: Int, locale: Locale = Locale.getDefault()): String = count(n.toLong(), locale)

    /** [v] with [places] decimals in the phone's language, for text: "1.4", "1,4" in German. */
    fun decimal(v: Number, places: Int, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.${places}f", v)

    /** A length of time: "40 min", "7 h", "7 h 40", "7 h 05". Under a minute is "0 min". */
    fun duration(seconds: Long, locale: Locale = Locale.getDefault()): String {
        val minutes = seconds.coerceAtLeast(0) / MINUTE
        val h = minutes / MINUTES_PER_HOUR
        val m = minutes % MINUTES_PER_HOUR
        return when {
            h == 0L -> String.format(locale, "%d min", m)
            m == 0L -> String.format(locale, "%d h", h)
            else -> String.format(locale, "%d h %02d", h, m)
        }
    }

    /** A length of time with a clock's look, hours and minutes, where a design asks for it: "7:40". */
    fun clockDuration(seconds: Long, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%d:%02d", seconds / HOUR, seconds % HOUR / MINUTE)

    /** The workout timer: "12:34", or "1:02:03" past the hour. */
    fun workoutClock(seconds: Long, locale: Locale = Locale.getDefault()): String =
        if (seconds >= HOUR) String.format(locale, "%d:%02d:%02d", seconds / HOUR, seconds / MINUTE % MINUTES_PER_HOUR, seconds % MINUTE)
        else String.format(locale, "%d:%02d", seconds / MINUTE, seconds % MINUTE)

    /** A finished workout's length, or a gap between two: "45 s" under a minute, else [workoutClock]. */
    fun secondsOrClock(seconds: Long, locale: Locale = Locale.getDefault()): String =
        if (seconds < MINUTE) String.format(locale, "%d s", seconds) else workoutClock(seconds, locale)
}

/** A change against your usual, with its sign: "+3", "−3", or "±0" when it rounds to nothing, never "-0". */
object Signed {
    fun of(v: Int, locale: Locale = Locale.getDefault()): String {
        val text = String.format(locale, "%d", abs(v))
        return when {
            v > 0 -> "+$text"
            v < 0 -> "−$text"
            else -> "±$text"
        }
    }

    /** [v] to [decimals] places with its sign: "+0.4", "−3", "±0.0". */
    fun of(v: Float, decimals: Int, locale: Locale = Locale.getDefault()): String {
        val text = String.format(locale, "%.${decimals}f", abs(v))
        // Decided on the rounded value, not the text, so a comma decimal mark can't make "0,0" read as a change.
        val rounded = (abs(v) * TEN.pow(decimals)).roundToLong()
        return when {
            rounded == 0L -> "±$text"
            v > 0 -> "+$text"
            else -> "−$text"
        }
    }

    private const val TEN = 10f
}

/** A pace, decision 5 in [Units]: "5:24 /km", from 100 m covered, nothing slower than an hour a km. */
object Pace {
    /** Pace shows once a workout has covered this many metres: before that one GPS jump swings it wildly. */
    const val FROM_METERS = 100f

    /** A pace slower than this, seconds a km, is standing still and shows nothing. */
    const val SLOWEST_S = 3600.0

    /** Seconds a km for [meters] in [seconds], null with no distance or under [FROM_METERS]. */
    fun of(meters: Float?, seconds: Long): Double? =
        meters?.takeIf { it >= FROM_METERS }?.let { seconds / (it / Units.METERS_PER_KM.toDouble()) }

    /** A pace as a clock alone, "5:24", for a figure with its unit drawn apart. Null with none or slower than [SLOWEST_S]. */
    fun clock(secondsPerKm: Double?, locale: Locale = Locale.getDefault()): String? {
        if (secondsPerKm == null || secondsPerKm <= 0.0 || secondsPerKm > SLOWEST_S) return null
        val s = Math.round(secondsPerKm)
        return String.format(locale, "%d:%02d", s / Units.MINUTE, s % Units.MINUTE)
    }

    /** A pace with its unit, "5:24 /km", null as for [clock]. */
    fun text(secondsPerKm: Double?, locale: Locale = Locale.getDefault()): String? = clock(secondsPerKm, locale)?.let { "$it /km" }
}

/** A time of day, decision 4 in [Units]: every clock in the app follows the phone's 12 or 24 hour setting through here. */
object TimeOfDay {
    private const val MINUTES_PER_DAY = 1440

    /** [epoch] as a time of day: "22:30", or "10:30 PM" on a 12 hour phone. [use24Hour] is the phone's setting ([ClockSetting]). */
    fun format(epoch: Long, zone: ZoneId, use24Hour: Boolean = ClockSetting.use24Hour, locale: Locale = Locale.getDefault()): String =
        Instant.ofEpochSecond(epoch).atZone(zone).format(formatter(use24Hour, locale))

    /** A wall clock time with no date. */
    fun format(time: LocalTime, use24Hour: Boolean = ClockSetting.use24Hour, locale: Locale = Locale.getDefault()): String =
        time.format(formatter(use24Hour, locale))

    /** Minutes since midnight, wrapping past either end of the day: 1470 is "00:30". */
    fun minutes(minutes: Int, use24Hour: Boolean = ClockSetting.use24Hour, locale: Locale = Locale.getDefault()): String =
        format(LocalTime.ofSecondOfDay(Math.floorMod(minutes, MINUTES_PER_DAY) * Units.MINUTE), use24Hour, locale)

    private fun formatter(use24Hour: Boolean, locale: Locale) = Formatters.of(if (use24Hour) "HH:mm" else "h:mm a", locale)
}

/**
 * The phone's 12 or 24 hour setting, kept here so data code, widgets and tests can write a time without a Context.
 * The app sets it from `DateFormat.is24HourFormat` when it starts and when the setting changes. Until then it's 24
 * hours, as the app wrote every time before it followed the setting.
 */
object ClockSetting {
    @Volatile var use24Hour: Boolean = true
}

/**
 * [String.format] for text drawn in the dot font: ASCII digits and a dot decimal mark whatever the phone's language,
 * since the font has no other script's digits and a comma would read as a thousands mark.
 */
fun dotFormat(format: String, vararg args: Any?): String = String.format(Units.DOT_LOCALE, format, *args)

/**
 * Date and time formatters by pattern and locale, built once each. They write the locale's own digits
 * ([DecimalStyle.of]), so an Arabic phone's clocks and dates match its other numbers: a plain pattern would keep ASCII.
 */
internal object Formatters {
    private val cache = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

    fun of(pattern: String, locale: Locale): DateTimeFormatter =
        cache.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale).withDecimalStyle(DecimalStyle.of(locale)) }
}
