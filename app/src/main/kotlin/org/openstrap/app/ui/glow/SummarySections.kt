package org.openstrap.app.ui.glow

import org.openstrap.app.data.Weeks
import org.openstrap.app.presentation.dotFormat
import org.openstrap.app.presentation.Ranges
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.Pace
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.openstrap.app.data.Rewards
import org.openstrap.app.data.Route
import org.openstrap.app.data.Structure
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.presentation.EarnedLine
import org.openstrap.app.ui.workout.ExerciseSets
import org.openstrap.app.ui.workout.FamilySummary
import org.openstrap.app.ui.workout.PeriodLine
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.workout.RoundLine
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.workout.ZONE_NAMES
import org.openstrap.app.ui.workout.familyColor
import org.openstrap.app.ui.glow.draw.EdgeKind
import org.openstrap.app.ui.glow.draw.Edge
import org.openstrap.app.ui.dashboard.LocalHeartZones

/*
 * The summary's parts that differ by kind of workout (workouts spec 2.8, boards SummaryRun to SummaryGame): the hero's
 * one number and three figures, then each kind's own section. Cards alternate their look down the page (an edge card
 * in the kind's colour, then a quiet one), so each section says whether it ended on an edge card for the card under it.
 */

/**
 * The hero: its one number in dots and its word, then a sentence or three figures in text. A figure's value is null
 * when it's missing (no heart rate, no profile for kcal): it shows the waiting dash.
 */
internal class SummaryHero(val value: String, val unit: String, val figures: List<Pair<String?, String>> = emptyList(), val sentence: String? = null, val line: String? = null, val pace: String? = null)

/** A workout's active kcal as its summary and share card write it: "1,312". */
internal fun kcalFigure(a: WorkoutAnalysis): String? = a.activeKcal?.let { Units.count(Math.round(it)) }

/** A workout's length in whole minutes, or seconds under one, with its unit: "42" to "min". */
internal fun minutesFigure(active: Long): Pair<String, String> = if (active >= 60) "${active / 60}" to "min" else "$active" to "s"

/**
 * What the hero shows for [summary]'s [family]; [usualLoad] names a game's Load. When the kind's own number is missing
 * (no heart rate for an indoor workout, no sets or rounds), the hero falls back to the minutes, for every kind.
 */
internal fun summaryHero(family: Workouts.Family, summary: PhoneSummary, usualLoad: Float?): SummaryHero {
    val a = summary.analysis
    val active = a.activeSeconds
    val readings = FamilySummary.readings(summary)
    val peak = readings.maxOfOrNull { it.second }?.toString()
    val avg = a.workout.averageHeartRate?.toString()
    val kcal = kcalFigure(a)
    val time = workoutClock(active)
    val (minutes, minUnit) = minutesFigure(active)
    fun byTime(figures: List<Pair<String?, String>>) = SummaryHero(minutes, minUnit, listOf(time to "Time") + figures)
    return when (family) {
        Workouts.Family.Distance -> {
            val m = summary.distanceMeters
            val gps = FamilySummary.gpsDistance(m, summary.distanceFromSteps)
            val pace = Pace.clock(Pace.of(gps, active))
            when {
                m == null || m <= 0f -> byTime(listOf(avg to "Avg bpm", kcal to "Active kcal"))
                else -> SummaryHero(
                    if (m < Units.METERS_PER_KM) m.toInt().toString() else Units.km(m, Units.Precision.Workout, Units.DOT_LOCALE),
                    if (m < Units.METERS_PER_KM) "m" else "km",
                    listOf(time to "Time", if (pace != null) pace to "Avg pace" else kcal to "Active kcal", avg to "Avg bpm"),
                    pace = pace,
                )
            }
        }
        Workouts.Family.IndoorCardio ->
            if (avg != null) SummaryHero(avg, "avg bpm", listOf(time to "Time", kcal to "Active kcal", peak to "Peak"))
            else byTime(listOf(kcal to "Active kcal", peak to "Peak"))
        Workouts.Family.Strength -> {
            val lifted = org.openstrap.app.data.Strength.kgLifted(summary.sets)
            val sets = FamilySummary.setsDone(summary.sets)
            val rest = FamilySummary.averageRest(summary.sets)?.let(::workoutClock)
            // Without a log the hero is the sets, and kg lifted isn't shown (spec 2.8); without sets, the minutes.
            when {
                lifted != null -> SummaryHero(
                    dotFormat("%,d", Math.round(lifted)), "kg lifted", listOf(time to "Time", "$sets" to "Sets", rest to "Avg rest"),
                )
                sets > 0 -> SummaryHero("$sets", if (sets == 1) "set" else "sets", listOf(time to "Time", rest to "Avg rest", peak to "Peak"))
                else -> byTime(listOf(avg to "Avg bpm", peak to "Peak"))
            }
        }
        Workouts.Family.Intervals -> {
            val done = org.openstrap.app.data.Phases.roundsDone(summary.rounds)
            val reps = summary.structure is Structure.Reps
            val hard = a.zoneSeconds.drop(3).sum()
            if (done == 0) byTime(listOf(avg to "Avg bpm", peak to "Peak"))
            else SummaryHero(
                "$done", if (reps) "reps done" else "rounds done",
                listOf(
                    workoutClock(FamilySummary.workSeconds(summary.rounds)) to (if (reps) "Rep time" else "Work time"), peak to "Peak",
                    (if (readings.isEmpty()) null else "${hard / 60} min") to "In zone 4 to 5",
                ),
            )
        }
        Workouts.Family.MindBody -> {
            val settled = FamilySummary.settled(readings, a.shownStart, a.end)
            SummaryHero(
                minutes, minUnit, sentence = settled?.let { (f, t) -> FamilySummary.settledLine(f, t) },
                line = listOfNotNull(a.workout.averageHeartRate?.let { "Average $it bpm" }, a.activeKcal?.let { "${Math.round(it)} active kcal" }).joinToString(", ").ifEmpty { null },
            )
        }
        Workouts.Family.Games -> {
            val load = a.workout.trainingLoad
            SummaryHero(
                minutes, if (minUnit == "min") "min played" else "s played",
                listOf(load?.toString() to (load?.let { FamilySummary.loadLabel(it, usualLoad) } ?: "Load"), kcal to "Active kcal", peak to "Peak bpm"),
            )
        }
    }
}

/** The hero's figures: values in Row value type over their labels, the last one to the right; a missing one is the dash. */
@Composable
internal fun SummaryFigures(figures: List<Pair<String?, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        figures.forEachIndexed { i, (value, label) ->
            val end = i == figures.lastIndex && i > 0
            Column(Modifier.weight(1f), horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
                if (value != null) Text(value, color = Color.White, style = GlowType.rowValue, maxLines = 1)
                else WaitingDash(Modifier.padding(vertical = 10.5.dp))
                Text(label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
            }
        }
    }
}

/**
 * The section for [family] (boards SummaryRun to SummaryGame): an edge card in the kind's colour, then a quiet card.
 * [week] is this week's count toward the goal, for the line under a calm session's week. Returns whether it ended on
 * an edge card, so the card under it can take another edge.
 */
@Composable
internal fun FamilySection(
    family: Workouts.Family, summary: PhoneSummary, liveRoute: List<Route.Point>, others: List<WorkoutAnalysis>, weekStarts: List<Long>,
    /** False on a past workout's page, whose heart rate card has the line already: the lines under it stay. */
    heartLine: Boolean = true,
    week: Rewards.WeekLine? = null,
): Boolean {
    val heartZones = LocalHeartZones.current
    val a = summary.analysis
    val readings = remember(summary) { FamilySummary.readings(summary) }
    // The stored time in zones, as the hero and the share card read it, so the page shows one total.
    val zones = remember(a) { a.zoneSeconds.toIntArray() }
    when (family) {
        Workouts.Family.Distance -> {
            RouteCard(summary)
            return SplitsList(summary, liveRoute, markFastest = true)
        }
        Workouts.Family.IndoorCardio -> {
            // Board SummaryIndoor: the zones lit from the right in the zone you were mostly in, the heart rate quiet
            // under it, then how it compares with last time as a line of its own.
            val zonesShown = zones.sum() > 0
            if (zonesShown) ZonesEdgeCard(zones)
            val kcal = a.activeKcal?.let(Math::round)
            val target = org.openstrap.app.ui.workout.SummaryCopy.targetLine(a.target, a.targetReachedSeconds, kcal)?.let {
                it to FamilySummary.targetReached(a.target, a.targetReachedSeconds, kcal)
            }
            val heartShown = IndoorHeartCard(readings, target, chart = heartLine)
            FamilySummary.versusLast(a, others)?.let { VersusLast(it.replaceFirstChar { c -> c.lowercase() }) }
            return zonesShown && !heartShown
        }
        Workouts.Family.Strength -> {
            val groups = remember(summary) { FamilySummary.exerciseSets(summary.sets, readings) }
            if (groups.isNotEmpty()) SetsCard(groups, a.maxHeartRate)
            val below = summary.restBelow
            val rest = remember(summary) { FamilySummary.restRecovery(summary.sets, readings, below) }
            val restShown = below != null && rest != null
            if (restShown) SentenceCard("Rest", FamilySummary.restLine(rest, below, summary.restBefore))
            return groups.isNotEmpty() && !restShown
        }
        Workouts.Family.Intervals -> {
            val reps = summary.structure is Structure.Reps
            val lines = remember(summary) { FamilySummary.rounds(summary.rounds, readings) }
            // Without a heart rate the peaks are empty: a run's reps show their times instead, timed rounds nothing.
            val peaks = lines.any { it.peak != null }
            val times = summary.rounds.mapNotNull { r -> r.workEnd?.let { it - r.workStart } }
            if (peaks) RoundsCard(lines, if (reps) "Each rep" else "Each round", a.maxHeartRate)
            else if (reps && times.isNotEmpty()) RepTimesCard(times)
            val rest = (summary.structure as? Structure.Timed)?.rest ?: (summary.structure as? Structure.Reps)?.rest
            val line = FamilySummary.roundsLine(lines, rest)
            if (line != null) SentenceCard("Recovery", line)
            return (peaks || (reps && times.isNotEmpty())) && line == null
        }
        Workouts.Family.MindBody -> {
            val settled = FamilySummary.settled(readings, a.shownStart, a.end)
            HeartLineCard(readings, MetricColors.evening, settled, chart = heartLine)
            val day = java.time.Instant.ofEpochSecond(a.shownStart).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            WeekDaysPanel(weekStarts, note = week?.let { FamilySummary.weekGoalLine(it.count, it.goal) }, day = day, color = MetricColors.evening)
            return false
        }
        Workouts.Family.Games -> {
            // Named out of the periods it was set up with, not the ones played, so ending early doesn't rename them.
            val of = (summary.structure as? org.openstrap.app.data.Structure.Periods)?.rounds ?: summary.rounds.count { it.workEnd != null }
            val periods = remember(summary, heartZones) { FamilySummary.periods(summary.rounds, readings, a.maxHeartRate, of, heartZones) }
            val read = FamilySummary.gameRead(readings, a.shownStart, a.end)
            val periodsShown = periods.isNotEmpty() || read != null
            if (periodsShown) PeriodsCard(periods, of, read)
            if (zones.sum() > 0) ZoneStripPanel(zones)
            return periodsShown && zones.sum() <= 0
        }
    }
}

/** The kept route in its zones' colours with the climb; nothing without a route that goes somewhere. */
@Composable
private fun RouteCard(summary: PhoneSummary) {
    if (!Route.mappable(summary.route)) return
    EdgeCard(MetricColors.steps, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        EdgeTitle("Route", Route.climb(summary.route)?.let { "Climb ${Math.round(it)} m" })
        RouteMap(summary, Modifier.padding(top = 12.dp))
    }
}

/** A quiet card with its [title] and one [sentence] under it: Rest after the sets, Recovery after the rounds. */
@Composable
private fun SentenceCard(title: String, sentence: String) {
    QuietPanel {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelTitle(title)
            Text(sentence, color = Color.White.copy(alpha = 0.85f), style = GlowType.body)
        }
    }
}

/**
 * The sets by exercise (board SummaryStrength), lit from the left in strength's orange: each set's number, what was
 * lifted, its peak and a bar as long as the peak against the hardest set's, in the zone it reached.
 */
@Composable
private fun SetsCard(groups: List<ExerciseSets>, maxHr: Int) {
    val heartZones = LocalHeartZones.current
    val shares = remember(groups) { FamilySummary.peakShares(groups.flatMap { g -> g.sets.map { it.peak } }) }
    EdgeCard(familyColor(Workouts.Family.Strength), Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        EdgeTitle("Sets", "Peak heart rate")
        var i = 0
        groups.forEachIndexed { gi, g ->
            if (g.name != null) Text(
                g.name, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1,
                modifier = Modifier.padding(top = if (gi == 0) 10.dp else 8.dp, bottom = 2.dp),
            ) else if (gi == 0) Spacer(Modifier.height(8.dp))
            g.sets.forEach { s ->
                val share = shares[i++]
                Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${s.number}", color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.secondary, maxLines = 1,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(s.text, color = Color.White, style = GlowType.secondary, maxLines = 1, modifier = Modifier.width(100.dp))
                    Text(
                        s.peak?.let { "peak $it" }.orEmpty(), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1,
                        modifier = Modifier.width(72.dp),
                    )
                    Box(Modifier.weight(1f).height(6.dp)) {
                        if (share != null) {
                            Box(
                                Modifier.fillMaxWidth(share).fillMaxHeight().clip(CircleShape)
                                    .background(zoneColor(s.peak, maxHr, heartZones)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Rounds shown with their figures; past this many there's no room, and the columns stand alone. */
private const val LabelledRounds = 10

/**
 * Each round as a column (board SummaryHiit), lit from the left in heart red: its peak over it, as tall as the peak
 * against the hardest round's, in the zone it reached, the hardest outlined. Under a hairline, how far the heart rate
 * came down in the rest after each.
 */
@Composable
private fun RoundsCard(lines: List<RoundLine>, title: String, maxHr: Int) {
    val heartZones = LocalHeartZones.current
    val shares = remember(lines) { FamilySummary.peakShares(lines.map { it.peak }) }
    val hardest = remember(lines) { FamilySummary.hardestRound(lines) }
    val labelled = lines.size <= LabelledRounds
    val gap = if (labelled) 6.dp else 3.dp
    val shape = RoundedCornerShape(5.dp)
    EdgeCard(familyColor(Workouts.Family.Intervals), Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        EdgeTitle(title, "Peak, then rest")
        Row(Modifier.fillMaxWidth().height(84.dp).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
            lines.forEachIndexed { i, r ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (labelled && r.peak != null) Text(
                        "${r.peak}", color = Color.White, maxLines = 1,
                        style = if (i == hardest) GlowType.caption.copy(fontWeight = FontWeight.SemiBold) else GlowType.caption,
                    )
                    shares[i]?.let { share ->
                        Box(
                            Modifier.fillMaxWidth().height(RoundColumnHeight * share).clip(shape)
                                .background(zoneColor(r.peak, maxHr, heartZones))
                                .let { if (i == hardest) it.border(2.dp, Color.White, shape) else it },
                        )
                    }
                }
            }
        }
        if (labelled && lines.any { it.drop != null }) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                lines.forEach { r ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (r.drop != null) {
                            Text("down", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.caption, maxLines = 1)
                            Text("${r.drop}", color = Color.White, style = GlowType.body, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A run's reps without a heart rate: each rep's time as a column, the fastest full height, bright and outlined, the
 * rest as tall as the fastest against them, with the time under each.
 */
@Composable
private fun RepTimesCard(times: List<Long>) {
    val fastest = times.filter { it > 0 }.minOrNull() ?: return
    val best = times.indexOf(fastest)
    val labelled = times.size <= LabelledRounds
    val gap = if (labelled) 6.dp else 3.dp
    val shape = RoundedCornerShape(5.dp)
    val color = familyColor(Workouts.Family.Intervals)
    EdgeCard(color, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        EdgeTitle("Each rep", "Time")
        Row(Modifier.fillMaxWidth().height(RoundColumnHeight + 14.dp).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
            times.forEachIndexed { i, t ->
                val share = if (t > 0) (fastest.toFloat() / t).coerceIn(0.3f, 1f) else 0.3f
                Box(
                    Modifier.weight(1f).height(RoundColumnHeight * share).clip(shape).background(if (i == best) color else color.copy(alpha = 0.45f))
                        .let { if (i == best) it.border(2.dp, Color.White, shape) else it },
                )
            }
        }
        if (labelled) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            times.forEachIndexed { i, t ->
                Text(
                    workoutClock(t), color = if (i == best) Color.White else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), maxLines = 1,
                    style = if (i == best) GlowType.caption.copy(fontWeight = FontWeight.SemiBold) else GlowType.caption,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The hardest round's column; the rest are as tall as their peak against it. */
private val RoundColumnHeight = 52.dp

/**
 * A game's halves or periods (board SummaryGame), lit from the left in the games colour: each one's hard efforts, a
 * bar as long as its average heart rate against the highest, that one full colour, and the average beside it. Then
 * how the game went.
 */
@Composable
private fun PeriodsCard(periods: List<PeriodLine>, of: Int, read: String?) {
    val color = familyColor(Workouts.Family.Games)
    val shares = remember(periods) { FamilySummary.peakShares(periods.map { it.averageBpm }) }
    EdgeCard(color, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        EdgeTitle(when (of) { 2 -> "Each half"; 4 -> "Each quarter"; else -> "Each period" }, null)
        periods.forEachIndexed { i, p ->
            Column(Modifier.fillMaxWidth().padding(top = if (i == 0) 14.dp else 12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(p.name, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(
                        "${p.hardEfforts} hard ${if (p.hardEfforts == 1) "effort" else "efforts"}", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                        style = GlowType.secondary, maxLines = 1,
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(Raised)) {
                        shares[i]?.let { share ->
                            Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(CircleShape).background(if (share >= 1f) color else color.copy(alpha = 0.6f)))
                        }
                    }
                    p.averageBpm?.let {
                        Text("$it", color = Color.White, style = GlowType.rowValue, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                        Text(" avg", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
                    }
                }
            }
        }
        read?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = GlowType.secondary, modifier = Modifier.padding(top = 14.dp)) }
    }
}

/**
 * Time in zones as one strip (boards SummaryGame, SummaryIndoor): a rounded segment for each zone with time in it, as
 * long as the time.
 */
@Composable
private fun ZoneStrip(zones: IntArray, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Row(modifier.fillMaxWidth().height(12.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        zones.forEachIndexed { z, s -> if (s > 0) Box(Modifier.weight(s.toFloat()).fillMaxHeight().clip(shape).background(ZONE_COLORS[z])) }
    }
}

/**
 * Time in zones on a quiet card (board SummaryGame): the zone with the most time in the title row, the strip, then
 * each zone's dot and minutes under it, the first with its unit.
 */
@Composable
internal fun ZoneStripPanel(zones: IntArray) {
    val minutes = remember(zones) { FamilySummary.zoneMinutes(zones) }
    QuietPanel {
        PanelTitle("Time in zones", FamilySummary.mostlyZone(zones))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZoneStrip(zones)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                var first = true
                zones.indices.filter { zones[it] > 0 }.forEach { z ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(ZONE_COLORS[z], CircleShape))
                        Text(
                            if (first) "${minutes[z]} min" else "${minutes[z]}", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                            style = GlowType.caption, maxLines = 1, modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                    first = false
                }
            }
        }
    }
}

/**
 * Time in zones on an edge card lit from the right in the zone you were mostly in (board SummaryIndoor): the strip,
 * then each zone with time in it by name and minutes, that zone's row bold.
 */
@Composable
private fun ZonesEdgeCard(zones: IntArray) {
    val minutes = remember(zones) { FamilySummary.zoneMinutes(zones) }
    val most = zones.indices.maxByOrNull { zones[it] } ?: 0
    CompositionLocalProvider(LocalEdgeOverride provides EdgeOverride(edge = Edge.Right)) {
        EdgeCard(ZONE_COLORS[most], Modifier.fillMaxWidth()) {
            EdgeTitle("Time in zones", FamilySummary.mostlyZone(zones))
            ZoneStrip(zones, Modifier.padding(top = 10.dp, bottom = 6.dp))
            zones.indices.filter { zones[it] > 0 }.forEach { z ->
                val style = if (z == most) GlowType.secondary.copy(fontWeight = FontWeight.SemiBold) else GlowType.secondary
                Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(ZONE_COLORS[z], CircleShape))
                    Text("Zone ${z + 1}, ${ZONE_NAMES[z].lowercase()}", color = Color.White, style = style, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    Text("${minutes[z]} min", color = Color.White, style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                }
            }
        }
    }
}

/**
 * The heart rate of an indoor workout on a quiet card (board SummaryIndoor): its range in the title row, the line in
 * heart red with its peak marked, then the kcal [target] with a tick when it was reached. Without the line ([chart]
 * off, or too few readings) the target alone. Returns whether it showed.
 */
@Composable
private fun IndoorHeartCard(readings: List<Pair<Long, Int>>, target: Pair<String, Boolean>?, chart: Boolean): Boolean {
    val line = chart && readings.size >= 10
    if (!line && target == null) return false
    val values = remember(readings) { FamilySummary.heartLine(readings, HeartPoints, window = 3) }
    QuietPanel {
        if (line) Column {
            PanelTitle("Heart rate", Ranges.of("${readings.minOf { it.second }}", "${readings.maxOf { it.second }}"))
            SmoothLine(values, MetricColors.heart, Modifier.padding(top = 8.dp), height = 64.dp, peak = true)
        }
        target?.let { (text, reached) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(20.dp).background((if (reached) MetricColors.good else Color.White).copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (reached) AppIcons.Tick02 else AppIcons.Target, null, tint = if (reached) MetricColors.good else Color.White, modifier = Modifier.size(12.dp)) }
                Text(text, color = Color.White, style = GlowType.secondary, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    return true
}

/** "vs last time:" and how this one compares, as a line of its own under the cards (board SummaryIndoor). */
@Composable
private fun VersusLast(line: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA))) { append("vs last time: ") }
            append(line)
        },
        color = Color.White, style = GlowType.secondary, modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** How many points a heart line is drawn through. */
private const val HeartPoints = 60

/**
 * The heart rate as a smooth line in [color], no grid: a dot at each end with [ends], or at the highest point with
 * [peak], and a hairline along the foot with [peak] (boards SummaryMind, SummaryIndoor). Gaps are bridged.
 */
@Composable
private fun SmoothLine(values: List<Float?>, color: Color, modifier: Modifier = Modifier, height: Dp, ends: Boolean = false, peak: Boolean = false) {
    val points = values.withIndex().filter { it.value != null }.map { it.index to it.value!! }
    if (points.size < 2) return
    Canvas(modifier.fillMaxWidth().height(height)) {
        val inset = 4.dp.toPx()
        val lo = points.minOf { it.second }
        val hi = points.maxOf { it.second }.let { if (it - lo < 1f) lo + 1f else it }
        fun at(i: Int, v: Float) = Offset(inset + i / (values.size - 1f) * (size.width - 2 * inset), inset + (hi - v) / (hi - lo) * (size.height - 2 * inset))
        val p = points.map { (i, v) -> at(i, v) }
        if (peak) drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        // Through the midpoints, each reading the control point, so the line bends without overshooting.
        val path = Path().apply {
            moveTo(p[0].x, p[0].y)
            for (k in 1 until p.size) {
                val m = Offset((p[k - 1].x + p[k].x) / 2, (p[k - 1].y + p[k].y) / 2)
                quadraticTo(p[k - 1].x, p[k - 1].y, m.x, m.y)
            }
            lineTo(p.last().x, p.last().y)
        }
        drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val dot = 4.dp.toPx()
        if (ends) {
            drawCircle(color, dot, p.first())
            drawCircle(color, dot, p.last())
        }
        if (peak) drawCircle(color, 3.5.dp.toPx(), p[points.indices.maxBy { points[it].second }])
    }
}

/**
 * The heart rate over a calm session on an edge card lit from the left (board SummaryMind): how far it came down in
 * the title row, a smooth line with its two ends marked, then the start and end figures ([settled]). Without them the
 * range in the title row and the line alone; with [chart] off, the figures alone.
 */
@Composable
private fun HeartLineCard(readings: List<Pair<Long, Int>>, color: Color, settled: Pair<Int, Int>? = null, chart: Boolean = true) {
    if (readings.size < 10 || (!chart && settled == null)) return
    val values = remember(readings) { FamilySummary.heartLine(readings, HeartPoints, window = 9) }
    EdgeCard(color, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        val drop = settled?.let { FamilySummary.settledDrop(it.first, it.second) }
        EdgeTitle("Heart rate", drop?.let { "down $it" } ?: Ranges.of("${readings.minOf { it.second }}", "${readings.maxOf { it.second }}"))
        if (chart) SmoothLine(values, color, Modifier.padding(top = 14.dp), height = 74.dp, ends = true)
        if (settled != null) Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${settled.first}", color = Color.White, style = GlowType.rowValue)
                Text("At the start", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${settled.second}", color = Color.White, style = GlowType.rowValue)
                Text("At the end", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            }
        }
    }
}

/**
 * The starts of the [workouts] that fill a day on [WeekDaysPanel]: the ones that count toward the weekly goal by the
 * rule the week count uses ([Rewards.countsTowardGoal]), so the dots and "N days with a workout" agree with "N workouts
 * this week" under them.
 */
internal fun goalWeekStarts(workouts: List<WorkoutAnalysis>): List<Long> =
    workouts.filter { Rewards.countsTowardGoal(it.startedByYou, it.activeSeconds) }.map { it.shownStart }

/**
 * The days of [day]'s week as round dots from the locale's first day (board SummaryMind): a day with a workout filled
 * in [color], today ringed, and the [note] under them.
 */
@Composable
internal fun WeekDaysPanel(weekStarts: List<Long>, note: String? = null, day: LocalDate = LocalDate.now(), color: Color = Color.White) {
    val firstDay = remember { Weeks.firstDay() }
    val days = remember(weekStarts, day) { FamilySummary.weekDays(weekStarts, day, firstDay) }
    val today = LocalDate.now()
    val thisWeek = days.any { it.first == today }
    QuietPanel {
        PanelTitle(if (thisWeek) "This week" else "That week")
        WeekDots(
            days.map { (d, on) -> d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) to on }, color,
            today = days.indexOfFirst { it.first == today }.takeIf { it >= 0 }, ticks = false, dot = 24.dp,
        )
        val text = note ?: days.count { it.second }.let { n -> "$n ${if (n == 1) "day" else "days"} with a workout ${if (thisWeek) "this" else "that"} week." }
        Text(text, color = Color.White, style = GlowType.body)
    }
}

/**
 * A week as seven round marks, each day's letter under it; a day with a workout is filled, with a tick in it when
 * [ticks]. The [today] mark is ringed and its letter white.
 */
@Composable
internal fun WeekDots(
    days: List<Pair<String, Boolean>>, color: Color = Color.White, modifier: Modifier = Modifier, today: Int? = null, ticks: Boolean = true, dot: Dp = 28.dp,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { i, (letter, on) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(dot)
                        // Today's ring sits just outside the mark, so it keeps its size.
                        .let { if (i == today) it.drawBehind { drawCircle((if (on) color else Color.White).copy(alpha = 0.3f), radius = size.minDimension / 2 + 3.dp.toPx()) } else it }
                        .background(if (on) color else Color.White.copy(alpha = 0.08f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (on && ticks) Icon(AppIcons.Tick02, null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
                Text(
                    letter, color = if (i == today) Color.White else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.caption,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * What this workout earned (workouts spec 2.8, boards SummaryRun to SummaryGame): "Earned", then each new best, badge
 * and the weekly goal as a chip, its icon in its colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EarnedChips(lines: List<EarnedLine>) {
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Earned", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(start = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { l ->
                val (icon, color) = when (l.kind) {
                    EarnedLine.Kind.Best -> AppIcons.FlagCheckered to MetricColors.calories
                    EarnedLine.Kind.Badge -> AppIcons.CheckCircle to MetricColors.good
                    EarnedLine.Kind.Goal -> AppIcons.Target to if (l.met) MetricColors.good else Color.White
                }
                Row(
                    Modifier.clip(CircleShape).background(Raised).padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                    Text(
                        listOfNotNull(l.text, l.value).joinToString(", "), color = Color.White, style = GlowType.secondary, maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
