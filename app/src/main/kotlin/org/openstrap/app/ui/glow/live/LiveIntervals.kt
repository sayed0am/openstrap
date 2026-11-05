package org.openstrap.app.ui.glow.live

import org.openstrap.app.presentation.WorkoutColors
import org.openstrap.app.presentation.dotFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.Phase
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.db.AppWorkoutRound
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.HeroValue
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.PictureCard
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PeakBars
import org.openstrap.app.ui.glow.Segment
import org.openstrap.app.ui.glow.SegmentBar
import org.openstrap.app.ui.glow.Raised
import org.openstrap.app.ui.glow.WorkoutButton
import org.openstrap.app.ui.glow.activeSecondsState
import org.openstrap.app.ui.glow.workoutClock
import org.openstrap.app.ui.glow.dotClock
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.IntervalMoment
import org.openstrap.app.ui.workout.StartCopy
import org.openstrap.app.presentation.WorkoutCopy
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.workout.intervalMoment
import org.openstrap.app.ui.dashboard.LocalHeartZones
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R

/** Work red and rest green, the intervals' two colours. */
private val WorkRed = MetricColors.heart
private val RestGreen = Color(WorkoutColors.REST_GREEN)

/**
 * HIIT (workouts spec 2.5, boards HiitWork and HiitRest), with the total small on top and the screen in the phase's
 * colour. Work: round N of M as dots, what's next, the seconds left large in dots, the heart rate and zone; Skip on the
 * left moves to rest (Done, every minute). Rest: what's done and next, the seconds left, the heart rate and its drop
 * since work ended, each round's peak so far, and +10 s. A warm up waits for Start rounds. With [split] the heart rate
 * is on the other half of a folded phone, so this half leaves it out, and [compact] (a short half) puts the seconds
 * left on one row with the phase and +10 s.
 */
@Composable
internal fun LiveIntervalsScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    readings: List<Pair<Long, Int>>,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    controls: LiveControls,
    onSkip: () -> Unit,
    onAddRest: () -> Unit,
    split: Boolean = false,
    compact: Boolean = false,
) {
    val heartZones = LocalHeartZones.current
    val now = nowTickingState()
    val connected = connection is ConnectionState.Connected
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    ZoneTick(bpm, r)
    // The phase alone colours the screen and names the left button; the seconds left are read under it ([intervalMoment]
    // in the content), so the screen holds still between seconds.
    val phase = androidx.compose.runtime.remember(r) { androidx.compose.runtime.derivedStateOf { r.phase(now.value) } }.value
    if (r.structure == null || phase == null) return
    val color = when {
        r.paused -> org.openstrap.app.ui.glow.PausedColor
        phase is Phase.Work -> WorkRed
        phase is Phase.Rest -> RestGreen
        else -> org.openstrap.app.ui.glow.workoutColor(r, liveHeartRate, connected, heartZones)
    }
    // Every minute's work ends on Done, which shows a tick. The rest skip.
    val skipDone = phase is Phase.Work && r.structure is org.openstrap.app.data.Structure.EveryMinute
    val skipLabel = when (phase) {
        is Phase.Work -> stringResource(if (skipDone) R.string.workout_live_done_button else R.string.workout_live_skip)
        is Phase.Rest -> stringResource(R.string.workout_live_skip_rest)
        else -> null
    }
    // Parsed once with the recording, not on each recomposition.
    val structure = r.structure
    val plan = structure?.let { StartCopy.structureName(it) }
    val active = activeSecondsState(r)
    LiveFrame(
        r, color, connected, contentPadding, controls, time = { active.value }, smallTime = true,
        lap = skipLabel?.takeIf { !r.paused }?.let { label -> { SkipButton(label, skipDone, onSkip) } },
        paused = { LivePaused(r, liveHeartRate.takeIf { connected }, readings, listOf(
            "${org.openstrap.app.data.Phases.roundsDone(r.rounds)}" to stringResource(R.string.workout_live_figure_rounds_done),
            r.rounds.mapNotNull { it.peakBpm }.maxOrNull()?.toString() to stringResource(R.string.workout_live_figure_peak_bpm),
        )) },
        fill = true,
        title = listOfNotNull(org.openstrap.app.data.Workouts.sportName(r.workout.sportType), plan).joinToString(", "),
        backdrop = when {
            r.paused -> null
            phase is Phase.Work -> WorkGlow
            phase is Phase.Rest -> RestGlow
            else -> null
        },
    ) {
        val m = intervalMoment(r, now.value, bpm, readings, emptyList()) ?: return@LiveFrame
        RoundBar(m, when (structure) {
            is org.openstrap.app.data.Structure.Timed -> structure.work
            is org.openstrap.app.data.Structure.EveryMinute -> structure.period
            else -> null
        })
        when (m.phase) {
            is Phase.Work -> PhaseFigure(
                stringResource(R.string.workout_live_round_of, m.round, m.rounds),
                m.nextRest?.let { stringResource(R.string.workout_live_then_rest, StartCopy.restText(it)) }
                    ?: stringResource(R.string.workout_live_last_round),
                stringResource(R.string.workout_live_phase_work), m.left?.let { dotFormat("%02d", it) },
                stringResource(if (m.everyMinute) R.string.workout_live_seconds_left_minute else R.string.workout_live_seconds_left),
                compact,
            )
            is Phase.Rest -> PhaseFigure(
                stringResource(R.string.workout_live_done_of, m.round - 1, m.rounds),
                stringResource(R.string.workout_live_round_next, m.round),
                stringResource(R.string.workout_live_phase_rest), m.left?.let { dotFormat("%02d", it) },
                bpm?.let { b ->
                    m.drop?.takeIf { it > 0 }?.let { stringResource(R.string.workout_live_heart_rate_down, b, it) }
                        ?: stringResource(R.string.workout_live_heart_rate, b)
                } ?: stringResource(R.string.workout_live_seconds_left),
                compact,
                // Folded, +10 s sits beside the seconds: the half has no room for a row of its own.
                action = {
                    val more = stringResource(R.string.workout_live_add_10_s)
                    WorkoutButton(more, OverGlow, Color.White, onAddRest, Modifier.width(120.dp), height = 44.dp)
                },
            )
            Phase.WarmUp -> PhaseFigure(
                stringResource(R.string.workout_live_warm_up),
                pluralStringResource(R.plurals.workout_live_rounds_after, m.rounds, m.rounds),
                stringResource(R.string.workout_live_phase_warming_up), null,
                stringResource(R.string.workout_live_rounds_start_hint), compact,
            )
            Phase.Done -> PhaseFigure(
                pluralStringResource(R.plurals.workout_live_all_rounds_done, m.rounds, m.rounds),
                stringResource(R.string.workout_live_end_or_keep),
                stringResource(R.string.workout_live_phase_done), null, "", compact,
            )
        }
        if (!split && (m.phase is Phase.Rest || m.phase == Phase.Done)) RoundPeaks(r.rounds, m.rounds)
        if (!split && m.phase !is Phase.Rest) HeartStripDark(bpm, r.maxHeartRate, r.paused, Modifier.align(Alignment.CenterHorizontally))
        val tall = if (compact) 48.dp else 64.dp
        when (m.phase) {
            Phase.WarmUp -> WorkoutButton(
                stringResource(R.string.workout_live_start_rounds), Color.White, Color.Black, onSkip, icon = AppIcons.PlayFill,
                height = tall,
            )
            // A pill as high as the heart rate strip and, like it, no wider than its words need.
            is Phase.Rest -> if (!compact) WorkoutButton(
                stringResource(R.string.workout_live_add_10_s), OverGlow, Color.White, onAddRest,
                Modifier.align(Alignment.CenterHorizontally).width(140.dp), height = 52.dp,
            )
            is Phase.Work -> if (m.everyMinute) WorkoutButton(
                stringResource(R.string.workout_live_done_button), Color.White, Color.Black, onSkip, icon = AppIcons.Tick02, height = tall,
            )
            Phase.Done -> Unit
        }
    }
}

/** A see-through button colour that reads both on the phase's light and on black. */
private val OverGlow = Color.White.copy(alpha = 0.16f)

/**
 * The phase's light over the whole screen (boards HiitWork and HiitRest): work rises from the bottom, rest falls from
 * the top, strongest at the edge and gone by the middle, so the seconds stay on black.
 */
// Made once: a new brush each second would draw the backdrop again.
private val WorkGlow by lazy { phaseGlow(WorkRed, fromTop = false) }
private val RestGlow by lazy { phaseGlow(RestGreen, fromTop = true) }

private fun phaseGlow(color: Color, fromTop: Boolean) = object : ShaderBrush() {
    override fun createShader(size: Size) = RadialGradientShader(
        Offset(size.width / 2f, if (fromTop) -size.height * 0.08f else size.height * 1.08f),
        size.height * if (fromTop) 0.7f else 0.88f,
        // A white-hot core in the phase's colour, as on the boards.
        listOf(lerp(color, Color.White, 0.6f), color.copy(alpha = 0.85f), color.copy(alpha = 0.3f), Color.Transparent),
        listOf(0f, 0.3f, 0.62f, 1f),
    )
}

/** Skip, Done ([done], with a tick) or Skip rest on the controls row's left, labelled under it. */
@Composable
private fun SkipButton(label: String, done: Boolean, onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.height(88.dp), contentAlignment = Alignment.Center) {
            FlatButton(OverGlow, 64.dp, onClick = onSkip) {
                Icon(if (done) AppIcons.Tick02 else AppIcons.SkipFill, label, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Text(label, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, maxLines = 1)
    }
}

/**
 * The rounds as a segmented bar: done white, the one under way filling as its [workLength] passes (or by [underWay],
 * a run rep's share of its metres), those to go faint.
 */
@Composable
internal fun RoundBar(m: IntervalMoment, workLength: Long?, underWay: Float? = null) {
    val done = when (m.phase) {
        is Phase.Work, is Phase.Rest -> m.round - 1
        Phase.Done -> m.rounds
        Phase.WarmUp -> 0
    }
    // How far into the round under way: the work's seconds gone of [workLength], when it runs on a clock.
    val under = if (m.phase is Phase.Work && underWay != null) underWay.coerceIn(0f, 1f)
    else if (m.phase is Phase.Work && m.left != null && workLength != null && workLength > 0) {
        (1f - m.left.toFloat() / workLength).coerceIn(0f, 1f)
    } else 0f
    val described = pluralStringResource(R.plurals.workout_live_rounds_done_description, m.rounds, done, m.rounds)
    SegmentBar(
        List(m.rounds.coerceIn(1, MAX_ROUNDS_SHOWN)) { i ->
            Segment(1f, Color.White.copy(alpha = if (i < done) 1f else 0.25f), filled = if (i == done) under else 0f, fill = Color.White)
        },
        Modifier.semantics { contentDescription = described }, height = 6.dp, gap = 6.dp,
    )
}

/** The most rounds the bar splits into, so each stays wide enough to read. */
private const val MAX_ROUNDS_SHOWN = 20

/**
 * A phase: where you are and what's next, its word large, the seconds in the screen's one dotted number, a line under.
 * [compact] (half a folded phone, or a phone on its side) puts the word, the seconds and the phase's [action] side by
 * side, smaller, with no line.
 */
@Composable
private fun ColumnScope.PhaseFigure(
    where: String, next: String, word: String, seconds: String?, line: String, compact: Boolean = false, action: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(where, color = Color.White, style = GlowType.body, modifier = Modifier.weight(1f))
        Text(next, color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary)
    }
    if (compact) {
        Row(Modifier.fillMaxWidth().then(if (shortLandscape(compact)) Modifier else Modifier.weight(1f)), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Text(word.uppercase(), color = Color.White, style = GlowType.phaseWord, maxLines = 1)
            if (seconds != null) DotMatrixText(seconds, dot = DotSize.Detail)
            action?.invoke()
        }
        return
    }
    Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Text(word.uppercase(), color = Color.White, style = GlowType.phaseWord)
        if (seconds != null) DotMatrixText(seconds, dot = DotSize.Live, scale = 1.4f)
        if (line.isNotEmpty()) Text(line, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, textAlign = TextAlign.Center)
    }
}

/** The heart rate on a black pill as wide as its words, with the zone's dot and name: the phase's colour is all around it. */
@Composable
private fun HeartStripDark(bpm: Int?, maxHr: Int, paused: Boolean, modifier: Modifier = Modifier) {
    val heartZones = LocalHeartZones.current
    Row(
        modifier.height(52.dp).clip(CircleShape).background(Color.Black).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val zone = WorkoutRecorder.liveZone(bpm, maxHr, heartZones)
        Canvas(Modifier.size(8.dp)) { drawCircle(zone?.let { ZONE_COLORS[it] } ?: Color.White) }
        Spacer(Modifier.width(10.dp))
        Text(
            bpm?.let { stringResource(R.string.workout_live_bpm, it) } ?: WorkoutCopy.noHeartRate(paused), color = Color.White,
            style = GlowType.body,
        )
        zone?.let { Spacer(Modifier.width(16.dp)); Text(WorkoutCopy.zoneLine(it), color = Color.White, style = GlowType.secondary, maxLines = 1) }
    }
}

/**
 * Each round's peak heart rate so far as bars in the peak's zone colour, rounds to come as faint stubs, with how far
 * the heart rate came down in each rest.
 */
@Composable
private fun RoundPeaks(rounds: List<AppWorkoutRound>, total: Int) {
    val peaks = rounds.filter { it.workEnd != null }.map { it.peakBpm }
    if (peaks.all { it == null }) return
    val shown = peaks.filterNotNull().joinToString(", ")
    val described = stringResource(R.string.workout_live_peaks_description, shown)
    PictureCard(WorkRed, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.workout_live_peaks_title), color = Color.White, style = GlowType.cardTitle, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        PeakBars(
            peaks, { MetricColors.heart },
            Modifier.semantics { contentDescription = described },
            height = 56.dp, rounds = total, corner = 3.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.workout_live_peaks_bpm, shown), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
            style = GlowType.secondary,
        )
    }
}

/**
 * A run rep (workouts spec 2.5, board RunIntervals), in place of the Pace card: rep N of M, the metres to go (or the
 * seconds of a time rep) in dots, the rep's pace now, and the reps done with their times. Rest shows its time left.
 */
@Composable
internal fun RepCard(r: WorkoutState.Recording, route: List<Route.Point>, readings: List<Pair<Long, Int>>, bpm: Int?) {
    val now = nowTicking()
    val m = intervalMoment(r, now, bpm, readings, route) ?: return
    val resting = m.phase is Phase.Rest
    PictureCard(if (resting) RestGreen else MetricColors.steps, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                when (m.phase) {
                    is Phase.Work -> stringResource(R.string.workout_live_rep_of, m.round, m.rounds)
                    is Phase.Rest -> stringResource(R.string.workout_live_rest_rep_next, m.round)
                    Phase.WarmUp -> stringResource(R.string.workout_live_warm_up)
                    Phase.Done -> pluralStringResource(R.plurals.workout_live_all_reps_done, m.rounds, m.rounds)
                },
                color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f),
            )
            val pace = if (m.phase is Phase.Work) org.openstrap.app.presentation.Pace.clock(Route.currentPace(route)) else null
            if (pace != null) Text(
                stringResource(R.string.workout_live_pace_now, pace), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                style = GlowType.secondary,
            )
        }
        Spacer(Modifier.height(12.dp))
        val (value, unit) = when {
            m.metersLeft != null && m.phase is Phase.Work -> "${m.metersLeft.toInt()}" to stringResource(R.string.workout_live_unit_m_to_go)
            m.left != null -> dotClock(m.left) to stringResource(
                if (resting) R.string.workout_live_unit_rest_left else R.string.workout_live_unit_left,
            )
            else -> null to ""
        }
        if (value != null) HeroValue(value, unit, dot = DotSize.Detail)
        else Text(
            if (m.phase == Phase.WarmUp) stringResource(R.string.workout_live_reps_start_hint) else "", color = Color.White,
            style = GlowType.body,
        )
        val done = r.rounds.filter { it.workEnd != null }
        if (done.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                done.takeLast(6).joinToString(", ") { "${it.index + 1}: ${workoutClock(it.workEnd!! - it.workStart)}" },
                color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
            )
        }
    }
}
