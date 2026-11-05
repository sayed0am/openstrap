package org.openstrap.app.ui.glow.live

import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.Pace
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.SportTypes
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.components.HapticEvent
import org.openstrap.app.ui.components.rememberHaptics
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.PictureCard
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.MediumBody
import org.openstrap.app.ui.glow.TrendBad
import org.openstrap.app.ui.glow.TrendGood
import org.openstrap.app.ui.glow.activeSecondsState
import org.openstrap.app.ui.glow.workoutColor
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.glow.dotFitScale
import org.openstrap.app.presentation.LiveView
import org.openstrap.app.presentation.WorkoutCopy
import org.openstrap.app.ui.glow.WaitingDash
import org.openstrap.app.ui.glow.workoutClock
import org.openstrap.app.ui.glow.dotClock
import org.openstrap.app.ui.dashboard.LocalHeartZones
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R

/**
 * A distance workout with GPS (workouts spec 2.3, boards Run and Map): two views, swiped, under the moving time. Pace:
 * the pace now large on a card lit in the distance lime, the average beside its title and a bar per kilometre, the one
 * under way growing; the heart rate on a strip in its zone's colour; then distance, climb and active kcal. Map: the
 * route in zone colours with the heart rate on it, then distance, pace and the last split. Opens on the view used last
 * ([liveView], saved through [onLiveView]). A ride shows its speed instead of a pace. With [split] the heart rate is on
 * the other half of a folded phone, so both views leave out their own; [compact] (a short half) keeps the moving time small
 * on top and the distance, pace and kcal in one row.
 */
@Composable
internal fun LiveDistanceScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    recent: List<Pair<Long, Int>>,
    stats: WorkoutRecorder.Stats?,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    controls: LiveControls,
    route: List<Route.Point>,
    history: List<Pair<Long, Int>>,
    onLap: () -> Unit,
    liveView: Int?,
    onLiveView: (Int) -> Unit,
    kcal: WorkoutRecorder.Kcal?,
    /** Run reps: Skip the rep or the rest, or start the reps after a warm up. */
    onSkip: () -> Unit = {},
    split: Boolean = false,
    compact: Boolean = false,
) {
    val heartZones = LocalHeartZones.current
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
    val scope = rememberCoroutineScope()
    val connected = connection is ConnectionState.Connected
    val color = workoutColor(r, liveHeartRate, connected, heartZones)
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    ZoneTick(bpm, r)
    val distance = r.workout.distanceMeters ?: 0f
    val splits = remember(route.size, r.workout.pauseRanges) { Route.splits(route, r.workout.pauseRanges) }
    val climb = remember(route.size) { Route.climb(route) }
    val speed = Workouts.tileType(r.workout.sportType) !in SportTypes.ON_FOOT
    val pace = remember(route) { Route.currentPace(route) }
    val active = activeSecondsState(r)
    // Read where it shows, so the screen holds still between seconds.
    val averagePace = { Pace.of(distance, active.value) }
    val accuracy = route.lastOrNull()?.accuracy
    val reps = r.structure is org.openstrap.app.data.Structure.Reps
    val gps = WorkoutCopy.gpsPill(accuracy) to if (accuracy != null && accuracy <= Route.MAX_ACCURACY_M) TrendGood else TrendBad
    // Kept across recompositions, so the frames and pages that take them can skip while nothing they show changed.
    val status = remember(gps) { listOf(gps) }
    val readings = remember(history, recent) { WorkoutCopy.readings(history, recent) }
    // Reps skip instead of marking laps: the rounds are the laps.
    val lap: (@Composable () -> Unit)? = when {
        r.paused -> null
        reps -> ({ LapButton(onSkip, label = true, skip = true) })
        else -> ({ LapButton(onLap, label = true) })
    }
    val battery = (connection as? ConnectionState.Connected)?.batteryPercent
    if (r.paused) {
        LiveFrame(
            r, color, connected, contentPadding, controls, lap, time = { active.value }, battery = battery, status = status,
            paused = {
                LivePaused(r, liveHeartRate.takeIf { connected }, recent, listOf(
                    Units.km(distance, Units.Precision.Workout) to stringResource(R.string.workout_live_figure_km),
                    (if (speed) speedText(averagePace()) else Pace.clock(averagePace())) to
                        stringResource(if (speed) R.string.workout_live_figure_avg_kmh else R.string.workout_live_figure_avg_pace),
                    stats?.average?.toString() to stringResource(R.string.workout_live_figure_avg_bpm),
                ))
            },
        ) {}
        return
    }
    val onPage: (Int) -> Unit = { scope.launch { pager.animateScrollToPage(it) } }
    // Only what's above the dots slides: the note, the dots and the controls stay put under the pager, the dots
    // following the swipe. A drag on the map pans the map instead.
    val density = LocalDensity.current
    var measured by remember { mutableStateOf(240.dp) }
    // On its side the bar is a column on the right, so the pages leave no room under them but keep clear of it on
    // their right, the pages' glow still across the whole screen.
    val landscape = LocalLiveLandscape.current
    val bar = if (landscape) 0.dp else measured
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = landscapeControlsWidth(maxWidth)
        val clear = if (landscape) side else 0.dp
        val pagePadding = if (!landscape) contentPadding else PaddingValues(
            start = contentPadding.calculateStartPadding(direction), top = contentPadding.calculateTopPadding(),
            end = side, bottom = contentPadding.calculateBottomPadding(),
        )
        val mapStart = if (landscape) pagePadding.calculateStartPadding(direction) else 0.dp
        HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top, beyondViewportPageCount = 1) { page ->
            when (LiveView.entries[page]) {
                // Reps (board RunIntervals): the total small on top, the rep the screen's number.
                LiveView.Heart -> if (reps) LiveFrame(
                    r, color, connected, pagePadding, controls, lap, time = { active.value }, smallTime = true, battery = battery,
                    status = status,
                    fill = true, title = stringResource(R.string.workout_live_reps_title, Workouts.sportName(r.workout.sportType)),
                    bottomRoom = bar,
                ) {
                    RepPage(r, route, readings, bpm, color, compact, if (shortLandscape(compact)) Modifier else Modifier.weight(1f))
                } else LiveFrame(
                    r, color, connected, pagePadding, controls, lap, time = { active.value }, smallTime = compact, battery = battery,
                    status = status, fill = true, bottomRoom = bar,
                ) {
                    if (compact) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        LiveFigures(listOf(
                            Units.km(distance, Units.Precision.Workout) to stringResource(R.string.workout_live_figure_km),
                            (if (speed) speedText(pace) else Pace.clock(pace)) to
                                stringResource(if (speed) R.string.workout_live_unit_kmh else R.string.workout_live_figure_pace_km),
                            kcal?.active?.toInt()?.toString() to stringResource(R.string.workout_live_figure_active_kcal),
                        ))
                    } else Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PaceCard(pace, averagePace, splits, speed, Modifier.weight(1f))
                        if (!split) LitHeartPill(bpm, r.maxHeartRate, color, r.paused, 56.dp, Modifier.fillMaxWidth())
                        LiveFigures(listOf(
                            Units.km(distance, Units.Precision.Workout) to stringResource(R.string.workout_live_figure_km),
                            climb?.toInt()?.toString() to stringResource(R.string.workout_live_figure_climb),
                            kcal?.active?.toInt()?.toString() to stringResource(R.string.workout_live_figure_active_kcal),
                        ))
                    }
                    // Each kilometre gets a line for a few seconds, with one tick. This page stays composed beside the
                    // map, so the tick comes on either.
                    KmSplitLine(route, readings, r.workout.pauseRanges, keepRoom = !compact)
                }
                // On its side the map stops short of the bar, so the controls stay on black, and of the nav bar.
                LiveView.Map -> Box(Modifier.fillMaxSize().padding(start = mapStart, end = clear)) {
                    LiveMapScreen(
                        r, route, readings, bpm, color, contentPadding, controls,
                        figures = listOf(
                            Units.km(distance, Units.Precision.Workout) to stringResource(R.string.workout_live_figure_km),
                            (if (speed) speedText(pace) else Pace.clock(pace)) to
                                stringResource(if (speed) R.string.workout_live_unit_kmh else R.string.workout_live_figure_pace_km),
                        ),
                        time = { active.value }, bottomRoom = bar, heartPill = !split,
                    )
                }
            }
        }
        LiveBottomBar(
            r, connected, contentPadding, controls, lap, { pager.currentPage + pager.currentPageOffsetFraction }, LiveView.entries.size, onPage,
            onHeight = { if (it != measured) measured = it },
            modifier = if (landscape) Modifier.align(Alignment.CenterEnd).width(side) else Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * A run's reps (workouts spec 2.5, board RunIntervals): the plan and a bar of the reps, rep N of M and the rest after
 * it, the metres to go in the screen's one dotted number (the seconds of a time rep) with the rep's own pace, the heart
 * rate strip, then each rep done with its time. A rest shows its seconds left and the rep after it.
 */
@Composable
private fun RepPage(
    r: WorkoutState.Recording, route: List<Route.Point>, readings: List<Pair<Long, Int>>, bpm: Int?, color: Color, compact: Boolean,
    modifier: Modifier,
) {
    val now = nowTicking()
    val m = org.openstrap.app.ui.workout.intervalMoment(r, now, bpm, readings, route) ?: return
    val structure = r.structure as? org.openstrap.app.data.Structure.Reps ?: return
    val (repLength, repUnit) = org.openstrap.app.ui.workout.StartCopy.repParts(structure)
    val repName = stringResource(R.string.workout_live_rep_length, repLength, repUnit)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(
                R.string.workout_live_reps_plan,
                org.openstrap.app.ui.workout.StartCopy.structureName(structure),
                org.openstrap.app.ui.workout.StartCopy.restText(structure.rest),
            ),
            color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary, maxLines = 1,
        )
        val underWay = structure.meters?.let { meters -> m.metersLeft?.let { 1f - it / meters } }
        RoundBar(m, structure.seconds, underWay)
        Row(Modifier.fillMaxWidth()) {
            Text(
                when (m.phase) {
                    is org.openstrap.app.data.Phase.Work -> stringResource(R.string.workout_live_rep_of, m.round, m.rounds)
                    is org.openstrap.app.data.Phase.Rest -> stringResource(R.string.workout_live_done_of, m.round - 1, m.rounds)
                    org.openstrap.app.data.Phase.WarmUp -> stringResource(R.string.workout_live_warm_up)
                    org.openstrap.app.data.Phase.Done -> pluralStringResource(R.plurals.workout_live_all_reps_done, m.rounds, m.rounds)
                },
                color = Color.White, style = MediumBody, maxLines = 1, modifier = Modifier.weight(1f),
            )
            val next = when (m.phase) {
                is org.openstrap.app.data.Phase.Work ->
                    m.nextRest?.let { stringResource(R.string.workout_live_then_rest, org.openstrap.app.ui.workout.StartCopy.restText(it)) }
                        ?: stringResource(R.string.workout_live_last_rep)
                is org.openstrap.app.data.Phase.Rest -> stringResource(R.string.workout_live_rep_next, m.round)
                org.openstrap.app.data.Phase.WarmUp -> pluralStringResource(R.plurals.workout_live_reps_after, m.rounds, m.rounds)
                org.openstrap.app.data.Phase.Done -> ""
            }
            Text(next, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
        }
        Column(
            Modifier.fillMaxWidth().then(if (shortLandscape(compact)) Modifier else Modifier.weight(1f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            val work = m.phase is org.openstrap.app.data.Phase.Work
            val rest = m.phase is org.openstrap.app.data.Phase.Rest
            Text(
                when {
                    work -> stringResource(R.string.workout_live_rep_word, repName)
                    rest -> stringResource(R.string.workout_live_phase_rest)
                    m.phase == org.openstrap.app.data.Phase.WarmUp -> stringResource(R.string.workout_live_phase_warming_up)
                    else -> stringResource(R.string.workout_live_phase_done)
                },
                color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
            )
            val (value, unit) = when {
                work && m.metersLeft != null -> "${m.metersLeft.toInt()}" to stringResource(R.string.workout_live_unit_m_to_go)
                m.left != null -> dotClock(m.left) to stringResource(
                    if (rest) R.string.workout_live_unit_rest_left else R.string.workout_live_unit_left,
                )
                else -> null to ""
            }
            if (value != null) {
                DotMatrixText(value, dot = DotSize.Live, scale = 1.2f)
                Text(unit, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body)
            }
            val line = when {
                work -> Route.currentPace(route)?.let { pace ->
                    structure.meters?.let { meters -> workoutClock((pace * meters / 1000).toLong()) }
                        ?.let { stringResource(R.string.workout_live_rep_pace, it, repName) }
                        ?: Pace.clock(pace)?.let { stringResource(R.string.workout_live_rep_pace_km, it) }
                }
                m.phase == org.openstrap.app.data.Phase.WarmUp -> stringResource(R.string.workout_live_reps_skip_hint)
                m.phase == org.openstrap.app.data.Phase.Done -> stringResource(R.string.workout_live_end_or_keep)
                else -> null
            }
            if (line != null) Text(line, color = Color.White, style = GlowType.sentence, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        LitHeartPill(bpm, r.maxHeartRate, color, r.paused, 56.dp, Modifier.fillMaxWidth())
        val done = r.rounds.filter { it.workEnd != null }
        if (done.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(Int.MAX_VALUE)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            done.forEach { round ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(org.openstrap.app.ui.glow.Raised).padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.workout_live_rep_chip, round.index + 1),
                        color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(workoutClock(round.workEnd!! - round.workStart), color = Color.White, style = MediumBody, maxLines = 1)
                }
            }
        }
    }
}

/** A tick when the zone changes (spec 3.6); a pause forgets the zone, so resuming in another doesn't tick. */
@Composable
internal fun ZoneTick(bpm: Int?, r: WorkoutState.Recording) {
    val heartZones = LocalHeartZones.current
    val haptics = rememberHaptics()
    val zone = WorkoutRecorder.liveZone(bpm, r.maxHeartRate, heartZones)
    var lastZone by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(zone, r.paused) {
        if (WorkoutCopy.zoneTick(lastZone, zone)) haptics.perform(HapticEvent.ZoneChange)
        lastZone = if (r.paused) null else zone ?: lastZone
    }
}

/** km/h from seconds per kilometre, one decimal; null with no pace. */
internal fun speedText(secondsPerKm: Double?, locale: java.util.Locale = java.util.Locale.getDefault()): String? =
    secondsPerKm?.takeIf { it > 0 && it < Pace.SLOWEST_S }?.let { String.format(locale, "%.1f", 3600 / it) }

/**
 * The Pace card: "Pace" with the average beside it, the pace now in the card's one dotted number, and a bar per
 * kilometre under it ([KmSplitBars]). Lit in the distance lime from its edge, so it never looks like the heart strip
 * under it.
 */
@Composable
private fun PaceCard(pace: Double?, averagePace: () -> Double?, splits: List<Route.Split>, speed: Boolean, modifier: Modifier = Modifier) {
    PictureCard(MetricColors.steps, modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(if (speed) R.string.workout_live_speed_title else R.string.workout_live_pace_title), color = Color.White,
                style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f),
            )
            AveragePace(averagePace, speed)
        }
        // The pace sits in the middle of whatever height the card is given, the splits on its floor.
        Spacer(Modifier.height(12.dp).weight(1f))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val room = maxWidth - UnitRoom
            // Centred, as the board has it: the one number the card is for.
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.Bottom) {
                val now = if (speed) speedText(pace, Units.DOT_LOCALE) else Pace.clock(pace, Units.DOT_LOCALE)
                // The live size, shrunk only as far as a walk's 12:30 needs to fit beside its unit.
                val fit = now?.let { dotFitScale(it, DotSize.Live, room).coerceAtLeast(0.5f) } ?: 1f
                if (now != null) DotMatrixText(now, dot = DotSize.Live, scale = fit) else WaitingDash(Modifier.padding(vertical = 18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (speed) R.string.workout_live_unit_kmh else R.string.workout_live_unit_per_km),
                    color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(16.dp).weight(1f))
        if (splits.isNotEmpty()) {
            KmSplitBars(splits)
        }
    }
}

/** "Avg 5:42" beside the card's title: the average moves with the clock, so it's read in its own scope. */
@Composable
private fun AveragePace(averagePace: () -> Double?, speed: Boolean) {
    val average = averagePace()
    val avg = (if (speed) speedText(average) else Pace.clock(average))?.let { stringResource(R.string.workout_live_avg, it) }
    if (avg != null) Text(avg, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
}

/**
 * The last five kilometres as bars, taller for faster, the fastest finished one bright with its time in white and bold,
 * the rest dim, each with its time under it; the one under way is an outlined full bar filling from the bottom, named
 * by its kilometre.
 */
@Composable
private fun KmSplitBars(splits: List<Route.Split>) {
    val shown = splits.takeLast(SPLIT_SLOTS)
    val done = splits.filter { it.meters >= 1000 }
    val fastest = done.minByOrNull { it.pace }
    val slowest = done.maxOfOrNull { it.pace } ?: 0.0
    val quickest = fastest?.pace ?: 0.0
    val described = stringResource(
        R.string.workout_live_splits_description, done.takeLast(4).joinToString(", ") { workoutClock(it.seconds) },
    )
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = described },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.forEach { s ->
            val underWay = s.meters < 1000
            // Faster is taller: from 60 % for the slowest to full for the fastest.
            val height = when {
                underWay -> 1f
                slowest <= quickest -> 1f
                else -> (0.6f + 0.4f * ((slowest - s.pace) / (slowest - quickest)).toFloat()).coerceIn(0.6f, 1f)
            }
            val best = s == fastest
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val lime = MetricColors.steps
                Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                    val h = size.height * height
                    val corner = CornerRadius(5.dp.toPx())
                    if (underWay) {
                        val filled = size.height * (s.meters / 1000).toFloat().coerceIn(0f, 1f)
                        drawRoundRect(lime.copy(alpha = 0.35f), Offset(0f, size.height - filled), Size(size.width, filled), corner)
                        val w = 1.5.dp.toPx()
                        drawRoundRect(
                            lime.copy(alpha = 0.6f), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), corner,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                        )
                    } else drawRoundRect(if (best) lime else lime.copy(alpha = 0.35f), Offset(0f, size.height - h), Size(size.width, h), corner)
                }
                Text(
                    if (underWay) stringResource(R.string.workout_live_split_km, s.index + 1) else workoutClock(s.seconds),
                    color = if (best) Color.White else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.caption.copy(fontWeight = if (best) FontWeight.SemiBold else null), maxLines = 1,
                )
            }
        }
        // Empty slots keep each bar a fifth of the card, so the first kilometre isn't a line across it.
        repeat(SPLIT_SLOTS - shown.size) { Spacer(Modifier.weight(1f)) }
    }
}

private const val SPLIT_SLOTS = 5

/** Room left beside the pace for "km/h" and its gap. */
private val UnitRoom = 56.dp
