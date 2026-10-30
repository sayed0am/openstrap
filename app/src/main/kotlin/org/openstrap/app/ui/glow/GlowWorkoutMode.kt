package org.openstrap.app.ui.glow

import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import org.openstrap.app.ui.components.PinnedFade
import org.openstrap.app.ui.components.pinnedFade
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.min
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.WorkoutSuggestions
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.components.GlassAlertDialog
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.workout.SportIcons
import org.openstrap.app.ui.workout.ZONE_COLORS
import androidx.compose.ui.semantics.onClick
import org.openstrap.app.ui.workout.SummaryCopy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* ---------------------------------------------- Shared ----------------------------------------------- */

/** A paused workout, or one with no reading yet, is a cool grey rather than a zone. */
internal val PausedColor = Color(0xFF9AA0B4)

/** The Control surface (spec 2.2): round buttons, chips, pills, tiles and fields on the workout pages. */
internal val Raised = Color(0xFF1F1F22)

/** A button's label: Body (spec 2.3), a step heavier so it reads as the thing to press. */
private val ButtonText = GlowType.body.copy(fontWeight = FontWeight.SemiBold)

/** Body in Medium, for pills, chips and short live lines. */
internal val MediumBody = GlowType.body.copy(fontWeight = FontWeight.Medium)

/** The colour a workout takes: its live zone's, or [PausedColor] with no reading. */
internal fun zoneColor(bpm: Int?, maxHr: Int): Color = WorkoutRecorder.liveZone(bpm, maxHr)?.let { ZONE_COLORS[it] } ?: PausedColor

/** The colour the whole app takes while [r] records. */
fun workoutColor(r: WorkoutState.Recording, liveHeartRate: Int?, connected: Boolean): Color =
    if (r.paused) PausedColor else zoneColor(liveHeartRate.takeIf { connected }, r.maxHeartRate)

/** "12:34", or "1:02:03" past the hour. */
internal fun workoutClock(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)

internal fun zoneRange(zone: Int, maxHr: Int) =
    if (zone == 0) "under ${60 * maxHr / 100} bpm"
    else "${(50 + zone * 10) * maxHr / 100} to ${if (zone == Workouts.ZONES - 1) maxHr else (60 + zone * 10) * maxHr / 100 - 1} bpm"

/** Active time of the workout, ticking once a second while it records. */
@Composable
internal fun activeSeconds(r: WorkoutState.Recording): Long = produceState(WorkoutRecorder.activeSeconds(r.workout, System.currentTimeMillis() / 1000), r.workout) {
    while (true) {
        value = WorkoutRecorder.activeSeconds(r.workout, System.currentTimeMillis() / 1000)
        if (r.paused) break
        delay(1000 - System.currentTimeMillis() % 1000)
    }
}.value

/**
 * A target's colour: the top zone it aims for, a calm blue for a time, Calories' gold for kcal, Steps' lime for a
 * distance, white for none.
 */
internal fun targetColor(t: WorkoutTarget?): Color = when (t) {
    is WorkoutTarget.Zones -> ZONE_COLORS[t.high]
    is WorkoutTarget.Time -> ZONE_COLORS[0]
    is WorkoutTarget.Kcal -> MetricColors.calories
    is WorkoutTarget.Distance -> MetricColors.steps
    null -> Color.White
}

internal fun targetLabel(t: WorkoutTarget?): String = when (t) {
    is WorkoutTarget.Zones -> t.label
    is WorkoutTarget.Time -> t.label
    is WorkoutTarget.Kcal -> t.label
    is WorkoutTarget.Distance -> t.label
    null -> "None"
}

/** A full-width pill button, 56 dp like Start and Done (spec 6.3). */
@Composable
internal fun WorkoutButton(
    label: String, background: Color, content: Color, onClick: () -> Unit,
    modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, height: Dp = 56.dp,
) {
    Row(
        modifier.fillMaxWidth().height(height).shadow(28.dp, RoundedCornerShape(50), ambientColor = background, spotColor = background)
            .clip(RoundedCornerShape(50)).background(background).pressClickable(onClick, RoundedCornerShape(50)),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = content, style = ButtonText, maxLines = 1)
    }
}

/**
 * A workout screen: black, with the workout's colour glowing softly from the top, scrolling if it must but filling
 * the height so the controls sit at the bottom. [pinned] (Start, Done) stays on the bottom edge while the rest
 * scrolls under it, behind the pinned buttons' fade ([pinnedFade], spec 6.3), and the content ends clear of it.
 */
@Composable
internal fun WorkoutPage(
    glow: Color, contentPadding: PaddingValues, scroll: Boolean = true,
    pinned: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val width = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        var pinnedHeight by remember { mutableStateOf(0.dp) }
        Box(
            Modifier.fillMaxWidth().height(420.dp).background(
                Brush.radialGradient(listOf(glow.copy(alpha = 0.22f), Color.Transparent), center = Offset(width / 2f, 0f), radius = width * 0.9f),
            ),
        )
        // On a wide screen the page keeps to one centred column (spec 2.4 Unfolded); the glow stays the screen's.
        val side = GlowLayout.pageSide(maxWidth, LocalPageColumn.current, GlowLayout.Gutter)
        // The content ends [PinnedFade.Ramp] above the pinned buttons (their box already counts the bottom padding),
        // or 16 dp above the bottom padding without them.
        val padTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + contentPadding.calculateTopPadding() + 8.dp
        val padBottom = contentPadding.calculateBottomPadding()
        val end = if (pinned != null) (PinnedFade.listClearance(pinnedHeight) - padBottom).coerceAtLeast(16.dp) else 16.dp
        // The live screen fits the screen instead (scroll = false): its controls never move.
        Column(
            Modifier.fillMaxSize().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).statusBarsPadding()
                .padding(contentPadding).padding(start = side, end = side, top = 8.dp, bottom = end)
                // At least the screen's height, so the controls sit at the bottom without the page scrolling.
                .then(if (scroll) Modifier.heightIn(min = (maxHeight - padTop - padBottom - end).coerceAtLeast(0.dp)) else Modifier),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        // Black from the bottom of the screen up to the buttons, fading out above them.
        if (pinned != null) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().pinnedFade()
                .padding(contentPadding).padding(start = side, end = side, bottom = 16.dp)
                .onSizeChanged { pinnedHeight = with(density) { it.height.toDp() } },
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = pinned,
        )
    }
}

/** A dark pill: a dot and a label, optionally tappable with a caret. */
@Composable
internal fun Pill(label: String, dot: Color?, onClick: (() -> Unit)? = null, caret: Boolean = false) {
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(Raised)
            .let { if (onClick != null) it.pressClickable(onClick, RoundedCornerShape(50)) else it }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = Color.White, style = GlowType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        if (caret) {
            Spacer(Modifier.width(6.dp))
            Icon(AppIcons.CaretDown, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/* ----------------------------------------- Activity tab entry ---------------------------------------- */

/** A sport someone has done, for the start card: how often and when last. */
data class SportUse(val sportType: Int, val count: Int, val lastStart: Long?)

/** The 3 most-used sports across both kinds of workout, else three sensible defaults. */
fun topSports(workouts: List<WorkoutAnalysis>, n: Int = 3): List<SportUse> {
    // The strap's own "Activity" records aren't a sport to offer.
    val used = workouts.filter { it.workout.sportType != Workouts.GENERIC_ACTIVITY }.groupBy { it.workout.sportType }
        .map { (type, ws) -> SportUse(type, ws.size, ws.maxOf { it.workout.start }) }
        .sortedWith(compareByDescending<SportUse> { it.count }.thenByDescending { it.lastStart })
        .take(n)
    return used + Workouts.DEFAULT_SPORTS.filter { d -> used.none { it.sportType == d } }.take(n - used.size).map { SportUse(it, 0, null) }
}

/**
 * Where an offer or a suggestion sits: a quiet panel, or on Today a strip lit in heart red from [edge]'s side, so the
 * two never look alike when both show.
 */
@Composable
private fun OfferBox(edge: EdgeKind?, content: @Composable ColumnScope.() -> Unit) {
    if (edge == null) QuietPanel(content = content)
    else EdgeCard(MetricColors.heart, Modifier.fillMaxWidth(), kind = edge) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** "Looks like you worked out": a stretch nobody started, offered for saving. [edge] draws it as a Today strip. */
@Composable
internal fun SuggestionPanel(
    s: org.openstrap.app.data.WorkoutSuggestions.Suggestion, onSave: () -> Unit, onDismiss: () -> Unit, edge: EdgeKind? = null,
) {
    OfferBox(edge) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(AppIcons.Heartbeat, null, tint = ZONE_COLORS[s.zone], modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Looks like you worked out", color = Color.White, style = GlowType.cardTitle, maxLines = 1)
                val day = dayName(Instant.ofEpochSecond(s.start).atZone(ZoneId.systemDefault()).toLocalDate())
                Note("$day ${clock(s.start)}–${clock(s.end)}, mostly zone ${s.zone + 1}, ${s.averageHeartRate} bpm on average. Nobody pressed Start.")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { WorkoutButton("Save as workout", Color.White, Color.Black, onSave, height = 48.dp) }
            Box(Modifier.weight(1f)) { WorkoutButton("Not a workout", Raised, Color.White, onDismiss, height = 48.dp) }
        }
    }
}

/**
 * "Looks like a workout": an effort still under way that nobody started, offered for recording from its start.
 * [edge] draws it as a Today strip.
 */
@Composable
internal fun OfferPanel(o: org.openstrap.app.data.AutoStart.Offer, onRecord: () -> Unit, onDecline: () -> Unit, edge: EdgeKind? = null) {
    OfferBox(edge) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(SportIcons.of(o.sportType), null, tint = MetricColors.heart, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Looks like a workout", color = Color.White, style = GlowType.cardTitle, maxLines = 1)
                Note("Your heart rate has been up since ${clock(o.start)}, ${o.averageHeartRate} bpm on average. Record a ${Workouts.sportName(o.sportType).lowercase()} from ${clock(o.start)}?")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { WorkoutButton("Record", MetricColors.heart, Color.Black, onRecord, height = 48.dp) }
            Box(Modifier.weight(1f)) { WorkoutButton("Not now", Raised, Color.White, onDecline, height = 48.dp) }
        }
    }
}

/**
 * Adds a workout nobody started, after the fact: the day's heart rate with two handles to drag to where it began
 * and ended (first set on the day's likeliest stretch), then the sport. Built from the strap's stored minutes.
 */
@Composable
fun GlowAddWorkoutScreen(
    date: LocalDate,
    minuteHr: List<Pair<Long, Int>>?,
    maxHr: Int,
    contentPadding: PaddingValues,
    onSave: (from: Long, to: Long, sportType: Int) -> Unit,
    onBack: () -> Unit,
    recentSports: List<Int> = emptyList(),
    sides: Map<Int, Boolean> = emptyMap(),
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val series = minuteHr.orEmpty()
    val first = series.firstOrNull()?.first ?: 0L
    val last = (series.lastOrNull()?.first ?: 0L) + 60
    val likely = remember(series) { WorkoutSuggestions.find(series, 60, maxHr, emptyList()).firstOrNull() }
    var from by rememberSaveable(series.size) { mutableStateOf(likely?.start ?: (last - 1800).coerceAtLeast(first)) }
    var to by rememberSaveable(series.size) { mutableStateOf(likely?.end ?: last) }
    if (picking) {
        SportPicker(-1, recentSports, contentPadding, onPick = { onSave(from, to, pickedType(it, sides)) }, onBack = { picking = false })
        return
    }
    BackHandler(onBack = onBack)
    WorkoutPage(Color.Transparent, contentPadding, pinned = { WorkoutButton("Choose the sport", Color.White, Color.Black, { picking = true }) }) {
        PageHeader("Add a workout", onBack, subtitle = dayName(date))
        if (minuteHr == null) {
            QuietPanel { Note("Loading…") }
            return@WorkoutPage
        }
        if (series.size < 2) {
            QuietPanel { Note("The strap hasn't sent heart rate for this day yet. Sync, then try again.") }
            return@WorkoutPage
        }
        QuietPanel {
            PanelTitle("When was it?", trailing = "${clock(from)} – ${clock(to)}")
            RangeChart(series, first, last, from, to, maxHr) { f, t -> from = f; to = t }
            Row(Modifier.fillMaxWidth()) {
                Note(clock(first))
                Spacer(Modifier.weight(1f))
                Note(clock(last))
            }
            Note("Drag the handles to where the workout began and ended. Its heart rate comes from the strap's minute records.")
        }
    }
}

/**
 * The sport type to save for a tile picked away from Ready, where there's no switch: a sport with two kinds takes the
 * side you last chose on Ready ([sides]), else its default (a pool for Swim). Any other type is itself.
 */
internal fun pickedType(type: Int, sides: Map<Int, Boolean>): Int =
    Workouts.sidesOf(type)?.let { s -> s.type(Workouts.indoorSide(type, sides) ?: false) } ?: type

/** Room either side of a range chart, so its handles sit whole inside it. */
private val HandleInset = 14.dp
/** The trim frame's sides, the handles; the strip is inset by as much so either side can reach its end. */
private val TrimSide = 12.dp

/** A day of minute heart rate in its zones' colours, with two handles marking a range; outside it dims. */
@Composable
private fun RangeChart(series: List<Pair<Long, Int>>, first: Long, last: Long, from: Long, to: Long, maxHr: Int, onChange: (Long, Long) -> Unit) {
    val lo = (series.minOfOrNull { it.second } ?: 60) - 5f
    val hi = (series.maxOfOrNull { it.second } ?: 180) + 5f
    var f by remember { mutableStateOf(from) }
    var t by remember { mutableStateOf(to) }
    f = from
    t = to
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).pointerInput(series) {
            var dragging = 0
            val inset = HandleInset.toPx()
            fun timeAt(x: Float) = (first + ((x - inset) / (size.width - 2 * inset) * (last - first)).toLong()).coerceIn(first, last)
            detectHorizontalDragGestures(
                onDragStart = { o ->
                    val at = timeAt(o.x)
                    dragging = if (kotlin.math.abs(at - f) <= kotlin.math.abs(at - t)) 0 else 1
                },
            ) { change, _ ->
                val at = timeAt(change.position.x) / 60 * 60
                if (dragging == 0) f = at.coerceAtMost(t - 60) else t = at.coerceAtLeast(f + 60)
                onChange(f, t)
            }
        },
    ) {
        val inset = HandleInset.toPx()
        fun x(time: Long) = inset + (time - first).toFloat() / (last - first) * (size.width - 2 * inset)
        fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
        series.zipWithNext().forEach { (a, b) ->
            if (b.first - a.first > 300) return@forEach
            val inside = a.first in f until t
            val color = ZONE_COLORS[WorkoutRecorder.liveZone(a.second, maxHr)!!].copy(alpha = if (inside) 1f else 0.25f)
            drawLine(color, Offset(x(a.first), y(a.second.toFloat())), Offset(x(b.first), y(b.second.toFloat())), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        listOf(f, t).forEach { time ->
            val hx = x(time)
            drawLine(Color.White, Offset(hx, 0f), Offset(hx, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(hx, size.height / 2))
        }
    }
}

/** "5.21" kilometres, or "820 m" under one. */
internal fun km(meters: Float): String = if (meters < 1000) "${meters.toInt()} m" else String.format(java.util.Locale.ROOT, "%.2f", meters / 1000)

/* ---------------------------------------------- Summary ---------------------------------------------- */

private fun clock(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/** Done right after the end, and Delete workout behind a question. */
@Composable
internal fun ColumnScope.SummaryActions(justEnded: Boolean, onDone: () -> Unit, onDelete: () -> Unit) {
    if (justEnded) WorkoutButton("Done", Color.White, Color.Black, onDone)
    DeleteWorkout(PHONE_DELETE, onDelete, Modifier.align(Alignment.CenterHorizontally))
}

/** What deleting a phone workout does. */
internal const val PHONE_DELETE = "Its heart rate and times are removed from OpenStrap. The strap's own history is kept."

/** "Delete workout" in [DestructiveInk], asking first; [what] says what deleting it does. */
@Composable
internal fun DeleteWorkout(what: String, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var confirmDelete by remember { mutableStateOf(false) }
    Text(
        "Delete this workout", color = DestructiveInk, style = GlowType.body,
        modifier = modifier.clip(RoundedCornerShape(50))
            .pressClickable({ confirmDelete = true }, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
    )
    if (confirmDelete) {
        GlassAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this workout?") },
            text = { Text(what) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * What the app did to the end, with undo (an early end it proposed, or a trim), then "Trim start and end", which opens
 * the trimmer in place.
 */
@Composable
internal fun TrimSection(summary: PhoneSummary, onTrim: (from: Long, to: Long) -> Unit, onUndoTrim: () -> Unit) {
    var trimming by rememberSaveable { mutableStateOf(false) }
    val a = summary.analysis
    val w = a.workout
    // What the app did, with undo: an early end it proposed, or a trim.
    a.recordedEnd?.let { recorded ->
        val cut = recorded - a.end
        val left = if (cut < 60) "$cut s" else "${cut / 60} min"
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (summary.endReason == AppWorkout.END_CAR && a.shownStart == w.start) "Ended at ${clock(a.end)}. $left of driving were left out."
                else if (summary.endReason == AppWorkout.END_AUTO && a.shownStart == w.start) "Ended at ${clock(a.end)}, when you'd stopped. $left after that were left out."
                else "Trimmed to ${clock(a.shownStart)}–${clock(a.end)}. The rest is kept.",
                color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary, modifier = Modifier.weight(1f),
            )
            Text(
                "Undo", color = Color.White, style = MediumBody,
                modifier = Modifier.clip(RoundedCornerShape(50)).pressClickable(onUndoTrim, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    } ?: if (a.shownStart != w.start) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Trimmed to start at ${clock(a.shownStart)}. The rest is kept.", color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary, modifier = Modifier.weight(1f))
            Text(
                "Undo", color = Color.White, style = MediumBody,
                modifier = Modifier.clip(RoundedCornerShape(50)).pressClickable(onUndoTrim, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    } else Unit
    if (trimming) {
        TrimPanel(summary, onSave = { from, to -> onTrim(from, to); trimming = false }, onCancel = { trimming = false })
    } else {
        Row(
            Modifier.fillMaxWidth().glass(GlassRowShape, { trimming = true }).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Trim start and end", color = Color.White, style = GlowType.body, modifier = Modifier.weight(1f))
            TapCaret(ROW_CARET_ALPHA)
        }
    }
}

/**
 * Moves the start and end on the whole recording, as WHOOP allows: drag either handle along the heart rate; what
 * falls outside dims. Saving changes only what is shown; nothing recorded is deleted.
 */
@Composable
internal fun TrimPanel(summary: PhoneSummary, onSave: (Long, Long) -> Unit, onCancel: () -> Unit) {
    val a = summary.analysis
    val first = summary.recordedStart
    val last = summary.recordedEnd.coerceAtLeast(first + 60)
    var from by remember(summary) { mutableStateOf(a.shownStart) }
    var to by remember(summary) { mutableStateOf(a.end) }
    val step = 10L
    val points = remember(summary) {
        val n = ((last - first) / step + 1).toInt().coerceAtLeast(2)
        val sums = FloatArray(n)
        val counts = IntArray(n)
        summary.recorded.forEach { s ->
            val i = ((s.timestamp - first) / step).toInt()
            if (i in 0 until n) { sums[i] += s.bpm.toFloat(); counts[i]++ }
        }
        List(n) { if (counts[it] == 0) null else sums[it] / counts[it] }
    }
    QuietPanel {
        PanelTitle("Trim", trailing = SummaryCopy.trimHeader(clock(from), clock(to), to - from))
        val known = points.filterNotNull()
        val lo = (known.minOrNull() ?: 60f) - 10f
        val hi = (known.maxOrNull() ?: 180f) + 5f
        // A video trimmer: the workout as thin solid bars in their zone colours, inside one rounded frame whose
        // thicker sides are the handles (Snapchat's, Polarsteps'); what's left out fades back. Drag either side in.
        Canvas(
            Modifier.fillMaxWidth().height(64.dp).pointerInput(summary) {
                var dragging = 0
                val inset = TrimSide.toPx()
                fun timeAt(x: Float) = (first + ((x - inset) / (size.width - 2 * inset) * (last - first)).toLong()).coerceIn(first, last)
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val t = timeAt(o.x)
                        dragging = if (kotlin.math.abs(t - from) <= kotlin.math.abs(t - to)) 0 else 1
                    },
                ) { change, _ ->
                    val t = timeAt(change.position.x)
                    if (dragging == 0) from = t.coerceAtMost(to - 60) else to = t.coerceAtLeast(from + 60)
                }
            },
        ) {
            val inset = TrimSide.toPx()
            val w = size.width - 2 * inset
            val h = size.height
            val edge = 3.dp.toPx()
            fun x(t: Long) = inset + (t - first).toFloat() / (last - first) * w
            drawRoundRect(Raised, Offset(inset, 0f), Size(w, h), CornerRadius(8.dp.toPx()))
            // Solid rounded bars, as tall as the heart rate in that stretch (dots are only for numbers, spec 2.7). A
            // stretch with no reading is left empty.
            val pitch = 5.dp.toPx()
            val bar = 3.dp.toPx()
            val pad = edge + 5.dp.toPx()
            val cols = (w / pitch).toInt().coerceAtLeast(1)
            val fx = x(from)
            val tx = x(to)
            for (c in 0 until cols) {
                val cx = inset + (c + 0.5f) * (w / cols)
                val i = ((c + 0.5f) / cols * points.size).toInt().coerceIn(0, points.size - 1)
                val v = points[i] ?: continue
                val zone = WorkoutRecorder.liveZone(v.toInt(), a.maxHeartRate) ?: continue
                val barH = ((v - lo) / (hi - lo)).coerceIn(0.1f, 1f) * (h - 2 * pad)
                val color = ZONE_COLORS[zone]
                drawRoundRect(
                    if (cx in fx..tx) color else color.copy(alpha = color.alpha * 0.3f),
                    Offset(cx - bar / 2, h - pad - barH), Size(bar, barH), CornerRadius(bar / 2),
                )
            }
            // The frame: one rounded outline, its sides thick enough to grab, each with a short grip.
            val frame = androidx.compose.ui.graphics.Path().apply {
                fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                addRoundRect(androidx.compose.ui.geometry.RoundRect(fx - inset, 0f, tx + inset, h, CornerRadius(10.dp.toPx())))
                addRoundRect(androidx.compose.ui.geometry.RoundRect(fx, edge, tx, h - edge, CornerRadius(4.dp.toPx())))
            }
            drawPath(frame, Color.White)
            val grip = 2.dp.toPx()
            listOf(fx - inset / 2, tx + inset / 2).forEach { gx ->
                drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(gx - grip / 2, h / 2 - 8.dp.toPx()), Size(grip, 16.dp.toPx()), CornerRadius(grip / 2))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Note(clock(first))
            Spacer(Modifier.weight(1f))
            Note(clock(last))
        }
        Note("Drag the handles to where the workout really started and ended. Nothing recorded is deleted.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { WorkoutButton("Cancel", Raised, Color.White, onCancel) }
            Box(Modifier.weight(1f)) { WorkoutButton("Save", Color.White, Color.Black, { onSave(from, to) }) }
        }
    }
}

/**
 * A screen over the app that comes and goes like every other screen ([org.openstrap.app.ui.components.PushContent]):
 * in from the right a quarter of the width with a fade, out the same way, kept on screen until it has gone. [cut]
 * drops it at once instead, for when what replaces it (a workout starting) shouldn't watch it leave.
 */
@Composable
fun <T : Any> PushOverlay(value: T?, cut: Boolean, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != null) last = value }
    if (cut) return
    val move = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(
        org.openstrap.app.ui.components.Motion.SWIFT, easing = org.openstrap.app.ui.components.Motion.easing,
    )
    androidx.compose.animation.AnimatedVisibility(
        value != null,
        enter = androidx.compose.animation.slideInHorizontally(move) { it / 4 } +
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(org.openstrap.app.ui.components.Motion.SWIFT)),
        exit = androidx.compose.animation.slideOutHorizontally(move) { it / 4 } +
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(org.openstrap.app.ui.components.Motion.QUICK)),
    ) { (value ?: last)?.let { content(it) } }
}
