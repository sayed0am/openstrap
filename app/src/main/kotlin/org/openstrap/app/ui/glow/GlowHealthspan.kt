package org.openstrap.app.ui.glow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.openstrap.app.data.Healthspan
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.R
import org.openstrap.app.tr

/** The Healthspan card's orchid, used for the glow: warm enough to stay clear of Sleep's blue. */
private val Orchid = MetricColors.habitAge

/** The same recipe as the Heart rate and Activity cards, in orchid. */
internal val HealthspanGlow = GlowStyle.chart(Orchid)

/** "3 years younger", "1 year older" or "Right on your age" for an [estimate] against [age], in whole years. */
internal fun healthspanVerdict(age: Int, estimate: Float): String {
    val years = age - estimate.roundToInt()
    return when {
        years == 1 -> tr(R.string.missed_year_younger)
        years > 0 -> tr(R.string.missed_years_younger, years)
        years == -1 -> tr(R.string.missed_year_older)
        years < 0 -> tr(R.string.missed_years_older, -years)
        else -> tr(R.string.glow_healthspan_right_on_your_age)
    }
}

/** "Steady" at 1x, "Slower" below, "Faster" above; "Learning" until there's enough history. */
internal fun paceLabel(pace: Float?): String = when {
    pace == null -> tr(R.string.common_learning)
    pace < 1f -> tr(R.string.glow_healthspan_slower)
    pace > 1f -> tr(R.string.glow_healthspan_faster)
    else -> tr(R.string.common_steady)
}

/** "1x", "0.5x", "-1x". */
internal fun paceText(pace: Float): String = if (pace % 1f == 0f) "${pace.toInt()}x" else "${pace}x"

/** "takes off about 1 year", "adds about ½ year", "takes off a few months" or "no change". */
internal fun effectText(years: Float): String {
    val y = abs(years)
    val verb = if (years < 0) tr(R.string.glow_healthspan_takes_off) else tr(R.string.missed_adds)
    return when {
        y < 0.05f -> tr(R.string.glow_healthspan_no_change)
        y < 0.375f -> tr(R.string.glow_healthspan_a_few_months, verb)
        y < 0.75f -> tr(R.string.glow_healthspan_about_year, verb)
        else -> y.roundToInt().let {
            when {
                it == 1 -> tr(if (years < 0) R.string.missed_takes_off_about_year else R.string.missed_adds_about_year)
                else -> tr(if (years < 0) R.string.missed_takes_off_about_years else R.string.missed_adds_about_years, it)
            }
        }
    }
}

/** The line under a habit: your value, the typical one it's scored against, and a target where there is one. */
internal fun termDetail(t: Healthspan.Term, age: Int): String {
    val v = t.value
    val ref = t.reference
    if (v == null) return if (t.habit == Healthspan.Habit.Vo2max) tr(R.string.glow_healthspan_no_vo_max_from) else tr(R.string.glow_healthspan_not_enough_data_yet)
    return when (t.habit) {
        Healthspan.Habit.Strength -> {
            val m = v.roundToInt()
            (if (m == 0) tr(R.string.glow_healthspan_none_recorded) else tr(R.string.glow_healthspan_min_a_week, m)) + tr(R.string.glow_healthspan_most_people_your_age) +
                (if (m < Healthspan.STRENGTH_TARGET_MINUTES) tr(R.string.glow_healthspan_aim_for, Healthspan.STRENGTH_TARGET_MINUTES) else "")
        }
        Healthspan.Habit.Vo2max -> tr(R.string.glow_healthspan_ml_kg_min_typical, v.roundToInt(), age, ref?.roundToInt())
        Healthspan.Habit.Steps -> tr(R.string.glow_healthspan_a_day_typical, "%,d".format(v.roundToInt()), "%,d".format(((ref ?: 0f) / 100).roundToInt() * 100)) +
            (if (v < 8000f) tr(R.string.glow_healthspan_aim_for_2) else "")
        Healthspan.Habit.Regularity -> tr(R.string.glow_healthspan_typical, v.roundToInt(), ref?.roundToInt())
        Healthspan.Habit.RestingHr -> tr(R.string.glow_healthspan_bpm_asleep_typical_about, v.roundToInt(), ref?.roundToInt())
        Healthspan.Habit.TimeAsleep -> v.roundToInt().let { m -> tr(R.string.glow_healthspan_h_m_counts_only, m / 60, m % 60) }
    }
}

/** "Sunday 22 Nov" for local midnight [day] (epoch seconds), with no-break spaces so it never wraps inside the date. */
internal fun sundayText(day: Long): String = java.time.Instant.ofEpochSecond(day).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    .format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMM")).replace(' ', '\u00A0')

/** What to add to the profile: "Add your birth year and sex in your profile to see your Habit Age." */
internal fun profileReminder(missing: List<String>): String =
    tr(R.string.glow_healthspan_add_your_in_your, missing.joinToString(tr(R.string.glow_healthspan_add_your_in_your__and)))

/** The starting state's line under your age: when a first estimate is due, or what it's still waiting for. */
internal fun learningText(o: Healthspan.Outcome): String = when {
    // The date's line is kept whole, so "First estimate:" never ends up apart from its date.
    o.firstEstimate != null -> tr(R.string.glow_healthspan_starts_at_your_age, sundayText(o.firstEstimate))
    o.withheld == Healthspan.Withheld.Learning -> tr(R.string.glow_healthspan_starts_at_your_age_2)
    else -> tr(R.string.glow_healthspan_starts_at_your_age_3, o.withheld?.reason.orEmpty())
}

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
            CardTitle(tr(R.string.common_habit_age), Modifier.weight(1f))
            InfoButton(tr(R.string.glow_healthspan_about_habit_age), { info = true })
        }
        r?.let {
            Text(
                org.openstrap.app.ui.dashboard.TabCopy.habitAgeUpdated(dateOf(it.asOf)), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                style = GlowType.secondary, maxLines = 1,
            )
        }
        val line = r?.let { org.openstrap.app.ui.dashboard.TabCopy.habitAgeLine(it.estimate, state.lastWeek, it.low, it.high) }
        val waiting = when {
            r != null -> null
            needsProfile -> profileReminder(state.missing)
            state.firstEstimate != null -> org.openstrap.app.ui.dashboard.TabCopy.firstEstimate(dateOf(state.firstEstimate))
            else -> withheld?.reason
        }
        // The estimate once there is one; until then your own age stands in. Without a birth year, no number.
        val shown = r?.estimate?.roundToInt() ?: state.age
        EdgeValue(
            shown?.toString(), shown?.let { tr(R.string.missed_unit_years) },
            line?.text ?: if (shown != null) org.openstrap.app.ui.dashboard.TabCopy.habitAgeForNow(waiting) else waiting,
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
            Box(Modifier.fillMaxWidth().height(16.dp)) { ScaleLabel(org.openstrap.app.ui.dashboard.TabCopy.yourAge(age), x(age.toFloat()), w, label) }
            Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                val y = size.height / 2
                fun px(a: Float) = x(a).toPx()
                drawLine(Color.White.copy(alpha = 0.15f), Offset(px(from.toFloat()), y), Offset(px(to.toFloat()), y), 6.dp.toPx(), StrokeCap.Round)
                drawLine(Orchid.copy(alpha = 0.5f), Offset(px(low.toFloat()), y), Offset(px(high.toFloat()), y), 12.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White.copy(alpha = 0.6f), Offset(px(age.toFloat()), 0f), Offset(px(age.toFloat()), size.height), 1.dp.toPx())
                drawCircle(Color.White, 7.dp.toPx(), Offset(px(estimate), y))
            }
            Box(Modifier.fillMaxWidth().height(16.dp)) { ScaleLabel(org.openstrap.app.ui.dashboard.TabCopy.ageRange(low, high), x((low + high) / 2f), w, label) }
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
