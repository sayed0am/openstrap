package org.openstrap.app.ui.glow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.data.Rewards
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.RewardsCopy
import org.openstrap.app.ui.workout.RewardsView

/*
 * The Goals and bests page from You (workouts spec 3.5, board AchievementsV1), All badges, and the weekly recap the
 * Monday notification opens (board WeeklyRecap).
 */

/** The goal colour: met weeks, the goal moment. */
private val GoalColor = MetricColors.good

/** What a badge's art reads on these pages: the first weekly goal shows the goal it was met at, or this week's while locked. */
private fun RewardsView.face(b: Rewards.Badge): String =
    earned.firstOrNull { it.id == b.id }?.let { RewardsCopy.face(b, Rewards.goalAt(it, weeks, firstDay, ZoneId.systemDefault())) }
        ?: RewardsCopy.face(b, goal)

/** The days of [first]'s week, each a letter and whether a workout in [view] counted on it. */
private fun weekDots(view: RewardsView, first: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, Boolean>> {
    val days = view.counted.map { Instant.ofEpochSecond(it.shownStart).atZone(zone).toLocalDate() }.toSet()
    return (0L until 7L).map { first.plusDays(it).let { d -> d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) to (d in days) } }
}

/**
 * A week against its goal on a glow lit as far as the count is toward it: the count in dots, "of 4 workouts", its days,
 * and [line] under them. [trailing] goes on the title row.
 */
@Composable
private fun GoalWeekCard(title: String, count: Int, goal: Int, dots: List<Pair<String, Boolean>>, line: String, trailing: @Composable (() -> Unit)? = null) {
    val style = GlowStyle.level(GoalColor, (count.toFloat() / goal).coerceIn(0f, 1f))
    GlowCard(style, Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, style = GlowType.cardTitle, maxLines = 1, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Row(Modifier.padding(top = GlowLayout.TitleGap), verticalAlignment = Alignment.Bottom) {
                DotMatrixText("$count", dot = DotSize.Hero)
                Text("of $goal workouts", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.padding(start = 8.dp))
            }
            WeekDots(dots, Color.White, Modifier.padding(top = 18.dp))
            Text(line, color = Color.White, style = GlowType.body, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/** The weekly goal on its own, in a sheet: the pencil on the Goals and bests page and the recap. */
@Composable
fun WeeklyGoalSheet(goal: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableIntStateOf(goal) }
    PageSheet(onDismiss, "Weekly goal", note = "A change applies to this week. Past weeks keep the goal they had.") {
        org.openstrap.app.ui.device.WorkoutGoalField(value) { value = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton("Cancel", onDismiss, Modifier.weight(1f))
            PageButton("Save", { onSave(value) }, Modifier.weight(1f), primary = true)
        }
    }
}

/**
 * Goals and bests (board AchievementsV1): this week against the goal with the weeks in a row and the best run, the
 * personal bests, the badges earned with the next one to earn, and the ways on.
 */
@Composable
fun GoalsAndBestsPage(
    view: RewardsView?,
    contentPadding: PaddingValues,
    onGoal: () -> Unit,
    onAllBadges: () -> Unit,
    onRecap: () -> Unit,
    onCelebrations: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("Goals and bests", onBack) }
        if (view == null) {
            item { Note("Loading…") }
            return@ScreenList
        }
        item {
            val week = view.week(view.thisWeek)
            GoalWeekCard(
                "This week", week?.count ?: 0, view.goal, weekDots(view, view.thisWeek), RewardsCopy.runLine(view.inARow, view.bestRun),
            ) { PageButton("Goal ${view.goal}", onGoal, icon = AppIcons.PencilEdit) }
        }
        item {
            // Lit from the left in gold, so it doesn't sit as a list right under the badges' panel's look.
            CompositionLocalProvider(LocalEdgeOverride provides EdgeOverride(edge = Edge.Left, color = BestColor)) {
                ListPanel(title = "Personal bests") {
                    val shown = view.bests.sortedBy { order(it.kind) }
                    if (shown.isEmpty()) Text("Your bests show here after your first workouts.", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(vertical = 10.dp))
                    shown.forEachIndexed { i, b ->
                        ListRow(
                            RewardsCopy.bestTitle(b.kind), BestColor, RewardsCopy.bestValue(b.kind, b.value), shortDate(b.date), divider = i > 0,
                            icon = AppIcons.FlagCheckered,
                        )
                    }
                }
            }
        }
        item {
            val earned = view.earned.sortedByDescending { it.earnedAt }.take(5).mapNotNull { r -> Rewards.badge(r.id)?.let { it to shortDate(r.earnedAt) } }
            val next = view.next?.let { b -> view.progress[b.id]?.let { (have, need) -> b to RewardsCopy.progressLine(have, need, b.unit) } }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Badges, ${view.earned.size} of ${Rewards.BADGES.size}", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
                BadgeGrid(earned + listOfNotNull(next), locked = { b -> view.earned.none { it.id == b.id } }, face = view::face)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PageButton("All badges", onAllBadges, Modifier.fillMaxWidth(), icon = AppIcons.CheckCircle)
                PageButton("Last week", onRecap, Modifier.fillMaxWidth(), icon = AppIcons.CalendarBlank)
                PageButton("Celebrations", onCelebrations, Modifier.fillMaxWidth(), icon = AppIcons.SlidersHorizontal)
            }
        }
    }
}

/** Bests in a fixed order: runs, then the longest workout, lifts, steps. */
private fun order(kind: String): Int = Rewards.Kind.fastestMeters(kind)
    ?: when {
        kind == Rewards.Kind.LONGEST_RUN -> 100_000
        kind == Rewards.Kind.LONGEST_WORKOUT -> 100_001
        Rewards.Kind.liftName(kind) != null -> 100_002
        else -> 100_003
    }

/** "30 Oct", or "30 Oct 2025" in another year. */
private fun shortDate(epoch: Long): String {
    val d = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate()
    return d.format(DateTimeFormatter.ofPattern(if (d.year == LocalDate.now().year) "d MMM" else "d MMM yyyy", Locale.getDefault()))
}

/** Every badge by group: earned ones in colour with their date, locked ones dim with what they need. */
@Composable
fun AllBadgesPage(view: RewardsView?, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("All badges", onBack, subtitle = view?.let { "${it.earned.size} of ${Rewards.BADGES.size} earned" }) }
        if (view == null) return@ScreenList
        val earned = view.earned.associateBy { it.id }
        Rewards.Group.entries.forEach { group ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        when (group) {
                            Rewards.Group.Firsts -> "Firsts"
                            Rewards.Group.Milestones -> "Milestones"
                            Rewards.Group.Weekly -> "Weekly goal"
                            Rewards.Group.Kinds -> "Something new"
                        },
                        color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                    BadgeGrid(
                        Rewards.BADGES.filter { it.group == group }.map { b ->
                            b to (earned[b.id]?.let { shortDate(it.earnedAt) } ?: view.progress[b.id]?.let { (have, need) -> RewardsCopy.progressLine(have, need, b.unit) })
                        },
                        locked = { it.id !in earned }, face = view::face,
                    )
                }
            }
        }
    }
}

/**
 * The weekly recap (board WeeklyRecap) for the week from [first]: the goal and the days, the weeks in a row, the bests
 * set that week, and its time, distance and active kcal. Next week's goal can be changed from here.
 */
@Composable
fun WeeklyRecapPage(view: RewardsView?, first: LocalDate, contentPadding: PaddingValues, onGoal: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("Your week", onBack, subtitle = RewardsCopy.weekRange(first)) }
        if (view == null) return@ScreenList
        val week = view.week(first)
        val count = week?.count ?: 0
        val goal = week?.goal ?: view.goal
        item {
            val run = Rewards.inARow(view.weeks, first)
            GoalWeekCard(
                if (week?.met == true) "Goal met" else "Weekly goal", count, goal, weekDots(view, first),
                if (run > 0) "Weekly streak: $run ${if (run == 1) "week" else "weeks"} in a row" else "${(goal - count).coerceAtLeast(0)} short of your goal",
            )
        }
        val zone = ZoneId.systemDefault()
        val from = first.atStartOfDay(zone).toEpochSecond()
        val to = first.plusDays(7).atStartOfDay(zone).toEpochSecond()
        val highlights = view.bests.filter { it.date in from until to }
        if (highlights.isNotEmpty()) item {
            ListPanel(title = "Highlights") {
                highlights.forEachIndexed { i, b ->
                    ListRow(
                        "${RewardsCopy.bestTitle(b.kind)}, ${RewardsCopy.bestValue(b.kind, b.value)}", BestColor,
                        Instant.ofEpochSecond(b.date).atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), null,
                        divider = i > 0, icon = AppIcons.FlagCheckered,
                    )
                }
            }
        }
        item {
            val mine = view.all.filter { it.startedByYou && it.shownStart in from until to }
            val seconds = mine.sumOf { it.activeSeconds }
            val km = mine.sumOf { (it.meters ?: 0f).toDouble() } / 1000
            val kcal = mine.mapNotNull { it.activeKcal }.sum()
            EdgeCard(GoalColor, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
                EdgeTitle("In all", "${mine.size} ${if (mine.size == 1) "workout" else "workouts"}")
                SummaryFigures(
                    listOf(
                        "%d:%02d".format(seconds / 3600, seconds / 60 % 60) to "Hours",
                        String.format(Locale.ROOT, "%.1f", km) to "km",
                        "%,d".format(Math.round(kcal)) to "Active kcal",
                    ),
                    Modifier.padding(top = 12.dp),
                )
            }
        }
        item { PageButton("Next week: ${view.goal}", onGoal, Modifier.fillMaxWidth(), icon = AppIcons.PencilEdit) }
    }
}
