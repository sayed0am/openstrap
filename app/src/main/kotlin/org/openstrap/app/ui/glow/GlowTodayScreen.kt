package org.openstrap.app.ui.glow

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.openstrap.app.data.Moment
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.StrapReadinessRow
import org.openstrap.app.device.SyncRecord
import org.openstrap.app.ui.components.ChartPoint
import org.openstrap.app.ui.components.HapticEvent
import org.openstrap.app.ui.components.rememberHaptics
import org.openstrap.app.ui.dashboard.DailyHistory
import org.openstrap.app.ui.dashboard.DayUiState
import org.openstrap.app.ui.dashboard.Hue
import org.openstrap.app.ui.dashboard.Tier
import org.openstrap.app.ui.dashboard.defaultLook
import org.openstrap.app.ui.dashboard.lookOr
import org.openstrap.app.ui.dashboard.todayPanes
import org.openstrap.app.ui.dashboard.varyLooks
import org.openstrap.app.ui.dashboard.habitAgeNew
import org.openstrap.app.ui.dashboard.items
import org.openstrap.app.ui.dashboard.LocalGoals
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.dashboard.TodayCard
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.TodayEdits
import org.openstrap.app.ui.dashboard.TodayFacts
import org.openstrap.app.ui.dashboard.TodaySlot
import org.openstrap.app.ui.dashboard.applyEdits
import org.openstrap.app.ui.dashboard.avg
import org.openstrap.app.ui.dashboard.sportNoun
import org.openstrap.app.ui.dashboard.CHARGE_FAILED
import org.openstrap.app.ui.dashboard.FAILED_SHORT
import org.openstrap.app.ui.dashboard.RECOVERY_FAILED
import org.openstrap.app.ui.dashboard.RecoveryFailure
import org.openstrap.app.ui.dashboard.STALE_AFTER_S
import org.openstrap.app.ui.dashboard.TRY_AGAIN
import org.openstrap.app.ui.dashboard.WAITING_LAST_NIGHT
import org.openstrap.app.ui.dashboard.clockText
import org.openstrap.app.ui.dashboard.lastTried
import org.openstrap.app.ui.dashboard.stuckCopy
import org.openstrap.app.ui.dashboard.syncStuck
import org.openstrap.app.ui.dashboard.upToMarker
import org.openstrap.app.ui.dashboard.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.FIRST_SCORE
import org.openstrap.app.ui.dashboard.PARTIAL_DAY
import org.openstrap.app.ui.dashboard.PairStep
import org.openstrap.app.ui.dashboard.Wear
import org.openstrap.app.ui.dashboard.WakingLead
import org.openstrap.app.ui.dashboard.pairSentence
import org.openstrap.app.ui.dashboard.wakingLead
import org.openstrap.app.ui.dashboard.todayCards
import org.openstrap.app.ui.theme.MetricColors

/*
 * Today (spec 4): one hero whose content follows the moment, then a stable set of cards for it ([todayCards]), with
 * Edit Today's pins and hides on top ([applyEdits]). The hero glows; the rest is edge light and lists, so the glow
 * keeps meaning "look here".
 */

/** The strap's state where Today needs it: paired at all, whether syncing has stopped, and how syncs went. */
class TodayStrap(
    val paired: Boolean = true,
    /** The pairing stage's step while there's no strap or it's connecting for the first time; null once paired (E3). */
    val pairing: PairStep? = null,
    /** Syncing has stopped ([syncStopped]), so cards whose data is old say how far it goes. */
    val stopped: Boolean = false,
    /** When syncs last ran and how they ended: the stuck hero's "Last tried 07:42" and its 3 h rule. */
    val record: SyncRecord = SyncRecord(),
    val canSync: Boolean = true,
    /** How old a card's newest reading is before it's marked; a debug preview marks every card at once. */
    val staleAfter: Long = STALE_AFTER_S,
)

@Composable
fun GlowTodayScreen(
    state: DayUiState,
    liveHeartRate: Int?,
    header: @Composable () -> Unit,
    contentPadding: PaddingValues,
    /** The moment Today is laid out for ([org.openstrap.app.ui.dashboard.layoutMoment]); null while it loads. */
    moment: Moment?,
    onOpenMetric: (Metric) -> Unit = {},
    recovery: RecoveryResult? = null,
    /** Opens the Recovery screen over Today, from the evening hero's day in a sentence. */
    onOpenRecovery: () -> Unit = {},
    workouts: List<WorkoutAnalysis> = emptyList(),
    onOpenWorkout: (Long) -> Unit = {},
    onOpenHealthspan: () -> Unit = {},
    /** Opens the profile, for the Habit Age card's reminder to add a birth year or sex. */
    onOpenProfile: () -> Unit = {},
    /** The weekly Habit Age estimate, or why it's withheld; null hides the card. */
    healthspan: org.openstrap.app.data.Healthspan.Outcome? = null,
    /** The Sunday of the last Habit Age estimate opened, so Today stops offering it ([habitAgeNew]). */
    habitAgeSeenAsOf: Long? = null,
    /** Per-day history for Your week; null leaves its steps out. */
    history: DailyHistory? = null,
    /** Edit Today's hidden and pinned cards. */
    edits: TodayEdits = TodayEdits(),
    onEditsChange: (TodayEdits) -> Unit = {},
    /** The strap's own Readiness for the day, shown as "Strap's estimate" while OpenStrap's score is withheld. */
    strapReadiness: StrapReadinessRow? = null,
    /** The morning check-in (1-5), asked on the waking moment. */
    checkIn: Int? = null,
    onCheckIn: (Int?) -> Unit = {},
    /** A stretch nobody started, offered for saving; null hides the strip. */
    suggestion: org.openstrap.app.data.WorkoutSuggestions.Suggestion? = null,
    onSaveSuggestion: () -> Unit = {},
    onDismissSuggestion: () -> Unit = {},
    /** Opens the ready screen for a sport, as Activity's start card does. */
    onStartSport: (Int) -> Unit = {},
    /** Opens the ready screen on its sport list. */
    onOtherSport: () -> Unit = {},
    /** An effort under way nobody started ([org.openstrap.app.data.AutoStart]); null hides the strip. */
    offer: org.openstrap.app.data.AutoStart.Offer? = null,
    onRecordOffer: () -> Unit = {},
    onDeclineOffer: () -> Unit = {},
    /** A workout recording now: the hero during a workout leads back to it ([WorkoutShortcut]). */
    running: org.openstrap.app.device.WorkoutState.Recording? = null,
    onOpenRunning: () -> Unit = {},
    onResumeRunning: () -> Unit = {},
    /** Where the hero's light comes from for the moment ([org.openstrap.app.ui.dashboard.heroLightAngle]); null keeps its own. */
    heroLightAngle: Float? = null,
    strap: TodayStrap = TodayStrap(),
    /** Opens the Strap page, to pair. */
    onPair: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    /** When you usually go to sleep, for "Bedtime in 1 h 10". */
    usualBedtime: LocalTime? = null,
    /** Today's Recovery failed to work out; null when it didn't. */
    recoveryFailure: RecoveryFailure? = null,
    /** Works Recovery out again after a failure. */
    onRetryRecovery: () -> Unit = {},
    /** Opens a detail over Today: Load, time to recover, the week story. */
    onOpen: (org.openstrap.app.ui.dashboard.Detail) -> Unit = {},
    /** Your usual steps by the end of each hour, for the Activity card's ghost line; null until there's a usual. */
    usualSteps: List<Float>? = null,
    /** How much of the day the strap was worn, for the Partial day tag ([org.openstrap.app.ui.dashboard.wear]). */
    wear: Wear? = null,
    /** Today's Recovery is the first real score, marked once (E4). */
    firstScore: Boolean = false,
) {
    val list = rememberLazyListState()
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing) EditTodaySheet(edits, onSave = { onEditsChange(it); editing = false }, onDismiss = { editing = false })
    var partialInfo by rememberSaveable { mutableStateOf(false) }

    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val isToday = state.date == now.atZone(zone).toLocalDate()
    val rec = recovery?.takeIf { it.date == state.date }
    val lastWorkout = workouts.maxByOrNull { it.end }
    val ctx = TodayContext(
        state, rec, liveHeartRate, workouts, lastWorkout, isToday, now.epochSecond, zone, moment,
        restingRange = rememberNormal(Metric.RestingHr, state.date, history),
        firstNights = rec != null && !rec.charge.anchored,
        stuck = strap.paired && syncStuck(isToday, state.asleepSeconds != null, strap.record.failingSince, now.epochSecond, zone),
        strapScore = strapReadiness?.takeIf { it.day == state.dayStart }?.score,
        stopped = strap.stopped, staleAfter = strap.staleAfter,
        recoveryFailure = recoveryFailure?.takeIf { it.date == state.date && rec == null },
        partial = wear?.takeIf { it.partial }?.let { TitleTag(PARTIAL_DAY) { partialInfo = true } },
        firstScore = firstScore,
    )
    if (partialInfo && wear != null) PartialDaySheet(wear, isToday, onDismiss = { partialInfo = false })
    // Before a strap is paired, or while it connects for the first time, the hero is the pairing stage (E3).
    val pairing = strap.pairing ?: PairStep.Ready.takeIf { !strap.paired }
    val facts = TodayFacts(
        isToday = isToday, offer = offer != null, suggestion = suggestion != null, alerts = state.alerts.isNotEmpty(),
        workout = lastWorkout != null && isToday,
        healthspan = healthspan?.result?.let { r ->
            habitAgeNew(Instant.ofEpochSecond(r.asOf).atZone(zone).toLocalDate(), habitAgeSeenAsOf == r.asOf, state.date, now.atZone(zone).toLocalDate())
        } == true,
    )
    // Before the first estimate Habit Age never shows on Today, pinned or not.
    val unavailable = if (healthspan?.result == null) setOf(TodayCard.HabitAge) else emptySet()
    val morning = LocalTime.now().hour < 12
    val live = liveHeartRate != null
    // The moment the cards are laid out for, with no moment yet resolved as todayCards does.
    val looksFor = moment ?: if (morning) Moment.Waking else Moment.Midday

    // The glow holds still while the list scrolls, so scrolling stays as smooth as on other screens.
    CompositionLocalProvider(LocalGlowPaused provides { list.isScrollInProgress }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // No two cards next to each other look the same (spec 2.4). On two columns, side by side counts too.
            val wide = maxWidth >= GlowLayout.TwoColumnWidth
            // On columns too narrow for Smalls (the Fold's inner screen) a Small pair spans both (spec 2.4 Unfolded).
            val across = GlowLayout.smallsAcross(maxWidth)
            val slots = varyLooks(applyEdits(todayCards(moment, facts, morning), edits, moment, unavailable), looksFor, live, wide, across)
            val panes = todayPanes(slots, across)
            GlowScreenList(contentPadding, list) {
                full { header() }
                // One block per grid row: a Small pair stays side by side, and on a wide screen in one column.
                packGrid(slots.indices.map { slots[it].size to it }).forEach { row ->
                    val block: @Composable () -> Unit = {
                        GridRowBlock(row) { i, m ->
                            val slot = slots[i]
                            // Before a strap is paired, everything under the pairing hero waits, dimmed.
                            val dim = if (pairing != null && slot.card != TodayCard.Hero && slot.card != TodayCard.EditToday) Modifier.alpha(0.45f) else Modifier
                            // Where the look differs from the card's own, its edge card is told the side and colour to use.
                            val own = defaultLook(slot.copy(look = null), looksFor, live)
                            val look = slot.lookOr(looksFor, live)
                            val override = EdgeOverride(
                                edge = look.edge.takeIf { it != own.edge },
                                color = EdgeNeutral.takeIf { look.hue == Hue.Neutral && own.hue != Hue.Neutral },
                                // An unlit card drawn as an edge card (Last night, Your week, Start) skips its light.
                                plain = look.tier == Tier.List,
                            )
                            CompositionLocalProvider(LocalEdgeOverride provides override) {
                                Column(m.then(dim)) {
                                    when (slot.card) {
                                        TodayCard.Hero -> Hero(
                                            ctx, strap, pairing, heroLightAngle, usualBedtime,
                                            running, onOpenRunning, onResumeRunning, onStartSport, onOtherSport, onPair, onSyncNow,
                                            onOpenRecovery, onOpenWorkout, onRetryRecovery,
                                            onOpenScore = { onOpen(org.openstrap.app.ui.dashboard.Detail.OfScore(it)) },
                                            energyHidden = slots.none { it.card == TodayCard.Charge },
                                        )
                                        TodayCard.EditToday -> EditTodayButton({ editing = true })
                                        else -> TodayRow(slot, ctx, healthspan, history, checkIn, onCheckIn, onOpenMetric,
                                            onOpenWorkout, onOpenHealthspan, onOpenProfile, offer, onRecordOffer, onDeclineOffer, suggestion,
                                            onSaveSuggestion, onDismissSuggestion, onOpen, usualSteps, onRetryRecovery,
                                            if (slot.size == CardSize.Small) Modifier.fillMaxSize() else Modifier, onStartSport = onStartSport)
                                    }
                                }
                            }
                        }
                    }
                    // The hero and strips span the width on a wide screen; the rows between take turns.
                    when (panes[row.items().first()]) {
                        Pane.Full -> full(block)
                        Pane.Left -> left(block)
                        Pane.Right -> right(block)
                    }
                }
            }
        }
    }
}

/** What Today's cards read, worked out once per screen. */
private class TodayContext(
    val state: DayUiState,
    val rec: RecoveryResult?,
    val live: Int?,
    val workouts: List<WorkoutAnalysis>,
    val lastWorkout: WorkoutAnalysis?,
    val isToday: Boolean,
    val now: Long,
    val zone: ZoneId,
    val moment: Moment?,
    /** Your normal resting heart rate, drawn as the resting range behind heart rate lines; null until known. */
    val restingRange: org.openstrap.app.data.History.Normal?,
    /** Before the strap has seen a night: cards keep their shape with a dash (spec 4.3). */
    val firstNights: Boolean,
    /** Last night is still on the strap (spec 4.3). */
    val stuck: Boolean,
    /** The strap's own Readiness for the day. */
    val strapScore: Int?,
    /** Syncing has stopped, and how old a reading is before its card is marked. */
    val stopped: Boolean,
    val staleAfter: Long,
    /** Today's Recovery failed to work out (spec 4.3). */
    val recoveryFailure: RecoveryFailure?,
    /** The Partial day tag for cards whose figures a partial day lowers (spec 4.3); null on a day worn enough. */
    val partial: TitleTag?,
    /** Today's Recovery is the first real score (E4). */
    val firstScore: Boolean,
) {
    /** The evening and night hero shows the day's steps in dots ([EveningHero]), so the Steps card needn't. */
    val stepsInHero: Boolean get() = (moment == Moment.Evening || moment == Moment.Night) && !stuck

    /** "Up to 09:40" for a card whose [newest] reading is stale while syncing has stopped ([upToMarker]). */
    fun upTo(newest: Long?): String? = upToMarker(newest, now, stopped, isToday, zone, staleAfter)

    /** The newest reading of the day, from the series the strap records through it. */
    val newest: Long?
        get() = listOfNotNull(state.heartRate.lastOrNull(), state.stress.lastOrNull(), state.hrv.lastOrNull(), state.spo2.lastOrNull())
            .maxOfOrNull { it.x }

    val score get() = rec?.readiness?.score
    val alert get() = rec?.readiness?.strainAlert == true
    /**
     * Charge now, cut to a whole number as the Charge detail's headline is ([chargeShown]); none before the first
     * night, when it would only be the simulation's starting guess.
     */
    val charge get() = rec?.charge?.takeIf { it.anchored }?.latest?.let(::chargeShown)

    /** Why OpenStrap has no score ([TodayCopy.noScore]). */
    val noScore: TodayCopy.NoScore? get() = TodayCopy.noScore(rec?.readiness)

    /** The score, else the strap's estimate shown as such while OpenStrap's own is withheld (as on the Recovery tab). */
    val recoveryShown get() = TodayCopy.recoveryShown(rec?.readiness, strapScore, waiting)

    /** What a card with no value says while it waits. */
    val waiting: String
        get() = when {
            firstNights -> AFTER_FIRST_NIGHT
            stuck -> WAITING_LAST_NIGHT
            else -> "No reading yet"
        }

    /** The day's workouts you'd call workouts, oldest first: the strap's own "Activity" records aren't. */
    val dayWorkouts: List<WorkoutAnalysis>
        get() = workouts.filter { it.workout.start in state.dayStart..state.dayEnd && it.workout.sportType != Workouts.GENERIC_ACTIVITY }
            .sortedBy { it.workout.start }

    /** The evening and night, when Charge waits for sleep to fill it back. */
    val endOfDay get() = moment == Moment.Evening || moment == Moment.Night

    /**
     * Load against your usual. Only a day that's over is a whole day to compare with your usual one, so that's decided
     * by the date, as on the Activity tab and in the Load detail, not by the moment.
     */
    val loadStatus get() = rec?.let { TodayCopy.loadStatus(it.load, it.chronicLoad, endOfDay = !isToday) }

    /** How far Load has come toward your usual day's, for its bar. Null before there's a usual. */
    val loadShare: Float? get() = rec?.let { r -> r.chronicLoad?.takeIf { it > 0f }?.let { r.load / it } }

    /** Time to recover from the latest workout that says how long it needs, or null when none does. */
    val recover: Pair<String?, String>?
        get() {
            val w = workouts.filter { it.recoveryMinutes != null }.maxByOrNull { it.end } ?: return null
            return TodayCopy.recoverText(TodayCopy.recoverLeft(w.end, w.recoveryMinutes ?: 0, now), now, zone)
        }

    /** Your usual sport: the one you start most, over the same 28 days as your usual workout time. */
    val usualSport: Int
        get() {
            val from = now - org.openstrap.app.data.MomentResolver.USUAL_WORKOUT_DAYS * 86_400L
            val yours = workouts.filter { it.startedByYou && it.workout.start >= from }.map { it.workout.sportType to it.workout.start }
            return org.openstrap.app.ui.dashboard.usualSport(yours) ?: topSports(workouts).first().sportType
        }
}

/* ----------------------------------------------- Hero ----------------------------------------------- */

@Composable
private fun Hero(
    ctx: TodayContext, strap: TodayStrap, pairing: PairStep?, lightAngle: Float?, bedtime: LocalTime?,
    running: org.openstrap.app.device.WorkoutState.Recording?, onOpenRunning: () -> Unit, onResumeRunning: () -> Unit,
    onStartSport: (Int) -> Unit, onOtherSport: () -> Unit, onPair: () -> Unit, onSyncNow: () -> Unit,
    onOpenRecovery: () -> Unit, onOpenWorkout: (Long) -> Unit, onRetryRecovery: () -> Unit,
    /**
     * A score's own detail, where a hero about it leads (spec 3.3): Recovery's from waking, Charge's from the Charge
     * heroes, as the Charge battery on Recovery does.
     */
    onOpenScore: (org.openstrap.app.ui.dashboard.Score) -> Unit,
    /** The Energy card is hidden in Edit Today, so the evening hero says Energy itself. */
    energyHidden: Boolean = false,
) {
    val onOpenCharge = { onOpenScore(org.openstrap.app.ui.dashboard.Score.Charge) }
    // Every hero is as high as its content with the card padding around it. The floor is only a Wide's height, under
    // every hero's content, so none shows an empty band.
    val m = Modifier.fillMaxWidth().gridSize(CardSize.Wide)
    // The hero's content crossfades when the moment moves on (spec 3.5); the rows below change only with it.
    val kind = when {
        pairing != null -> HeroKind.Pair
        ctx.stuck -> HeroKind.Stuck
        else -> when (ctx.moment) {
            Moment.During -> if (running != null) HeroKind.During else HeroKind.Midday
            Moment.After -> if (ctx.lastWorkout != null) HeroKind.After else HeroKind.Midday
            Moment.WorkoutWindow -> HeroKind.Window
            Moment.Midday -> HeroKind.Midday
            Moment.Evening, Moment.Night -> HeroKind.Evening
            Moment.Waking -> HeroKind.Waking
            null -> if (LocalTime.now().hour < 12 || !ctx.isToday) HeroKind.Waking else HeroKind.Midday
        }
    }.let {
        // No sleep last night: nothing for Recovery to score, so Charge leads from waking on (spec 4.3). Only once
        // the day has loaded, or every cold start would flash the no-sleep hero first.
        if (it == HeroKind.Waking && ctx.state.loaded && ctx.recoveryFailure == null && wakingLead(ctx.firstNights, ctx.state.asleepSeconds, ctx.isToday) == WakingLead.Charge) HeroKind.NoSleep
        else it
    }
    // The During hero keeps drawing while it fades out after the workout ends, when [running] is already gone.
    val lastRunning = remember { androidx.compose.runtime.mutableStateOf(running) }
    if (running != null) lastRunning.value = running
    Crossfade(kind, animationSpec = tween(300), label = "hero") { k ->
        when (k) {
            HeroKind.Pair -> PairHero(pairing ?: PairStep.Ready, onPair, m)
            HeroKind.Stuck -> StuckHero(ctx, strap, onSyncNow, m)
            HeroKind.Waking -> WakingHero(ctx, lightAngle, { onOpenScore(org.openstrap.app.ui.dashboard.Score.Recovery) }, onRetryRecovery, m)
            HeroKind.Midday -> ChargeHero(ctx, onOpenCharge, onRetryRecovery, m)
            HeroKind.NoSleep -> ChargeHero(ctx, onOpenCharge, onRetryRecovery, m, noSleep = true)
            HeroKind.Window -> WindowHero(ctx, lightAngle, onStartSport, onOtherSport, m)
            HeroKind.After -> AfterHero(ctx, lightAngle, onOpenWorkout, m)
            HeroKind.Evening -> EveningHero(ctx, lightAngle, bedtime, energyHidden, onOpenRecovery, m)
            HeroKind.During -> (running ?: lastRunning.value)?.let { DuringHero(ctx, it, onOpenRunning, onResumeRunning, m) }
        }
    }
}

private enum class HeroKind { Pair, Stuck, Waking, NoSleep, Midday, Window, After, Evening, During }

/**
 * The hero's recipe, L4 (spec 2.1): the rim at full from the one side the moment places, no bevel, and the dark core
 * opposite the light. [rim] raises or calms it for the moment (spec 4.2: 120% before a workout, 80% after one). The
 * Moment widget lights its background with the same recipe (widgets spec 3.1).
 */
internal fun heroStyle(color: Color, lightAngle: Float?, rim: Float = 1f) = GlowStyle.Bloom.copy(
    lightAngle = lightAngle, color = color,
    coreShape = CoreShape.Radial, coreX = 0.5f, coreY = 0.6f, coreWidth = 0.95f, coreHeight = 0.75f,
    coreBlur = 30.dp, toneWidth = 16.dp, toneShift = -10f, glowStrength = rim, bevel = 0f,
)

/** The workout window's rim, raised for effort, and the calmer one after a workout (spec 4.2). */
internal const val WINDOW_RIM = 1.2f
internal const val AFTER_RIM = 0.8f
internal const val EVENING_BODY = 0.46f

/**
 * Waking up: how you slept and what Recovery says, the score in dots with its word, and Charge at waking as text
 * beside it (one dotted number a card, spec 2.3). Recovery's one teal, lit from the lower left (spec 4.2). Without
 * OpenStrap's own score the strap's stands in, labelled as its estimate.
 */
@Composable
private fun WakingHero(ctx: TodayContext, lightAngle: Float?, onOpen: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    ctx.recoveryFailure?.let { return FailedHero(it, lightAngle, onOpen, onRetry, modifier) }
    val score = ctx.score
    val shown = ctx.recoveryShown
    val before = ctx.rec?.readinessTrend?.dropLast(1)?.lastOrNull()?.second
    val atWake = ctx.rec?.charge?.takeIf { it.anchored }?.atWake?.toInt()
    GlowCard(heroStyle(MetricColors.readiness, lightAngle), modifier, onClick = onOpen) {
        Column {
            GlowHeader(
                "Recovery", trailing = score?.let { org.openstrap.app.ui.dashboard.TabCopy.vsYesterday(it, before) },
                tag = if (ctx.firstScore && score != null) TitleTag(FIRST_SCORE) else null,
            )
            if (ctx.state.loaded) HeroSentence(TodayCopy.waking(ctx.state.asleepSeconds, score, ctx.alert, ctx.noScore, ctx.firstNights, ctx.isToday, deleted = ctx.state.deletedSleep != null))
            // A hero leaves out a figure it doesn't have rather than show a lone dash; the sentence says why.
            if (shown.value == null && atWake == null) return@Column
            Spacer(Modifier.height(24.dp))
            HeroNumber(
                shown.value?.toString(),
                listOfNotNull(shown.value?.let { shown.word ?: ctx.waiting }, atWake?.let { "Energy at waking $it" }),
            )
        }
    }
}

/** Today's Recovery failed (E5): yesterday's score dimmed, one line, one action. Never waiting forever. */
@Composable
private fun FailedHero(f: RecoveryFailure, lightAngle: Float?, onOpen: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    GlowCard(heroStyle(MetricColors.readiness, lightAngle), modifier, onClick = onOpen) {
        Column {
            GlowHeader("Recovery")
            HeroSentence(RECOVERY_FAILED)
            f.yesterday?.let { y ->
                Spacer(Modifier.height(24.dp))
                HeroFigure(y.toString(), "Yesterday", big = true, modifier = Modifier.alpha(FAILED_ALPHA))
            }
            Spacer(Modifier.height(24.dp))
            HeroButton(TRY_AGAIN, onRetry)
        }
    }
}

/**
 * Midday: Charge as a light level, what the day took and what's left: Charge now in dots, recharged and drained as text
 * beside it. With [noSleep] it leads the morning too, saying no sleep was recorded (spec 4.3).
 */
@Composable
private fun ChargeHero(ctx: TodayContext, onOpen: () -> Unit, onRetry: () -> Unit, modifier: Modifier, noSleep: Boolean = false) {
    val c = ctx.rec?.charge
    val now = ctx.charge
    var info by rememberSaveable { mutableStateOf(false) }
    // Charge is lit to its level from below (spec 2.5), so the moment's angle gives way here.
    GlowCard(GlowStyle.Ember.copy(lightLevel = (now ?: 0) / 100f), modifier, onClick = onOpen) {
        Column {
            val start = c?.takeIf { it.anchored }?.atWake?.toInt()
            GlowHeader(
                "Energy", trailing = start?.let { if (c.strapOffOvernight) "$it at start" else "$it at waking" },
                marker = ctx.upTo(ctx.newest), tag = ctx.partial,
            )
            // Charge is worked out with Recovery, so when that fails it says so rather than waiting for a first night.
            val failed = ctx.recoveryFailure != null
            HeroSentence(
                when {
                    failed -> CHARGE_FAILED
                    noSleep -> if (ctx.state.deletedSleep != null) TodayCopy.deletedNight(ctx.isToday) else TodayCopy.NO_SLEEP
                    else -> TodayCopy.charge(now, c?.let { org.openstrap.app.ui.dashboard.TabCopy.drainedAmount(it.drainedExercise + it.drainedStress + it.drainedLiving).toInt() }, c?.anchored == true, LocalTime.now().hour)
                },
            )
            if (failed) {
                Spacer(Modifier.height(24.dp))
                HeroButton(TRY_AGAIN, onRetry)
                return@Column
            }
            // The caveat marker: tapping it opens Charge's info sheet, which explains the estimated start. One caveat a
            // card (spec 7.1): a Partial day tag, or the no sleep sentence, already says the day is short of wear.
            if (c?.strapOffOvernight == true && !noSleep && ctx.partial == null) Text(
                "Strap off overnight, so today started from an estimate.",
                color = Color.White.copy(alpha = 0.7f), style = GlowType.secondary,
                modifier = Modifier.padding(top = 8.dp).clickable(onClickLabel = "About Energy") { info = true },
            )
            // No lone dash in a hero: before the first night the sentence says Charge starts after it.
            if (now == null) return@Column
            Spacer(Modifier.height(24.dp))
            HeroNumber(
                now.toString(),
                if (c != null && c.anchored) listOf(
                    "Recharged ${c.recharged.toInt()}",
                    // After "Drained" the amount needs no minus.
                    "Drained ${org.openstrap.app.ui.dashboard.TabCopy.drainedAmount(c.drainedExercise + c.drainedStress + c.drainedLiving)}",
                ) else listOf("Now"),
            )
        }
    }
    if (info) InfoSheet(
        org.openstrap.app.ui.dashboard.InfoCopy.of(org.openstrap.app.ui.dashboard.Score.Charge), MetricColors.charge,
        now?.let(org.openstrap.app.ui.dashboard.InfoCopy::chargeBar), now?.let { org.openstrap.app.ui.dashboard.InfoCopy.HERE },
        onDismiss = { info = false }, notes = org.openstrap.app.ui.dashboard.InfoCopy.chargeNotes(c),
    )
}

/**
 * Your usual workout time: whether you're ready for effort, and a way to start the sport you usually do now. Heart red
 * from the upper right with the rim raised (spec 4.2); Recovery is a note on the title row, not the hero's colour.
 */
@Composable
private fun WindowHero(ctx: TodayContext, lightAngle: Float?, onStart: (Int) -> Unit, onOther: () -> Unit, modifier: Modifier) {
    val sport = ctx.usualSport
    val noun = sportNoun(sport)
    GlowCard(heroStyle(MetricColors.heart, lightAngle, rim = WINDOW_RIM), modifier) {
        Column {
            GlowHeader(TodayCopy.windowTitle(ctx.score), trailing = ctx.score?.let { "Recovery $it" })
            HeroSentence(TodayCopy.window(noun, ctx.charge))
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroButton("Start ${noun ?: Workouts.sportName(sport).lowercase()}", { onStart(sport) }, Modifier.weight(1f), icon = true)
                HeroSecondaryButton("Other sport", onOther)
            }
        }
    }
}

/**
 * After a workout: how long since, and the heart coming down, drawn from the readings since it ended. The heart rate
 * now in dots, the end and resting rates as text beside it. Calm light from below, the rim at 80% (spec 4.2).
 */
@Composable
private fun AfterHero(ctx: TodayContext, lightAngle: Float?, onOpenWorkout: (Long) -> Unit, modifier: Modifier) {
    val w = ctx.lastWorkout ?: return
    val since = ctx.state.heartRate.filter { it.x >= w.end }
    val nowHr = ctx.live ?: since.lastOrNull()?.y?.toInt()
    val noun = sportNoun(w.workout.sportType) ?: "workout"
    GlowCard(heroStyle(MetricColors.heart, lightAngle, rim = AFTER_RIM), modifier, onClick = { onOpenWorkout(w.workout.start) }) {
        Column {
            GlowHeader("Since your $noun", trailing = TodayCopy.duration(ctx.now - w.end))
            HeroSentence(TodayCopy.after(nowHr, ctx.state.restingHeartRate))
            // The drop since the end, from the readings the strap has sent. A live curve needs live readings kept
            // through the calm down (step 7).
            if (since.size >= 2) {
                // About 40 points over whatever time has passed, and never narrower than the strap's usual gap between
                // readings (it keeps one every few minutes outside a workout): a slot with no reading between two that
                // have one breaks the line into lone dots.
                val gaps = since.zipWithNext { a, b -> b.x - a.x }.filter { it > 0 }.sorted()
                val typical = gaps.getOrNull(gaps.size / 2) ?: 0L
                // Half as wide again, so a reading a little late still lands in its own slot.
                val step = maxOf(60L, (ctx.now - w.end) / 40, typical * 3 / 2)
                val values = remember(since, w.end, step) { bucket(since, w.end..ctx.now, step) }
                Spacer(Modifier.height(14.dp))
                // The line drops into your resting range, a dim band, once it's known.
                GlowLineChart(values, height = 64.dp, showAverage = false, band = ctx.restingRange?.let { it.low..it.high })
            }
            Spacer(Modifier.height(18.dp))
            // No readings around the end give no end heart rate: leave it out rather than show a dash.
            val then = listOfNotNull(w.endHeartRate?.let { "$it at the end" }, ctx.state.restingHeartRate?.let { "resting $it" })
                .joinToString(", ").replaceFirstChar { it.uppercase() }
            HeroNumber(nowHr?.toString(), listOfNotNull(nowHr?.let { "bpm now" }, then.ifEmpty { null }))
        }
    }
}

/**
 * Evening and night: the day in a sentence (Load in it), the steps in dots, Energy left under them only when
 * [energyHidden] (its card, normally right under the hero, is hidden in Edit Today), and how
 * long until your usual bedtime. Evening violet from the lower right in the evening, Sleep blue from below at night
 * (spec 4.2), each in its mood.
 */
@Composable
private fun EveningHero(ctx: TodayContext, lightAngle: Float?, bedtime: LocalTime?, energyHidden: Boolean, onOpen: () -> Unit, modifier: Modifier) {
    val goals = LocalGoals.current
    val nouns = ctx.dayWorkouts.map { sportNoun(it.workout.sportType) }
    val night = ctx.moment == Moment.Night
    val style = heroStyle(if (night) MetricColors.sleepDeep else MetricColors.evening, lightAngle)
    // The evening's violet is lit lighter so it reads as violet, not as a second night (spec 4.2).
    GlowCard(if (night) style else style.copy(bodyLightness = EVENING_BODY), modifier, onClick = onOpen) {
        Column {
            GlowHeader("Today", trailing = TodayCopy.bedtimeIn(LocalTime.now(), bedtime), marker = ctx.upTo(ctx.newest), tag = ctx.partial)
            // The count is in dots below, so the sentence says the steps against the goal instead.
            HeroSentence(TodayCopy.evening(nouns, ctx.state.steps, goals.steps, ctx.loadStatus, stepCount = false))
            Spacer(Modifier.height(24.dp))
            HeroNumber("%,d".format(ctx.state.steps), listOf("steps"))
            // Load is said in the sentence, and Energy has its own card right under the hero. Only with that card
            // hidden in Edit Today does Energy come back here, last and full width, never a lone dash.
            val energy = ctx.charge?.takeIf { energyHidden }
            if (energy != null) Text(
                "Energy $energy left", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                maxLines = 1, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * During a workout: the live heart rate, number first, and the way back to the live screen. Lit from below in the
 * current zone's colour, pulsing at the real bpm (spec 4.2).
 */
@Composable
private fun DuringHero(
    ctx: TodayContext, r: org.openstrap.app.device.WorkoutState.Recording, onOpen: () -> Unit, onResume: () -> Unit, modifier: Modifier,
) {
    val live = ctx.live
    val zone = live?.let { zoneColor(it, r.maxHeartRate) } ?: MetricColors.heart
    val style = heroStyle(zone, LightAngle.Below).let { if (live != null) it.copy(motion = GlowMotion.Heartbeat(live)) else it }
    GlowCard(style, modifier, onClick = onOpen) {
        Column {
            GlowHeader(Workouts.sportName(r.workout.sportType))
            Spacer(Modifier.height(18.dp))
            HeroFigure(live?.toString(), if (live != null) "bpm, live" else "Waiting for heart rate", big = true)
            Spacer(Modifier.height(28.dp))
            WorkoutShortcut(r, live, onOpen, onResume)
        }
    }
}

/**
 * No strap paired, or connecting for the first time: the pairing stage (E3). One sentence per [step], the strap still
 * and lit, the line to the phone changing under it, and one firm tick when it connects (spec 3.6). Find my strap opens
 * the Strap page, where pairing is done.
 */
@Composable
private fun PairHero(step: PairStep, onPair: () -> Unit, modifier: Modifier) {
    val haptics = rememberHaptics()
    LaunchedEffect(step) { if (step == PairStep.Connected) haptics.perform(HapticEvent.PairingDone) }
    GlowCard(heroStyle(Color(0xFF4E8F86), LightAngle.Below), modifier, onClick = onPair) {
        Column {
            GlowHeader("Pair your Helio Strap")
            HeroSentence(pairSentence(step))
            Spacer(Modifier.height(18.dp))
            PairingStage(step)
            if (step != PairStep.Connected) {
                Spacer(Modifier.height(18.dp))
                HeroButton("Find my strap", onPair)
            }
        }
    }
}

/**
 * The sync is stuck when it matters (spec 4.3): failing in the morning with last night still on the strap, or failing
 * for hours. One action, and when it was last tried.
 */
@Composable
private fun StuckHero(ctx: TodayContext, strap: TodayStrap, onSyncNow: () -> Unit, modifier: Modifier) {
    val haptics = rememberHaptics()
    val copy = stuckCopy(ctx.state.asleepSeconds != null, ctx.newest?.let { clockText(it, ctx.zone) })
    GlowCard(heroStyle(MetricColors.warn, LightAngle.Below), modifier) {
        Column {
            GlowHeader(copy.title, trailing = lastTried(strap.record, ctx.zone))
            HeroSentence(copy.sentence)
            Text(copy.line, color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(28.dp))
            HeroButton("Sync now", { if (strap.canSync) onSyncNow() else haptics.perform(HapticEvent.SyncFailed) })
        }
    }
}

/* ----------------------------------------------- Rows ----------------------------------------------- */

@Composable
private fun TodayRow(
    slot: TodaySlot, ctx: TodayContext, healthspan: org.openstrap.app.data.Healthspan.Outcome?, history: DailyHistory?,
    checkIn: Int?, onCheckIn: (Int?) -> Unit, onOpenMetric: (Metric) -> Unit,
    onOpenWorkout: (Long) -> Unit, onOpenHealthspan: () -> Unit, onOpenProfile: () -> Unit,
    offer: org.openstrap.app.data.AutoStart.Offer?, onRecordOffer: () -> Unit, onDeclineOffer: () -> Unit,
    suggestion: org.openstrap.app.data.WorkoutSuggestions.Suggestion?, onSaveSuggestion: () -> Unit, onDismissSuggestion: () -> Unit,
    onOpen: (org.openstrap.app.ui.dashboard.Detail) -> Unit, usualSteps: List<Float>?, onRetryRecovery: () -> Unit,
    /** The size a Small takes in its row. */
    modifier: Modifier = Modifier,
    /** Opens Ready with a sport, for Start a workout. */
    onStartSport: (Int) -> Unit = {},
) {
    val s = ctx.state
    // Your normal over 30 days, as the Recovery tab's body signals measure against, so both say the same.
    @Composable fun normal(m: Metric) = rememberNormal(m, s.date, history)
    val openScore = { score: org.openstrap.app.ui.dashboard.Score -> onOpen(org.openstrap.app.ui.dashboard.Detail.OfScore(score)) }
    // Last night opens the Sleep score's detail, as the Sleep tab's hero does (spec 3.3).
    val onOpenSleep = { openScore(org.openstrap.app.ui.dashboard.Score.Sleep) }
    when (slot.card) {
        TodayCard.Offer -> offer?.let { OfferPanel(it, onRecordOffer, onDeclineOffer, edge = EdgeKind.Live) }
        TodayCard.Suggestion -> suggestion?.let { SuggestionPanel(it, onSaveSuggestion, onDismissSuggestion, edge = EdgeKind.Earlier) }
        TodayCard.Alerts -> AlertsStrip(s.alerts, onOpenMetric)
        TodayCard.CheckIn -> CheckInStrip(checkIn, onCheckIn)
        TodayCard.BodyMorning -> BodyList(
            "Your body this morning",
            listOf(
                BodyRow("Resting heart rate", MetricColors.heart, s.restingHeartRate?.toFloat(), normal(Metric.RestingHr), "", Metric.RestingHr),
                BodyRow("HRV", MetricColors.hrv, s.hrv.avg()?.toFloat(), normal(Metric.Hrv), "", Metric.Hrv),
                BodyRow("Blood oxygen", MetricColors.spo2, s.spo2.avg()?.toFloat(), normal(Metric.Spo2), "%", Metric.Spo2),
                BodyRow("Breathing rate", MetricColors.sleepLight, s.breathRate.avg()?.toFloat(), normal(Metric.Breathing), "", Metric.Breathing),
            ),
            ctx.waiting, onOpenMetric,
        )
        TodayCard.RightNow -> BodyList(
            "Right now",
            listOf(
                BodyRow("Stress", MetricColors.stress, s.stress.lastOrNull()?.y, normal(Metric.Stress), "", Metric.Stress),
                BodyRow("HRV", MetricColors.hrv, s.hrv.lastOrNull()?.y, normal(Metric.Hrv), "", Metric.Hrv),
            ),
            ctx.waiting, onOpenMetric, upTo = ctx.upTo(listOfNotNull(s.stress.lastOrNull(), s.hrv.lastOrNull()).maxOfOrNull { it.x }),
        )
        TodayCard.LastNight ->
            if (slot.size == CardSize.Strip) LastNightStrip(s, onOpenSleep, stuck = ctx.stuck, firstNights = ctx.firstNights)
            else LastNightCard(s, ctx.firstNights, onOpenSleep, stuck = ctx.stuck)
        // A live reading is never stale.
        TodayCard.HeartRate -> HeartCard(
            s, ctx.live, { onOpenMetric(Metric.HeartRate) }, restingRange = ctx.restingRange,
            upTo = if (ctx.live == null) ctx.upTo(s.heartRate.lastOrNull()?.x) else null,
        )
        // Steps come in the same records as the rest of the day, so the day's newest reading is how far they go.
        // In the evening and at night the hero has the day's steps in dots, so the card leads with its sentence.
        TodayCard.Activity -> ActivityCard(
            s, { onOpenMetric(Metric.Steps) }, usual = usualSteps, upTo = ctx.upTo(ctx.newest), tag = ctx.partial,
            count = !ctx.stepsInHero,
        )
        TodayCard.Recovery -> RecoveryEarlierCard(ctx.recoveryShown, { openScore(org.openstrap.app.ui.dashboard.Score.Recovery) }, failure = ctx.recoveryFailure, onRetry = onRetryRecovery)
        TodayCard.Charge -> {
            val c = ctx.rec?.charge
            // What the day drained, gross, as the hero, Recovery's Drained Small and the Charge detail say it.
            val drained = c?.let { it.drainedExercise + it.drainedStress + it.drainedLiving }
            val status = when {
                // Worked out with Recovery: when that failed, say so here too. The card opens Recovery and its Try again.
                ctx.charge == null -> if (ctx.recoveryFailure != null) CHARGE_FAILED else ctx.waiting
                ctx.endOfDay -> "Sleep fills it back"
                drained != null && drained >= 1f -> "Drained ${org.openstrap.app.ui.dashboard.TabCopy.drainedAmount(drained)}"
                else -> "Steady since waking"
            }
            // The day's Charge so far beside the number, in the evening too, as on every other Charge card.
            val soFar = c?.takeIf { it.anchored && ctx.isToday }?.let { chargeSoFar(it.points, s.dayStart..s.dayEnd, ctx.now) }
            ChargeWideCard(ctx.charge, status, slot.secondGlow, { openScore(org.openstrap.app.ui.dashboard.Score.Charge) }, soFar = soFar, upTo = ctx.upTo(ctx.newest), tag = ctx.partial)
        }
        // After a workout, what it did as a Small pair: Load toward your usual (from below) and time to recover.
        TodayCard.LoadToday -> if (slot.size == CardSize.Small) SmallCard(
            MetricColors.load, "Load", ctx.rec?.load?.let { Math.round(it).toString() }, null,
            when { ctx.recoveryFailure != null -> FAILED_SHORT; ctx.rec == null -> ctx.waiting; else -> TodayCopy.loadWord(ctx.loadStatus) },
            modifier, kind = EdgeKind.Progress, progress = ctx.loadShare,
            edgeStatus = if (ctx.loadStatus == TodayCopy.LoadStatus.Above) EdgeStatus.Above else EdgeStatus.Usual,
            onClick = { openScore(org.openstrap.app.ui.dashboard.Score.Load) },
        ) else LoadList(
            when (ctx.moment) {
                Moment.WorkoutWindow -> "Before you start"
                Moment.After -> "What the ${ctx.lastWorkout?.let { sportNoun(it.workout.sportType) } ?: "workout"} did"
                else -> "Load today"
            },
            ctx.rec?.load, TodayCopy.loadLine(ctx.loadStatus), ctx.recover,
            onLoad = { openScore(org.openstrap.app.ui.dashboard.Score.Load) }, onRecover = { openScore(org.openstrap.app.ui.dashboard.Score.Recover) },
        )
        TodayCard.Recover -> {
            val left = ctx.workouts.filter { it.recoveryMinutes != null }.maxByOrNull { it.end }
                ?.let { w -> TodayCopy.recoverShort(TodayCopy.recoverLeft(w.end, w.recoveryMinutes ?: 0, ctx.now), ctx.now, ctx.zone) }
            SmallCard(
                MetricColors.load, "To recover", left?.first, "h", left?.second ?: "No estimate from the strap", modifier,
                onClick = { openScore(org.openstrap.app.ui.dashboard.Score.Recover) },
            )
        }
        TodayCard.Workout -> ctx.lastWorkout?.let { w -> WorkoutCard(w, { onOpenWorkout(w.workout.start) }) }
        TodayCard.Week -> WeekCard(s, ctx.workouts, history, onClick = { onOpen(org.openstrap.app.ui.dashboard.Detail.Week) })
        // Habit Age's home is the foot of Recovery; Today shows the same card when there's a new estimate, or pinned.
        TodayCard.HabitAge -> HabitAgeEdgeCard(healthspan, onOpenHealthspan, onOpenProfile)
        TodayCard.StartWorkout -> StartWorkoutStrip(ctx.usualSport, onStartSport)
        TodayCard.Hero, TodayCard.EditToday -> Unit
    }
}

/* ---------------------------------------------- Helpers --------------------------------------------- */

/** The day's Charge in quarter hours across [day], up to [now]; the quarters still to come are null. */
internal fun chargeSoFar(points: List<Recovery.ChargePoint>, day: LongRange, now: Long): List<Float?> {
    val step = 15 * 60L
    val n = ((day.last - day.first + 1) / step).toInt().coerceAtLeast(2)
    val sums = FloatArray(n)
    val counts = IntArray(n)
    points.forEach { val i = ((it.t - day.first) / step).toInt(); if (i in 0 until n && it.t <= now) { sums[i] += it.value; counts[i]++ } }
    return List(n) { if (counts[it] == 0) null else sums[it] / counts[it] }
}

/** Averages timed points into evenly spaced buckets across [range]; empty buckets are null (gaps). */
internal fun bucket(points: List<ChartPoint>, range: LongRange, seconds: Long): List<Float?> {
    val n = ((range.last - range.first + 1) / seconds).toInt().coerceAtLeast(2)
    val sums = FloatArray(n)
    val counts = IntArray(n)
    points.forEach {
        val i = ((it.x - range.first) / seconds).toInt()
        if (i in 0 until n) { sums[i] += it.y; counts[i]++ }
    }
    return List(n) { if (counts[it] == 0) null else sums[it] / counts[it] }
}
