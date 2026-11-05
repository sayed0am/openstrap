package org.openstrap.app.ui.glow

import org.openstrap.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalResources
import org.openstrap.app.presentation.dotFormat
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.SleepEditCopy
import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.TimeOfDay
import org.openstrap.app.presentation.Units
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.data.DayStat
import org.openstrap.app.data.History
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.wasoMinutes
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.presentation.DetailCopy
import org.openstrap.app.ui.dashboard.InfoCopy
import org.openstrap.app.ui.dashboard.LocalGoals
import org.openstrap.app.ui.dashboard.Narrative
import org.openstrap.app.presentation.Range
import org.openstrap.app.presentation.SLEEP_NAMES
import org.openstrap.app.ui.dashboard.SLEEP_NIGHTS
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.SleepCopy
import org.openstrap.app.ui.dashboard.SleepWindows
import org.openstrap.app.presentation.TabCopy
import org.openstrap.app.ui.dashboard.sleepWeek
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.ZONE_COLORS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.openstrap.app.ui.glow.draw.stageColor
import org.openstrap.app.ui.glow.pictures.Nap
import org.openstrap.app.ui.glow.pictures.NapDay
import org.openstrap.app.ui.glow.pictures.PictureSize

/*
 * The Sleep tab's details (spec 5.2 and 6.1) in the metric template: the Sleep score with the night it came from,
 * Sleep regularity over the week, and the day's naps. They switch among themselves. Everything the Sleep tab let go
 * of in step 5 is here: what shaped the score, the night's heart rate and breathing, and each nap's heart rate.
 */

/** The days of the strip with a main sleep: the nights the day loads, and later days with breathing rate (measured in sleep). */
@Composable
private fun sleepDays(state: DayUiState, history: DailyHistory): Set<LocalDate> {
    val today = LocalDate.now()
    // The loaded nights are sleep as shown (a deleted night has none); breathing only fills in days past them. The day
    // shown goes by its own night, as the page says it, so a dot never stands beside "No sleep recorded".
    val loaded = state.nights.map { it.date }.toSet()
    val days = state.nights.filter { it.session != null }.map { it.date }.toSet() +
        daysWithData(DailyMetric.Breathing, state.date, today, history).filter { it !in loaded }
    return if (state.loaded && state.sleep == null) days - state.date else days
}

/** The lines in a detail's body that aren't the one sentence: a reading's caption, a chart's title. */
@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, modifier = modifier)

/** A chart's title inside a detail's body, with an optional [note] on the right. */
@Composable
private fun ChartTitle(title: String, note: String?, modifier: Modifier = Modifier, scrubbed: DetailHeadline? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
        // While the line is scrubbed, its reading takes the note's place, in white so it reads as the live value.
        if (scrubbed != null) Text(
            stringResource(R.string.glow_sleep_scrubbed, scrubbed.value.toString(), scrubbed.label), color = Color.White,
            style = GlowType.secondary, maxLines = 1,
        )
        else if (note != null) Text(note, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
    }
}

/* ---------------------------------------------- Sleep score ----------------------------------------- */

/**
 * The Sleep score's detail: the score in its horizon arc, then the night it came from. Day has the stages to scrub
 * with their totals as stage bars, bedtime to wake, the sentence, what shaped the score, and the night's heart rate and
 * breathing lines, each scrub moving the headline. Week has the last 7 nights' scores against your normal of the
 * nights before.
 */
@Composable
internal fun SleepScoreDetail(
    state: DayUiState, r: RecoveryResult?, history: DailyHistory,
    onOpen: (Detail) -> Unit, onSwitch: (Detail) -> Unit, onSelectDay: (LocalDate) -> Unit, contentPadding: PaddingValues, onBack: () -> Unit,
    editing: SleepEditing,
) {
    val session = state.sleep
    val score = session?.score
    val color = MetricColors.sleepDeep
    var adjusting by remember(state.date) { mutableStateOf(false) }
    var deleting by remember(state.date) { mutableStateOf(false) }
    // The strap's session just deleted, while its Undo shows.
    var undo by remember { mutableStateOf<SleepSession?>(null) }
    // TODO(step 6): Month and Year wait for a stored history of the strap's sleep scores; the day loads only 14 nights.
    val ranges = listOf(Range.Day, Range.Week)
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val range = ranges[rangeIndex.coerceIn(ranges.indices)]
    var scrub by remember(range, state.date) { mutableStateOf<DetailHeadline?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    val isToday = state.date == today

    // The week's scores, the night shown last; your normal from the nights before it, as the night vitals' is.
    val week = state.nights.takeLast(SleepHistory.WEEK_NIGHTS).map { n -> n.date to if (n.date == state.date) score?.toFloat() else n.session?.score?.toFloat() }
    val before = state.nights.filter { it.date < state.date }.mapNotNull { n -> n.session?.score?.let { DayStat(n.date, it.toFloat(), it.toFloat(), it.toFloat(), 1) } }
    val normal = History.normal(before, state.date.minusDays(1), SLEEP_NIGHTS - 1)
    val known = week.mapNotNull { it.second }

    val headline = when {
        range == Range.Week && known.isNotEmpty() ->
            DetailHeadline(Math.round(known.average()).toString(), stringResource(R.string.glow_sleep_week_average), null)
        range == Range.Week ->
            DetailHeadline.none(stringResource(R.string.glow_sleep_no_scores), stringResource(R.string.glow_sleep_in_last_7_nights))
        score != null -> DetailHeadline(score.toString(), SleepCopy.scoreLabel(isToday), TabCopy.sleepScoreWord(score))
        session == null && state.deletedSleep != null ->
            DetailHeadline.none(stringResource(R.string.glow_sleep_no_score), SleepCopy.noScoreDeleted(isToday))
        else -> SleepCopy.noScore(session != null, isToday).let { (word, why) -> DetailHeadline.none(word, why) }
    }

    UndoHost(undo?.let { SleepCopy.deleted(false, isToday, clock(it.start)) }, contentPadding, onUndo = { undo?.let(editing.restore); undo = null }, onGone = { undo = null }) {
        DetailTemplate(
            Score.Sleep.title, color, siblings(Detail.OfScore(Score.Sleep), onSwitch), onInfo = { info = true }, onBack = onBack,
            contentPadding = contentPadding, headline = headline,
            strip = DetailCopy.strip(state.date, today, sleepDays(state, history)), onDay = onSelectDay,
            ranges = ranges, range = range, onRange = { rangeIndex = ranges.indexOf(it) },
            // The horizon arc (chart kit, spec 2.7) holds the night's score, centred, the number under its crest. A
            // scrub takes its place in the hero alone, so the rest of the detail stays put (audit K4).
            heroValue = if (range == Range.Day && score != null) {
                { HorizonArc(score / 100f, color, key = state.date) { DetailScoreNumber(score) } }
            } else null,
            scrubbed = { scrub },
        ) {
            if (range == Range.Day) {
                val deleted = state.deletedSleep
                if (session == null && deleted != null) {
                    item { DeletedNight(SleepCopy.deletedNight(clock(deleted.start), clock(deleted.end), isToday)) { editing.restore(deleted.strap) } }
                } else if (session == null) {
                    item { DetailEmpty(TabCopy.noNight(state.naps.isNotEmpty(), isToday)) }
                } else {
                    val asleep = state.asleepSeconds ?: (session.end - session.start)
                    val detected = state.sleepAsDetected
                    val moved = detected != null && (detected.start != session.start || detected.end != session.end)
                    item {
                        Column(Modifier.padding(top = 8.dp)) {
                            // Moved, the strap's whole night shows with what's outside the user's times dimmed.
                            if (moved && state.strapSleepStages.isNotEmpty()) GlowSleepStages(state.strapSleepStages, 150.dp, kept = session.start..session.end)
                            else if (state.sleepStages.isNotEmpty()) GlowSleepStages(state.sleepStages, 150.dp)
                            state.sleepBreakdown?.let { StageBars(it, Modifier.padding(top = 18.dp)) }
                            // Times extended past the strap's have no stages for the added time, so the page says so.
                            if (detected != null && state.sleepBreakdown != null) {
                                SleepCopy.stagesCover(session.start, session.end, detected.start, detected.end)?.let {
                                    Caption(it, Modifier.padding(top = 12.dp))
                                }
                            }
                            DetailFigures(
                                listOf(
                                    clock(session.start) to SleepCopy.FELL_ASLEEP, clock(session.end) to SleepCopy.WOKE,
                                    Units.duration(asleep) to SleepCopy.ASLEEP,
                                ),
                                Modifier.padding(top = 20.dp),
                            )
                            if (detected != null) SleepEditActions(
                                if (moved) SleepCopy.yourTimes(clock(detected.start), clock(detected.end)) else null,
                                onAdjust = { adjusting = true }, onDelete = { deleting = true }, Modifier.padding(top = 20.dp),
                            )
                        }
                    }
                    item {
                        val others = state.nights.takeLast(SleepHistory.WEEK_NIGHTS).filter { it.date != state.date }.mapNotNull { it.asleepSeconds }
                        Column {
                            DetailSentence(SleepCopy.night(Narrative.sleepHeadline(session, asleep), asleep, others))
                            TabCopy.woke(session.wakeCount)?.let { Caption(it, Modifier.padding(top = 6.dp)) }
                        }
                    }
                    // What shaped the score: time asleep against your need and staying asleep, against sleep guidance.
                    val need = r?.sleepNeed ?: Recovery.SLEEP_NEED_MINUTES
                    val waso = Recovery.wasoMinutes(state.sleepStages.map { Triple(it.start, it.end, it.stage) })
                    val factors = SleepCopy.factors(Narrative.sleepContributors(session, asleep, need, waso), asleep, need, waso)
                    if (factors.isNotEmpty()) item {
                        ListPanel(Modifier.padding(top = 8.dp), title = stringResource(R.string.glow_sleep_what_shaped)) {
                            factors.forEachIndexed { i, f ->
                                ContributorRow(f.name, color, f.word, f.fraction, offNormal = f.off, divider = i > 0, detail = f.detail, onClick = { info = true })
                            }
                        }
                    }
                    item { NightLines(state, session.start..session.end) { scrub = it } }
                }
            } else {
                item {
                    if (known.isEmpty()) DetailEmpty(stringResource(R.string.glow_sleep_no_scores_week))
                    else Column {
                        val values = week.map { it.second }
                        NormalTrend(
                            values, DetailCopy.levels(values, normal), color, labels = week.map { DayWords.weekdayShort(it.first) },
                            band = normal?.let { it.low..it.high }, bandLabel = normal?.let { DetailCopy.bandLabel(it) { v -> Math.round(v).toString() } },
                            onScrub = { i ->
                                scrub = i?.let { week[it] }?.let { (d, v) ->
                                    v?.let { Math.round(it) }?.let { score ->
                                        DetailHeadline(score.toString(), DayWords.short(d), TabCopy.sleepScoreWord(score))
                                    }
                                }
                            },
                        )
                        if (normal == null) Caption(DetailCopy.learningNormal(before.size, nightly = true))
                    }
                }
                val values = week.map { it.second }
                DetailCopy.span(Range.Week, DetailCopy.levels(values, normal), nightly = true, normalKnown = normal != null, days = before.size)?.let {
                    item { DetailSentence(it) }
                }
            }
            item { SleepRelated(Score.Sleep, state, r, onOpen) }
        }
    }

    val detected = state.sleepAsDetected
    // A move of 15 minutes or more has the strap's score adjusted for the user's times (edit spec 5).
    val ownScore = session != null && detected != null && score != null && !SleepEdits.keepsScore(detected, session.start, session.end)
    if (info) InfoSheet(
        InfoCopy.of(Score.Sleep), color, score?.let(InfoCopy::sleepBar), score?.let { InfoCopy.HERE },
        onDismiss = { info = false },
        notes = listOfNotNull(SleepCopy.SCORE_FROM_YOUR_TIMES.takeIf { ownScore }) + InfoCopy.sleepNotes(r?.sleepNeed ?: Recovery.SLEEP_NEED_MINUTES),
    )
    if (adjusting && session != null && detected != null) SleepTimesSheet(
        session, detected, state.sleepHeartRate, state.sleepNeighbours.filter { it.nap || it.start != session.start },
        onSave = { from, to -> adjusting = false; editing.setTimes(detected, from, to) }, onDismiss = { adjusting = false },
    )
    if (deleting && session != null && detected != null) DeleteSleepSheet(
        SleepCopy.deleteTitle(false, isToday), Ranges.of(clock(session.start), clock(session.end)),
        onDelete = { deleting = false; editing.delete(detected); undo = detected }, onDismiss = { deleting = false },
    )
}

/**
 * What the Sleep score and Naps details can do to a sleep or nap, each given the strap's own session: keep the user's
 * times for it, delete it, and bring it back (Undo and Restore). Equal when its actions are (the same view model's
 * references are), so a detail given an equal one skips (audit K11).
 */
@androidx.compose.runtime.Immutable
data class SleepEditing(
    val setTimes: (SleepSession, Long, Long) -> Unit = { _, _, _ -> },
    val delete: (SleepSession) -> Unit = {},
    val restore: (SleepSession) -> Unit = {},
)

/**
 * Under a sleep's or nap's figures: the note that the times are the user's ([note], with a pencil, when they are),
 * then Adjust times and Delete side by side, Delete in the warning colour.
 */
@Composable
private fun SleepEditActions(note: String?, onAdjust: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (note != null) EditedNote(note)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PageButton(stringResource(R.string.glow_sleep_adjust_times), onAdjust, Modifier.weight(1f), icon = AppIcons.PencilEdit)
            PageButton(
                stringResource(R.string.glow_delete), onDelete, Modifier.weight(1f), color = MetricColors.warn, icon = AppIcons.Trash,
            )
        }
    }
}

/**
 * Asked once before a sleep or nap goes: [title], its [times], what a delete does and where to bring it back, then
 * Cancel and Delete.
 */
@Composable
private fun DeleteSleepSheet(title: String, times: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    PageSheet(onDismiss, title) {
        Text(
            stringResource(R.string.glow_sleep_delete_text, times, SleepCopy.DELETE_WHAT), color = Color.White.copy(alpha = 0.8f),
            style = GlowType.body,
        )
        PageNote(SleepCopy.DELETE_RESTORE)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(stringResource(R.string.glow_cancel), onDismiss, Modifier.weight(1f))
            PageButton(
                stringResource(R.string.glow_delete), onDelete, Modifier.weight(1f), color = MetricColors.warn, icon = AppIcons.Trash,
            )
        }
    }
}

/** In a deleted night's place: No sleep recorded, which times were deleted and what Recovery does, and Restore. */
@Composable
private fun DeletedNight(text: String, onRestore: () -> Unit) {
    OutlineCard(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(SleepCopy.NO_SLEEP_TITLE, color = Color.White, style = GlowType.cardTitle, maxLines = 1)
        Text(text, color = Color.White.copy(alpha = 0.75f), style = GlowType.body)
        PageButton(
            stringResource(R.string.glow_sleep_restore), onRestore, Modifier.padding(top = 4.dp), icon = AppIcons.ArrowCounterClockwise,
        )
    }
}

/**
 * [content] with a snackbar at its foot while [message] is set: the message and Undo, for [UNDO_MS], then [onGone].
 * Light on the dark page so it reads as passing, not as another card.
 */
@Composable
private fun UndoHost(message: String?, contentPadding: PaddingValues, onUndo: () -> Unit, onGone: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        if (message != null) {
            androidx.compose.runtime.LaunchedEffect(message) {
                kotlinx.coroutines.delay(UNDO_MS)
                onGone()
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 16.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth().height(56.dp).background(SnackbarSurface, CircleShape).padding(start = 22.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, color = Color.Black, style = GlowType.body, maxLines = 1, modifier = Modifier.weight(1f))
                Row(
                    Modifier.height(40.dp).clip(CircleShape).background(Color.Black).pressClickable(onUndo, CircleShape)
                        .semantics { role = Role.Button }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(AppIcons.ArrowCounterClockwise, null, Modifier.size(16.dp), tint = Color.White)
                    Text(
                        stringResource(R.string.glow_sleep_undo), color = Color.White, style = GlowType.body,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

/** How long a delete's Undo shows. */
private const val UNDO_MS = 8_000L

private val SnackbarSurface = Color(0xFFF2F2F4)

/** "Your times. The strap saw 23:20 to 07:21." on the list surface, with a pencil. */
@Composable
private fun EditedNote(text: String) {
    Row(
        Modifier.fillMaxWidth().background(ListSurface, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(AppIcons.PencilEdit, null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.7f))
        Text(text, color = Color.White, style = GlowType.secondary, modifier = Modifier.padding(start = 10.dp).weight(1f))
    }
}

/**
 * Stage bars (chart kit): each stage's name, a bar in its colour as long as its share of the night, and its time.
 * Under the hypnogram, deepest first.
 */
@Composable
private fun StageBars(b: Summaries.SleepBreakdown, modifier: Modifier = Modifier) {
    val total = (b.asleepSeconds + b.awakeSeconds).coerceAtLeast(1)
    val order = listOf(
        org.openstrap.app.data.db.SleepStage.DEEP, org.openstrap.app.data.db.SleepStage.REM,
        org.openstrap.app.data.db.SleepStage.LIGHT, org.openstrap.app.data.db.SleepStage.AWAKE,
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        order.forEach { st ->
            val secs = b.byStage[st] ?: 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SLEEP_NAMES[st].orEmpty(), color = Color.White.copy(alpha = 0.85f), style = GlowType.secondary, modifier = Modifier.width(56.dp))
                FillBar(secs.toFloat() / total, stageColor(st), Modifier.weight(1f), height = 10.dp)
                Text(
                    Units.duration(secs), color = Color.White, style = GlowType.secondary,
                    modifier = Modifier.padding(start = 12.dp).width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

/**
 * The night's heart rate and breathing as lines with scrub (chart kit), each scrub moving the detail's headline
 * ([onScrub], null on release). Either is left out without readings. They sit below the fold, where the headline has
 * scrolled away, so a scrub also puts the reading in the line's own title row.
 */
@Composable
private fun NightLines(state: DayUiState, window: LongRange, onScrub: (DetailHeadline?) -> Unit) {
    // Kept while the readings and the window are, so the lines' own remember holds across scrubs (audit K4).
    val hr = remember(state.sleepHeartRate, window) { state.sleepHeartRate.filter { it.x in window } }
    val breath = remember(state.breathRate, window) { state.breathRate.filter { it.x in window } }
    if (hr.isEmpty() && breath.isEmpty()) return
    val labels = listOf(clock(window.first), clock(window.last))
    var hrAt by remember(window) { mutableStateOf<DetailHeadline?>(null) }
    var breathAt by remember(window) { mutableStateOf<DetailHeadline?>(null) }
    Column {
        if (hr.isNotEmpty()) {
            ChartTitle(
                stringResource(R.string.glow_sleep_heart_rate),
                stringResource(R.string.glow_sleep_heart_note, hr.minOf { it.y }.toInt(), hr.map { it.y }.average().toInt()),
                scrubbed = hrAt,
            )
            ScrubLine(hr, window, 130.dp, labels, MetricColors.heart, stringResource(R.string.glow_unit_bpm)) { hrAt = it; onScrub(it) }
        }
        if (breath.isNotEmpty()) {
            val average = stringResource(R.string.glow_sleep_breathing_note, Units.decimal(breath.map { it.y }.average(), 1))
            ChartTitle(stringResource(R.string.glow_sleep_breathing_rate), average, scrubbed = breathAt)
            ScrubLine(
                breath, window, 100.dp, labels, MetricColors.sleepLight, stringResource(R.string.glow_sleep_unit_breaths), decimals = 1,
            ) { breathAt = it; onScrub(it) }
        }
    }
}

/** A line of [points] over [window] in 5-minute steps; scrubbing puts the reading and its time in the headline. */
@Composable
private fun ScrubLine(
    points: List<ChartPoint>, window: LongRange, height: Dp, labels: List<String>, color: Color, unit: String, decimals: Int = 0,
    onScrub: (DetailHeadline?) -> Unit,
) {
    val step = 5 * 60L
    val values = remember(points, window) { bucket(points, window, step) }
    val resources = LocalResources.current
    GlowLineChart(
        values, height = height, labels = labels, color = color, showAverage = false,
        onScrub = { i ->
            val headline = i?.let { values[it] }?.let { v ->
                val at = resources.getString(R.string.glow_sleep_scrub_at, unit, clock(window.first + i * step))
                DetailHeadline(dotFormat("%.${decimals}f", v), at, null)
            }
            onScrub(headline)
        },
    )
}

/* ---------------------------------------------- Regularity ------------------------------------------ */

/**
 * Sleep regularity's detail: the week's bed and wake bars against your usual times, large enough to tap a night,
 * which picks that day. The night shown is in white with how it sat against your usual; the method is in the sheet.
 */
@Composable
internal fun RegularityDetail(
    state: DayUiState, r: RecoveryResult?, history: DailyHistory,
    onOpen: (Detail) -> Unit, onSwitch: (Detail) -> Unit, onSelectDay: (LocalDate) -> Unit, contentPadding: PaddingValues, onBack: () -> Unit,
) {
    val reg = r?.regularity
    val sri = reg?.sri
    val band = reg?.band
    val color = MetricColors.sleepDeep
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val isToday = state.date == today
    var info by rememberSaveable { mutableStateOf(false) }
    val w = sleepWeek(state, LocalGoals.current.sleepSeconds, reg, zone)
    val headline = when {
        sri != null && band != null -> DetailHeadline(Math.round(sri).toString(), SleepCopy.REGULARITY_LABEL, band.label)
        w == null -> DetailHeadline.none(stringResource(R.string.glow_sleep_no_sleep), stringResource(R.string.glow_sleep_in_last_7_nights))
        else -> SleepCopy.regularityState(reg).let { (word, why) -> DetailHeadline.none(word, why) }
    }

    // Regularity is a week's measure, so it has no Day and no pills; the strip still picks the week's last day.
    DetailTemplate(
        Score.Regularity.title, color, siblings(Detail.OfScore(Score.Regularity), onSwitch), onInfo = { info = true }, onBack = onBack,
        contentPadding = contentPadding, headline = headline,
        strip = DetailCopy.strip(state.date, today, sleepDays(state, history)), onDay = onSelectDay,
        ranges = listOf(Range.Week), range = Range.Week, onRange = {},
    ) {
        item {
            if (w == null) DetailEmpty(stringResource(R.string.glow_sleep_none_week))
            else Column(Modifier.padding(top = 8.dp)) {
                SleepWindows(w.nights, w.usual, zone, selected = state.date, onNight = onSelectDay, height = 200.dp)
                val night = state.sleep
                val u = w.usual
                Caption(
                    if (night == null) SleepCopy.noNight(isToday)
                    else SleepCopy.regularityNight(minutesOf(night.start, zone), minutesOf(night.end, zone), u?.bedtime, isToday),
                    Modifier.padding(top = 14.dp),
                )
                if (u != null) DetailFigures(
                    listOf(
                        TimeOfDay.minutes(u.bedtime) to stringResource(R.string.glow_sleep_usually_asleep),
                        TimeOfDay.minutes(u.wake) to stringResource(R.string.glow_sleep_usually_up),
                    ),
                    Modifier.padding(top = 18.dp),
                )
            }
        }
        item { DetailSentence(SleepCopy.regularity(reg)) }
        item { SleepRelated(Score.Regularity, state, r, onOpen) }
    }

    if (info) InfoSheet(
        InfoCopy.of(Score.Regularity), color, band?.let(InfoCopy::regularityBar), band?.let { InfoCopy.HERE },
        onDismiss = { info = false }, notes = InfoCopy.regularityNotes(reg),
    )
}

private fun minutesOf(epoch: Long, zone: ZoneId): Int = Instant.ofEpochSecond(epoch).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

/* ------------------------------------------------- Naps --------------------------------------------- */

/**
 * The day's naps: how long in all, then each nap with its time, its stage strip and stage totals, and its heart rate
 * line, the scrub moving the headline. With none it says so.
 */
@Composable
internal fun NapsDetail(
    state: DayUiState, r: RecoveryResult?,
    onOpen: (Detail) -> Unit, onSwitch: (Detail) -> Unit, onSelectDay: (LocalDate) -> Unit, contentPadding: PaddingValues, onBack: () -> Unit,
    editing: SleepEditing,
) {
    val naps = state.naps.sortedBy { it.start }
    // The nap whose times are being adjusted, or that's being deleted, by its start as shown.
    var adjusting by remember(state.date) { mutableStateOf<Long?>(null) }
    var deleting by remember(state.date) { mutableStateOf<Long?>(null) }
    // The strap's nap just deleted and its start as shown, while its Undo shows.
    var undo by remember { mutableStateOf<Pair<SleepSession, Long>?>(null) }
    val total = naps.sumOf { it.end - it.start }
    val color = MetricColors.sleepDeep
    val today = LocalDate.now()
    val isToday = state.date == today
    var scrub by remember(state.date) { mutableStateOf<DetailHeadline?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    val headline = if (naps.isEmpty()) SleepCopy.noNaps(isToday).let { (word, why) -> DetailHeadline.none(word, why) }
    else DetailHeadline(clockDuration(total), SleepCopy.napsLabel(isToday), null)

    // TODO(step 6): dots under the other days of the strip need the week's naps; the day loads only its own.
    UndoHost(undo?.let { SleepCopy.deleted(true, isToday, clock(it.second)) }, contentPadding, onUndo = { undo?.let { editing.restore(it.first) }; undo = null }, onGone = { undo = null }) {
        DetailTemplate(
            Score.Naps.title, color, siblings(Detail.OfScore(Score.Naps), onSwitch), onInfo = { info = true }, onBack = onBack,
            contentPadding = contentPadding, headline = headline,
            strip = DetailCopy.strip(state.date, today, if (naps.isEmpty()) emptySet() else setOf(state.date)), onDay = onSelectDay,
            ranges = listOf(Range.Day), range = Range.Day, onRange = {}, scrubbed = { scrub },
        ) {
            if (naps.isEmpty()) item {
                DetailEmpty(stringResource(if (isToday) R.string.glow_sleep_no_naps_today else R.string.glow_sleep_no_naps_that_day))
            }
            else {
                // The Naps card's picture: the day as a hairline with each nap as its stage strip at its time.
                item {
                    val span = org.openstrap.app.ui.dashboard.napDaySpan(state.date, naps, java.time.ZoneId.systemDefault())
                    val pictured = naps.map { Nap(it.start, it.end, state.napStages[it.start].orEmpty()) }
                    Column {
                        DetailPicture(height = 28.dp) { m -> NapDay(pictured, span.first, span.last, color, PictureSize.Wide, m) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Caption(clock(span.first))
                            Caption(clock(span.last))
                        }
                    }
                }
                item { DetailSentence(SleepCopy.naps(naps.size, total)) }
                naps.forEachIndexed { i, nap ->
                    item(key = nap.start) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 12.dp)
                                .topHairline(i > 0, RowHairline),
                        ) {
                            ChartTitle(Ranges.of(clock(nap.start), clock(nap.end)), Units.duration(nap.end - nap.start))
                            val detected = state.napsAsDetected[nap.start]
                            val stages = state.napStages[nap.start].orEmpty()
                            // Heart rate as in the hour before and no deep sleep: maybe sitting still, said, never removed.
                            val deep = state.napBreakdowns[nap.start]?.byStage?.get(org.openstrap.app.data.db.SleepStage.DEEP) ?: 0L
                            SleepEdits.stillHeartRate(state.heartRate.map { it.x to it.y }, nap.start, nap.end, deep)?.let {
                                Caption(SleepCopy.stillNap(it), Modifier.padding(bottom = 10.dp))
                            }
                            if (stages.isEmpty()) Caption(SleepCopy.NAP_NO_STAGES)
                            else {
                                StageStrip(stages, height = 8.dp)
                                state.napBreakdowns[nap.start]?.let { b ->
                                    val figures = listOf(
                                        org.openstrap.app.data.db.SleepStage.DEEP, org.openstrap.app.data.db.SleepStage.REM,
                                        org.openstrap.app.data.db.SleepStage.LIGHT, org.openstrap.app.data.db.SleepStage.AWAKE,
                                    ).mapNotNull { st -> b.byStage[st]?.takeIf { it > 0 }?.let { Units.duration(it) to SLEEP_NAMES[st].orEmpty() } }
                                    if (figures.isNotEmpty()) DetailFigures(figures, Modifier.padding(top = 14.dp))
                                }
                                // A nap extended past the strap's times has stages only for what the strap saw.
                                if (detected != null) SleepCopy.stagesCover(nap.start, nap.end, detected.start, detected.end)?.let {
                                    Caption(it, Modifier.padding(top = 10.dp))
                                }
                            }
                            if (detected != null) {
                                val moved = detected.start != nap.start || detected.end != nap.end
                                SleepEditActions(
                                    if (moved) SleepCopy.yourTimes(clock(detected.start), clock(detected.end)) else null,
                                    onAdjust = { adjusting = nap.start }, onDelete = { deleting = nap.start }, Modifier.padding(top = 16.dp),
                                )
                            }
                            // The strap sends no average for a nap, so its heart rate is the day's readings in its window.
                            val window = nap.start..nap.end
                            val hr = remember(state.heartRate, window) { state.heartRate.filter { it.x in window } }
                            if (hr.isNotEmpty()) {
                                Box(Modifier.padding(top = 16.dp)) {
                                    ScrubLine(
                                        hr, window, 100.dp, listOf(clock(nap.start), clock(nap.end)), MetricColors.heart,
                                        stringResource(R.string.glow_unit_bpm),
                                    ) { scrub = it }
                                }
                                Caption(SleepCopy.napHeart(hr.minOf { it.y }.toInt(), hr.map { it.y }.average().toInt()), Modifier.padding(top = 6.dp, bottom = 8.dp))
                            }
                        }
                    }
                }
            }
            item { SleepRelated(Score.Naps, state, r, onOpen) }
        }
    }

    if (info) InfoSheet(InfoCopy.of(Score.Naps), color, null, null, onDismiss = { info = false })
    // A nap gone from the day (deleted elsewhere, or moved) closes its sheet, cleared once the composition is applied
    // rather than during it (audit K6).
    adjusting?.let { start ->
        val nap = naps.firstOrNull { it.start == start }
        val detected = state.napsAsDetected[start]
        if (nap == null || detected == null) androidx.compose.runtime.SideEffect { adjusting = null }
        else SleepTimesSheet(
            nap, detected, state.heartRate, state.sleepNeighbours.filter { !it.nap || it.start != start },
            onSave = { from, to -> adjusting = null; editing.setTimes(detected, from, to) }, onDismiss = { adjusting = null },
        )
    }
    deleting?.let { start ->
        val nap = naps.firstOrNull { it.start == start }
        val detected = state.napsAsDetected[start]
        if (nap == null || detected == null) androidx.compose.runtime.SideEffect { deleting = null }
        else DeleteSleepSheet(
            SleepCopy.deleteTitle(true, isToday), Ranges.of(clock(nap.start), clock(nap.end)),
            onDelete = { deleting = null; editing.delete(detected); undo = detected to nap.start }, onDismiss = { deleting = null },
        )
    }
}

/**
 * A sleep's or nap's own times, one sheet for both, around [SleepTrim]. [others] are the day's other sleep and naps as
 * shown, which the times mustn't run into.
 */
@Composable
private fun SleepTimesSheet(
    shown: SleepSession, detected: SleepSession, heartRate: List<ChartPoint>, others: List<SleepSession>,
    onSave: (Long, Long) -> Unit, onDismiss: () -> Unit,
) {
    val now = remember { Instant.now().epochSecond }
    PageSheet(onDismiss, SleepCopy.timesTitle(detected.nap), note = SleepCopy.strapSaw(clock(detected.start), clock(detected.end))) {
        SleepTrim(shown, detected, heartRate, others, now, onSave, onDismiss)
    }
}

/**
 * The workout's trim, its one bar ([TrimBar]), for a sleep or nap: the times and length over the heart rate from two
 * hours before to two hours after what the strap detected, so the drop into sleep and the rise out of it can be found
 * and the sleep trimmed or extended, a minute at a time. The handles stop at [now], at the [others] around it, and at
 * the kind's shortest and longest ([SleepEdits.NAP_MIN_SECONDS] and the rest). Times that still can't be saved
 * ([SleepEdits.check], the last guard) say why in the note's place and keep Save off, as do times that are still the
 * ones shown. The strap's times stay one tap away.
 */
// The readings and the other sleeps arrive whole with the day and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
@Composable
internal fun SleepTrim(
    shown: SleepSession, detected: SleepSession, heartRate: List<ChartPoint>, others: List<SleepSession>, now: Long,
    onSave: (Long, Long) -> Unit, onCancel: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    // The handles outlive a rotation and a fold while the sheet stays open (audit K7), until the sleep's times change.
    var start by rememberSaveable(detected.start, shown.start, shown.end) { mutableLongStateOf(shown.start) }
    var end by rememberSaveable(detected.start, shown.start, shown.end) { mutableLongStateOf(shown.end) }
    val reach = remember(detected, shown.start, shown.end, now, others) { Trim.sleepReach(detected, shown.start, shown.end, now, others) }
    val points = remember(heartRate, reach) { bucket(heartRate.filter { it.x in reach }, reach, HR_STEP_SECONDS) }
    val problem = SleepEdits.check(detected, start, end, now, others)?.let { SleepEditCopy.text(it, zone) }
    val changed = start != shown.start || end != shown.end
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // As the workout's trim: the times and length above the bar.
        Text(SleepCopy.trimLine(detected.nap, clock(start), clock(end), end - start), color = Color.White, style = GlowType.body)
        // Sleep's heart rate sits below zone 2, so the bars are zone 1's colour.
        TrimBar(
            points, core = detected.start..detected.end, reach = reach, from = start, to = end,
            colorOf = { ZONE_COLORS[0] }, onChange = { f, t -> start = f; end = t },
            step = Trim.SLEEP_STEP_SECONDS, keep = listOf(detected.start, detected.end, shown.start, shown.end),
            length = if (detected.nap) SleepEdits.NAP_MIN_SECONDS..SleepEdits.NAP_MAX_SECONDS
            else SleepEdits.SLEEP_MIN_SECONDS..SleepEdits.SLEEP_MAX_SECONDS,
        )
        if (problem != null) PageNote(problem, color = MetricColors.warn)
        else Text(SleepCopy.TIMES_HINT, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(stringResource(R.string.glow_cancel), onCancel, Modifier.weight(1f))
            PageButton(
                stringResource(R.string.glow_save), { onSave(start, end) }, Modifier.weight(1f), enabled = problem == null && changed,
                primary = true,
            )
        }
        if (shown.start != detected.start || shown.end != detected.end) {
            PageButton(
                stringResource(R.string.glow_sleep_use_strap_times), { onSave(detected.start, detected.end) }, Modifier.fillMaxWidth(),
                icon = AppIcons.ArrowCounterClockwise,
            )
        }
    }
}

/** The sleep bar's heart rate, in the steps the page loads it in. */
private const val HR_STEP_SECONDS = 5 * 60L

/* ----------------------------------------------- Related -------------------------------------------- */

/** The rows under a sleep detail: the other two sleep details and Recovery, each with the day's value, opening it. */
@Composable
private fun SleepRelated(current: Score, state: DayUiState, r: RecoveryResult?, onOpen: (Detail) -> Unit) {
    val reg = r?.regularity
    val naps = state.naps
    val rows = buildList<@Composable (Boolean) -> Unit> {
        if (current != Score.Sleep) add { divider -> SleepScoreRow(state, divider, onOpen) }
        if (current != Score.Regularity) add { divider ->
            val sri = reg?.sri?.let { Math.round(it).toString() }
            ScoreRow(Score.Regularity, MetricColors.sleepDeep, sri, TabCopy.regularity(reg), divider, onOpen)
        }
        if (current != Score.Naps) add { divider ->
            val total = naps.takeIf { it.isNotEmpty() }?.let { n -> Units.duration(n.sumOf { it.end - it.start }) }
            val word = if (naps.isEmpty()) {
                stringResource(R.string.glow_sleep_naps_none)
            } else {
                TabCopy.naps(naps.size, clock(naps.minOf { it.start }))
            }
            ScoreRow(Score.Naps, MetricColors.sleepDeep, total, word, divider, onOpen)
        }
        add { divider -> RecoveryScoreRow(r?.readiness, divider, onOpen) }
    }
    RelatedPanel { rows.forEachIndexed { i, row -> row(i > 0) } }
}
