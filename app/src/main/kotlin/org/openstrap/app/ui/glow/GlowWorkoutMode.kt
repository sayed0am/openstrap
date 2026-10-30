package org.openstrap.app.ui.glow

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.openstrap.app.device.WorkoutLocation
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.WorkoutSuggestions
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Route
import org.openstrap.app.data.Strength
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.wrapContentHeight
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.components.GlassAlertDialog
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.components.HapticEvent
import org.openstrap.app.ui.components.rememberHaptics
import org.openstrap.app.ui.dashboard.Info
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.workout.SportIcons
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.workout.LiveView
import org.openstrap.app.ui.workout.SideFigure
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import org.openstrap.app.ui.workout.WorkoutCopy
import org.openstrap.app.ui.workout.SummaryCopy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.openstrap.app.R
import org.openstrap.app.tr

/* ---------------------------------------------- Shared ----------------------------------------------- */

/** A paused workout, or one with no reading yet, is a cool grey rather than a zone. */
internal val PausedColor = Color(0xFF9AA0B4)

/** The Control surface (spec 2.2): round buttons, chips, pills, tiles and fields on the workout pages. */
internal val Raised = Color(0xFF1F1F22)

/** A button's label: Body (spec 2.3), a step heavier so it reads as the thing to press. */
private val ButtonText = GlowType.body.copy(fontWeight = FontWeight.SemiBold)

/** Body in Medium, for pills, chips and short live lines. */
private val MediumBody = GlowType.body.copy(fontWeight = FontWeight.Medium)

/**
 * The live stats row's figures, unchanged from before spec 6.3 ("Stats row unchanged"): the moving time large in the
 * middle and the two figures either side, in Display Light. The only two sizes on the workout pages no role has; a
 * live clock read at arm's length isn't one of spec 2.3's.
 */
private val LiveClock = GlowType.sentence.copy(fontSize = 46.sp, lineHeight = 50.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum")
private val LiveFigure = GlowType.sentence.copy(fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum")

/** The colour a workout takes: its live zone's, or [PausedColor] with no reading. */
internal fun zoneColor(bpm: Int?, maxHr: Int): Color = WorkoutRecorder.liveZone(bpm, maxHr)?.let { ZONE_COLORS[it] } ?: PausedColor

/** The colour the whole app takes while [r] records. */
fun workoutColor(r: WorkoutState.Recording, liveHeartRate: Int?, connected: Boolean): Color =
    if (r.paused) PausedColor else zoneColor(liveHeartRate.takeIf { connected }, r.maxHeartRate)

/** "12:34", or "1:02:03" past the hour. */
internal fun workoutClock(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)

internal fun zoneRange(zone: Int, maxHr: Int) =
    if (zone == 0) tr(R.string.common_under_bpm, 60 * maxHr / 100)
    else tr(R.string.glow_workout_mode_bpm, (50 + zone * 10) * maxHr / 100, if (zone == Workouts.ZONES - 1) maxHr else (60 + zone * 10) * maxHr / 100 - 1)

/** Active time of the workout, ticking once a second while it records. */
@Composable
internal fun activeSeconds(r: WorkoutState.Recording): Long = produceState(WorkoutRecorder.activeSeconds(r.workout, System.currentTimeMillis() / 1000), r.workout) {
    while (true) {
        value = WorkoutRecorder.activeSeconds(r.workout, System.currentTimeMillis() / 1000)
        if (r.paused) break
        delay(1000 - System.currentTimeMillis() % 1000)
    }
}.value

/** A target's colour: the top zone it aims for, a calm blue for a time, Calories' gold for kcal, white for none. */
internal fun targetColor(t: WorkoutTarget?): Color = when (t) {
    is WorkoutTarget.Zones -> ZONE_COLORS[t.high]
    is WorkoutTarget.Time -> ZONE_COLORS[0]
    is WorkoutTarget.Kcal -> MetricColors.calories
    null -> Color.White
}

internal fun targetLabel(t: WorkoutTarget?): String = when (t) {
    is WorkoutTarget.Zones -> t.label
    is WorkoutTarget.Time -> t.label
    is WorkoutTarget.Kcal -> t.label
    null -> tr(R.string.common_none)
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
private fun Pill(label: String, dot: Color?, onClick: (() -> Unit)? = null, caret: Boolean = false) {
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
 * A sport's tile in the picker (spec 6.3): a 64 dp Control tile with 16 dp corners, the icon at 24 dp, the name in
 * Secondary under it. The chosen sport's tile is white with a black icon; a sport done only outdoors carries the
 * 12 dp location mark in its corner.
 */
@Composable
private fun SportTile(type: Int, name: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    // Tight corners and room underneath, so the press area never clips a label that fills the width.
    Column(modifier.pressClickable(onClick, RoundedCornerShape(8.dp)).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(SportTileSize).clip(GlowLayout.TileShape).background(if (selected) Color.White else Raised),
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (selected) Color.Black else Color.White
            Icon(SportIcons.of(type), null, tint = ink, modifier = Modifier.size(24.dp))
            if (type in Workouts.OUTDOOR_ONLY) Icon(
                SportIcons.Location, tr(R.string.glow_workout_mode_outdoors), tint = ink.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(12.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        // Two lines, so a long name in More sports' search isn't cut short.
        Text(
            name, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 2,
            textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
        )
    }
}

private val SportTileSize = 64.dp

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
                Text(tr(R.string.glow_workout_mode_looks_like_you_worked), color = Color.White, style = GlowType.cardTitle, maxLines = 1)
                val day = dayName(Instant.ofEpochSecond(s.start).atZone(ZoneId.systemDefault()).toLocalDate())
                Note(tr(R.string.glow_workout_mode_mostly_zone_bpm_on, day, clock(s.start), clock(s.end), s.zone + 1, s.averageHeartRate))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { WorkoutButton(tr(R.string.glow_workout_mode_save_as_workout), Color.White, Color.Black, onSave, height = 48.dp) }
            Box(Modifier.weight(1f)) { WorkoutButton(tr(R.string.common_not_a_workout), Raised, Color.White, onDismiss, height = 48.dp) }
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
                Text(tr(R.string.common_looks_like_a_workout), color = Color.White, style = GlowType.cardTitle, maxLines = 1)
                Note(tr(R.string.glow_workout_mode_your_heart_rate_has, clock(o.start), o.averageHeartRate, Workouts.sportName(o.sportType).lowercase(), clock(o.start)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { WorkoutButton(tr(R.string.common_record), MetricColors.heart, Color.Black, onRecord, height = 48.dp) }
            Box(Modifier.weight(1f)) { WorkoutButton(tr(R.string.common_not_now), Raised, Color.White, onDecline, height = 48.dp) }
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
    WorkoutPage(Color.Transparent, contentPadding, pinned = { WorkoutButton(tr(R.string.glow_workout_mode_choose_the_sport), Color.White, Color.Black, { picking = true }) }) {
        PageHeader(tr(R.string.glow_workout_mode_add_a_workout), onBack, subtitle = dayName(date))
        if (minuteHr == null) {
            QuietPanel { Note(tr(R.string.missed_loading)) }
            return@WorkoutPage
        }
        if (series.size < 2) {
            QuietPanel { Note(tr(R.string.glow_workout_mode_the_strap_hasn_t)) }
            return@WorkoutPage
        }
        QuietPanel {
            PanelTitle(tr(R.string.glow_workout_mode_when_was_it), trailing = "${clock(from)} – ${clock(to)}")
            RangeChart(series, first, last, from, to, maxHr) { f, t -> from = f; to = t }
            Row(Modifier.fillMaxWidth()) {
                Note(clock(first))
                Spacer(Modifier.weight(1f))
                Note(clock(last))
            }
            Note(tr(R.string.glow_workout_mode_drag_the_handles_to))
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

/** The sport list on its own, for saving a stretch nobody started. */
@Composable
fun GlowSportPickerScreen(
    contentPadding: PaddingValues, onPick: (Int) -> Unit, onBack: () -> Unit,
    recentSports: List<Int> = emptyList(), sides: Map<Int, Boolean> = emptyMap(),
) = SportPicker(-1, recentSports, contentPadding, { onPick(pickedType(it, sides)) }, onBack)

/* -------------------------------------------- Ready screen ------------------------------------------- */

/**
 * One screen, not a wizard (spec 6.3): close, the sport (tap to change), the Outdoor or Indoor switch for a sport with
 * both, the strap as the hero with its live heart rate, the settings as list rows, and Start pinned to the bottom.
 * Recording starts even when the strap is away; the strap's history fills in after it syncs.
 *
 * The switch picks the sport type saved ([Workouts.Sides]) and whether GPS is offered; it opens on the side you last
 * chose for the sport ([sides], saved through [onSide]). GPS is on for the outdoor side and sports done outdoors, with
 * its row to turn it off for this workout. While the phone's location is off or not allowed, the row says so with
 * Turn on ([onTurnOnLocation] asks for the permission), and Start works without GPS.
 */
@Composable
fun GlowWorkoutReadyScreen(
    sportType: Int,
    pickSport: Boolean,
    connection: ConnectionState,
    liveHeartRate: Int?,
    contentPadding: PaddingValues,
    onSport: (Int) -> Unit,
    onStart: (sportType: Int, askToEnd: Boolean, target: WorkoutTarget?, useGps: Boolean, keepRoute: Boolean, rest: Strength.Rest?, setsTarget: Int?) -> Unit,
    onBack: () -> Unit,
    recentSports: List<Int> = emptyList(),
    sides: Map<Int, Boolean> = emptyMap(),
    onSide: (sport: Int, indoor: Boolean) -> Unit = { _, _ -> },
    onTurnOnLocation: (then: () -> Unit) -> Unit = { it() },
    /** kcal targets are offered: there's a profile to work kcal out from. */
    kcalTargets: Boolean = false,
) {
    var picking by rememberSaveable { mutableStateOf(pickSport) }
    var askToEnd by rememberSaveable { mutableStateOf(true) }
    var targetCode by rememberSaveable { mutableStateOf<String?>(null) }
    val pair = Workouts.sidesOf(sportType)
    // The side picked on this screen; until then the one you last chose for the sport, or its default.
    var chosenIndoor by rememberSaveable(sportType) { mutableStateOf<Boolean?>(null) }
    val indoor = chosenIndoor ?: Workouts.indoorSide(sportType, sides)
    val type = pair?.type(indoor == true) ?: sportType
    val outdoor = Workouts.gpsOffered(sportType, indoor)
    // GPS is on wherever it's offered; turning it off holds for this workout only.
    var gpsOff by rememberSaveable(type, indoor) { mutableStateOf(false) }
    var keepRoute by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var locationOn by remember { mutableStateOf(locationAvailable(context)) }
    LifecycleResumeEffect(Unit) {
        locationOn = locationAvailable(context)
        onPauseOrDispose { }
    }
    val useGps = outdoor && !gpsOff && locationOn
    val strength = type in Strength.SPORTS
    var setsTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var restSeconds by rememberSaveable { mutableStateOf(Strength.Rest.DEFAULT.seconds) }
    var restBelow by rememberSaveable { mutableStateOf<Int?>(null) }
    // A kcal target picked before the profile went is dropped with its chip.
    val target = WorkoutTarget.parse(targetCode)?.takeIf { it in WorkoutTarget.offered(kcalTargets) }
    var info by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    if (picking) {
        SportPicker(type, recentSports, contentPadding, onPick = { onSport(it); picking = false }, onBack = { if (pickSport) onBack() else picking = false })
        return
    }
    BackHandler(onBack = onBack)
    // Start takes the target's colour, previewing the workout mode; plain white with none.
    val accent = targetColor(target)
    WorkoutPage(
        accent, contentPadding,
        pinned = {
            WorkoutButton(WorkoutCopy.startLabel(type), accent, Color.Black, {
                haptics.perform(HapticEvent.WorkoutStart)
                onStart(
                    type, askToEnd, target, useGps, useGps && keepRoute,
                    Strength.Rest(restSeconds, restBelow).takeIf { strength }, setsTarget.takeIf { strength },
                )
            }, icon = AppIcons.PlayFill)
        },
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Raised).pressClickable(onBack, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.X, tr(R.string.common_close), tint = Color.White, modifier = Modifier.size(18.dp)) }
            Box(Modifier.align(Alignment.Center)) { Pill(Workouts.shortName(type), null, onClick = { picking = true }, caret = true) }
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr(R.string.glow_workout_mode_ready_when_you_are), color = Color.White, style = GlowType.pageTitle, maxLines = 1)
            Text(tr(R.string.glow_workout_mode_recording_starts_when_you), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        // Outdoor or indoor is a switch, not a second sport.
        if (pair != null) SideSwitch(listOf(pair.outdoorName, pair.indoorName), if (indoor == true) 1 else 0) { i ->
            chosenIndoor = i == 1
            onSide(pair.outdoor, i == 1)
        }
        StrapHero(connection, liveHeartRate) { info = true }
        // GPS for the outdoor side and sports done outdoors only; indoors there's no row at all.
        if (outdoor) ListPanel {
            if (locationOn) {
                SettingRow(SportIcons.Location, tr(R.string.common_gps), WorkoutCopy.gpsNote(useGps, type), divider = false, onClick = { gpsOff = !gpsOff }) {
                    GlowSwitch(!gpsOff, { gpsOff = !it })
                }
                if (useGps) SettingRow(AppIcons.Route01, tr(R.string.common_keep_the_route), WorkoutCopy.routeNote(keepRoute), onClick = { keepRoute = !keepRoute }) {
                    GlowSwitch(keepRoute, { keepRoute = it })
                }
            } else {
                val turnOn = {
                    if (!WorkoutLocation.granted(context)) onTurnOnLocation { locationOn = locationAvailable(context) }
                    else context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                SettingRow(SportIcons.Location, WorkoutCopy.LOCATION_OFF, WorkoutCopy.LOCATION_OFF_NOTE, divider = false, onClick = turnOn) {
                    SmallPill(tr(R.string.glow_workout_mode_turn_on), turnOn)
                }
            }
        }
        // Strength counts sets and rest; the strap buzzes when rest is over, so the phone can stay in a pocket.
        if (strength) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipLabel(tr(R.string.common_sets))
            Chips(listOf(tr(R.string.common_open)) + listOf(3, 4, 5).map { tr(R.string.missed_n_sets, it) }, listOf(null, 3, 4, 5).indexOf(setsTarget), Color.White) { setsTarget = listOf(null, 3, 4, 5)[it] }
            ChipLabel(tr(R.string.glow_workout_mode_rest_the_strap_buzzes))
            Chips(Strength.Rest.TIMES.map { tr(R.string.missed_seconds, it) }, Strength.Rest.TIMES.indexOf(restSeconds), Color.White) { restSeconds = Strength.Rest.TIMES[it] }
            ChipLabel(tr(R.string.glow_workout_mode_or_sooner_once_heart))
            Chips(Strength.Rest.LEVELS.map { it?.let { b -> "$b bpm" } ?: tr(R.string.common_off) }, Strength.Rest.LEVELS.indexOf(restBelow), Color.White) { restBelow = Strength.Rest.LEVELS[it] }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipLabel(WorkoutCopy.targetHint(target))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<WorkoutTarget?>(null) + WorkoutTarget.offered(kcalTargets)).forEach { t ->
                    Chip(targetLabel(t), t == target, targetColor(t)) { targetCode = t?.code }
                }
            }
        }
        ListPanel {
            SettingRow(null, tr(R.string.glow_workout_mode_ask_to_end_when), tr(R.string.glow_workout_mode_after_min_near_resting), divider = false, onClick = { askToEnd = !askToEnd }) {
                GlowSwitch(askToEnd, { askToEnd = it })
            }
        }
    }
    if (info) InfoSheet(
        Info(tr(R.string.common_recording), WorkoutCopy.READY_MEANING, emptyList(), "", "", ""), accent, null, null,
        onDismiss = { info = false }, rows = WorkoutCopy.readyRows(outdoor),
    )
}

/** Whether the phone's location can be used now: allowed, and turned on. */
internal fun locationAvailable(context: Context): Boolean =
    WorkoutLocation.granted(context) && context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

/**
 * Ready's hero, the strap (spec 6.3): a glow in heart red lit from below with the live heart rate in the hero's dots,
 * pulsing at the real bpm, "Live from your strap." and the battery when low. While the strap is away it drops to an
 * outline card that says you can start anyway. The (i) explains recording, GPS and the route.
 */
@Composable
private fun StrapHero(connection: ConnectionState, liveHeartRate: Int?, onInfo: () -> Unit) {
    val hero = WorkoutCopy.readyHero(connection, liveHeartRate)
    val bpm = liveHeartRate.takeIf { hero.live }
    val style = GlowStyle.chart(MetricColors.heart).copy(
        lightAngle = LightAngle.Below,
        motion = if (bpm != null) GlowMotion.Heartbeat(bpm) else GlowMotion.Still,
        outline = !hero.live,
    )
    GlowCard(style, Modifier.fillMaxWidth()) {
        Column {
            GlowHeader(hero.title, trailingContent = { RoundButton(AppIcons.Info, tr(R.string.glow_workout_mode_about_recording), onInfo, size = 32.dp) })
            if (bpm != null) Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.Bottom) {
                DotMatrixText(bpm.toString(), dot = DotSize.Hero)
                // The word after a number (spec 2.3).
                Text("bpm", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                hero.line, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                modifier = Modifier.padding(top = if (bpm != null) 4.dp else 12.dp),
            )
        }
    }
}

/** The Outdoor or Indoor switch: two halves on a Control pill, the chosen one white. */
@Composable
private fun SideSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(Raised).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(CircleShape).background(if (on) Color.White else Color.Transparent)
                    .pressClickable({ if (!on) onSelect(i) }, CircleShape, scale = 0.97f)
                    .semantics { this.selected = on; role = Role.Tab }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (on) Color.Black else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1,
                )
            }
        }
    }
}

/**
 * A settings row in a list (spec 2.5, Grouped list): an optional white [icon], the [title] in Body with its [note]
 * under it in Secondary, and [trailing] (a switch) on the right. The whole row toggles through [onClick].
 */
@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?, title: String, note: String, divider: Boolean = true,
    onClick: () -> Unit, trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .let { if (divider) it.drawBehind { drawLine(RowHairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else it }
            .padding(vertical = GlowLayout.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = SportIconTint, modifier = Modifier.size(RowIcon))
            Spacer(Modifier.width(RowIconGap))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RowName(title)
            Text(note, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** A small white pill button at the end of a row ("Turn on"). */
@Composable
private fun SmallPill(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.Black, style = GlowType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(Color.White).pressClickable(onClick, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The label over a row of chips, in Caption. */
@Composable
private fun ChipLabel(text: String) =
    Text(text, color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.caption, modifier = Modifier.padding(start = 4.dp))

/** A choice on a Control tile, the chosen one lit in [color]. */
@Composable
private fun Chip(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Text(
        label, color = if (on) Color.Black else Color.White, style = GlowType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1,
        modifier = Modifier.height(40.dp).clip(GlowLayout.TileShape).background(if (on) color else Raised)
            .pressClickable(onClick, GlowLayout.TileShape).padding(horizontal = 14.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

/** A scrolling row of choices; the chosen one lit in [color]. */
@Composable
private fun Chips(labels: List<String>, selected: Int, color: Color, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label -> Chip(label, i == selected, color) { onSelect(i) } }
    }
}

/* ------------------------------------------- Choose a sport ------------------------------------------ */

/**
 * Choose a sport (spec 6.3): a page with the search field, your Recent sports, the twelve everyday ones as tiles and
 * a More sports row for the rest, A to Z. Search looks through every sport, both kinds of a two-kind sport included.
 * A tile picks its sport's key ([Workouts.tileType]); Ready's switch then picks the kind. [selected] is the sport
 * chosen now, -1 for none.
 */
@Composable
private fun SportPicker(selected: Int, recent: List<Int>, contentPadding: PaddingValues, onPick: (Int) -> Unit, onBack: () -> Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (more) more = false else onBack() }
    if (more) {
        ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
            item { PageHeader(tr(R.string.glow_workout_mode_more_sports), { more = false }) }
            item {
                ListPanel {
                    Workouts.MORE.forEachIndexed { i, type ->
                        PageRow(Workouts.sportName(type), icon = SportIcons.of(type), divider = i > 0, onClick = { onPick(type) })
                    }
                }
            }
        }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(query) { Workouts.search(query) }
    val chosen = Workouts.tileType(selected)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(tr(R.string.common_choose_a_sport), onBack) }
        item { SearchField(query) { query = it } }
        if (query.isNotBlank()) {
            item(key = "found") {
                if (found.isEmpty()) Text(
                    tr(R.string.glow_workout_mode_no_sport_matches, query.trim()), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.secondary, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
                else SportTiles(null, found, { if (it in Workouts.EVERYDAY) Workouts.shortName(it) else Workouts.sportName(it) }, { it == selected }, onPick)
            }
            return@ScreenList
        }
        if (recent.isNotEmpty()) item(key = "recent") { SportTiles(tr(R.string.glow_workout_mode_recent), recent, Workouts::shortName, { it == chosen }, onPick) }
        item(key = "sports") { SportTiles(tr(R.string.glow_workout_mode_sports), Workouts.EVERYDAY, Workouts::shortName, { it == chosen }, onPick) }
        item(key = "more") {
            ListPanel {
                PageRow(tr(R.string.glow_workout_mode_more_sports), tr(R.string.glow_workout_mode_tennis_padel_dance_and), below = true, divider = false, onClick = { more = true })
            }
        }
    }
}

/** A section of sport tiles, four to a row, under its Caption [title]. */
@Composable
private fun SportTiles(title: String?, types: List<Int>, name: (Int) -> String, isSelected: (Int) -> Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) ChipLabel(title)
        types.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                row.forEach { type -> SportTile(type, name(type), Modifier.weight(1f), selected = isSelected(type)) { onPick(type) } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The picker's search: a 48 dp field on the Control surface with 16 dp corners and the search icon (spec 6.3). */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        query, onChange, Modifier.fillMaxWidth(), singleLine = true,
        textStyle = GlowType.body.copy(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(GlowLayout.TileShape).background(Raised).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SportIcons.Search, null, tint = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(tr(R.string.common_search), color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.body, maxLines = 1)
                    field()
                }
                if (query.isNotEmpty()) Icon(
                    AppIcons.X, tr(R.string.glow_workout_mode_clear), tint = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onChange("") }.padding(6.dp),
                )
            }
        },
    )
}

/* -------------------------------------------- Live screen -------------------------------------------- */

/**
 * The workout as it records, in the current zone's colour. The card beats at the live heart rate on its zone arc, the
 * last ten minutes behind the number; the zone, its range and the time spent in it sit under the arc. A GPS workout
 * swipes between that card and the map, which keeps the heart rate on a chip, and opens on the view used last
 * ([liveView], saved through [onLiveView]). Back tucks it away; ending takes a one-second hold on the square so a
 * pocket can't end it. With [split] the heart card is on the other half of a folded phone ([GlowWorkoutGlance]), so
 * this half keeps the figures and the controls.
 */
@Composable
fun GlowWorkoutLiveScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    stats: WorkoutRecorder.Stats?,
    zoneSeconds: IntArray,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onMinimize: () -> Unit,
    onEndAtPrompt: () -> Unit,
    onKeepGoing: () -> Unit,
    route: List<Route.Point> = emptyList(),
    history: List<Pair<Long, Int>> = emptyList(),
    onStartSet: () -> Unit = {},
    onSetDone: () -> Unit = {},
    onExtendRest: () -> Unit = {},
    onLap: () -> Unit = {},
    split: Boolean = false,
    liveView: Int? = null,
    onLiveView: (Int) -> Unit = {},
    kcal: WorkoutRecorder.Kcal? = null,
) {
    BackHandler(onBack = onMinimize)
    // Saved with the screen; [liveView] is the view from the last workout, loaded a moment after the screen opens.
    val pager = rememberPagerState(initialPage = LiveView.of(liveView).ordinal) { LiveView.entries.size }
    var viewApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(liveView) {
        if (!viewApplied && liveView != null) {
            pager.scrollToPage(LiveView.of(liveView).ordinal)
            viewApplied = true
        }
    }
    // Only a swipe or a dot saves the view, never the page the screen opened on.
    val saveView by rememberUpdatedState(onLiveView)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect { saveView(it) } }
    // The map takes the heart card's height, so swiping never moves what's below.
    var cardHeight by remember { mutableStateOf(360.dp) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var routeOnly by rememberSaveable { mutableStateOf(false) }
    val connected = connection is ConnectionState.Connected
    val color = workoutColor(r, liveHeartRate, connected)
    val active = activeSeconds(r)
    val haptics = rememberHaptics()
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    // A tick when the zone changes (spec 3.6); a pause forgets the zone, so resuming in another doesn't tick.
    val zone = WorkoutRecorder.liveZone(bpm, r.maxHeartRate)
    var lastZone by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(zone, r.paused) {
        if (WorkoutCopy.zoneTick(lastZone, zone)) haptics.perform(HapticEvent.ZoneChange)
        lastZone = if (r.paused) null else zone ?: lastZone
    }
    WorkoutPage(color, contentPadding, scroll = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Raised).pressClickable(onMinimize, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.CaretDown, tr(R.string.glow_workout_mode_minimize), tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Pill(Workouts.sportName(r.workout.sportType), color)
            Spacer(Modifier.weight(1f))
            Text(
                when { r.paused -> tr(R.string.common_paused); connected -> tr(R.string.glow_workout_mode_strap); else -> tr(R.string.common_reconnecting) },
                color = Color.White.copy(alpha = 0.65f), style = GlowType.secondary,
            )
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.size(6.dp)) { drawCircle(if (connected) TrendGood else TrendBad) }
        }
        val heartCard: @Composable () -> Unit = {
            WorkoutHeartCard(
                r, liveHeartRate, recent, zoneSeconds, connected, active,
                Modifier.fillMaxWidth().onSizeChanged { cardHeight = with(density) { it.height.toDp() } }, kcal = kcal?.active,
            )
        }
        // GPS workouts have two views, heart rate and the map, with two dots under them (pattern D5). They swipe as
        // well as tap; a drag that starts on the map pans the map. Split across a fold, the card has the other half.
        if (split) Unit
        else if (r.workout.useGps) {
            HorizontalPager(pager, pageSpacing = 16.dp, verticalAlignment = Alignment.Top, beyondViewportPageCount = 1) { page ->
                Column(Modifier.fillMaxWidth()) {
                    when (LiveView.entries[page]) {
                        LiveView.Map -> LiveMapPage(route, history, r.maxHeartRate, routeOnly, bpm, color, r.paused, Modifier.height(cardHeight)) { routeOnly = it }
                        LiveView.Heart -> heartCard()
                    }
                }
            }
            PageDots(pager.currentPage, LiveView.entries.size) { scope.launch { pager.animateScrollToPage(it) } }
        } else heartCard()
        if (r.strength) SetsPanel(r, color, onStartSet, { haptics.perform(HapticEvent.SetDone); onSetDone() }, onExtendRest)
        // Centred between the card and the buttons: the moving time large in the middle, the average and peak either
        // side (Oura's avg and max around the live number), labels under the numbers and thin rules between (WHOOP).
        // kcal, the distance and the pace sit in a row under it, so every figure shows without a tap.
        Spacer(Modifier.weight(1f))
        val figures = WorkoutCopy.liveFigures(WorkoutCopy.sideFigures(kcal != null, r.workout.distanceMeters != null, !r.workout.distanceFromSteps))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
            SideStat(figures.sides[0], r, stats, kcal, route, Modifier.weight(1f))
            StatRule()
            LiveStat(tr(R.string.glow_workout_mode_moving_time), Modifier.weight(1.5f)) {
                // Plain digits: the heart rate above is the one dot-matrix number on the screen.
                Text(workoutClock(active), color = Color.White, style = LiveClock)
            }
            StatRule()
            SideStat(figures.sides[1], r, stats, kcal, route, Modifier.weight(1f))
        }
        if (figures.below.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 16.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
            // A figure alone sits in the middle third, under the moving time, rather than across the screen.
            if (figures.below.size == 1) Spacer(Modifier.weight(1f))
            figures.below.forEachIndexed { i, f ->
                if (i > 0) StatRule()
                SideStat(f, r, stats, kcal, route, Modifier.weight(1f))
            }
            if (figures.below.size == 1) Spacer(Modifier.weight(1f))
        }
        // The splits live in the summary; here each kilometre gets a line for a few seconds.
        if (r.workout.useGps) KmSplitLine(route, WorkoutCopy.readings(history, recent), r.workout.pauseRanges)
        // Once Lap has been pressed: the lap under way and its own clock, so a press shows on screen too.
        r.workout.lapMarks.lastOrNull()?.let { mark ->
            val lap = (active - WorkoutRecorder.activeSeconds(r.workout, mark)).coerceAtLeast(0)
            Text(
                tr(R.string.glow_workout_mode_lap, r.workout.lapMarks.size + 1, workoutClock(lap)),
                color = Color.White.copy(alpha = 0.75f), style = MediumBody.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
        }
        if (!connected && !r.paused) {
            Note(tr(R.string.glow_workout_mode_the_strap_isn_t))
        }
        r.endPrompt?.let { at -> StoppedPrompt(r, at, onEndAtPrompt, onKeepGoing) }
        Spacer(Modifier.weight(1f))
        // At most a thumb's reach wide, so on a wide screen (an unfolded or half-folded phone) Lap and End stay by Pause.
        Row(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Lap (not for strength, which has sets), keeping Pause centred either way. An ordinary tap: no tick.
            if (!r.strength && !r.paused) FlatButton(Raised, 64.dp, onClick = onLap) {
                Icon(SportIcons.Lap, tr(R.string.common_lap), tint = Color.White, modifier = Modifier.size(22.dp))
            } else Spacer(Modifier.size(64.dp))
            Spacer(Modifier.weight(1f))
            FlatButton(Color.White, 88.dp, onClick = if (r.paused) onResume else onPause) {
                Icon(if (r.paused) AppIcons.PlayFill else AppIcons.PauseFill, if (r.paused) tr(R.string.common_resume) else tr(R.string.common_pause), tint = Color.Black, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.weight(1f))
            HoldToEnd(onEnd)
        }
        Text(
            tr(R.string.glow_workout_mode_hold_the_square_to), color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        // A little room under the controls, so they sit up off the screen's edge.
        Spacer(Modifier.height(20.dp))
    }
}

/** Two small dots for the live views, the current one white; tapping one goes there (the map keeps its own drags). */
@Composable
private fun PageDots(current: Int, count: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier.size(28.dp).clip(CircleShape).pressClickable({ onSelect(i) }, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == current) 1f else 0.3f)))
            }
        }
    }
}

/**
 * "Km 3 in 5:42, avg 152" under the stats for ten seconds after each kilometre, with one tick. Kilometres done before
 * the screen opened have had theirs.
 */
@Composable
private fun KmSplitLine(route: List<Route.Point>, readings: List<Pair<Long, Int>>, pauses: List<LongRange>) {
    val splits = remember(route.size, pauses) { Route.splits(route, pauses) }
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(1000 - System.currentTimeMillis() % 1000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val line = WorkoutCopy.splitLine(splits, readings, route.firstOrNull()?.t, now)
    val haptics = rememberHaptics()
    var ticked by remember { mutableIntStateOf(WorkoutCopy.wholeKms(splits)) }
    LaunchedEffect(line?.index) {
        if (line != null && WorkoutCopy.splitTick(line, ticked)) {
            haptics.perform(HapticEvent.KmSplit)
            ticked = line.index
        }
    }
    if (line != null) Text(
        line.text, color = Color.White, style = MediumBody.copy(fontFeatureSettings = "tnum"),
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    )
}

/** Where the zone arc starts (lower left, clockwise from three o'clock) and how far it goes, in degrees. */
private const val ARC_START = 150f
private const val ARC_SWEEP = 240f
private const val ARC_GAP = 8f

/**
 * The five zones as segments of an arc open at the bottom, only the current one lit, with a marker where [bpm] sits
 * (pattern D2). The number goes in its middle.
 */
@Composable
private fun ZoneArc(bpm: Int?, maxHr: Int, modifier: Modifier) {
    val zone = WorkoutRecorder.liveZone(bpm, maxHr)
    Canvas(modifier) {
        val stroke = 6.dp.toPx()
        val halo = 9.dp.toPx()
        val r = min(size.width, size.height) / 2 - halo
        val topLeft = Offset(center.x - r, center.y - r)
        val box = Size(r * 2, r * 2)
        val seg = (ARC_SWEEP - ARC_GAP * (Workouts.ZONES - 1)) / Workouts.ZONES
        for (z in 0 until Workouts.ZONES) {
            val start = ARC_START + z * (seg + ARC_GAP)
            val on = z == zone
            // The lit segment glows: a wide soft stroke of itself under it.
            if (on) drawArc(ZONE_COLORS[z].copy(alpha = 0.3f), start, seg, false, topLeft, box, style = Stroke(stroke * 2.6f, cap = StrokeCap.Round))
            drawArc(ZONE_COLORS[z].copy(alpha = if (on) 1f else 0.22f), start, seg, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (bpm != null) {
            val a = Math.toRadians((ARC_START + WorkoutRecorder.zonePosition(bpm, maxHr) * ARC_SWEEP).toDouble())
            val at = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
            val dot = zone?.let { ZONE_COLORS[it] } ?: Color.White
            drawCircle(dot.copy(alpha = 0.3f), radius = halo, center = at)
            drawCircle(Color.White, radius = 5.dp.toPx(), center = at)
        }
    }
}

/**
 * The live heart card: the beating number on its zone arc over the last ten minutes, then the zone, its range and the
 * time in it. Its light reaches as high as the zone (spec 2.5); paused, it drops to an outline of the same layout
 * (pattern D1). With [fill] it takes all the height it's given and the number grows, for the standing half of a fold.
 */
@Composable
private fun WorkoutHeartCard(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    zoneSeconds: IntArray,
    connected: Boolean,
    active: Long,
    modifier: Modifier,
    fill: Boolean = false,
    /** The live active kcal, for a kcal target's progress. */
    kcal: Float? = null,
) {
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    val zone = WorkoutRecorder.liveZone(bpm, r.maxHeartRate)
    val color = workoutColor(r, liveHeartRate, connected)
    val style = GlowStyle.chart(color).copy(
        motion = if (bpm != null) GlowMotion.Heartbeat(bpm) else GlowMotion.Still,
        lightLevel = WorkoutCopy.zoneLightLevel(zone),
        outline = r.paused,
    )
    GlowCard(style, modifier) {
        Column(if (fill) Modifier.fillMaxSize() else Modifier) {
            GlowHeader(tr(R.string.common_heart_rate), trailing = bpm?.let { tr(R.string.glow_workout_mode_of_max, it * 100 / r.maxHeartRate) })
            BoxWithConstraints(Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f) else Modifier.height(220.dp))) {
                val now = recent.lastOrNull()?.first ?: (System.currentTimeMillis() / 1000)
                val values = remember(recent) { bucketRecent(recent, now) }
                if (values.any { it != null }) {
                    GlowLineChart(values, Modifier.align(Alignment.BottomCenter).alpha(0.45f), height = 120.dp, showAverage = false)
                }
                ZoneArc(bpm, r.maxHeartRate, Modifier.matchParentSize())
                // Sized for three digits inside the arc, whatever the reading, so the number never spills past it
                // and doesn't jump in size as the heart rate crosses 100.
                val inside = (minOf(maxWidth, maxHeight) - 18.dp) * 0.78f
                val scale = (inside / dotNumberWidth(3, DotSize.Live)).coerceAtMost(1f)
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bpm != null) {
                        DotMatrixText(bpm.toString(), dot = DotSize.Live, scale = scale)
                        Spacer(Modifier.height(8.dp))
                        Text(tr(R.string.glow_workout_mode_bpm_last_min_behind), color = Color.White.copy(alpha = 0.75f), style = GlowType.secondary)
                    } else Text(
                        when { r.paused -> tr(R.string.common_paused); !connected -> tr(R.string.common_waiting_for_the_strap); else -> tr(R.string.common_waiting_for_heart_rate) },
                        color = Color.White, style = GlowType.sentence,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (zone != null) {
                    // "Zone 3, aerobic": two text elements, the name a step quieter.
                    val (name, word) = WorkoutCopy.zoneTitle(zone)
                    Text(name, color = Color.White, style = GlowType.sentence)
                    Spacer(Modifier.width(6.dp))
                    Text(word, color = Color.White.copy(alpha = 0.75f), style = GlowType.sentence, modifier = Modifier.weight(1f))
                    Text(zoneRange(zone, r.maxHeartRate), color = Color.White.copy(alpha = 0.85f), style = GlowType.secondary)
                } else Text(if (r.paused) tr(R.string.glow_workout_mode_no_zone_while_paused) else tr(R.string.glow_workout_mode_no_zone_yet), color = Color.White, style = GlowType.sentence)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                zone?.let { Note(WorkoutCopy.zoneTime(zoneSeconds[it])) }
                Spacer(Modifier.weight(1f))
                when (val t = WorkoutTarget.parse(r.workout.target)) {
                    is WorkoutTarget.Zones -> Note(tr(R.string.glow_workout_mode_target_the_strap_buzzes_3, t.label.removePrefix("Zone ")))
                    is WorkoutTarget.Time -> Note("${workoutClock(active)} of ${t.label}")
                    is WorkoutTarget.Kcal -> Note(WorkoutCopy.kcalProgress(kcal, t))
                    null -> Unit
                }
            }
        }
    }
}

/**
 * The standing half of a foldable half-open during a workout (on a table, or stood up like a book): the live heart
 * card as big as the half allows, readable from a step away. The controls stay on the other half, under your hand.
 */
@Composable
fun GlowWorkoutGlance(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    zoneSeconds: IntArray,
    connection: ConnectionState,
    modifier: Modifier = Modifier,
    kcal: WorkoutRecorder.Kcal? = null,
) {
    val connected = connection is ConnectionState.Connected
    Box(modifier.background(Color.Black).statusBarsPadding().padding(horizontal = GlowLayout.Gutter, vertical = 12.dp)) {
        WorkoutHeartCard(r, liveHeartRate, recent, zoneSeconds, connected, activeSeconds(r), Modifier.fillMaxSize(), fill = true, kcal = kcal?.active)
    }
}

/**
 * Strength: the sets as dots against the target, and the one control that matters now: "Set done" during a set;
 * during rest a countdown with "Start set N" and "+30 s"; otherwise "Start set N".
 */
@Composable
private fun SetsPanel(r: WorkoutState.Recording, color: Color, onStartSet: () -> Unit, onSetDone: () -> Unit, onExtend: () -> Unit) {
    val now by produceState(System.currentTimeMillis() / 1000) { while (true) { value = System.currentTimeMillis() / 1000; delay(1000 - System.currentTimeMillis() % 1000) } }
    val done = r.sets.count { it.end != null }
    val target = r.workout.setsTarget
    QuietPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PanelTitle(
                    when {
                        r.inSet -> tr(R.string.glow_workout_mode_set, r.sets.size, workoutClock(now - r.sets.last().start))
                        r.resting -> tr(R.string.glow_workout_mode_resting_after_set, done, workoutClock((r.restUntil!! - now).coerceAtLeast(0)))
                        done == 0 -> tr(R.string.glow_workout_mode_ready_for_set)
                        else -> tr(R.string.glow_workout_mode_rest_over_set_next, done + 1)
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(maxOf(target ?: 0, done + if (r.inSet) 1 else 0)) { i ->
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (i < done) color else if (i == done && r.inSet) color.copy(alpha = 0.5f) else Raised))
                }
            }
        }
        r.restUntil?.let { until ->
            val start = r.sets.lastOrNull()?.end ?: until
            GlowBar(((now - start).toFloat() / (until - start).coerceAtLeast(1)).coerceIn(0f, 1f), color)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (r.inSet) Box(Modifier.weight(1f)) { WorkoutButton(tr(R.string.common_set_done), Color.White, Color.Black, onSetDone, height = 52.dp) }
            else Box(Modifier.weight(1f)) { WorkoutButton(tr(R.string.common_start_set, r.sets.size + 1), Color.White, Color.Black, onStartSet, height = 52.dp) }
            if (r.resting) Box(Modifier.width(96.dp)) { WorkoutButton(tr(R.string.missed_plus_30_s), Raised, Color.White, onExtend, height = 52.dp) }
        }
        if (target != null && done >= target && !r.inSet) Note(tr(R.string.glow_workout_mode_all_sets_done_hold, target))
    }
}

/** "5.21" kilometres, or "820 m" under one. */
internal fun km(meters: Float): String = if (meters < 1000) "${meters.toInt()} m" else String.format(java.util.Locale.ROOT, "%.2f", meters / 1000)

/**
 * The live route in zone colours with the heart rate on a chip in the zone's colour, so it's never hidden (pattern
 * D5), then the GPS accuracy and "route only" (no tiles).
 */
@Composable
private fun LiveMapPage(
    route: List<Route.Point>,
    history: List<Pair<Long, Int>>,
    maxHr: Int,
    routeOnly: Boolean,
    bpm: Int?,
    color: Color,
    paused: Boolean,
    modifier: Modifier,
    onRouteOnly: (Boolean) -> Unit,
) {
    val stretches = remember(route.size, history.size) { Route.byZone(route, history, maxHr) }
    // The whole page is the map; what its tiles reveal is said in the ready screen's (i).
    Box(modifier.fillMaxWidth().clip(GlowLayout.CardShape).background(Raised)) {
        // Where you are now, from the latest fix good enough to count, even while standing still.
        val here = route.lastOrNull { it.accuracy <= Route.MAX_ACCURACY_M }
        WorkoutMap(stretches, routeOnly, Modifier.fillMaxSize(), here)
        BpmChip(bpm, color, paused, Modifier.align(Alignment.TopStart).padding(16.dp))
        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val acc = route.lastOrNull()?.accuracy
            Pill(if (acc == null || acc > 1000) tr(R.string.glow_workout_mode_finding_gps) else tr(R.string.glow_workout_mode_gps_m, acc.toInt()), if (acc != null && acc <= Route.MAX_ACCURACY_M) TrendGood else TrendBad)
            Pill(if (routeOnly) tr(R.string.glow_workout_mode_route_only) else tr(R.string.glow_workout_mode_map), null, onClick = { onRouteOnly(!routeOnly) })
        }
    }
}

/** The live heart rate on the map: a pill in the zone's [color], a dot beating at it, the number in dots. */
@Composable
private fun BpmChip(bpm: Int?, color: Color, paused: Boolean, modifier: Modifier) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bpm != null) {
            PulseDot(Color.Black, bpm, 8.dp)
            Spacer(Modifier.width(10.dp))
            DotMatrixText("$bpm", dot = DotSize.Edge, color = Color.Black)
            Spacer(Modifier.width(6.dp))
            Text("bpm", color = Color.Black, style = MediumBody)
        } else Text(if (paused) tr(R.string.common_paused) else tr(R.string.glow_workout_mode_no_heart_rate_yet), color = Color.Black, style = MediumBody)
    }
}

@Composable
private fun LiveStat(label: String, modifier: Modifier, value: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        value()
        Text(label, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary)
    }
}

/**
 * One figure around the moving time. "1.25" alone could be anything: the distance's label carries the unit from a
 * kilometre on. kcal is active kcal, in plain digits like the rest.
 */
@Composable
private fun SideStat(
    figure: SideFigure, r: WorkoutState.Recording, stats: WorkoutRecorder.Stats?, kcal: WorkoutRecorder.Kcal?,
    route: List<Route.Point>, modifier: Modifier,
) {
    val distance = r.workout.distanceMeters
    val (label, value) = when (figure) {
        SideFigure.Avg -> tr(R.string.glow_workout_mode_avg) to stats?.average?.toString()
        SideFigure.Max -> tr(R.string.common_max) to stats?.max?.toString()
        SideFigure.Kcal -> "kcal" to kcal?.active?.toInt()?.toString()
        SideFigure.Distance -> (if (r.workout.distanceFromSteps) tr(R.string.glow_workout_mode_distance) else tr(R.string.common_distance)) +
            (if (distance != null && distance >= 1000) " km" else "") to distance?.let(::km)
        SideFigure.Pace -> tr(R.string.glow_workout_mode_pace_km) to Route.formatPace(Route.currentPace(route))
    }
    val spoken = when (figure) {
        SideFigure.Avg -> tr(R.string.sleep_panels_average_heart_rate)
        SideFigure.Max -> tr(R.string.glow_workout_mode_peak_heart_rate)
        SideFigure.Kcal -> tr(R.string.glow_workout_mode_active_kcal)
        SideFigure.Distance -> if (r.workout.distanceFromSteps) tr(R.string.glow_workout_mode_distance_from_steps) else tr(R.string.common_distance)
        SideFigure.Pace -> tr(R.string.glow_workout_mode_pace_per_kilometre)
    }
    val unit = if (figure == SideFigure.Distance && distance != null && distance >= 1000) " kilometres" else ""
    LiveStat(
        label,
        modifier.semantics(mergeDescendants = true) { contentDescription = "$spoken ${value?.let { it + unit } ?: "waiting"}" },
    ) { LiveValue(value) }
}

/** The thin rule between the live figures. */
@Composable
private fun StatRule() = Box(Modifier.padding(vertical = 6.dp).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.12f)))

/** Avg and Max in the same face and size as the moving time beside them, or the dim dash at the same height. */
@Composable
private fun LiveValue(text: String?) =
    if (text == null) WaitingDash(Modifier.padding(vertical = 13.5.dp))
    else Text(text, color = Color.White, style = LiveFigure)

private const val BUCKET_S = 5L
private const val WINDOW_S = 600L

/** The last ten minutes in five-second averages, oldest first; gaps stay null and break the line. */
private fun bucketRecent(recent: List<Pair<Long, Int>>, now: Long): List<Float?> {
    val n = (WINDOW_S / BUCKET_S).toInt()
    val sums = FloatArray(n)
    val counts = IntArray(n)
    val from = now - WINDOW_S + 1
    recent.forEach { (t, bpm) ->
        val i = ((t - from) / BUCKET_S).toInt()
        if (i in 0 until n) { sums[i] += bpm.toFloat(); counts[i]++ }
    }
    return List(n) { if (counts[it] == 0) null else sums[it] / counts[it] }
}

/**
 * A round button in the glow language: a small glow card lit from its rim in [color], its icon on the dark core.
 * [onClick] null leaves the touch handling to [modifier] (the end square's hold).
 */
@Composable
private fun FlatButton(container: Color, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    // Flat, like the app's Start and Done: the live screen's card already carries the glow.
    Box(
        modifier.size(size).clip(CircleShape).background(container)
            .then(if (onClick != null) Modifier.pressClickable(onClick, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** "Looks like you've stopped": end where the wearer stopped, or keep going. The strap has buzzed already. */
@Composable
private fun StoppedPrompt(r: WorkoutState.Recording, at: Long, onEnd: () -> Unit, onKeep: () -> Unit) {
    QuietPanel {
        PanelTitle(if (r.inCar) tr(R.string.common_looks_like_you_re) else tr(R.string.common_looks_like_you_ve))
        Note(
            if (r.inCar) tr(R.string.glow_workout_mode_you_last_moved_at, clock(at), Workouts.sportName(r.workout.sportType).lowercase())
            else tr(R.string.glow_workout_mode_your_heart_rate_has_2, clock(at), Workouts.sportName(r.workout.sportType).lowercase(), clock(at)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { WorkoutButton(tr(R.string.glow_workout_mode_end_at, clock(at)), Color.White, Color.Black, onEnd) }
            Box(Modifier.weight(1f)) { WorkoutButton(tr(R.string.common_keep_going), Raised, Color.White, onKeep) }
        }
    }
}

/** The end square needs a one-second hold: a ring fills around it, and letting go early cancels it. */
@Composable
private fun HoldToEnd(onEnd: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val haptics = rememberHaptics()
    Box(contentAlignment = Alignment.Center) {
        FlatButton(
            Raised, 64.dp,
            Modifier.pointerInput(Unit) {
                detectTapGestures(onPress = {
                    coroutineScope {
                        val hold = launch {
                            progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                            haptics.perform(HapticEvent.WorkoutEnd)
                            onEnd()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            hold.cancel()
                            progress.animateTo(0f, tween(200))
                        }
                    }
                })
            },
        ) {
            Icon(AppIcons.StopFill, tr(R.string.glow_workout_mode_hold_to_end_the), tint = EndRed, modifier = Modifier.size(20.dp))
        }
        Canvas(Modifier.size(64.dp).padding(2.dp)) {
            if (progress.value > 0f) drawArc(EndRed, -90f, 360f * progress.value, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

private val EndRed = MetricColors.heart
private const val HOLD_MS = 2000

/* ------------------------------------- Rest of the app meanwhile ------------------------------------- */

/**
 * The workout tucked away: a bar above the tab bar ringed in the zone's colour, with a dot pulsing at the heart
 * rate, the sport and time (and the active kcal after it when there's room), the zone, the bpm and Pause. Tap it to
 * bring the live screen back.
 */
@Composable
fun WorkoutCapsule(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    connected: Boolean,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    kcal: WorkoutRecorder.Kcal? = null,
) {
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    val zone = WorkoutRecorder.liveZone(bpm, r.maxHeartRate)
    val color = workoutColor(r, liveHeartRate, connected)
    val active = activeSeconds(r)
    val shape = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().height(64.dp).shadow(24.dp, shape, ambientColor = color, spotColor = color)
            .clip(shape).background(Raised).border(BorderStroke(1.5.dp, color), shape)
            .pressClickable(onClick, shape).padding(start = 18.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(color, bpm, 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AfterIfRoom(
                { Text("${Workouts.sportName(r.workout.sportType)}, ${workoutClock(active)}", color = Color.White, style = MediumBody, maxLines = 1) },
                WorkoutCopy.kcalText(kcal?.active)?.let { k ->
                    { Text(k, color = Color.White.copy(alpha = 0.6f), style = MediumBody.copy(fontFeatureSettings = "tnum"), maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
                },
            )
            Text(
                when {
                    r.endPrompt != null -> tr(R.string.glow_workout_mode_stopped_tap_to_answer)
                    r.paused -> tr(R.string.common_paused)
                    zone != null -> WorkoutCopy.zoneTitle(zone).let { (name, word) -> "$name $word" }
                    connected -> tr(R.string.common_waiting_for_heart_rate)
                    else -> tr(R.string.glow_workout_mode_strap_reconnecting)
                },
                color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, maxLines = 1,
            )
        }
        if (bpm != null) {
            DotMatrixText("$bpm", dot = DotSize.Edge, color = color)
            Spacer(Modifier.width(12.dp))
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(color).pressClickable(if (r.paused) onResume else onPause, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (r.paused) AppIcons.PlayFill else AppIcons.PauseFill, if (r.paused) tr(R.string.common_resume) else tr(R.string.common_pause), tint = Color.Black, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * [first], then [after] beside it on the same line only if all of it fits in the width left; otherwise
 * [after] is left out rather than cut short.
 */
@Composable
private fun AfterIfRoom(first: @Composable () -> Unit, after: (@Composable () -> Unit)?) {
    androidx.compose.ui.layout.Layout(content = { first(); after?.invoke() }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0)
        val a = measurables[0].measure(loose)
        val b = measurables.getOrNull(1)?.measure(loose.copy(maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
            ?.takeIf { a.width + it.width <= constraints.maxWidth }
        val height = maxOf(a.height, b?.height ?: 0)
        layout((a.width + (b?.width ?: 0)).coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            a.placeRelative(0, 0)
            b?.placeRelative(a.width, 0)
        }
    }
}

/** A dot in [color] that beats at [bpm]; still without one. */
@Composable
fun PulseDot(color: Color, bpm: Int?, size: Dp) {
    val beat = if (bpm != null) {
        val period = (60_000 / bpm).coerceIn(250, 2000)
        rememberInfiniteTransition(label = "beat").animateFloat(
            1f, 1f,
            infiniteRepeatable(keyframes { durationMillis = period; 1.5f at period * 15 / 100; 1f at period * 40 / 100 }, RepeatMode.Restart),
            label = "dot",
        ).value
    } else 1f
    Canvas(Modifier.size(size).scale(beat)) { drawCircle(color) }
}

/* ---------------------------------------------- Summary ---------------------------------------------- */

private fun clock(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/** Done right after the end, and Delete workout behind a question. */
@Composable
internal fun ColumnScope.SummaryActions(justEnded: Boolean, onDone: () -> Unit, onDelete: () -> Unit) {
    if (justEnded) WorkoutButton(tr(R.string.common_done), Color.White, Color.Black, onDone)
    DeleteWorkout(PHONE_DELETE, onDelete, Modifier.align(Alignment.CenterHorizontally))
}

/** What deleting a phone workout does. */
internal val PHONE_DELETE: String get() = tr(R.string.glow_workout_mode_its_heart_rate_and)

/** "Delete workout" in [DestructiveInk], asking first; [what] says what deleting it does. */
@Composable
internal fun DeleteWorkout(what: String, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var confirmDelete by remember { mutableStateOf(false) }
    Text(
        tr(R.string.glow_workout_mode_delete_this_workout), color = DestructiveInk, style = GlowType.body,
        modifier = modifier.clip(RoundedCornerShape(50))
            .pressClickable({ confirmDelete = true }, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
    )
    if (confirmDelete) {
        GlassAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr(R.string.glow_workout_mode_delete_this_workout_2)) },
            text = { Text(what) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(tr(R.string.glow_workout_mode_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr(R.string.common_cancel)) } },
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
        val left = if (cut < 60) tr(R.string.missed_seconds, cut) else tr(R.string.missed_duration_min, cut / 60)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (summary.endReason == AppWorkout.END_CAR && a.shownStart == w.start) tr(R.string.glow_workout_mode_ended_at_of_driving, clock(a.end), left)
                else if (summary.endReason == AppWorkout.END_AUTO && a.shownStart == w.start) tr(R.string.glow_workout_mode_ended_at_when_you, clock(a.end), left)
                else tr(R.string.glow_workout_mode_trimmed_to_the_rest, clock(a.shownStart), clock(a.end)),
                color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary, modifier = Modifier.weight(1f),
            )
            Text(
                tr(R.string.common_undo), color = Color.White, style = MediumBody,
                modifier = Modifier.clip(RoundedCornerShape(50)).pressClickable(onUndoTrim, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    } ?: if (a.shownStart != w.start) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.glow_workout_mode_trimmed_to_start_at, clock(a.shownStart)), color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary, modifier = Modifier.weight(1f))
            Text(
                tr(R.string.common_undo), color = Color.White, style = MediumBody,
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
            Text(tr(R.string.glow_workout_mode_trim_start_and_end), color = Color.White, style = GlowType.body, modifier = Modifier.weight(1f))
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
        PanelTitle(tr(R.string.glow_workout_mode_trim), trailing = SummaryCopy.trimHeader(clock(from), clock(to), to - from))
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
        Note(tr(R.string.glow_workout_mode_drag_the_handles_to_2))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { WorkoutButton(tr(R.string.common_cancel), Raised, Color.White, onCancel) }
            Box(Modifier.weight(1f)) { WorkoutButton(tr(R.string.common_save), Color.White, Color.Black, { onSave(from, to) }) }
        }
    }
}

/**
 * The live screen tucking away into its capsule above the tab bar, and back, in the app's screen motion: the screen
 * drops a quarter of its height and fades while the capsule rises a little and fades in, and the reverse. [capsule]
 * gets the modifier that places it; [capsuleBottom] is its distance from the bottom edge.
 */
@Composable
fun WorkoutMorph(
    minimized: Boolean,
    capsuleBottom: androidx.compose.ui.unit.Dp,
    full: @Composable () -> Unit,
    capsule: @Composable (Modifier) -> Unit,
) {
    val swift = org.openstrap.app.ui.components.Motion.SWIFT
    val move = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(swift, easing = org.openstrap.app.ui.components.Motion.easing)
    val fadeIn = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(swift))
    val fadeOut = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(org.openstrap.app.ui.components.Motion.QUICK))
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            !minimized, Modifier.fillMaxSize(),
            enter = androidx.compose.animation.slideInVertically(move) { it / 4 } + fadeIn,
            exit = androidx.compose.animation.slideOutVertically(move) { it / 4 } + fadeOut,
        ) { Box(Modifier.fillMaxSize().background(Color.Black)) { full() } }
        androidx.compose.animation.AnimatedVisibility(
            minimized, Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(move) { it / 2 } + fadeIn,
            exit = androidx.compose.animation.slideOutVertically(move) { it / 2 } + fadeOut,
        ) { capsule(Modifier.widthIn(max = 440.dp).padding(start = 16.dp, end = 16.dp, bottom = capsuleBottom)) }
    }
}

/**
 * Where Today's Start workout pill stands while a workout records, in the same white pill: running, the sport and its
 * clock with a live dot, opening the live screen; paused, "Resume" with a play icon, resuming at once.
 */
@Composable
fun WorkoutShortcut(r: WorkoutState.Recording, liveHeartRate: Int?, onOpen: () -> Unit, onResume: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val sport = Workouts.sportName(r.workout.sportType)
    val clock = workoutClock(activeSeconds(r))
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(Color.White).pressClickable(if (r.paused) onResume else onOpen, shape),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (r.paused) Icon(AppIcons.PlayFill, null, tint = Color.Black, modifier = Modifier.size(16.dp))
        else PulseDot(Color.Black, liveHeartRate, 10.dp)
        Spacer(Modifier.width(10.dp))
        Text(if (r.paused) tr(R.string.glow_workout_mode_resume, sport.lowercase()) else sport, color = Color.Black, style = MediumBody)
        Spacer(Modifier.width(8.dp))
        Text(clock, color = Color.Black, style = MediumBody.copy(fontFeatureSettings = "tnum"))
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
