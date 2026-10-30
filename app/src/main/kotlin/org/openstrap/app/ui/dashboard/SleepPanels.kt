package org.openstrap.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openstrap.app.data.DayStat
import org.openstrap.app.data.History
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.ui.components.formatDuration
import org.openstrap.app.ui.theme.MetricColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import kotlin.math.roundToInt
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * A row of the Sleep tab's night vitals: the [value] in dot-matrix digits (null while there's none), what it says
 * against your previous nights ([status]) and which way it's off ([direction], null with no normal yet).
 */
internal class NightVital(
    val metric: Metric,
    val label: String,
    val color: Color,
    val value: String?,
    val status: String,
    val direction: Int? = null,
)

/**
 * The six night vitals the Sleep tab lists (spec 5.2), always all six, in order: lowest heart rate, HRV, breathing
 * rate, blood oxygen, skin temperature (as its change from your normal once there is one) and average heart rate.
 * HRV, breathing, blood oxygen and skin temperature are read against your previous nights once there are a week of them.
 */
internal fun nightVitals(state: DayUiState, firstNights: Boolean = false): List<NightVital> {
    val session = state.sleep
    val missing = when {
        firstNights && session == null -> AFTER_FIRST_NIGHT
        session == null -> tr(R.string.common_no_sleep_recorded)
        else -> tr(R.string.sleep_panels_no_reading_last_night)
    }
    // The same value and normal the night-scoped detail shows ([SleepCopy]).
    fun against(metric: Metric, label: String, color: Color, show: (Float, History.Normal?) -> String): NightVital {
        val v = SleepCopy.nightValue(state, metric) ?: return NightVital(metric, label, color, null, missing)
        val n = SleepCopy.nightNormal(state, metric)
        val direction = History.status(v, n)
        val status = if (n == null) {
            tr(R.string.sleep_panels_learning_of_nights, SleepCopy.nightNormalNights(state, metric).coerceAtMost(History.MIN_NORMAL_DAYS), History.MIN_NORMAL_DAYS)
        } else TodayCopy.normalWord(direction)
        return NightVital(metric, label, color, show(v, n), status, direction.takeIf { n != null })
    }
    val lowest = SleepCopy.lowestHeart(state)
    val average = SleepCopy.averageHeart(state)
    return listOf(
        NightVital(
            Metric.HeartRate, tr(R.string.sleep_panels_lowest_heart_rate), MetricColors.heart, lowest?.y?.roundToInt()?.toString(),
            lowest?.let { tr(R.string.sleep_panels_at, clockOf(it.x)) } ?: missing,
        ),
        against(Metric.Hrv, tr(R.string.common_hrv), MetricColors.hrv) { v, _ -> "${v.roundToInt()}" },
        against(Metric.Breathing, tr(R.string.common_breathing_rate), MetricColors.sleepLight) { v, _ -> Metric.Breathing.format(v) },
        against(Metric.Spo2, tr(R.string.common_blood_oxygen), MetricColors.spo2) { v, _ -> "${v.roundToInt()}%" },
        // Skin temperature means little on its own; once there's a normal, show how far off it is.
        against(Metric.SkinTemperature, tr(R.string.common_skin_temperature), MetricColors.temperature) { v, n ->
            n?.let { Metric.SkinTemperature.formatChange(v - it.mean) } ?: Metric.SkinTemperature.format(v)
        },
        NightVital(Metric.HeartRate, tr(R.string.sleep_panels_average_heart_rate), MetricColors.heart, average?.toString(), if (average != null) tr(R.string.sleep_panels_while_asleep) else missing),
    )
}

/**
 * The last seven nights: when each sleep started and ended on a shared clock, with time asleep over each bar,
 * then the week's averages and how regular it was. Regularity is the Sleep Regularity Index from RecoveryResult
 * (SleepRegularity.Result.sri), banded at the UK Biobank quintile cuts; null [regularity] means it is still loading.
 */
internal class SleepWeek(
    val nights: List<SleepNight>,
    val slept: Int,
    val usual: SleepHistory.UsualTimes?,
    val regularity: SleepRegularity.Result?,
    val averageAsleep: String,
    val goalNights: Int,
) {
    /** The SRI shown as "Regularity", or null when withheld or loading. Its method is in its info sheet ([InfoCopy.regularityNotes]). */
    val sri: Float? get() = regularity?.sri
    val band: SleepRegularity.Band? get() = regularity?.band
}

/** The last seven nights' facts, or null when none of them has sleep. [regularity] comes from the selected day's recovery. */
internal fun sleepWeek(
    state: DayUiState, goalSeconds: Long, regularity: SleepRegularity.Result?, zone: ZoneId = ZoneId.systemDefault(),
): SleepWeek? {
    val week = state.nights.takeLast(SleepHistory.WEEK_NIGHTS)
    val slept = week.filter { it.session != null }
    if (slept.isEmpty()) return null
    val asleep = slept.mapNotNull { it.asleepSeconds }
    return SleepWeek(
        nights = week,
        slept = slept.size,
        usual = SleepHistory.usualTimes(state.nights.mapNotNull { n -> n.session?.let { n.date to it } }.toMap(), state.date, zone),
        regularity = regularity,
        averageAsleep = formatDuration(asleep.average().toLong()),
        goalNights = asleep.count { it >= goalSeconds },
    )
}

internal fun bandColor(band: SleepRegularity.Band) = when (band) {
    SleepRegularity.Band.VeryRegular, SleepRegularity.Band.Regular -> MetricColors.sleepRem
    SleepRegularity.Band.Fair -> MetricColors.sleepLight
    SleepRegularity.Band.Irregular -> MetricColors.sleepAwake
}

/**
 * Bed and wake bars (chart kit): one bar per night on a clock running down the chart (evening at the top, morning at
 * the bottom), from falling asleep to waking, the [selected] night (the last, without one) in white. Your usual
 * bedtime and wake time are dim bands across, so nights that drift stand out. With [onNight], tapping a night picks it.
 */
@Composable
internal fun SleepWindows(
    week: List<SleepNight>, usual: SleepHistory.UsualTimes?, zone: ZoneId,
    selected: java.time.LocalDate? = null, onNight: ((java.time.LocalDate) -> Unit)? = null, height: androidx.compose.ui.unit.Dp = 110.dp,
) {
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
    val today = axis.copy(color = Color.White)

    // Minutes since the noon before each night, so a night never wraps.
    fun sinceNoon(epoch: Long, wake: Boolean): Int {
        val t = Instant.ofEpochSecond(epoch).atZone(zone).toLocalTime()
        val m = t.hour * 60 + t.minute
        return if (wake) m + 12 * 60 else Math.floorMod(m - 12 * 60, 24 * 60)
    }
    val spans = week.map { n -> n.session?.let { sinceNoon(it.start, false) to sinceNoon(it.end, true).coerceAtLeast(sinceNoon(it.start, false) + 1) } }
    val known = spans.filterNotNull()
    val usualBed = usual?.let { it.bedtime - 12 * 60 }
    val usualWake = usual?.let { it.wake + 12 * 60 }
    val top = (listOfNotNull(known.minOfOrNull { it.first }, usualBed).minOrNull() ?: (10 * 60)) - 30
    val bottom = (listOfNotNull(known.maxOfOrNull { it.second }, usualWake).maxOrNull() ?: (20 * 60)) + 30

    val tap = if (onNight == null || week.isEmpty()) Modifier else Modifier.pointerInput(week, onNight) {
        detectTapGestures { o ->
            val i = (o.x / (size.width.toFloat() / week.size)).toInt().coerceIn(0, week.lastIndex)
            onNight(week[i].date)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(height).then(tap)) {
        val labelH = 16.dp.toPx()
        val chartBottom = size.height - labelH
        val slot = size.width / week.size.coerceAtLeast(1)
        fun yOf(m: Int) = chartBottom * (m - top).toFloat() / (bottom - top).coerceAtLeast(1)
        val band = 14.dp.toPx()
        listOfNotNull(usualBed, usualWake).forEach { m ->
            drawRect(MetricColors.sleepDeep.copy(alpha = 0.16f), Offset(0f, yOf(m) - band / 2), Size(size.width, band))
        }
        val width = 16.dp.toPx().coerceAtMost(slot * 0.5f)
        week.forEachIndexed { i, n ->
            val cx = slot * i + slot / 2
            val last = if (selected != null) n.date == selected else i == week.lastIndex
            val name = n.date.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault()).take(2)
            val label = measurer.measure(name, if (last) today else axis)
            drawText(label, topLeft = Offset(cx - label.size.width / 2, chartBottom + 3.dp.toPx()))
            val span = spans[i]
            if (span == null) {
                drawCircle(Color.White.copy(alpha = 0.25f), 2.5.dp.toPx(), Offset(cx, chartBottom / 2))
                return@forEachIndexed
            }
            val y0 = yOf(span.first)
            val y1 = yOf(span.second)
            drawRoundRect(
                if (last) Color.White else MetricColors.sleepLight,
                Offset(cx - width / 2, y0), Size(width, (y1 - y0).coerceAtLeast(width)), CornerRadius(width / 2),
            )
        }
    }
}

internal fun minutesClock(minutes: Int): String = SleepHistory.clockMinutes(minutes).let { "%02d:%02d".format(it / 60, it % 60) }

private fun clockOf(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).let { "%02d:%02d".format(it.hour, it.minute) }
