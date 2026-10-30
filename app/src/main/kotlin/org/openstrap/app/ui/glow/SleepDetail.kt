package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.data.DayStat
import org.openstrap.app.data.History
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.ui.alarms.AlarmClockColors
import org.openstrap.app.ui.components.ChartPoint
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.DetailCopy
import org.openstrap.app.ui.dashboard.InfoCopy
import org.openstrap.app.ui.dashboard.LocalGoals
import org.openstrap.app.ui.dashboard.Narrative
import org.openstrap.app.ui.dashboard.Range
import org.openstrap.app.ui.dashboard.SLEEP_NAMES
import org.openstrap.app.ui.dashboard.SLEEP_NIGHTS
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.SleepCopy
import org.openstrap.app.ui.dashboard.SleepWindows
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.sleepWeek
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * The Sleep tab's details (spec 5.2 and 6.1) in the metric template: the Sleep score with the night it came from,
 * Sleep regularity over the week, and the day's naps. They switch among themselves. Everything the Sleep tab let go
 * of in step 5 is here: what shaped the score, the night's heart rate and breathing, and each nap's heart rate.
 */

private fun clock(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun shortDay(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

/** The days of the strip with a main sleep: the nights the day loads, and later days with breathing rate (measured in sleep). */
@Composable
private fun sleepDays(state: DayUiState, history: DailyHistory): Set<LocalDate> {
    val today = LocalDate.now()
    return state.nights.filter { it.session != null }.map { it.date }.toSet() + daysWithData(DailyMetric.Breathing, state.date, today, history)
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
        if (scrubbed != null) Text("${scrubbed.value} ${scrubbed.label}", color = Color.White, style = GlowType.secondary, maxLines = 1)
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
) {
    val session = state.sleep
    val score = session?.score
    val color = MetricColors.sleepDeep
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

    val headline = scrub ?: when {
        range == Range.Week && known.isNotEmpty() -> DetailHeadline(Math.round(known.average()).toString(), "Average of the last 7 nights", null)
        range == Range.Week -> DetailHeadline.none("No scores", "In the last 7 nights")
        score != null -> DetailHeadline(score.toString(), SleepCopy.scoreLabel(isToday), TabCopy.sleepScoreWord(score))
        else -> SleepCopy.noScore(session != null, isToday).let { (word, why) -> DetailHeadline.none(word, why) }
    }

    DetailTemplate(
        Score.Sleep.title, color, siblings(Detail.OfScore(Score.Sleep), onSwitch), onInfo = { info = true }, onBack = onBack,
        contentPadding = contentPadding, headline = headline,
        strip = DetailCopy.strip(state.date, today, sleepDays(state, history)), onDay = onSelectDay,
        ranges = ranges, range = range, onRange = { rangeIndex = ranges.indexOf(it) },
        // The horizon arc (chart kit, spec 2.7) holds the night's score, centred, the number under its crest.
        heroValue = if (range == Range.Day && score != null && scrub == null) {
            { HorizonArc(score / 100f, color, key = state.date) { DetailScoreNumber(score) } }
        } else null,
    ) {
        if (range == Range.Day) {
            if (session == null) {
                item { DetailEmpty(TabCopy.noNight(state.naps.isNotEmpty(), isToday)) }
            } else {
                val asleep = state.asleepSeconds ?: (session.end - session.start)
                item {
                    Column(Modifier.padding(top = 8.dp)) {
                        if (state.sleepStages.isNotEmpty()) GlowSleepStages(state.sleepStages, 150.dp)
                        state.sleepBreakdown?.let { StageBars(it, Modifier.padding(top = 18.dp)) }
                        DetailFigures(
                            listOf(clock(session.start) to SleepCopy.FELL_ASLEEP, clock(session.end) to SleepCopy.WOKE, clockDuration(asleep) to SleepCopy.ASLEEP),
                            Modifier.padding(top = 20.dp),
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
                    ListPanel(Modifier.padding(top = 8.dp), title = "What shaped the score") {
                        factors.forEachIndexed { i, f ->
                            ContributorRow(f.name, color, f.word, f.fraction, offNormal = f.off, divider = i > 0, detail = f.detail, onClick = { info = true })
                        }
                    }
                }
                item { NightLines(state, session.start..session.end) { scrub = it } }
            }
        } else {
            item {
                if (known.isEmpty()) DetailEmpty("No sleep scores in the last 7 nights yet.")
                else Column {
                    val values = week.map { it.second }
                    NormalTrend(
                        values, DetailCopy.levels(values, normal), color, labels = week.map { shortDay(it.first) },
                        band = normal?.let { it.low..it.high }, bandLabel = normal?.let { DetailCopy.bandLabel(it) { v -> Math.round(v).toString() } },
                        onScrub = { i ->
                            scrub = i?.let { week[it] }?.let { (d, v) -> v?.let { DetailHeadline(Math.round(it).toString(), d.format(DAY), TabCopy.sleepScoreWord(Math.round(it))) } }
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

    if (info) InfoSheet(
        InfoCopy.of(Score.Sleep), color, score?.let(InfoCopy::sleepBar), score?.let { InfoCopy.HERE },
        onDismiss = { info = false }, notes = InfoCopy.sleepNotes(r?.sleepNeed ?: Recovery.SLEEP_NEED_MINUTES),
    )
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
                val shape = RoundedCornerShape(50)
                Box(Modifier.weight(1f).height(10.dp).background(Color.White.copy(alpha = 0.06f), shape)) {
                    Box(Modifier.fillMaxWidth((secs.toFloat() / total).coerceIn(0f, 1f)).height(10.dp).background(stageColor(st), shape))
                }
                Text(
                    TodayCopy.duration(secs), color = Color.White, style = GlowType.secondary,
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
    val hr = state.sleepHeartRate.filter { it.x in window }
    val breath = state.breathRate.filter { it.x in window }
    if (hr.isEmpty() && breath.isEmpty()) return
    val labels = listOf(clock(window.first), clock(window.last))
    var hrAt by remember(window) { mutableStateOf<DetailHeadline?>(null) }
    var breathAt by remember(window) { mutableStateOf<DetailHeadline?>(null) }
    Column {
        if (hr.isNotEmpty()) {
            ChartTitle("Heart rate", "Lowest ${hr.minOf { it.y }.toInt()}, average ${hr.map { it.y }.average().toInt()} bpm", scrubbed = hrAt)
            ScrubLine(hr, window, 130.dp, labels, MetricColors.heart, "bpm") { hrAt = it; onScrub(it) }
        }
        if (breath.isNotEmpty()) {
            ChartTitle("Breathing rate", "Average %.1f br/min".format(breath.map { it.y }.average()), scrubbed = breathAt)
            ScrubLine(breath, window, 100.dp, labels, MetricColors.sleepLight, "br/min", decimals = 1) { breathAt = it; onScrub(it) }
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
    val values = remember(points, window) { evenly(points, window, step) }
    GlowLineChart(
        values, height = height, labels = labels, color = color, showAverage = false,
        onScrub = { i -> onScrub(i?.let { values[it] }?.let { v -> DetailHeadline("%.${decimals}f".format(v), "$unit at ${clock(window.first + i * step)}", null) }) },
    )
}

/** Averages timed points into evenly spaced buckets across [range]; empty buckets are gaps. */
private fun evenly(points: List<ChartPoint>, range: LongRange, seconds: Long): List<Float?> {
    val n = ((range.last - range.first + 1) / seconds).toInt().coerceAtLeast(2)
    val sums = FloatArray(n)
    val counts = IntArray(n)
    points.forEach {
        val i = ((it.x - range.first) / seconds).toInt()
        if (i in 0 until n) { sums[i] += it.y; counts[i]++ }
    }
    return List(n) { if (counts[it] == 0) null else sums[it] / counts[it] }
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
        w == null -> DetailHeadline.none("No sleep", "In the last 7 nights")
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
            if (w == null) DetailEmpty("No sleep recorded in the last 7 nights.")
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
                        minutesClock(u.bedtime) to "Usually asleep by",
                        minutesClock(u.wake) to "Usually up at",
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

private fun minutesClock(minutes: Int): String = org.openstrap.app.ui.dashboard.minutesClock(minutes)

/* ------------------------------------------------- Naps --------------------------------------------- */

/**
 * The day's naps: how long in all, then each nap with its time, its stage strip and stage totals, and its heart rate
 * line, the scrub moving the headline. With none it says so.
 */
@Composable
internal fun NapsDetail(
    state: DayUiState, r: RecoveryResult?,
    onOpen: (Detail) -> Unit, onSwitch: (Detail) -> Unit, onSelectDay: (LocalDate) -> Unit, contentPadding: PaddingValues, onBack: () -> Unit,
    onSetNapTimes: (SleepSession, Long, Long) -> Unit,
) {
    val naps = state.naps.sortedBy { it.start }
    // The nap whose times are being adjusted, by its start as shown.
    var adjusting by remember(state.date) { mutableStateOf<Long?>(null) }
    val total = naps.sumOf { it.end - it.start }
    val color = MetricColors.sleepDeep
    val today = LocalDate.now()
    val isToday = state.date == today
    var scrub by remember(state.date) { mutableStateOf<DetailHeadline?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    val headline = scrub ?: if (naps.isEmpty()) SleepCopy.noNaps(isToday).let { (word, why) -> DetailHeadline.none(word, why) }
    else DetailHeadline(clockDuration(total), SleepCopy.napsLabel(isToday), null)

    // TODO(step 6): dots under the other days of the strip need the week's naps; the day loads only its own.
    DetailTemplate(
        Score.Naps.title, color, siblings(Detail.OfScore(Score.Naps), onSwitch), onInfo = { info = true }, onBack = onBack,
        contentPadding = contentPadding, headline = headline,
        strip = DetailCopy.strip(state.date, today, if (naps.isEmpty()) emptySet() else setOf(state.date)), onDay = onSelectDay,
        ranges = listOf(Range.Day), range = Range.Day, onRange = {},
    ) {
        if (naps.isEmpty()) item { DetailEmpty(if (isToday) "No naps recorded today." else "No naps recorded that day.") }
        else {
            item { DetailSentence(SleepCopy.naps(naps.size, total)) }
            naps.forEachIndexed { i, nap ->
                item(key = nap.start) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 12.dp)
                            .let { if (i > 0) it.drawBehind { drawLine(RowHairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else it },
                    ) {
                        ChartTitle("${clock(nap.start)}–${clock(nap.end)}", TodayCopy.duration(nap.end - nap.start))
                        val detected = state.napsAsDetected[nap.start]
                        if (detected != null) {
                            if (detected.start != nap.start || detected.end != nap.end) {
                                Caption("Your times. The strap saw ${clock(detected.start)}–${clock(detected.end)}.")
                            }
                            PageButton("Adjust times", { adjusting = nap.start }, Modifier.padding(top = 4.dp, bottom = 14.dp), icon = AppIcons.PencilEdit)
                        }
                        val stages = state.napStages[nap.start].orEmpty()
                        if (stages.isEmpty()) Caption(SleepCopy.NAP_NO_STAGES)
                        else {
                            StageStrip(stages, height = 8.dp)
                            state.napBreakdowns[nap.start]?.let { b ->
                                val figures = listOf(
                                    org.openstrap.app.data.db.SleepStage.DEEP, org.openstrap.app.data.db.SleepStage.REM,
                                    org.openstrap.app.data.db.SleepStage.LIGHT, org.openstrap.app.data.db.SleepStage.AWAKE,
                                ).mapNotNull { st -> b.byStage[st]?.takeIf { it > 0 }?.let { clockDuration(it) to SLEEP_NAMES[st].orEmpty() } }
                                if (figures.isNotEmpty()) DetailFigures(figures, Modifier.padding(top = 14.dp))
                            }
                        }
                        // The strap sends no average for a nap, so its heart rate is the day's readings in its window.
                        val window = nap.start..nap.end
                        val hr = state.heartRate.filter { it.x in window }
                        if (hr.isNotEmpty()) {
                            Box(Modifier.padding(top = 16.dp)) {
                                ScrubLine(hr, window, 100.dp, listOf(clock(nap.start), clock(nap.end)), MetricColors.heart, "bpm") { scrub = it }
                            }
                            Caption(SleepCopy.napHeart(hr.minOf { it.y }.toInt(), hr.map { it.y }.average().toInt()), Modifier.padding(top = 6.dp, bottom = 8.dp))
                        }
                    }
                }
            }
        }
        item { SleepRelated(Score.Naps, state, r, onOpen) }
    }

    if (info) InfoSheet(InfoCopy.of(Score.Naps), color, null, null, onDismiss = { info = false })
    adjusting?.let { start ->
        val nap = naps.firstOrNull { it.start == start }
        val detected = state.napsAsDetected[start]
        if (nap == null || detected == null) adjusting = null
        else NapTimesSheet(nap, detected, state.heartRate, onSave = { from, to -> adjusting = null; onSetNapTimes(detected, from, to) }, onDismiss = { adjusting = null })
    }
}

/**
 * A nap's own times: Fell asleep and Woke up on the alarms' clock, with the heart rate from an hour before to an
 * hour after the strap's nap, so the drop into sleep and the rise out of it can be found by scrubbing. The strap's
 * times stay one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NapTimesSheet(nap: SleepSession, detected: SleepSession, heartRate: List<ChartPoint>, onSave: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    fun local(t: Long) = Instant.ofEpochSecond(t).atZone(zone)
    val startClock = rememberTimePickerState(local(nap.start).hour, local(nap.start).minute, is24Hour = true)
    val endClock = rememberTimePickerState(local(nap.end).hour, local(nap.end).minute, is24Hour = true)
    var pickingEnd by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<DetailHeadline?>(null) }
    // A clock left on the minute it opened at keeps the time to the second, so the strap's own times stay its own.
    fun picked(c: androidx.compose.material3.TimePickerState, was: Long, near: Long): Long =
        if (c.hour == local(was).hour && c.minute == local(was).minute) was else SleepEdits.atNear(c.hour * 60 + c.minute, near, zone)
    val start = picked(startClock, nap.start, detected.start)
    val end = picked(endClock, nap.end, detected.end)
    val problem = SleepEdits.problem(detected, start, end, Instant.now().epochSecond)
    val window = (detected.start - 3600)..(detected.end + 3600)
    val hr = heartRate.filter { it.x in window }

    PageSheet(onDismiss, "Nap times", note = "The strap saw ${clock(detected.start)}–${clock(detected.end)}.") {
        if (hr.isNotEmpty()) Column {
            Text(
                scrub?.let { "${it.value} ${it.label}" } ?: "Heart rate around the nap. It drops as you fall asleep.",
                color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1,
            )
            Box(Modifier.padding(top = 8.dp)) {
                ScrubLine(hr, window, 90.dp, listOf(clock(window.first), clock(window.last)), MetricColors.heart, "bpm") { scrub = it }
            }
        }
        GlowSegments(listOf("Fell asleep ${clock(start)}", "Woke up ${clock(end)}"), if (pickingEnd) 1 else 0, { pickingEnd = it == 1 })
        TimePicker(if (pickingEnd) endClock else startClock, Modifier.align(Alignment.CenterHorizontally), colors = AlarmClockColors())
        if (problem != null) PageNote(problem, color = MetricColors.warn)
        else Text("A nap of ${TodayCopy.duration(end - start)}.", color = Color.White, style = GlowType.body)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton("Cancel", onDismiss, Modifier.weight(1f))
            PageButton("Save", { onSave(start, end) }, Modifier.weight(1f), enabled = problem == null, primary = true)
        }
        if (nap.start != detected.start || nap.end != detected.end) {
            PageTextButton("Use the strap's times", { onSave(detected.start, detected.end) }, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/* ----------------------------------------------- Related -------------------------------------------- */

/** The rows under a sleep detail: the other two sleep details and Recovery, each with the day's value, opening it. */
@Composable
private fun SleepRelated(current: Score, state: DayUiState, r: RecoveryResult?, onOpen: (Detail) -> Unit) {
    val score = state.sleep?.score
    val reg = r?.regularity
    val naps = state.naps
    val ready = r?.readiness
    val rows = buildList<@Composable (Boolean) -> Unit> {
        if (current != Score.Sleep) add { divider ->
            ListRow(
                Score.Sleep.title, MetricColors.sleepDeep, score?.toString(), score?.let(TabCopy::sleepScoreWord) ?: "Nothing for this night",
                onClick = { onOpen(Detail.OfScore(Score.Sleep)) }, divider = divider, icon = Score.Sleep.rowIcon(),
            )
        }
        if (current != Score.Regularity) add { divider ->
            ListRow(
                Score.Regularity.title, MetricColors.sleepDeep, reg?.sri?.let { Math.round(it).toString() }, TabCopy.regularity(reg),
                onClick = { onOpen(Detail.OfScore(Score.Regularity)) }, divider = divider, icon = Score.Regularity.rowIcon(),
            )
        }
        if (current != Score.Naps) add { divider ->
            ListRow(
                Score.Naps.title, MetricColors.sleepDeep, naps.takeIf { it.isNotEmpty() }?.let { n -> clockDuration(n.sumOf { it.end - it.start }) },
                if (naps.isEmpty()) "None that day" else TabCopy.naps(naps.size, clock(naps.minOf { it.start })),
                onClick = { onOpen(Detail.OfScore(Score.Naps)) }, divider = divider, icon = Score.Naps.rowIcon(),
            )
        }
        add { divider ->
            ListRow(
                Score.Recovery.title, MetricColors.readiness, ready?.score?.toString(),
                ready?.score?.let { TodayCopy.recoveryWord(it, ready.strainAlert) } ?: "Nothing for this day",
                onClick = { onOpen(Detail.OfScore(Score.Recovery)) }, divider = divider, icon = Score.Recovery.rowIcon(),
            )
        }
    }
    ListPanel(Modifier.padding(top = 8.dp), title = "Related") { rows.forEachIndexed { i, row -> row(i > 0) } }
}
