package org.openstrap.app.ui.glow.live

import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.openstrap.app.data.Workouts
import org.openstrap.app.presentation.WorkoutWords
import org.openstrap.app.presentation.sportNoun
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.components.HapticEvent
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.components.rememberHaptics
import org.openstrap.app.ui.glow.*
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.SportIcons
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.shape.RoundedCornerShape
import org.openstrap.app.presentation.WorkoutCopy
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.dashboard.LocalHeartZones
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R

/**
 * The live stats row's figures, unchanged from before spec 6.3 ("Stats row unchanged"): the moving time large in the
 * middle and the two figures either side, in Display Light. The only two sizes on the workout pages no role has; a
 * live clock read at arm's length isn't one of spec 2.3's.
 */
internal val LiveClock = GlowType.sentence.copy(fontSize = 46.sp, lineHeight = 50.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum")
internal val LiveFigure = GlowType.sentence.copy(fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum")

/** What every live screen can do to the workout, passed down as one. */
@androidx.compose.runtime.Immutable
internal class LiveControls(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onEnd: () -> Unit,
    val onMinimize: () -> Unit,
    val onEndAtPrompt: () -> Unit,
    val onKeepGoing: () -> Unit,
)

/**
 * The frame every live screen shares (workouts spec 2.3, 6): minimize, the sport in the workout's [color] and the
 * strap's state along the top, then the moving time large ([time], on the screens built for each sport), the screen's
 * own [content], what any workout may need to say (the strap away, "Looks like you've stopped"), and the controls row
 * at the bottom in the same place on every screen: [lap] on the left (Lap or Skip, or nothing), Pause or Resume in the
 * middle and largest, and the square to hold to end. Back tucks it away.
 *
 * With [paused] given, a paused workout shows it in place of [content], and the controls become End workout (held, so
 * ending still takes a hold) and Resume. [status] adds lines to the strap's on the top right (GPS and its accuracy).
 */
@Composable
internal fun LiveFrame(
    r: WorkoutState.Recording,
    color: Color,
    connected: Boolean,
    contentPadding: PaddingValues,
    controls: LiveControls,
    lap: (@Composable () -> Unit)? = null,
    /** The total time, read by its own text so only the clock is composed again each second. */
    time: (() -> Long)? = null,
    /** The total time small on the top right instead of large under it (strength, intervals). */
    smallTime: Boolean = false,
    battery: Int? = null,
    status: List<Pair<String, Color>> = emptyList(),
    paused: (@Composable ColumnScope.() -> Unit)? = null,
    /** The [content] takes the height left above the buttons itself (a weighted pager), so nothing pushes them down. */
    fill: Boolean = false,
    /** Pause and the hold to end as two pills under the screen's own big button (strength), not the round row. */
    pillControls: Boolean = false,
    /** The pill's words when more than the sport's name ("HIIT, Tabata"). */
    title: String? = null,
    backdrop: androidx.compose.ui.graphics.Brush? = null,
    /** Mind and body (board LiveMind): the sport's name plain and centred, no strap status, and two dark buttons. */
    quiet: Boolean = false,
    /** Under a quiet screen's buttons, the line saying a tap lights it; left out where there's no room for it. */
    tapHint: Boolean = true,
    /**
     * A page under a bar shared by the pages ([LiveBottomBar]): the note, the prompt and the controls are drawn once
     * under the pager, so the page only leaves this much room for them.
     */
    bottomRoom: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = controls.onMinimize)
    // The screens built for each sport label their buttons; the older one says to hold the square under them.
    val labelled = time != null
    val landscape = LocalLiveLandscape.current
    // On its side the screen keeps a wider column than a page's, so the content and the controls fit side by side.
    CompositionLocalProvider(LocalPageColumn provides if (landscape) GlowLayout.MaxContentWidth else LocalPageColumn.current) {
        WorkoutPage(color, contentPadding, scroll = false, backdrop = backdrop) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(AppIcons.CaretDown, stringResource(R.string.workout_live_minimize), controls.onMinimize)
                if (quiet) {
                    Text(
                        title ?: Workouts.sportName(r.workout.sportType), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                        style = GlowType.cardTitle, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f),
                    )
                    // The minimise button's width again, so the name sits in the middle of the screen.
                    Spacer(Modifier.width(44.dp))
                    return@Row
                }
                Spacer(Modifier.width(10.dp))
                // The pill takes what the status leaves, so on a narrow screen the sport's name gives way, not the status.
                Box(Modifier.weight(1f)) { IconPill(title ?: Workouts.sportName(r.workout.sportType), null, dot = color) }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val strap = when {
                        r.paused -> stringResource(R.string.workout_live_status_paused)
                        !connected -> WorkoutWords.STRAP_GONE
                        battery != null && labelled -> stringResource(R.string.workout_live_status_strap_battery, battery)
                        else -> stringResource(R.string.workout_live_status_strap)
                    }
                    val lines = status.takeIf { !r.paused }.orEmpty() + (strap to if (connected) TrendGood else TrendBad)
                    if (time != null && smallTime) {
                        // The total, small, where a set or a round's clock is the screen's number.
                        ClockText(time, GlowType.sentence.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                        // Under it, the GPS where there is one (run reps), else that it's the total.
                        val total = stringResource(R.string.workout_live_status_total)
                        val (under, dot) = status.firstOrNull() ?: (total to if (connected) TrendGood else TrendBad)
                        StatusDot(under, dot, dotAfter = true)
                    } else lines.forEach { (text, dot) -> StatusDot(text, dot, dotAfter = true) }
                }
            }
            val bigTime = time.takeIf { !smallTime }
            if (landscape) {
                LandscapePanes(
                    r, connected, controls, bigTime, bottomRoom != null, paused,
                    frameControls = { FrameControls(r, controls, lap, labelled, paused != null, quiet, tapHint, pillControls) },
                    content = content,
                )
                return@WorkoutPage
            }
            if (bigTime != null) MovingTime(bigTime)
            if (r.paused && paused != null) paused() else content()
            if (bottomRoom != null && !r.paused) {
                Spacer(Modifier.height(bottomRoom))
                return@WorkoutPage
            }
            if (!connected && !r.paused) {
                PageNote(WorkoutCopy.STRAP_GONE_NOTE)
            }
            r.endPrompt?.let { at -> StoppedPrompt(r, at, controls.onEndAtPrompt, controls.onKeepGoing) }
            if (!fill || r.paused) Spacer(Modifier.weight(1f))
            FrameControls(r, controls, lap, labelled, paused != null, quiet, tapHint, pillControls)
        }
    }
}

/**
 * The live screen is on its side and whole (not half of a folded phone): [LiveFrame] puts the controls beside the
 * content instead of under it. Set by [GlowWorkoutLiveScreen].
 */
@Suppress("CompositionLocalAllowlist", "ComposeCompositionLocalUsage")
internal val LocalLiveLandscape = staticCompositionLocalOf { false }

/** The moving time large under the top bar, or over the controls on a phone on its side. */
@Composable
internal fun MovingTime(time: () -> Long) =
    // Plain digits: the screen's one dot-matrix number is further down.
    LiveStat(stringResource(R.string.workout_live_moving_time), Modifier.fillMaxWidth()) { ClockText(time, LiveClock) }

/**
 * A phone on its side (or an unfolded one) is too short for the screen above its controls: the controls take a column
 * on the right, under the moving time when it's large, and the screen's own [content] the rest, scrolling only when
 * the height can't hold it, so the buttons never cover it. The strap note and the stopped prompt go first on the left,
 * where they're seen. A page under a shared bar ([sharedBar]) leaves the right to that bar ([LiveBottomBar]).
 */
@Composable
private fun ColumnScope.LandscapePanes(
    r: WorkoutState.Recording,
    connected: Boolean,
    controls: LiveControls,
    time: (() -> Long)?,
    sharedBar: Boolean,
    paused: (@Composable ColumnScope.() -> Unit)?,
    frameControls: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Paused, a page under the shared bar is the whole screen, as it is upright.
    val bar = sharedBar && !r.paused
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val side = landscapeControlsWidth(maxWidth)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(LandscapeGap)) {
            ScrollPane(Modifier.weight(1f).fillMaxHeight(), Arrangement.spacedBy(14.dp)) {
                // Beside a shared bar the moving time stays over the page, as it is upright.
                if (bar && time != null) MovingTime(time)
                if (!bar && !connected && !r.paused) PageNote(WorkoutCopy.STRAP_GONE_NOTE)
                if (!bar) r.endPrompt?.let { at -> StoppedPrompt(r, at, controls.onEndAtPrompt, controls.onKeepGoing) }
                if (r.paused && paused != null) paused() else content()
            }
            if (bar) return@Row
            // Centred in the height, and like the content, scrolling rather than cut on a screen too short for it.
            val centred = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
            ScrollPane(Modifier.width(side).fillMaxHeight(), centred, Alignment.CenterHorizontally) {
                if (time != null) MovingTime(time)
                frameControls()
            }
        }
    }
}

/**
 * A pane of a phone on its side: [content] at least the pane's height, so the screens' weights still share it out
 * when everything fits, and scrolling when the height can't hold it, so nothing is cut.
 */
@Composable
private fun ScrollPane(
    modifier: Modifier,
    verticalArrangement: Arrangement.Vertical,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment, content = content,
        )
    }
}

/**
 * A short phone on its side ([compact] and [LocalLiveLandscape]): its content pane scrolls rather than squeeze, so a
 * part that takes the height left over ([Modifier.weight]) keeps its own height instead, or it would be squeezed to
 * nothing under its neighbours.
 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun shortLandscape(compact: Boolean): Boolean = compact && LocalLiveLandscape.current

/** How wide the controls' column is on a phone on its side: the controls row's own width where there's room. */
internal fun landscapeControlsWidth(width: Dp): Dp = (width * CONTROLS_SHARE).coerceIn(260.dp, 360.dp)

private const val CONTROLS_SHARE = 0.42f

/** Between the content and the controls' column on a phone on its side. */
private val LandscapeGap = 24.dp

/**
 * The controls at the foot of the screen, or in their own column on a phone on its side: End workout and Resume when
 * [hasPaused] shows the paused view, the two quiet buttons ([quiet]), Pause and the hold to end as pills
 * ([pillControls]), or else the controls row.
 */
@Composable
private fun ColumnScope.FrameControls(
    r: WorkoutState.Recording, controls: LiveControls, lap: (@Composable () -> Unit)?, labelled: Boolean, hasPaused: Boolean,
    quiet: Boolean, tapHint: Boolean, pillControls: Boolean,
) {
    when {
        r.paused && hasPaused -> {
            Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 420.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldPill(stringResource(R.string.workout_live_end_workout), Modifier.weight(1f), controls.onEnd)
                WorkoutButton(
                    stringResource(R.string.workout_live_resume), Color.White, Color.Black, controls.onResume, Modifier.weight(1f),
                    icon = AppIcons.PlayFill, height = 64.dp,
                )
            }
            ControlsHint(stringResource(R.string.workout_live_paused_hint))
            Spacer(Modifier.height(20.dp))
        }
        quiet -> {
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                // With no [paused] view, a paused quiet screen keeps this row, so it turns into Resume.
                val label = stringResource(if (r.paused) R.string.workout_live_resume else R.string.workout_live_pause)
                Labelled(label, true) {
                    FlatButton(Raised, 64.dp, onClick = if (r.paused) controls.onResume else controls.onPause) {
                        Icon(if (r.paused) AppIcons.PlayFill else AppIcons.PauseFill, label, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Labelled(stringResource(R.string.workout_live_hold_to_end), true) { HoldToEnd(controls.onEnd) }
            }
            if (tapHint) ControlsHint(stringResource(R.string.workout_live_tap_to_light), alpha = 0.45f)
            Spacer(Modifier.height(8.dp))
        }
        pillControls -> {
            Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 420.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WorkoutButton(
                    stringResource(R.string.workout_live_pause), Raised, Color.White, controls.onPause, Modifier.weight(1f),
                    icon = AppIcons.PauseFill, height = 52.dp,
                )
                HoldPill(stringResource(R.string.workout_live_hold_to_end), Modifier.weight(1f), controls.onEnd, height = 52.dp)
            }
            Spacer(Modifier.height(8.dp))
        }
        else -> {
        ControlsRow(r, controls, lap, labelled, Modifier.align(Alignment.CenterHorizontally))
        if (!labelled) ControlsHint(stringResource(R.string.workout_live_hold_square_hint))
        // A little room under the controls, so they sit up off the screen's edge.
        Spacer(Modifier.height(20.dp))
        }
    }
}

/** A line under the controls saying how to use them, centred. Quieter on the dimmed mind screen ([alpha]). */
@Composable
private fun ControlsHint(text: String, alpha: Float = 0.6f) = Text(
    text, color = Color.White.copy(alpha = alpha), style = GlowType.secondary,
    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
)

/**
 * The controls row, in the same place on every live screen: [lap] on the left (or room for it), Pause or Resume in the
 * middle and largest, and the square to hold to end, [labelled] under each on the screens built for each sport.
 */
@Composable
internal fun ControlsRow(r: WorkoutState.Recording, controls: LiveControls, lap: (@Composable () -> Unit)?, labelled: Boolean, modifier: Modifier = Modifier) {
    // At most a thumb's reach wide, so on a wide screen (an unfolded or half-folded phone) Lap and End stay by Pause.
    Row(modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Top) {
        val pauseLabel = stringResource(if (r.paused) R.string.workout_live_resume else R.string.workout_live_pause)
        // Pause stays centred with or without the left button. The left button labels itself ([LapButton]).
        if (lap != null) lap() else Spacer(Modifier.size(64.dp))
        Spacer(Modifier.weight(1f))
        Labelled(pauseLabel, labelled) {
            FlatButton(Color.White, 88.dp, onClick = if (r.paused) controls.onResume else controls.onPause) {
                Icon(if (r.paused) AppIcons.PlayFill else AppIcons.PauseFill, pauseLabel, tint = Color.Black, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Labelled(stringResource(R.string.workout_live_hold_to_end), labelled) { HoldToEnd(controls.onEnd) }
    }
}

/** A control on the controls row, with its [label] under it on the screens that label them ([show]). */
@Composable
private fun Labelled(label: String, show: Boolean, button: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Every button sits in Pause's height, so the row's buttons stay centred on one line and the labels on another.
        Box(Modifier.height(88.dp), contentAlignment = Alignment.Center) { button() }
        if (show) Text(label, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, maxLines = 1)
    }
}

/** The controls row's left button: Lap, which marks a lap, or Skip ([skip]), with its label on the labelled screens. */
@Composable
internal fun LapButton(onLap: () -> Unit, label: Boolean = false, skip: Boolean = false) {
    val text = stringResource(if (skip) R.string.workout_live_skip else R.string.workout_live_lap)
    Labelled(text, label) {
        FlatButton(Raised, 64.dp, onClick = onLap) {
            Icon(if (skip) AppIcons.SkipFill else SportIcons.Lap, text, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * End workout on the paused screen: a pill that ends only once held for [HOLD_MS], filling with red as it's held, so
 * a pocket still can't end a workout.
 */
@Composable
private fun HoldPill(label: String, modifier: Modifier, onEnd: () -> Unit, height: androidx.compose.ui.unit.Dp = 64.dp) {
    val progress = remember { Animatable(0f) }
    val haptics = rememberHaptics()
    val described = stringResource(R.string.workout_live_hold_to_end_description)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Row(
        modifier.height(height).clip(shape).background(Raised)
            .drawBehind { drawRect(EndRed.copy(alpha = 0.35f), size = size.copy(width = size.width * progress.value)) }
            .pointerInput(Unit) {
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
            }
            .semantics { contentDescription = described },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp)) { drawCircle(EndRed) }
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, style = GlowType.body, maxLines = 1)
    }
}


/**
 * What the run's views share under their pager, fixed while the views slide: the strap note, the stopped prompt, the
 * page dots following the swipe ([position]) and the controls. [onHeight] reports its height above the bottom padding.
 * On a phone on its side ([LocalLiveLandscape]) it's a column beside the pager instead, as wide as [modifier] makes it.
 */
@Composable
internal fun LiveBottomBar(
    r: WorkoutState.Recording, connected: Boolean, contentPadding: PaddingValues, controls: LiveControls, lap: (@Composable () -> Unit)?,
    /** The pager's page plus how far it's swiped, read here so a swipe composes only the bar again. */
    position: () -> Float, count: Int, onPage: (Int) -> Unit, onHeight: (Dp) -> Unit, modifier: Modifier = Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val items: @Composable ColumnScope.() -> Unit = {
        if (!connected) PageNote(WorkoutCopy.STRAP_GONE_NOTE)
        r.endPrompt?.let { at -> StoppedPrompt(r, at, controls.onEndAtPrompt, controls.onKeepGoing) }
        val at = position()
        val view = stringResource(R.string.workout_live_view_of, kotlin.math.round(at).toInt() + 1, count)
        Box(Modifier.fillMaxWidth().semantics { contentDescription = view }) {
            PageDots(kotlin.math.round(at).toInt(), count, at, onPage)
        }
        ControlsRow(r, controls, lap, labelled = true, Modifier.align(Alignment.CenterHorizontally))
    }
    if (LocalLiveLandscape.current) {
        val end = contentPadding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current) + GlowLayout.Gutter
        // Centred in the height, scrolling only when the stopped prompt makes it taller than the screen.
        ScrollPane(
            modifier.fillMaxHeight().statusBarsPadding()
                .padding(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding(), end = end),
            Arrangement.spacedBy(14.dp, Alignment.CenterVertically), Alignment.CenterHorizontally, items,
        )
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val side = org.openstrap.app.ui.glow.GlowLayout.pageSide(maxWidth, org.openstrap.app.ui.glow.LocalPageColumn.current, org.openstrap.app.ui.glow.GlowLayout.Gutter)
        Column(
            Modifier.fillMaxWidth().padding(bottom = contentPadding.calculateBottomPadding())
                .onSizeChanged { onHeight(with(density) { it.height.toDp() }) }
                .padding(start = side, end = side, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = items,
        )
    }
}

/**
 * Two small dots for the live views, the current one white; tapping one goes there (the map keeps its own drags).
 * With [position] (the pager's page plus how far it's swiped) the white passes from one dot to the next as it slides.
 */
@Composable
internal fun PageDots(current: Int, count: Int, position: Float? = null, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier.size(28.dp).clip(CircleShape).pressClickable({ onSelect(i) }, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val lit = if (position != null) (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f) else if (i == current) 1f else 0f
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f + 0.7f * lit)))
            }
        }
    }
}

@Composable
internal fun LiveStat(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        value()
        Text(label, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary)
    }
}

/** The thin rule between the live figures. */
@Composable
internal fun StatRule() = Box(Modifier.padding(vertical = 6.dp).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.12f)))

/** Avg and Max in the same face and size as the moving time beside them, or the dim dash at the same height. */
@Composable
internal fun LiveValue(text: String?) =
    if (text == null) WaitingDash(Modifier.padding(vertical = 13.5.dp))
    else Text(text, color = Color.White, style = LiveFigure)

/**
 * A round button in the glow language: a small glow card lit from its rim in [color], its icon on the dark core.
 * [onClick] null leaves the touch handling to [modifier] (the end square's hold).
 */
@Composable
internal fun FlatButton(container: Color, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    // Flat, like the app's Start and Done: the live screen's card already carries the glow.
    Box(
        modifier.size(size).clip(CircleShape).background(container)
            .then(if (onClick != null) Modifier.pressClickable(onClick, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** "Looks like you've stopped": end where the wearer stopped, or keep going. The strap has buzzed already. */
@Composable
internal fun StoppedPrompt(r: WorkoutState.Recording, at: Long, onEnd: () -> Unit, onKeep: () -> Unit) {
    QuietPanel {
        GlowHeader(stringResource(if (r.inCar) R.string.workout_live_stopped_car_title else R.string.workout_live_stopped_title))
        val sport = sportNoun(r.workout.sportType) ?: stringResource(R.string.glow_today_workout_noun)
        PageNote(
            stringResource(if (r.inCar) R.string.workout_live_stopped_car_note else R.string.workout_live_stopped_note, clock(at), sport),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                WorkoutButton(stringResource(R.string.workout_live_stopped_end_at, clock(at)), Color.White, Color.Black, onEnd)
            }
            Box(Modifier.weight(1f)) { WorkoutButton(stringResource(R.string.workout_live_keep_going), Raised, Color.White, onKeep) }
        }
    }
}

/** The end square needs a one-second hold: a ring fills around it, and letting go early cancels it. */
@Composable
internal fun HoldToEnd(onEnd: () -> Unit) {
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
            Icon(
                AppIcons.StopFill, stringResource(R.string.workout_live_hold_to_end_description), tint = EndRed,
                modifier = Modifier.size(20.dp),
            )
        }
        Canvas(Modifier.size(64.dp).padding(2.dp)) {
            if (progress.value > 0f) drawArc(EndRed, -90f, 360f * progress.value, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

internal val EndRed = MetricColors.heart
private const val HOLD_MS = 2000

/** A live clock in white, its own scope: the second it reads composes it again, not the screen around it. */
@Composable
internal fun ClockText(time: () -> Long, style: androidx.compose.ui.text.TextStyle, maxLines: Int = Int.MAX_VALUE) {
    Text(workoutClock(time()), color = Color.White, style = style, maxLines = maxLines)
}

/** Now in epoch seconds, ticking on each whole second: the clock every live screen counts from. */
@Composable
internal fun nowTicking(): Long = nowTickingState().value

/** [nowTicking] as state, read only where the second shows, so what holds it isn't composed again each second. */
@Composable
internal fun nowTickingState(): androidx.compose.runtime.State<Long> = produceState(System.currentTimeMillis() / 1000) {
    while (true) {
        value = System.currentTimeMillis() / 1000
        delay(1000 - System.currentTimeMillis() % 1000)
    }
}

/**
 * The live heart rate on a pill lit in the zone's [color] (pattern D5): a dot beating at it, the bpm in small dots and
 * the zone named when [maxHr] is given, all in black on the colour. The pace page's strip and the map's chip, [height]
 * apart. Paused or without a reading, it says so.
 */
@Composable
internal fun LitHeartPill(bpm: Int?, maxHr: Int?, color: Color, paused: Boolean, height: Dp, modifier: Modifier = Modifier) {
    val heartZones = LocalHeartZones.current
    Row(
        modifier.height(height).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bpm == null) {
            Text(WorkoutCopy.noHeartRate(paused), color = Color.Black, style = MediumBody)
            return@Row
        }
        org.openstrap.app.ui.glow.PulseDot(Color.Black, bpm, 8.dp)
        Spacer(Modifier.width(10.dp))
        DotMatrixText("$bpm", dot = DotSize.Edge, color = Color.Black)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.workout_live_unit_bpm), color = Color.Black, style = MediumBody)
        if (maxHr == null) return@Row
        Spacer(Modifier.weight(1f))
        WorkoutRecorder.liveZone(bpm, maxHr, heartZones)?.let { z ->
            Text(WorkoutCopy.zoneLine(z), color = Color.Black, style = MediumBody, maxLines = 1)
        }
    }
}
