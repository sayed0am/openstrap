package org.openstrap.app.ui.glow

import org.openstrap.app.R
import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.Vo2maxCopy
import org.openstrap.app.presentation.TimeOfDay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import org.openstrap.app.data.Activity
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.dashboard.ActivityCard
import org.openstrap.app.presentation.ActivityCopy
import org.openstrap.app.ui.dashboard.ActivityHistory
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.ActivityFacts
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.LocalGoals
import org.openstrap.app.ui.dashboard.CaloriesCopy
import org.openstrap.app.presentation.Metric
import org.openstrap.app.ui.dashboard.PARTIAL_DAY
import org.openstrap.app.ui.dashboard.Wear
import org.openstrap.app.presentation.TabCopy
import org.openstrap.app.presentation.TodayCopy
import org.openstrap.app.ui.dashboard.activityCards
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.SportIcons
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.glow.draw.drawHourPills
import org.openstrap.app.ui.glow.draw.hourPills
import androidx.compose.foundation.layout.BoxWithConstraints
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.draw.level
import org.openstrap.app.ui.theme.ControlSurface
import org.openstrap.app.presentation.topSports
import org.openstrap.app.presentation.LoadBands
import org.openstrap.app.data.History
import org.openstrap.app.ui.components.scrub
import org.openstrap.app.ui.components.cardNumber
import org.openstrap.app.ui.dashboard.GoalFacts
import org.openstrap.app.ui.dashboard.GoalMetric
import org.openstrap.app.ui.dashboard.NewsTag
import org.openstrap.app.ui.dashboard.OffStatus
import org.openstrap.app.ui.dashboard.OffWords
import org.openstrap.app.ui.dashboard.bodyOffStatus
import org.openstrap.app.ui.dashboard.goalTag
import org.openstrap.app.ui.dashboard.loadOffStatus
import org.openstrap.app.ui.dashboard.offTag
import org.openstrap.app.ui.dashboard.recoveryOffStatus
import org.openstrap.app.ui.glow.pictures.Blocks
import org.openstrap.app.ui.glow.pictures.DayBars
import org.openstrap.app.ui.glow.pictures.DayLine
import org.openstrap.app.ui.glow.pictures.LevelFill
import org.openstrap.app.ui.glow.pictures.PictureSize
import org.openstrap.app.ui.glow.pictures.dayLineRim
import org.openstrap.app.ui.glow.pictures.levelFillRim
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf

/** Heart points' weekly aim: 100 or more over the last seven days. */
private const val HEART_POINTS_TARGET = ActivityCopy.HEART_POINTS_TARGET

/** Workouts the list shows, the day's first, before "All workouts". */
private const val LISTED_WORKOUTS = 5

/*
 * The Activity tab (spec 5.3): "how much did I move and train". A card to start a sport today, steps glowing, Load
 * against your usual, this week's facts and the workouts ([activityCards]). Every card and row opens its detail; the
 * method behind each number (calories, active minutes, VO2 max, Load's parts) is in their info sheets. Workouts under
 * way and the morning check-in live on Today.
 */
@Composable
fun GlowActivityScreen(
    state: DayUiState,
    recovery: RecoveryResult?,
    workouts: List<WorkoutAnalysis>,
    /** Each day's Heart points, for today's total until the strap's own comes. */
    activity: ActivityHistory,
    header: @Composable () -> Unit,
    contentPadding: PaddingValues,
    onOpenMetric: (Metric) -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onAllWorkouts: () -> Unit,
    onEditProfile: () -> Unit,
    onStartWorkout: (Int) -> Unit = {},
    onAllSports: () -> Unit = {},
    suggestion: org.openstrap.app.data.WorkoutSuggestions.Suggestion? = null,
    onSaveSuggestion: () -> Unit = {},
    onDismissSuggestion: () -> Unit = {},
    onAddWorkout: () -> Unit = {},
    /** The live heart rate, when the live switch is on; lights Heart rate from the right. */
    liveHeartRate: Int? = null,
    /** Per-day history, for your resting range behind the heart rate line; null leaves the band out. */
    history: org.openstrap.app.ui.dashboard.DailyHistory? = null,
    /** Opens a detail over the tab: the Activity details behind the Small pair, Load and the Today list. */
    onOpen: (org.openstrap.app.ui.dashboard.Detail) -> Unit = {},
    /** Your usual steps by the end of each hour ([org.openstrap.app.data.UsualSteps]); null until there's a usual. */
    usualSteps: List<Float>? = null,
    /** How much of the day the strap was worn, for the Partial day tag ([org.openstrap.app.ui.dashboard.wear]). */
    wear: Wear? = null,
) {
    val open = { s: Score -> onOpen(Detail.OfScore(s)) }
    val list = rememberLazyListState()
    val isToday = state.date == LocalDate.now()
    // Your three most used first, as on a phone; a wider card goes on with the rest you use, then the everyday sports,
    // one tile per sport whichever kind.
    val sports = remember(workouts) {
        val shown = topSports(workouts).map { it.sportType }.toMutableList()
        (topSports(workouts, MORE_BY_USE).map { it.sportType } + Workouts.EVERYDAY).forEach { t ->
            if (shown.none { Workouts.tileType(it) == Workouts.tileType(t) }) shown += t
        }
        shown.toList()
    }
    val stored = activity.heartPointsOn(state.date)
    val heartPoints = state.pai?.total ?: stored[state.date]?.total
    val r = recovery?.takeIf { it.date == state.date }
    val live = liveHeartRate?.takeIf { isToday }
    val slots = activityCards(ActivityFacts(isToday = isToday, suggestion = suggestion != null, live = live != null))
    val restingRange = rememberNormal(Metric.RestingHr, state.date, history)
    // A partial day lowers the steps and the day's totals: those two cards carry the tag (spec 4.3).
    var partialInfo by rememberSaveable { mutableStateOf(false) }
    // Built once, so the cards given it can skip (audit K11).
    val partialTag = remember { TitleTag(PARTIAL_DAY) { partialInfo = true } }
    val partial = partialTag.takeIf { wear?.partial == true }
    if (partialInfo && wear != null) PartialDaySheet(wear, isToday, onDismiss = { partialInfo = false })
    CompositionLocalProvider(LocalGlowPaused provides { list.isScrollInProgress }) {
        GlowScreenList(contentPadding, list) {
            add(Pane.Full, GlowBlocks.HEADER) { header() }
            tabCards(slots) { card, m ->
                when (card) {
                    ActivityCard.Start -> StartCard(sports, onStartWorkout, onAllSports)
                    ActivityCard.Steps -> StepsGlow(state, usualSteps, activity, partial) { onOpenMetric(Metric.Steps) }
                    ActivityCard.ActiveMinutes -> r?.activity.let { a ->
                        // Blocks toward the week's target, a block per 5 minutes. A target, never off: no rim.
                        PictureSmall(
                            MetricColors.steps, stringResource(R.string.glow_activity_active_minutes), a?.weekEquivalent?.toString(),
                            stringResource(R.string.glow_unit_min).takeIf { a != null },
                            a?.let { TabCopy.activeMinutes(it.weekEquivalent, Activity.MVPA_WEEKLY_TARGET) } ?: NO_READING, m,
                            onClick = { open(Score.ActiveMinutes) },
                            picture = a?.let { w ->
                                { pm -> Blocks(w.weekEquivalent, MetricColors.steps, PictureSize.Small, pm, target = Activity.MVPA_WEEKLY_TARGET) }
                            },
                        )
                    }
                    ActivityCard.HeartPoints -> {
                        val week = remember(stored, state.date, state.pai) { heartPointsWeek(state.date, stored, state.pai?.daily) }
                        // The last 7 days as bars, today's in full ink. The aim is the week's total, so no line per day.
                        PictureSmall(
                            MetricColors.heart, stringResource(R.string.glow_activity_heart_points),
                            heartPoints?.roundToInt()?.toString(), null,
                            heartPoints?.let { TabCopy.heartPoints(it.roundToInt(), HEART_POINTS_TARGET) } ?: AFTER_NEXT_SYNC, m,
                            onClick = { open(Score.HeartPoints) },
                            picture = week.takeIf { days -> days.any { it != null } }?.let { days ->
                                { pm -> DayBars(days, MetricColors.heart, PictureSize.Small, pm) }
                            },
                        )
                    }
                    ActivityCard.Load -> DayLoadCard(r) { open(Score.Load) }
                    ActivityCard.HeartRate -> HeartRateCard(
                        state, live, restingRange, rememberNormal(Metric.HeartRate, state.date, history),
                    ) { onOpenMetric(Metric.HeartRate) }
                    ActivityCard.Facts -> DayFactsList(state, r, workouts, isToday, onEditProfile, open, partial)
                    ActivityCard.Workouts -> WorkoutsList(
                        state.date, workouts, suggestion?.takeIf { isToday }, onOpenWorkout, onAllWorkouts, onAddWorkout,
                        onSaveSuggestion, onDismissSuggestion,
                    )
                }
            }
        }
    }
}

/* ----------------------------------------------- Start ---------------------------------------------- */

/**
 * Start a workout (spec 5.3): a Wide edge card in the neutral light from below, its title, then your three most used
 * sports as 64 dp tiles with their names under them and All sports after them. Words with the icons, since an icon
 * alone doesn't say "start". On a wider card (unfolded) as many more tiles as fit at a phone's spacing, your other
 * sports and then the everyday ones ([sports] in that order). A sport opens Ready with it chosen ([onStart]); All
 * sports opens Choose with every sport. The tiles are the buttons, so the card itself opens nothing and has no caret.
 * Today only.
 */
@Composable
private fun StartCard(sports: List<Int>, onStart: (Int) -> Unit, onAllSports: () -> Unit) {
    // Unlit (its look), with the heart-red light travelling round its border, as on Today.
    PictureCard(EdgeNeutral, Modifier.fillMaxWidth().movingBorder(MetricColors.heart)) {
        EdgeTitle(stringResource(R.string.glow_activity_start_workout), null)
        Spacer(Modifier.height(GlowLayout.TitleGap))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            // Four on a phone; wider, as many as fit at a phone's column, so the tiles never spread apart, centred.
            val columns = maxOf(4, ((maxWidth + StartTileGap) / (StartTileColumn + StartTileGap)).toInt())
            Row(
                Modifier.widthIn(max = StartTileColumn * columns + StartTileGap * (columns - 1)).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StartTileGap),
            ) {
                sports.take(columns - 1).forEach { type ->
                    SportTile(SportIcons.of(type), Workouts.sportName(type), Modifier.weight(1f), outdoor = type in Workouts.OUTDOOR) {
                        onStart(type)
                    }
                }
                SportTile(
                    AppIcons.MoreHorizontalCircle01, stringResource(R.string.glow_activity_all_sports), Modifier.weight(1f),
                    outdoor = false, onClick = onAllSports,
                )
            }
        }
    }
}

/** A tile's column on a phone: four tiles and their gaps in 344 dp. */
private val StartTileColumn = 80.dp
private val StartTileGap = 8.dp

/** How many sports by use a wide Start card offers before the everyday ones pad it. */
private const val MORE_BY_USE = 8


/* ----------------------------------------------- Steps ---------------------------------------------- */

/**
 * Steps as a level glow (spec 2.1 Levels, 2.6, 5.3): the light rises from below to the share of the goal walked, the
 * dark core above it, like Charge. The title with the goal as its note, the day's steps hour by hour in the dark, then
 * the number at the foot in the light with how far toward the goal and, once there is one, against your [usual] by now
 * (compare by pace). Tapping an hour puts its steps in the number; tapping it again, or another card, puts the day
 * back. Opens the Steps detail.
 */
@Composable
private fun StepsGlow(state: DayUiState, usual: List<Float>?, history: ActivityHistory, tag: TitleTag?, onClick: () -> Unit) {
    val goal = LocalGoals.current.steps
    // The goal tag after the number once met, climbing the ladder (spec 2.5, Tags), none while the history loads.
    val goalTag = history.stepsFacts(state.date, value = state.steps, goal = goal)?.let { goalTag(GoalMetric.Steps, it) }
    val share = if (goal > 0) state.steps / goal.toFloat() else 0f
    val isToday = state.date == LocalDate.now()
    val now = java.time.LocalTime.now()
    val pace = ActivityCopy.paceOf(state.steps, usual, isToday, now.hour, now.minute)
    var hour by remember(state.date, state.hourlySteps) { mutableStateOf<Int?>(null) }
    GlowCard(GlowStyle.level(MetricColors.steps, share), Modifier.fillMaxWidth().gridSize(CardSize.Wide), onClick = onClick) {
        Column {
            GlowHeader(
                stringResource(R.string.glow_activity_steps), trailing = stringResource(R.string.glow_activity_goal, Units.count(goal)),
                tag = tag,
            )
            Spacer(Modifier.height(GlowLayout.TitleGap))
            HourPills(state.hourlySteps, hour) { hour = it }
            Spacer(Modifier.height(16.dp))
            // A picked hour takes the number's place; one dotted number either way (spec 2.3). A level glow isn't the
            // screen's hero, so its number is the second glow's size, as on the Charge battery.
            val h = hour
            val picked = h?.let { state.hourlySteps.getOrNull(it) }
            // The tag is the day's, so a picked hour leaves it out.
            val (count, label) = if (h != null && picked != null) Math.round(picked) to ActivityCopy.hourLabel(h)
            else state.steps to ActivityCopy.activityLine(state.steps, goal, pace, isToday)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TaggedNumber(Units.count(count, Units.DOT_LOCALE), null, goalTag.takeIf { h == null || picked == null }, dot = DotSize.Second)
                Text(
                    label,
                    color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 2,
                )
            }
        }
    }
}

/**
 * Hourly pill bars (chart kit, spec 2.7): the day's steps as 24 pills, one an hour, as high as the hour's share of the
 * busiest, white at 45% with the latest hour with steps at full. Hours with none, and those still to come, aren't
 * drawn, only the hairline baseline under the bars. Tapping an hour picks it ([onPick]) and lights it in place of the
 * latest; tapping it again lets it go. The hours under it in Caption.
 */
@Composable
private fun HourPills(hourly: List<Float>, picked: Int?, onPick: (Int?) -> Unit) {
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(HourPillsHeight).pointerInput(hourly, picked) {
                detectTapGestures { o ->
                    if (hourly.isEmpty()) return@detectTapGestures
                    val h = (o.x / (size.width.toFloat() / hourly.size)).toInt().coerceIn(0, hourly.lastIndex)
                    // A tap, not a scrub: no tick (spec 3.6).
                    onPick(if (h == picked) null else h)
                }
            },
        ) {
            // The shared pill bars ([hourPills]), as a widget's chart draws them.
            hourPills(hourly, picked, size.width, size.height, density)?.let { drawHourPills(it) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach {
                Text(it, color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.caption)
            }
        }
    }
}

private val HourPillsHeight = 56.dp

/* ------------------------------------------------ Load ---------------------------------------------- */

/**
 * Load today as a level fill over your usual band ([LoadBands.usualRange], spec 2.7), "12 today", "Your usual is 40"
 * and the week's balance in a word beside the title. Off your usual it carries the rim at the fill's end and "Above
 * usual" or "Below usual" after the number. A low Load is only so far until the day is over, so today never says Below
 * ([loadOffStatus]). Opens the Load detail, where Strain, fitness and fatigue live. TODO(step 6): the ghost line of
 * your usual Load by this time needs past days' Load hour by hour. The engine keeps each past day's Load only as a
 * total ([org.openstrap.app.data.Recovery.dailyLoad]); hours would mean re-running it 24 times for each of 30 days in
 * every recompute, with nothing to cache them in.
 */
@Composable
private fun DayLoadCard(r: RecoveryResult?, onClick: () -> Unit) {
    val usual = r?.chronicLoad?.takeIf { it > 0f }
    val band = LoadBands.usualRange(usual)
    val load = r?.load
    val past = r != null && r.date != LocalDate.now()
    val tag = offTag(OffWords.Usual, loadOffStatus(load, usual, endOfDay = past))
    val rim = if (tag != null && load != null) levelFillRim(load, band, PictureSize.Wide) else null
    PictureCard(MetricColors.load, Modifier.fillMaxWidth().gridSize(CardSize.Wide), rim = rim, onClick = onClick) {
        EdgeTitle(stringResource(R.string.glow_activity_load), TabCopy.loadBalance(r?.loadTrend))
        if (load != null) {
            Spacer(Modifier.height(GlowLayout.TitleGap))
            LevelFill(load, band, MetricColors.load, PictureSize.Wide, Modifier.fillMaxWidth().height(LevelFillHeight))
        }
        TaggedNumber(
            load?.roundToInt()?.toString(),
            stringResource(if (past) R.string.glow_activity_load_that_day else R.string.glow_activity_load_today), tag,
            Modifier.padding(top = 14.dp),
        )
        StatusLine(if (r == null) NO_READING else TabCopy.loadUsual(usual))
    }
}

/** The level fill's height on the Load card: its 16 dp band with room for the dot's glow. */
private val LevelFillHeight = 28.dp

/* --------------------------------------------- Heart rate ------------------------------------------- */

/**
 * Heart rate (spec 5.3, 6): the day's line through your [resting] range as a dim band ([DayLine]), the number under it
 * following the finger while you hold the line. When the latest reading is off your heart rate [normal] the rim lights
 * at the line's end, over the top when above and the foot when below, with "Above normal" or "Below normal" after the
 * number (spec 2.5). Opens the heart rate detail.
 */
@Composable
private fun HeartRateCard(state: DayUiState, live: Int?, resting: History.Normal?, normal: History.Normal?, onClick: () -> Unit) {
    val step = HEART_STEP_SECONDS
    val values = remember(state.heartRate, state.dayStart) { bucket(state.heartRate, state.dayStart..state.dayEnd, step) }
    var picked by remember(values) { mutableStateOf<Int?>(null) }
    var width by remember { mutableIntStateOf(0) }
    val pad = with(LocalDensity.current) { LinePad.toPx() }
    val scrubbed = picked?.let { i -> values.getOrNull(i)?.let { it.roundToInt() to state.dayStart + i * step } }
    val now = scrubbed?.first ?: live ?: state.latestHeartRate
    val status = bodyOffStatus(state.latestHeartRate?.toFloat(), normal)
    val tag = offTag(OffWords.Normal, status)
    val rim = if (tag != null) dayLineRim(values, status == OffStatus.Above, PictureSize.Wide) else null
    PictureCard(MetricColors.heart, Modifier.fillMaxWidth().gridSize(CardSize.Wide), rim = rim, onClick = onClick) {
        EdgeTitle(
            stringResource(R.string.glow_today_heart_rate), state.restingHeartRate?.let { stringResource(R.string.glow_today_resting, it) },
        )
        if (state.heartRate.size >= 2) {
            Spacer(Modifier.height(GlowLayout.TitleGap))
            DayLine(
                values, resting?.let { it.low..it.high }, MetricColors.heart, PictureSize.Wide,
                Modifier
                    .fillMaxWidth()
                    .height(DayLineHeight)
                    .onSizeChanged { width = it.width }
                    .scrub(values) { x -> picked = x?.let { nearestReading(values, it, width.toFloat(), pad) } },
            )
        }
        // The tag is about now, so a scrubbed point leaves it out.
        TaggedNumber(
            now?.toString(), stringResource(R.string.glow_unit_bpm), tag.takeIf { scrubbed == null }, Modifier.padding(top = 14.dp),
        )
        val at = scrubbed?.second ?: state.heartRate.lastOrNull()?.x
        StatusLine(TodayCopy.heartAt(live != null && scrubbed == null, at?.let(::clock)))
    }
}

/** The heart rate line's buckets: 10 minutes each. */
private const val HEART_STEP_SECONDS = 10 * 60L

/** The line's height on the card and the inset [DayLine] draws inside its edges. */
private val DayLineHeight = 70.dp
private val LinePad = 4.dp

/* ---------------------------------------------- Smalls ---------------------------------------------- */

/**
 * A Small with its picture (spec 2.5, Picture card): the title, the [picture] in the ink filling what the card has
 * left, the number in dots with its [unit], and the [status] in Secondary. Without a picture (waiting) the number sits
 * at the foot. Never lit: Active minutes and Heart points are targets, not readings with a normal.
 */
@Composable
private fun PictureSmall(
    ink: Color, title: String, value: String?, unit: String?, status: String, modifier: Modifier, onClick: () -> Unit,
    picture: (@Composable (Modifier) -> Unit)?,
) {
    PictureCard(ink, modifier, onClick = onClick) {
        CardTitle(title)
        if (picture != null) {
            Spacer(Modifier.height(10.dp))
            picture(Modifier.fillMaxWidth().weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        TaggedNumber(value, unit, null, Modifier.padding(top = 8.dp))
        StatusLine(status)
    }
}

/**
 * Heart points earned on each of the 7 days ending on [date], oldest first: the stored days, with [today]'s from the
 * strap's latest record when there is one. Null for a day with none stored.
 */
internal fun heartPointsWeek(
    date: LocalDate, stored: Map<LocalDate, org.openstrap.app.ui.dashboard.HeartPointsDay>, today: Float?,
): List<Float?> = (6L downTo 0L).map { back ->
    val d = date.minusDays(back)
    if (back == 0L) today ?: stored[d]?.daily else stored[d]?.daily
}

/* ---------------------------------------------- Numbers --------------------------------------------- */

/**
 * A card's number in dots with its [unit] and news [tag] after it ([NumberWithTag]), or the waiting dash. The number
 * stays white, whatever the tag says (spec 2.2).
 */
@Composable
private fun TaggedNumber(value: String?, unit: String?, tag: NewsTag?, modifier: Modifier = Modifier, dot: DotSize = DotSize.Edge) {
    NumberWithTag(tag.takeIf { value != null }, modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (value != null) DotMatrixText(value, Modifier.cardNumber(value), dot = dot) else WaitingDash(Modifier.padding(vertical = 8.dp))
            if (unit != null && value != null) {
                Text(unit, color = Color.White.copy(alpha = 0.7f), style = GlowType.secondary, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** A card's status line, in Secondary. */
@Composable
private fun StatusLine(text: String) {
    Text(
        text, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
        modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

/* ------------------------------------------------ Facts --------------------------------------------- */

/**
 * The day's smaller facts: VO2 max, time to recover, calories and distance, each opening its detail. Active minutes and
 * Heart points are the Small pair above.
 */
// One branch per fact and its missing case, as flat as the list reads. It was FactsList before the rename.
@Suppress("CyclomaticComplexMethod")
@Composable
private fun DayFactsList(
    state: DayUiState, r: RecoveryResult?, workouts: List<WorkoutAnalysis>, isToday: Boolean, onEditProfile: () -> Unit, open: (Score) -> Unit,
    /** The Partial day tag: calories and distance are the day's totals. */
    tag: TitleTag? = null,
) {
    val waiting = NO_READING
    val note = if (isToday) stringResource(R.string.glow_activity_so_far) else null
    ListPanel(title = stringResource(R.string.glow_activity_today), note = note, tag = tag) {
        val a = r?.activity
        // The latest workout the strap read VO2 max from; the estimate from your profile otherwise.
        val fromWorkouts = workouts.sortedByDescending { it.workout.start }
            .firstNotNullOfOrNull { w -> (w.workout.vo2max ?: w.effect?.vo2max?.roundToInt())?.takeIf { it > 0 } }
        val est = r?.vo2max
        ListRow(
            stringResource(R.string.glow_activity_vo2max), MetricColors.heart,
            fromWorkouts?.toString() ?: est?.let { Vo2maxCopy.estimate(it.low, it.high) },
            when {
                fromWorkouts != null -> stringResource(R.string.glow_activity_vo2max_from_workouts)
                est != null -> est.band?.let { stringResource(R.string.glow_activity_vo2max_band, Vo2maxCopy.band(it)) }
                    ?: stringResource(R.string.glow_activity_vo2max_estimated)
                else -> stringResource(R.string.glow_activity_vo2max_after_workout)
            },
            divider = false, onClick = { open(Score.Vo2Max) }, icon = Score.Vo2Max.rowIcon(),
        )
        val recover = latestRecoveryEstimate(workouts)?.let { w ->
            val now = System.currentTimeMillis() / 1000
            TodayCopy.recoverText(TodayCopy.recoverLeft(w.end, w.recoveryMinutes ?: 0, now), now, ZoneId.systemDefault())
        }
        // Recovered is the whole answer: no number, no dash.
        ListRow(
            stringResource(R.string.glow_activity_time_to_recover), MetricColors.load, recover?.first,
            recover?.second ?: stringResource(R.string.glow_activity_no_recent_workout), dash = recover?.first != null,
            onClick = { open(Score.Recover) }, icon = Score.Recover.rowIcon(),
        )
        // Active kcal leads, the total under it; the ring in the icon's place fills to the goal (calories spec 4.4).
        val e = r?.calories
        val goal = LocalGoals.current.calories
        val active = e?.activeKcal?.roundToInt()
        // Its goal tag in the status's place once met: no bests are stored for calories, so it climbs no higher than
        // Double goal, and stops at Goal met on a day Load is above your usual or Recovery below it (spec 2.5).
        val easeOff = r != null && (
            loadOffStatus(r.load, r.chronicLoad?.takeIf { it > 0f }, endOfDay = !isToday) == OffStatus.Above ||
                recoveryOffStatus(r) == OffStatus.Below
            )
        val caloriesTag = active?.let { goalTag(GoalMetric.Calories, GoalFacts(it, goal), easeOff) }
        ListRow(
            stringResource(R.string.glow_activity_calories), MetricColors.calories, active?.let { Units.count(it) },
            e?.let { CaloriesCopy.rowLine(active ?: 0, goal, it.totalKcal.roundToInt()) }
                ?: stringResource(R.string.glow_activity_add_profile_to_see),
            tag = caloriesTag,
            onClick = if (e == null && r != null) onEditProfile else { { open(Score.Calories) } }, dash = e != null,
            icon = Score.Calories.rowIcon(), ring = active?.let { CaloriesCopy.share(it, goal) },
        )
        // Ours from step length and height when we can; the strap's own figure otherwise. Always approximate.
        val distance = a?.distanceMeters ?: state.distanceMeters?.toFloat()
        ListRow(
            stringResource(R.string.glow_activity_distance), MetricColors.steps, distance?.let { Units.km(it, Units.Precision.Day) },
            if (distance == null) waiting else ActivityCopy.distanceSource(a?.distanceMeters != null),
            onClick = { open(Score.Distance) }, icon = Score.Distance.rowIcon(),
        )
    }
}

/* ---------------------------------------------- Workouts -------------------------------------------- */

/**
 * The day's workouts and the latest before it, a workout to save when the strap noticed one, then ways to add one
 * and to see them all. No scrolling window inside: the list is short and "All workouts" is always there. The rows sit
 * under day headers ("Today", "Yesterday", "Wed 21 Oct") rather than each saying its date.
 */
@Composable
private fun WorkoutsList(
    date: LocalDate, workouts: List<WorkoutAnalysis>, suggestion: org.openstrap.app.data.WorkoutSuggestions.Suggestion?,
    onOpen: (Long) -> Unit, onAll: () -> Unit, onAdd: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    fun dayOf(a: WorkoutAnalysis) = Instant.ofEpochSecond(a.workout.start).atZone(zone).toLocalDate()
    // Newest first within the day too, as under every day before it.
    val today = workouts.filter { dayOf(it) == date }.sortedByDescending { it.workout.start }
    val before = workouts.filter { dayOf(it) < date }.sortedByDescending { it.workout.start }
    val shown = today + before.take((LISTED_WORKOUTS - today.size).coerceAtLeast(0))
    // From every workout (newest first), so a row glows the same here and in All workouts.
    val usual = remember(workouts) { Workouts.usualLoad(workouts.map { it.workout.trainingLoad }) }
    val note = if (today.isNotEmpty()) pluralStringResource(R.plurals.glow_activity_this_day, today.size, today.size) else null
    ListPanel(title = stringResource(R.string.glow_activity_workouts), note = note) {
        var first = true
        fun divider() = !first.also { first = false }
        suggestion?.let { SuggestionRow(it, onSave, onDismiss, divider()) }
        if (shown.isEmpty()) ListRow(
            stringResource(R.string.glow_activity_no_workouts), MetricColors.heart, null,
            stringResource(R.string.glow_activity_no_workouts_note), dash = false, divider = divider(),
        )
        var day: LocalDate? = null
        shown.forEach { a ->
            if (dayOf(a) != day) {
                day = dayOf(a)
                WorkoutDayHeader(TabCopy.workoutDay(dayOf(a), LocalDate.now()))
                // The first row under a header has no hairline: the header already parts it from the day before.
                first = true
            }
            WorkoutRow(a, usual, divider()) { onOpen(a.workout.start) }
        }
        PageRow(stringResource(R.string.glow_activity_add_workout), onClick = onAdd, divider = divider())
        PageRow(stringResource(R.string.glow_activity_all_workouts), onClick = onAll)
    }
}

/** A day's header in a workout list: small dim text, no hairline. */
@Composable
internal fun WorkoutDayHeader(text: String) {
    Text(
        text, color = Color.White.copy(alpha = 0.5f), style = GlowType.secondary.copy(fontSize = 12.sp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
    )
}

/** The effort light's tile, as the mock draws it: 38 dp, 12 dp corners, a shade above the panel. */
private val EffortTile = 38.dp
private val EffortShape = RoundedCornerShape(12.dp)
private val EffortSurface = Color(0xFF151517)
private val EffortGap = 12.dp

/**
 * A workout as a list row: its sport's toned white icon on the effort light (a heart-red glow as strong as the
 * workout's Load against your [usual], [Workouts.effort]), the sport with "Auto-detected" when the strap logged
 * it by itself, the sport's one fact and the start time ([TabCopy.workoutFact]), and the duration in dots. A thin zone
 * bar under it, lined up with the name. [divider] draws the hairline above.
 */
@Composable
internal fun WorkoutRow(a: WorkoutAnalysis, usual: Float?, divider: Boolean, onClick: () -> Unit) {
    val fact = TabCopy.workoutFact(
        TabCopy.sportFact(a.workout.sportType), a.distanceMeters, a.distanceFromSteps,
        a.workout.exerciseSeconds?.toLong(), a.sets, a.workout.averageHeartRate,
    )
    val start = Instant.ofEpochSecond(a.shownStart).atZone(ZoneId.systemDefault())
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .topHairline(divider, RowHairline),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            EffortLight(SportIcons.of(a.workout.sportType), Workouts.effort(a.workout.trainingLoad, usual))
            Spacer(Modifier.width(EffortGap))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.sportName, color = Color.White, style = GlowType.body,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!a.startedByYou) {
                        Spacer(Modifier.width(6.dp))
                        OutlineTag(TabCopy.AUTO_DETECTED)
                    }
                }
                Text(
                    TabCopy.workoutLine(fact, TimeOfDay.format(start.toLocalTime())),
                    color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(workoutClock(a.activeSeconds), color = Color.White, style = GlowType.rowValue, maxLines = 1)
            Spacer(Modifier.width(RowCaretGap))
            TapCaret(ROW_CARET_ALPHA)
        }
        // Zone bar (chart kit): how the workout's time was spent, thin under its row, lined up with its name.
        ZoneBar(a.zoneSeconds, ZONE_COLORS, Modifier.padding(start = EffortTile + EffortGap, bottom = 10.dp), height = 4.dp)
    }
}

/**
 * The effort light: the sport's icon on a small dark tile, a soft heart-red glow behind it, centred a little low as
 * light from below would be. [effort] (0..1) sets the glow's strength.
 */
@Composable
private fun EffortLight(icon: androidx.compose.ui.graphics.vector.ImageVector, effort: Float) {
    Box(
        Modifier
            .size(EffortTile)
            .clip(EffortShape)
            .background(EffortSurface)
            .drawBehind {
                val centre = Offset(size.width / 2, size.height * 0.6f)
                drawRect(
                    Brush.radialGradient(
                        0f to MetricColors.heart.copy(alpha = 0.7f * effort.coerceIn(0f, 1f)), 1f to Color.Transparent,
                        center = centre, radius = size.minDimension * 0.56f,
                    ),
                )
            }
            .border(1.dp, Color.White.copy(alpha = 0.06f), EffortShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(RowIcon))
    }
}

/** A stretch nobody started, as a row with Save: when it was and how hard. */
@Composable
private fun SuggestionRow(s: org.openstrap.app.data.WorkoutSuggestions.Suggestion, onSave: () -> Unit, onDismiss: () -> Unit, divider: Boolean) {
    Column {
        ListRow(
            stringResource(R.string.glow_activity_suggestion), ZONE_COLORS[s.zone], null,
            stringResource(R.string.glow_activity_suggestion_line, Ranges.times(s.start, s.end), s.zone + 1, s.averageHeartRate),
            dash = false, divider = divider, icon = AppIcons.Heartbeat,
        )
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageButton(stringResource(R.string.glow_save), onSave, primary = true, size = ButtonSize.Row)
            PageButton(stringResource(R.string.glow_activity_not_a_workout), onDismiss, size = ButtonSize.Row, onCard = true)
            Box(Modifier.weight(1f))
        }
    }
}
