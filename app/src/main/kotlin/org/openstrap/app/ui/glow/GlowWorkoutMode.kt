package org.openstrap.app.ui.glow

import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.WorkoutColors
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.WorkoutSuggestions
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.SportIcons
import org.openstrap.app.ui.workout.ZONE_COLORS
import androidx.compose.ui.semantics.onClick
import org.openstrap.app.ui.workout.SummaryCopy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.openstrap.app.ui.glow.draw.EdgeKind
import org.openstrap.app.ui.theme.ControlSurface
import org.openstrap.app.data.HeartZones
import org.openstrap.app.ui.dashboard.LocalHeartZones
import androidx.compose.runtime.ReadOnlyComposable

/* ---------------------------------------------- Shared ----------------------------------------------- */

/** A paused workout, or one with no reading yet, is a cool grey rather than a zone, the notification's grey. */
internal val PausedColor = Color(WorkoutColors.PAUSED_GREY)

/** The Control surface (spec 2.2): round buttons, chips, pills, tiles and fields on the workout pages. */
internal val Raised = ControlSurface

/** A button's label: Body (spec 2.3), a step heavier so it reads as the thing to press. */
private val ButtonText = GlowType.body.copy(fontWeight = FontWeight.SemiBold)

/** Body in Medium, for pills, chips and short live lines. */
internal val MediumBody = GlowType.body.copy(fontWeight = FontWeight.Medium)

/** The colour a workout takes: its live zone's in your [zones], or [PausedColor] with no reading. */
internal fun zoneColor(bpm: Int?, maxHr: Int, zones: HeartZones): Color = WorkoutRecorder.liveZone(bpm, maxHr, zones)?.let { ZONE_COLORS[it] } ?: PausedColor

/** The colour the whole app takes while [r] records. */
fun workoutColor(r: WorkoutState.Recording, liveHeartRate: Int?, connected: Boolean, zones: HeartZones): Color =
    if (r.paused) PausedColor else zoneColor(liveHeartRate.takeIf { connected }, r.maxHeartRate, zones)

/** "12:34", or "1:02:03" past the hour, in the phone's digits. Text drawn in dots passes [Units.DOT_LOCALE]. */
internal fun workoutClock(seconds: Long, locale: java.util.Locale = java.util.Locale.getDefault()): String = Units.workoutClock(seconds, locale)

/** [workoutClock] for the dot font, whose digits are ASCII only. */
internal fun dotClock(seconds: Long): String = Units.workoutClock(seconds, Units.DOT_LOCALE)

/** A live zone's heart rates in your zones: "under 115 bpm" for zone 1 (anything slower counts), else "134 to 152 bpm". */
// From the root, so it reads again when the zones are moved on the Heart rate zones page.
@Composable
@ReadOnlyComposable
internal fun zoneRange(zone: Int, maxHr: Int): String {
    val zones = LocalHeartZones.current
    if (zone == 0) return "under ${zones.startBpm(1, maxHr)} bpm"
    return Ranges.of("${zones.startBpm(zone, maxHr)}", "${zones.endBpm(zone, maxHr) ?: maxHr}") + " bpm"
}

/** Active time of the workout, ticking once a second while it records. */
@Composable
internal fun activeSeconds(r: WorkoutState.Recording): Long = activeSecondsState(r).value

/**
 * [activeSeconds] as state, for a live screen to hand on as `{ active.value }`: only what shows the time reads it, so
 * the screen itself isn't composed again each second.
 */
@Composable
internal fun activeSecondsState(r: WorkoutState.Recording): androidx.compose.runtime.State<Long> = produceState(WorkoutRecorder.activeSeconds(r.workout, System.currentTimeMillis() / 1000), r.workout) {
    while (true) {
        value = WorkoutRecorder.activeSeconds(r.workout, System.currentTimeMillis() / 1000)
        if (r.paused) break
        delay(1000 - System.currentTimeMillis() % 1000)
    }
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
    /** Painted over the whole screen in place of the top glow (HIIT's phase light). */
    backdrop: Brush? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val width = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        var pinnedHeight by remember { mutableStateOf(0.dp) }
        // No wider than the box is tall: on a wide screen (unfolded) a wider glow would be cut off in a straight line.
        val glowHeight = 420.dp
        val radius = minOf(width * 0.9f, with(density) { glowHeight.toPx() })
        if (backdrop != null) Box(Modifier.fillMaxSize().background(backdrop)) else Box(
            Modifier.fillMaxWidth().height(glowHeight).background(
                Brush.radialGradient(listOf(glow.copy(alpha = 0.22f), Color.Transparent), center = Offset(width / 2f, 0f), radius = radius),
            ),
        )
        // On a wide screen the page keeps to one centred column (spec 2.4 Unfolded); the glow stays the screen's.
        val side = GlowLayout.pageSide(maxWidth, LocalPageColumn.current, GlowLayout.Gutter)
        // The content ends [PinnedFade.Ramp] above the pinned buttons (their box already counts the bottom padding),
        // or 16 dp above the bottom padding without them.
        val padTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + contentPadding.calculateTopPadding() + 8.dp
        val padBottom = contentPadding.calculateBottomPadding()
        val end = if (pinned != null) (PinnedFade.listClearance(pinnedHeight) - padBottom).coerceAtLeast(16.dp) else 16.dp
        // The live screen fits the screen instead (scroll = false): its controls never move. A scrolling page ends at
        // the keyboard, so what's typed can scroll above it.
        Column(
            Modifier.fillMaxSize().then(if (scroll) Modifier.imePadding().verticalScroll(rememberScrollState()) else Modifier).statusBarsPadding()
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

/* ----------------------------------------- Activity tab entry ---------------------------------------- */

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
                PageNote(
                    "$day ${Ranges.times(s.start, s.end)}, mostly zone ${s.zone + 1}, ${s.averageHeartRate} bpm on average. " +
                        "Nobody pressed Start.",
                )
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
                PageNote(
                    "Your heart rate has been up since ${clock(o.start)}, ${o.averageHeartRate} bpm on average. " +
                        "Record a ${Workouts.sportName(o.sportType).lowercase()} from ${clock(o.start)}?",
                )
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
    val heartZones = LocalHeartZones.current
    var picking by rememberSaveable { mutableStateOf(false) }
    val series = minuteHr.orEmpty()
    val first = series.firstOrNull()?.first ?: 0L
    val last = (series.lastOrNull()?.first ?: 0L) + 60
    val likely = remember(series, heartZones) { WorkoutSuggestions.find(series, 60, maxHr, emptyList(), heartZones).firstOrNull() }
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
            QuietPanel { PageNote("Loading…") }
            return@WorkoutPage
        }
        if (series.size < 2) {
            QuietPanel { PageNote("The strap hasn't sent heart rate for this day yet. Sync, then try again.") }
            return@WorkoutPage
        }
        QuietPanel {
            GlowHeader("When was it?", trailing = Ranges.times(from, to))
            RangeChart(series, first, last, from, to, maxHr) { f, t -> from = f; to = t }
            Row(Modifier.fillMaxWidth()) {
                PageNote(clock(first))
                Spacer(Modifier.weight(1f))
                PageNote(clock(last))
            }
            PageNote("Drag the handles to where the workout began and ended. Its heart rate comes from the strap's minute records.")
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

/** A day of minute heart rate in its zones' colours, with two handles marking a range; outside it dims. */
@Composable
private fun RangeChart(series: List<Pair<Long, Int>>, first: Long, last: Long, from: Long, to: Long, maxHr: Int, onChange: (Long, Long) -> Unit) {
    val heartZones = LocalHeartZones.current
    val lo = (series.minOfOrNull { it.second } ?: 60) - 5f
    val hi = (series.maxOfOrNull { it.second } ?: 180) + 5f
    // The drag reads the range as it stands when it starts and keeps its own copy while it moves, so nothing is
    // written back during composition (audit K6).
    val range by rememberUpdatedState(from to to)
    val change by rememberUpdatedState(onChange)
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).pointerInput(series, first, last) {
            var dragging = 0
            var f = 0L
            var t = 0L
            val inset = HandleInset.toPx()
            fun timeAt(x: Float) = (first + ((x - inset) / (size.width - 2 * inset) * (last - first)).toLong()).coerceIn(first, last)
            detectHorizontalDragGestures(
                onDragStart = { o ->
                    f = range.first
                    t = range.second
                    val at = timeAt(o.x)
                    dragging = if (kotlin.math.abs(at - f) <= kotlin.math.abs(at - t)) 0 else 1
                },
            ) { c, _ ->
                val at = timeAt(c.position.x) / 60 * 60
                if (dragging == 0) f = at.coerceAtMost(t - 60) else t = at.coerceAtLeast(f + 60)
                change(f, t)
            }
        },
    ) {
        val f = from
        val t = to
        val inset = HandleInset.toPx()
        fun x(time: Long) = inset + (time - first).toFloat() / (last - first) * (size.width - 2 * inset)
        fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
        series.zipWithNext().forEach { (a, b) ->
            if (b.first - a.first > 300) return@forEach
            val inside = a.first in f until t
            val color = ZONE_COLORS[WorkoutRecorder.liveZoneOf(a.second, maxHr, heartZones)].copy(alpha = if (inside) 1f else 0.25f)
            drawLine(color, Offset(x(a.first), y(a.second.toFloat())), Offset(x(b.first), y(b.second.toFloat())), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        listOf(f, t).forEach { time ->
            val hx = x(time)
            drawLine(Color.White, Offset(hx, 0f), Offset(hx, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(hx, size.height / 2))
        }
    }
}

/* ---------------------------------------------- Summary ---------------------------------------------- */

/** Done right after the end, and Delete workout behind a question. */
@Composable
internal fun ColumnScope.SummaryActions(justEnded: Boolean, onDone: () -> Unit, onDelete: () -> Unit) {
    if (justEnded) WorkoutButton("Done", Color.White, Color.Black, onDone)
    DeleteWorkout(PHONE_DELETE, onDelete, Modifier.align(Alignment.CenterHorizontally))
}

/** What deleting a phone workout does. */
internal const val PHONE_DELETE = "Its heart rate and times are removed from OpenStrap. The strap's own history is kept."

/** "Delete this workout" as a warn coloured button with a trash icon, asking first; [what] says what deleting it does. */
@Composable
internal fun DeleteWorkout(what: String, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    PageButton("Delete this workout", { confirmDelete = true }, modifier.fillMaxWidth(), color = MetricColors.warn, icon = AppIcons.Trash)
    if (confirmDelete) {
        PageConfirm("Delete this workout?", what, "Delete", { confirmDelete = false; onDelete() }, { confirmDelete = false })
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
                else "Trimmed, now from ${Ranges.times(a.shownStart, a.end)}. The rest is kept.",
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
    val heartZones = LocalHeartZones.current
    val a = summary.analysis
    val first = summary.recordedStart
    val last = summary.recordedEnd.coerceAtLeast(first + 60)
    // The handles outlive a rotation, a fold and process death while the panel stays open (audit K7). They start again
    // only when the workout or its shown range changes.
    var from by rememberSaveable(a.workout.start, a.shownStart, a.end) { mutableLongStateOf(a.shownStart) }
    var to by rememberSaveable(a.workout.start, a.shownStart, a.end) { mutableLongStateOf(a.end) }
    val step = 10L
    val points = remember(summary) {
        val n = ((last - first) / step + 1).toInt().coerceAtLeast(2)
        org.openstrap.app.data.Summaries.bucketMeans(summary.recorded, n, { ((it.timestamp - first) / step).toInt() }, { it.bpm.toFloat() })
    }
    QuietPanel {
        GlowHeader("Trim", trailing = SummaryCopy.trimHeader(clock(from), clock(to), to - from))
        // Recorded is all it can reach for now: the core and the reach are one, so the bar only trims.
        TrimBar(
            points, core = first..last, reach = first..last, from = from, to = to,
            colorOf = { v -> WorkoutRecorder.liveZone(v.toInt(), a.maxHeartRate, heartZones)?.let { ZONE_COLORS[it] } },
            onChange = { f, t -> from = f; to = t },
        )
        PageNote("Drag the handles to where the workout really started and ended. Nothing recorded is deleted.")
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
