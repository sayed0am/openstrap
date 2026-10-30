package org.openstrap.app.ui.glow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.Healthspan
import org.openstrap.app.data.History
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.ui.dashboard.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.ui.dashboard.FIRST_SCORE
import org.openstrap.app.ui.dashboard.PARTIAL_DAY
import org.openstrap.app.ui.dashboard.Wear
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.InfoCopy
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.dashboard.FAILED_SHORT
import org.openstrap.app.ui.dashboard.RECOVERY_FAILED
import org.openstrap.app.ui.dashboard.RecoveryFailure
import org.openstrap.app.ui.dashboard.TRY_AGAIN
import org.openstrap.app.ui.dashboard.RecoveryCard
import org.openstrap.app.ui.dashboard.RecoveryFacts
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.avg
import org.openstrap.app.ui.dashboard.recoveryCards
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.R
import org.openstrap.app.tr

/*
 * The Recovery tab (spec 5.1): "can I push today, and why". The score glows with the last 7 days beside it, what
 * drove it as contributor bars, then Charge as a Tall battery with Recharged and Drained as Smalls, the body signals
 * and Habit Age ([recoveryCards]). How the score is
 * worked out, the strap's own score and why it might be off are in the Recovery info sheet, behind the hero's (i).
 * The hero, the contributors and the Charge battery open their details on this tab's stack.
 */

/** Charge as a whole number the way every screen shows it, cut rather than rounded, so they all agree. */
fun chargeShown(value: Float): Int = value.toInt()

@Composable
fun GlowRecoveryScreen(
    state: DayUiState,
    recovery: RecoveryResult?,
    history: DailyHistory,
    /** The tab's header, or a back header when the screen is opened from a card on another tab. */
    header: @Composable () -> Unit,
    contentPadding: PaddingValues,
    onOpenMetric: (Metric) -> Unit,
    /** Opens Recovery's or Charge's detail. */
    onOpenScore: (Score) -> Unit = {},
    /** The strap's own Recovery for the day, for the info sheet. */
    strapScore: Int? = null,
    /** The weekly Habit Age estimate, or why it's withheld; null leaves the card out. */
    healthspan: Healthspan.Outcome? = null,
    onOpenHealthspan: () -> Unit = {},
    /** Opens the profile, for Habit Age's reminder to add a birth year or sex. */
    onOpenProfile: () -> Unit = {},
    /** Scroll to the Charge card once (the All metrics Charge row); [onChargeShown] when it's there. */
    showCharge: Boolean = false,
    onChargeShown: () -> Unit = {},
    /** The day's Recovery failed to work out; null when it didn't. */
    recoveryFailure: RecoveryFailure? = null,
    /** Works Recovery out again after a failure. */
    onRetryRecovery: () -> Unit = {},
    /** How much of the day the strap was worn, for the Partial day tag on Charge ([org.openstrap.app.ui.dashboard.wear]). */
    wear: Wear? = null,
    /** The day's score is the first real one, marked once (E4). */
    firstScore: Boolean = false,
) {
    val list = rememberLazyListState()
    val signals = rememberSignals(state, history)
    val r = recovery?.takeIf { it.date == state.date }
    val failure = recoveryFailure?.takeIf { it.date == state.date && r == null }
    val learning = r?.readiness?.calibrating == true
    val firstNights = r != null && !r.charge.anchored
    val waiting = if (firstNights) AFTER_FIRST_NIGHT else tr(R.string.common_no_reading_yet)
    val slots = recoveryCards(RecoveryFacts(habitAge = healthspan != null, learning = learning, hasRecovery = r != null))
    var info by rememberSaveable { mutableStateOf(false) }
    var partialInfo by rememberSaveable { mutableStateOf(false) }
    val partial = wear?.takeIf { it.partial }?.let { TitleTag(PARTIAL_DAY) { partialInfo = true } }
    val isToday = state.date == java.time.LocalDate.now()
    CompositionLocalProvider(LocalGlowPaused provides { list.isScrollInProgress }) {
        GlowScreenList(contentPadding, list, focus = showCharge, onFocused = onChargeShown) {
            full { header() }
            tabCards(slots, focus = RecoveryCard.Charge) { card, m ->
                when (card) {
                    RecoveryCard.Hero ->
                        if (failure != null) RecoveryFailedHero(failure, onOpen = { onOpenScore(Score.Recovery) }, onRetry = onRetryRecovery)
                        else RecoveryHero(
                            r, onOpen = { onOpenScore(Score.Recovery) }, onInfo = { info = true }, firstNights = firstNights,
                            noSleep = isToday && state.loaded && state.asleepSeconds == null, firstScore = firstScore,
                            shown = TodayCopy.recoveryShown(r?.readiness, strapScore, waiting),
                        )
                    RecoveryCard.Drivers -> DriversList(r?.readiness, onAll = { onOpenScore(Score.Recovery) })
                    // Charge is worked out with Recovery, so a failure there says so here too, not "after your first night".
                    RecoveryCard.Charge -> ChargeTall(
                        r?.charge, state.dayStart..state.dayEnd, m, failed = failure != null, tag = partial, onOpen = { onOpenScore(Score.Charge) },
                    )
                    RecoveryCard.Recharged -> ChargeSmall(r?.charge, recharged = true, m, failed = failure != null)
                    RecoveryCard.Drained -> ChargeSmall(r?.charge, recharged = false, m, failed = failure != null)
                    RecoveryCard.BodySignals -> SignalsList(signals, waiting, onOpenMetric)
                    RecoveryCard.HabitAge -> HabitAgeEdgeCard(healthspan, onOpenHealthspan, onOpenProfile)
                }
            }
        }
    }
    if (info) {
        val score = r?.readiness?.score
        InfoSheet(
            InfoCopy.of(Score.Recovery), MetricColors.readiness, score?.let(InfoCopy::recoveryBar),
            score?.let { InfoCopy.HERE }, onDismiss = { info = false }, notes = InfoCopy.recoveryNotes(r?.readiness, strapScore),
        )
    }
    if (partialInfo && wear != null) PartialDaySheet(wear, isToday, onDismiss = { partialInfo = false })
}

/* ----------------------------------------------- Hero ----------------------------------------------- */

/** The score sits in a dark pool, light all round it (spec 2.5): the core behind the number, low on the left. */
private fun recoveryHeroStyle(color: Color) = GlowStyle.Bloom.copy(
    color = color,
    coreShape = CoreShape.Radial, coreX = 0.3f, coreY = 0.72f, coreWidth = 0.75f, coreHeight = 0.6f,
    coreBlur = 30.dp, toneWidth = 16.dp, toneShift = -10f, glowStrength = 1f, bevel = 0.9f,
)

/**
 * The score: what it means for the day and what held it back, then the number and its word. Compared with yesterday
 * in the title, and the calibration caveat under the sentence while it learns. As high as its content. Before the
 * first night, or with no sleep last night, the sentence says so; the first real score is tagged once (E4). While
 * OpenStrap's own score is withheld the strap's stands in, labelled as its estimate, as on Today ([shown]).
 */
@Composable
private fun RecoveryHero(
    r: RecoveryResult?, onOpen: () -> Unit, onInfo: () -> Unit, firstNights: Boolean = false, noSleep: Boolean = false, firstScore: Boolean = false,
    shown: TodayCopy.RecoveryShown? = null,
) {
    val ready = r?.readiness
    val score = ready?.score
    val yesterday = r?.readinessTrend?.dropLast(1)?.lastOrNull()?.second
    val alert = ready?.strainAlert == true
    GlowCard(recoveryHeroStyle(MetricColors.readiness), Modifier.fillMaxWidth().gridSize(CardSize.Wide), onClick = onOpen) {
        Column {
            GlowHeader(
                tr(R.string.common_recovery), trailing = score?.let { TabCopy.vsYesterday(it, yesterday) },
                tag = if (firstScore && score != null) TitleTag(FIRST_SCORE) else null,
                trailingContent = {
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    InfoButton(tr(R.string.glow_recovery_screen_about_recovery), onInfo)
                },
            )
            HeroSentence(
                when {
                    firstNights -> TodayCopy.RECOVERY_FIRST_NIGHT
                    ready == null -> TabCopy.RECOVERY_WAITING
                    score != null -> TabCopy.recovery(score, alert, ready.contributors)
                    noSleep -> TabCopy.NO_SLEEP_SCORE
                    ready.phase == Recovery.Phase.Withheld -> TodayCopy.noScoreLine(TodayCopy.NoScore.Learning(ready.baselineNights, Recovery.SCORED_NIGHTS))
                    ready.withheld -> TodayCopy.noScoreLine(TodayCopy.NoScore.NoHeartData)
                    else -> tr(R.string.common_recovery_is_scored_each)
                },
            )
            // The strap's estimate stands in for a withheld score, under its own word.
            val figure = score ?: shown?.value?.takeIf { shown.estimate }
            if (figure != null) {
                // Before the first score the sentence already says how many nights are in, so the caveat waits for a score.
                if (score != null) ready.let { TabCopy.learning(it.phase, it.baselineNights) }?.let { CaveatLine(it, MetricColors.readiness) }
                Spacer(Modifier.height(22.dp))
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                    HeroFigure(figure.toString(), if (score != null) TodayCopy.recoveryWord(score, alert) else shown?.word.orEmpty(), big = true)
                    // Trend dots (chart kit): the last 7 days beside the score, today the only full dot. TODO(step 6): your
                    // normal as their band ([TrendDots] draws one) needs a normal of the score, and the app keeps 7 days of it.
                    val week = r?.readinessTrend.orEmpty().map { it.second }
                    if (score != null && week.count { it != null } >= 2) {
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            TrendDots(week)
                            Text(TabCopy.LAST_7_DAYS, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Today's score failed to work out (spec 4.3, E5): yesterday's stays, dimmed, with the line and Try again, so the
 * hero never waits forever.
 */
@Composable
private fun RecoveryFailedHero(failure: RecoveryFailure, onOpen: () -> Unit, onRetry: () -> Unit) {
    GlowCard(recoveryHeroStyle(MetricColors.readiness), Modifier.fillMaxWidth().gridSize(CardSize.Wide), onClick = onOpen) {
        Column {
            GlowHeader(tr(R.string.common_recovery))
            HeroSentence(RECOVERY_FAILED)
            failure.yesterday?.let { y ->
                Spacer(Modifier.height(22.dp))
                HeroFigure(y.toString(), tr(R.string.common_yesterday), big = true, modifier = Modifier.alpha(FAILED_ALPHA))
            }
            Spacer(Modifier.height(22.dp))
            HeroButton(TRY_AGAIN, onRetry)
        }
    }
}

/** What drove the score: the three that weigh most, each with its bar and word. */
@Composable
private fun DriversList(ready: Recovery.Readiness?, onAll: () -> Unit) {
    val all = ready?.contributors.orEmpty()
    val top = TabCopy.topDrivers(all)
    // "All" and each row open the Recovery detail, which has every contributor.
    ListPanel(title = tr(R.string.common_what_drove_it), note = if (all.size > top.size) tr(R.string.glow_recovery_screen_all, all.size) else null, onNote = onAll.takeIf { all.isNotEmpty() }) {
        if (top.isEmpty()) ListRow(tr(R.string.glow_recovery_screen_waiting_for_a_night), MetricColors.readiness, null, tr(R.string.glow_recovery_screen_what_drove_recovery_shows), dash = false, divider = false)
        top.forEachIndexed { i, c ->
            ContributorRow(
                TabCopy.factorName(c.factor), MetricColors.readiness, TabCopy.contributorWord(c.score),
                c.score / 100f, offNormal = TabCopy.contributorOff(c.score), divider = i > 0, onClick = onAll,
            )
        }
    }
}

/* ---------------------------------------------- Charge ---------------------------------------------- */

/**
 * Charge as a Tall battery (spec 2.3): the light rises to its level from below, the day's Charge curve at the top,
 * and the number now with Charge at waking at the foot.
 */
@Composable
private fun ChargeTall(
    c: Recovery.ChargeDay?, day: LongRange, modifier: Modifier, failed: Boolean = false, tag: TitleTag? = null, onOpen: () -> Unit,
) {
    // Before the first night the number would only be the simulation's starting guess: a dash until then.
    val now = c?.takeIf { it.anchored }?.latest?.let(::chargeShown)
    val atWake = c?.atWake?.toInt()
    val step = 15 * 60L
    val values = remember(c?.points, day) { c?.points?.let { bucket(it, day, step) }.orEmpty() }
    var picked by remember(values) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val scrubbed = picked?.let { i -> values.getOrNull(i)?.let { it.toInt() to clockAt(day.first + i * step) } }
    GlowCard(GlowStyle.Ember.copy(lightLevel = (now ?: 0) / 100f, second = true), modifier, onClick = onOpen) {
        Column(Modifier.fillMaxHeight()) {
            GlowHeader(tr(R.string.common_energy), tag = tag)
            if (c != null && now != null && c.points.size >= 2) {
                Spacer(Modifier.height(10.dp))
                // The Charge curve (chart kit): sleep fills it, the day drains it. Scrubbing moves the number.
                GlowLineChart(values, height = 64.dp, showAverage = false, yRange = 0f..100f, onScrub = { picked = it })
            }
            Spacer(Modifier.weight(1f))
            HeroFigure(
                scrubbed?.first?.toString() ?: now?.toString(),
                when {
                    scrubbed != null -> tr(R.string.glow_recovery_screen_at, scrubbed.second)
                    failed -> FAILED_SHORT
                    c == null || !c.anchored -> AFTER_FIRST_NIGHT
                    atWake == null -> tr(R.string.common_now)
                    c.strapOffOvernight -> tr(R.string.glow_recovery_screen_at_start, atWake)
                    else -> tr(R.string.glow_recovery_screen_at_waking, atWake)
                },
                big = true, dot = DotSize.Second, labelLines = 2,
            )
        }
    }
}

/** Recharged or Drained beside the Charge battery: a number and a word. */
@Composable
private fun ChargeSmall(c: Recovery.ChargeDay?, recharged: Boolean, modifier: Modifier, failed: Boolean = false) {
    val ready = c != null && c.anchored && c.latest != null
    val value = c?.takeIf { ready }?.let {
        if (recharged) TabCopy.chargeAmount(it.recharged, +1)
        else TabCopy.drainedAmount(it.drainedExercise + it.drainedStress + it.drainedLiving)
    }
    val status = when {
        failed -> FAILED_SHORT
        !ready -> AFTER_FIRST_NIGHT
        recharged -> TabCopy.RECHARGED
        else -> TabCopy.drainedMostly(c!!.drainedExercise, c.drainedStress, c.drainedLiving)
    }
    SmallCard(MetricColors.charge, if (recharged) tr(R.string.common_recharged) else tr(R.string.common_drained), value, null, status, modifier, plain = true)
}

/** Averages charge samples into evenly spaced buckets across [range]; empty buckets are gaps. */
private fun bucket(points: List<Recovery.ChargePoint>, range: LongRange, seconds: Long): List<Float?> {
    val n = ((range.last - range.first + 1) / seconds).toInt().coerceAtLeast(2)
    val sums = FloatArray(n)
    val counts = IntArray(n)
    points.forEach {
        val i = ((it.t - range.first) / seconds).toInt()
        if (i in 0 until n) { sums[i] += it.value; counts[i]++ }
    }
    return List(n) { if (counts[it] == 0) null else sums[it] / counts[it] }
}

/* ------------------------------------------- Body signals ------------------------------------------- */

/** A body signal for the list: today's value and how it compares with your normal. */
private class BodySignal(
    val metric: Metric,
    val label: String,
    val accent: Color,
    val today: Float?,
    val normal: History.Normal?,
    /** Show the value as a change from your normal (skin temperature), not as a level. */
    val asDeviation: Boolean = false,
    val unit: String = "",
)

/** Loads the last 30 days of each body signal, enough for its normal. */
@Composable
private fun rememberSignals(state: DayUiState, history: DailyHistory): List<BodySignal> {
    val end = state.date
    val from = end.minusDays(History.NORMAL_DAYS - 1L)
    @Composable fun daily(m: Metric) = remember(m, end) { history(m.daily, from, end) }
    val rhr by daily(Metric.RestingHr).collectAsState(emptyList())
    val hrv by daily(Metric.Hrv).collectAsState(emptyList())
    val skin by daily(Metric.SkinTemperature).collectAsState(emptyList())
    val breath by daily(Metric.Breathing).collectAsState(emptyList())
    val spo2 by daily(Metric.Spo2).collectAsState(emptyList())
    val stress by daily(Metric.Stress).collectAsState(emptyList())
    fun normalOf(stats: List<org.openstrap.app.data.DayStat>) = History.normal(stats, end)
    return listOf(
        BodySignal(Metric.RestingHr, tr(R.string.common_resting_heart_rate_2), MetricColors.heart, state.restingHeartRate?.toFloat(), normalOf(rhr)),
        BodySignal(Metric.Hrv, tr(R.string.common_hrv), MetricColors.hrv, state.hrv.avg()?.toFloat(), normalOf(hrv)),
        BodySignal(
            Metric.SkinTemperature, tr(R.string.common_skin_temperature), MetricColors.temperature,
            state.skinTemperature.takeIf { it.isNotEmpty() }?.map { it.y }?.average()?.toFloat(), normalOf(skin), asDeviation = true,
        ),
        BodySignal(Metric.Breathing, tr(R.string.common_breathing_rate), MetricColors.sleepLight, state.breathRate.avg()?.toFloat(), normalOf(breath)),
        BodySignal(Metric.Spo2, tr(R.string.common_blood_oxygen), MetricColors.spo2, state.spo2.avg()?.toFloat(), normalOf(spo2), unit = "%"),
        BodySignal(Metric.Stress, tr(R.string.common_stress), MetricColors.stress, state.stress.avg()?.toFloat(), normalOf(stress)),
    )
}

/**
 * Body signals against your normal, each opening its metric. Skin temperature shows as its change from your normal,
 * since the level depends on fit and room. [waiting] is what a row with no reading says.
 */
@Composable
private fun SignalsList(signals: List<BodySignal>, waiting: String, onOpen: (Metric) -> Unit) {
    ListPanel(title = tr(R.string.common_body_signals), note = tr(R.string.common_against_your_normal)) {
        signals.forEachIndexed { i, s ->
            val direction = History.status(s.today, s.normal)
            val value = s.today?.let { v ->
                if (s.asDeviation && s.normal != null) s.metric.formatChange(v - s.normal.mean)
                else s.metric.format(v) + s.unit
            }
            ListRow(
                s.label, s.accent, value,
                if (s.today == null) waiting else TodayCopy.normalWord(direction),
                offNormal = direction != null && direction != 0, onClick = { onOpen(s.metric) }, divider = i > 0,
                icon = s.metric.rowIcon(),
            )
        }
    }
}

/* --------------------------------------------- Shared ------------------------------------------------ */

/**
 * The quiet panels' progress bar: a faint solid track, the first [fraction] of it a glowing line in [color], and a
 * dot where it ends, like the knob on the sleep score's arc.
 */
@Composable
internal fun GlowBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val y = size.height / 2
        val r = 5.dp.toPx()
        // Inset by the dot's halo, so a full or empty bar keeps its dot inside the panel.
        val x0 = r
        val x1 = size.width - r
        drawLine(Color.White.copy(alpha = 0.10f), Offset(x0, y), Offset(x1, y), 2.dp.toPx(), StrokeCap.Round)
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return@Canvas
        val end = Offset(x0 + (x1 - x0) * f, y)
        drawLine(color.copy(alpha = 0.22f), Offset(x0, y), end, 6.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(x0, y), end, 2.2.dp.toPx(), StrokeCap.Round)
        drawCircle(color.copy(alpha = 0.30f), r, end)
        drawCircle(color, 3.dp.toPx(), end)
        drawCircle(Color.White, 1.2.dp.toPx(), end)
    }
}

@Composable
internal fun Note(text: String) = Text(text, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
