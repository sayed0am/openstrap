package org.openstrap.app.ui.glow

import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.Units
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.data.Rewards
import org.openstrap.app.i18n.Strings
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.presentation.RewardsCopy
import org.openstrap.app.presentation.RewardsView
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.draw.level

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
            HeroValue(
                "$count", pluralStringResource(R.plurals.glow_goals_of_workouts, goal, goal), Modifier.padding(top = GlowLayout.TitleGap),
            )
            // The week's workouts against the goal as a bar of [goal] parts, [count] of them white.
            val described = pluralStringResource(R.plurals.glow_goals_count_of_goal, goal, count, goal)
            if (bar) SegmentBar(
                List(goal.coerceIn(1, MAX_GOAL_SHOWN)) { i -> Segment(1f, Color.White.copy(alpha = if (i < count) 1f else 0.2f)) },
                Modifier.padding(top = 18.dp).semantics { contentDescription = described }, height = 8.dp, gap = 6.dp,
            ) else WeekDots(dots, Color.White, Modifier.padding(top = 18.dp))
            Text(line, color = Color.White, style = GlowType.body, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/** The most workouts the goal bar splits into, so each part stays wide enough to read. */
private const val MAX_GOAL_SHOWN = 14

/** A week's distance shows from a tenth of a km, the first the figure can write. */
private const val MIN_WEEK_METERS = 100f

/**
 * The share card for the week from [first]: the count against the goal, its days, the hours, km and active kcal, and
 * chips for the weeks in a row and the week's first best.
 */
private fun weekStory(view: RewardsView, first: LocalDate, strings: Strings = Strings.app): StoryCard.Week {
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
        WeekFigure(AppIcons.Timer, Units.clockDuration(seconds), null, strings.get(R.string.glow_goals_hours)),
        meters.takeIf { it >= MIN_WEEK_METERS }?.let {
            WeekFigure(
                AppIcons.Route01, Units.km(it, Units.Precision.Day), strings.get(R.string.glow_unit_km),
                strings.get(R.string.glow_goals_distance),
            )
        },
        kcal.takeIf { it > 0 }?.let { WeekFigure(AppIcons.Fire, Units.count(it), null, strings.get(R.string.glow_goals_active_kcal)) },
    )
    val run = Rewards.inARow(view.weeks, first)
    val best = view.bests.firstOrNull { it.date in from until to }
    val chips = listOfNotNull(
        if (week?.met == true && run > 0) {
            StoryChip(strings.plural(R.plurals.glow_goals_weeks_in_a_row, run, run), best = false, met = true)
        } else {
            null
        },
        best?.let {
            val text = strings.format(R.string.glow_goals_best, RewardsCopy.bestTitle(it.kind), RewardsCopy.bestValue(it.kind, it.value))
            StoryChip(text, best = true, met = false)
        },
    )
    return StoryCard.Week(
        Ranges.of(DayWords.dayMonth(first), DayWords.dayMonth(first.plusDays(6))), "$count",
        strings.format(R.string.glow_goals_story_goal, strings.plural(R.plurals.glow_goals_workouts_word, count), goal),
        weekDots(view, first), totals, chips,
    )
}

/** The weekly goal on its own, in a sheet: the pencil on the Goals and bests page and the recap. */
@Composable
fun WeeklyGoalSheet(goal: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableIntStateOf(goal) }
    PageSheet(onDismiss, stringResource(R.string.glow_goals_weekly_goal), note = stringResource(R.string.glow_goals_sheet_note)) {
        org.openstrap.app.ui.device.WorkoutGoalField(value) { value = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(stringResource(R.string.glow_cancel), onDismiss, Modifier.weight(1f))
            PageButton(stringResource(R.string.glow_save), { onSave(value) }, Modifier.weight(1f), primary = true)
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
        item { PageHeader(stringResource(R.string.glow_goals_title), onBack) }
        if (view == null) {
            item { PageNote(stringResource(R.string.glow_goals_loading)) }
            return@ScreenList
        }
        item {
            val week = view.week(view.thisWeek)
            GoalWeekCard(
                stringResource(R.string.glow_goals_this_week), week?.count ?: 0, view.goal, weekDots(view, view.thisWeek),
                RewardsCopy.runLine(view.inARow, view.bestRun), bar = true,
            ) { PageButton(stringResource(R.string.glow_goals_goal_button, view.goal), onGoal, icon = AppIcons.PencilEdit) }
        }
        item {
            ListPanel(title = stringResource(R.string.glow_goals_personal_bests)) {
                val shown = view.bests.sortedBy { order(it.kind) }
                if (shown.isEmpty()) Text(
                    stringResource(R.string.glow_goals_no_bests), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.secondary, modifier = Modifier.padding(vertical = 10.dp),
                )
                shown.forEachIndexed { i, b ->
                    ListRow(
                        RewardsCopy.bestTitle(b.kind), BestColor, RewardsCopy.bestValue(b.kind, b.value), shortDate(b.date), divider = i > 0,
                        icon = AppIcons.FlagCheckered,
                    )
                }
            }
        }
        item {
            val earned = view.earned.sortedByDescending { it.earnedAt }.take(5).mapNotNull { r -> Rewards.badge(r.id)?.let { it to shortDate(r.earnedAt) } }
            val next = view.next?.let { b -> view.progress[b.id]?.let { (have, need) -> b to RewardsCopy.progressLine(have, need, b.unit) } }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.glow_goals_badges_count, view.earned.size, Rewards.BADGES.size),
                    color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
                // The last three earned and the next one, so the row is always four.
                BadgeRow(earned.take(if (next != null) 3 else 4) + listOfNotNull(next), locked = { b -> view.earned.none { it.id == b.id } }, face = view::face)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PageButton(
                    stringResource(R.string.glow_goals_all_badges), onAllBadges, Modifier.fillMaxWidth(), icon = AppIcons.CheckCircle,
                )
                PageButton(stringResource(R.string.glow_goals_last_week), onRecap, Modifier.fillMaxWidth(), icon = AppIcons.CalendarBlank)
                PageButton(
                    stringResource(R.string.glow_goals_celebrations), onCelebrations, Modifier.fillMaxWidth(),
                    icon = AppIcons.SlidersHorizontal,
                )
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
    return if (d.year == LocalDate.now().year) DayWords.dayMonth(d) else DayWords.dayMonthYear(d)
}

/** Every badge by group: earned ones in colour with their date, locked ones dim with what they need. */
@Composable
fun AllBadgesPage(view: RewardsView?, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item {
            val subtitle = view?.let { stringResource(R.string.glow_goals_earned_count, it.earned.size, Rewards.BADGES.size) }
            PageHeader(stringResource(R.string.glow_goals_all_badges), onBack, subtitle = subtitle)
        }
        if (view == null) return@ScreenList
        val earned = view.earned.associateBy { it.id }
        Rewards.Group.entries.forEach { group ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(
                            when (group) {
                                Rewards.Group.Firsts -> R.string.glow_goals_group_firsts
                                Rewards.Group.Milestones -> R.string.glow_goals_group_milestones
                                Rewards.Group.Weekly -> R.string.glow_goals_weekly_goal
                                Rewards.Group.Kinds -> R.string.glow_goals_group_kinds
                            },
                        ),
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
        item { PageHeader(stringResource(R.string.glow_goals_your_week), onBack, subtitle = RewardsCopy.weekRange(first)) }
        if (view == null) return@ScreenList
        val week = view.week(first)
        val count = week?.count ?: 0
        val goal = week?.goal ?: view.goal
        item {
            val run = Rewards.inARow(view.weeks, first)
            GoalWeekCard(
                stringResource(if (week?.met == true) R.string.glow_goals_goal_met else R.string.glow_goals_weekly_goal),
                count, goal, weekDots(view, first),
                if (run > 0) {
                    pluralStringResource(R.plurals.glow_goals_weekly_streak, run, run)
                } else {
                    (goal - count).coerceAtLeast(0).let { pluralStringResource(R.plurals.glow_goals_short_of_goal, it, it) }
                },
            )
        }
        val zone = ZoneId.systemDefault()
        val from = first.atStartOfDay(zone).toEpochSecond()
        val to = first.plusDays(7).atStartOfDay(zone).toEpochSecond()
        val highlights = view.bests.filter { it.date in from until to }
        if (highlights.isNotEmpty()) item {
            ListPanel(title = stringResource(R.string.glow_goals_highlights)) {
                highlights.forEachIndexed { i, b ->
                    ListRow(
                        stringResource(R.string.glow_goals_best, RewardsCopy.bestTitle(b.kind), RewardsCopy.bestValue(b.kind, b.value)),
                        BestColor,
                        DayWords.weekdayShort(Instant.ofEpochSecond(b.date).atZone(zone).toLocalDate()), null,
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
            PictureCard(GoalColor, Modifier.fillMaxWidth()) {
                EdgeTitle(
                    stringResource(R.string.glow_goals_in_all),
                    pluralStringResource(R.plurals.glow_goals_workouts_count, mine.size, mine.size),
                )
                DetailFigures(
                    listOf(
                        Units.clockDuration(seconds) to stringResource(R.string.glow_goals_hours),
                        Units.km(meters, Units.Precision.Day) to stringResource(R.string.glow_unit_km),
                        Units.count(Math.round(kcal)) to stringResource(R.string.glow_goals_active_kcal),
                    ),
                    Modifier.padding(top = 12.dp), valueStyle = GlowType.rowValue, missing = null, gap = 0.dp,
                )
            }
        }
        item {
            // Board WeeklyRecap: next week's goal and Share week side by side, Share the bright one.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageButton(
                    stringResource(R.string.glow_goals_next_week, view.goal), onGoal, Modifier.weight(1f), icon = AppIcons.PencilEdit,
                )
                PageButton(
                    stringResource(R.string.glow_goals_share_week), { sharing = true }, Modifier.weight(1f), primary = true,
                    icon = AppIcons.UploadSimple,
                )
            }
        }
    }
}
