package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.Structure
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.SportIcons
import org.openstrap.app.ui.workout.StartCopy

/*
 * Ready for strength, HIIT, mind and body and games (boards ReadyFinalStrength, ReadyFinalHiit, ReadyFinalMind,
 * ReadyFinalGames), on the run Ready's skeleton: the heart card, one plan card ([ReadyPlanCard]) and a few switch
 * rows ([ReadyRows]).
 */

/**
 * Strength's plan: Open or Plan in the pill. A plan is "5 sets" over "with 90 s rest between", each number typed in
 * place, then the sets presets. Open has no sets to count but still rests after each one, so it keeps the rest.
 */
@Composable
internal fun StrengthPlanCard(
    type: Int,
    sets: Int?,
    onSets: (Int?) -> Unit,
    planSets: Int,
    restSeconds: Long,
    onRest: (Long) -> Unit,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
) {
    val options = listOf(
        PlanOption(SportIcons.of(type), StartCopy.OPEN, StartCopy.OPEN_SETS_KIND_NOTE),
        PlanOption(AppIcons.ChartBar, StartCopy.PLAN, StartCopy.PLAN_KIND_NOTE),
    )
    val at = if (sets == null) 0 else 1
    ReadyPlanCard(Workouts.Family.Strength, options, options[at], at, { onSets(if (it == 0) null else planSets) }, typing, onTyping) {
        val (restN, restU) = StartCopy.restParts(restSeconds)
        val rest = NumberField(restN, restU, edit = "$restSeconds", editUnit = "s", style = GlowType.goalValueMinor)
        if (sets == null) {
            TypedValue(listOf(rest), typing, onTyping, onDone = { t -> StartCopy.parseSetRest(t[0])?.let(onRest) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Number(0)
                    ValueWords("rest after each set")
                }
            }
            GoalNote(if (typing) StartCopy.STRENGTH_TYPING else StartCopy.OPEN_SETS_NOTE)
            return@ReadyPlanCard
        }
        TypedValue(
            listOf(NumberField("$sets", "sets", length = 2), rest), typing, onTyping,
            onDone = { t ->
                StartCopy.parseSets(t[0])?.let(onSets)
                StartCopy.parseSetRest(t[1])?.let(onRest)
            },
        ) {
            Row { Number(0) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ValueWords("with")
                Number(1)
                ValueWords("rest between")
            }
        }
        if (!typing) Presets(StartCopy.SET_PRESETS, sets) { onSets(it) }
        GoalNote(if (typing) StartCopy.STRENGTH_TYPING else StartCopy.STRENGTH_STATUS)
    }
}

/**
 * HIIT's plan: the presets are the pill (Tabata, 40 / 20, Every minute, Free); once a number is typed the pill says
 * what it is ("30 / 15"). Timed rounds read "20 s on 10 s off" over "8 rounds 4:00 in all" with work and rest in
 * proportion under them; Every minute reads "60 s a round" over its rounds. Free has no timer: laps mark the rounds.
 */
@Composable
internal fun HiitPlanCard(type: Int, rounds: Structure?, onChange: (Structure?) -> Unit, typing: Boolean, onTyping: (Boolean) -> Unit) {
    val icons = listOf(AppIcons.Timer, AppIcons.Hourglass, AppIcons.Alarm, SportIcons.of(type))
    val options = StartCopy.PRESETS.mapIndexed { i, (_, name, note) -> PlanOption(icons[i], name, note) }
    val at = StartCopy.PRESETS.indexOfFirst { it.first?.code == rounds?.warm(false)?.code }.takeIf { it >= 0 }
    val shown = at?.let { options[it] } ?: PlanOption(if (rounds is Structure.EveryMinute) AppIcons.Alarm else AppIcons.Timer, StartCopy.hiitName(rounds!!), "")
    ReadyPlanCard(
        Workouts.Family.Intervals, options, shown, at, { onChange(StartCopy.PRESETS[it].first?.warm(rounds?.warmUp == true)) }, typing, onTyping,
    ) {
        when (rounds) {
            is Structure.Timed -> {
                TypedValue(
                    listOf(
                        NumberField("${rounds.work}", "s on"), NumberField("${rounds.rest}", "s off"),
                        NumberField("${rounds.rounds}", "rounds", length = 2, style = GlowType.goalValueMinor),
                    ),
                    typing, onTyping, onDone = { onChange(StartCopy.typedTimed(rounds, it)) },
                ) {
                    val now = typed?.let { StartCopy.typedTimed(rounds, it) } ?: rounds
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Number(0)
                        Number(1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Number(2)
                        StartCopy.totalLine(now)?.let { ValueWords(it) }
                    }
                }
                if (!typing) RoundsStrip(rounds)
                GoalNote(if (typing) StartCopy.HIIT_TYPING else StartCopy.INTERVALS_STATUS)
            }
            is Structure.EveryMinute -> {
                TypedValue(
                    listOf(NumberField("${rounds.period}", "s a round"), NumberField("${rounds.rounds}", "rounds", length = 2, style = GlowType.goalValueMinor)),
                    typing, onTyping, onDone = { onChange(StartCopy.typedEveryMinute(rounds, it)) },
                ) {
                    val now = typed?.let { StartCopy.typedEveryMinute(rounds, it) } ?: rounds
                    Row { Number(0) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Number(1)
                        StartCopy.totalLine(now)?.let { ValueWords(it) }
                    }
                }
                GoalNote(if (typing) StartCopy.EVERY_TYPING else StartCopy.INTERVALS_STATUS)
            }
            else -> GoalNote(StartCopy.HIIT_FREE_NOTE)
        }
    }
}

/** Mind and body's length: Open or Time in the pill; a time is the run's Time card with its own presets and note. */
@Composable
internal fun MindPlanCard(type: Int, seconds: Long?, onSeconds: (Long?) -> Unit, lastSeconds: Long, typing: Boolean, onTyping: (Boolean) -> Unit) {
    val options = listOf(
        PlanOption(SportIcons.of(type), StartCopy.OPEN, StartCopy.MIND_OPEN_KIND_NOTE),
        PlanOption(AppIcons.Timer, StartCopy.TIME, StartCopy.MIND_TIME_KIND_NOTE),
    )
    val at = if (seconds == null) 0 else 1
    ReadyPlanCard(Workouts.Family.MindBody, options, options[at], at, { onSeconds(if (it == 0) null else lastSeconds) }, typing, onTyping) {
        if (seconds == null) GoalNote(StartCopy.MIND_OPEN_NOTE)
        else TimeGoal(seconds, { onSeconds(it) }, typing, onTyping, StartCopy.MIND_PRESETS, StartCopy.MIND_STATUS)
    }
}

/**
 * A game's match: Free play, Halves or Quarters in the pill; periods read "2 × 40 min" over "with a 10 min break",
 * each number typed in place (in minutes). Another count of periods says so in the pill ("3 periods").
 */
@Composable
internal fun GamePlanCard(type: Int, periods: Structure.Periods?, onChange: (Structure.Periods?) -> Unit, typing: Boolean, onTyping: (Boolean) -> Unit) {
    val options = listOf(
        PlanOption(SportIcons.of(type), StartCopy.FREE_PLAY, StartCopy.FREE_PLAY_KIND_NOTE),
        PlanOption(AppIcons.Hourglass, StartCopy.periodsName(Structure.HALVES), StartCopy.HALVES_KIND_NOTE),
        PlanOption(AppIcons.Timer, StartCopy.periodsName(Structure.QUARTERS), StartCopy.QUARTERS_KIND_NOTE),
    )
    val at = when (periods?.rounds) {
        null -> 0
        Structure.HALVES.rounds -> 1
        Structure.QUARTERS.rounds -> 2
        else -> null
    }
    val shown = at?.let { options[it] } ?: PlanOption(AppIcons.Timer, StartCopy.periodsName(periods!!), "")
    ReadyPlanCard(
        Workouts.Family.Games, options, shown, at, { onChange(listOf(null, Structure.HALVES, Structure.QUARTERS)[it]) }, typing, onTyping,
    ) {
        if (periods != null) {
            TypedValue(
                listOf(
                    NumberField("${periods.rounds}", null, length = 1), NumberField("${periods.length / 60}", "min", length = 2),
                    NumberField("${periods.rest / 60}", "min", length = 2, style = GlowType.goalValueMinor),
                ),
                typing, onTyping, onDone = { onChange(StartCopy.typedPeriods(periods, it)) },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Number(0)
                    Times()
                    Number(1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ValueWords("with a")
                    Number(2)
                    ValueWords("break")
                }
            }
        }
        GoalNote(if (typing) StartCopy.GAMES_TYPING else StartCopy.gamesNote(periods))
    }
}

/** Ready's switch rows under the plan card, on a plain panel. */
@Composable
internal fun ReadyRows(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(GlowLayout.CardShape).background(ListSurface).padding(horizontal = 18.dp), content = content)
}

/** Work and rest along the rounds, each as wide as its time: work in heart red, rest in sleep blue. */
@Composable
private fun RoundsStrip(s: Structure.Timed) {
    Row(
        Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = "${s.rounds} rounds of ${s.work} seconds work and ${s.rest} seconds rest" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(s.rounds) { i ->
            Box(Modifier.weight(s.work.toFloat()).height(10.dp).clip(CircleShape).background(MetricColors.heart))
            if (i < s.rounds - 1 && s.rest > 0) Box(Modifier.weight(s.rest.toFloat()).height(10.dp).clip(CircleShape).background(MetricColors.sleepDeep))
        }
    }
}
