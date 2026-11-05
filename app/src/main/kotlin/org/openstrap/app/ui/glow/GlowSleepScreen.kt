package org.openstrap.app.ui.glow

import org.openstrap.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalResources
import org.openstrap.app.presentation.dotFormat
import org.openstrap.app.presentation.Ranges
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.StageSpan
import org.openstrap.app.presentation.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.InfoCopy
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.DayViewModel
import org.openstrap.app.ui.dashboard.LocalGoals
import org.openstrap.app.presentation.Metric
import org.openstrap.app.presentation.SLEEP_NAMES
import org.openstrap.app.ui.dashboard.SleepCard
import org.openstrap.app.ui.dashboard.SleepFacts
import org.openstrap.app.ui.dashboard.NewsTag
import org.openstrap.app.ui.dashboard.OffWords
import org.openstrap.app.ui.dashboard.bedWakeWeek
import org.openstrap.app.ui.dashboard.bedtimeOffStatus
import org.openstrap.app.ui.dashboard.breathingOffStatus
import org.openstrap.app.ui.dashboard.napDaySpan
import org.openstrap.app.ui.dashboard.offTag
import org.openstrap.app.ui.components.cardNumber
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.pictures.BedWakeBars
import org.openstrap.app.ui.glow.pictures.Nap
import org.openstrap.app.ui.glow.pictures.NapDay
import org.openstrap.app.ui.glow.pictures.PictureSize
import org.openstrap.app.ui.glow.pictures.WeekOfDots
import org.openstrap.app.ui.glow.pictures.bedWakeRim
import org.openstrap.app.ui.glow.pictures.weekOfDotsRim
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import org.openstrap.app.presentation.TabCopy
import org.openstrap.app.ui.dashboard.nightVitals
import org.openstrap.app.ui.dashboard.sleepCards
import org.openstrap.app.ui.dashboard.sleepWeek
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.glow.draw.StageBarLook
import org.openstrap.app.ui.glow.draw.stageTotals
import org.openstrap.app.ui.glow.draw.GlowMood
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.draw.stageColor

/*
 * The Sleep tab (spec 5.2): "how did I sleep, and is it trending right". The night glows with its stages, the
 * vitals while asleep as a list, then the week's regularity, breathing disturbances and naps as edge cards
 * ([sleepCards]). Always in the night mood. What shaped the score and the night's charts are in the Sleep score's
 * detail ([SleepScoreDetail]), which the hero opens; the vitals open scoped to the night, regularity and naps their
 * own details, and breathing disturbances its info sheet.
 */
@Composable
fun GlowSleepScreen(
    state: DayUiState,
    /** The tab's header, or a back header when the screen is opened from a card on another tab. */
    header: @Composable () -> Unit,
    contentPadding: PaddingValues,
    /** Opens a detail over the tab: the Sleep score's, a night vital's scoped to the night, regularity's or the naps'. */
    onOpen: (Detail) -> Unit,
    /** The nightly model, for regularity and breathing disturbances. */
    recovery: org.openstrap.app.data.RecoveryResult? = null,
    /** Brings back a night the user deleted, the strap's own session given: the hero's Restore. */
    onRestore: (org.openstrap.app.data.db.SleepSession) -> Unit = {},
) {
    val list = rememberLazyListState()
    val rec = recovery?.takeIf { it.date == state.date }
    val breathing = rec?.breathingDisturbances
    // Before the strap has seen a night, cards keep their shape and wait (spec 4.3): breathing disturbances too.
    val firstNights = rec != null && !rec.charge.anchored && state.sleep == null
    val slots = sleepCards(SleepFacts(breathing = breathing != null || firstNights, naps = state.naps.isNotEmpty(), hasNight = state.sleep != null))
    var breathingInfo by rememberSaveable { mutableStateOf(false) }
    // The Sleep tab is always at night (spec 2.6): calm glow, light from below only.
    CompositionLocalProvider(LocalGlowPaused provides { list.isScrollInProgress }, LocalGlowMood provides GlowMood.Night) {
        GlowScreenList(contentPadding, list) {
            full { header() }
            // With naps and a breathing summary the two are a Small pair; alone, either is a Wide.
            val pair = (breathing != null || firstNights) && state.naps.isNotEmpty()
            tabCards(slots) { card, m ->
                when (card) {
                    SleepCard.Hero -> NightHero(state, firstNights, onRestore) { onOpen(Detail.OfScore(Score.Sleep)) }
                    SleepCard.Vitals -> VitalsList(state, firstNights) { onOpen(Detail.OfMetric(it, night = true)) }
                    SleepCard.Regularity -> RegularityCard(state, rec?.regularity, firstNights) { onOpen(Detail.OfScore(Score.Regularity)) }
                    SleepCard.Breathing -> BreathingCard(
                        rec, if (pair) PictureSize.Small else PictureSize.Wide, if (pair) m else Modifier.fillMaxWidth().height(GlowGrid.UnitHeight),
                        onInfo = if (breathing != null) ({ breathingInfo = true }) else null,
                    )
                    SleepCard.Naps -> NapsCard(
                        state, if (pair) PictureSize.Small else PictureSize.Wide, if (pair) m else Modifier.fillMaxWidth().height(GlowGrid.UnitHeight),
                    ) { onOpen(Detail.OfScore(Score.Naps)) }
                }
            }
        }
    }
    if (breathingInfo && breathing != null) InfoSheet(
        InfoCopy.BREATHING_DISTURBANCES, MetricColors.sleepLight, null, InfoCopy.breathingLine(breathing, state.date == LocalDate.now()),
        onDismiss = { breathingInfo = false },
    )
}

/* ------------------------------------------------------------------------------------------------ */

/** Sleep glows midnight blue. */
internal val SleepGlow = GlowStyle.Midnight.copy(
    color = MetricColors.sleepDeep,
    coreX = 0.5f, coreY = 0.6f, coreWidth = 1.1f, coreHeight = 0.9f,
)


/**
 * The night (spec 5.2, the sleep hero): how long, against the rest of the week, then the score in dots with its word
 * and how often you woke beside it, the one dotted number on the card. The stages to scrub in their colours, then
 * their totals as one bar with a plain legend. No arc. As high as its content. Tapping opens the Sleep score's detail
 * ([onOpen]). A night the user deleted says so, with Restore ([onRestore]).
 */
@Composable
private fun NightHero(state: DayUiState, firstNights: Boolean, onRestore: (org.openstrap.app.data.db.SleepSession) -> Unit, onOpen: () -> Unit) {
    val session = state.sleep
    val isToday = state.date == LocalDate.now()
    GlowCard(SleepGlow, Modifier.fillMaxWidth().gridSize(CardSize.Wide), onClick = onOpen) {
        Column {
            GlowHeader(stringResource(R.string.glow_sleep_last_night), trailing = session?.let { TabCopy.nightTimes(it.start, it.end) })
            if (session == null) {
                // Nothing said until the night has loaded, so a cold start doesn't open on "No sleep recorded".
                val deleted = state.deletedSleep
                if (state.loaded) HeroSentence(
                    if (firstNights) TabCopy.FIRST_NIGHT_SLEEP else TabCopy.noNight(state.naps.isNotEmpty(), isToday, deleted = deleted != null),
                )
                if (state.loaded && deleted != null) {
                    Spacer(Modifier.height(20.dp))
                    PageButton(
                        stringResource(R.string.glow_sleep_restore), { onRestore(deleted.strap) },
                        icon = org.openstrap.app.ui.theme.AppIcons.ArrowCounterClockwise,
                    )
                }
                return@Column
            }
            val asleep = state.asleepSeconds ?: (session.end - session.start)
            val others = SleepHistory.weekOthers(state.nights.mapNotNull { n -> n.session?.let { n.date to it } }.toMap(), state.date)
            HeroSentence(TabCopy.night(asleep, others))
            Spacer(Modifier.height(16.dp))
            val score = session.score
            HeroNumber(
                score?.toString(),
                listOfNotNull(
                    TabCopy.sleepScoreLine(score),
                    TabCopy.woke(session.wakeCount),
                ),
            )
            if (state.sleepStages.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                GlowSleepStages(state.sleepStages, 96.dp)
            }
            state.sleepBreakdown?.let { b ->
                // Stage totals as text, never dots (spec 2.3): a stage under a minute leaves its segment and legend out.
                val totals = stageTotals(b.byStage)
                if (totals.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    // The stage bar: sized by [StageBarLook] as a widget's bar is ([stageBarSegments] lays that one out in
                    // whole pixels the way this bar's Row does), its legend the stage's total by a square mark, "Deep 2 h 51".
                    SegmentBar(
                        totals.map { (stage, seconds) -> Segment(seconds.toFloat(), stageColor(stage)) },
                        height = StageBarLook.HEIGHT_DP.dp, gap = StageBarLook.GAP_DP.dp, corner = StageBarLook.CORNER_DP.dp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Legend(
                        totals.map { (stage, seconds) -> TabCopy.stageTotal(stage, seconds) to stageColor(stage) },
                        swatch = RoundedCornerShape(2.dp), layout = LegendLayout.Flow,
                    )
                }
            }
        }
    }
}

/**
 * Stages as the glow hypnogram, rows Awake to Deep, each stage a band in its own colour (spec 2.7, every hypnogram),
 * with start and end times underneath. Shared with Today and the Sleep detail; [compact] leaves the names and times out.
 * [kept], the user's own times for a sleep they moved, dims the strap's stages outside it.
 */
@Composable
internal fun GlowSleepStages(spans: List<StageSpan>, height: Dp, compact: Boolean = false, kept: LongRange? = null) {
    val start = spans.minOf { it.start }
    val end = spans.maxOf { it.end }.coerceAtLeast(start + 1)
    val rows = DayViewModel.SLEEP_STAGE_ROWS
    val byRow = rows.entries.associate { it.value to (SLEEP_NAMES[it.key] ?: "") }
    val names = (0 until rows.size).map { byRow[it] ?: "" }
    val stages = remember(spans) { sleepHypnogramStages(spans) }
    val resources = LocalResources.current
    fun at(f: Float) = clock(start + ((end - start) * f).toLong())
    // Compact, as on Today's Last night card, the rows are too close for their names and the card's title has the times.
    val colors = SleepRowColors
    GlowHypnogram(
        stages, names, height = height, labels = if (compact) emptyList() else listOf(clock(start), clock(end)), showNames = !compact,
        rowColors = colors,
        kept = kept?.let { (it.first - start).toFloat() / (end - start)..(it.last - start).toFloat() / (end - start) },
    ) { st ->
        val mins = (((end - start) * (st.to - st.from)) / 60).toInt().coerceAtLeast(1)
        resources.getString(R.string.glow_sleep_stage_span, names[st.row], Ranges.of(at(st.from), at(st.to)), mins)
    }
}

/**
 * A night's stage [spans] as hypnogram stages: rows Awake (0) to Deep ([DayViewModel.SLEEP_STAGE_ROWS]), times as
 * fractions of the night. The Sleep tab's, Today's and the Sleep widget's hypnogram.
 */
internal fun sleepHypnogramStages(spans: List<StageSpan>): List<GlowStage> {
    if (spans.isEmpty()) return emptyList()
    val start = spans.minOf { it.start }
    val end = spans.maxOf { it.end }.coerceAtLeast(start + 1)
    val rows = DayViewModel.SLEEP_STAGE_ROWS
    return spans.map { GlowStage((it.start - start).toFloat() / (end - start), (it.end - start).toFloat() / (end - start), rows[it.stage] ?: 0) }
}

/** Each hypnogram row's stage colour, Awake at the top, Deep at the foot. */
internal val SleepRowColors: List<Color> = DayViewModel.SLEEP_STAGE_ROWS.let { rows ->
    (0 until rows.size).map { row -> stageColor(rows.entries.first { it.value == row }.key) }
}

/** The vitals while asleep against your previous nights, each opening its metric scoped to the night. */
@Composable
private fun VitalsList(state: DayUiState, firstNights: Boolean, onOpen: (Metric) -> Unit) {
    ListPanel(title = stringResource(R.string.glow_sleep_vitals_title), note = stringResource(R.string.glow_sleep_vitals_note)) {
        nightVitals(state, firstNights).forEachIndexed { i, v ->
            ListRow(
                v.label, v.color, v.value, v.status,
                tag = org.openstrap.app.ui.dashboard.bodyTag(v.metric, v.direction), onClick = { onOpen(v.metric) }, divider = i > 0,
                icon = v.metric.rowIcon(),
            )
        }
    }
}


/**
 * Sleep regularity (spec 5.2): the Sleep Regularity Index in dots, Later or Earlier when last night's bedtime was off
 * your usual (else the band's word), your usual bedtime and wake, then the week's bed and wake bars against dim bands
 * at those times. Off, the rim lights beside the nights that are off. Tapping opens its detail ([onOpen]), where a
 * night can be picked and the method is in the info sheet.
 */
@Composable
private fun RegularityCard(state: DayUiState, reg: org.openstrap.app.data.SleepRegularity.Result?, firstNights: Boolean, onOpen: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val w = sleepWeek(state, LocalGoals.current.sleepSeconds, reg, zone)
    val week = w?.let { bedWakeWeek(it, zone) }
    val tag = if (w == null) null else offTag(OffWords.Timing, bedtimeOffStatus(state, zone))
    val rim = if (tag != null && week != null) bedWakeRim(week.first, week.second, PictureSize.Wide) else null
    PictureCard(MetricColors.sleepDeep, Modifier.fillMaxWidth().gridSize(CardSize.Wide), rim = rim, onClick = onOpen) {
        EdgeTitle(stringResource(R.string.glow_sleep_regularity_title), stringResource(R.string.glow_sleep_last_7_nights))
        // Waiting for the first night it keeps its shape with the dim dash (spec 4.3).
        SleepNumber(reg?.sri?.roundToInt()?.toString(), null, tag, Modifier.padding(top = 14.dp), waiting = firstNights && w == null)
        val status = when {
            w == null -> if (firstNights) AFTER_FIRST_NIGHT else stringResource(R.string.glow_sleep_regularity_none)
            tag != null -> null
            else -> TabCopy.regularity(reg)
        }
        status?.let { SleepStatus(it, Modifier.padding(top = 6.dp)) }
        w?.usual?.let { u ->
            SleepStatus(TabCopy.usualTimes(SleepHistory.clockMinutes(u.bedtime), SleepHistory.clockMinutes(u.wake)), Modifier.padding(top = 4.dp))
        }
        if (week != null) {
            Spacer(Modifier.height(12.dp))
            BedWakeBars(week.first, week.second, MetricColors.sleepDeep, PictureSize.Wide, Modifier.fillMaxWidth().height(RegularityBarsHeight))
        }
    }
}

/** How tall regularity's bed and wake bars stand. */
private val RegularityBarsHeight = 64.dp

/**
 * Breathing disturbances (spec 5.2): the strap's dips in blood oxygen an hour asleep, a week of dots through the band
 * of your usual, the night's count in dots and "Usual 2.1, early estimate". No severity words: those are apnea bands,
 * and this is a wellness estimate, marked early until checked on hardware. Only more than usual is news, so only that
 * tags and lights the rim beside the latest dot. A Small beside the naps, else a Wide. The app keeps the count and your
 * usual, no detail, so tapping opens its info sheet ([onInfo]). Before the first night it waits with the dim dash.
 */
@Composable
private fun BreathingCard(rec: org.openstrap.app.data.RecoveryResult?, size: PictureSize, modifier: Modifier, onInfo: (() -> Unit)?) {
    val b = rec?.breathingDisturbances
    val values = rec?.disturbancesWeek?.map { it.second }.orEmpty()
    val usual = rec?.disturbancesUsual?.let { it.low..it.high }
    val tag = if (b == null) null else offTag(OffWords.MoreOnly, breathingOffStatus(rec))
    val rim = if (tag != null) weekOfDotsRim(values, usual, size) else null
    SleepPictureCard(
        MetricColors.sleepLight, stringResource(R.string.glow_sleep_breathing_title), rim, onInfo, modifier = modifier,
        value = b?.let { dotFormat("%.1f", it.perHour) }, unit = stringResource(R.string.glow_sleep_an_hour), tag = tag,
        status = if (b == null) AFTER_FIRST_NIGHT else TabCopy.breathingShort(b),
        picture = if (values.none { it != null }) null else ({ m -> WeekOfDots(values, usual, MetricColors.sleepLight, size, m) }),
    )
}

/**
 * The day's naps (spec 5.2): the day as a hairline with each nap as its stage strip at its time, the time napped in dots
 * and when ("At 14:10"). Naps have no usual: no tag, no rim. A Small beside breathing disturbances, else a Wide.
 * Tapping opens the naps' detail ([onOpen]), with each nap's heart rate.
 */
@Composable
private fun NapsCard(state: DayUiState, size: PictureSize, modifier: Modifier, onOpen: () -> Unit) {
    val naps = state.naps.sortedBy { it.start }
    if (naps.isEmpty()) return
    val span = napDaySpan(state.date, naps, ZoneId.systemDefault())
    val pictured = naps.map { Nap(it.start, it.end, state.napStages[it.start].orEmpty()) }
    SleepPictureCard(
        MetricColors.sleepDeep, TabCopy.napsTitle(naps.size), null, onOpen, modifier = modifier,
        value = clockDuration(naps.sumOf { it.end - it.start }), unit = null, tag = null,
        status = TabCopy.naps(naps.size, clock(naps.first().start)),
        picture = { m -> NapDay(pictured, span.first, span.last, MetricColors.sleepDeep, size, m) },
    )
}

/**
 * A Sleep picture card at the size its row gives it (spec 2.5, Picture card): the title, the [picture] in the ink
 * filling what's left, the number in white dots with its [tag], then the [status] in Secondary. With no [value] the
 * dim dash waits in its place.
 */
@Composable
private fun SleepPictureCard(
    ink: Color, title: String, rim: Rim?, onClick: (() -> Unit)?,
    value: String?, unit: String?, tag: NewsTag?, status: String?,
    picture: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    PictureCard(ink, modifier, rim = rim, onClick = onClick) {
        CardTitle(title)
        if (picture != null) {
            Spacer(Modifier.height(10.dp))
            picture(Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(8.dp))
        } else Spacer(Modifier.weight(1f))
        SleepNumber(value, unit, tag)
        status?.let { SleepStatus(it, Modifier.padding(top = 4.dp)) }
    }
}

/** A Sleep card's number in white dots with its unit and news tag ([NumberWithTag]); the dim dash while [waiting]. */
@Composable
private fun SleepNumber(value: String?, unit: String?, tag: NewsTag?, modifier: Modifier = Modifier, waiting: Boolean = true) {
    if (value == null && !waiting) return
    NumberWithTag(if (value == null) null else tag, modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (value != null) DotMatrixText(value, Modifier.cardNumber(value), dot = DotSize.Edge) else WaitingDash(Modifier.padding(vertical = 8.dp))
            if (unit != null && value != null) {
                Text(unit, color = Color.White.copy(alpha = 0.7f), style = GlowType.secondary, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** A Sleep card's status line, always Secondary (spec 2.2): colour never says how it's going. */
@Composable
private fun SleepStatus(text: String, modifier: Modifier = Modifier) = Text(
    text, modifier, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
    maxLines = 1, overflow = TextOverflow.Ellipsis,
)
