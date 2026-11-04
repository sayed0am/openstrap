package org.openstrap.app.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.glow.ButtonSize
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageSheet
import org.openstrap.app.ui.glow.RoundIconButton
import org.openstrap.app.ui.theme.AppIcons
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.data.Weeks

/**
 * What the date header's calendar needs: the days that have data, and where a picked day goes. Immutable, so a header
 * given an equal picker skips (audit K11).
 */
@androidx.compose.runtime.Immutable
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
    // Opened on another day, the calendar opens on that day's month (audit K8).
    var month by rememberSaveable(selected) { mutableStateOf(YearMonth.from(selected)) }

    // Glass over the blurred page, in the app's overlay. The month heads the sheet, between its arrows.
    PageSheet(onDismiss) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), color = Color.White, style = GlowType.cardTitle,
                    maxLines = 1, modifier = Modifier.weight(1f),
                )
                RoundIconButton(AppIcons.CaretLeft, "Previous month", { month = month.minusMonths(1) }, enabled = month > first)
                RoundIconButton(AppIcons.CaretRight, "Next month", { month = month.plusMonths(1) }, enabled = month < last)
            }
            Spacer(Modifier.height(16.dp))

            val firstDay = Weeks.firstDay()
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    Text(
                        firstDay.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2).uppercase(),
                        Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally),
                        color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA),
                        style = GlowType.caption,
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
                Box(Modifier.size(12.dp).background(DayFill, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("Has data", style = GlowType.secondary, color = LegendInk)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(6.dp).background(TodayDot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("Today", style = GlowType.secondary, color = LegendInk)
                Spacer(Modifier.weight(1f))
                if (selected != today) {
                    PageButton("Go to today", { onPick(today); onDismiss() }, icon = AppIcons.CalendarBlank, size = ButtonSize.Row)
                }
            }
        }
    }
}

/** A day with data: a light grey disc, so the days that can be picked read as something to press. */
private val DayFill = Color(0xFF2C2C2E)

/** The legend's words, quieter than the days. */
private val LegendInk = Color.White.copy(alpha = 0.6f)

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
    val text = when {
        selected -> Color.Black
        enabled -> Color.White
        else -> Color.White.copy(alpha = 0.2f)
    }
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .then(
                when {
                    selected -> Modifier.background(Color.White)
                    enabled -> Modifier.background(DayFill)
                    else -> Modifier
                },
            )
            .then(if (enabled) Modifier.clickable { onPick(date) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = GlowType.body.copy(fontFeatureSettings = "tnum"),
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
