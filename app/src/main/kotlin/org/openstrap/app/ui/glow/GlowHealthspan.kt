package org.openstrap.app.ui.glow

import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.Ranges
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.openstrap.app.data.Healthspan
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.glow.draw.EdgeKind
import org.openstrap.app.presentation.TabCopy

/** The Healthspan card's orchid, used for the glow: warm enough to stay clear of Sleep's blue. */
private val Orchid = MetricColors.habitAge

/** "Steady" at 1x, "Slower" below, "Faster" above; "Learning" until there's enough history. */
internal fun paceLabel(pace: Float?): String = when {
    pace == null -> "Learning"
    pace < 1f -> "Slower"
    pace > 1f -> "Faster"
    else -> "Steady"
}

/** "1x", "0.5x", "-1x". */
internal fun paceText(pace: Float): String = if (pace % 1f == 0f) "${pace.toInt()}x" else "${pace}x"

/** "takes off about 1 year", "adds about ½ year", "takes off a few months" or "no change". */
internal fun effectText(years: Float): String {
    val y = abs(years)
    val verb = if (years < 0) "takes off" else "adds"
    return when {
        y < 0.05f -> "no change"
        y < 0.375f -> "$verb a few months"
        y < 0.75f -> "$verb about ½ year"
        else -> y.roundToInt().let { "$verb about $it ${if (it == 1) "year" else "years"}" }
    }
}

/** The steps a day the Steps habit's line aims for. */
private const val STEPS_AIM_LOW = 8000
private const val STEPS_AIM_HIGH = 10_000

/** The line under a habit: your value, the typical one it's scored against, and a target where there is one. */
internal fun termDetail(t: Healthspan.Term, age: Int): String {
    val v = t.value
    val ref = t.reference
    if (v == null) return if (t.habit == Healthspan.Habit.Vo2max) "No VO2 max from workouts yet, counted as typical" else "Not enough data yet, counted as typical"
    return when (t.habit) {
        Healthspan.Habit.Strength -> {
            val m = v.roundToInt()
            (if (m == 0) "None recorded" else "$m min a week") + ", most people your age do none" +
                (if (m < Healthspan.STRENGTH_TARGET_MINUTES) ", aim for ${Healthspan.STRENGTH_TARGET_MINUTES}" else "")
        }
        Healthspan.Habit.Vo2max -> "${v.roundToInt()} ml/kg/min, typical at $age: ${ref?.roundToInt()}"
        Healthspan.Habit.Steps -> "${Units.count(v.roundToInt())} a day, typical: ${Units.count(((ref ?: 0f) / 100).roundToInt() * 100)}" +
            (if (v < STEPS_AIM_LOW) ", aim for ${Ranges.numbers(STEPS_AIM_LOW, STEPS_AIM_HIGH)}" else "")
        Healthspan.Habit.Regularity -> "${v.roundToInt()}, typical: ${ref?.roundToInt()}"
        Healthspan.Habit.RestingHr -> "${v.roundToInt()} bpm asleep, typical: about ${ref?.roundToInt()}"
        Healthspan.Habit.TimeAsleep -> v.roundToInt().let { m ->
            "${Units.duration(m * MINUTE_S)}, counts only below ${Units.duration(Healthspan.SLEEP_COUNTS_BELOW_MINUTES * MINUTE_S)}"
        }
    }
}

/** Seconds in a minute, for the sleep habit's minutes. */
private const val MINUTE_S = 60L

/** "Sunday 22 Nov" for local midnight [day] (epoch seconds), with no-break spaces so it never wraps inside the date. */
internal fun sundayText(day: Long): String = DayWords.weekdayDayMonth(dateOf(day)).replace(' ', '\u00A0')

/** What to add to the profile: "Add your birth year and sex in your profile to see your Habit Age." */
internal fun profileReminder(missing: List<String>): String =
    "Add your ${missing.joinToString(" and ")} in your profile to see your Habit Age."

/** The local date of [epoch] (a local midnight). */
private fun dateOf(epoch: Long) = java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

/**
 * Habit Age, one design wherever it shows (Recovery's foot, Today when there's a new estimate, the top of its page):
 * an edge card lit from the left, since it's about the weeks behind you. The title with its caret when it opens
 * something and (i) at the right of the title row (spec 5.4), "Updated Sun 18 Oct" under it, the age in dots, one
 * status line against last week (the range until there's a last week, orchid only when the number moved) and the age
 * scale. The method, the pace of aging and what moves it are in its info sheet, behind the (i). Before the first
 * estimate your own age from your birth year stands in, "Your age for now" and when the first estimate comes under it;
 * without a birth year it says so, with no number. With no birth year or sex it opens the profile ([onOpenProfile])
 * instead of the Habit Age page ([onClick], null on the page itself). Nothing shows until it has been worked out.
 */
@Composable
fun HabitAgeEdgeCard(state: Healthspan.Outcome?, onClick: (() -> Unit)?, onOpenProfile: (() -> Unit)?, modifier: Modifier = Modifier) {
    state ?: return
    val r = state.result
    val withheld = state.withheld
    val needsProfile = r == null && state.missing.isNotEmpty() && withheld?.profile == true
    var info by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    EdgeCard(
        // Without a number it's one line, so it's only as high as that line.
        Orchid, modifier.fillMaxWidth().let { if (r != null || state.age != null) it.gridSize(CardSize.Wide) else it }, kind = EdgeKind.Earlier,
        onClick = if (needsProfile) onOpenProfile else onClick,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Habit Age", Modifier.weight(1f))
            InfoButton("About Habit Age", { info = true })
        }
        r?.let {
            Text(
                TabCopy.habitAgeUpdated(dateOf(it.asOf)), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                style = GlowType.secondary, maxLines = 1,
            )
        }
        val line = r?.let { TabCopy.habitAgeLine(it.estimate, state.lastWeek, it.low, it.high) }
        val waiting = when {
            r != null -> null
            needsProfile -> profileReminder(state.missing)
            state.firstEstimate != null -> TabCopy.firstEstimate(dateOf(state.firstEstimate))
            else -> withheld?.reason
        }
        // The estimate once there is one; until then your own age stands in. Without a birth year, no number.
        val shown = r?.estimate?.roundToInt() ?: state.age
        EdgeValue(
            shown?.toString(), shown?.let { "years" },
            line?.text ?: if (shown != null) TabCopy.habitAgeForNow(waiting) else waiting,
            if (line?.moved == true) Orchid else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
            waiting = shown != null,
        )
        if (r != null) {
            Spacer(Modifier.height(10.dp))
            AgeScale(r.age, r.low, r.high, r.estimate)
        }
    }
    if (info) {
        val here = r?.let { org.openstrap.app.ui.dashboard.InfoCopy.habitAgeBar(it.age - it.estimate.roundToInt()) }
        InfoSheet(
            org.openstrap.app.ui.dashboard.InfoCopy.HABIT_AGE, Orchid, here, here?.let { org.openstrap.app.ui.dashboard.InfoCopy.HERE },
            onDismiss = { info = false },
        )
    }
}

/**
 * The age scale (chart kit): a line of ages, seven years either side of yours, the estimate's range as an orchid band
 * with a white dot at the [estimate], and a hairline at your [age]. Labelled with the range under the band and you at
 * the hairline. An early estimate's range can run past the ends; it's drawn to them.
 */
@Composable
internal fun AgeScale(age: Int, low: Int, high: Int, estimate: Float) {
    val from = age - 7
    val to = age + 7
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        val pad = 8.dp
        fun f(a: Float) = (a.coerceIn(from.toFloat(), to.toFloat()) - from) / (to - from)
        fun x(a: Float) = pad + (w - pad * 2) * f(a)
        val label = Color.White.copy(alpha = 0.6f)
        Column {
            // Your age over its hairline, the range under its band, as in the kit, so the two never overlap.
            Box(Modifier.fillMaxWidth().height(16.dp)) { ScaleLabel(TabCopy.yourAge(age), x(age.toFloat()), w, label) }
            Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                val y = size.height / 2
                fun px(a: Float) = x(a).toPx()
                drawLine(Color.White.copy(alpha = 0.15f), Offset(px(from.toFloat()), y), Offset(px(to.toFloat()), y), 6.dp.toPx(), StrokeCap.Round)
                drawLine(Orchid.copy(alpha = 0.5f), Offset(px(low.toFloat()), y), Offset(px(high.toFloat()), y), 12.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White.copy(alpha = 0.6f), Offset(px(age.toFloat()), 0f), Offset(px(age.toFloat()), size.height), 1.dp.toPx())
                drawCircle(Color.White, 7.dp.toPx(), Offset(px(estimate), y))
            }
            Box(Modifier.fillMaxWidth().height(16.dp)) { ScaleLabel(TabCopy.ageRange(low, high), x((low + high) / 2f), w, label) }
        }
    }
}

/** A label centred on [at] across a scale [width] wide, kept inside it. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ScaleLabel(text: String, at: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp, color: Color) {
    // Centred on its point by aligning a share of the way across, as BiasAlignment does.
    val bias = ((at / width) * 2f - 1f).coerceIn(-1f, 1f)
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(androidx.compose.ui.BiasAlignment(bias, 0f)))
}
