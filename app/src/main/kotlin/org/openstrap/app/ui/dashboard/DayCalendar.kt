package org.openstrap.app.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.components.SectionLabel
import org.openstrap.app.ui.theme.AppIcons
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.tr

/** What the date header's calendar needs: the days that have data, and where a picked day goes. */
data class DayPicker(val available: Set<LocalDate> = emptySet(), val onPick: (LocalDate) -> Unit = {})

/** The red full stop from the wordmark marks today. */
private val TodayDot = Color(0xFFFF4D5E)

/**
 * Month calendar in a bottom sheet. Days with data are lit and tappable; the rest are dimmed and can't be
 * picked. Today is always pickable and carries the wordmark's red dot.
 */
@Composable
fun DayCalendarSheet(selected: LocalDate, available: Set<LocalDate>, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val first = YearMonth.from(available.minOrNull()?.coerceAtMost(today) ?: today)
    val last = YearMonth.from(today)
    var month by remember { mutableStateOf(YearMonth.from(selected)) }

    // Glass over the blurred page, in the app's overlay.
    org.openstrap.app.ui.components.GlassSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { month = month.minusMonths(1) }, enabled = month > first) {
                    Icon(AppIcons.CaretLeft, tr(R.string.day_calendar_previous_month))
                }
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < last) {
                    Icon(AppIcons.CaretRight, tr(R.string.day_calendar_next_month))
                }
            }
            Spacer(Modifier.height(12.dp))

            val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    SectionLabel(
                        firstDay.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                        Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            AnimatedContent(
                targetState = month,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(220)) { it * dir / 4 } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally(tween(220)) { -it * dir / 4 } + fadeOut(tween(160)))
                },
                label = "month",
            ) { m ->
                MonthGrid(m, firstDay, selected, today, available) { onPick(it); onDismiss() }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(tr(R.string.day_calendar_has_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(6.dp).background(TodayDot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(tr(R.string.common_today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (selected != today) TextButton(onClick = { onPick(today); onDismiss() }) { Text(tr(R.string.day_calendar_go_to_today)) }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    firstDay: DayOfWeek,
    selected: LocalDate,
    today: LocalDate,
    available: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
) {
    val lead = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    val cells = lead + month.lengthOfMonth()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (week in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNumber = week * 7 + col - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayNumber)
                            DayCell(date, date == selected, date == today, date <= today && (date in available || date == today), onPick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, today: Boolean, enabled: Boolean, onPick: (LocalDate) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val text = when {
        selected -> scheme.surface
        enabled -> scheme.onSurface
        else -> scheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .then(
                when {
                    selected -> Modifier.background(scheme.onSurface)
                    enabled -> Modifier.background(scheme.surfaceContainerHighest).border(1.dp, scheme.outline, CircleShape)
                    else -> Modifier
                },
            )
            .then(if (enabled) Modifier.clickable { onPick(date) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = text,
            textAlign = TextAlign.Center,
        )
        if (today) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(5.dp)
                    .background(TodayDot, CircleShape),
            )
        }
    }
}
