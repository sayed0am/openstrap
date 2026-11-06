package org.openstrap.app.ui.glow

import androidx.compose.runtime.ReadOnlyComposable
import org.openstrap.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.Units
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlin.math.roundToInt
import org.openstrap.app.data.CheckIn
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.ui.components.cardNumber
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.presentation.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.RECOVERY_FAILED
import org.openstrap.app.ui.dashboard.RecoveryFailure
import org.openstrap.app.ui.dashboard.TRY_AGAIN
import org.openstrap.app.ui.dashboard.WAITING_LAST_NIGHT
import org.openstrap.app.presentation.Metric
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.presentation.TodayCopy
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.glow.draw.LightAngle
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.dashboard.FAILED_SHORT
import org.openstrap.app.ui.dashboard.GoalFacts
import org.openstrap.app.ui.dashboard.GoalMetric
import org.openstrap.app.ui.dashboard.NewsTag
import org.openstrap.app.ui.dashboard.OffStatus
import org.openstrap.app.ui.dashboard.OffWords
import org.openstrap.app.ui.dashboard.bodyOffStatus
import org.openstrap.app.ui.dashboard.goalTag
import org.openstrap.app.ui.dashboard.offTag
import org.openstrap.app.ui.dashboard.rimLit
import org.openstrap.app.ui.glow.pictures.CountdownRing
import org.openstrap.app.ui.glow.pictures.DayLine
import org.openstrap.app.ui.glow.pictures.DayStrip
import org.openstrap.app.ui.glow.pictures.LevelFill
import org.openstrap.app.ui.glow.pictures.PictureSize
import org.openstrap.app.ui.glow.pictures.WeekOfDots
import org.openstrap.app.ui.glow.pictures.WeekRings
import org.openstrap.app.ui.glow.pictures.dayLineRim
import org.openstrap.app.ui.glow.pictures.dayStripRim
import org.openstrap.app.ui.glow.pictures.levelFillRim
import org.openstrap.app.ui.glow.pictures.weekOfDotsRim

/*
 * The pieces Today is built from (spec 4.2): the hero's parts, strips, edge cards and lists. Each card has one home
 * on the screen and opens its detail. The words come from [TodayCopy].
 */

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

/* ---------------------------------------------- Strips ---------------------------------------------- */

/** A strip is only 68 dp high, so its words get less above and below than a card; the sides are a card's. */
private val StripPadding = PaddingValues(horizontal = GlowLayout.CardInset, vertical = 14.dp)

/** A full-width strip: a state or an action, [GlowGrid.StripHeight] high unless its words need more. */
@Composable
internal fun TodayStrip(
    color: Color, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) = PictureCard(
    color, modifier.fillMaxWidth().gridSize(CardSize.Strip), onClick = onClick,
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
    TodayStrip(MetricColors.sleepDeep, modifier, onClick = onClick) {
        StripLine(
            stringResource(R.string.glow_today_last_night),
            TodayCopy.lastNight(state.asleepSeconds, state.sleep?.score) ?: when {
                firstNights -> AFTER_FIRST_NIGHT
                stuck -> WAITING_LAST_NIGHT
                else -> stringResource(R.string.glow_today_no_sleep)
            },
        )
    }

/**
 * The morning check-in: how you feel, 1 to 5. It never changes the score; it's how the score gets checked. A strip,
 * so lit from below in the neutral light (spec 2.5): a question, not Recovery's.
 */
@Composable
internal fun CheckInStrip(feeling: Int?, onCheckIn: (Int?) -> Unit, modifier: Modifier = Modifier) =
    TodayStrip(EdgeNeutral, modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.glow_today_check_in_title), color = Color.White, style = GlowType.cardTitle, maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (feeling != null) Text(CheckIn.label(feeling), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        }
        Spacer(Modifier.height(10.dp))
        // Tapping the chosen one again takes the answer back.
        val values = (CheckIn.MIN..CheckIn.MAX).toList()
        GlowSegments(values.map { "$it" }, feeling?.let(values::indexOf) ?: -1, { onCheckIn(values[it].takeIf { v -> v != feeling }) })
    }

/**
 * Start a workout (spec 4.2): unlit, with a rainbow light travelling round its border ([movingBorder]), the title
 * with your usual sport under it ("Outdoor run, your usual") and a white play button. One thing to do, so the whole
 * strip is the button and has no caret; it opens Ready with that sport.
 */
@Composable
internal fun StartWorkoutStrip(sport: Int, onStart: (Int) -> Unit, modifier: Modifier = Modifier) {
    val name = Workouts.sportName(sport)
    // A strip that does one thing is a button: its title is plain text, with no caret (spec 2.5, Tappable says so).
    TodayStrip(MetricColors.heart, modifier.movingBorder(), onClick = { onStart(sport) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.glow_today_start_title), color = Color.White, style = GlowType.cardTitle, maxLines = 1)
                Text(
                    stringResource(R.string.glow_today_start_usual, name), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.secondary, maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(AppIcons.PlayFill, null, tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** What the strap warned about, in the words of its notification. Codes as in HealthAlert.Type. */
internal fun alertText(a: HealthAlertRow, strings: Strings = Strings.app): String = when (a.type) {
    1 -> strings.format(R.string.glow_today_alert_high_hr, a.value)
    2 -> strings.format(R.string.glow_today_alert_low_hr, a.value)
    3 -> strings.format(R.string.glow_today_alert_high_stress, a.value)
    4 -> strings.format(R.string.glow_today_alert_low_spo2, a.value)
    5 -> strings.get(R.string.glow_today_alert_irregular)
    else -> strings.get(R.string.glow_today_alert_other)
}

private fun alertMetric(a: HealthAlertRow) = when (a.type) { 3 -> Metric.Stress; 4 -> Metric.Spo2; else -> Metric.HeartRate }

/** "From your strap": the newest of the day's alerts, opening its metric, with how many more there were. */
@Composable
internal fun AlertsStrip(alerts: List<HealthAlertRow>, onOpenMetric: (Metric) -> Unit, modifier: Modifier = Modifier) {
    val newest = alerts.maxByOrNull { it.timestamp } ?: return
    TodayStrip(MetricColors.heart, modifier, onClick = { onOpenMetric(alertMetric(newest)) }) {
        StripLine(
            stringResource(R.string.glow_today_alerts_title),
            if (alerts.size > 1) {
                pluralStringResource(R.plurals.glow_today_alerts_more, alerts.size - 1, alerts.size - 1)
            } else {
                clock(newest.timestamp)
            },
        )
        Text(
            if (alerts.size > 1) {
                stringResource(R.string.glow_today_alert_at, alertText(newest), clock(newest.timestamp))
            } else {
                stringResource(R.string.glow_today_alert_line, alertText(newest))
            },
            color = Color.White.copy(alpha = 0.85f), style = GlowType.secondary,
        )
        Text(
            org.openstrap.app.data.BodySignalsCopy.DISCLAIMER, color = Color.White.copy(alpha = 0.5f), style = GlowType.secondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/* ------------------------------------------- Edge cards --------------------------------------------- */

/**
 * An edge card's title row: [title] left with its sync [marker] or neutral [tag] beside it ([TitleMark]), [note] right,
 * and a small [action] after the note (a workout's Trim).
 */
@Composable
internal fun EdgeTitle(
    title: String, note: String?, marker: String? = null, tag: TitleTag? = null, action: (@Composable () -> Unit)? = null,
) {
    // The note drops under the title when they can't share a line, so the title never wraps.
    TitleRow(
        start = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle(title, Modifier.weight(1f, fill = false))
                if (marker != null || tag != null) TitleMark(marker, tag, Modifier.padding(start = 8.dp))
            }
        },
        end = if (note == null && action == null) null else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    note?.let { NoteText(it) }
                    action?.invoke()
                }
            }
        },
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

/** A length of time as dot-matrix digits, the clock look the big figures have: "7:40". Text uses [Units.duration]. */
internal fun clockDuration(seconds: Long): String = Units.clockDuration(seconds, Units.DOT_LOCALE)

/* ------------------------------------------ Picture cards ------------------------------------------- */

/** How high a Wide's picture is on Today, between its title row and its number. */
private val WidePictureHeight = 52.dp

/**
 * A picture card's number (spec 2.5, Picture card): white dots with the unit after them, then the news [tag] 8 dp on
 * ([NumberWithTag]), dropping under the number where a Small can't hold both. A dim dash while there's no [value].
 */
@Composable
internal fun PictureNumber(value: String?, unit: String?, tag: NewsTag?, modifier: Modifier = Modifier) {
    if (value == null) WaitingDash(modifier.padding(vertical = 8.dp))
    else NumberWithTag(tag, modifier) {
        DotMatrixText(value, Modifier.cardNumber(value), dot = DotSize.Edge)
        // Its own item, so on a Small too narrow for both the unit drops under the number whole, never split.
        if (unit != null) Text(unit, color = Color.White.copy(alpha = 0.7f), style = GlowType.secondary, maxLines = 1, softWrap = false)
    }
}

/**
 * One of Today's picture cards (spec 2.5, 4.2) at its [size]: the title row, the metric's [picture] in its [ink], the
 * number in white with its news [tag], and the [status] in Secondary. Colour never says how it's going: the ink draws
 * only the picture, and off your normal the [rim] lights beside the part that's off. A Small fills the [modifier] its
 * row gives it, picture in the middle and the number at the foot. On a Small a tag stands in for the status line, as
 * a list row's does. A Wide keeps both. A Wide puts its picture under the title row, or under the number with
 * [pictureLast] (Last night's stage bar). [dash] says whether a missing [value] shows the waiting dash, false where
 * the status says all there is.
 */
@Composable
internal fun TodayPictureCard(
    ink: Color, title: String, size: PictureSize, value: String?, unit: String?, tag: NewsTag?, status: String?,
    modifier: Modifier = Modifier, note: String? = null, rim: Rim? = null, onClick: (() -> Unit)? = null,
    marker: String? = null, titleTag: TitleTag? = null, pictureLast: Boolean = false, dash: Boolean = true,
    picture: (@Composable (Modifier) -> Unit)? = null,
) {
    val small = size == PictureSize.Small
    val m = if (small) modifier else modifier.fillMaxWidth().gridSize(CardSize.Wide)
    PictureCard(ink, m, rim = rim, onClick = onClick) {
        EdgeTitle(title, note.takeIf { !small }, marker = marker, tag = titleTag)
        if (small) {
            if (picture != null) {
                Spacer(Modifier.height(12.dp))
                picture(Modifier.fillMaxWidth().weight(1f))
                // As much room under the picture as over it, so a picture standing on a baseline (Steps' bars) doesn't
                // touch the number.
                Spacer(Modifier.height(12.dp))
            } else Spacer(Modifier.weight(1f))
            if (value != null || dash) PictureNumber(value, unit, tag)
            val line = status.takeIf { tag == null || value == null }
            if (line != null) Text(
                line, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                modifier = Modifier.padding(top = 4.dp), maxLines = if (value == null) 2 else 1, overflow = TextOverflow.Ellipsis,
            )
            return@PictureCard
        }
        if (picture != null && !pictureLast) {
            Spacer(Modifier.height(12.dp))
            picture(Modifier.fillMaxWidth().height(WidePictureHeight))
        }
        if (value != null || dash) PictureNumber(value, unit, tag, Modifier.padding(top = 12.dp))
        if (status != null) Text(
            status, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
            modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (picture != null && pictureLast) {
            Spacer(Modifier.height(12.dp))
            picture(Modifier.fillMaxWidth())
        }
    }
}

/** The size a Today picture card draws at, from its grid [size]: Smalls small, everything else wide. */
internal fun pictureSize(size: CardSize): PictureSize = if (size == CardSize.Small) PictureSize.Small else PictureSize.Wide

/** Waiting, still on the strap, or no sleep recorded: what Last night says without a night. */
@Composable
@ReadOnlyComposable
private fun noNight(firstNights: Boolean, stuck: Boolean): String =
    if (firstNights) AFTER_FIRST_NIGHT else if (stuck) WAITING_LAST_NIGHT else stringResource(R.string.glow_today_no_sleep)

/**
 * Last night (spec 4.2): its stage bar in the stage colours, and Short or Long as its tag when the time asleep is off
 * your usual, with the rim in the corner. A Small has the sleep score in dots and the time asleep under it, as the page
 * it opens leads, or the time asleep in dots for a night without a score. A Wide, the card the morning is about,
 * has the sleep score in dots with its word and the time asleep under it, as the Sleep tab's hero does, then the
 * stage bar. [stuck]: it's still on the strap, so it waits rather than saying there was none.
 */
@Composable
internal fun LastNightCard(
    state: DayUiState, firstNights: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, stuck: Boolean = false,
    size: PictureSize = PictureSize.Wide,
) {
    val s = state.sleep
    val asleep = state.asleepSeconds
    val score = s?.score
    val off = org.openstrap.app.ui.dashboard.sleepLengthOffStatus(state)
    val tag = offTag(OffWords.Length, off)
    val totals = state.sleepBreakdown?.let { org.openstrap.app.ui.glow.draw.stageTotals(it.byStage) }.orEmpty()
    val picture: (@Composable (Modifier) -> Unit)? = if (totals.isEmpty()) null else { m ->
        Box(m, contentAlignment = Alignment.Center) {
            SegmentBar(
                totals.map { (stage, seconds) -> Segment(seconds.toFloat(), org.openstrap.app.ui.glow.draw.stageColor(stage)) },
                height = org.openstrap.app.ui.glow.draw.StageBarLook.HEIGHT_DP.dp, gap = org.openstrap.app.ui.glow.draw.StageBarLook.GAP_DP.dp,
                corner = org.openstrap.app.ui.glow.draw.StageBarLook.CORNER_DP.dp,
            )
        }
    }
    val rim = Rim.Corner.takeIf { rimLit(OffWords.Length, off) }
    val title = stringResource(R.string.glow_today_last_night)
    if (size == PictureSize.Small) {
        TodayPictureCard(
            MetricColors.sleepDeep, title, size, score?.toString() ?: asleep?.let(::clockDuration),
            stringResource(if (score != null) R.string.glow_today_score_unit else R.string.glow_today_hours_unit), tag,
            when {
                asleep == null -> noNight(firstNights, stuck)
                score != null -> Units.duration(asleep)
                else -> null
            },
            modifier, rim = rim, onClick = onClick, picture = picture,
        )
        return
    }
    // A night the strap gave no score shows the time asleep in its place, so its tag still has a number to sit by.
    TodayPictureCard(
        MetricColors.sleepDeep, title, size, score?.toString() ?: asleep?.let(::clockDuration),
        stringResource(if (score != null) R.string.glow_today_score_unit else R.string.glow_today_hours_unit), tag,
        when {
            asleep == null -> noNight(firstNights, stuck)
            score != null -> stringResource(
                R.string.glow_today_last_night_scored, org.openstrap.app.presentation.TabCopy.sleepScoreWord(score), Units.duration(asleep),
            )
            else -> null
        },
        modifier, note = s?.let { Ranges.times(it.start, it.end) }, rim = rim, onClick = onClick, pictureLast = true,
        dash = asleep == null, picture = picture,
    )
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
    PictureCard(
        MetricColors.heart, modifier.fillMaxWidth().gridSize(CardSize.Wide), onClick = onClick,
    ) {
        EdgeTitle(
            stringResource(R.string.glow_today_heart_rate), state.restingHeartRate?.let { stringResource(R.string.glow_today_resting, it) },
            marker = upTo,
        )
        val at = scrubbed?.second ?: state.heartRate.lastOrNull()?.x
        EdgeValue(
            now?.toString(), stringResource(R.string.glow_unit_bpm), TodayCopy.heartAt(live != null && scrubbed == null, at?.let(::clock)),
        )
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
 * Heart rate on Today (spec 4.2): the day's line through your resting range ([restingRange], your normal resting
 * heart rate) as a dim band, the latest reading or the [live] one in dots. Off your [normal] heart rate for a day, the
 * day so far running high or low, the card rims at the line's end and says Above or Below normal. One high reading
 * isn't news, a day running high is. No scrub here: that lives on Activity's Wide and in the detail.
 */
@Composable
internal fun HeartDayCard(
    state: DayUiState, live: Int?, size: PictureSize, onClick: () -> Unit, modifier: Modifier = Modifier,
    restingRange: org.openstrap.app.data.History.Normal? = null, normal: org.openstrap.app.data.History.Normal? = null,
    upTo: String? = null,
) {
    val values = remember(state.heartRate, state.dayStart, state.dayEnd) { bucket(state.heartRate, state.dayStart..state.dayEnd, HEART_STEP) }
    val mean = remember(values) { values.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.toFloat() }
    val now = live ?: state.latestHeartRate
    val off = bodyOffStatus(mean, normal)
    val at = TodayCopy.heartAt(live != null, state.heartRate.lastOrNull()?.x?.let(::clock))
    TodayPictureCard(
        MetricColors.heart, stringResource(R.string.glow_today_heart_rate), size, now?.toString(), stringResource(R.string.glow_unit_bpm),
        offTag(OffWords.Normal, off),
        // A Small says where the day sits against your normal once there is one, a Wide when the reading was taken.
        if (size == PictureSize.Small && normal != null && mean != null && !off.off) TodayCopy.normalWord(0) else at,
        modifier, note = state.restingHeartRate?.let { stringResource(R.string.glow_today_resting, it) },
        rim = if (rimLit(OffWords.Normal, off)) dayLineRim(values, off == OffStatus.Above, size) else null,
        onClick = onClick, marker = upTo,
        picture = if (state.heartRate.size < 2) null else { m -> DayLine(values, restingRange?.let { it.low..it.high }, MetricColors.heart, size, m) },
    )
}

/** Heart rate's line on Today: a point every ten minutes. */
private const val HEART_STEP = 10 * 60L

/**
 * Stress on Today (spec 4.2): the day as one strip, calm to high ([DayStrip], [DayUiState.stressStrip]), the latest
 * reading in dots. The day so far against your [normal] stress says Above or Below usual, with the rim over the
 * stretch that's off.
 */
@Composable
internal fun StressDayCard(
    state: DayUiState, size: PictureSize, normal: org.openstrap.app.data.History.Normal?, waiting: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, upTo: String? = null,
) {
    val off = org.openstrap.app.ui.dashboard.stressOffStatus(state.stress, normal)
    val levels = state.stressStrip
    val now = state.stress.lastOrNull()
    TodayPictureCard(
        MetricColors.stress, stringResource(R.string.glow_today_stress), size, now?.y?.roundToInt()?.toString(), null,
        offTag(OffWords.Usual, off),
        when {
            now == null -> waiting
            // Off, the tag says how it's going and the line when it was read.
            off.off -> TodayCopy.heartAt(false, clock(now.x))
            normal == null -> TodayCopy.normalWord(null)
            else -> TodayCopy.normalWord(0)
        },
        modifier, rim = if (rimLit(OffWords.Usual, off)) dayStripRim(levels, size) else null, onClick = onClick, marker = upTo,
        picture = if (levels.none { it != null }) null else { m -> DayStrip(levels, MetricColors.stress, size, m) },
    )
}

/**
 * Steps on Today (spec 4.2): today's hourly bars in lime, the count in dots with its goal tag after it ([goalTag]:
 * Goal met and up the ladder), and the pace in words under it. A Wide has "Goal 8,000" as the title row's note. No
 * progress bar: the bars and the tag say how far. [facts] are the day's goal facts ([org.openstrap.app.ui.dashboard
 * .ActivityHistory.stepsFacts]). Without them the tag climbs only as far as the goal itself tells.
 */
@Composable
internal fun ActivityCard(
    state: DayUiState, onClick: () -> Unit, modifier: Modifier = Modifier, usual: List<Float>? = null, upTo: String? = null,
    /** The Partial day tag when the strap was worn for part of the day ([TitleTag]). */
    tag: TitleTag? = null,
    size: PictureSize = PictureSize.Wide,
    facts: GoalFacts? = null,
) {
    val goal = org.openstrap.app.ui.dashboard.LocalGoals.current.steps
    val isToday = state.date == java.time.LocalDate.now()
    val now = java.time.LocalTime.now()
    val pace = org.openstrap.app.presentation.ActivityCopy.paceOf(state.steps, usual, isToday, now.hour, now.minute)
    // A Small is half a row: a count in the thousands leaves no room for "steps" beside it, and the unit dropping to a
    // line of its own squeezed the bars to a sliver. The title already says Steps, so a Small has the number alone and
    // only the goal share under it, one line like Heart rate's beside it; the pace waits on the Wide and the page.
    val small = size == PictureSize.Small
    TodayPictureCard(
        MetricColors.steps, stringResource(R.string.glow_today_steps_title), size, Units.count(state.steps, Units.DOT_LOCALE),
        if (small) null else stringResource(R.string.glow_today_steps), goalTag(GoalMetric.Steps, facts ?: GoalFacts(state.steps, goal)),
        if (small) org.openstrap.app.presentation.ActivityCopy.goalShare(state.steps, goal)
        else org.openstrap.app.presentation.ActivityCopy.activityLine(state.steps, goal, pace, isToday),
        modifier, note = stringResource(R.string.glow_today_steps_goal, Units.count(goal)), onClick = onClick, marker = upTo, titleTag = tag,
        picture = if (state.steps <= 0) null else { m -> HourBars(state.hourlySteps, MetricColors.steps, m) },
    )
}

/**
 * The day's steps hour by hour as solid pill bars in [ink] on a hairline (spec 2.7): hours with no steps and hours to
 * come aren't drawn, the latest hour with steps in full ink and the rest a little dimmer.
 */
@Composable
// The hours arrive whole with each update and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
private fun HourBars(hourly: List<Float>, ink: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        if (hourly.isEmpty()) return@Canvas
        val line = org.openstrap.app.ui.glow.draw.ChartInk.HAIRLINE_DP.dp.toPx()
        val base = size.height - line / 2
        drawLine(
            org.openstrap.app.ui.glow.draw.ChartInk.Grid, androidx.compose.ui.geometry.Offset(0f, base),
            androidx.compose.ui.geometry.Offset(size.width, base), line,
        )
        val max = hourly.max().coerceAtLeast(1f)
        val pitch = size.width / hourly.size
        val latest = hourly.indexOfLast { it > 0f }
        val room = base - 2.dp.toPx()
        hourly.forEachIndexed { i, v ->
            if (v > 0f) pillBar(if (i == latest) ink else ink.copy(alpha = HOUR_BAR_ALPHA), pitch * i, pitch, base - line, room * v / max)
        }
    }
}

/** The hours before the latest, a little dimmer than it. */
private const val HOUR_BAR_ALPHA = 0.6f

/**
 * This morning's Recovery, later in the day (spec 4.2): a week of dots through the band of your usual
 * ([org.openstrap.app.data.RecoveryResult.readinessTrend], [org.openstrap.app.data.RecoveryResult.readinessUsual]),
 * the score in dots, and Above or Below usual with the rim by the latest dot when it's off. The same score the waking
 * hero shows: OpenStrap's, the strap's estimate labelled as such, or why there's none yet.
 */
@Composable
internal fun RecoveryEarlierCard(
    shown: TodayCopy.RecoveryShown, onClick: () -> Unit, modifier: Modifier = Modifier,
    /** Today's score failed to work out: yesterday's stays, dimmed, with a way to try again (spec 4.3). */
    failure: RecoveryFailure? = null, onRetry: () -> Unit = {},
    size: PictureSize = PictureSize.Wide,
    /** The day's Recovery, for its week of dots and its usual. Null draws the score alone. */
    recovery: org.openstrap.app.data.RecoveryResult? = null,
) {
    val title = stringResource(R.string.glow_today_recovery_title)
    if (failure != null) {
        // A Small has no room for Try again: it says it failed and opens Recovery, where Try again is.
        if (size == PictureSize.Small) TodayPictureCard(
            MetricColors.readiness, title, size, null, null, null, FAILED_SHORT, modifier, onClick = onClick, dash = false,
        )
        else PictureCard(MetricColors.readiness, modifier.fillMaxWidth().gridSize(CardSize.Wide), onClick = onClick) {
            EdgeTitle(title, stringResource(R.string.glow_today_this_morning))
            RecoveryFailed(failure, onRetry)
        }
        return
    }
    val week = remember(recovery) { recovery?.readinessTrend?.map { it.second?.toFloat() }.orEmpty() }
    val usual = recovery?.readinessUsual?.let { it.low..it.high }
    val off = org.openstrap.app.ui.dashboard.recoveryOffStatus(recovery)
    TodayPictureCard(
        MetricColors.readiness, title, size, shown.value?.toString(), shown.word.takeIf { size == PictureSize.Wide },
        // The strap's estimate isn't OpenStrap's score, so it's never said against your usual.
        offTag(OffWords.Usual, off).takeIf { !shown.estimate },
        if (size == PictureSize.Small && shown.value != null) shown.word else shown.line,
        modifier, note = stringResource(R.string.glow_today_this_morning),
        rim = if (rimLit(OffWords.Usual, off) && !shown.estimate) weekOfDotsRim(week, usual, size) else null, onClick = onClick,
        picture = if (week.none { it != null }) null else { m -> WeekOfDots(week, usual, MetricColors.readiness, size, m) },
    )
}

/**
 * Load on Today (spec 4.2): a level filling to today's Load over a dim band of your usual ([LoadBands.usualRange]),
 * the Load in dots, and Above or Below usual with the rim past the band when it's off. A low Load is only so far until
 * the day is over ([org.openstrap.app.ui.dashboard.loadOffStatus]), so it's never news before then.
 */
@Composable
internal fun LoadCard(
    load: Float?, usual: Float?, endOfDay: Boolean, status: String, size: PictureSize, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val off = org.openstrap.app.ui.dashboard.loadOffStatus(load, usual, endOfDay)
    val band = org.openstrap.app.presentation.LoadBands.usualRange(usual)
    TodayPictureCard(
        MetricColors.load, stringResource(R.string.glow_today_load), size, load?.roundToInt()?.toString(), null, offTag(OffWords.Usual, off), status,
        modifier, rim = if (load != null && rimLit(OffWords.Usual, off)) levelFillRim(load, band, size) else null, onClick = onClick,
        picture = if (load == null) null else { m -> LevelFill(load, band, MetricColors.load, size, m) },
    )
}

/**
 * Time to recover on Today (spec 4.2): a ring filling toward Ready from the end of the [workout] that says how long it
 * needs, full once you're clear. The hours left in dots and when you're ready under them. A countdown, not a reading,
 * so never a tag or a rim.
 */
@Composable
internal fun RecoverCard(
    workout: org.openstrap.app.data.WorkoutAnalysis?, now: Long, zone: ZoneId, size: PictureSize, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val left = workout?.let { TodayCopy.recoverLeft(it.end, it.recoveryMinutes ?: 0, now) }
    val text = left?.let { TodayCopy.recoverShort(it, now, zone) }
    val share = recoverShare(workout?.recoveryMinutes, left)
    TodayPictureCard(
        MetricColors.load, stringResource(R.string.glow_today_to_recover), size, text?.first, stringResource(R.string.glow_today_hours_unit), null,
        text?.second ?: stringResource(R.string.glow_today_recover_no_estimate), modifier, onClick = onClick, dash = false,
        picture = if (left == null) null else { m -> CountdownRing(share, MetricColors.load, m) },
    )
}

/**
 * How far Time to recover's ring has filled toward Ready: the share of the workout's [recoveryMinutes] gone, with
 * [left] seconds still to go. Full once you're clear, or with no estimate to count down.
 */
internal fun recoverShare(recoveryMinutes: Int?, left: Long?): Float {
    val total = (recoveryMinutes ?: 0) * 60L
    return if (left == null || total <= 0) 1f else 1f - left.toFloat() / total
}

/** In place of a Recovery that failed: yesterday's score dimmed, the line, and Try again. */
@Composable
internal fun RecoveryFailed(failure: RecoveryFailure, onRetry: () -> Unit) {
    failure.yesterday?.let { y ->
        Column(Modifier.alpha(FAILED_ALPHA)) { EdgeValue(y.toString(), stringResource(R.string.glow_today_yesterday), null) }
    }
    Text(RECOVERY_FAILED, color = Color.White.copy(alpha = 0.85f), style = GlowType.secondary, modifier = Modifier.padding(top = 8.dp))
    PageButton(TRY_AGAIN, onRetry, Modifier.padding(top = 12.dp), size = ButtonSize.Hero, onCard = true)
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
                GlowHeader(stringResource(R.string.glow_today_energy_title), marker = upTo, tag = tag)
                if (soFar == null || soFar.count { it != null } < 2) EdgeValue(now?.toString(), null, status, Color.White.copy(alpha = 0.8f), dot = DotSize.Second)
                else Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) { EdgeValue(now?.toString(), null, status, Color.White.copy(alpha = 0.8f), dot = DotSize.Second) }
                    Spacer(Modifier.width(14.dp))
                    GhostLine(soFar, Color.White, 100f, Modifier.weight(1f), height = 52.dp)
                }
            }
        }
    } else PictureCard(MetricColors.charge, modifier.fillMaxWidth(), onClick = onClick) {
        EdgeTitle(stringResource(R.string.glow_today_energy_title), null, marker = upTo, tag = tag)
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
    PictureCard(MetricColors.heart, modifier.fillMaxWidth().gridSize(CardSize.Wide), onClick = onClick) {
        EdgeTitle(Workouts.sportName(w.workout.sportType), Ranges.times(w.shownStart, w.end))
        val mostly = w.zoneSeconds.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index
        EdgeValue(
            dotClock(w.activeSeconds.coerceAtLeast(0)), stringResource(R.string.glow_today_workout_long),
            workoutLine(w.workout.averageHeartRate, mostly?.let { it + 1 }),
        )
        // Zone bar (chart kit): how the time was spent.
        ZoneBar(w.zoneSeconds, org.openstrap.app.ui.workout.ZONE_COLORS, Modifier.padding(top = 8.dp))
    }
}

/** The workout card's line: its average heart rate and the zone it mostly sat in, either alone, or null with neither. */
@Composable
@ReadOnlyComposable
private fun workoutLine(average: Int?, zone: Int?): String? = when {
    average != null && zone != null -> stringResource(R.string.glow_today_workout_average_zone, average, zone)
    average != null -> stringResource(R.string.glow_today_workout_average, average)
    zone != null -> stringResource(R.string.glow_today_workout_zone, zone)
    else -> null
}

/**
 * Your week (spec 4.2): Monday to Sunday as a ring a day in Steps' lime ([WeekRings]), full for a day at the goal, a
 * day to come its faint track alone, and "4 of 7 days at goal" as the title row's note. Unlit, with no number: the
 * rings are the card. Today's steps are the live count, the days before come from Steps' daily [history]. It opens the
 * week story.
 */
@Composable
internal fun WeekCard(
    state: DayUiState, history: DailyHistory?, modifier: Modifier = Modifier,
    /** Opens the week story. */
    onClick: (() -> Unit)? = null,
) {
    val goal = org.openstrap.app.ui.dashboard.LocalGoals.current.steps
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    // The phone's own week, as everywhere else and on the page it opens ([TodayCopy.thisWeek]).
    val firstDay = remember { org.openstrap.app.data.Weeks.firstDay() }
    val start = org.openstrap.app.data.Weeks.start(state.date, firstDay)
    val end = start.plusDays(org.openstrap.app.data.Weeks.DAYS - 1L)
    val stored by remember(start, history) {
        history?.invoke(Metric.Steps.daily, start, end) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val today = java.time.LocalDate.now()
    val week = remember(stored, state.date, state.steps, goal, today) {
        org.openstrap.app.ui.dashboard.stepsWeek(
            state.date, state.steps, goal, stored.associate { it.date to it.value.roundToInt() }, today, firstDay,
        )
    }
    val shares = week.days.map { (_, steps) -> steps?.let { if (goal > 0) it.toFloat() / goal else 0f } }
    val atGoal = week.days.count { (_, steps) -> steps != null && goal > 0 && steps >= goal }
    PictureCard(MetricColors.steps, modifier.fillMaxWidth().gridSize(CardSize.Wide), onClick = onClick) {
        EdgeTitle(
            stringResource(R.string.glow_today_week_title),
            pluralStringResource(R.plurals.glow_today_week_at_goal, week.days.size, atGoal, week.days.size),
        )
        Spacer(Modifier.height(14.dp))
        WeekRings(shares, MetricColors.steps, PictureSize.Wide, Modifier.fillMaxWidth().height(WidePictureHeight))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            week.days.forEach { (day, _) ->
                Text(
                    day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale),
                    Modifier.weight(1f), color = Color.White.copy(alpha = if (day == state.date) 1f else GlowType.CAPTION_ALPHA),
                    style = GlowType.caption, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                )
            }
        }
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
    rim: Rim? = null, statusColor: Color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
    onClick: (() -> Unit)? = null, extra: (@Composable () -> Unit)? = null,
    /** Waiting for its first value: the dim dash with [status] under it, rather than the word in its place. */
    waiting: Boolean = false,
    /** The number as plain text ([GlowType.smallValue]), for a Small that breaks down the card beside it. */
    plain: Boolean = false,
) {
    PictureCard(color, modifier, rim = rim, onClick = onClick) {
        CardTitle(title)
        Spacer(Modifier.weight(1f))
        if (value == null && status != null && !waiting) {
            extra?.invoke()
            Text(status, color = Color.White, style = GlowType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            return@PictureCard
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
    /** What the row says while its normal is forming, "Learning, 5 of 7 nights"; null for the plain word. */
    val learning: String? = null,
)

/**
 * Body values against your normal, each opening its metric, read and rounded as the Recovery tab's body signals are.
 * [waiting] is what an empty row says.
 */
@Composable
internal fun BodyList(
    title: String, rows: List<BodyRow>, waiting: String, onOpenMetric: (Metric) -> Unit, modifier: Modifier = Modifier, upTo: String? = null,
) {
    ListPanel(modifier, title = title, note = stringResource(R.string.glow_today_against_normal), marker = upTo) {
        rows.forEachIndexed { i, r ->
            val direction = org.openstrap.app.data.History.status(r.value, r.normal)
            ListRow(
                r.name, r.color, r.value?.let { r.metric.format(it) + r.unit },
                when {
                    r.value == null -> waiting
                    r.normal == null && r.learning != null -> r.learning
                    else -> TodayCopy.normalWord(direction)
                },
                tag = org.openstrap.app.ui.dashboard.bodyTag(r.metric, direction), onClick = { onOpenMetric(r.metric) }, divider = i > 0,
                icon = r.metric.rowIcon(),
            )
        }
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
            Text(stringResource(R.string.glow_today_edit), color = Color.White, style = GlowType.body)
        }
    }
}
