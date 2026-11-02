package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.Strength
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.db.AppWorkoutSet
import org.openstrap.app.data.db.ExerciseRow
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.components.HapticEvent
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.components.rememberHaptics
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.DotSize
import org.openstrap.app.ui.glow.EdgeCard
import org.openstrap.app.ui.glow.GlowCard
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowMotion
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.LightAngle
import org.openstrap.app.ui.glow.MediumBody
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageSheet
import org.openstrap.app.ui.glow.QuietPanel
import org.openstrap.app.ui.glow.Raised
import org.openstrap.app.ui.glow.MinusPlus
import org.openstrap.app.ui.glow.RoundIcon
import org.openstrap.app.ui.glow.WorkoutButton
import org.openstrap.app.ui.glow.activeSecondsState
import org.openstrap.app.ui.glow.heroStyle
import org.openstrap.app.ui.glow.workoutClock
import org.openstrap.app.ui.glow.workoutColor
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.workout.WorkoutCopy
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.workout.strengthMoment

/** Strength's rest colour, the green the floating bar and the hero rest in too. */
private val RestGreen = ZONE_COLORS[1]

/**
 * Strength (workouts spec 2.4, boards StrengthSet, StrengthLog and StrengthRest), with the total small on top.
 *
 * In a set: set N of M and its exercise (tap to name or change it), the set's clock in dots on a glow in the zone's
 * colour, the sets as dots, the heart rate and zone, the sets done with their reps, weight, time and peak, and one big
 * Set done. Resting: the rest left in dots on a card lit in rest green, the heart rate and how far it dropped, its line
 * falling toward the level that ends rest sooner, what's next, +30 s and Start set N. The strap buzzes when rest ends.
 *
 * With logging on ([WorkoutState.Recording.logSets]), the log card follows each set while rest runs underneath: reps
 * and kg filled from the last set of the exercise ([Strength.prefill]), Skip or Save, and Next exercise.
 *
 * [compact] (half a phone stood like a laptop, the heart rate on the other half): each card is one row with its clock,
 * and the sets done and what's next are left out, so the buttons stay on screen.
 */
@Composable
internal fun LiveStrengthScreen(
    r: WorkoutState.Recording,
    liveHeartRate: Int?,
    history: List<Pair<Long, Int>>,
    connection: ConnectionState,
    contentPadding: PaddingValues,
    controls: LiveControls,
    exercises: List<ExerciseRow>,
    onStartSet: () -> Unit,
    onSetDone: () -> Unit,
    onExtendRest: () -> Unit,
    onExercise: (String?) -> Unit,
    onLog: (index: Int, exercise: String?, reps: Int, kg: Float) -> Unit,
    compact: Boolean = false,
) {
    val now = nowTickingState()
    val connected = connection is ConnectionState.Connected
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    ZoneTick(bpm, r)
    val color = if (r.resting && !r.paused) RestGreen else workoutColor(r, liveHeartRate, connected)
    // Read where it shows (the cards' clocks, the paused figures, the log card), so the screen holds still between
    // seconds. The rest of the screen doesn't move with the clock: [steady] is the moment without its seconds, which
    // only the clocks read, so it needs no time at all.
    val momentNow = { strengthMoment(r, now.value, bpm, history) }
    val steady = remember(r, bpm, history) { strengthMoment(r, 0, bpm, history).copy(setSeconds = 0, restLeft = null) }
    val haptics = rememberHaptics()
    var naming by rememberSaveable { mutableStateOf(false) }
    // Sets whose log card was skipped, so it doesn't come back.
    val skipped = rememberSaveable(r.workout.start) { mutableStateListOf<Int>() }
    val unlogged = r.sets.lastOrNull { it.end != null }?.takeIf { r.logSets && it.reps == null && it.index !in skipped && !r.inSet }
    val active = activeSecondsState(r)
    LiveFrame(
        r, color, connected, contentPadding, controls, time = { active.value }, smallTime = true, pillControls = true, fill = true,
        paused = { LivePaused(r, liveHeartRate.takeIf { connected }, history, listOf(
            momentNow().done.let { done -> done.toString() to if (done == 1) "Set done" else "Sets done" },
            Strength.kgLifted(r.sets)?.let { Strength.kgText(it) } to "kg lifted",
            r.workout.maxHeartRate?.toString() to "Peak bpm",
        )) },
    ) {
        val moment = steady
        val tall = if (compact) 56.dp else 72.dp
        if (compact) CompactCard(r, moment, momentNow, color, onName = { naming = true }) else when {
            r.inSet -> SetCard(r, moment.setNumber, { momentNow().setSeconds }, bpm, color) { naming = true }
            r.resting -> RestCard(r, { momentNow().restLeft ?: 0 }, moment.done, bpm, moment.drop, history)
            else -> ReadyCard(r, moment.setNumber, color) { naming = true }
        }
        if (!compact && r.resting && !r.inSet) NextLine(moment.setNumber, r.exercise, moment.nextLoad)
        if (!compact) DoneSets(r.sets, history)
        Spacer(Modifier.weight(1f))
        when {
            r.inSet -> WorkoutButton("Set done", Color.White, Color.Black, { haptics.perform(HapticEvent.SetDone); onSetDone() }, icon = AppIcons.Tick02, height = tall)
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (r.resting) Box(Modifier.width(108.dp)) { WorkoutButton("+30 s", Raised, Color.White, onExtendRest, height = tall) }
                Box(Modifier.weight(1f)) { WorkoutButton("Start set ${moment.setNumber}", Color.White, Color.Black, onStartSet, icon = AppIcons.PlayFill, height = tall) }
            }
        }
        val target = r.workout.setsTarget
        if (!compact && target != null && moment.done >= target && !r.inSet) {
            Text("All $target sets done. Hold the square to end, or keep going.", color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
    if (unlogged != null) LogCard(
        unlogged, r.sets, exercises, history, restLeft = { momentNow().restLeft },
        onSave = { exercise, reps, kg -> onLog(unlogged.index, exercise, reps, kg) },
        onSkip = { skipped += unlogged.index },
        onNextExercise = { skipped += unlogged.index; naming = true },
    )
    if (naming) ExerciseSheet(r.exercise, exercises.map { it.name }, onPick = { onExercise(it); naming = false }, onDismiss = { naming = false })
}

/** The set under way: set N of M and its exercise, the set's clock in dots, the sets as dots, the heart rate and zone. */
@Composable
private fun SetCard(r: WorkoutState.Recording, number: Int, seconds: () -> Long, bpm: Int?, color: Color, onName: () -> Unit) {
    val style = heroStyle(color, LightAngle.Below).let { s -> bpm?.let { s.copy(motion = GlowMotion.Heartbeat(it)) } ?: s }
    GlowCard(style, Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(WorkoutCopy.setOf(number, r.workout.setsTarget), color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
                ExercisePill(r.exercise, onName)
            }
            Column(Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DotClock({ workoutClock(seconds()) }, DotSize.Live)
                Spacer(Modifier.height(14.dp))
                Text("in this set", color = Color.White.copy(alpha = 0.8f), style = GlowType.body)
            }
            SetDots(r.sets.count { it.end != null }, r.workout.setsTarget, inSet = true, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(22.dp))
            HeartLine(bpm, r.maxHeartRate, r.paused)
        }
    }
}

/**
 * The set, the rest or the next set on one row (half a folded phone): what it is and its exercise, and the set's clock
 * or the rest left in dots, on an edge card in the screen's colour.
 */
@Composable
private fun CompactCard(
    r: WorkoutState.Recording, moment: org.openstrap.app.ui.workout.StrengthMoment, now: () -> org.openstrap.app.ui.workout.StrengthMoment,
    color: Color, onName: () -> Unit,
) {
    val (title, clock) = when {
        r.inSet -> WorkoutCopy.setOf(moment.setNumber, r.workout.setsTarget) to { workoutClock(now().setSeconds) }
        r.resting -> "Rest after " + WorkoutCopy.setOf(moment.done, r.workout.setsTarget).lowercase() to
            { workoutClock((now().restLeft ?: 0).coerceAtLeast(0)) }
        else -> (if (moment.setNumber == 1) "Ready for set 1" else "Rest over") to null
    }
    EdgeCard(color, Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = Color.White, style = GlowType.cardTitle, maxLines = 1)
                ExercisePill(r.exercise, onName)
            }
            if (clock != null) DotClock(clock, DotSize.Detail)
        }
    }
}

/** Before the first set, or once rest is over: which set is next and its exercise. */
@Composable
private fun ReadyCard(r: WorkoutState.Recording, number: Int, color: Color, onName: () -> Unit) {
    EdgeCard(color, Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (number == 1) "Ready for set 1" else "Rest over", color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
            ExercisePill(r.exercise, onName)
        }
        Spacer(Modifier.height(10.dp))
        Text(WorkoutCopy.setOf(number, r.workout.setsTarget) + " next. Tap Start when you pick up the weight.", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        Spacer(Modifier.height(14.dp))
        SetDots(r.sets.count { it.end != null }, r.workout.setsTarget, inSet = false, Modifier.align(Alignment.CenterHorizontally))
    }
}

/**
 * Rest: what it follows, the time left in dots, the heart rate and how far it dropped, and the heart line since the
 * set ended falling toward the level that ends rest sooner, drawn as a solid rule.
 */
@Composable
private fun RestCard(r: WorkoutState.Recording, left: () -> Long, done: Int, bpm: Int?, drop: Int?, history: List<Pair<Long, Int>>) {
    val rule = Strength.Rest.parse(r.workout.rest) ?: Strength.Rest.DEFAULT
    // Outlined, not lit: the set's card glows, the rest's counts down in its ring.
    Column(
        Modifier.fillMaxWidth().clip(GlowLayout.CardShape).border(1.5.dp, Color.White.copy(alpha = 0.2f), GlowLayout.CardShape).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row {
            Text("Rest", color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
            Text("after " + WorkoutCopy.setOf(done, r.workout.setsTarget).lowercase(), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        Spacer(Modifier.height(10.dp))
        val total = (r.restUntil ?: 0) - (r.sets.lastOrNull()?.end ?: r.restUntil ?: 0)
        RestRing({ left().coerceAtLeast(0) }, total.coerceAtLeast(1))
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth()) {
            val since = r.sets.lastOrNull()?.end
            if (since != null) {
                Spacer(Modifier.height(10.dp))
                FallingHeart(remember(history, since) { history.filter { it.first >= since - 10 } }, rule.belowBpm)
            }
            Spacer(Modifier.height(6.dp))
            if (bpm != null) Text("$bpm bpm" + (drop?.takeIf { it > 0 }?.let { ", down $it" } ?: ""), color = Color.White, style = GlowType.sentence)
            Spacer(Modifier.height(4.dp))
            Text(WorkoutCopy.restRule(rule.belowBpm), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
    }
}

/** The rest left in dots inside a ring in rest green that empties as it runs down. [left] is read in draw and its text. */
@Composable
private fun RestRing(left: () -> Long, total: Long) {
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val w = 8.dp.toPx()
            val inset = Offset(w / 2, w / 2)
            val arc = Size(size.width - w, size.height - w)
            drawArc(Color.White.copy(alpha = 0.1f), 0f, 360f, false, inset, arc, style = Stroke(w))
            val sweep = 360f * (left().toFloat() / total).coerceIn(0f, 1f)
            drawArc(RestGreen, -90f, sweep, false, inset, arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DotClock({ workoutClock(left()) }, DotSize.Detail)
            Spacer(Modifier.height(10.dp))
            Text("left", color = Color.White.copy(alpha = 0.75f), style = GlowType.body)
        }
    }
}

/** A clock in dots, its own scope: the second it reads composes only it again, not the card around it. */
@Composable
private fun DotClock(text: () -> String, dot: DotSize) = DotMatrixText(text(), dot = dot)

/** What follows the rest: "Set 4, bench press 10 × 60 kg". */
@Composable
private fun NextLine(number: Int, exercise: String?, load: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 14.dp)) {
        Text("Next", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.weight(1f))
        Text(
            listOfNotNull("Set $number", exercise?.lowercase()?.let { e -> listOfNotNull(e, load).joinToString(" ") }).joinToString(", "),
            color = Color.White, style = GlowType.body,
        )
    }
}

/** The heart rate since the set ended, falling, with the level that ends rest sooner as a thin solid rule. */
@Composable
private fun FallingHeart(readings: List<Pair<Long, Int>>, level: Int?) {
    if (readings.size < 2) return
    val lo = minOf(readings.minOf { it.second }, level ?: Int.MAX_VALUE) - 5
    val hi = readings.maxOf { it.second } + 5
    val from = readings.first().first
    val to = maxOf(readings.last().first, from + 60)
    Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "Heart rate since the set ended" }) {
        fun x(t: Long) = (t - from).toFloat() / (to - from) * size.width
        fun y(v: Int) = size.height - (v - lo).toFloat() / (hi - lo) * size.height
        if (level != null) drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, y(level)), Offset(size.width, y(level)), 1.dp.toPx())
        val path = Path().apply {
            readings.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) }
        }
        drawPath(path, EndRed, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The sets as dots: done ones white, the one under way white too, those to go dark. */
@Composable
private fun SetDots(done: Int, target: Int?, inSet: Boolean, modifier: Modifier = Modifier) {
    val total = maxOf(target ?: 0, done + if (inSet) 1 else 0)
    if (total == 0) return
    Row(
        modifier.semantics { contentDescription = "$done sets done" + (target?.let { ", ${(it - done).coerceAtLeast(0)} to go" } ?: "") },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(total) { i ->
            val now = inSet && i == done
            // The set under way has a soft ring round it.
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (now) Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < done || now) Color.White else Color.Black.copy(alpha = 0.35f)))
            }
        }
    }
}

/** The heart rate in plain figures with its zone named: the set's clock is the card's dotted number. */
@Composable
private fun HeartLine(bpm: Int?, maxHr: Int, paused: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(bpm?.toString() ?: WorkoutCopy.noHeartRate(paused), color = Color.White, style = GlowType.sentence)
        if (bpm != null) {
            Spacer(Modifier.width(6.dp))
            Text("bpm", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            Spacer(Modifier.weight(1f))
            WorkoutRecorder.liveZone(bpm, maxHr)?.let { z ->
                Text(WorkoutCopy.zoneLine(z), color = Color.White, style = GlowType.secondary, maxLines = 1)
            }
        }
    }
}

/** The exercise as a dark pill to tap, or "Name it" while the sets are only numbered. */
@Composable
private fun ExercisePill(name: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        name ?: "Name it", color = Color.White, style = MediumBody, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(shape).background(Color.Black.copy(alpha = 0.35f)).pressClickable(onClick, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The sets done, newest first: "Set 2" and "10 × 60 kg, 0:51, peak 148". */
@Composable
private fun DoneSets(sets: List<AppWorkoutSet>, history: List<Pair<Long, Int>>) {
    val done = sets.filter { it.end != null }.asReversed()
    if (done.isEmpty()) return
    // The peaks scan the readings, so they're worked out when a set or the readings change, not on every pass.
    val lines = remember(done, history) { done.take(4).map { s -> WorkoutCopy.setLine(s, Strength.peak(history, s.start, s.end!!)) } }
    Column(Modifier.fillMaxWidth()) {
        done.take(4).forEachIndexed { i, s ->
            SetRule()
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 14.dp)) {
                Text("Set ${s.index + 1}", color = Color.White, style = GlowType.body, modifier = Modifier.weight(1f))
                Text(
                    lines[i], color = Color.White.copy(alpha = 0.72f), style = GlowType.body, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SetRule()
    }
}

/** The line between the rows under the card, plain enough to read as a break between sets. */
@Composable
private fun SetRule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.18f)))

/**
 * The log card after a set (board StrengthLog): the set done with its time and peak, its exercise, reps and kg
 * steppers filled from the last set of that exercise, Skip or Save, and Next exercise. Rest runs underneath.
 */
@Composable
private fun LogCard(
    set: AppWorkoutSet, sets: List<AppWorkoutSet>, exercises: List<ExerciseRow>, history: List<Pair<Long, Int>>, restLeft: () -> Long?,
    onSave: (exercise: String?, reps: Int, kg: Float) -> Unit, onSkip: () -> Unit, onNextExercise: () -> Unit,
) {
    val row = exercises.firstOrNull { it.name.equals(set.exercise, ignoreCase = true) }
    val (fillReps, fillKg) = remember(set.index) { Strength.prefill(set.exercise, sets, row?.lastReps, row?.lastKg) }
    var reps by rememberSaveable(set.index) { mutableStateOf(fillReps) }
    var kg by rememberSaveable(set.index) { mutableStateOf(fillKg) }
    val peak = Strength.peak(history, set.start, set.end ?: set.start)
    PageSheet(
        onSkip, "Set ${set.index + 1} done",
        note = listOfNotNull(workoutClock((set.end ?: set.start) - set.start), peak?.let { "peak $it bpm" }).joinToString(", ") +
            (restLeft()?.let { ". Rest is running, ${workoutClock(it.coerceAtLeast(0))} left." } ?: "."),
    ) {
        Text(set.exercise ?: "Numbered set", color = Color.White, style = GlowType.body)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogStepper("Reps", reps.toString(), "Fewer reps", "More reps", Modifier.weight(1f), { reps = Strength.stepReps(reps, -1) }, { reps = Strength.stepReps(reps, 1) })
            LogStepper("kg", Strength.kgText(kg), "Less weight", "More weight", Modifier.weight(1f), { kg = Strength.stepKg(kg, -1) }, { kg = Strength.stepKg(kg, 1) })
        }
        Text("Filled from your last set. Change only what's different.", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton("Skip", onSkip, Modifier.weight(1f), icon = AppIcons.X)
            PageButton("Save", { onSave(set.exercise, reps, kg) }, Modifier.weight(1f), primary = true, icon = AppIcons.Tick02)
        }
        PageButton("Next exercise", onNextExercise, Modifier.fillMaxWidth(), icon = AppIcons.Plus)
    }
}

/** A log card stepper: its label, the value large, and minus and plus. */
@Composable
private fun LogStepper(label: String, value: String, less: String, more: String, modifier: Modifier, onLess: () -> Unit, onMore: () -> Unit) {
    Column(modifier.clip(GlowLayout.CardShape).background(org.openstrap.app.ui.glow.ListSurface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        MinusPlus(less, more, onLess, onMore) {
            Text(value, color = Color.White, style = GlowType.pageTitle, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Naming the exercise: a field to type one, then yours (last used first) and the common lifts; or no name, so the sets
 * are just numbered.
 */
@Composable
private fun ExerciseSheet(current: String?, used: List<String>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    PageSheet(onDismiss, "Exercise", note = "Picked once, it stays for the next sets.") {
        BasicTextField(
            typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = GlowType.body.copy(color = Color.White), cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (typed.isNotBlank()) onPick(typed) }),
            decorationBox = { field ->
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(GlowLayout.TileShape).background(Raised).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (typed.isEmpty()) Text("Type a name", color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.body)
                        field()
                    }
                    if (typed.isNotBlank()) Icon(AppIcons.Tick02, "Use this name", tint = Color.White, modifier = Modifier.size(28.dp).clip(CircleShape).pressClickable({ onPick(typed) }, CircleShape).padding(4.dp))
                }
            },
        )
        val names = Strength.exerciseList(used).filter { typed.isBlank() || it.contains(typed.trim(), ignoreCase = true) }
        Column {
            names.forEachIndexed { i, name -> PageRow(name, value = if (name.equals(current, ignoreCase = true)) "Now" else null, divider = i > 0, onClick = { onPick(name) }) }
        }
        if (current != null) PageButton("No name, just number the sets", { onPick(null) }, Modifier.fillMaxWidth(), icon = AppIcons.X)
    }
}
