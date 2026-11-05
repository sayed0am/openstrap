package org.openstrap.app.ui.glow

import org.openstrap.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import org.openstrap.app.presentation.Units
import org.openstrap.app.ui.components.cardNumber
import org.openstrap.app.ui.dashboard.SleepCopy
import org.openstrap.app.ui.dashboard.NewsTag
import org.openstrap.app.ui.dashboard.OffStatus
import org.openstrap.app.ui.dashboard.OffWords
import org.openstrap.app.ui.dashboard.bodyOffStatus
import org.openstrap.app.ui.dashboard.bodyTag
import org.openstrap.app.ui.dashboard.offTag
import org.openstrap.app.ui.dashboard.stressMean
import org.openstrap.app.ui.dashboard.stressOffStatus
import org.openstrap.app.ui.glow.pictures.PictureSize
import org.openstrap.app.ui.glow.pictures.RangeFill
import org.openstrap.app.ui.glow.pictures.SplitBar
import org.openstrap.app.ui.glow.pictures.TempDial
import org.openstrap.app.ui.glow.pictures.tempDialRim
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openstrap.app.presentation.chargeShown
import org.openstrap.app.data.Healthspan
import org.openstrap.app.data.History
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.presentation.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.ui.dashboard.FIRST_SCORE
import org.openstrap.app.ui.dashboard.PARTIAL_DAY
import org.openstrap.app.ui.dashboard.Wear
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.InfoCopy
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.presentation.Metric
import org.openstrap.app.ui.dashboard.FAILED_SHORT
import org.openstrap.app.ui.dashboard.RECOVERY_FAILED
import org.openstrap.app.ui.dashboard.RecoveryFailure
import org.openstrap.app.ui.dashboard.TRY_AGAIN
import org.openstrap.app.ui.dashboard.RecoveryCard
import org.openstrap.app.ui.dashboard.RecoveryFacts
import org.openstrap.app.presentation.TabCopy
import org.openstrap.app.presentation.TodayCopy
import org.openstrap.app.ui.dashboard.avg
import org.openstrap.app.ui.dashboard.recoveryCards
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.glow.draw.CoreShape
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.draw.DotSize

/*
 * The Recovery tab (spec 5.1): "can I push today, and why". The score glows with the last 7 days beside it, what
 * drove it as contributor bars, then Charge as a Tall battery with Recharged and Drained as Smalls, skin temperature
 * with its dial, the body signals and Habit Age ([recoveryCards]). How the score is
 * worked out, the strap's own score and why it might be off are in the Recovery info sheet, behind the hero's (i).
 * The hero, the contributors and the Charge battery open their details on this tab's stack.
 */

// A whole tab, filling the pane like the other tab screens: nothing outside places it.
@Suppress("ModifierMissing", "ComposeModifierMissing")
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
    val waiting = if (firstNights) AFTER_FIRST_NIGHT else NO_READING
    val slots = recoveryCards(RecoveryFacts(habitAge = healthspan != null, learning = learning, hasRecovery = r != null))
    var info by rememberSaveable { mutableStateOf(false) }
    var partialInfo by rememberSaveable { mutableStateOf(false) }
    // Built once, so the cards given it can skip (audit K11).
    val partialTag = remember { TitleTag(PARTIAL_DAY) { partialInfo = true } }
    val partial = partialTag.takeIf { wear?.partial == true }
    val isToday = state.date == java.time.LocalDate.now()
    CompositionLocalProvider(LocalGlowPaused provides { list.isScrollInProgress }) {
        GlowScreenList(contentPadding, list, focus = showCharge, onFocused = onChargeShown) {
            add(Pane.Full, GlowBlocks.HEADER) { header() }
            tabCards(slots, focus = RecoveryCard.Charge) { card, m ->
                when (card) {
                    RecoveryCard.Hero ->
                        if (failure != null) RecoveryFailedHero(
                            failure, recoveryHeroStyle(MetricColors.readiness), { onOpenScore(Score.Recovery) }, onRetryRecovery,
                            Modifier.fillMaxWidth().gridSize(CardSize.Wide), gap = 22.dp,
                        )
                        else RecoveryHero(
                            r, onOpen = { onOpenScore(Score.Recovery) }, onInfo = { info = true }, firstNights = firstNights,
                            noSleep = isToday && state.loaded && state.asleepSeconds == null, deleted = state.deletedSleep != null, firstScore = firstScore,
                            shown = TodayCopy.recoveryShown(r?.readiness, strapScore, waiting),
                        )
                    RecoveryCard.Drivers -> DriversList(r?.readiness, onAll = { onOpenScore(Score.Recovery) })
                    // Charge is worked out with Recovery, so a failure there says so here too, not "after your first night".
                    RecoveryCard.Charge -> ChargeTall(
                        r?.charge, m, failed = failure != null, tag = partial, onOpen = { onOpenScore(Score.Energy) },
                    )
                    RecoveryCard.Recharged -> ChargeSmall(r?.charge, recharged = true, m, failed = failure != null)
                    RecoveryCard.Drained -> ChargeSmall(r?.charge, recharged = false, m, failed = failure != null)
                    RecoveryCard.SkinTemp -> SkinTempCard(
                        signals.skin, signals.skinNormal, signals.skinLearning, waiting, onOpen = { onOpenMetric(Metric.SkinTemperature) }, modifier = m,
                    )
                    RecoveryCard.BodySignals -> SignalsList(signals.rows, waiting, onOpenMetric)
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
    r: RecoveryResult?, onOpen: () -> Unit, onInfo: () -> Unit, firstNights: Boolean = false, noSleep: Boolean = false, deleted: Boolean = false, firstScore: Boolean = false,
    shown: TodayCopy.RecoveryShown? = null,
) {
    val ready = r?.readiness
    val score = ready?.score
    val yesterday = r?.readinessTrend?.dropLast(1)?.lastOrNull()?.second
    val alert = ready?.strainAlert == true
    GlowCard(recoveryHeroStyle(MetricColors.readiness), Modifier.fillMaxWidth().gridSize(CardSize.Wide), onClick = onOpen) {
        Column {
            GlowHeader(
                stringResource(R.string.glow_recovery_title), trailing = score?.let { TabCopy.vsYesterday(it, yesterday) },
                tag = if (firstScore && score != null) TitleTag(FIRST_SCORE) else null,
                trailingContent = {
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    InfoButton(stringResource(R.string.glow_recovery_about), onInfo)
                },
            )
            HeroSentence(
                when {
                    firstNights -> TodayCopy.RECOVERY_FIRST_NIGHT
                    ready == null -> TabCopy.RECOVERY_WAITING
                    score != null -> TabCopy.recovery(score, alert, ready.contributors)
                    noSleep -> if (deleted) TabCopy.DELETED_SLEEP_SCORE else TabCopy.NO_SLEEP_SCORE
                    ready.phase == Recovery.Phase.Withheld -> TodayCopy.noScoreLine(TodayCopy.NoScore.Learning(ready.baselineNights, Recovery.SCORED_NIGHTS))
                    ready.withheld -> TodayCopy.noScoreLine(TodayCopy.NoScore.NoHeartData)
                    else -> stringResource(R.string.glow_recovery_scored_each_morning)
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
 * hero never waits forever. The Recovery tab and Today both show it, each in its own hero [style] and [gap].
 */
@Composable
internal fun RecoveryFailedHero(
    failure: RecoveryFailure,
    style: GlowStyle,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
    gap: Dp,
) {
    GlowCard(style, modifier, onClick = onOpen) {
        Column {
            GlowHeader(stringResource(R.string.glow_recovery_title))
            HeroSentence(RECOVERY_FAILED)
            failure.yesterday?.let { y ->
                Spacer(Modifier.height(gap))
                HeroFigure(
                    y.toString(), stringResource(R.string.glow_recovery_yesterday), big = true, modifier = Modifier.alpha(FAILED_ALPHA),
                )
            }
            Spacer(Modifier.height(gap))
            PageButton(TRY_AGAIN, onRetry, primary = true, size = ButtonSize.Hero)
        }
    }
}

/**
 * What drove the score: the three that weigh most, each as a contributor bar with its word, the off tag in the word's
 * place when it's off usual (spec 5.1). Bars and words stay white: colour never says how it's going (spec 2.2).
 */
@Composable
private fun DriversList(ready: Recovery.Readiness?, onAll: () -> Unit) {
    val all = ready?.contributors.orEmpty()
    val top = TabCopy.topDrivers(all)
    // "All" and each row open the Recovery detail, which has every contributor.
    ListPanel(
        title = stringResource(R.string.glow_recovery_what_drove),
        note = if (all.size > top.size) stringResource(R.string.glow_recovery_drivers_all, all.size) else null,
        onNote = onAll.takeIf { all.isNotEmpty() },
    ) {
        if (top.isEmpty()) ListRow(
            stringResource(R.string.glow_recovery_waiting_night), MetricColors.readiness, null,
            stringResource(R.string.glow_recovery_drivers_waiting), dash = false, divider = false,
        )
        top.forEachIndexed { i, c ->
            DriverRow(
                TabCopy.factorName(c.factor), TabCopy.contributorWord(c.score), c.score / 100f,
                offTag(OffWords.Usual, contributorOffStatus(c.score)), divider = i > 0, onClick = onAll,
            )
        }
    }
}

/** A contributor against usual: Above from Ready for more, Below under Ready, by the score's own bands. */
internal fun contributorOffStatus(score: Int): OffStatus = when {
    !TabCopy.contributorOff(score) -> OffStatus.Usual
    score >= org.openstrap.app.presentation.RecoveryBands.READY_FOR_MORE_FROM -> OffStatus.Above
    else -> OffStatus.Below
}

/**
 * A contributor bar (chart kit): the [name] on its own line, then a white bar filled to [fraction] (0..1) with the
 * [word] after it, or the off [tag] in the word's place. [onClick] opens the Recovery detail, with the row's caret.
 */
@Composable
private fun DriverRow(name: String, word: String, fraction: Float, tag: NewsTag?, divider: Boolean, onClick: () -> Unit) {
    val opening = org.openstrap.app.ui.components.cardOpening(onClick)
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth()
            .then(opening?.modifier ?: Modifier)
            .clickable(onClick = opening?.onClick ?: onClick)
            .topHairline(divider, RowHairline)
            .padding(vertical = GlowLayout.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, style = GlowType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val shape = RoundedCornerShape(3.dp)
                Box(Modifier.weight(1f).height(6.dp).background(Color.White.copy(alpha = 0.07f), shape)) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(Color.White.copy(alpha = 0.7f), shape))
                }
                Spacer(Modifier.width(12.dp))
                if (tag != null) NewsPill(tag)
                else Text(word, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
            }
        }
        Spacer(Modifier.width(RowCaretGap))
        TapCaret(ROW_CARET_ALPHA)
    }
}

/* ---------------------------------------------- Charge ---------------------------------------------- */

/**
 * Charge as a Tall battery (spec 2.3): the light rises to its level from below, with the number now and Charge at
 * waking at the foot, nothing else in the light (design-system.html, Levels).
 */
@Composable
private fun ChargeTall(
    c: Recovery.ChargeDay?, modifier: Modifier, failed: Boolean = false, tag: TitleTag? = null, onOpen: () -> Unit,
) {
    // Before the first night the number would only be the simulation's starting guess: a dash until then.
    val now = c?.takeIf { it.anchored }?.latest?.let(::chargeShown)
    val atWake = c?.atWake?.toInt()
    GlowCard(GlowStyle.Ember.copy(lightLevel = (now ?: 0) / 100f, second = true), modifier, onClick = onOpen) {
        Column(Modifier.fillMaxHeight()) {
            GlowHeader(stringResource(R.string.glow_recovery_energy), tag = tag)
            Spacer(Modifier.weight(1f))
            HeroFigure(
                now?.toString(),
                when {
                    failed -> FAILED_SHORT
                    c == null || !c.anchored -> AFTER_FIRST_NIGHT
                    atWake == null -> stringResource(R.string.glow_recovery_now)
                    c.strapOffOvernight -> stringResource(R.string.glow_recovery_charge_at_start, atWake)
                    else -> stringResource(R.string.glow_recovery_charge_at_waking, atWake)
                },
                big = true, dot = DotSize.Second, labelLines = 2,
            )
        }
    }
}

/**
 * Recharged or Drained beside the Charge battery (spec 5.1), plain: unlit, no tag, no caret, opening nothing, since no
 * Energy history is kept. Recharged draws its night as a fill from Energy at bedtime to Energy at waking, "Overnight,
 * 34 to 72". Drained draws a split bar of what took it: exercise, stress and the rest of living.
 */
@Composable
private fun ChargeSmall(c: Recovery.ChargeDay?, recharged: Boolean, modifier: Modifier = Modifier, failed: Boolean = false) {
    val ready = c != null && c.anchored && c.latest != null
    val value = c?.takeIf { ready }?.let {
        if (recharged) TabCopy.chargeAmount(it.recharged, +1)
        else TabCopy.chargeAmount(it.drainedExercise + it.drainedStress + it.drainedLiving, -1)
    }
    // Recharged's night: from bedtime, or where the card's own figure says it started, to waking.
    val night = c?.takeIf { ready && recharged }?.atWake?.let { wake -> (c.atBedtime ?: (wake - c.recharged)).coerceIn(0f, 100f) to wake }
    val parts = c?.takeIf { ready && !recharged }?.let { listOf(it.drainedExercise, it.drainedStress, it.drainedLiving) }
    val status = when {
        failed -> FAILED_SHORT
        !ready -> AFTER_FIRST_NIGHT
        night != null -> stringResource(R.string.glow_recovery_overnight, night.first.roundToInt(), night.second.roundToInt())
        recharged -> TabCopy.RECHARGED
        else -> TabCopy.drainedMostly(c.drainedExercise, c.drainedStress, c.drainedLiving)
    }
    val title = stringResource(if (recharged) R.string.glow_recovery_recharged else R.string.glow_recovery_drained)
    PictureCard(MetricColors.charge, modifier) {
        CardTitle(title)
        Spacer(Modifier.height(10.dp))
        val picture = Modifier.fillMaxWidth().weight(1f)
        when {
            night != null -> RangeFill(night.first, night.second, MetricColors.charge, PictureSize.Small, picture)
            parts != null && parts.any { it > 0f } -> SplitBar(parts, MetricColors.charge, PictureSize.Small, picture)
            else -> Spacer(picture)
        }
        // A plain number, not dots: it breaks down the battery beside it.
        if (value != null) Text(value, Modifier.cardNumber(value), color = Color.White, style = GlowType.smallValue, maxLines = 1)
        else WaitingDash(Modifier.padding(vertical = 8.dp))
        Text(
            status, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
            modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ------------------------------------------ Skin temperature ------------------------------------------ */

/** The share of a Wide's width the dial's box takes at the right, after the number, so it centres where [tempDialRim] lights. */
private const val DIAL_SHARE = 0.44f

/**
 * Skin temperature as a short Wide (spec 5.1): the change from your normal in dots with °C and its off tag at the
 * left, the dial at the right centred on your normal. Off your normal the rim lights at the dial's top when above, its
 * foot when below. Before there's a normal it waits for one. Opens the skin temperature detail.
 */
@Composable
private fun SkinTempCard(
    today: Float?,
    normal: History.Normal?,
    learning: String?,
    waiting: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deviation = if (today != null && normal != null) today - normal.mean else null
    val band = normal?.let { (it.low - it.mean)..(it.high - it.mean) }
    val status = bodyOffStatus(today, normal)
    val tag = offTag(OffWords.Normal, status)
    val rim = if (tag != null) tempDialRim(high = status == OffStatus.Above, PictureSize.Wide) else null
    PictureCard(MetricColors.temperature, modifier.fillMaxWidth(), rim = rim, onClick = onOpen) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle(stringResource(R.string.glow_recovery_skin_temperature))
                Spacer(Modifier.height(12.dp))
                NumberWithTag(tag) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Bottom) {
                        // Against your normal once there is one; until then last night's reading, as the Sleep tab shows it.
                        val shown = deviation?.let { Metric.SkinTemperature.formatChange(it, Units.DOT_LOCALE) }
                            ?: today?.let { Metric.SkinTemperature.format(it, Units.DOT_LOCALE) }
                        if (shown != null) DotMatrixText(shown, Modifier.cardNumber(shown), dot = DotSize.Edge)
                        else WaitingDash(Modifier.padding(vertical = 8.dp))
                        if (shown != null) Text(
                            Metric.SkinTemperature.unit, color = Color.White.copy(alpha = 0.7f), style = GlowType.secondary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    when {
                        today == null -> waiting
                        normal == null -> learning ?: TodayCopy.normalWord(null)
                        else -> stringResource(R.string.glow_recovery_against_normal)
                    },
                    color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                    modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            TempDial(deviation, band, MetricColors.temperature, PictureSize.Wide, Modifier.fillMaxWidth(DIAL_SHARE).height(84.dp))
        }
    }
}

/* ------------------------------------------- Body signals ------------------------------------------- */

/** A body signal row: today's value as shown, how it compares with your normal, and its off tag when off. */
private class BodySignal(
    val metric: Metric,
    val label: String,
    val accent: Color,
    val value: String?,
    val direction: Int?,
    val tag: NewsTag?,
    /** What the row says while its normal is forming, "Learning, 5 of 7 nights"; null for the plain word. */
    val learning: String? = null,
)

/** The Recovery tab's body signals: the rows of the list and skin temperature for its own card. */
private class Signals(val rows: List<BodySignal>, val skin: Float?, val skinNormal: History.Normal?, val skinLearning: String?)

/**
 * Loads the last 30 days of each body signal, enough for its normal ([rememberNormal], keyed on the day and the
 * history, each normal worked out once per load), and the signals once per day state (audit K8). Breathing rate,
 * blood oxygen and stress make the list (spec 5.1): HRV and resting heart rate are in What drove it already.
 */
@Composable
private fun rememberSignals(state: DayUiState, history: DailyHistory): Signals {
    val stress = rememberNormal(Metric.Stress, state.date, history)
    val breathName = stringResource(R.string.glow_recovery_breathing_rate)
    val spo2Name = stringResource(R.string.glow_recovery_blood_oxygen)
    val stressName = stringResource(R.string.glow_recovery_stress)
    return remember(state, stress) {
        fun body(metric: Metric, name: String, accent: Color, today: Float?, normal: History.Normal?, unit: String = ""): BodySignal {
            val direction = History.status(today, normal)
            return BodySignal(
                metric, name, accent, today?.let { metric.format(it) + unit }, direction, bodyTag(metric, direction),
                SleepCopy.learning(state, metric),
            )
        }
        val stressToday = stressMean(state.stress)
        Signals(
            listOf(
                // Night signals: last night's against the nights before, as the Sleep tab and their details read them.
                body(
                    Metric.Breathing, breathName, MetricColors.sleepLight, SleepCopy.nightValue(state, Metric.Breathing),
                    SleepCopy.nightNormal(state, Metric.Breathing),
                ),
                body(
                    Metric.Spo2, spo2Name, MetricColors.spo2, SleepCopy.nightValue(state, Metric.Spo2),
                    SleepCopy.nightNormal(state, Metric.Spo2), unit = "%",
                ),
                BodySignal(
                    Metric.Stress, stressName, MetricColors.stress, stressToday?.let { Metric.Stress.format(it) },
                    History.status(stressToday, stress), offTag(OffWords.Usual, stressOffStatus(state.stress, stress)),
                ),
            ),
            // Tonight's against the nights before (spec 2.7, Dial): the day's mean counts hours off the wrist, which
            // read several degrees low, so a day with no sleep has no reading here.
            skin = SleepCopy.nightValue(state, Metric.SkinTemperature),
            skinNormal = SleepCopy.nightNormal(state, Metric.SkinTemperature),
            skinLearning = SleepCopy.learning(state, Metric.SkinTemperature),
        )
    }
}

/** Body signals against your normal, each opening its metric, its off tag in place of its word. [waiting] is what a row with no reading says. */
@Composable
private fun SignalsList(signals: List<BodySignal>, waiting: String, onOpen: (Metric) -> Unit) {
    ListPanel(title = stringResource(R.string.glow_recovery_body_signals), note = stringResource(R.string.glow_recovery_against_normal)) {
        signals.forEachIndexed { i, s ->
            ListRow(
                s.label, s.accent, s.value,
                when {
                    s.value == null -> waiting
                    s.learning != null -> s.learning
                    else -> TodayCopy.normalWord(s.direction)
                },
                tag = s.tag, onClick = { onOpen(s.metric) }, divider = i > 0, icon = s.metric.rowIcon(),
            )
        }
    }
}
