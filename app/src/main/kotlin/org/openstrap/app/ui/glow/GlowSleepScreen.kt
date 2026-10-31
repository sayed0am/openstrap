package org.openstrap.app.ui.glow

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
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
import org.openstrap.app.ui.components.ChartPoint
import org.openstrap.app.ui.components.StageSpan
import org.openstrap.app.ui.dashboard.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.InfoCopy
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.DayViewModel
import org.openstrap.app.ui.dashboard.LocalGoals
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.dashboard.SLEEP_NAMES
import org.openstrap.app.ui.dashboard.SleepCard
import org.openstrap.app.ui.dashboard.SleepFacts
import org.openstrap.app.ui.dashboard.SleepWindows
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.nightVitals
import org.openstrap.app.ui.dashboard.sleepCards
import org.openstrap.app.ui.dashboard.sleepWeek
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.glow.draw.StageBarLook
import org.openstrap.app.ui.glow.draw.stageTotals

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
                    SleepCard.Breathing -> when {
                        breathing != null -> if (pair) BreathingSmall(breathing, m) { breathingInfo = true } else BreathingCard(breathing) { breathingInfo = true }
                        // Waiting for the first night: the same card, a dim dash and when it fills.
                        pair -> SmallCard(MetricColors.sleepLight, "Breathing disturbances", null, null, AFTER_FIRST_NIGHT, m, waiting = true)
                        else -> EdgeCard(MetricColors.sleepLight, Modifier.fillMaxWidth(), kind = EdgeKind.Value) {
                            EdgeTitle("Breathing disturbances", "Early estimate")
                            EdgeValue(null, null, AFTER_FIRST_NIGHT)
                        }
                    }
                    SleepCard.Naps -> NapsCard(state, if (pair) m else Modifier.fillMaxWidth().height(GlowGrid.UnitHeight)) { onOpen(Detail.OfScore(Score.Naps)) }
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

private fun clock(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

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
            GlowHeader("Last night", trailing = session?.let { TabCopy.nightTimes(it.start, it.end) })
            if (session == null) {
                // Nothing said until the night has loaded, so a cold start doesn't open on "No sleep recorded".
                val deleted = state.deletedSleep
                if (state.loaded) HeroSentence(
                    if (firstNights) TabCopy.FIRST_NIGHT_SLEEP else TabCopy.noNight(state.naps.isNotEmpty(), isToday, deleted = deleted != null),
                )
                if (state.loaded && deleted != null) {
                    Spacer(Modifier.height(20.dp))
                    PageButton("Restore", { onRestore(deleted.strap) }, icon = org.openstrap.app.ui.theme.AppIcons.ArrowCounterClockwise)
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
                    StageBar(totals)
                    Spacer(Modifier.height(10.dp))
                    StageLegend(totals)
                }
            }
        }
    }
}

/**
 * The stage bar (chart kit): one 8 dp bar split by stage, deepest first, with 2 dp gaps, sized by [StageBarLook] as a
 * widget's bar is ([stageBarSegments] lays that one out in whole pixels the way this Row does).
 */
@Composable
private fun StageBar(totals: List<Pair<Int, Long>>) {
    Row(Modifier.fillMaxWidth().height(StageBarLook.HEIGHT_DP.dp), horizontalArrangement = Arrangement.spacedBy(StageBarLook.GAP_DP.dp)) {
        totals.forEach { (stage, seconds) ->
            Box(Modifier.weight(seconds.toFloat()).fillMaxHeight().background(stageColor(stage), RoundedCornerShape(StageBarLook.CORNER_DP.dp)))
        }
    }
}

/** The stage bar's legend: an 8 dp colour mark and the stage's total in Secondary, "Deep 2 h 51". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageLegend(totals: List<Pair<Int, Long>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        totals.forEach { (stage, seconds) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(stageColor(stage), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(6.dp))
                Text(
                    TabCopy.stageTotal(stage, seconds), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.secondary, maxLines = 1,
                )
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
    fun at(f: Float) = clock(start + ((end - start) * f).toLong())
    // Compact, as on Today's Last night card, the rows are too close for their names and the card's title has the times.
    val colors = SleepRowColors
    GlowHypnogram(
        stages, names, height = height, labels = if (compact) emptyList() else listOf(clock(start), clock(end)), showNames = !compact,
        rowColors = colors,
        kept = kept?.let { (it.first - start).toFloat() / (end - start)..(it.last - start).toFloat() / (end - start) },
    ) { st ->
        val mins = (((end - start) * (st.to - st.from)) / 60).toInt().coerceAtLeast(1)
        "${names[st.row]}, ${at(st.from)}–${at(st.to)}, $mins min"
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
    ListPanel(title = "Night vitals", note = "Against your nights") {
        nightVitals(state, firstNights).forEachIndexed { i, v ->
            ListRow(
                v.label, v.color, v.value, v.status,
                offNormal = v.direction != null && v.direction != 0, onClick = { onOpen(v.metric) }, divider = i > 0,
                icon = v.metric.rowIcon(),
            )
        }
    }
}

/**
 * Sleep regularity: how alike the week's sleep and wake times were, the usual times, and each night's bed and wake as
 * bars. Lit from the top like a night sky. Tapping opens its detail ([onOpen]), where a night can be picked and the
 * method is in the info sheet.
 */
@Composable
private fun RegularityCard(state: DayUiState, reg: org.openstrap.app.data.SleepRegularity.Result?, firstNights: Boolean, onOpen: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val w = sleepWeek(state, LocalGoals.current.sleepSeconds, reg, zone)
    EdgeCard(MetricColors.sleepDeep, Modifier.fillMaxWidth().gridSize(CardSize.Wide), kind = EdgeKind.Sleep, onClick = onOpen) {
        EdgeTitle("Sleep regularity", "Last 7 nights")
        EdgeValue(
            reg?.sri?.roundToInt()?.toString(), null,
            when {
                w != null -> TabCopy.regularity(reg)
                firstNights -> AFTER_FIRST_NIGHT
                else -> "No sleep recorded in the last 7 nights"
            },
            // Waiting for the first night it keeps its shape with the dim dash (spec 4.3).
            waiting = firstNights && w == null,
        )
        if (w != null) {
            w.usual?.let { u ->
                Text(
                    TabCopy.usualTimes(SleepHistory.clockMinutes(u.bedtime), SleepHistory.clockMinutes(u.wake)),
                    color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            SleepWindows(w.nights, w.usual, zone)
        }
    }
}

/**
 * Breathing disturbances: the strap's count of dips in blood oxygen per hour asleep, against your own nights. No
 * severity words: those are apnea bands, and this is a wellness estimate, marked early until checked on hardware.
 * A value against your usual, so lit from below. The app has only the night's count and your usual, no history of
 * nights, so tapping opens its info sheet ([onInfo]) rather than a detail.
 */
@Composable
private fun BreathingCard(b: org.openstrap.app.data.Recovery.BreathingDisturbances, onInfo: () -> Unit) {
    // As high as its content: a number and a line, so a unit high would leave an empty band under them (spec 2.4).
    EdgeCard(MetricColors.sleepLight, Modifier.fillMaxWidth(), kind = EdgeKind.Value, onClick = onInfo) {
        EdgeTitle("Breathing disturbances", "Early estimate")
        EdgeValue("%.1f".format(b.perHour), "an hour", TabCopy.breathing(b))
    }
}

/** Breathing disturbances as a Small beside the naps: the count an hour and your usual. Lit from below, a value. */
@Composable
private fun BreathingSmall(b: org.openstrap.app.data.Recovery.BreathingDisturbances, modifier: Modifier, onInfo: () -> Unit) =
    SmallCard(MetricColors.sleepLight, "Breathing disturbances", "%.1f".format(b.perHour), "an hour", TabCopy.breathingShort(b), modifier, onClick = onInfo)

/**
 * The day's naps as a Small (a Wide when alone): the time napped, a strip of the latest nap's stages and when it was.
 * Earlier in the day, so lit from the left. Tapping opens the naps' detail ([onOpen]), with each nap's heart rate.
 */
@Composable
private fun NapsCard(state: DayUiState, modifier: Modifier, onOpen: () -> Unit) {
    val naps = state.naps.sortedBy { it.start }
    if (naps.isEmpty()) return
    val stages = state.napStages[naps.last().start].orEmpty()
    SmallCard(
        MetricColors.sleepDeep, TabCopy.napsTitle(naps.size), clockDuration(naps.sumOf { it.end - it.start }), null,
        TabCopy.naps(naps.size, clock(naps.first().start)), modifier, kind = EdgeKind.Earlier, onClick = onOpen,
        extra = if (stages.isEmpty()) null else ({ StageStrip(stages, Modifier.padding(top = 8.dp)) }),
    )
}
