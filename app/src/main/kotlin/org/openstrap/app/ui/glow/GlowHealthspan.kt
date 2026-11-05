package org.openstrap.app.ui.glow

import org.openstrap.app.R
import androidx.compose.ui.res.stringResource
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.Ranges
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import org.openstrap.app.ui.components.cardNumber
import org.openstrap.app.ui.dashboard.OffWords
import org.openstrap.app.ui.dashboard.habitAgeOffStatus
import org.openstrap.app.ui.dashboard.offTag
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.pictures.AgeScalePicture
import org.openstrap.app.ui.glow.pictures.PictureSize
import org.openstrap.app.ui.glow.pictures.ageScaleRim
import org.openstrap.app.ui.glow.pictures.ageScaleX
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.openstrap.app.data.Healthspan
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.presentation.TabCopy

/** Habit Age's ink, orchid: warm enough to stay clear of Sleep's blue. */
private val Orchid = MetricColors.habitAge

/** "Steady" at 1x, "Slower" below, "Faster" above; "Learning" until there's enough history. */
internal fun paceLabel(pace: Float?, strings: Strings = Strings.app): String = strings.get(
    when {
        pace == null -> R.string.glow_healthspan_pace_learning
        pace < 1f -> R.string.glow_healthspan_pace_slower
        pace > 1f -> R.string.glow_healthspan_pace_faster
        else -> R.string.glow_healthspan_pace_steady
    },
)

/** "1x", "0.5x", "-1x". */
internal fun paceText(pace: Float, strings: Strings = Strings.app): String =
    strings.format(R.string.glow_healthspan_pace_times, if (pace % 1f == 0f) pace.toInt().toString() else pace.toString())

/** "takes off about 1 year", "adds about ½ year", "takes off a few months" or "no change". */
internal fun effectText(years: Float, strings: Strings = Strings.app): String {
    val y = abs(years)
    val off = years < 0
    return when {
        y < 0.05f -> strings.get(R.string.glow_healthspan_effect_none)
        y < 0.375f -> strings.get(if (off) R.string.glow_healthspan_effect_off_months else R.string.glow_healthspan_effect_adds_months)
        y < 0.75f -> strings.get(if (off) R.string.glow_healthspan_effect_off_half else R.string.glow_healthspan_effect_adds_half)
        else -> y.roundToInt().let {
            strings.plural(if (off) R.plurals.glow_healthspan_effect_off_years else R.plurals.glow_healthspan_effect_adds_years, it, it)
        }
    }
}

/** The steps a day the Steps habit's line aims for. */
private const val STEPS_AIM_LOW = 8000
private const val STEPS_AIM_HIGH = 10_000

/** The line under a habit: your value, the typical one it's scored against, and a target where there is one. */
internal fun termDetail(t: Healthspan.Term, age: Int, strings: Strings = Strings.app): String {
    val v = t.value
    val ref = t.reference?.roundToInt().toString()
    if (v == null) {
        return strings.get(if (t.habit == Healthspan.Habit.Vo2max) R.string.glow_healthspan_no_vo2max else R.string.glow_healthspan_no_data)
    }
    return when (t.habit) {
        Healthspan.Habit.Strength -> {
            val m = v.roundToInt()
            val aim = m < Healthspan.STRENGTH_TARGET_MINUTES
            when {
                m == 0 && aim -> strings.format(R.string.glow_healthspan_strength_none_aim, Healthspan.STRENGTH_TARGET_MINUTES)
                m == 0 -> strings.get(R.string.glow_healthspan_strength_none)
                aim -> strings.format(R.string.glow_healthspan_strength_minutes_aim, m, Healthspan.STRENGTH_TARGET_MINUTES)
                else -> strings.format(R.string.glow_healthspan_strength_minutes, m)
            }
        }
        Healthspan.Habit.Vo2max -> strings.format(R.string.glow_healthspan_vo2max, v.roundToInt(), age, ref)
        Healthspan.Habit.Steps -> {
            val steps = Units.count(v.roundToInt())
            val typical = Units.count(((t.reference ?: 0f) / 100).roundToInt() * 100)
            val aim = Ranges.numbers(STEPS_AIM_LOW, STEPS_AIM_HIGH)
            if (v < STEPS_AIM_LOW) strings.format(R.string.glow_healthspan_steps_aim, steps, typical, aim)
            else strings.format(R.string.glow_healthspan_steps, steps, typical)
        }
        Healthspan.Habit.Regularity -> strings.format(R.string.glow_healthspan_regularity, v.roundToInt(), ref)
        Healthspan.Habit.RestingHr -> strings.format(R.string.glow_healthspan_resting_hr, v.roundToInt(), ref)
        Healthspan.Habit.TimeAsleep -> v.roundToInt().let { m ->
            strings.format(
                R.string.glow_healthspan_time_asleep, Units.duration(m * MINUTE_S),
                Units.duration(Healthspan.SLEEP_COUNTS_BELOW_MINUTES * MINUTE_S),
            )
        }
    }
}

/** Seconds in a minute, for the sleep habit's minutes. */
private const val MINUTE_S = 60L

/** "Sunday 22 Nov" for local midnight [day] (epoch seconds), with no-break spaces so it never wraps inside the date. */
internal fun sundayText(day: Long): String = DayWords.weekdayDayMonth(dateOf(day)).replace(' ', '\u00A0')

/** What to add to the profile: "Add your birth year and sex to your profile to see your Habit Age." */
internal fun profileReminder(missing: List<Healthspan.ProfileGap>, strings: Strings = Strings.app): String = strings.get(
    when {
        missing.size > 1 -> R.string.glow_healthspan_profile_both
        missing.firstOrNull() == Healthspan.ProfileGap.Sex -> R.string.glow_healthspan_profile_sex
        else -> R.string.glow_healthspan_profile_birth_year
    },
)

/** The local date of [epoch] (a local midnight). */
private fun dateOf(epoch: Long) = java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

/**
 * Habit Age, one design wherever it shows (Recovery's foot, Today when there's a new estimate, the top of its page):
 * a Wide picture card (spec 5.4). The title with its caret when it opens something and (i) at the right of the title
 * row, "Updated Sun 18 Oct" under it, the age in dots, one status line against last week (the range until there's a
 * last week) and the age scale. When the estimate's whole range is past your own age it carries the Older or Younger
 * tag and the rim at the band's end ([habitAgeOffStatus]). The method, the pace of aging and what moves it are in its
 * info sheet, behind the (i). Before the first estimate your own age from your birth year stands in, "Your age for
 * now" and when the first estimate comes under it. Without a birth year it says so, with no number. With no birth year
 * or sex it opens the profile ([onOpenProfile]) instead of the Habit Age page ([onClick], null on the page itself).
 * Nothing shows until it has been worked out.
 */
@Composable
fun HabitAgeEdgeCard(state: Healthspan.Outcome?, onClick: (() -> Unit)?, onOpenProfile: (() -> Unit)?, modifier: Modifier = Modifier) {
    state ?: return
    val r = state.result
    val withheld = state.withheld
    val needsProfile = r == null && state.missing.isNotEmpty() && withheld?.profile == true
    var info by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val off = habitAgeOffStatus(state)
    val tag = offTag(OffWords.Age, off)
    val rim = r?.takeIf { tag != null }?.let { ageScaleRim(it.low.toFloat(), it.high.toFloat(), it.age.toFloat(), PictureSize.Wide) }
    PictureCard(
        // Without a number it's one line, so it's only as high as that line.
        Orchid, modifier.fillMaxWidth().let { if (r != null || state.age != null) it.gridSize(CardSize.Wide) else it },
        rim = rim,
        onClick = if (needsProfile) onOpenProfile else onClick,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CardTitle(stringResource(R.string.glow_healthspan_title), Modifier.weight(1f))
            InfoButton(stringResource(R.string.glow_healthspan_about), { info = true })
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
        // The estimate once there is one, and until then your own age stands in. Without a birth year, no number.
        val shown = r?.estimate?.roundToInt() ?: state.age
        val status = line?.text ?: if (shown != null) TabCopy.habitAgeForNow(waiting) else waiting
        if (shown != null) {
            NumberWithTag(tag, Modifier.padding(top = 14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    DotMatrixText(shown.toString(), Modifier.cardNumber(shown.toString()), dot = DotSize.Edge)
                    Text(
                        stringResource(R.string.glow_healthspan_years), color = Color.White.copy(alpha = 0.7f), style = GlowType.secondary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        if (status != null) Text(
            status, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
            modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
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

/** The dot at the estimate's radius in [AgeScalePicture]: the scale runs this far in from each end. */
private val AgeScaleInset = 6.dp

/**
 * The age scale (spec 2.7, Age scale): [AgeScalePicture] with "You, 34" over the hairline at your [age] and "27 to 31"
 * under the band, each placed with [ageScaleX] and kept inside the card.
 */
@Composable
private fun AgeScale(age: Int, low: Int, high: Int, estimate: Float) {
    val label = Color.White.copy(alpha = GlowType.CAPTION_ALPHA)
    fun at(a: Float) = ageScaleX(a, low.toFloat(), high.toFloat(), age.toFloat())
    Column(Modifier.fillMaxWidth()) {
        LabelAt(TabCopy.yourAge(age), at(age.toFloat()), label)
        AgeScalePicture(
            low.toFloat(), high.toFloat(), estimate, age.toFloat(), Orchid, PictureSize.Wide, Modifier.fillMaxWidth().height(28.dp),
        )
        LabelAt(TabCopy.ageRange(low, high), at((low + high) / 2f), label)
    }
}

/** [text] in Caption centred on [share] of the scale (0 to 1, inside [AgeScaleInset] at each end), kept inside the row. */
@Composable
private fun LabelAt(text: String, share: Float, color: Color) {
    Layout(content = { Text(text, color = color, style = GlowType.caption, maxLines = 1) }, modifier = Modifier.fillMaxWidth()) { measurables, cs ->
        val p = measurables.first().measure(cs.copy(minWidth = 0))
        val width = cs.maxWidth
        val inset = AgeScaleInset.roundToPx()
        val centre = inset + share * (width - 2 * inset)
        layout(width, p.height) {
            p.place((centre - p.width / 2f).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
        }
    }
}
