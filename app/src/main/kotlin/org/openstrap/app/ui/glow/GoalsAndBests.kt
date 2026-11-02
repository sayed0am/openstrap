package org.openstrap.app.ui.glow

import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.Formatters
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.data.Rewards
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.presentation.RewardsCopy
import org.openstrap.app.presentation.RewardsView

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
private fun GoalWeekCard(
    title: String, count: Int, goal: Int, dots: List<Pair<String, Boolean>>, line: String,
    /** The week as a bar of the goal's workouts (board AchievementsV1) rather than its days (WeeklyRecap). */
    bar: Boolean = false, trailing: @Composable (() -> Unit)? = null,
) {
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
            if (bar) GoalBar(count, goal, Modifier.padding(top = 18.dp)) else WeekDots(dots, Color.White, Modifier.padding(top = 18.dp))
            Text(line, color = Color.White, style = GlowType.body, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/** The week's workouts against the goal as a bar of [goal] segments, [count] of them white. */
@Composable
private fun GoalBar(count: Int, goal: Int, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(
        modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = "$count of $goal workouts" },
    ) {
        val n = goal.coerceIn(1, 14)
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val corner = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        for (i in 0 until n) drawRoundRect(
            Color.White.copy(alpha = if (i < count) 1f else 0.2f), androidx.compose.ui.geometry.Offset(i * (w + gap), 0f),
            androidx.compose.ui.geometry.Size(w, size.height), corner,
        )
    }
}

/** A week's distance shows from a tenth of a km, the first the figure can write. */
private const val MIN_WEEK_METERS = 100f

/**
 * The share card for the week from [first]: the count against the goal, its days, the hours, km and active kcal, and
 * chips for the weeks in a row and the week's first best.
 */
private fun weekStory(view: RewardsView, first: LocalDate): StoryCard.Week {
    val week = view.week(first)
    val count = week?.count ?: 0
    val goal = week?.goal ?: view.goal
    val zone = ZoneId.systemDefault()
    val from = first.atStartOfDay(zone).toEpochSecond()
    val to = first.plusDays(7).atStartOfDay(zone).toEpochSecond()
    val mine = view.all.filter { it.startedByYou && it.shownStart in from until to }
    val seconds = mine.sumOf { it.activeSeconds }
    val meters = mine.sumOf { (it.meters ?: 0f).toDouble() }.toFloat()
    val kcal = Math.round(mine.mapNotNull { it.activeKcal }.sum())
    val totals = if (mine.isEmpty()) emptyList() else listOfNotNull(
        WeekFigure(AppIcons.Timer, Units.clockDuration(seconds), null, "Hours"),
        meters.takeIf { it >= MIN_WEEK_METERS }?.let { WeekFigure(AppIcons.Route01, Units.km(it, Units.Precision.Day), "km", "Distance") },
        kcal.takeIf { it > 0 }?.let { WeekFigure(AppIcons.Fire, Units.count(it), null, "Active kcal") },
    )
    val run = Rewards.inARow(view.weeks, first)
    val best = view.bests.firstOrNull { it.date in from until to }
    val chips = listOfNotNull(
        if (week?.met == true && run > 0) StoryChip("$run ${if (run == 1) "week" else "weeks"} in a row", best = false, met = true) else null,
        best?.let { StoryChip("${RewardsCopy.bestTitle(it.kind)}, ${RewardsCopy.bestValue(it.kind, it.value)}", best = true, met = false) },
    )
    return StoryCard.Week(
        "${dayMonth(first)} to ${dayMonth(first.plusDays(6))}", "$count",
        "${if (count == 1) "workout" else "workouts"}, goal $goal", weekDots(view, first), totals, chips,
    )
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
                "This week", week?.count ?: 0, view.goal, weekDots(view, view.thisWeek), RewardsCopy.runLine(view.inARow, view.bestRun), bar = true,
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
                // The last three earned and the next one, so the row is always four.
                BadgeRow(earned.take(if (next != null) 3 else 4) + listOfNotNull(next), locked = { b -> view.earned.none { it.id == b.id } }, face = view::face)
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
    return if (d.year == LocalDate.now().year) dayMonth(d) else d.format(Formatters.of("d MMM yyyy", Locale.getDefault()))
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
    // Share week opens the share screen in the recap's place; close comes back here.
    var sharing by rememberSaveable { mutableStateOf(false) }
    if (sharing && view != null) {
        val card = remember(view, first) { weekStory(view, first) }
        WeekShareScreen(card, "OpenStrap_week_$first.png") { sharing = false }
        return
    }
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
                        weekdayShort(Instant.ofEpochSecond(b.date).atZone(zone).toLocalDate()), null,
                        divider = i > 0, icon = AppIcons.FlagCheckered,
                    )
                }
            }
        }
        item {
            val mine = view.all.filter { it.startedByYou && it.shownStart in from until to }
            val seconds = mine.sumOf { it.activeSeconds }
            val meters = mine.sumOf { (it.meters ?: 0f).toDouble() }.toFloat()
            val kcal = mine.mapNotNull { it.activeKcal }.sum()
            EdgeCard(GoalColor, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
                EdgeTitle("In all", "${mine.size} ${if (mine.size == 1) "workout" else "workouts"}")
                SummaryFigures(
                    listOf(
                        Units.clockDuration(seconds) to "Hours",
                        Units.km(meters, Units.Precision.Day) to "km",
                        Units.count(Math.round(kcal)) to "Active kcal",
                    ),
                    Modifier.padding(top = 12.dp),
                )
            }
        }
        item {
            // Board WeeklyRecap: next week's goal and Share week side by side, Share the bright one.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageButton("Next week: ${view.goal}", onGoal, Modifier.weight(1f), icon = AppIcons.PencilEdit)
                PageButton("Share week", { sharing = true }, Modifier.weight(1f), primary = true, icon = AppIcons.UploadSimple)
            }
        }
    }
}
