package org.openstrap.app.presentation

import androidx.annotation.StringRes
import org.openstrap.app.R
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.i18n.Strings
import java.util.Locale

/**
 * A metric with its own detail screen: its title, unit, decimals shown, and the per-day series behind it. Stress has no
 * unit and blood oxygen's is the percent sign, which a string resource can't hold alone (every one is a format), so
 * both have [unitId] 0.
 */
enum class Metric(@StringRes private val titleId: Int, @StringRes private val unitId: Int, val decimals: Int, val daily: DailyMetric) {
    HeartRate(R.string.core_metric_heart_rate, R.string.core_unit_bpm, 0, DailyMetric.HeartRate),
    RestingHr(R.string.core_metric_resting_hr, R.string.core_unit_bpm, 0, DailyMetric.RestingHr),
    Hrv(R.string.core_metric_hrv, R.string.core_unit_ms, 0, DailyMetric.Hrv),
    Spo2(R.string.core_metric_spo2, 0, 0, DailyMetric.Spo2),
    Stress(R.string.core_metric_stress, 0, 0, DailyMetric.Stress),
    SkinTemperature(R.string.core_metric_skin_temperature, R.string.core_unit_celsius, 1, DailyMetric.SkinTemperature),
    Breathing(R.string.core_metric_breathing, R.string.core_unit_breaths, 1, DailyMetric.Breathing),
    Steps(R.string.core_metric_steps, R.string.core_unit_steps, 0, DailyMetric.Steps),
    ;

    val title: String get() = Strings.app.get(titleId)

    val unit: String get() = when {
        unitId != 0 -> Strings.app.get(unitId)
        this == Spo2 -> "%"
        else -> ""
    }

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
internal enum class Range(@StringRes private val labelId: Int, val days: Int) {
    Day(R.string.core_metric_range_day, 1),
    Week(R.string.core_metric_range_week, 7),
    Month(R.string.core_metric_range_month, 30),
    Year(R.string.core_metric_range_year, 365),
    ;

    val label: String get() = Strings.app.get(labelId)
}

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
