package org.openstrap.app.presentation

import org.openstrap.app.data.DailyMetric
import java.util.Locale

/** A metric with its own detail screen: its title, unit, decimals shown, and the per-day series behind it. */
enum class Metric(val title: String, val unit: String, val decimals: Int, val daily: DailyMetric) {
    HeartRate("Heart rate", "bpm", 0, DailyMetric.HeartRate),
    RestingHr("Resting heart rate", "bpm", 0, DailyMetric.RestingHr),
    Hrv("HRV", "ms", 0, DailyMetric.Hrv),
    Spo2("Blood oxygen", "%", 0, DailyMetric.Spo2),
    Stress("Stress", "", 0, DailyMetric.Stress),
    SkinTemperature("Skin temperature", "°C", 1, DailyMetric.SkinTemperature),
    Breathing("Breathing rate", "br/min", 1, DailyMetric.Breathing),
    Steps("Steps", "steps", 0, DailyMetric.Steps),
    ;

    /**
     * [v] as this metric shows it: "13,860" steps, "36.5" °C, in [locale]'s digits and marks. A figure drawn in the dot
     * font passes [Units.DOT_LOCALE], since the font has only ASCII digits and a comma would read as a thousands mark.
     */
    fun format(v: Float, locale: Locale = Locale.getDefault()): String =
        if (this == Steps) Units.count(Math.round(v).toLong(), locale) else String.format(locale, "%.${decimals}f", v)

    /** A change from your normal, signed with a true minus (spec 2.3): "+0.2", "−0.3"; no sign is lost to rounding. */
    fun formatChange(d: Float, locale: Locale = Locale.getDefault()): String {
        val shown = format(kotlin.math.abs(d), locale)
        return (if (d < 0 && shown.any { it in '1'..'9' }) "\u2212" else "+") + shown
    }
}

/** How far back a metric detail looks: the selected day, or one value per day (per week, for a year). */
// Each range's length in days is its definition, read nowhere else.
@Suppress("MagicNumber")
internal enum class Range(val label: String, val days: Int) { Day("Day", 1), Week("Week", 7), Month("Month", 30), Year("Year", 365) }

/** Where a metric's number for a day comes from, which decides how its sentence talks about the day. */
internal enum class DaySource {
    /** From the night: breathing rate in sleep, and the strap's resting heart rate from your calmest stretch. */
    Night,

    /** The average of every reading dated that day: HRV, blood oxygen spot readings, skin temperature, stress. */
    Average,

    /** A running total or the latest reading: steps, heart rate. */
    SoFar,
}

internal fun daySource(metric: Metric): DaySource = when (metric) {
    Metric.Breathing, Metric.RestingHr -> DaySource.Night
    Metric.Hrv, Metric.Spo2, Metric.SkinTemperature, Metric.Stress -> DaySource.Average
    Metric.Steps, Metric.HeartRate -> DaySource.SoFar
}
