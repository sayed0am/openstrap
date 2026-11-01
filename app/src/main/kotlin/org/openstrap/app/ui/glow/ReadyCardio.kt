package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.platform.LocalDensity
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
 * Ready's heart: the live bpm in dots on a card lit from below in [color] (heart red, or the family's colour), as
 * Today's hero, "bpm, live from your strap" beside it. While the strap is away, what's wrong and that you can start
 * anyway, on a dimmer light.
 */
@Composable
internal fun ReadyHeartCard(connection: ConnectionState, liveHeartRate: Int?, color: Color = MetricColors.heart) {
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
                            0f to color.copy(alpha = 0.7f * light), 0.5f to color.copy(alpha = 0.18f * light),
                            1f to Color.Transparent, center = c, radius = ry,
                        ),
                        ry, c,
                    )
                }
            }
            .border(1.dp, color.copy(alpha = 0.3f), GlowLayout.CardShape)
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
 * with Turn on location. Then Ask to end when I stop, and Save a map of my run where GPS is used ([keepRoute] not null).
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
        if (keepRoute != null) PageSwitchRow(StartCopy.keepRoute(type), WorkoutCopy.routeNote(keepRoute), keepRoute, onKeepRoute)
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
 * The goal (board ReadyFinal): the kinds in one pill ([kinds]; Just go named for the sport), and under it the chosen
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
    val options = kinds.map { PlanOption(kindIcon(it, type), kindName(it, type), kindNote(it, hasDistance)) }
    val at = kinds.indexOf(draft.kind)
    ReadyPlanCard(
        Workouts.family(type), options, options.getOrElse(at) { options[0] }, at.takeIf { it >= 0 }, { onDraft(draft.copy(kind = kinds[it])) },
        typing, onTyping,
    ) {
        when (draft.kind) {
            GoalKind.None -> GoalNote(StartCopy.noGoalNote(hasDistance))
            GoalKind.Distance -> DistanceGoal(draft.meters, { onDraft(draft.copy(meters = it)) }, typing, onTyping)
            GoalKind.Time -> TimeGoal(draft.seconds, { onDraft(draft.copy(seconds = it)) }, typing, onTyping)
            GoalKind.Zone -> ZoneGoal(draft.zones, { onDraft(draft.copy(zones = it)) }, zones, maxHr, onYourZones)
            GoalKind.Intervals -> IntervalsGoal(draft.reps, { onDraft(draft.copy(reps = it)) }, typing, onTyping, saved, onSave)
        }
    }
}

/** One choice in a plan card's pill and its sheet: its icon, its name, and what the strap does with it. */
internal class PlanOption(val icon: ImageVector, val name: String, val note: String)

/**
 * The one plan card every Ready has (boards ReadyFinal, ReadyKindPill, ReadyKindSheet): the family's label over it
 * ([StartCopy.planTitle]), then the choice in one pill ([shown], which may be none of [options] once a value is typed),
 * opening a sheet of every option with what each does, the one [chosen] ticked; then [content], the chosen one's
 * value. While typing ([typing]) the whole card scrolls up over the keyboard, presets and note too.
 */
@Composable
internal fun ReadyPlanCard(
    family: Workouts.Family,
    options: List<PlanOption>,
    shown: PlanOption,
    chosen: Int?,
    onChoose: (Int) -> Unit,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Text(StartCopy.planTitle(family), color = Color.White.copy(alpha = 0.6f), style = GlowType.body, modifier = Modifier.padding(horizontal = 4.dp))
    val inView = remember { BringIntoViewRequester() }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    // Once the keyboard has stopped moving: each frame of it rising starts this again.
    LaunchedEffect(typing, keyboard) { if (typing && keyboard > 0) { kotlinx.coroutines.delay(80); inView.bringIntoView() } }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(inView).clip(GlowLayout.CardShape).background(ListSurface).border(1.dp, RowHairline, GlowLayout.CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        var choosing by rememberSaveable { mutableStateOf(false) }
        KindPill(shown.icon, shown.name, StartCopy.planHint(family)) { onTyping(false); choosing = true }
        if (choosing) PageSheet({ choosing = false }, StartCopy.planSheet(family)) {
            Column {
                options.forEachIndexed { i, o ->
                    KindRow(o.icon, o.name, o.note, i == chosen, divider = i > 0) {
                        // The one already chosen keeps what was typed into it.
                        if (i != chosen) onChoose(i)
                        choosing = false
                    }
                }
            }
        }
        content()
    }
}

private fun kindName(k: GoalKind, type: Int): String = when (k) {
    GoalKind.None -> StartCopy.noGoal(type)
    GoalKind.Distance -> StartCopy.DISTANCE
    GoalKind.Time -> StartCopy.TIME
    GoalKind.Zone -> StartCopy.ZONE
    GoalKind.Intervals -> StartCopy.INTERVALS
}

private fun kindIcon(k: GoalKind, type: Int): ImageVector = when (k) {
    GoalKind.None -> org.openstrap.app.ui.workout.SportIcons.of(type)
    GoalKind.Distance -> AppIcons.Route01
    GoalKind.Time -> AppIcons.Timer
    GoalKind.Zone -> AppIcons.Pulse01
    GoalKind.Intervals -> AppIcons.ChartBar
}

private fun kindNote(k: GoalKind, hasDistance: Boolean): String = when (k) {
    GoalKind.None -> StartCopy.noGoalNote(hasDistance)
    GoalKind.Distance -> StartCopy.DISTANCE_KIND_NOTE
    GoalKind.Time -> StartCopy.TIME_KIND_NOTE
    GoalKind.Zone -> StartCopy.ZONE_KIND_NOTE
    GoalKind.Intervals -> StartCopy.INTERVALS_KIND_NOTE
}

/** The plan's choice in one pill (board ReadyKindPill): its icon in lime, its name and a caret, opening the kinds' sheet. */
@Composable
private fun KindPill(icon: ImageVector, label: String, hint: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(40.dp).clip(CircleShape).background(Raised)
                .pressClickable(onClick, CircleShape).semantics { role = Role.Button; contentDescription = "$label. $hint" }
                .padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, tint = TypingLime, modifier = Modifier.size(18.dp))
            Text(label, color = Color.White, style = GlowType.body, maxLines = 1)
            Icon(AppIcons.CaretDown, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/** A plan's choice in the sheet (board ReadyKindSheet): its icon on a tile, its name, what the strap does, a tick when chosen. */
@Composable
private fun KindRow(icon: ImageVector, label: String, note: String, on: Boolean, divider: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (divider) Modifier.drawBehind { drawLine(RowHairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
            .clickable(onClick = onClick).semantics { selected = on }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) TypingLime.copy(alpha = 0.14f) else Raised),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (on) TypingLime else Color.White, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, style = GlowType.body)
            Text(note, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, modifier = Modifier.padding(top = 2.dp))
        }
        if (on) Icon(AppIcons.CheckCircle, null, tint = TypingLime, modifier = Modifier.size(20.dp))
    }
}

/** The line under a goal: what the strap will do. */
@Composable
internal fun GoalNote(text: String) =
    Text(text, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

/** A row of presets under a value, each as wide as the others, on a hairline; the one matching the value ([on]) in white. */
@Composable
internal fun <T> Presets(presets: List<Pair<T, String>>, on: T?, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { (v, label) ->
            val chosen = v == on
            Box(
                Modifier.weight(1f).height(34.dp).clip(CircleShape).border(1.dp, if (chosen) Color.White else Color.White.copy(alpha = 0.15f), CircleShape)
                    .pressClickable({ onPick(v) }, CircleShape).semantics { role = Role.Button; selected = chosen },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (chosen) Color.White else Color.White.copy(alpha = 0.8f), style = GlowType.secondary, maxLines = 1) }
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.drawBehind {
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
    Presets(StartCopy.DISTANCE_PRESETS, meters.takeIf { !typing }) { stop(); onMeters(it) }
    GoalNote(StartCopy.distanceNote(if (typing) StartCopy.parseDistance(text.text) ?: meters else meters))
}

/**
 * Time: minus and plus by 5 minutes, the value in hours and minutes to tap and type (hours, then on to the minutes by
 * itself), the [presets], and when it would end if you started now, or [note] in its place (mind and body's length).
 */
@Composable
internal fun TimeGoal(
    seconds: Long,
    onSeconds: (Long) -> Unit,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    presets: List<Pair<Long, String>> = StartCopy.TIME_PRESETS,
    note: String? = null,
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    var hours by remember { mutableStateOf(TextFieldValue()) }
    var minutes by remember { mutableStateOf(TextFieldValue()) }
    var focused by remember { mutableStateOf<Int?>(null) }
    var everFocused by remember { mutableStateOf(false) }
    // Set once the hour is typed and the minutes have focus, until the minutes let it go.
    var hoursDone by remember { mutableStateOf(false) }
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
                    // Once on to the minutes, the hours field can still send its old figure as it lets go: ignored.
                    if (hoursDone) return@TypedNumber
                    // The figure just typed, whether the keyboard replaced the selected hour or added after it.
                    hours = v.copy(text = v.text.filter { it.isDigit() }.takeLast(1), selection = TextRange(1).takeIf { v.text.length > 1 } ?: v.selection)
                    // One figure of hours, then on to the minutes.
                    if (hours.text.length == 1 && v.text != selectedText(v)) { hoursDone = true; minutesFocus.requestFocus() }
                }, "h", focused == 0, Modifier.focusRequester(hoursFocus).tracked(0), KeyboardType.Number, ImeAction.Next, { minutesFocus.requestFocus() },
            )
            Spacer(Modifier.width(10.dp))
            TypedNumber(
                minutes, { v -> minutes = v.copy(text = v.text.filter { it.isDigit() }.take(3)) }, "min", focused == 1,
                Modifier.focusRequester(minutesFocus).tracked(1).onFocusChanged { if (!it.isFocused) hoursDone = false }, KeyboardType.Number, ImeAction.Done, { focusManager.clearFocus() },
            )
        } else TapValue(StartCopy.timeParts(seconds), StartCopy.TAP_TO_TYPE) {
            hours = selected("${seconds / 3600}"); minutes = selected("${seconds / 60 % 60}"); everFocused = false; hoursDone = false; onTyping(true)
        }
    }
    Presets(presets, seconds.takeIf { !typing }) { stop(); onSeconds(it) }
    val shown = if (typing) StartCopy.parseTime(hours.text, minutes.text) ?: seconds else seconds
    // Ticks with the clock, so the end time stays right while Ready shows.
    val now by produceState(LocalTime.now()) { while (true) { kotlinx.coroutines.delay(15_000); value = LocalTime.now() } }
    val ends = StartCopy.endsNear(now, shown, android.text.format.DateFormat.is24HourFormat(context))
    GoalNote(if (typing) StartCopy.timeTypingNote(ends) else note ?: ends)
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
    var unit by remember { mutableStateOf(StartCopy.repUnit(reps)) }
    var sheet by rememberSaveable { mutableStateOf<Int?>(null) }
    fun typed(t: List<String>): Structure.Reps {
        val withCount = StartCopy.parseCount(t[0])?.let { reps.copy(rounds = it) } ?: reps
        val withRep = StartCopy.parseRep(t[1], unit, withCount) ?: withCount
        return StartCopy.parseRest(t[2])?.let { withRep.copy(rest = it) } ?: withRep
    }
    val (n, u) = StartCopy.repParts(reps)
    val (restN, restU) = StartCopy.restParts(reps.rest)
    TypedValue(
        listOf(
            NumberField("${reps.rounds}", null, length = 2),
            NumberField(n, u, editUnit = unit.label, length = 5, decimal = true),
            NumberField(restN, restU, edit = "${reps.rest}", editUnit = "s", style = GlowType.goalValueMinor),
        ),
        typing, onTyping, onDone = { onReps(typed(it)) }, onStart = { unit = StartCopy.repUnit(reps) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Number(0)
            Times()
            Number(1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ValueWords("with")
            Number(2)
            ValueWords("rest between")
        }
        if (focused == 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(StartCopy.EACH_REP_IN, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary)
            Spacer(Modifier.width(10.dp))
            StartCopy.RepUnit.entries.forEach { e ->
                UnitChip(e.label, e == unit) { unit = e }
                Spacer(Modifier.width(6.dp))
            }
        }
    }
    if (typing) {
        GoalNote(StartCopy.INTERVALS_TYPING)
        return
    }
    Presets(StartCopy.INTERVAL_PRESETS.map { it to StartCopy.structureName(it) }, reps.copy(warmUp = false)) { onReps(it.warm(reps.warmUp)) }
    GoalNote(StartCopy.intervalsNote(reps))
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

/**
 * One number of a value typed in place: as it shows ([shown] and its [unit]) and as it's typed ([edit] in
 * [editUnit], the rest typed in seconds where it shows "2 min"), in [style], up to [length] figures, with a decimal
 * point when [decimal].
 */
internal data class NumberField(
    val shown: String,
    val unit: String?,
    val edit: String = shown,
    val editUnit: String? = unit,
    val style: TextStyle = GlowType.goalValue,
    val length: Int = 3,
    val decimal: Boolean = false,
)

/** What a [TypedValue]'s lines have: its numbers ([Number]), what's typed so far ([typed], null at rest), the number being typed ([focused]). */
internal class TypedValueScope(val typed: List<String>?, val focused: Int?, private val number: @Composable RowScope.(Int) -> Unit) {
    @Composable
    fun RowScope.Number(i: Int) = number(i)
}

/**
 * A value of several numbers, each typed in place (board ReadyFinalReps): [lines] lay out its numbers ([NumberField],
 * by index) among words. Any number taps to type ([onStart] first), then Next goes on to the next; once none has
 * focus, [onDone] reads what was typed, each number as text. "Tap a number to type it" ([hint]) under it at rest.
 */
@Composable
internal fun TypedValue(
    fields: List<NumberField>,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    onDone: (List<String>) -> Unit,
    onStart: () -> Unit = {},
    hint: String? = StartCopy.TAP_A_NUMBER,
    lines: @Composable TypedValueScope.() -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var texts by remember(fields.size) { mutableStateOf(List(fields.size) { TextFieldValue() }) }
    var focused by remember { mutableStateOf<Int?>(null) }
    var everFocused by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(0) }
    val focus = remember(fields.size) { List(fields.size) { FocusRequester() } }
    EndTyping(typing, focused, everFocused) {
        if (typing) {
            onDone(texts.map { it.text })
            onTyping(false)
        }
    }
    if (typing) LaunchedEffect(Unit) { focus[first].requestFocus() }
    val number: @Composable RowScope.(Int) -> Unit = { i ->
        val f = fields[i]
        val last = i == fields.lastIndex
        if (typing) TypedNumber(
            texts[i], { v -> texts = texts.toMutableList().also { it[i] = v.copy(text = v.text.filter { c -> c.isDigit() || f.decimal && (c == '.' || c == ',') }.take(f.length)) } },
            f.editUnit, focused == i,
            Modifier.focusRequester(focus[i]).onFocusChanged { if (it.isFocused) { focused = i; everFocused = true } else if (focused == i) focused = null },
            if (f.decimal) KeyboardType.Decimal else KeyboardType.Number, if (last) ImeAction.Done else ImeAction.Next,
            { if (last) focusManager.clearFocus() else focus[i + 1].requestFocus() },
            style = f.style, modifier = Modifier.alignByBaseline(),
        ) else TapNumber(f.shown, f.unit, f.style) {
            texts = fields.map { selected(it.edit) }
            first = i; everFocused = false
            onStart()
            onTyping(true)
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (typing) 10.dp else 8.dp)) {
        TypedValueScope(texts.map { it.text }.takeIf { typing }, focused.takeIf { typing }, number).lines()
        if (!typing && hint != null) Text(hint, color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.caption)
    }
}

/** Words between a typed value's numbers, on their baseline. */
@Composable
internal fun RowScope.ValueWords(text: String) =
    Text(text, color = Color.White.copy(alpha = 0.7f), style = GlowType.body, modifier = Modifier.alignByBaseline())

/** The × between a count and what's counted: "6 × 400 m". */
@Composable
internal fun RowScope.Times() =
    Text("×", color = Color.White.copy(alpha = 0.5f), style = GlowType.goalValueMinor, modifier = Modifier.alignByBaseline().padding(horizontal = 4.dp))

private const val SAVE = 0
private const val SAVED = 1

/** One number of the intervals to tap and type, with its [unit] after it, on the shared baseline. */
@Composable
internal fun RowScope.TapNumber(n: String, unit: String?, style: TextStyle, onClick: () -> Unit) {
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
