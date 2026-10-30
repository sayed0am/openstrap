package org.openstrap.app.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.openstrap.app.ui.components.growClick
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.R
import org.openstrap.app.tr

/** How far a swipe on the header has to travel to change the day. */
private val SwipeDistance = 56.dp

/**
 * The header on every tab (spec 3.1): the avatar on the left opens You, the [title] over the day in the middle, the
 * All metrics button and the strap chip on the right open the sheet and the Strap page. Swipe it sideways to change
 * the day (on every tab at once), or tap the day for the calendar. The day before peeks in dimmed on the left, the day
 * after on the right while there is one. Pull to sync opens its gap underneath.
 */
@Composable
fun MomentHeader(
    title: String,
    date: LocalDate,
    chip: StrapChip,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    picker: DayPicker,
    onOpenYou: () -> Unit,
    onOpenStrap: () -> Unit,
    /** Shows the All metrics button when set. */
    onOpenAllMetrics: (() -> Unit)?,
    /** A new version waits: a dot on the avatar until the Updates page is opened. */
    updateWaiting: Boolean = false,
) {
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    if (calendarOpen) DayCalendarSheet(date, picker.available, picker.onPick, onDismiss = { calendarOpen = false })
    val today = LocalDate.now()
    val hasNext = date < today
    Column(Modifier.fillMaxWidth()) {
        // With no day after this one, the room kept for its peek is empty and may tuck under the right side.
        CentredRow(
            overhang = if (hasNext) 0.dp else PeekWidth + 16.dp,
            // You and the Strap page grow out of the button tapped (spec 3.5).
            start = {
                growClick(onOpenYou).let { g ->
                    RoundButton(AppIcons.User, if (updateWaiting) tr(R.string.moment_header_you_a_new_version) else tr(R.string.common_you), tr(R.string.moment_header_open_you), g.onClick, g.modifier, dot = updateWaiting)
                }
            },
            centre = { DaySwitcher(title, date, hasNext, onPrev, onNext, { calendarOpen = true }, Modifier) },
            end = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onOpenAllMetrics != null) {
                        RoundButton(AppIcons.ChartBar, tr(R.string.common_all_metrics), tr(R.string.moment_header_open_all_metrics), onOpenAllMetrics)
                        Spacer(Modifier.width(8.dp))
                    }
                    growClick(onOpenStrap).let { g -> StrapChipButton(chip, g.onClick, g.modifier) }
                }
            },
        )
        // Pull to sync opens here and pushes everything below it down.
        org.openstrap.app.ui.components.PullSyncReveal()
    }
}

/**
 * [start] and [end] at the edges, [centre] in the middle of the screen rather than of the room between them, so the day
 * sits under the status bar's centre whatever the sides hold. Only when the strap chip grows wide enough to reach it does
 * the centre slide over, just far enough to clear it. The last [overhang] of the centre is empty and may pass under [end].
 */
@Composable
private fun CentredRow(
    overhang: androidx.compose.ui.unit.Dp, start: @Composable () -> Unit, centre: @Composable () -> Unit, end: @Composable () -> Unit,
) {
    Layout(listOf(start, centre, end), Modifier.fillMaxWidth().height(60.dp)) { (s, c, e), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val sp = s.first().measure(loose)
        val ep = e.first().measure(loose)
        val over = overhang.roundToPx()
        val room = (constraints.maxWidth - sp.width - ep.width + over).coerceAtLeast(0)
        val cp = c.first().measure(loose.copy(maxWidth = room))
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        layout(w, h) {
            sp.place(0, (h - sp.height) / 2)
            ep.place(w - ep.width, (h - ep.height) / 2)
            val x = ((w - cp.width) / 2).coerceIn(sp.width, (w - ep.width - cp.width + over).coerceAtLeast(sp.width))
            cp.place(x, (h - cp.height) / 2)
        }
    }
}

/** A round button at either end of the header: the avatar, and All metrics in its likeness. [dot] marks its top right. */
@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, clickLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    dot: Boolean = false,
) {
    Box(modifier.size(38.dp)) {
        Box(
            Modifier.matchParentSize().clip(CircleShape).background(Avatar).clickable(onClickLabel = clickLabel, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, description, Modifier.size(20.dp), tint = Color.White) }
        // On the circle's edge at its top right, ringed in the page's black so it reads apart from the button.
        if (dot) org.openstrap.app.ui.glow.NoticeDot(Modifier.align(Alignment.TopEnd), ring = Color.Black)
    }
}

/** The title over the day, with its neighbours peeking in either side; it follows a sideways drag and springs back. */
@Composable
private fun DaySwitcher(
    title: String, date: LocalDate, hasNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit, onPick: () -> Unit, modifier: Modifier,
) {
    val threshold = with(LocalDensity.current) { SwipeDistance.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shift by animateFloatAsState(drag, if (dragging) snap() else spring(dampingRatio = 1f), label = "header drag")
    val prev by rememberUpdatedState(onPrev)
    val next by rememberUpdatedState(onNext)
    val canNext by rememberUpdatedState(hasNext)
    Row(
        modifier.height(60.dp)
            .pointerInput(threshold) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        // Pulling the day to the right brings the day before, as a page would.
                        if (drag > threshold) prev() else if (drag < -threshold && canNext) next()
                        dragging = false; drag = 0f
                    },
                    onDragCancel = { dragging = false; drag = 0f },
                ) { change, amount ->
                    change.consume()
                    // No day after today: the drag that way only gives a little.
                    drag = (drag + amount).let { if (it < 0 && !canNext) it.coerceAtLeast(-threshold / 3) else it }
                }
            }
            .graphicsLayer { translationX = shift * 0.4f },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Peek(peekDay(date.minusDays(1)))
        Column(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 200.dp).clip(CircleShape)
                .clickable(onClickLabel = tr(R.string.moment_header_pick_a_day), onClick = onPick)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .semantics { contentDescription = "$title, ${dateLine(date)}" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title, color = Color.White.copy(alpha = GlowType.MOMENT_ALPHA), style = GlowType.moment,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
            Text(
                dateLine(date), color = Color.White, style = GlowType.headerDate,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasNext) Peek(peekDay(date.plusDays(1))) else Spacer(Modifier.width(PeekWidth))
    }
}

@Composable
private fun Peek(text: String) {
    Text(text, Modifier.width(PeekWidth), color = Color.White.copy(alpha = 0.22f), style = GlowType.body, maxLines = 1, softWrap = false)
}

/** The strap chip: a dot, and words only when they matter. */
@Composable
private fun StrapChipButton(chip: StrapChip, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = CircleShape
    Row(
        modifier.clip(shape).background(ChipSurface).border(1.dp, Color.White.copy(alpha = 0.07f), shape)
            .clickable(onClickLabel = tr(R.string.moment_header_open_your_strap), onClick = onClick)
            .semantics { contentDescription = chip.description }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(chipDotColor(chip.dot), CircleShape))
        if (chip.text.isNotEmpty()) {
            Spacer(Modifier.width(7.dp))
            Text(
                chip.text, maxLines = 1, softWrap = false,
                color = if (chip.dot == StrapChip.Dot.Warn) MetricColors.warn else Color.White,
                style = GlowType.secondary,
            )
        }
    }
}

private fun chipDotColor(dot: StrapChip.Dot): Color = when (dot) {
    StrapChip.Dot.Fine -> MetricColors.good
    StrapChip.Dot.Low -> MetricColors.battery(StrapChip.LOW_BATTERY)
    StrapChip.Dot.Charging -> MetricColors.good
    StrapChip.Dot.Warn -> MetricColors.warn
    StrapChip.Dot.Quiet -> Color.White.copy(alpha = 0.3f)
}

private val Avatar = Color(0xFF1C1C1E)
private val ChipSurface = Color(0xFF16161A)
private val PeekWidth = 34.dp
