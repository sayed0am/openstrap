package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.Structure
import org.openstrap.app.data.WorkoutSetup
import org.openstrap.app.data.WorkoutSetup.GoalKind
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.SavedWorkout
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.StartCopy
import org.openstrap.app.ui.workout.WorkoutCopy
import org.openstrap.app.ui.workout.ZONE_COLORS
import java.time.LocalTime

/*
 * Ready for distance and cardio sports (board ReadyFinal): the heart, where you are, then the goal, typed in place.
 * No settings sheet: everything it held is a row here or went with the design.
 */

/**
 * Ready's heart: the live bpm in dots on a card lit heart red from below, as Today's hero, "bpm, live from your strap"
 * beside it. While the strap is away, what's wrong and that you can start anyway, on a dimmer light.
 */
@Composable
internal fun ReadyHeartCard(connection: ConnectionState, liveHeartRate: Int?) {
    val hero = WorkoutCopy.readyHero(connection, liveHeartRate)
    val bpm = liveHeartRate.takeIf { hero.live }
    val light = if (bpm != null) 1f else 0.4f
    Row(
        Modifier.fillMaxWidth().clip(GlowLayout.CardShape).background(HeartBase)
            .drawBehind {
                // An ellipse 1.2 widths across and 1.1 heights tall, centred a quarter of the height below the card.
                val ry = size.height * 1.1f
                val c = Offset(size.width / 2, size.height * 1.25f)
                scale(size.width * 1.2f / ry, 1f, c) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to MetricColors.heart.copy(alpha = 0.7f * light), 0.5f to MetricColors.heart.copy(alpha = 0.18f * light),
                            1f to Color.Transparent, center = c, radius = ry,
                        ),
                        ry, c,
                    )
                }
            }
            .border(1.dp, MetricColors.heart.copy(alpha = 0.3f), GlowLayout.CardShape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (bpm != null) {
            DotMatrixText(bpm.toString(), dot = DotSize.Hero)
            Spacer(Modifier.width(14.dp))
            Text("bpm, live from your strap", color = Color.White.copy(alpha = 0.8f), style = GlowType.body, modifier = Modifier.weight(1f))
        } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(hero.title, color = Color.White, style = GlowType.sentence)
            Text(hero.line, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
    }
}

/** The heart card's dark base under its light. */
private val HeartBase = Color(0xFF0A0A0B)

/**
 * Where you are and the switch rows, on one quiet panel. For a sport with an indoor side ([sides]), its title is the
 * side you're on: outside with a green pin and the GPS line, or indoors with its amber icon and what you get without
 * GPS; its pill names the other side and switches to it ([onSwitch]). An outdoor sport whose location is off says so,
 * with Turn on location. Then Ask to end when I stop, and Keep the route where GPS is used ([keepRoute] not null).
 */
@Composable
internal fun ReadyWhere(
    type: Int,
    sides: Workouts.Sides?,
    indoor: Boolean,
    outdoor: Boolean,
    locationOn: Boolean,
    gpsLine: String,
    onSwitch: () -> Unit,
    onTurnOnLocation: () -> Unit,
    askToEnd: Boolean,
    onAskToEnd: (Boolean) -> Unit,
    keepRoute: Boolean?,
    onKeepRoute: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(GlowLayout.CardShape).background(ListSurface).padding(horizontal = 18.dp)) {
        var rows = 0
        if (sides != null) {
            PlaceRow(
                placeIcon(sides, indoor), if (indoor) IndoorTint else TrendGood, StartCopy.place(sides, indoor),
                when {
                    indoor -> StartCopy.indoorNote(type)
                    !locationOn -> WorkoutCopy.LOCATION_OFF
                    else -> gpsLine
                },
            ) { PlacePill(placeIcon(sides, !indoor), StartCopy.otherPlace(sides, indoor), onSwitch) }
            rows++
        } else if (outdoor && !locationOn) {
            PlaceRow(SportIconsLocation, TrendBad, WorkoutCopy.LOCATION_OFF, WorkoutCopy.LOCATION_OFF_NOTE, trailing = null)
            rows++
        }
        if (outdoor && !locationOn) {
            PageButton(StartCopy.TURN_ON_LOCATION, onTurnOnLocation, Modifier.fillMaxWidth().padding(bottom = 14.dp), icon = SportIconsLocation)
        }
        PageSwitchRow(StartCopy.ASK_TO_END, StartCopy.ASK_TO_END_NOTE, askToEnd, onAskToEnd, divider = rows > 0)
        if (keepRoute != null) PageSwitchRow(StartCopy.KEEP_ROUTE, WorkoutCopy.routeNote(keepRoute), keepRoute, onKeepRoute)
    }
}

private val SportIconsLocation: ImageVector get() = org.openstrap.app.ui.workout.SportIcons.Location

/** Amber, for an indoor side: zone 3's yellow, as the board has it. */
private val IndoorTint = ZONE_COLORS[2]

/** Each side's icon: the pin outdoors, then the treadmill, the pool or a house. */
private fun placeIcon(sides: Workouts.Sides, indoor: Boolean): ImageVector = when {
    !indoor -> SportIconsLocation
    sides.indoor == 2 -> AppIcons.Treadmill
    sides.indoor == 6 -> AppIcons.PersonSimpleSwim
    else -> AppIcons.HouseSimple
}

/** A where row: the icon in a tile tinted its [tint], the title and its note, then [trailing]. */
@Composable
private fun PlaceRow(icon: ImageVector, tint: Color, title: String, note: String, trailing: (@Composable () -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = GlowLayout.RowPadding), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RowName(title)
            Text(note, color = Color.White.copy(alpha = 0.65f), style = GlowType.secondary)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** The pill at the where row's end: the other side's icon and name. Tapping it goes there. */
@Composable
private fun PlacePill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(CircleShape).background(Raised).pressClickable(onClick, CircleShape)
            .semantics { role = Role.Button; contentDescription = label }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = GlowType.body, maxLines = 1)
    }
}

/* -------------------------------------------- The goal card ---------------------------------------------- */

/** What the goal card shows and changes: the kind chosen and each kind's value, kept while you flip between them. */
internal data class GoalDraft(
    val kind: GoalKind,
    val meters: Int,
    val seconds: Long,
    val zones: WorkoutTarget.Zones,
    val reps: Structure.Reps,
)

/**
 * The goal (board ReadyFinal): the kinds as chips ([kinds]; Just go named for the sport), and under them the chosen
 * kind's value with what the strap will do. A value is typed in place on the number keyboard; [typing] says when, so
 * Start can make way for the keyboard. Intervals save by name ([onSave]) and open a saved one ([saved]).
 */
@Composable
internal fun ReadyGoal(
    type: Int,
    kinds: List<GoalKind>,
    hasDistance: Boolean,
    draft: GoalDraft,
    onDraft: (GoalDraft) -> Unit,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    zones: HeartZones,
    maxHr: Int?,
    onYourZones: () -> Unit,
    saved: List<SavedWorkout>,
    onSave: (String, Structure.Reps) -> Unit,
) {
    Text(StartCopy.goalTitle(Workouts.family(type)), color = Color.White.copy(alpha = 0.6f), style = GlowType.body, modifier = Modifier.padding(horizontal = 4.dp))
    Column(
        Modifier.fillMaxWidth().clip(GlowLayout.CardShape).background(ListSurface).border(1.dp, RowHairline, GlowLayout.CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            kinds.forEach { k ->
                KindChip(kindName(k, type), k == draft.kind) { onTyping(false); onDraft(draft.copy(kind = k)) }
            }
        }
        when (draft.kind) {
            GoalKind.None -> GoalNote(StartCopy.noGoalNote(hasDistance))
            GoalKind.Distance -> DistanceGoal(draft.meters, { onDraft(draft.copy(meters = it)) }, typing, onTyping)
            GoalKind.Time -> TimeGoal(draft.seconds, { onDraft(draft.copy(seconds = it)) }, typing, onTyping)
            GoalKind.Zone -> ZoneGoal(draft.zones, { onDraft(draft.copy(zones = it)) }, zones, maxHr, onYourZones)
            GoalKind.Intervals -> IntervalsGoal(draft.reps, { onDraft(draft.copy(reps = it)) }, typing, onTyping, saved, onSave)
        }
    }
}

private fun kindName(k: GoalKind, type: Int): String = when (k) {
    GoalKind.None -> StartCopy.noGoal(type)
    GoalKind.Distance -> StartCopy.DISTANCE
    GoalKind.Time -> StartCopy.TIME
    GoalKind.Zone -> StartCopy.ZONE
    GoalKind.Intervals -> StartCopy.INTERVALS
}

/** A goal kind's chip: white when chosen, on the Control surface otherwise. */
@Composable
private fun KindChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(CircleShape).background(if (on) Color.White else Raised)
            .pressClickable(onClick, CircleShape).semantics { selected = on; role = Role.Tab }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (on) Color.Black else Color.White.copy(alpha = 0.85f),
            style = GlowType.secondary.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1,
        )
    }
}

/** The line under a goal: what the strap will do. */
@Composable
private fun GoalNote(text: String) =
    Text(text, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

/** A row of presets under a value, each as wide as the others, on a hairline. */
@Composable
private fun <T> Presets(presets: List<Pair<T, String>>, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { (v, label) ->
            Box(
                Modifier.weight(1f).height(34.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    .pressClickable({ onPick(v) }, CircleShape).semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary, maxLines = 1) }
        }
    }
}

/** A value between round minus and plus buttons. */
@Composable
private fun ValueStepper(less: String, more: String, onLess: () -> Unit, onMore: () -> Unit, value: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StepButton(AppIcons.Minus, less, onLess)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = value)
        StepButton(AppIcons.Plus, more, onMore)
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(Raised).pressClickable(onClick, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** A value to tap and type: its numbers and units, "Tap to type" under them ([hint]). */
@Composable
private fun TapValue(parts: List<Pair<String, String>>, hint: String?, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = StartCopy.TAP_TO_TYPE, onClick = onClick).padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row {
            parts.forEachIndexed { i, (n, unit) ->
                if (i > 0) Spacer(Modifier.width(6.dp))
                Text(n, color = Color.White, style = GlowType.goalValue, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(4.dp))
                Text(unit, color = Color.White.copy(alpha = 0.75f), style = GlowType.body, modifier = Modifier.alignByBaseline())
            }
        }
        if (hint != null) Text(hint, color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.caption, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * A number typed in place: [GlowType.goalValue] (or [style]) on the phone's number keyboard, with the lime caret and
 * a lime underline while it has focus ([focused]), a faint one otherwise. [unit] follows it.
 */
@Composable
private fun TypedNumber(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    unit: String?,
    focused: Boolean,
    field: Modifier,
    keyboard: KeyboardType,
    ime: ImeAction,
    onIme: () -> Unit,
    style: TextStyle = GlowType.goalValue,
) {
    Row(
        Modifier.drawBehind {
            val y = size.height - 1.dp.toPx()
            drawLine(if (focused) TypingLime else Color.White.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
        }.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
    ) {
        BasicTextField(
            value, onValue, field.width(IntrinsicSize.Min).widthIn(min = 18.dp).alignByBaseline(),
            textStyle = style.copy(color = Color.White), cursorBrush = SolidColor(TypingLime), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ime),
            keyboardActions = KeyboardActions(onDone = { onIme() }, onNext = { onIme() }),
        )
        if (unit != null) {
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Color.White.copy(alpha = 0.75f), style = GlowType.body, modifier = Modifier.alignByBaseline())
        }
    }
}

/** The caret and underline of a number being typed. */
private val TypingLime = Color(0xFFC3F53C)

/** [text] with all of it selected, so the first key typed replaces it. */
private fun selected(text: String) = TextFieldValue(text, TextRange(0, text.length))

/**
 * While typing, which field has focus ([focused], null for none). Once none has had it for a moment (focus moving
 * from one field to the next passes through none), typing is over and [onDone] reads what was typed.
 */
@Composable
private fun EndTyping(typing: Boolean, focused: Int?, everFocused: Boolean, onDone: () -> Unit) {
    LaunchedEffect(typing, focused, everFocused) {
        if (typing && everFocused && focused == null) {
            kotlinx.coroutines.delay(120)
            onDone()
        }
    }
}

/** Distance: minus and plus by half a km, the value in km to tap and type, the presets, and its note. */
@Composable
private fun DistanceGoal(meters: Int, onMeters: (Int) -> Unit, typing: Boolean, onTyping: (Boolean) -> Unit) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(TextFieldValue()) }
    var focused by remember { mutableStateOf<Int?>(null) }
    var everFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun finish() {
        if (!typing) return
        StartCopy.parseDistance(text.text)?.let(onMeters)
        onTyping(false)
    }
    // A step or preset while typing drops what was typed.
    fun stop() { onTyping(false); focusManager.clearFocus() }
    EndTyping(typing, focused, everFocused) { finish() }
    ValueStepper("Shorter", "Longer", { stop(); onMeters(StartCopy.stepDistance(meters, up = false)) }, { stop(); onMeters(StartCopy.stepDistance(meters, up = true)) }) {
        if (typing) {
            LaunchedEffect(Unit) { focus.requestFocus() }
            TypedNumber(
                text, { v -> text = v.copy(text = v.text.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) }, "km", focused == 0,
                Modifier.focusRequester(focus).onFocusChanged { if (it.isFocused) { focused = 0; everFocused = true } else if (focused == 0) focused = null },
                KeyboardType.Decimal, ImeAction.Done, { focusManager.clearFocus() },
            )
        } else TapValue(listOf(StartCopy.distanceValue(meters) to "km"), StartCopy.TAP_TO_TYPE) {
            text = selected(StartCopy.distanceValue(meters)); everFocused = false; onTyping(true)
        }
    }
    Presets(StartCopy.DISTANCE_PRESETS) { stop(); onMeters(it) }
    GoalNote(StartCopy.distanceNote(if (typing) StartCopy.parseDistance(text.text) ?: meters else meters))
}

/**
 * Time: minus and plus by 5 minutes, the value in hours and minutes to tap and type (hours, then on to the minutes by
 * itself), the presets, and when it would end if you started now.
 */
@Composable
private fun TimeGoal(seconds: Long, onSeconds: (Long) -> Unit, typing: Boolean, onTyping: (Boolean) -> Unit) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    var hours by remember { mutableStateOf(TextFieldValue()) }
    var minutes by remember { mutableStateOf(TextFieldValue()) }
    var focused by remember { mutableStateOf<Int?>(null) }
    var everFocused by remember { mutableStateOf(false) }
    val hoursFocus = remember { FocusRequester() }
    val minutesFocus = remember { FocusRequester() }
    fun finish() {
        if (!typing) return
        StartCopy.parseTime(hours.text, minutes.text)?.let(onSeconds)
        onTyping(false)
    }
    fun stop() { onTyping(false); focusManager.clearFocus() }
    EndTyping(typing, focused, everFocused) { finish() }
    ValueStepper("Shorter", "Longer", { stop(); onSeconds(StartCopy.stepTime(seconds, up = false)) }, { stop(); onSeconds(StartCopy.stepTime(seconds, up = true)) }) {
        if (typing) {
            LaunchedEffect(Unit) { hoursFocus.requestFocus() }
            fun Modifier.tracked(i: Int) = onFocusChanged { if (it.isFocused) { focused = i; everFocused = true } else if (focused == i) focused = null }
            TypedNumber(
                hours, { v ->
                    hours = v.copy(text = v.text.filter { it.isDigit() }.take(1))
                    // One figure of hours, then on to the minutes.
                    if (hours.text.length == 1 && v.text != selectedText(v)) minutesFocus.requestFocus()
                }, "h", focused == 0, Modifier.focusRequester(hoursFocus).tracked(0), KeyboardType.Number, ImeAction.Next, { minutesFocus.requestFocus() },
            )
            Spacer(Modifier.width(10.dp))
            TypedNumber(
                minutes, { v -> minutes = v.copy(text = v.text.filter { it.isDigit() }.take(3)) }, "min", focused == 1,
                Modifier.focusRequester(minutesFocus).tracked(1), KeyboardType.Number, ImeAction.Done, { focusManager.clearFocus() },
            )
        } else TapValue(StartCopy.timeParts(seconds), StartCopy.TAP_TO_TYPE) {
            hours = selected("${seconds / 3600}"); minutes = selected("${seconds / 60 % 60}"); everFocused = false; onTyping(true)
        }
    }
    Presets(StartCopy.TIME_PRESETS) { stop(); onSeconds(it) }
    val shown = if (typing) StartCopy.parseTime(hours.text, minutes.text) ?: seconds else seconds
    // Ticks with the clock, so the end time stays right while Ready shows.
    val now by produceState(LocalTime.now()) { while (true) { kotlinx.coroutines.delay(15_000); value = LocalTime.now() } }
    val ends = StartCopy.endsNear(now, shown, android.text.format.DateFormat.is24HourFormat(context))
    GoalNote(if (typing) StartCopy.timeTypingNote(ends) else ends)
}

/** The text a field's change would leave selected, to tell a key typed from the selection only moving. */
private fun selectedText(v: TextFieldValue): String = v.text.substring(v.selection.min, v.selection.max)

/**
 * Zone: the chosen zone or range ("Zone 2 to 3") with its heart rates and names from your zones, five buttons in the
 * zones' colours with where each starts, its note, and Your zones to open the Heart rate zones page.
 */
@Composable
private fun ZoneGoal(goal: WorkoutTarget.Zones, onGoal: (WorkoutTarget.Zones) -> Unit, zones: HeartZones, maxHr: Int?, onYourZones: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(goal.label, color = Color.White, style = GlowType.smallValue)
        if (maxHr != null) Text(StartCopy.zoneRangeLine(goal, zones, maxHr), color = Color.White.copy(alpha = 0.7f), style = GlowType.body, textAlign = TextAlign.Center)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (0 until Workouts.ZONES).forEach { z ->
            val on = z in goal.low..goal.high
            val color = ZONE_COLORS[z]
            Column(
                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(14.dp)).background(if (on) color else ZoneOff)
                    .drawBehind { if (!on) drawRect(color, Offset(0f, size.height - 3.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())) }
                    .pressClickable({ onGoal(WorkoutSetup.pickZone(goal, z)) }, RoundedCornerShape(14.dp))
                    .semantics { selected = on; role = Role.Button },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val ink = if (on) Color.Black else Color.White.copy(alpha = 0.75f)
                Text("Z${z + 1}", color = ink, style = GlowType.body.copy(fontWeight = FontWeight.SemiBold))
                if (maxHr != null) Text("${zones.startBpm(z, maxHr)}", color = ink.copy(alpha = ink.alpha * 0.75f), style = GlowType.caption)
            }
        }
    }
    GoalNote(StartCopy.ZONE_NOTE)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        PageButton(StartCopy.YOUR_ZONES, onYourZones, icon = AppIcons.PencilEdit)
    }
}

/** A zone button not chosen: dark, with its colour along the bottom. */
private val ZoneOff = Color(0xFF1A1A1D)

/**
 * Intervals: "6 × 400 m" over "with 90 s rest between". Any number taps to type, then on to the next: how many, each
 * rep (in m, km or min, chosen while you type it), then the rest. The presets, what the strap will do, and Save by
 * name and Saved, for Choose's chips and here.
 */
@Composable
private fun IntervalsGoal(
    reps: Structure.Reps,
    onReps: (Structure.Reps) -> Unit,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    saved: List<SavedWorkout>,
    onSave: (String, Structure.Reps) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var count by remember { mutableStateOf(TextFieldValue()) }
    var rep by remember { mutableStateOf(TextFieldValue()) }
    var rest by remember { mutableStateOf(TextFieldValue()) }
    var unit by remember { mutableStateOf(StartCopy.repUnit(reps)) }
    var focused by remember { mutableStateOf<Int?>(null) }
    var everFocused by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(0) }
    val focus = remember { List(3) { FocusRequester() } }
    var sheet by rememberSaveable { mutableStateOf<Int?>(null) }
    fun typed(): Structure.Reps {
        val withCount = StartCopy.parseCount(count.text)?.let { reps.copy(rounds = it) } ?: reps
        val withRep = StartCopy.parseRep(rep.text, unit, withCount) ?: withCount
        return StartCopy.parseRest(rest.text)?.let { withRep.copy(rest = it) } ?: withRep
    }
    fun finish() {
        if (!typing) return
        onReps(typed())
        onTyping(false)
    }
    fun start(field: Int) {
        count = selected("${reps.rounds}")
        rep = selected(StartCopy.repParts(reps).first)
        rest = selected("${reps.rest}")
        unit = StartCopy.repUnit(reps)
        first = field; everFocused = false; onTyping(true)
    }
    EndTyping(typing, focused, everFocused) { finish() }
    val shown = if (typing) typed() else reps
    if (typing) {
        LaunchedEffect(Unit) { focus[first].requestFocus() }
        fun Modifier.tracked(i: Int) = focusRequester(focus[i]).onFocusChanged { if (it.isFocused) { focused = i; everFocused = true } else if (focused == i) focused = null }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypedNumber(count, { v -> count = v.copy(text = v.text.filter { it.isDigit() }.take(2)) }, null, focused == 0, Modifier.tracked(0), KeyboardType.Number, ImeAction.Next, { focus[1].requestFocus() })
                Text("×", color = Color.White.copy(alpha = 0.5f), style = GlowType.goalValueMinor, modifier = Modifier.padding(horizontal = 10.dp))
                TypedNumber(
                    rep, { v -> rep = v.copy(text = v.text.filter { it.isDigit() || it == '.' || it == ',' }.take(5)) }, unit.label, focused == 1, Modifier.tracked(1),
                    KeyboardType.Decimal, ImeAction.Next, { focus[2].requestFocus() },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("with", color = Color.White.copy(alpha = 0.7f), style = GlowType.body)
                Spacer(Modifier.width(6.dp))
                TypedNumber(
                    rest, { v -> rest = v.copy(text = v.text.filter { it.isDigit() }.take(3)) }, "s", focused == 2, Modifier.tracked(2),
                    KeyboardType.Number, ImeAction.Done, { focusManager.clearFocus() }, style = GlowType.goalValueMinor,
                )
                Spacer(Modifier.width(6.dp))
                Text("rest between", color = Color.White.copy(alpha = 0.7f), style = GlowType.body)
            }
        }
        if (focused == 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(StartCopy.EACH_REP_IN, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary)
            Spacer(Modifier.width(10.dp))
            StartCopy.RepUnit.entries.forEach { u ->
                UnitChip(u.label, u == unit) { unit = u }
                Spacer(Modifier.width(6.dp))
            }
        }
        GoalNote(StartCopy.INTERVALS_TYPING)
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            val (n, u) = StartCopy.repParts(shown)
            TapNumber("${shown.rounds}", null, GlowType.goalValue) { start(0) }
            Text("×", color = Color.White.copy(alpha = 0.5f), style = GlowType.goalValueMinor, modifier = Modifier.alignByBaseline().padding(horizontal = 10.dp))
            TapNumber(n, u, GlowType.goalValue) { start(1) }
        }
        Row {
            val (n, u) = StartCopy.restParts(shown.rest)
            Text("with", color = Color.White.copy(alpha = 0.7f), style = GlowType.body, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            TapNumber(n, u, GlowType.goalValueMinor) { start(2) }
            Spacer(Modifier.width(6.dp))
            Text("rest between", color = Color.White.copy(alpha = 0.7f), style = GlowType.body, modifier = Modifier.alignByBaseline())
        }
        Text(StartCopy.TAP_A_NUMBER, color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.caption)
    }
    Presets(StartCopy.INTERVAL_PRESETS.map { it to StartCopy.structureName(it) }) { onReps(it.warm(reps.warmUp)) }
    GoalNote(StartCopy.intervalsNote(shown))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        PageButton(StartCopy.SAVE_INTERVALS, { sheet = SAVE }, icon = AppIcons.Plus)
        if (saved.isNotEmpty()) PageButton(StartCopy.SAVED_INTERVALS, { sheet = SAVED }, icon = AppIcons.FolderOpen)
    }
    when (sheet) {
        SAVE -> SaveIntervalsSheet(reps, { name -> onSave(name, reps); sheet = null }) { sheet = null }
        SAVED -> PageSheet({ sheet = null }, StartCopy.SAVED_INTERVALS) {
            Column {
                saved.forEachIndexed { i, w ->
                    val s = Structure.parse(w.structure) as? Structure.Reps ?: return@forEachIndexed
                    PageRow(w.name, value = StartCopy.structureName(s), divider = i > 0, onClick = { onReps(s); sheet = null })
                }
            }
        }
    }
}

private const val SAVE = 0
private const val SAVED = 1

/** One number of the intervals to tap and type, with its [unit] after it, on the shared baseline. */
@Composable
private fun RowScope.TapNumber(n: String, unit: String?, style: TextStyle, onClick: () -> Unit) {
    Row(Modifier.alignByBaseline().clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = StartCopy.TAP_TO_TYPE, onClick = onClick).padding(horizontal = 4.dp)) {
        Text(n, color = Color.White, style = style, modifier = Modifier.alignByBaseline())
        if (unit != null) {
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Color.White.copy(alpha = 0.75f), style = GlowType.body, modifier = Modifier.alignByBaseline())
        }
    }
}

/** A rep's unit while it's typed: white when chosen. */
@Composable
private fun UnitChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).clip(CircleShape).background(if (on) Color.White else Raised)
            .clickable(onClick = onClick).semantics { selected = on }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Color.Black else Color.White.copy(alpha = 0.85f), style = GlowType.secondary.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
    }
}

/** Saving [reps] by name, to find on Choose and here. */
@Composable
private fun SaveIntervalsSheet(reps: Structure.Reps, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    PageSheet(onDismiss, "${StartCopy.SAVE_INTERVALS} ${StartCopy.structureName(reps)}", note = StartCopy.SAVE_NOTE) {
        PageField(name, { name = it.take(40) }, null, placeholder = StartCopy.NAME_HINT)
        PageButton(StartCopy.SAVE_INTERVALS, { onSave(name.trim()) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank(), primary = true, icon = AppIcons.Plus)
    }
}
