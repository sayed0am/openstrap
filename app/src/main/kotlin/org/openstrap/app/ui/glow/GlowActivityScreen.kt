package org.openstrap.app.ui.glow

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
import androidx.compose.runtime.collectAsState
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
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import org.openstrap.app.data.Activity
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.DailyPai
import org.openstrap.app.ui.dashboard.ActivityCard
import org.openstrap.app.ui.dashboard.ActivityCopy
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.ActivityFacts
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.LocalGoals
import org.openstrap.app.ui.dashboard.CaloriesCopy
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.dashboard.PARTIAL_DAY
import org.openstrap.app.ui.dashboard.Wear
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.activityCards
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.SportIcons
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.glow.draw.drawHourPills
import org.openstrap.app.ui.glow.draw.hourPills
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/** Heart points' weekly aim: 100 or more over the last seven days. */
private const val HEART_POINTS_TARGET = ActivityCopy.HEART_POINTS_TARGET

/** Workouts the list shows, the day's first, before "All workouts". */
private const val LISTED_WORKOUTS = 5

/** Each day's Heart points (the strap's PAI) between two dates, keyed by date. */
typealias PaiHistory = (LocalDate, LocalDate) -> Flow<Map<LocalDate, DailyPai>>

/** Each day's stored energy ([org.openstrap.app.data.DailyEnergyHistory]) between two dates, keyed by date. */
typealias EnergyHistory = (LocalDate, LocalDate) -> Flow<Map<LocalDate, org.openstrap.app.data.db.DailyEnergyRow>>

/*
 * The Activity tab (spec 5.3): "how much did I move and train". A card to start a sport today, steps glowing, Load
 * against your usual, this week's facts and the workouts ([activityCards]). Every card and row opens its detail; the
 * method behind each number (energy, active minutes, VO2 max, Load's parts) is in their info sheets. Workouts under
 * way and the morning check-in live on Today.
 */
@Composable
fun GlowActivityScreen(
    state: DayUiState,
    recovery: RecoveryResult?,
    workouts: List<WorkoutAnalysis>,
    paiHistory: PaiHistory,
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
    val sports = remember(workouts) { topSports(workouts) }
    val stored = remember(state.date) { paiHistory(state.date, state.date) }.collectAsState(emptyMap()).value
    val heartPoints = state.pai?.total ?: stored[state.date]?.total
    val r = recovery?.takeIf { it.date == state.date }
    val live = liveHeartRate?.takeIf { isToday }
    val slots = activityCards(ActivityFacts(isToday = isToday, suggestion = suggestion != null, live = live != null))
    val restingRange = rememberNormal(Metric.RestingHr, state.date, history)
    // A partial day lowers the steps and the day's totals: those two cards carry the tag (spec 4.3).
    var partialInfo by rememberSaveable { mutableStateOf(false) }
    val partial = wear?.takeIf { it.partial }?.let { TitleTag(PARTIAL_DAY) { partialInfo = true } }
    if (partialInfo && wear != null) PartialDaySheet(wear, isToday, onDismiss = { partialInfo = false })
    CompositionLocalProvider(LocalGlowPaused provides { list.isScrollInProgress }) {
        GlowScreenList(contentPadding, list) {
            full { header() }
            tabCards(slots) { card, m ->
                when (card) {
                    ActivityCard.Start -> StartCard(sports, onStartWorkout, onAllSports)
                    ActivityCard.Steps -> StepsGlow(state, usualSteps, partial) { onOpenMetric(Metric.Steps) }
                    ActivityCard.ActiveMinutes -> r?.activity.let { a ->
                        SmallCard(
                            MetricColors.steps, tr(R.string.common_active_minutes), a?.weekEquivalent?.toString(), null,
                            a?.let { TabCopy.activeMinutes(it.weekEquivalent, Activity.MVPA_WEEKLY_TARGET) } ?: tr(R.string.common_no_reading_yet), m,
                            onClick = { open(Score.ActiveMinutes) },
                        )
                    }
                    ActivityCard.HeartPoints -> SmallCard(
                        MetricColors.heart, tr(R.string.common_heart_points), heartPoints?.roundToInt()?.toString(), null,
                        heartPoints?.let { TabCopy.heartPoints(it.roundToInt(), HEART_POINTS_TARGET) } ?: tr(R.string.common_after_the_strap_s), m,
                        onClick = { open(Score.HeartPoints) },
                    )
                    ActivityCard.Load -> LoadCard(r) { open(Score.Load) }
                    ActivityCard.HeartRate -> HeartCard(state, live, { onOpenMetric(Metric.HeartRate) }, restingRange = restingRange)
                    ActivityCard.Facts -> FactsList(state, r, workouts, isToday, onEditProfile, open, partial)
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
 * alone doesn't say "start". A sport opens Ready with it chosen ([onStart]); All sports opens Choose a sport. The
 * tiles are the buttons, so the card itself opens nothing and has no caret. Today only.
 */
@Composable
private fun StartCard(sports: List<SportUse>, onStart: (Int) -> Unit, onAllSports: () -> Unit) {
    // Unlit (its look), with the heart-red light travelling round its border, as on Today.
    EdgeCard(EdgeNeutral, Modifier.fillMaxWidth().movingBorder(MetricColors.heart), kind = EdgeKind.Value) {
        EdgeTitle(tr(R.string.common_start_a_workout), null)
        Spacer(Modifier.height(GlowLayout.TitleGap))
        // Four even columns up to the phone's width, so the tiles keep together on an unfolded screen.
        Row(Modifier.widthIn(max = StartTilesWidth).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sports.take(3).forEach { s ->
                SportTile(SportIcons.of(s.sportType), Workouts.sportName(s.sportType), outdoor = s.sportType in Workouts.OUTDOOR, Modifier.weight(1f)) {
                    onStart(s.sportType)
                }
            }
            SportTile(AppIcons.MoreHorizontalCircle01, tr(R.string.common_all_sports), outdoor = false, Modifier.weight(1f), onClick = onAllSports)
        }
    }
}

/** Four tiles and their gaps at most: a phone's width inside the card. */
private val StartTilesWidth = 344.dp

private val SportTileSize = 64.dp
private val SportTileShape = RoundedCornerShape(GlowLayout.TileCorner)

/**
 * A sport as a tile: its icon on a 64 dp Control tile, its name in Secondary under it, up to two lines. An [outdoor]
 * sport carries a small location mark in the tile's corner.
 */
@Composable
private fun SportTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, outdoor: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    Column(
        modifier.pressClickable(onClick, SportTileShape).semantics(mergeDescendants = true) { role = Role.Button },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(SportTileSize).background(ControlSurface, SportTileShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            if (outdoor) Icon(
                AppIcons.Location01, null, tint = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(12.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 2,
            textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ----------------------------------------------- Steps ---------------------------------------------- */

/**
 * Steps as a level glow (spec 2.1 Levels, 2.6, 5.3): the light rises from below to the share of the goal walked, the
 * dark core above it, like Charge. The title with the goal as its note, the day's steps hour by hour in the dark, then
 * the number at the foot in the light with how far toward the goal and, once there is one, against your [usual] by now
 * (compare by pace). Tapping an hour puts its steps in the number; tapping it again, or another card, puts the day
 * back. Opens the Steps detail.
 */
@Composable
private fun StepsGlow(state: DayUiState, usual: List<Float>?, tag: TitleTag?, onClick: () -> Unit) {
    val goal = LocalGoals.current.steps
    val share = if (goal > 0) state.steps / goal.toFloat() else 0f
    val isToday = state.date == LocalDate.now()
    val now = java.time.LocalTime.now()
    val pace = ActivityCopy.paceOf(state.steps, usual, isToday, now.hour, now.minute)
    var hour by remember(state.date, state.hourlySteps) { mutableStateOf<Int?>(null) }
    GlowCard(GlowStyle.level(MetricColors.steps, share), Modifier.fillMaxWidth().gridSize(CardSize.Wide), onClick = onClick) {
        Column {
            GlowHeader(tr(R.string.common_steps), trailing = trRaw(R.string.common_goal_d).format(goal), tag = tag)
            Spacer(Modifier.height(GlowLayout.TitleGap))
            HourPills(state.hourlySteps, hour) { hour = it }
            Spacer(Modifier.height(16.dp))
            // A picked hour takes the number's place; one dotted number either way (spec 2.3). A level glow isn't the
            // screen's hero, so its number is the second glow's size, as on the Charge battery.
            val h = hour
            val picked = h?.let { state.hourlySteps.getOrNull(it) }
            HeroFigure(
                "%,d".format(if (h != null && picked != null) Math.round(picked) else state.steps),
                if (h != null && picked != null) ActivityCopy.hourLabel(h) else ActivityCopy.activityLine(state.steps, goal, pace, isToday),
                big = true, dot = DotSize.Second, labelLines = 2,
            )
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
 * Load today, its light filling across toward your usual for a day, and the week's balance in a word. Above your
 * usual it glows stronger. Opens the Load detail, where Strain, fitness and fatigue live. TODO(step 6): the ghost line
 * of your usual Load by this time needs past days' Load hour by hour. The engine keeps each past day's Load only as a
 * total ([org.openstrap.app.data.Recovery.dailyLoad]); hours would mean re-running it 24 times for each of 30 days in
 * every recompute, with nothing to cache them in.
 */
@Composable
private fun LoadCard(r: RecoveryResult?, onClick: () -> Unit) {
    val usual = r?.chronicLoad?.takeIf { it > 0f }
    val status = r?.let { TodayCopy.loadStatus(it.load, it.chronicLoad, endOfDay = it.date != LocalDate.now()) }
    EdgeCard(
        MetricColors.load, Modifier.fillMaxWidth().gridSize(CardSize.Wide),
        status = if (status == TodayCopy.LoadStatus.Above) EdgeStatus.Above else EdgeStatus.Usual,
        kind = EdgeKind.Level, progress = r?.let { l -> usual?.let { l.load / it } } ?: 0f, onClick = onClick,
    ) {
        EdgeTitle(tr(R.string.common_load), TabCopy.loadBalance(r?.loadTrend))
        EdgeValue(r?.load?.roundToInt()?.toString(), tr(R.string.missed_today_small), if (r == null) tr(R.string.common_no_reading_yet) else TabCopy.loadUsual(usual))
    }
}

/* ------------------------------------------------ Facts --------------------------------------------- */

/**
 * The day's smaller facts: VO2 max, time to recover, energy and distance, each opening its detail. Active minutes and
 * Heart points are the Small pair above.
 */
@Composable
private fun FactsList(
    state: DayUiState, r: RecoveryResult?, workouts: List<WorkoutAnalysis>, isToday: Boolean, onEditProfile: () -> Unit, open: (Score) -> Unit,
    /** The Partial day tag: energy and distance are the day's totals. */
    tag: TitleTag? = null,
) {
    val waiting = tr(R.string.common_no_reading_yet)
    ListPanel(title = tr(R.string.common_today), note = if (isToday) tr(R.string.glow_activity_screen_so_far) else null, tag = tag) {
        val a = r?.activity
        // The latest workout the strap read VO2 max from; the estimate from your profile otherwise.
        val fromWorkouts = workouts.sortedByDescending { it.workout.start }
            .firstNotNullOfOrNull { w -> (w.workout.vo2max ?: w.effect?.vo2max?.roundToInt())?.takeIf { it > 0 } }
        val est = r?.vo2max
        ListRow(
            tr(R.string.common_vo_max), MetricColors.heart,
            fromWorkouts?.toString() ?: est?.let { "${it.low}-${it.high}" },
            when {
                fromWorkouts != null -> tr(R.string.glow_activity_screen_from_your_workouts)
                est != null -> est.band?.let { tr(R.string.glow_activity_screen_for_your_age, it.label) } ?: tr(R.string.glow_activity_screen_estimated_from_your_profile)
                else -> tr(R.string.common_after_a_workout_the)
            },
            divider = false, onClick = { open(Score.Vo2Max) }, icon = Score.Vo2Max.rowIcon(),
        )
        val recover = workouts.filter { it.recoveryMinutes != null }.maxByOrNull { it.end }?.let { w ->
            val now = System.currentTimeMillis() / 1000
            TodayCopy.recoverText(TodayCopy.recoverLeft(w.end, w.recoveryMinutes ?: 0, now), now, ZoneId.systemDefault())
        }
        // Recovered is the whole answer: no number, no dash.
        ListRow(
            tr(R.string.common_time_to_recover), MetricColors.load, recover?.first, recover?.second ?: tr(R.string.glow_activity_screen_no_recent_workout), dash = recover?.first != null,
            onClick = { open(Score.Recover) }, icon = Score.Recover.rowIcon(),
        )
        // Active kcal leads, the total under it; the ring in the icon's place fills to the goal (calories spec 4.4).
        val e = r?.energy
        val goal = LocalGoals.current.calories
        val active = e?.activeKcal?.roundToInt()
        ListRow(
            tr(R.string.common_calories), MetricColors.calories, active?.let { "%,d".format(it) },
            e?.let { CaloriesCopy.rowLine(active ?: 0, goal, it.totalKcal.roundToInt()) } ?: tr(R.string.glow_activity_screen_add_age_weight_and),
            onClick = if (e == null && r != null) onEditProfile else { { open(Score.Energy) } }, dash = e != null,
            icon = Score.Energy.rowIcon(), ring = active?.let { CaloriesCopy.share(it, goal) },
        )
        // Ours from step length and height when we can; the strap's own figure otherwise. Always approximate.
        val distance = a?.distanceMeters ?: state.distanceMeters?.toFloat()
        ListRow(
            tr(R.string.common_distance), MetricColors.steps, distance?.let { "%.1f".format(it / 1000f) },
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
    ListPanel(title = tr(R.string.common_workouts), note = if (today.isNotEmpty()) tr(R.string.glow_activity_screen_this_day, today.size) else null) {
        var first = true
        fun divider() = !first.also { first = false }
        suggestion?.let { SuggestionRow(it, onSave, onDismiss, divider()) }
        if (shown.isEmpty()) ListRow(tr(R.string.common_no_workouts_yet), MetricColors.heart, null, tr(R.string.glow_activity_screen_workouts_you_record_show), dash = false, divider = divider())
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
        LinkRow(tr(R.string.glow_activity_screen_add_a_workout_you), onAdd, divider = divider())
        LinkRow(tr(R.string.glow_activity_screen_all_workouts), onAll)
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
            .let { if (divider) it.drawBehind { drawLine(RowHairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else it },
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            EffortLight(SportIcons.of(a.workout.sportType), Workouts.effort(a.workout.trainingLoad, usual))
            Spacer(Modifier.width(EffortGap))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.sportName, color = Color.White, style = GlowType.label.copy(fontWeight = null),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!a.startedByYou) {
                        Spacer(Modifier.width(6.dp))
                        FoundTag()
                    }
                }
                Text(
                    TabCopy.workoutLine(fact, start.format(CLOCK)), color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary,
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

/** "Auto-detected": a quiet outlined label beside the sport's name. */
@Composable
internal fun FoundTag() {
    Text(
        TabCopy.AUTO_DETECTED, color = Color.White.copy(alpha = 0.7f), style = GlowType.secondary.copy(fontSize = 11.sp, lineHeight = 14.sp),
        maxLines = 1,
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.19f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** A stretch nobody started, as a row with Save: when it was and how hard. */
@Composable
private fun SuggestionRow(s: org.openstrap.app.data.WorkoutSuggestions.Suggestion, onSave: () -> Unit, onDismiss: () -> Unit, divider: Boolean) {
    Column {
        ListRow(
            tr(R.string.common_looks_like_a_workout), ZONE_COLORS[s.zone], null,
            tr(R.string.glow_activity_screen_mostly_zone_bpm_on, clockAt(s.start), clockAt(s.end), s.zone + 1, s.averageHeartRate),
            dash = false, divider = divider, icon = AppIcons.Heartbeat,
        )
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RowPill(tr(R.string.common_save), onSave, primary = true)
            RowPill(tr(R.string.common_not_a_workout), onDismiss)
            Box(Modifier.weight(1f))
        }
    }
}
