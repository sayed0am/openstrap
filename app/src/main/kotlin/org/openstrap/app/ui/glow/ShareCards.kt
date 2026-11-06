package org.openstrap.app.ui.glow

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.ui.glow.draw.EdgeSurface
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import org.openstrap.app.data.Phases
import org.openstrap.app.data.Route
import org.openstrap.app.data.Strength
import org.openstrap.app.data.Structure
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.presentation.EarnedLine
import org.openstrap.app.ui.workout.FamilySummary
import org.openstrap.app.ui.workout.PhoneSummary
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.ui.workout.familyColor
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.dashboard.LocalHeartZones

/*
 * The story cards a workout shares as (workouts spec 2.9, one system for every kind in mockups/share-cards-system.html):
 * 9 by 16, the wordmark and date on top, the picture zone left clear, and what the card says in one block on the foot,
 * lit from below in the kind's colour or laid over your photo with a dark fade so the text reads. Drawn at
 * [StoryWidth] by [StoryHeight] and scaled, so the preview and the saved 1080 by 1920 picture are the same card.
 */

/** The card's own width, in its own dp: the saved picture is three pixels to each. */
internal val StoryWidth = 360.dp

/** The card's own height, 16 to [StoryWidth]'s 9. */
internal val StoryHeight = 640.dp

/** A chip on a card: a best, the weekly goal, or the weeks in a row. */
internal class StoryChip(val text: String, val best: Boolean, val met: Boolean)

/** A figure in a card's stat row: its value, the small unit after it, and its label. */
internal class StoryStat(val value: String, val unit: String?, val label: String)

/**
 * What a share card says (mockups/share-cards-system.html): every kind has the same parts, the [date] by the wordmark,
 * the [title], one [value] in dots with its [unit], two or three [stats] and up to two [chips]. Each kind adds the one
 * picture of the session its summary draws. Every text is ready to show.
 */
internal sealed class StoryCard(
    val family: Workouts.Family,
    val date: String,
    val title: String,
    val value: String,
    val unit: String,
    val stats: List<StoryStat>,
    val chips: List<StoryChip>,
) {
    /** A run, ride or walk: the distance (else the time), its route drawn above when there is one. */
    class Run(date: String, title: String, value: String, unit: String, stats: List<StoryStat>, chips: List<StoryChip>) :
        StoryCard(Workouts.Family.Distance, date, title, value, unit, stats, chips)

    /** Strength: kg lifted, each exercise's sets and top weight as [rows]. */
    class Lift(
        date: String, title: String, value: String, unit: String, val rows: List<Pair<String, String>>,
        stats: List<StoryStat>, chips: List<StoryChip>,
    ) : StoryCard(Workouts.Family.Strength, date, title, value, unit, stats, chips)

    /** Indoor cardio: the time, and the [zones] strip. */
    class Indoor(date: String, title: String, value: String, val zones: List<Int>, stats: List<StoryStat>, chips: List<StoryChip>) :
        StoryCard(Workouts.Family.IndoorCardio, date, title, value, "", stats, chips)

    /** Intervals: rounds done, a column for each round's peak. */
    class Rounds(
        date: String, title: String, value: String, unit: String, val peaks: List<Int?>, val maxHeartRate: Int,
        stats: List<StoryStat>, chips: List<StoryChip>,
    ) : StoryCard(Workouts.Family.Intervals, date, title, value, unit, stats, chips)

    /** Mind and body: minutes, and the [heart] rate through the session as one line. */
    class Calm(
        date: String, title: String, value: String, unit: String, val heart: List<Float?>, stats: List<StoryStat>, chips: List<StoryChip>,
    ) : StoryCard(Workouts.Family.MindBody, date, title, value, unit, stats, chips)

    /** A game: minutes, with nothing drawn between, so a photo leads. */
    class Game(date: String, title: String, value: String, unit: String, stats: List<StoryStat>, chips: List<StoryChip>) :
        StoryCard(Workouts.Family.Games, date, title, value, unit, stats, chips)

    /** A week (the recap's Share week): the workouts counted against the goal, its [days] as the app's week dots. */
    // Not a kind of workout: the card is lit in the goal green, as the recap is.
    class Week(
        range: String, title: String, count: String, goalLine: String, val days: List<Pair<String, Boolean>>,
        totals: List<StoryStat>, chips: List<StoryChip>,
    ) : StoryCard(Workouts.Family.Distance, range, title, count, goalLine, totals, chips.take(2))
}

/** The card for [summary]'s workout of [family], from the [hero] its summary shows and what it [earned]. */
internal fun storyCard(
    family: Workouts.Family, summary: PhoneSummary, hero: SummaryHero, earned: List<EarnedLine>,
    zone: ZoneId = ZoneId.systemDefault(), strings: Strings = Strings.app,
): StoryCard {
    val a = summary.analysis
    val active = a.activeSeconds
    val readings = FamilySummary.readings(summary)
    val date = DayWords.monthDay(Instant.ofEpochSecond(a.shownStart).atZone(zone).toLocalDate())
    val sport = a.sportName
    val (minutes, minUnit) = minutesFigure(active, strings)
    val chips = listOfNotNull(
        earned.firstOrNull { it.kind == EarnedLine.Kind.Best }?.let { StoryChip(it.text, best = true, met = false) }
            ?: earned.firstOrNull { it.kind == EarnedLine.Kind.Goal }?.let {
                val text = it.value?.let { v -> strings.format(R.string.glow_share_join, it.text, v) } ?: it.text
                StoryChip(text, best = false, met = it.met)
            },
    )
    val time = StoryStat(workoutClock(active), null, strings.get(R.string.glow_share_stat_time))
    val timeMinutes = StoryStat(minutes, minUnit, strings.get(R.string.glow_share_stat_time))
    val avg = a.workout.averageHeartRate?.let { StoryStat("$it", null, strings.get(R.string.glow_share_stat_avg_bpm)) }
    val peak = readings.maxOfOrNull { it.second }?.let { StoryStat("$it", null, strings.get(R.string.glow_share_stat_peak_bpm)) }
    val kcal = kcalFigure(a)?.let { StoryStat(it, null, strings.get(R.string.glow_share_stat_active_kcal)) }
    return when (family) {
        Workouts.Family.Distance -> {
            val m = summary.distanceMeters?.takeIf { it > 0f }
            if (m != null) {
                val pace = hero.pace?.let {
                    StoryStat(it, strings.get(R.string.glow_share_unit_per_km), strings.get(R.string.glow_share_stat_pace))
                }
                StoryCard.Run(date, sport, hero.value, hero.unit, listOfNotNull(time, pace, avg), chips)
            } else {
                StoryCard.Run(date, sport, dotClock(active), "", listOfNotNull(avg, kcal, peak).take(MAX_STATS), chips)
            }
        }
        Workouts.Family.Strength -> {
            val lifted = Strength.kgLifted(summary.sets)
            val done = FamilySummary.setsDone(summary.sets)
            val sets = StoryStat("$done", null, strings.get(R.string.glow_share_stat_sets)).takeIf { done > 0 }
            StoryCard.Lift(
                date, sport, hero.value, if (lifted != null) strings.get(R.string.glow_share_unit_kg) else hero.unit,
                exerciseRows(summary.sets, strings).take(MAX_EXERCISES), listOfNotNull(timeMinutes, sets, avg), chips,
            )
        }
        Workouts.Family.IndoorCardio ->
            StoryCard.Indoor(date, sport, dotClock(active), a.zoneSeconds, listOfNotNull(avg, kcal, peak), chips)
        Workouts.Family.Intervals -> roundsCard(summary, date, minutes to minUnit, listOfNotNull(peak), time, chips, strings)
        Workouts.Family.MindBody -> {
            val settled = FamilySummary.settled(readings, a.shownStart, a.end)
            val stats = settled?.let { (from, to) ->
                listOf(
                    StoryStat("$from", null, strings.get(R.string.glow_share_stat_bpm_start)),
                    StoryStat("$to", null, strings.get(R.string.glow_share_stat_bpm_end)),
                )
            } ?: listOfNotNull(avg)
            StoryCard.Calm(date, sport, minutes, minUnit, heartLine(readings), stats, chips)
        }
        Workouts.Family.Games -> {
            val load = a.workout.trainingLoad?.let { StoryStat("$it", null, strings.get(R.string.glow_share_stat_load)) }
            StoryCard.Game(date, sport, minutes, minUnit, listOfNotNull(load, peak, avg), chips)
        }
    }
}

/** The most figures a stat row holds. */
private const val MAX_STATS = 3

/** An intervals card: the reps or rounds done of those planned and each one's peak, then the [peak], the work and the [time]. */
private fun roundsCard(
    summary: PhoneSummary, date: String, minutes: Pair<String, String>, peak: List<StoryStat>, time: StoryStat,
    chips: List<StoryChip>, strings: Strings,
): StoryCard.Rounds {
    val a = summary.analysis
    val done = Phases.roundsDone(summary.rounds)
    val reps = summary.structure is Structure.Reps
    val planned = (summary.structure as? Structure.Timed)?.rounds ?: (summary.structure as? Structure.Reps)?.rounds
    val readings = FamilySummary.readings(summary)
    val lines = FamilySummary.rounds(summary.rounds, readings)
    val roundsPeak = lines.mapNotNull { it.peak }.maxOrNull()
    val peakStat = roundsPeak?.let { StoryStat("$it", null, strings.get(R.string.glow_share_stat_peak_bpm)) } ?: peak.firstOrNull()
    if (done == 0) {
        val stats = listOfNotNull(peakStat, time)
        return StoryCard.Rounds(date, a.sportName, minutes.first, minutes.second, emptyList(), a.maxHeartRate, stats, chips)
    }
    val work = FamilySummary.workSeconds(summary.rounds)
    val workStat = if (work >= 60) {
        StoryStat("${work / 60}", strings.get(R.string.glow_unit_min), strings.get(R.string.glow_share_stat_work))
    } else {
        StoryStat("$work", strings.get(R.string.glow_summary_unit_s), strings.get(R.string.glow_share_stat_work))
    }
    val unit = when {
        planned != null && planned >= done ->
            strings.plural(if (reps) R.plurals.glow_share_unit_of_reps else R.plurals.glow_share_unit_of_rounds, planned, planned)
        else -> strings.get(if (reps) R.string.glow_share_unit_reps else R.string.glow_share_unit_rounds)
    }
    val stats = listOfNotNull(peakStat, workStat, time)
    return StoryCard.Rounds(date, a.sportName, "$done", unit, lines.map { it.peak }, a.maxHeartRate, stats, chips)
}

/** Exercises a strength card names; the time and sets go in its stat row. */
private const val MAX_EXERCISES = 4

/**
 * Each named exercise in the order first done: "5 × 10, top 65 kg" when every set had the same reps, else "4 sets,
 * top 65 kg", without the top when nothing was weighed. Sets without a name aren't listed.
 */
internal fun exerciseRows(sets: List<org.openstrap.app.data.db.AppWorkoutSet>, strings: Strings = Strings.app): List<Pair<String, String>> {
    val done = sets.filter { it.end != null && !it.exercise.isNullOrBlank() }
    return done.groupBy { it.exercise!!.trim().lowercase() }.values.map { mine ->
        val n = mine.size
        val reps = mine.map { it.reps }.distinct().singleOrNull()
        val top = mine.mapNotNull { it.weightKg }.maxOrNull()
        val count = if (reps != null) {
            strings.format(R.string.glow_share_row_reps, n, reps)
        } else {
            strings.plural(R.plurals.glow_share_sets, n, n)
        }
        mine.first().exercise!!.trim() to (top?.let { strings.format(R.string.glow_share_row_top, count, Strength.kgText(it)) } ?: count)
    }
}

/** [readings] as 40 averages, for a calm card's heart line; empty under ten readings. */
private fun heartLine(readings: List<Pair<Long, Int>>): List<Float?> {
    if (readings.size < 10) return emptyList()
    return FamilySummary.heartLine(readings, 40)
}

/** How a run's route shows on its card. Map, the route over a map, is to come. */
internal enum class StoryRoute { Off, Line }

/** The route's geometry on a card: its ends cut for privacy, and fitted into a box. Pure, so it's tested. */
internal object StoryRouteShape {
    /** What "Hide start and end" cuts from each end of the line, in metres. */
    const val HIDE_M = 200.0

    /**
     * [points] less the first and last [meters] along the way, cut between fixes so the line stops where the distance
     * runs out. Empty when nothing is left between the two.
     */
    fun trimEnds(points: List<Route.Point>, meters: Double = HIDE_M): List<Route.Point> {
        if (points.size < 2) return emptyList()
        val along = DoubleArray(points.size)
        for (i in 1 until points.size) along[i] = along[i - 1] + Route.meters(points[i - 1], points[i])
        val from = meters
        val to = along.last() - meters
        if (to <= from) return emptyList()
        fun at(d: Double): Route.Point {
            val i = (1 until points.size).first { along[it] >= d }
            val a = points[i - 1]
            val b = points[i]
            val span = along[i] - along[i - 1]
            val f = if (span <= 0) 0.0 else (d - along[i - 1]) / span
            return a.copy(t = a.t + ((b.t - a.t) * f).toLong(), lat = a.lat + (b.lat - a.lat) * f, lon = a.lon + (b.lon - a.lon) * f)
        }
        return listOf(at(from)) + points.indices.filter { along[it] > from && along[it] < to }.map { points[it] } + at(to)
    }

    /**
     * [points] in a [width] by [height] box, north up, to scale (longitude narrowed by the latitude) and centred.
     * Empty without two points apart.
     */
    fun fit(points: List<Route.Point>, width: Float, height: Float): List<Offset> {
        if (points.size < 2) return emptyList()
        val narrow = cos(Math.toRadians(points.sumOf { it.lat } / points.size))
        val xs = points.map { it.lon * narrow }
        val ys = points.map { -it.lat }
        val spanX = xs.max() - xs.min()
        val spanY = ys.max() - ys.min()
        if (spanX <= 0 && spanY <= 0) return emptyList()
        val scale = minOf(if (spanX > 0) width / spanX else Double.MAX_VALUE, if (spanY > 0) height / spanY else Double.MAX_VALUE)
        val left = (width - spanX * scale) / 2
        val top = (height - spanY * scale) / 2
        return points.indices.map { i -> Offset((left + (xs[i] - xs.min()) * scale).toFloat(), (top + (ys[i] - ys.min()) * scale).toFloat()) }
    }
}

/**
 * The card for [card], at [StoryWidth] by [StoryHeight] in whatever density it's given: over [photo] with the dark
 * fade, else lit from below in its colour, and a run's [route] as a glowing white line when there is one.
 */
@Composable
internal fun StoryCardView(card: StoryCard, photo: ImageBitmap?, route: List<Route.Point>, modifier: Modifier = Modifier) {
    Box(modifier.size(StoryWidth, StoryHeight).background(EdgeSurface)) {
        if (photo != null) {
            Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            PhotoFade()
        } else StoryGlow(storyColor(card))
        // In the picture zone, between the header and the stack at the foot.
        if (route.size >= 2) {
            val box = Modifier.fillMaxWidth().padding(horizontal = StoryRouteInset).padding(top = StoryRouteTop).height(StoryRouteHeight)
            RouteLine(route, box)
        }
        Box(Modifier.fillMaxSize().padding(StoryGap.Part)) {
            BrandRow(card.date)
            StoryStack(card, modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}

/** The three gaps on a card (mockups/share-cards-system.html): inside a part, stats to chips, and between parts. */
private object StoryGap {
    val Inner = 8.dp
    val Near = 16.dp
    val Part = 24.dp
}

/** Where a run's route sits in the picture zone: its top, height and side insets. */
private val StoryRouteTop = 76.dp
private val StoryRouteHeight = 232.dp
private val StoryRouteInset = 48.dp

/** How wide the dotted number may be before it shrinks, leaving room for its unit. */
private val StoryHeroRoom = 230.dp

/**
 * Everything a card says, as one block on the foot, left aligned: the title over the number in dots, the kind's own
 * picture of the session, the stat row, then the chips.
 */
@Composable
private fun StoryStack(card: StoryCard, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StoryGap.Part)) {
        Column(verticalArrangement = Arrangement.spacedBy(StoryGap.Inner)) {
            Text(card.title, color = Color.White, style = GlowType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HeroValue(card.value, card.unit, dot = DotSize.Detail, gap = 10.dp, maxWidth = StoryHeroRoom)
        }
        StoryDetail(card)
        if (card.stats.isNotEmpty() || card.chips.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(StoryGap.Near)) {
                if (card.stats.isNotEmpty()) StatRow(card.stats)
                if (card.chips.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(StoryGap.Inner)) { card.chips.forEach { StoryChipView(it) } }
                }
            }
        }
    }
}

/** The one picture of the session a kind draws between its number and its figures; a run and a game draw none. */
@Composable
private fun StoryDetail(card: StoryCard) {
    when (card) {
        is StoryCard.Lift -> if (card.rows.isNotEmpty()) Column {
            card.rows.forEachIndexed { i, (name, text) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                Row(Modifier.fillMaxWidth().padding(vertical = StoryGap.Inner), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name, color = Color.White, style = GlowType.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    Text(text, color = Color.White.copy(alpha = 0.8f), style = GlowType.body, maxLines = 1)
                }
            }
        }
        // Time in each zone as one strip, its ends rounded.
        is StoryCard.Indoor -> if (card.zones.any { it > 0 }) {
            ZoneBar(card.zones, ZONE_COLORS, height = 10.dp, gap = 3.dp, corner = 2.dp, ends = 5.dp)
        }
        is StoryCard.Rounds -> if (card.peaks.isNotEmpty()) {
            val heartZones = LocalHeartZones.current
            // Each round's peak in its zone's colour, white once it reached the top zone.
            fun colorOf(bpm: Int) = WorkoutRecorder.liveZone(bpm, card.maxHeartRate, heartZones)?.takeIf { it < ZONE_COLORS.lastIndex }
            PeakBars(card.peaks, { bpm -> colorOf(bpm)?.let { ZONE_COLORS[it] } ?: Color.White })
        }
        // The heart rate through the session as one soft white line.
        is StoryCard.Calm -> if (card.heart.isNotEmpty()) {
            PlainLine(card.heart, Color.White.copy(alpha = 0.55f), 40.dp, smooth = false, stroke = 2.dp)
        }
        is StoryCard.Week -> WeekDots(card.days, Color.White, dot = 34.dp)
        is StoryCard.Run, is StoryCard.Game -> Unit
    }
}

/** Two or three figures in equal columns, a thin rule between them: the value large with its unit, the label under. */
@Composable
// The figures arrive whole with each card and never change in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
private fun StatRow(stats: List<StoryStat>) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        stats.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.padding(end = StoryGap.Near).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.18f)))
            Column(Modifier.weight(1f)) {
                // The value and its unit as one line on one baseline; a long one ("10:05 /km") shrinks the value to fit.
                val text = buildAnnotatedString {
                    append(s.value)
                    s.unit?.let { withStyle(GlowType.body.toSpanStyle()) { append(" $it") } }
                }
                // Kept at the full line's height, so a shrunk value's label lines up with its neighbours'.
                val line = with(LocalDensity.current) { GlowType.storyStat.lineHeight.toDp() }
                Box(Modifier.height(line), contentAlignment = Alignment.BottomStart) {
                    BasicText(
                        text, style = GlowType.storyStat.copy(color = Color.White), maxLines = 1, softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = StatMinSize, maxFontSize = GlowType.storyStat.fontSize),
                    )
                }
                Text(
                    s.label, color = Color.White.copy(alpha = StoryDim), style = GlowType.secondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The smallest a stat's value shrinks to before its column is full. */
private val StatMinSize = 20.sp

/** A card's light: its kind's colour, as on Choose and Ready, and the week's goal green. */
private fun storyColor(card: StoryCard): Color = when (card) {
    is StoryCard.Week -> MetricColors.good
    else -> familyColor(card.family)
}

/** How strong a card's light is at its foot, the same for every kind. */
private const val STORY_LIGHT = 0.95f

/** Where the board's light sits, in card heights from the top, and its size in card widths and heights. */
private const val STORY_LIGHT_BELOW = 1.12f
private const val STORY_LIGHT_WIDTH = 1.3f
private const val STORY_LIGHT_HEIGHT = 0.65f

/** The light's middle stop, as a share of its strength, and its fixed rim before it fades out. */
private const val STORY_LIGHT_MID_AT = 0.38f
private const val STORY_LIGHT_MID = 0.47f
private const val STORY_LIGHT_RIM_AT = 0.7f
private const val STORY_LIGHT_RIM = 0.08f

/**
 * The board's light from below: an ellipse wider than the card centred under its foot, from [strength] of [color] to
 * black at its edge.
 */
@Composable
private fun StoryGlow(color: Color, strength: Float = STORY_LIGHT) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawCardLight(
                color, Offset(size.width / 2, size.height * STORY_LIGHT_BELOW),
                size.width * STORY_LIGHT_WIDTH, size.height * STORY_LIGHT_HEIGHT,
                listOf(0f to strength, STORY_LIGHT_MID_AT to strength * STORY_LIGHT_MID, STORY_LIGHT_RIM_AT to STORY_LIGHT_RIM),
            )
        },
    )
}

/**
 * The dark fade over a photo: down from the top, where every card has the wordmark, and up from the foot, high enough
 * for the tallest stack.
 */
@Composable
private fun PhotoFade() {
    Box(Modifier.fillMaxSize()) {
        Box(
            // Deep enough that the wordmark's red reads over a bright photo.
            Modifier.fillMaxWidth().fillMaxHeight(0.3f)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.62f).background(
                Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.85f)),
            ),
        )
    }
}

/** The route as a white line with a soft white glow round it, fitted into the space it's given. */
@Composable
private fun RouteLine(route: List<Route.Point>, modifier: Modifier) {
    Canvas(modifier) {
        val points = StoryRouteShape.fit(route, size.width, size.height)
        if (points.size < 2) return@Canvas
        val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
        // The glow: one blurred stroke under the line, so it fades smoothly instead of in steps.
        drawIntoCanvas { canvas ->
            val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 8.dp.toPx()
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
                color = android.graphics.Color.argb(150, 255, 255, 255)
                maskFilter = android.graphics.BlurMaskFilter(8.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            canvas.nativeCanvas.drawPath(path.asAndroidPath(), glow)
        }
        drawPath(path, Color.White, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The top row: the wordmark on the left, always at full white, and [date] on the right. The light rises from the foot
 * and a photo is darkened at the top, so the red reads on every card.
 */
@Composable
private fun BrandRow(date: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Wordmark() }
        Text(date, color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, maxLines = 1)
    }
}

/** A best, the weekly goal or the weeks in a row on a dark pill, its icon in its colour. */
@Composable
private fun StoryChipView(chip: StoryChip) {
    val (icon, tint) = if (chip.best) AppIcons.FlagCheckered to MetricColors.calories else AppIcons.Target to if (chip.met) MetricColors.good else Color.White
    Row(
        Modifier.height(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Text(chip.text, color = Color.White, style = ChipText, maxLines = 1, modifier = Modifier.padding(start = 7.dp))
    }
}

private val ChipText = GlowType.secondary.copy(fontWeight = FontWeight.Medium)

/** White for a card's dimmer lines, as on the boards. */
private const val StoryDim = 0.7f
