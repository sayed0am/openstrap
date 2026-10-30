package org.openstrap.app.ui.glow

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import org.openstrap.app.data.CheckIn
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.ui.components.cardNumber
import org.openstrap.app.ui.components.formatDuration
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.ui.dashboard.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.RECOVERY_FAILED
import org.openstrap.app.ui.dashboard.RecoveryFailure
import org.openstrap.app.ui.dashboard.TRY_AGAIN
import org.openstrap.app.ui.dashboard.WAITING_LAST_NIGHT
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/*
 * The pieces Today is built from (spec 4.2): the hero's parts, strips, edge cards and lists. Each card has one home
 * on the screen and opens its detail. The words come from [TodayCopy].
 */

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
internal fun clockAt(epoch: Long): String = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(CLOCK)

/** The smallest height a card of [size] takes: its grid height, so long text grows the card instead of clipping. */
internal fun Modifier.gridSize(size: CardSize): Modifier = GlowGrid.height(size)?.let { heightIn(min = it) } ?: this

/* ----------------------------------------------- Hero ----------------------------------------------- */

/** The hero's sentence, the answer the card leads with. */
@Composable
internal fun HeroSentence(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Color.White, style = GlowType.sentence, modifier = modifier.padding(top = 14.dp))

/** A number on the hero: dot-matrix digits, or the dim dash while there's none, with a [label] under it. */
@Composable
internal fun HeroFigure(
    value: String?, label: String, big: Boolean = false, horizontal: Alignment.Horizontal = Alignment.Start,
    /** The hero's size for a [big] figure, an edge card's otherwise; a Tall or second glow passes [DotSize.Second]. */
    dot: DotSize = if (big) DotSize.Hero else DotSize.Edge,
    modifier: Modifier = Modifier, labelLines: Int = 1,
) {
    Column(modifier, horizontalAlignment = horizontal, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A big figure is the card's number: it can move into the detail's headline (spec 3.5).
        if (value != null) DotMatrixText(
            value, if (big) Modifier.cardNumber(value) else Modifier,
            dot = dot,
        )
        else WaitingDash(Modifier.padding(vertical = 8.dp))
        Text(label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = labelLines)
    }
}

/**
 * The hero's one dotted number (spec 2.3, one per card) with up to two short [lines] beside it on the digits' baseline:
 * its word, and the figures that go with it as text ("Energy at waking 84"). Without a [value] the lines stand alone.
 */
@Composable
internal fun HeroNumber(value: String?, lines: List<String>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        // The card's number: it can move into the detail's headline (spec 3.5).
        if (value != null) {
            DotMatrixText(value, Modifier.cardNumber(value), dot = DotSize.Hero)
            Spacer(Modifier.width(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lines.forEach { Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1) }
        }
    }
}

/** The white pill that starts something, like the ready screen's Start. */
@Composable
internal fun HeroButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(48.dp).clip(shape).background(Color.White).pressClickable(onClick, shape).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon) {
            Icon(AppIcons.PlayFill, null, tint = Color.Black, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.Black, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

/** A quieter glass pill beside [HeroButton]. */
@Composable
internal fun HeroSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.height(48.dp).glass(RoundedCornerShape(50), onClick).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

/* ---------------------------------------------- Strips ---------------------------------------------- */

/** A strip is only 68 dp high, so its words get less above and below than a card; the sides are a card's. */
private val StripPadding = PaddingValues(horizontal = GlowLayout.CardInset, vertical = 14.dp)

/** A full-width strip: a state or an action, [GlowGrid.StripHeight] high unless its words need more. */
@Composable
internal fun TodayStrip(
    color: Color, kind: EdgeKind, modifier: Modifier = Modifier, status: EdgeStatus = EdgeStatus.Usual,
    onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) = EdgeCard(
    color, modifier.fillMaxWidth().gridSize(CardSize.Strip), status = status, kind = kind, onClick = onClick,
    contentPadding = StripPadding, content = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, content = content) },
)

/** A strip's single line: a [title] (with the tap caret when the strip opens a page) and, on the right, its [detail]. */
@Composable
internal fun StripLine(title: String, detail: String?) {
    Box(Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
        TitleRow(start = { CardTitle(title) }, end = detail?.let { { NoteText(it) } })
    }
}

/**
 * Last night, collapsed to one line once the morning is over (spec 4.2). [stuck]: it's still on the strap. Before
 * the first night it says when it fills, not that there was none.
 */
@Composable
internal fun LastNightStrip(state: DayUiState, onClick: () -> Unit, modifier: Modifier = Modifier, stuck: Boolean = false, firstNights: Boolean = false) =
    TodayStrip(MetricColors.sleepDeep, EdgeKind.Sleep, modifier, onClick = onClick) {
        StripLine(
            tr(R.string.common_last_night_2),
            TodayCopy.lastNight(state.asleepSeconds, state.sleep?.score) ?: when {
                firstNights -> AFTER_FIRST_NIGHT
                stuck -> WAITING_LAST_NIGHT
                else -> tr(R.string.common_no_sleep_recorded)
            },
        )
    }

/**
 * The morning check-in: how you feel, 1 to 5. It never changes the score; it's how the score gets checked. A strip,
 * so lit from below in the neutral light (spec 2.5): a question, not Recovery's.
 */
@Composable
internal fun CheckInStrip(feeling: Int?, onCheckIn: (Int?) -> Unit, modifier: Modifier = Modifier) =
    TodayStrip(EdgeNeutral, EdgeKind.Value, modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.today_cards_how_do_you_feel), color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
            if (feeling != null) Text(CheckIn.label(feeling), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (CheckIn.MIN..CheckIn.MAX).forEach { v ->
                val selected = feeling == v
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier.weight(1f)
                        .let { if (selected) it.background(Color.White.copy(alpha = 0.85f), shape) else it }
                        .glass(shape, { onCheckIn(if (selected) null else v) })
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("$v", color = if (selected) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }

/**
 * Start a workout (spec 4.2): unlit, with a heart-red light travelling round its border ([movingBorder]), the title
 * with your usual sport under it ("Outdoor run, your usual") and a white play button. One thing to do, so the whole
 * strip is the button and has no caret; it opens Ready with that sport.
 */
@Composable
internal fun StartWorkoutStrip(sport: Int, onStart: (Int) -> Unit, modifier: Modifier = Modifier) {
    val name = Workouts.sportName(sport)
    // A strip that does one thing is a button: its title is plain text, with no caret (spec 2.5, Tappable says so).
    TodayStrip(MetricColors.heart, EdgeKind.Value, modifier.movingBorder(MetricColors.heart), onClick = { onStart(sport) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr(R.string.common_start_a_workout), color = Color.White, style = GlowType.cardTitle, maxLines = 1)
                Text(tr(R.string.today_cards_your_usual, name), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(AppIcons.PlayFill, null, tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** What the strap warned about, in the words of its notification. Codes as in HealthAlert.Type. */
internal fun alertText(a: HealthAlertRow): String = when (a.type) {
    1 -> tr(R.string.today_cards_high_heart_rate_bpm, a.value)
    2 -> tr(R.string.today_cards_low_heart_rate_bpm, a.value)
    3 -> tr(R.string.today_cards_high_stress, a.value)
    4 -> tr(R.string.today_cards_low_blood_oxygen, a.value)
    5 -> tr(R.string.common_irregular_heart_rhythm)
    else -> tr(R.string.today_cards_a_reading_the_strap)
}

private fun alertMetric(a: HealthAlertRow) = when (a.type) { 3 -> Metric.Stress; 4 -> Metric.Spo2; else -> Metric.HeartRate }

/** "From your strap": the newest of the day's alerts, opening its metric, with how many more there were. */
@Composable
internal fun AlertsStrip(alerts: List<HealthAlertRow>, onOpenMetric: (Metric) -> Unit, modifier: Modifier = Modifier) {
    val newest = alerts.maxByOrNull { it.timestamp } ?: return
    TodayStrip(MetricColors.heart, EdgeKind.Value, modifier, status = EdgeStatus.Above, onClick = { onOpenMetric(alertMetric(newest)) }) {
        StripLine(tr(R.string.common_from_your_strap), if (alerts.size > 1) tr(R.string.today_cards_more_today, alerts.size - 1) else clockAt(newest.timestamp))
        Text(
            alertText(newest) + if (alerts.size > 1) tr(R.string.today_cards_at, clockAt(newest.timestamp)) else ".",
            color = Color.White.copy(alpha = 0.85f), style = GlowType.secondary,
        )
        Text(
            org.openstrap.app.data.BodySignalsCopy.DISCLAIMER, color = Color.White.copy(alpha = 0.5f), style = GlowType.secondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/* ------------------------------------------- Edge cards --------------------------------------------- */

/** An edge card's title row: [title] left with its sync [marker] or neutral [tag] beside it ([TitleMark]), [note] right. */
@Composable
internal fun EdgeTitle(title: String, note: String?, marker: String? = null, tag: TitleTag? = null) {
    // The note drops under the title when they can't share a line, so the title never wraps.
    TitleRow(
        start = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle(title, Modifier.weight(1f, fill = false))
                if (marker != null || tag != null) TitleMark(marker, tag, Modifier.padding(start = 8.dp))
            }
        },
        end = note?.let { { NoteText(it) } },
    )
}

/** An edge card's number and the line under it; a dim dash and [status] while there's no number. */
@Composable
internal fun EdgeValue(
    value: String?, unit: String?, status: String?, statusColor: Color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
    /** Whether a missing [value] shows the waiting dash; false where the [status] says all there is. */
    waiting: Boolean = true,
    /** An edge card's size; the second glow passes [DotSize.Second]. */
    dot: DotSize = DotSize.Edge,
) {
    if (value != null || waiting) Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
        if (value != null) DotMatrixText(value, Modifier.cardNumber(value), dot = dot) else WaitingDash(Modifier.padding(vertical = 8.dp))
        if (unit != null && value != null) Text(unit, color = Color.White.copy(alpha = 0.7f), style = GlowType.secondary, modifier = Modifier.padding(start = 8.dp))
    }
    if (status != null) Text(status, color = statusColor, style = GlowType.secondary, modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** A length of time as dot-matrix digits: "7:40". */
internal fun clockDuration(seconds: Long): String = "%d:%02d".format(seconds / 3600, (seconds % 3600) / 60)

/**
 * Last night in full, on waking: the score in dots with its word and time asleep under it, as the Sleep tab's hero has
 * them, then the stages, lit from the top like a night sky. [stuck]: it's still on the strap, so it waits rather than
 * saying there was none.
 */
@Composable
internal fun LastNightCard(state: DayUiState, firstNights: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, stuck: Boolean = false) {
    val s = state.sleep
    EdgeCard(MetricColors.sleepDeep, modifier.fillMaxWidth().gridSize(CardSize.Wide), kind = EdgeKind.Sleep, onClick = onClick) {
        EdgeTitle(tr(R.string.common_last_night_2), s?.let { "${clockAt(it.start)}–${clockAt(it.end)}" })
        val asleep = state.asleepSeconds
        val score = s?.score
        // A night the strap gave no score says so in words, with no dash where the score would be.
        EdgeValue(
            score?.toString(), tr(R.string.missed_unit_score),
            when {
                asleep == null -> if (firstNights) AFTER_FIRST_NIGHT else if (stuck) WAITING_LAST_NIGHT else tr(R.string.common_no_sleep_recorded)
                score != null -> tr(R.string.today_cards_asleep, org.openstrap.app.ui.dashboard.TabCopy.sleepScoreWord(score), TodayCopy.duration(asleep))
                else -> tr(R.string.today_cards_asleep_no_score_from, TodayCopy.duration(asleep))
            },
            waiting = asleep == null,
        )
        if (state.sleepStages.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            GlowSleepStages(state.sleepStages, 44.dp, compact = true)
        }
    }
}

/**
 * Heart rate (line with scrub, chart kit): the latest reading and the day's line, with your resting range as a dim
 * band behind it when there's one ([restingRange], your normal resting heart rate). Hold the line to scrub: the big
 * number and the time under it follow the finger, and snap back on release. Lit from the right while the reading is
 * live, otherwise as a value at its usual. The live switch lives in the Heart rate detail, not here. Shared by Today
 * and the Activity tab.
 */
@Composable
internal fun HeartCard(
    state: DayUiState, live: Int?, onClick: () -> Unit, modifier: Modifier = Modifier,
    restingRange: org.openstrap.app.data.History.Normal? = null,
    /** "Up to 09:40" when the line has gone stale ([org.openstrap.app.ui.dashboard.upToMarker]). */
    upTo: String? = null,
) {
    val step = 10 * 60L
    val values = remember(state.heartRate, state.dayStart) { bucket(state.heartRate, state.dayStart..state.dayEnd, step) }
    var picked by remember(values) { mutableStateOf<Int?>(null) }
    val scrubbed = picked?.let { i -> values.getOrNull(i)?.let { it.roundToInt() to state.dayStart + i * step } }
    val now = scrubbed?.first ?: live ?: state.latestHeartRate
    EdgeCard(
        MetricColors.heart, modifier.fillMaxWidth().gridSize(CardSize.Wide),
        kind = if (live != null) EdgeKind.Live else EdgeKind.Value, onClick = onClick,
    ) {
        EdgeTitle(tr(R.string.common_heart_rate), state.restingHeartRate?.let { tr(R.string.common_resting, it) }, marker = upTo)
        val at = scrubbed?.second ?: state.heartRate.lastOrNull()?.x
        EdgeValue(now?.toString(), "bpm", TodayCopy.heartAt(live != null && scrubbed == null, at?.let(::clockAt)))
        if (state.heartRate.size >= 2) {
            Spacer(Modifier.height(10.dp))
            GlowLineChart(
                values, height = 72.dp, showAverage = false, color = MetricColors.heart, labels = listOf("00", "06", "12", "18", "24"),
                band = restingRange?.let { it.low..it.high }, onScrub = { picked = it },
            )
            Text(
                TodayCopy.heartLineNote(restingRange != null), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                style = GlowType.secondary, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Steps toward the day's goal, with the day's steps so far as a line (ghost line, chart kit) on an axis up to the goal,
 * and your [usual] by the end of each hour as the faint solid ghost behind it, with "about usual by now" beside the
 * goal (spec 2.7, compare by pace). Without a usual yet only today's line shows. The share of the goal is the card's
 * progress bar. Titled Steps, as the Activity tab and the detail it opens are.
 */
@Composable
internal fun ActivityCard(
    state: DayUiState, onClick: () -> Unit, modifier: Modifier = Modifier, usual: List<Float>? = null, upTo: String? = null,
    /** The Partial day tag when the strap was worn for part of the day ([TitleTag]). */
    tag: TitleTag? = null,
    /** The day's count in dots; false leads with the sentence, where the hero above already has the count. */
    count: Boolean = true,
) {
    val goal = org.openstrap.app.ui.dashboard.LocalGoals.current.steps
    val isToday = state.date == java.time.LocalDate.now()
    val hour = java.time.LocalTime.now().hour
    val line = remember(state.hourlySteps, isToday, hour) { org.openstrap.app.data.Summaries.runningSteps(state.hourlySteps, if (isToday) hour else null) }
    val now = java.time.LocalTime.now()
    val pace = org.openstrap.app.ui.dashboard.ActivityCopy.paceOf(state.steps, usual, isToday, now.hour, now.minute)
    val ghost = usual?.takeIf { it.size == 24 }
    val chart = state.steps > 0 && line.size >= 2
    EdgeCard(
        MetricColors.steps, modifier.fillMaxWidth().gridSize(CardSize.Wide), kind = EdgeKind.Progress,
        // The goal bar only without the day's line: under it, a short bar read as the start of the line. The line
        // already climbs toward the goal, and the sentence gives the share.
        progress = if (chart || goal <= 0) null else state.steps / goal.toFloat(), onClick = onClick,
    ) {
        EdgeTitle(tr(R.string.common_steps), tr(R.string.today_cards_goal, goal), marker = upTo, tag = tag)
        val sentence = org.openstrap.app.ui.dashboard.ActivityCopy.activityLine(state.steps, goal, pace, isToday)
        if (count) EdgeValue("%,d".format(state.steps), tr(R.string.missed_unit_steps), sentence)
        else Text(sentence, color = Color.White, style = GlowType.body, modifier = Modifier.padding(top = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (chart) {
            Spacer(Modifier.height(10.dp))
            GhostLine(
                line, MetricColors.steps, maxOf(goal.toFloat(), state.steps.toFloat(), line.filterNotNull().maxOrNull() ?: 0f, ghost?.maxOrNull() ?: 0f),
                height = 56.dp, usual = ghost,
            )
        }
    }
}

/**
 * This morning's Recovery, later in the day: about earlier, so lit from the left. The same as the waking hero shows:
 * OpenStrap's score, the strap's estimate labelled as such, or why there's none yet.
 */
@Composable
internal fun RecoveryEarlierCard(
    shown: TodayCopy.RecoveryShown, onClick: () -> Unit, modifier: Modifier = Modifier,
    /** Today's score failed to work out: yesterday's stays, dimmed, with a way to try again (spec 4.3). */
    failure: RecoveryFailure? = null, onRetry: () -> Unit = {},
) {
    EdgeCard(MetricColors.readiness, modifier.fillMaxWidth().gridSize(CardSize.Wide), kind = EdgeKind.Earlier, onClick = onClick) {
        EdgeTitle(tr(R.string.common_recovery), tr(R.string.common_this_morning))
        if (failure != null) RecoveryFailed(failure, onRetry)
        else EdgeValue(shown.value?.toString(), shown.word, shown.line)
    }
}

/** In place of a Recovery that failed: yesterday's score dimmed, the line, and Try again. */
@Composable
internal fun RecoveryFailed(failure: RecoveryFailure, onRetry: () -> Unit) {
    failure.yesterday?.let { y -> Column(Modifier.alpha(FAILED_ALPHA)) { EdgeValue(y.toString(), tr(R.string.missed_yesterday_small), null) } }
    Text(RECOVERY_FAILED, color = Color.White.copy(alpha = 0.85f), style = GlowType.secondary, modifier = Modifier.padding(top = 8.dp))
    HeroSecondaryButton(TRY_AGAIN, onRetry, Modifier.padding(top = 12.dp))
}

/** How dim a value kept from before is, beside a failure. */
internal const val FAILED_ALPHA = 0.45f

/**
 * Charge as a wide card: the screen's second glow, a level whose light fills across from the left to the value, the
 * Charge battery of Recovery on its side (spec 2.1). Where that would sit next to another glow it steps down to a level
 * edge card whose light fills across to the value, only as high as its content. [status] says what's happening to it. [soFar] is the day's
 * Charge so far (evenly spaced, nulls still to come), drawn beside the number on the level card (ghost line, chart
 * kit). TODO(step 6): the ghost of your usual Charge by this time needs past days' Charge hour by hour. The engine
 * simulates Charge only for the day shown (with a week's lead-in to settle it); past days would each need that whole
 * simulation re-run, and nothing keeps its results, so only today's line shows.
 */
@Composable
internal fun ChargeWideCard(
    now: Int?, status: String, secondGlow: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, soFar: List<Float?>? = null,
    upTo: String? = null, tag: TitleTag? = null,
) {
    val m = modifier.fillMaxWidth().gridSize(CardSize.Wide)
    if (secondGlow) {
        GlowCard(GlowStyle.Ember.copy(lightLevel = (now ?: 0) / 100f, lightAngle = LightAngle.Left, second = true), m, onClick = onClick) {
            Column {
                GlowHeader(tr(R.string.common_energy), marker = upTo, tag = tag)
                if (soFar == null || soFar.count { it != null } < 2) EdgeValue(now?.toString(), null, status, Color.White.copy(alpha = 0.8f), dot = DotSize.Second)
                else Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) { EdgeValue(now?.toString(), null, status, Color.White.copy(alpha = 0.8f), dot = DotSize.Second) }
                    Spacer(Modifier.width(14.dp))
                    GhostLine(soFar, Color.White, 100f, Modifier.weight(1f), height = 52.dp)
                }
            }
        }
    } else EdgeCard(MetricColors.charge, modifier.fillMaxWidth(), kind = EdgeKind.Level, progress = (now ?: 0) / 100f, onClick = onClick) {
        EdgeTitle(tr(R.string.common_energy), null, marker = upTo, tag = tag)
        if (soFar == null || soFar.count { it != null } < 2) EdgeValue(now?.toString(), null, status)
        else Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) { EdgeValue(now?.toString(), null, status) }
            Spacer(Modifier.width(14.dp))
            GhostLine(soFar, MetricColors.charge, 100f, Modifier.weight(1f), height = 52.dp)
        }
    }
}

/** The workout just done, after it: how long it was, the average heart rate and the zone it mostly sat in. Lit from the left, about earlier. */
@Composable
internal fun WorkoutCard(w: org.openstrap.app.data.WorkoutAnalysis, onClick: () -> Unit, modifier: Modifier = Modifier) {
    EdgeCard(MetricColors.heart, modifier.fillMaxWidth().gridSize(CardSize.Wide), kind = EdgeKind.Earlier, onClick = onClick) {
        EdgeTitle(Workouts.sportName(w.workout.sportType), "${clockAt(w.shownStart)}–${clockAt(w.end)}")
        val mostly = w.zoneSeconds.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index
        EdgeValue(
            workoutClock(w.activeSeconds.coerceAtLeast(0)), tr(R.string.missed_long),
            listOfNotNull(w.workout.averageHeartRate?.let { tr(R.string.common_average_bpm, it) }, mostly?.let { tr(R.string.today_cards_mostly_zone, it + 1) })
                .joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { null },
        )
        // Zone bar (chart kit): how the time was spent.
        ZoneBar(w.zoneSeconds, org.openstrap.app.ui.workout.ZONE_COLORS, Modifier.padding(top = 8.dp))
    }
}

/**
 * Your week (spec 4.2): last Monday to Sunday against the week before, every day of this week. Steps a day in dots,
 * time asleep a night and the workouts done as text under them (one dotted number a card, spec 2.3). A summary of three
 * metrics borrows none of their colours: a neutral edge from the bottom, soft, like any value at its usual.
 */
@Composable
internal fun WeekCard(
    state: DayUiState, workouts: List<org.openstrap.app.data.WorkoutAnalysis>, history: DailyHistory?, modifier: Modifier = Modifier,
    /** Opens the week story. */
    onClick: (() -> Unit)? = null,
) {
    val week = TodayCopy.lastWeek(state.date)
    val start = week.start
    val end = week.endInclusive
    val steps by remember(end, history) {
        history?.invoke(Metric.Steps.daily, start.minusDays(7), end) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(emptyList())
    fun stepsAvg(from: java.time.LocalDate, to: java.time.LocalDate) =
        steps.filter { it.date in from..to }.map { it.value.toDouble() }.takeIf { it.isNotEmpty() }?.average()
    val stepsNow = stepsAvg(start, end)
    // A night belongs to the morning it ended, so the week's nights end Tuesday through the Monday after.
    val asleep = state.nights.filter { it.date in start.plusDays(1)..end.plusDays(1) }.mapNotNull { it.asleepSeconds }
    val zone = ZoneId.systemDefault()
    val done = workouts.count { Instant.ofEpochSecond(it.workout.start).atZone(zone).toLocalDate() in start..end }
    val range = DateTimeFormatter.ofPattern("d MMM")
    EdgeCard(EdgeNeutral, modifier.fillMaxWidth().gridSize(CardSize.Wide), kind = EdgeKind.Value, onClick = onClick) {
        EdgeTitle(tr(R.string.common_your_week), "${start.format(range)}–${end.format(range)}")
        EdgeValue(stepsNow?.let { "%,d".format(it.roundToInt()) }, tr(R.string.today_cards_steps_a_day), null)
        val night = asleep.takeIf { it.isNotEmpty() }?.average()?.toLong()?.let { TodayCopy.duration(it) + tr(R.string.today_cards_asleep_a_night) }
        val workoutsDone = if (done == 1) tr(R.string.today_cards_workout) else "$done workouts"
        Text(
            listOfNotNull(night, workoutsDone).joinToString(", ").replaceFirstChar { it.uppercase() },
            color = Color.White, style = GlowType.body, modifier = Modifier.padding(top = 8.dp),
        )
        val change = TodayCopy.stepsChange(stepsNow, stepsAvg(start.minusDays(7), start.minusDays(1)))
        Text(
            change?.let { tr(R.string.common_on_the_week_before, it) } ?: TodayCopy.WEEK_FIRST,
            color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * A Small (spec 2.3): a number and a word only, in the edge light, filling the [modifier] its row gives it. The title
 * at the top, the number and its word at the foot. [extra] goes between them, for the nap's stage strip. With no
 * number the word alone stands in its place in Body, rather than a dash over it ("Recovered"), unless it's [waiting]:
 * a word is never drawn at a number's size.
 */
@Composable
internal fun SmallCard(
    color: Color, title: String, value: String?, unit: String?, status: String?, modifier: Modifier = Modifier,
    kind: EdgeKind = EdgeKind.Value, edgeStatus: EdgeStatus = EdgeStatus.Usual, statusColor: Color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
    onClick: (() -> Unit)? = null, extra: (@Composable () -> Unit)? = null,
    /** Waiting for its first value: the dim dash with [status] under it, rather than the word in its place. */
    waiting: Boolean = false,
    /** How far toward its goal or usual, 0..1, for a [EdgeKind.Progress] Small's bar ([EdgeCard]). */
    progress: Float? = null,
    /** The number as plain text ([GlowType.smallValue]), for a Small that breaks down the card beside it. */
    plain: Boolean = false,
) {
    EdgeCard(color, modifier, status = edgeStatus, kind = kind, progress = progress, onClick = onClick) {
        CardTitle(title)
        Spacer(Modifier.weight(1f))
        if (value == null && status != null && !waiting) {
            extra?.invoke()
            Text(status, color = Color.White, style = GlowType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            return@EdgeCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            when {
                value == null -> WaitingDash(Modifier.padding(vertical = 8.dp))
                plain -> Text(value, Modifier.cardNumber(value), color = Color.White, style = GlowType.smallValue, maxLines = 1)
                else -> DotMatrixText(value, Modifier.cardNumber(value), dot = DotSize.Edge)
            }
            if (unit != null && value != null) Text(unit, color = Color.White.copy(alpha = 0.7f), style = GlowType.secondary, modifier = Modifier.padding(start = 6.dp))
        }
        extra?.invoke()
        if (status != null) Text(status, color = statusColor, style = GlowType.secondary, modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/* ---------------------------------------------- Lists ----------------------------------------------- */

/** A row of a body list: a value against your [normal] ([org.openstrap.app.ui.glow.rememberNormal]). */
internal class BodyRow(
    val name: String, val color: Color, val value: Float?, val normal: org.openstrap.app.data.History.Normal?, val unit: String, val metric: Metric,
)

/**
 * Body values against your normal, each opening its metric, read and rounded as the Recovery tab's body signals are.
 * [waiting] is what an empty row says.
 */
@Composable
internal fun BodyList(
    title: String, rows: List<BodyRow>, waiting: String, onOpenMetric: (Metric) -> Unit, modifier: Modifier = Modifier, upTo: String? = null,
) {
    ListPanel(modifier, title = title, note = tr(R.string.common_against_your_normal), marker = upTo) {
        rows.forEachIndexed { i, r ->
            val direction = org.openstrap.app.data.History.status(r.value, r.normal)
            ListRow(
                r.name, r.color, r.value?.let { r.metric.format(it) + r.unit },
                if (r.value == null) waiting else TodayCopy.normalWord(direction),
                offNormal = direction != null && direction != 0, onClick = { onOpenMetric(r.metric) }, divider = i > 0,
                icon = r.metric.rowIcon(),
            )
        }
    }
}

/** Load today and time to recover, before or after a workout, each opening its detail. */
@Composable
internal fun LoadList(
    title: String, load: Float?, loadLine: String, recover: Pair<String?, String>?, modifier: Modifier = Modifier,
    onLoad: (() -> Unit)? = null, onRecover: (() -> Unit)? = null,
) {
    ListPanel(modifier, title = title) {
        ListRow(tr(R.string.common_load_today), MetricColors.load, load?.roundToInt()?.toString(), loadLine, divider = false, onClick = onLoad, icon = Score.Load.rowIcon())
        // Recovered is the whole answer: no number, no dash.
        if (recover != null) ListRow(
            tr(R.string.common_time_to_recover), MetricColors.load, recover.first, recover.second, dash = recover.first != null, onClick = onRecover,
            icon = Score.Recover.rowIcon(),
        )
    }
}

/* ------------------------------------------- Edit Today --------------------------------------------- */

/** The last thing on Today: a way to change what it shows. */
@Composable
internal fun EditTodayButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.glass(CircleShape, onClick, scale = 0.97f).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.SlidersHorizontal, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr(R.string.common_edit_today), color = Color.White, style = GlowType.body)
        }
    }
}
