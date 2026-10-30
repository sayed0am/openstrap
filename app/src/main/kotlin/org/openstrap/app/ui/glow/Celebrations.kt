package org.openstrap.app.ui.glow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.Rewards
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.RewardsCopy

/*
 * The celebrations (workouts spec 3.4, boards GoalMoment and BadgeUnlocked): the full screen goal moment, once a week,
 * the full screen card for a personal best (never for a milestone), the badges past history earned, shown once on
 * one screen, and the two switches that turn the goal moment and the weekly recap off. No other pop ups.
 */

/** Each group of badges in its own colour, so the art tells them apart. */
internal fun badgeColor(group: Rewards.Group): Color = when (group) {
    Rewards.Group.Firsts -> MetricColors.steps
    Rewards.Group.Milestones -> MetricColors.calories
    Rewards.Group.Weekly -> MetricColors.good
    Rewards.Group.Kinds -> MetricColors.load
}

/** A personal best's colour: the calories' gold. */
internal val BestColor = MetricColors.calories

/**
 * A badge's art in the glow language: a round glow in [color] lit from below, a hairline rim, and its [short] text.
 * [dots] puts the text in dots (the one dotted number on its screen); a [locked] badge is dim and unlit.
 */
@Composable
internal fun BadgeArt(short: String, color: Color, size: Dp, modifier: Modifier = Modifier, locked: Boolean = false, dots: Boolean = false, under: String? = null) {
    Box(
        modifier.size(size).alpha(if (locked) 0.35f else 1f)
            .background(
                if (locked) Brush.radialGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f)))
                else Brush.verticalGradient(listOf(Color.Black, color.copy(alpha = 0.55f))),
                CircleShape,
            )
            .border(1.dp, if (locked) Color.White.copy(alpha = 0.12f) else color.copy(alpha = 0.8f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (dots) DotMatrixText(short, dot = DotSize.Hero)
            else Text(short, color = Color.White, style = if (size >= 80.dp) GlowType.pageTitle else GlowType.cardTitle, maxLines = 1)
            under?.let { Text(it, color = Color.White, style = GlowType.body, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

/** The close button in a celebration's top corner. */
@Composable
private fun CloseRow(title: String?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.orEmpty(), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.weight(1f).padding(start = 4.dp))
        RoundButton(AppIcons.X, "Close", onClose, size = 40.dp)
    }
}

/**
 * The full screen card for a personal best (board BadgeUnlocked): the best's art with its value, what it is, how far it
 * beat the last one and when it was set. Share comes with the share screen; Continue goes on.
 */
@Composable
fun BestCardScreen(best: Rewards.NewBest, sportName: String, contentPadding: PaddingValues, onContinue: () -> Unit) {
    BackHandler(onBack = onContinue)
    val now = System.currentTimeMillis() / 1000
    WorkoutPage(BestColor, contentPadding, pinned = { WorkoutButton("Continue", Color.White, Color.Black, onContinue) }) {
        CloseRow("Personal best", onContinue)
        Spacer(Modifier.height(24.dp))
        BadgeArt(
            RewardsCopy.bestShort(best.best.kind), BestColor, 200.dp, Modifier.align(Alignment.CenterHorizontally), dots = true,
            under = RewardsCopy.bestValue(best.best.kind, best.best.value),
        )
        Spacer(Modifier.height(12.dp))
        Text(RewardsCopy.bestTitle(best.best.kind), color = Color.White, style = GlowType.pageTitle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(RewardsCopy.beatLine(best, now), color = Color.White, style = GlowType.sentence, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            RewardsCopy.whenSet(best.best.date, sportName), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The goal moment (board GoalMoment), once a week after the summary of the workout that met the goal: "4/4" in dots,
 * the weeks in a row, the week's days, and Continue, with Celebrations settings under it.
 */
@Composable
fun GoalMomentScreen(count: Int, goal: Int, inARow: Int, weekStarts: List<Long>, contentPadding: PaddingValues, onSettings: () -> Unit, onContinue: () -> Unit) {
    BackHandler(onBack = onContinue)
    WorkoutPage(
        MetricColors.good, contentPadding,
        pinned = {
            WorkoutButton("Continue", Color.White, Color.Black, onContinue)
            PageTextButton("Celebrations settings", onSettings, Modifier.align(Alignment.CenterHorizontally))
        },
    ) {
        CloseRow(null, onContinue)
        Spacer(Modifier.height(32.dp))
        DotMatrixText("$count/$goal", Modifier.align(Alignment.CenterHorizontally), dot = DotSize.Hero, color = MetricColors.good)
        Text("Weekly goal met", color = Color.White, style = GlowType.pageTitle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Text(RewardsCopy.momentLine(inARow), color = Color.White, style = GlowType.sentence, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        WeekDaysPanel(weekStarts, note = "Shown once a week, when you reach your goal.")
    }
}

/**
 * The celebration settings and the week the goal moment last showed for, with what changes them, handed to the
 * summary so it can follow itself with the goal moment.
 */
class Celebrations(
    val goalMoment: Boolean,
    val weeklyRecap: Boolean,
    /** The week (epoch day) the goal moment last showed for. */
    val momentWeek: Long?,
    val setGoalMoment: (Boolean) -> Unit,
    val setWeeklyRecap: (Boolean) -> Unit,
    val markMoment: (Long) -> Unit,
)

/** The two celebration switches (workouts spec 3.4), in a sheet. */
@Composable
fun CelebrationsSheet(goalMoment: Boolean, weeklyRecap: Boolean, onGoalMoment: (Boolean) -> Unit, onWeeklyRecap: (Boolean) -> Unit, onDismiss: () -> Unit) {
    PageSheet(onDismiss, "Celebrations", note = "No streaks to keep and no nudges. Just these two.") {
        PageRows {
            PageSwitchRow("Weekly goal moment", "A full screen once a week, when you reach your goal", goalMoment, onGoalMoment, divider = false)
            PageSwitchRow("Weekly recap", "One notification on Monday morning about your week", weeklyRecap, onWeeklyRecap)
        }
        PageButton("Done", onDismiss, Modifier.fillMaxWidth(), primary = true)
    }
}

/**
 * The badges past history earned, shown once on one screen when the app first works them out (requests spec 7.2),
 * rather than one by one.
 */
@Composable
fun HistoryBadgesScreen(badges: List<Rewards.Badge>, contentPadding: PaddingValues, onContinue: () -> Unit) {
    BackHandler(onBack = onContinue)
    WorkoutPage(MetricColors.good, contentPadding, pinned = { WorkoutButton("Continue", Color.White, Color.Black, onContinue) }) {
        CloseRow(null, onContinue)
        Text("From your workouts so far", color = Color.White, style = GlowType.pageTitle, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        Text(
            "${badges.size} ${if (badges.size == 1) "badge" else "badges"} already earned. New ones show on the summary after a workout.",
            color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.padding(start = 4.dp),
        )
        BadgeGrid(badges.map { it to null })
    }
}

/** Badges three to a row, each its art and title; [note] under the title is the date earned or what it needs. */
@Composable
internal fun BadgeGrid(badges: List<Pair<Rewards.Badge, String?>>, locked: (Rewards.Badge) -> Boolean = { false }) {
    QuietPanel {
        badges.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (b, note) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BadgeArt(b.short, badgeColor(b.group), 64.dp, locked = locked(b))
                        Text(b.title, color = Color.White, style = GlowType.secondary, textAlign = TextAlign.Center, maxLines = 2)
                        note?.let { Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.caption, textAlign = TextAlign.Center) }
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
