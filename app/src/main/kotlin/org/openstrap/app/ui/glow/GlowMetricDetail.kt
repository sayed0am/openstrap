package org.openstrap.app.ui.glow

import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.TimeOfDay
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.Units
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.DayStat
import org.openstrap.app.data.History
import org.openstrap.app.data.Weeks
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.ui.components.LocalCardsGrow
import org.openstrap.app.ui.components.detailNumber
import org.openstrap.app.ui.components.detailSurface
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.presentation.DetailCopy
import org.openstrap.app.ui.dashboard.InfoCopy
import org.openstrap.app.presentation.Metric
import org.openstrap.app.ui.dashboard.NIGHTLY
import org.openstrap.app.ui.dashboard.RELATED
import org.openstrap.app.presentation.Range
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.dashboard.avg
import org.openstrap.app.ui.dashboard.colorOf
import org.openstrap.app.ui.dashboard.SleepCopy
import org.openstrap.app.ui.dashboard.siblingsOf
import java.time.LocalDate
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.theme.ControlSurface

/* ------------------------------------------- Shared pieces ------------------------------------------ */

/** [date] as a heading: "Today", "Yesterday", the weekday within the week, then the date ([DayWords.relative]). */
internal fun dayName(date: LocalDate): String = DayWords.relative(date, LocalDate.now())

/* ---------------------------------------------- Template --------------------------------------------- */

/**
 * What a detail's hero says: the [value] in dots, the [label] beside it ("ms, day average", or the time or date under
 * the finger while scrubbing) and the status [word] under it in the metric's colour. Without a value the [word] is the
 * state ("Learning", "No reading") and stands large in the value's place, the [label] under it says why, never a lone
 * dash ([DetailHeadline.none]).
 */
internal data class DetailHeadline(val value: String?, val label: String, val word: String?) {
    companion object {
        /** No value: the state as a word, and what it waits for or why. */
        fun none(state: String, why: String) = DetailHeadline(null, why, state)
    }
}

/** A detail the switcher can move to: its [title], and whether it's the one showing. */
internal class DetailSibling(val title: String, val current: Boolean, val icon: androidx.compose.ui.graphics.vector.ImageVector?, val open: () -> Unit)

/** The siblings of [detail] for its switcher, each opening in its place through [onSwitch]. */
internal fun siblings(detail: Detail, onSwitch: (Detail) -> Unit): List<DetailSibling> = siblingsOf(detail).map { d ->
    val title = when (d) {
        is Detail.OfMetric -> d.metric.title
        is Detail.OfScore -> d.score.title
        else -> ""
    }
    DetailSibling(title, d == detail, d.rowIcon()) { if (d != detail) onSwitch(d) }
}


/**
 * The metric detail template (spec 6.1), one for every number. The hero, tinted with [color] and fading to black,
 * carries back, the switcher across [siblings] ([title] on it), the (i) that opens the info sheet ([onInfo]), the
 * [headline] and the seven-day [strip] ([onDay] picks a day). Under it the [ranges] as pills when there's more than
 * one, then [body]: the chart, the sentence and the rows. [heroValue] replaces the value row with a score gauge (the
 * Recovery arc, the Sleep horizon), centred with its label and status word under it; [heroSide] sits to the right of
 * a plain value row (heart rate's Live switch). While a chart is scrubbed, [scrubbed] gives the reading under the
 * finger in the headline's place, on the value row in the gauge's place. It's read only inside the hero.
 */
@Composable
internal fun DetailTemplate(
    title: String,
    color: Color,
    siblings: List<DetailSibling>,
    onInfo: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    headline: DetailHeadline,
    strip: List<DetailCopy.StripDay>,
    onDay: (LocalDate) -> Unit,
    ranges: List<Range>,
    range: Range,
    onRange: (Range) -> Unit,
    heroValue: (@Composable () -> Unit)? = null,
    heroSide: (@Composable () -> Unit)? = null,
    scrubbed: () -> DetailHeadline? = { null },
    body: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    val list = rememberLazyListState()
    var switching by rememberSaveable { mutableStateOf(false) }
    val gutter = GlowLayout.WideGutter
    // Rows inside a detail open the next one with a crossfade, not by growing (spec 3.5).
    CompositionLocalProvider(LocalGlowPaused provides { list.isScrollInProgress }, LocalCardsGrow provides false) {
        ScreenList(contentPadding, list, gutter = gutter) {
            item {
                DetailHero(color, gutter) {
                    // A scrub is read here, so only the hero follows the finger, not the whole detail (audit K4).
                    val scrub = scrubbed()
                    val shown = scrub ?: headline
                    // A scrubbed reading takes the gauge's place, as the value row.
                    val gauge = heroValue.takeIf { scrub == null }
                    DetailNav(title, siblings.size > 1, onBack, onInfo, onSwitch = { switching = true })
                    // A score gauge, its label and status word make one block, centred in the header (spec 6.1).
                    if (gauge != null) Column(
                        Modifier.fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        gauge()
                        Text(
                            shown.label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                            style = GlowType.secondary, modifier = Modifier.padding(top = 8.dp),
                        )
                        if (shown.word != null && shown.value != null) StatusWord(shown.word, color, Modifier.padding(top = 12.dp))
                    } else Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.weight(1f)) {
                            when {
                                // No number: the state in words, large in the value's place, as a Small does.
                                shown.value == null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(shown.word ?: "No reading", color = Color.White, style = GlowType.pageTitle)
                                    Text(
                                        shown.label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                                    )
                                }
                                else -> Row(verticalAlignment = Alignment.Bottom) {
                                    DotMatrixText(shown.value, Modifier.detailNumber(shown.value), dot = DotSize.Detail)
                                    Text(
                                        shown.label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                                        style = GlowType.secondary, modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
                                    )
                                }
                            }
                        }
                        heroSide?.invoke()
                    }
                    if (gauge == null && shown.word != null && shown.value != null) {
                        StatusWord(shown.word, color, Modifier.padding(top = 12.dp))
                    }
                    if (strip.isNotEmpty()) WeekStrip(strip, color, onDay, Modifier.padding(top = 20.dp))
                }
            }
            if (ranges.size > 1) item {
                GlowSegments(
                    ranges.map { it.label }, ranges.indexOf(range), { onRange(ranges[it]) }, Modifier.padding(top = 6.dp), role = Role.Tab,
                )
            }
            body()
        }
    }
    if (switching) SwitcherSheet(siblings, color, onDismiss = { switching = false })
}

/** The hero's tint: the metric's colour at the top fading to black, rounded at the foot, as wide as the screen. */
@Composable
private fun DetailHero(color: Color, gutter: Dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(bottomStart = GlowLayout.CardCorner, bottomEnd = GlowLayout.CardCorner)
    Column(
        Modifier
            // Out to the screen's edges, past the list's gutter.
            .layout { measurable, constraints ->
                val extra = gutter.roundToPx() * 2
                val w = constraints.maxWidth + extra
                val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
            }
            // The card tapped grows into this surface (spec 3.5).
            .detailSurface(hero = true, shape)
            .clip(shape)
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(lerp(Color.Black, color, 0.2f), Color.Black)))
                drawRect(Brush.radialGradient(listOf(color.copy(alpha = 0.4f), Color.Transparent), Offset(size.width / 2, -size.height * 0.1f), size.width * 0.7f))
                // The hairline along the foot; its top is above the clip, so only the foot and its corners show.
                val r = GlowLayout.CardCorner.toPx()
                drawRoundRect(
                    Color.White.copy(alpha = 0.07f), Offset(0.5f, -r), Size(size.width - 1f, size.height + r - 0.5f), CornerRadius(r),
                    style = Stroke(1.dp.toPx()),
                )
            }
            .padding(start = gutter, end = gutter, top = 4.dp, bottom = 22.dp),
    ) { content() }
}

/**
 * The detail's page header ([PageHeader], spec 3.4 and 6.1): back, the title, which opens the switcher when there's
 * something to switch to, and the (i).
 */
@Composable
private fun DetailNav(title: String, switchable: Boolean, onBack: () -> Unit, onInfo: () -> Unit, onSwitch: () -> Unit) {
    PageHeader(title, onBack, onTitle = if (switchable) onSwitch else null) { InfoButton("About $title", onInfo) }
}

/** The status word under the value, in the metric's colour with a soft glow of it. */
@Composable
private fun StatusWord(word: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        word, modifier = modifier, color = lerp(color, Color.White, 0.25f),
        style = GlowType.cardTitle.copy(shadow = Shadow(color.copy(alpha = 0.8f), blurRadius = 16f)), maxLines = 1,
    )
}

/** The seven days: each day's short name, a dot under days with data, the day shown on a soft pill. */
@Composable
private fun WeekStrip(days: List<DetailCopy.StripDay>, color: Color, onDay: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { d ->
            Column(
                Modifier.width(40.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (d.selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                    .clickable(onClickLabel = "Show ${d.label}", role = Role.Button) { onDay(d.date) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(d.label, color = if (d.selected) Color.White else Color.White.copy(alpha = 0.5f), style = GlowType.secondary, maxLines = 1)
                Box(Modifier.size(5.dp).background(if (d.hasData) color else Color.Transparent, CircleShape))
            }
        }
    }
}

/** The switcher's choices, as a sheet over the detail: the group's details, the one showing ticked. */
@Composable
private fun SwitcherSheet(siblings: List<DetailSibling>, color: Color, onDismiss: () -> Unit) {
    PageSheet(onDismiss, side = 16.dp) {
        ListPanel {
            siblings.forEachIndexed { i, s ->
                ListRow(
                    s.title, if (s.current) color else Color.White.copy(alpha = 0.3f), null, if (s.current) "Showing" else null,
                    onClick = { onDismiss(); s.open() }, divider = i > 0, dash = false, icon = s.icon,
                )
            }
        }
    }
}

/** The one time-aware sentence under the chart. */
@Composable
internal fun DetailSentence(text: String) {
    Text(text, color = Color.White, style = GlowType.sentence, modifier = Modifier.padding(top = 4.dp))
}

/**
 * A detail's second figures side by side, each in an equal share of the width: the value as text in Body, its name
 * under it in Secondary. Text, not dots, since the header's number is the detail's one dotted number (spec 2.3). A
 * figure without a value shows "No reading" in its place.
 */
@Composable
internal fun DetailFigures(figures: List<Pair<String?, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        figures.forEachIndexed { i, (value, label) ->
            val end = i == figures.lastIndex && i > 0
            Column(
                Modifier.weight(1f), horizontalAlignment = if (end) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    value ?: "No reading", color = if (value != null) Color.White else Color.White.copy(alpha = 0.3f),
                    style = GlowType.body.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                )
                Text(label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
            }
        }
    }
}

/** What a chart says while there's nothing to draw. */
@Composable
internal fun DetailEmpty(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, modifier = Modifier.padding(vertical = 24.dp))
}

/* ------------------------------------------- Metric detail ------------------------------------------ */

private val HOURS = listOf("00", "06", "12", "18", "24")

/** A day's value of [metric]: the live or latest heart rate, the strap's resting heart rate, the day's steps, else the average of its readings. */
internal fun dayValue(metric: Metric, state: DayUiState, live: Int? = null): Float? = when (metric) {
    Metric.HeartRate -> (live ?: state.latestHeartRate)?.toFloat()
    Metric.RestingHr -> state.restingHeartRate?.toFloat()
    Metric.Steps -> state.steps.toFloat().takeIf { it > 0f }
    Metric.Hrv -> state.hrv.avg()?.toFloat()
    Metric.Spo2 -> state.spo2.avg()?.toFloat()
    Metric.Stress -> state.stress.avg()?.toFloat()
    Metric.SkinTemperature -> state.skinTemperature.takeIf { it.isNotEmpty() }?.map { it.y }?.average()?.toFloat()
    Metric.Breathing -> state.breathRate.takeIf { it.isNotEmpty() }?.map { it.y }?.average()?.toFloat()
}

/**
 * A metric's detail (spec 6.1) in the [DetailTemplate]: the day's value and its readings, or a week, month or year of
 * daily values (weekly for a year) against your normal, with related metrics under them. [onSwitch] puts a sibling in
 * this one's place, [onOpen] pushes another detail, [onSelectDay] changes the day shown everywhere.
 *
 * Scoped to the [night], as the Sleep tab's night vitals open it, Day and Week come from the sleep alone ([SleepCopy]):
 * the night's average and readings while asleep, the last 7 nights, against your normal of the nights before, as the
 * Sleep tab reads them. Month and Year come from the per-day series ([SleepCopy.nightDaily]), and their label says
 * whether those are nights or whole days.
 */
@Composable
fun GlowMetricDetailScreen(
    metric: Metric,
    night: Boolean,
    state: DayUiState,
    history: DailyHistory,
    liveHeartRate: Int?,
    liveEnabled: Boolean,
    canUseLive: Boolean,
    onLiveToggle: (Boolean) -> Unit,
    onOpen: (Detail) -> Unit,
    onSwitch: (Detail) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    var rangeIndex by rememberSaveable(metric, night) { mutableIntStateOf(0) }
    val range = Range.entries[rangeIndex]
    val end = state.date
    val today = LocalDate.now()
    val strip0 = DetailCopy.strip(end, today, emptySet())
    val to = maxOf(end, strip0.last().date)
    val from = minOf(end.minusDays(maxOf(range.days, History.NORMAL_DAYS) - 1L), strip0.first().date)
    val daily = if (night) SleepCopy.nightDaily(metric) else metric.daily
    val stats by remember(daily, from, to, history) { history(daily, from, to) }.collectAsStateWithLifecycle(null)
    // Worked out once per load, not on every pass of the screen (audit K4).
    val normal = remember(stats, end) { stats?.let { History.normal(it, end) } }
    val normalDays = remember(stats, end) { stats?.count { it.date in end.minusDays(History.NORMAL_DAYS - 1L)..end } ?: 0 }
    // The night's own: its normal from the nights before, and the last 7 nights. Day and Week read against these.
    val nightNormal = remember(night, state, metric) { if (night) SleepCopy.nightNormal(state, metric) else null }
    val nightDays = remember(night, state, metric) { if (night) SleepCopy.nightNormalNights(state, metric) else 0 }
    val nightWeek = remember(night, state, metric) { if (night) SleepCopy.nightWeek(state, metric) else emptyList() }
    val fromNight = night && (range == Range.Day || range == Range.Week)
    val color = colorOf(metric)
    val nightly = if (night) range == Range.Day || range == Range.Week || SleepCopy.dailyIsNightly(metric) else metric in NIGHTLY
    val isToday = end == today
    val live = if (!night && metric == Metric.HeartRate && isToday && canUseLive && liveEnabled) liveHeartRate else null
    var scrub by remember(metric, night, range, end) { mutableStateOf<DetailHeadline?>(null) }
    var info by rememberSaveable(metric, night) { mutableStateOf(false) }

    // The range's number and the daily values its chart draws (weekly over a year).
    val start = end.minusDays(range.days - 1L)
    val summary = remember(stats, range, start, end) { stats?.takeIf { range != Range.Day }?.let { History.summary(it, start, end) } }
    val value = when {
        night && range == Range.Day -> SleepCopy.nightValue(state, metric)
        night && range == Range.Week -> nightWeek.mapNotNull { it.second }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        range == Range.Day -> dayValue(metric, state, live)
        else -> summary?.average
    }
    // Heart rate's normal is a daily average, not a reading, so the day's reading gets no word; nor does a night's.
    val status = when {
        fromNight -> History.status(value, nightNormal)
        range == Range.Year || (range == Range.Day && metric == Metric.HeartRate) -> null
        else -> History.status(value, normal)
    }
    val label = if (night) SleepCopy.nightLabel(metric, range) else DetailCopy.valueLabel(metric, range, live = live != null, today = isToday)
    val headline = when {
        value != null -> DetailHeadline(metric.format(value, Units.DOT_LOCALE), label, DetailCopy.statusWord(status))
        night && range == Range.Day -> SleepCopy.nightNoValue(state.sleep != null, isToday).let { (word, why) -> DetailHeadline.none(word, why) }
        night && range == Range.Week -> DetailHeadline.none("No reading", "In the last 7 nights")
        range != Range.Day && stats == null -> DetailHeadline.none("Loading", DetailCopy.noValue(metric, range, isToday))
        else -> DetailHeadline.none("No reading", DetailCopy.noValue(metric, range, isToday))
    }
    val withData = remember(stats, nightWeek) {
        stats.orEmpty().map { it.date }.toSet() + nightWeek.filter { it.second != null }.map { it.first }
    }

    DetailTemplate(
        metric.title, color, siblings(Detail.OfMetric(metric, night), onSwitch), onInfo = { info = true }, onBack = onBack,
        contentPadding = contentPadding, headline = headline,
        strip = DetailCopy.strip(end, today, withData), onDay = onSelectDay,
        ranges = Range.entries, range = range, onRange = { rangeIndex = it.ordinal }, scrubbed = { scrub },
        heroSide = if (!night && metric == Metric.HeartRate && isToday && canUseLive && range == Range.Day) {
            {
                PageChip("Live", liveEnabled, { onLiveToggle(!liveEnabled) }, role = Role.Switch, filled = true)
            }
        } else null,
    ) {
        if (night && range == Range.Day) {
            item { NightChart(metric, state, color) { scrub = it } }
            val sentence = when {
                value == null -> null
                metric == Metric.HeartRate -> SleepCopy.lowestHeart(state).let { low ->
                    SleepCopy.nightHeart(low?.y?.toInt(), low?.x?.let(::clock), Math.round(value), isToday)
                }
                else -> DetailCopy.day(status, org.openstrap.app.presentation.DaySource.Night, isToday, nightDays)
            }
            if (sentence != null) item { DetailSentence(sentence) }
        } else if (night && range == Range.Week) {
            val values = nightWeek.map { it.second }
            item {
                if (values.all { it == null }) DetailEmpty("No ${inSentence(metric.title)} in the last 7 nights yet.")
                else Column {
                    NormalTrend(
                        values, DetailCopy.levels(values, nightNormal), color, labels = nightWeek.map { DayWords.weekdayShort(it.first) },
                        band = nightNormal?.let { it.low..it.high }, bandLabel = nightNormal?.let { DetailCopy.bandLabel(it, metric::format) },
                        onScrub = { i ->
                            scrub = i?.let { nightWeek[it] }?.let { (d, v) ->
                                v?.let {
                                    val word = DetailCopy.statusWord(History.status(it, nightNormal))
                                    DetailHeadline(metric.format(it, Units.DOT_LOCALE), "${metric.unit}, ${DayWords.short(d)}", word)
                                }
                            }
                        },
                    )
                    // Before there's a normal the sentence under the chart already says how far along it is.
                }
            }
            val sentence = if (metric == Metric.HeartRate) SleepCopy.heartWeek(values.map { it?.let(Math::round) })
            else DetailCopy.span(Range.Week, DetailCopy.levels(values, nightNormal), nightly = true, normalKnown = nightNormal != null, days = nightDays)
            if (sentence != null) item { DetailSentence(sentence) }
        } else if (range == Range.Day) {
            item { DayChart(metric, state, color, value != null, normalOf = { m -> rememberNormal(m, end, history) }) { scrub = it } }
            val sentence = when {
                value == null -> null
                metric == Metric.HeartRate -> state.heartRate.takeIf { it.isNotEmpty() }?.let { s ->
                    DetailCopy.heartDay(s.minOf { it.y }.toInt(), s.maxOf { it.y }.toInt(), isToday)
                }
                else -> DetailCopy.day(status, org.openstrap.app.presentation.daySource(metric), isToday, normalDays)
            }
            if (sentence != null) item { DetailSentence(sentence) }
        } else {
            val inRange = stats
            item {
                when {
                    inRange == null -> DetailEmpty("Loading")
                    summary == null -> DetailEmpty("No ${inSentence(metric.title)} in this range yet.")
                    else -> HistoryChart(metric, range, inRange, normal, start, end, color, nightly) { scrub = it }
                }
            }
            val sentence = when {
                inRange == null || summary == null -> null
                range == Range.Year -> History.weekly(inRange.filter { it.date in start..end }).takeIf { it.isNotEmpty() }?.let { w ->
                    DetailCopy.year(metric.format(w.minOf { it.value }), metric.format(w.maxOf { it.value }), metric.unit)
                }
                else -> {
                    val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
                    val byDate = inRange.associate { it.date to it.value }
                    DetailCopy.span(range, DetailCopy.levels(days.map { byDate[it] }, normal), nightly, normal != null, normalDays)
                }
            }
            if (sentence != null) item { DetailSentence(sentence) }
        }
        val related = RELATED[metric].orEmpty()
        if (related.isNotEmpty() || night) item { RelatedList(related, state, history, onOpen, sleepRow = night) }
    }

    if (info) {
        // Scoped to the night, the sheet reads the night against your earlier nights, as Day and Week do.
        val sheetStatus = when {
            night -> History.status(SleepCopy.nightValue(state, metric), nightNormal)
            range == Range.Year -> null
            else -> History.status(value, normal)
        }
        InfoSheet(
            if (night) InfoCopy.ofNight(metric) else InfoCopy.of(metric), color, InfoCopy.normalBar(sheetStatus),
            // Heart rate's night has no normal of its own, so there's no line about one.
            if (night && metric == Metric.HeartRate) null
            else if (night) InfoCopy.normalLine(nightNormal, nightDays, metric::format, metric.unit, nightly = true)
            else InfoCopy.normalLine(normal, normalDays, metric::format, metric.unit, nightly),
            onDismiss = { info = false },
        )
    }
}


/**
 * The night's readings of [metric] while asleep as a line to scrub, from falling asleep to waking ([SleepCopy.nightSeries]);
 * scrubbing moves the headline.
 */
@Composable
private fun NightChart(metric: Metric, state: DayUiState, color: Color, onScrub: (DetailHeadline?) -> Unit) {
    val session = state.sleep
    val series = SleepCopy.nightSeries(state, metric)
    if (session == null || series.isEmpty()) {
        DetailEmpty(if (session == null) SleepCopy.noNight(state.date == LocalDate.now()) else "No ${inSentence(metric.title)} readings while asleep.")
        return
    }
    val window = session.start..session.end
    // Heart rate and breathing come every few minutes in sleep, the rest more sparsely.
    val slot = if (metric == Metric.HeartRate || metric == Metric.Breathing) 5 * 60L else 15 * 60L
    val values = remember(series, window) { bucket(series, window, slot) }
    Column(Modifier.padding(top = 8.dp)) {
        GlowLineChart(
            values, height = 220.dp, labels = listOf(clock(window.first), clock(window.last)), color = color, showAverage = false, format = metric::format,
            onScrub = { i ->
                onScrub(i?.let { values[it] }?.let { v -> scrubAt(metric, v, window.first + i * slot) })
            },
        )
    }
}

/** A scrubbed reading of [metric] in the headline: its value and the time it was taken, "bpm at 22:30". */
private fun scrubAt(metric: Metric, v: Float, at: Long) = DetailHeadline(metric.format(v, Units.DOT_LOCALE), "${metric.unit} at ${clock(at)}".trim(), null)

/** A held hour's steps in the headline: "steps, 09:00 to 10:00". */
private fun hourSteps(hour: Int, steps: Float): DetailHeadline {
    val (from, to) = TimeOfDay.minutes(hour * MINUTES_PER_HOUR) to TimeOfDay.minutes((hour + 1) * MINUTES_PER_HOUR)
    return DetailHeadline(Units.count(Math.round(steps), Units.DOT_LOCALE), "steps, ${Ranges.of(from, to)}", null)
}

private const val MINUTES_PER_HOUR = 60

/** A name inside a sentence: lower case, unless it's an acronym like HRV. */
internal fun inSentence(name: String): String = if (name.all { it.isUpperCase() || it.isDigit() }) name else name.lowercase()


/**
 * The selected day's own chart: steps by the hour, the week of resting heart rates, or the day's readings as a line
 * to scrub, which moves the headline ([onScrub], null on release). Heart rate's line has your resting range behind it.
 */
@Composable
private fun DayChart(metric: Metric, state: DayUiState, color: Color, hasValue: Boolean, normalOf: @Composable (Metric) -> History.Normal?, onScrub: (DetailHeadline?) -> Unit) {
    val day = state.dayStart..state.dayEnd
    val (series, window) = when (metric) {
        Metric.HeartRate -> state.heartRate to day
        Metric.Hrv -> state.hrv to day
        Metric.Spo2 -> state.spo2 to day
        Metric.Stress -> state.stress to day
        Metric.SkinTemperature -> state.skinTemperature to day
        Metric.Breathing -> state.breathRate to (state.sleep?.let { it.start..it.end } ?: day)
        else -> emptyList<ChartPoint>() to day
    }
    if (!hasValue) {
        DetailEmpty(if (metric == Metric.Breathing) "Breathing rate is measured during sleep." else "No ${inSentence(metric.title)} for this day yet.")
        return
    }
    Column(Modifier.padding(top = 8.dp)) {
        when (metric) {
            // Holding an hour puts its steps in the headline.
            Metric.Steps -> GlowBars(
                state.hourlySteps, height = 200.dp, labels = HOURS, accent = color,
                onScrub = { h -> onScrub(h?.let { hourSteps(it, state.hourlySteps[it]) }) },
            )
            Metric.RestingHr -> if (state.restingTrend.any { it.value != null }) {
                GlowTrend(state.restingTrend.map { GlowDay(it.label, it.value) }, height = 200.dp)
            }
            else -> if (series.isNotEmpty()) {
                // Readings come every minute for heart rate, sparser for the rest.
                val slot = if (metric == Metric.HeartRate) 5 * 60L else 15 * 60L
                val values = remember(series, window) { bucket(series, window, slot) }
                val labels = if (window == day) HOURS else listOf(clock(window.first), clock(window.last))
                val band = if (metric == Metric.HeartRate) normalOf(Metric.RestingHr)?.let { it.low..it.high } else null
                GlowLineChart(
                    values, height = 220.dp, labels = labels, color = color, showAverage = false, band = band, format = metric::format,
                    onScrub = { i ->
                        onScrub(i?.let { values[it] }?.let { v -> scrubAt(metric, v, window.first + i * slot) })
                    },
                )
            }
        }
    }
}

/**
 * A week, month or year of daily values (weekly over a year) as dots against your normal: the band behind, days
 * outside it lit. Scrubbing puts the day under the finger in the headline.
 */
@Composable
private fun HistoryChart(
    metric: Metric, range: Range, stats: List<DayStat>, normal: History.Normal?, start: LocalDate, end: LocalDate, color: Color,
    nightly: Boolean, onScrub: (DetailHeadline?) -> Unit,
) {
    val (values, labels, dates) = remember(stats, range, start, end) { series(range, stats, start, end) }
    val levels = remember(values, normal) { DetailCopy.levels(values, normal) }
    NormalTrend(
        values, levels, color, labels = labels,
        band = normal?.let { it.low..it.high }, bandLabel = normal?.let { DetailCopy.bandLabel(it, metric::format) },
        onScrub = { i ->
            onScrub(i?.let { values[it] }?.let { v ->
                val d = dates[i]
                val label = if (range == Range.Year) "week of ${DayWords.dayMonth(d)}" else DayWords.short(d)
                val unitAndDay = listOf(metric.unit, label).filter { it.isNotEmpty() }.joinToString(", ")
                DetailHeadline(metric.format(v, Units.DOT_LOCALE), unitAndDay, DetailCopy.statusWord(History.status(v, normal)))
            })
        },
    )
    if (normal == null) Text(
        DetailCopy.learningNormal(stats.count { it.date in end.minusDays(History.NORMAL_DAYS - 1L)..end }, nightly),
        color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary,
    )
}

/** Evenly spaced values for the chart: one a day, or one a week over a year; gaps are null. Also axis labels and each point's date. */
internal fun series(
    range: Range, stats: List<DayStat>, start: LocalDate, end: LocalDate,
): Triple<List<Float?>, List<String>, List<LocalDate>> {
    val inRange = stats.filter { it.date in start..end }
    val dates: List<LocalDate>
    val byDate: Map<LocalDate, Float>
    if (range == Range.Year) {
        // The chart's weeks and History's must start on the same day, or no week finds its average.
        val firstDay = Weeks.firstDay()
        dates = generateSequence(Weeks.start(start, firstDay)) { it.plusWeeks(1) }.takeWhile { !it.isAfter(end) }.toList()
        byDate = History.weekly(inRange, firstDay).associate { it.date to it.value }
    } else {
        dates = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        byDate = inRange.associate { it.date to it.value }
    }
    val labels = when (range) {
        Range.Week -> dates.map { DayWords.weekdayShort(it) }
        Range.Month -> listOf(0f, 1 / 3f, 2 / 3f, 1f).map { DayWords.dayMonth(dates[((dates.size - 1) * it).toInt()]) }
        else -> listOf(0f, 1 / 3f, 2 / 3f, 1f).map { DayWords.monthShort(dates[((dates.size - 1) * it).toInt()]) }
    }
    return Triple(dates.map { byDate[it] }, labels, dates)
}

/**
 * Related metrics as list rows, each with the day's value and its word against your normal, opening its detail. With
 * [sleepRow], as a night-scoped detail has, the night's Sleep score leads.
 */
@Composable
private fun RelatedList(related: List<Metric>, state: DayUiState, history: DailyHistory, onOpen: (Detail) -> Unit, sleepRow: Boolean = false) {
    ListPanel(Modifier.padding(top = 8.dp), title = "Related") {
        if (sleepRow) {
            val score = state.sleep?.score
            ListRow(
                org.openstrap.app.ui.dashboard.Score.Sleep.title, org.openstrap.app.ui.theme.MetricColors.sleepDeep, score?.toString(),
                score?.let(org.openstrap.app.presentation.TabCopy::sleepScoreWord) ?: NO_NIGHT_VALUE,
                onClick = { onOpen(Detail.OfScore(org.openstrap.app.ui.dashboard.Score.Sleep)) }, divider = false,
                icon = org.openstrap.app.ui.dashboard.Score.Sleep.rowIcon(),
            )
        }
        related.forEachIndexed { i, m ->
            val normal = rememberNormal(m, state.date, history)
            val v = dayValue(m, state)
            val status = if (m == Metric.HeartRate) null else History.status(v, normal)
            ListRow(
                m.title, colorOf(m), v?.let { m.format(it) },
                if (v == null) NO_VALUE else DetailCopy.statusWord(status) ?: m.unit.ifEmpty { null },
                offNormal = status != null && status != 0, onClick = { onOpen(Detail.OfMetric(m)) }, divider = i > 0 || sleepRow,
                icon = m.rowIcon(),
            )
        }
    }
}
