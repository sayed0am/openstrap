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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.delay
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
import org.openstrap.app.ui.glow.RoundIcon
import org.openstrap.app.ui.glow.WorkoutButton
import org.openstrap.app.ui.glow.activeSeconds
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
) {
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            value = System.currentTimeMillis() / 1000
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    val connected = connection is ConnectionState.Connected
    val bpm = liveHeartRate.takeIf { connected && !r.paused }
    ZoneTick(bpm, r)
    val color = if (r.resting && !r.paused) RestGreen else workoutColor(r, liveHeartRate, connected)
    val moment = strengthMoment(r, now, bpm, history)
    val haptics = rememberHaptics()
    var naming by rememberSaveable { mutableStateOf(false) }
    // Sets whose log card was skipped, so it doesn't come back.
    val skipped = rememberSaveable(r.workout.start) { mutableStateListOf<Int>() }
    val unlogged = r.sets.lastOrNull { it.end != null }?.takeIf { r.logSets && it.reps == null && it.index !in skipped && !r.inSet }
    LiveFrame(
        r, color, connected, contentPadding, controls, time = activeSeconds(r), smallTime = true,
        paused = { LivePaused(r, liveHeartRate.takeIf { connected }, history, listOf(
            moment.done.toString() to if (moment.done == 1) "Set done" else "Sets done",
            Strength.kgLifted(r.sets)?.let { Strength.kgText(it) } to "kg lifted",
            r.workout.maxHeartRate?.toString() to "Peak bpm",
        )) },
    ) {
        when {
            r.inSet -> SetCard(r, moment.setNumber, moment.setSeconds, bpm, color) { naming = true }
            r.resting -> RestCard(r, moment.restLeft ?: 0, moment.done, bpm, moment.drop, history)
            else -> ReadyCard(r, moment.setNumber, color) { naming = true }
        }
        if (r.resting && !r.inSet) NextLine(moment.setNumber, r.exercise, moment.nextLoad)
        DoneSets(r.sets, history)
        Spacer(Modifier.weight(1f))
        when {
            r.inSet -> WorkoutButton("Set done", Color.White, Color.Black, { haptics.perform(HapticEvent.SetDone); onSetDone() }, icon = AppIcons.Tick02, height = 64.dp)
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (r.resting) Box(Modifier.width(110.dp)) { WorkoutButton("+30 s", Raised, Color.White, onExtendRest, height = 64.dp) }
                Box(Modifier.weight(1f)) { WorkoutButton("Start set ${moment.setNumber}", Color.White, Color.Black, onStartSet, icon = AppIcons.PlayFill, height = 64.dp) }
            }
        }
        val target = r.workout.setsTarget
        if (target != null && moment.done >= target && !r.inSet) {
            Text("All $target sets done. Hold the square to end, or keep going.", color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
    if (unlogged != null) LogCard(
        unlogged, r.sets, exercises, history, restLeft = moment.restLeft,
        onSave = { exercise, reps, kg -> onLog(unlogged.index, exercise, reps, kg) },
        onSkip = { skipped += unlogged.index },
        onNextExercise = { skipped += unlogged.index; naming = true },
    )
    if (naming) ExerciseSheet(r.exercise, exercises.map { it.name }, onPick = { onExercise(it); naming = false }, onDismiss = { naming = false })
}

/** The set under way: set N of M and its exercise, the set's clock in dots, the sets as dots, the heart rate and zone. */
@Composable
private fun SetCard(r: WorkoutState.Recording, number: Int, seconds: Long, bpm: Int?, color: Color, onName: () -> Unit) {
    val style = heroStyle(color, LightAngle.Below).let { s -> bpm?.let { s.copy(motion = GlowMotion.Heartbeat(it)) } ?: s }
    GlowCard(style, Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(WorkoutCopy.setOf(number, r.workout.setsTarget), color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
                ExercisePill(r.exercise, onName)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                DotMatrixText(workoutClock(seconds), dot = DotSize.Detail)
                Spacer(Modifier.width(10.dp))
                Text("in this set", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body)
            }
            Spacer(Modifier.height(16.dp))
            SetDots(r.sets.count { it.end != null }, r.workout.setsTarget, inSet = true)
            Spacer(Modifier.height(14.dp))
            HeartLine(bpm, r.maxHeartRate)
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
        SetDots(r.sets.count { it.end != null }, r.workout.setsTarget, inSet = false)
    }
}

/**
 * Rest: what it follows, the time left in dots, the heart rate and how far it dropped, and the heart line since the
 * set ended falling toward the level that ends rest sooner, drawn as a solid rule.
 */
@Composable
private fun RestCard(r: WorkoutState.Recording, left: Long, done: Int, bpm: Int?, drop: Int?, history: List<Pair<Long, Int>>) {
    val rule = Strength.Rest.parse(r.workout.rest) ?: Strength.Rest.DEFAULT
    EdgeCard(RestGreen, Modifier.fillMaxWidth()) {
        Row {
            Text("Rest", color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
            Text("after " + WorkoutCopy.setOf(done, r.workout.setsTarget).lowercase(), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            DotMatrixText(workoutClock(left.coerceAtLeast(0)), dot = DotSize.Detail)
            Spacer(Modifier.width(10.dp))
            Text("left", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body)
        }
        val since = r.sets.lastOrNull()?.end
        if (since != null) {
            Spacer(Modifier.height(12.dp))
            FallingHeart(history.filter { it.first >= since - 10 }, rule.belowBpm)
        }
        Spacer(Modifier.height(10.dp))
        if (bpm != null) Text("$bpm bpm" + (drop?.takeIf { it > 0 }?.let { ", down $it" } ?: ""), color = Color.White, style = GlowType.sentence)
        Text(WorkoutCopy.restRule(rule.belowBpm), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
    }
}

/** What follows the rest: "Set 4, bench press 10 × 60 kg". */
@Composable
private fun NextLine(number: Int, exercise: String?, load: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text("Next", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.width(56.dp))
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
        drawPath(path, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The sets as dots: done ones white, the one under way white too, those to go dark. */
@Composable
private fun SetDots(done: Int, target: Int?, inSet: Boolean) {
    val total = maxOf(target ?: 0, done + if (inSet) 1 else 0)
    if (total == 0) return
    Row(
        Modifier.semantics { contentDescription = "$done sets done" + (target?.let { ", ${(it - done).coerceAtLeast(0)} to go" } ?: "") },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { i ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (i < done || (inSet && i == done)) Color.White else Color.Black.copy(alpha = 0.35f)))
        }
    }
}

/** The heart rate in plain figures with its zone named: the set's clock is the card's dotted number. */
@Composable
private fun HeartLine(bpm: Int?, maxHr: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(bpm?.toString() ?: "No heart rate yet", color = Color.White, style = GlowType.sentence)
        if (bpm != null) {
            Spacer(Modifier.width(6.dp))
            Text("bpm", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            Spacer(Modifier.weight(1f))
            WorkoutRecorder.liveZone(bpm, maxHr)?.let { z ->
                Text(WorkoutCopy.zoneTitle(z).let { (a, b) -> "$a $b" }, color = Color.White, style = GlowType.secondary, maxLines = 1)
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        done.take(4).forEach { s ->
            Row {
                Text("Set ${s.index + 1}", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.width(56.dp))
                Text(WorkoutCopy.setLine(s, Strength.peak(history, s.start, s.end!!)), color = Color.White, style = GlowType.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The log card after a set (board StrengthLog): the set done with its time and peak, its exercise, reps and kg
 * steppers filled from the last set of that exercise, Skip or Save, and Next exercise. Rest runs underneath.
 */
@Composable
private fun LogCard(
    set: AppWorkoutSet, sets: List<AppWorkoutSet>, exercises: List<ExerciseRow>, history: List<Pair<Long, Int>>, restLeft: Long?,
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
            (restLeft?.let { ". Rest is running, ${workoutClock(it.coerceAtLeast(0))} left." } ?: "."),
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
    Column(modifier.clip(GlowLayout.CardShape).background(Color(0xFF0F0F11)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(AppIcons.Minus, less, onLess)
            Text(value, color = Color.White, style = GlowType.pageTitle, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            RoundIcon(AppIcons.Plus, more, onMore)
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
