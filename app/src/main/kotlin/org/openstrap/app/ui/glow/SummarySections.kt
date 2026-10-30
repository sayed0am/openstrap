package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import org.openstrap.app.data.Route
import org.openstrap.app.data.Structure
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.EarnedLine
import org.openstrap.app.ui.workout.FamilySummary
import org.openstrap.app.ui.workout.Glance
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.workout.ZONE_COLORS

/*
 * The summary's parts that differ by kind of workout (workouts spec 2.8, boards SummaryRun to SummaryGame): the hero's
 * one number and three figures, then each kind's own section. Cards alternate their look down the page (a list never
 * sits under a list), so each section says whether it ended on a list for the Earned list under it.
 */

/** The hero's colour for each kind of workout. */
internal fun familyColor(f: Workouts.Family): Color = when (f) {
    Workouts.Family.Distance -> MetricColors.steps
    Workouts.Family.IndoorCardio -> MetricColors.heart
    Workouts.Family.Strength -> MetricColors.load
    Workouts.Family.Intervals -> MetricColors.heart
    Workouts.Family.MindBody -> MetricColors.evening
    Workouts.Family.Games -> MetricColors.charge
}

/** The hero: its one number in dots and its word, then a sentence or three figures in text. */
internal class SummaryHero(val value: String, val unit: String, val figures: List<Pair<String, String>> = emptyList(), val sentence: String? = null, val line: String? = null)

/** What the hero shows for [summary]'s [family]; [usualLoad] names a game's Load. */
internal fun summaryHero(family: Workouts.Family, summary: PhoneSummary, usualLoad: Float?): SummaryHero {
    val a = summary.analysis
    val active = a.activeSeconds
    val readings = summary.recorded.filter { it.timestamp in a.shownStart..a.end }.map { it.timestamp to it.bpm }
    val peak = readings.maxOfOrNull { it.second }?.toString() ?: "None"
    val avg = a.workout.averageHeartRate?.toString() ?: "None"
    val kcal = a.activeKcal?.let { "%,d".format(Math.round(it)) } ?: "None"
    val minutes = (active / 60).toString()
    return when (family) {
        Workouts.Family.Distance -> {
            val m = summary.distanceMeters
            val gps = FamilySummary.gpsDistance(m, summary.distanceFromSteps)
            val pace = Glance.paceClock(FamilySummary.pace(gps, active))
            when {
                m == null || m <= 0f -> SummaryHero(minutes, "min", listOf(workoutClock(active) to "Time", avg to "Avg bpm", kcal to "Active kcal"))
                else -> SummaryHero(
                    if (m < 1000) m.toInt().toString() else km(m), if (m < 1000) "m" else "km",
                    listOf(workoutClock(active) to "Time", (pace ?: kcal) to (if (pace != null) "Avg pace" else "Active kcal"), avg to "Avg bpm"),
                )
            }
        }
        Workouts.Family.IndoorCardio -> SummaryHero(avg, "avg bpm", listOf(workoutClock(active) to "Time", kcal to "Active kcal", peak to "Peak"))
        Workouts.Family.Strength -> {
            val lifted = org.openstrap.app.data.Strength.kgLifted(summary.sets)
            val sets = FamilySummary.setsDone(summary.sets)
            val rest = FamilySummary.averageRest(summary.sets)?.let(::workoutClock) ?: "None"
            // Without a log the hero is the sets, and kg lifted isn't shown (spec 2.8).
            if (lifted != null) SummaryHero("%,d".format(Math.round(lifted)), "kg lifted", listOf(workoutClock(active) to "Time", "$sets" to "Sets", rest to "Avg rest"))
            else SummaryHero("$sets", if (sets == 1) "set" else "sets", listOf(workoutClock(active) to "Time", rest to "Avg rest", peak to "Peak"))
        }
        Workouts.Family.Intervals -> {
            val done = org.openstrap.app.data.Phases.roundsDone(summary.rounds)
            val reps = summary.structure is Structure.Reps
            val hard = a.zoneSeconds.drop(3).sum()
            SummaryHero(
                "$done", if (reps) "reps done" else "rounds done",
                listOf(workoutClock(FamilySummary.workSeconds(summary.rounds)) to (if (reps) "Rep time" else "Work time"), peak to "Peak", "${hard / 60} min" to "In zone 4 to 5"),
            )
        }
        Workouts.Family.MindBody -> {
            val settled = FamilySummary.settled(readings, a.shownStart, a.end)
            SummaryHero(
                minutes, "min", sentence = settled?.let { (f, t) -> FamilySummary.settledLine(f, t) },
                line = listOfNotNull(a.workout.averageHeartRate?.let { "Average $it bpm" }, a.activeKcal?.let { "${Math.round(it)} active kcal" }).joinToString(", ").ifEmpty { null },
            )
        }
        Workouts.Family.Games -> {
            val load = a.workout.trainingLoad
            SummaryHero(
                minutes, "min played",
                listOf((load?.toString() ?: "None") to (load?.let { FamilySummary.loadLabel(it, usualLoad) } ?: "Load"), kcal to "Active kcal", peak to "Peak bpm"),
            )
        }
    }
}

/** The hero's figures: values in Row value type over their labels, the last one to the right. */
@Composable
internal fun SummaryFigures(figures: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        figures.forEachIndexed { i, (value, label) ->
            val end = i == figures.lastIndex && i > 0
            Column(Modifier.weight(1f), horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
                Text(value, color = Color.White, style = GlowType.rowValue, maxLines = 1)
                Text(label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
            }
        }
    }
}

/**
 * The section for [family]. Returns whether it ended on a list, so the Earned list under it can take the edge
 * instead.
 */
@Composable
internal fun FamilySection(family: Workouts.Family, summary: PhoneSummary, liveRoute: List<Route.Point>, others: List<WorkoutAnalysis>, weekStarts: List<Long>): Boolean {
    val a = summary.analysis
    val readings = remember(summary) { summary.recorded.filter { it.timestamp in a.shownStart..a.end }.map { it.timestamp to it.bpm } }
    val zones = remember(a) { WorkoutRecorder.liveZoneSeconds(a.heartRate.filter { it.timestamp <= a.end }.map { it.timestamp to it.bpm }, a.maxHeartRate) }
    when (family) {
        Workouts.Family.Distance -> {
            RouteCard(summary)
            return SplitsList(summary, liveRoute, markFastest = true)
        }
        Workouts.Family.IndoorCardio -> {
            if (zones.sum() > 0) ZonesList(zones, a.maxHeartRate, note = FamilySummary.mostlyZone(zones) ?: "Max ${a.maxHeartRate} bpm")
            HeartLineCard(readings, MetricColors.heart, listOfNotNull(
                org.openstrap.app.ui.workout.SummaryCopy.targetLine(a.target, a.targetReachedSeconds, a.activeKcal?.let(Math::round)),
                FamilySummary.versusLast(a, others)?.let { "vs last time: ${it.replaceFirstChar { c -> c.lowercase() }}" },
            ))
            return false
        }
        Workouts.Family.Strength -> {
            val groups = remember(summary) { FamilySummary.exerciseSets(summary.sets, readings) }
            if (groups.isNotEmpty()) ListPanel(title = "Sets", note = "Peak heart rate") {
                var first = true
                groups.forEach { g ->
                    if (g.name != null) WorkoutDayHeader(g.name)
                    g.sets.forEach { s ->
                        ListRow(
                            if (g.name != null) "${s.number}. ${s.text}" else "Set ${s.number}", zoneColor(s.peak, a.maxHeartRate),
                            s.peak?.let { "peak $it" }, if (g.name == null) s.text else null, divider = !first, icon = AppIcons.Heart,
                        )
                        first = false
                    }
                }
            }
            val below = summary.restBelow
            val rest = remember(summary) { FamilySummary.restRecovery(summary.sets, readings, below) }
            if (below != null && rest != null) EdgeCard(RestGreenSummary, Modifier.fillMaxWidth(), status = EdgeStatus.Below) {
                EdgeTitle("Rest", null)
                Text(FamilySummary.restLine(rest, below, summary.restBefore), color = Color.White, style = GlowType.body, modifier = Modifier.padding(top = 8.dp))
            }
            return groups.isNotEmpty() && (below == null || rest == null)
        }
        Workouts.Family.Intervals -> {
            val lines = remember(summary) { FamilySummary.rounds(summary.rounds, readings) }
            if (lines.isNotEmpty()) ListPanel(title = if (summary.structure is Structure.Reps) "Each rep" else "Each round", note = "Peak, then rest") {
                lines.forEachIndexed { i, r ->
                    ListRow(
                        "${if (summary.structure is Structure.Reps) "Rep" else "Round"} ${r.number}", zoneColor(r.peak, a.maxHeartRate),
                        r.peak?.let { "$it bpm" }, r.drop?.let { "Down $it in the rest" }, divider = i > 0, icon = AppIcons.Heart,
                    )
                }
            }
            val rest = (summary.structure as? Structure.Timed)?.rest ?: (summary.structure as? Structure.Reps)?.rest
            FamilySummary.roundsLine(lines, rest)?.let { line ->
                EdgeCard(RestGreenSummary, Modifier.fillMaxWidth(), status = EdgeStatus.Below) {
                    EdgeTitle("Recovery", null)
                    Text(line, color = Color.White, style = GlowType.body, modifier = Modifier.padding(top = 8.dp))
                }
                return false
            }
            return lines.isNotEmpty()
        }
        Workouts.Family.MindBody -> {
            val settled = FamilySummary.settled(readings, a.shownStart, a.end)
            HeartLineCard(readings, MetricColors.evening, emptyList(), settled)
            WeekDaysPanel(weekStarts)
            return true
        }
        Workouts.Family.Games -> {
            val periods = remember(summary) { FamilySummary.periods(summary.rounds, readings, a.maxHeartRate) }
            val read = FamilySummary.gameRead(readings, a.shownStart, a.end)
            if (periods.isNotEmpty() || read != null) EdgeCard(MetricColors.charge, Modifier.fillMaxWidth()) {
                EdgeTitle(if (periods.size == 2) "Each half" else "Each period", null)
                periods.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Color.White, style = GlowType.body)
                            Text(
                                "${p.hardEfforts} hard ${if (p.hardEfforts == 1) "effort" else "efforts"}, ${p.seconds / 60} min",
                                color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                            )
                        }
                        p.averageBpm?.let { Text("$it avg", color = Color.White, style = GlowType.rowValue) }
                    }
                }
                read?.let { Text(it, color = Color.White, style = GlowType.body, modifier = Modifier.padding(top = 12.dp)) }
            }
            if (zones.sum() > 0) ZonesList(zones, a.maxHeartRate, note = FamilySummary.mostlyZone(zones) ?: "Max ${a.maxHeartRate} bpm")
            return zones.sum() > 0
        }
    }
}

/** Rest's colour, as on the live screens. */
private val RestGreenSummary = ZONE_COLORS[1]

/** The kept route in its zones' colours with the climb; nothing without a route. */
@Composable
private fun RouteCard(summary: PhoneSummary) {
    if (summary.route.size < 2) return
    EdgeCard(MetricColors.steps, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        EdgeTitle("Route", Route.climb(summary.route)?.let { "Climb ${Math.round(it)} m" })
        RouteMap(summary, Modifier.padding(top = 12.dp))
    }
}

/**
 * The heart rate over the workout as one line on an edge card, its range in the title, then [lines] under it. With
 * [settled], the start and end figures of a calm session instead of the range.
 */
@Composable
private fun HeartLineCard(readings: List<Pair<Long, Int>>, color: Color, lines: List<String>, settled: Pair<Int, Int>? = null) {
    if (readings.size < 10) {
        if (lines.isEmpty()) return
        EdgeCard(color, Modifier.fillMaxWidth()) { lines.forEach { Text(it, color = Color.White, style = GlowType.body) } }
        return
    }
    val values = remember(readings) {
        val first = readings.first().first
        val span = (readings.last().first - first).coerceAtLeast(1)
        val n = 60
        val sums = FloatArray(n)
        val counts = IntArray(n)
        readings.forEach { (t, v) -> val i = ((t - first) * (n - 1) / span).toInt(); sums[i] += v.toFloat(); counts[i]++ }
        List(n) { if (counts[it] == 0) null else sums[it] / counts[it] }
    }
    EdgeCard(color, Modifier.fillMaxWidth(), kind = EdgeKind.Earlier) {
        val lo = readings.minOf { it.second }
        val hi = readings.maxOf { it.second }
        EdgeTitle("Heart rate", if (settled != null && settled.first - settled.second >= 3) "Down ${settled.first - settled.second}" else "$lo to $hi")
        GlowLineChart(values, Modifier.padding(top = 12.dp), height = 90.dp, showAverage = false)
        if (settled != null) Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${settled.first}", color = Color.White, style = GlowType.rowValue)
                Text("At the start", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${settled.second}", color = Color.White, style = GlowType.rowValue)
                Text("At the end", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            }
        }
        lines.forEach { Text(it, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** The days of this week with a workout lit, from the locale's first day, and the [note] under them. */
@Composable
internal fun WeekDaysPanel(weekStarts: List<Long>, note: String? = null) {
    val firstDay = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val days = remember(weekStarts) { FamilySummary.weekDays(weekStarts, LocalDate.now(), firstDay) }
    QuietPanel {
        PanelTitle("This week")
        WeekDots(days.map { (d, on) -> d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) to on })
        val text = note ?: days.count { it.second }.let { n -> "$n ${if (n == 1) "day" else "days"} with a workout this week." }
        Text(text, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
    }
}

/** A week as seven round marks, each day's letter under it; a day with a workout is filled. */
@Composable
internal fun WeekDots(days: List<Pair<String, Boolean>>, color: Color = Color.White, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { (letter, on) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(28.dp).background(if (on) color else Color.White.copy(alpha = 0.08f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(AppIcons.CheckCircle, null, tint = Color.Black, modifier = Modifier.size(14.dp)) }
                Text(letter, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.caption, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * What this workout earned (workouts spec 2.8): new bests, badges, and the weekly goal line last. A list; under
 * another list ([underList]) it sits on an edge card instead.
 */
@Composable
internal fun EarnedPanel(lines: List<EarnedLine>, underList: Boolean) {
    if (lines.isEmpty()) return
    CompositionLocalProvider(LocalEdgeOverride provides if (underList) EdgeOverride(edge = Edge.Bottom) else EdgeOverride()) {
        ListPanel(title = "Earned") {
            lines.forEachIndexed { i, l ->
                val (icon, color) = when (l.kind) {
                    EarnedLine.Kind.Best -> AppIcons.FlagCheckered to MetricColors.calories
                    EarnedLine.Kind.Badge -> AppIcons.CheckCircle to MetricColors.good
                    EarnedLine.Kind.Goal -> AppIcons.Target to if (l.met) MetricColors.good else Color.White
                }
                ListRow(l.text, color, null, null, divider = i > 0, dash = false, icon = icon)
            }
        }
    }
}
